#!/usr/bin/env python3
"""Check that the per-CWE configs do not depend on a past scan.

THE DEFECT THIS GATE EXISTS FOR

The per-CWE whitelist configs used to be generated from ONE past scan:
the rules that happened to fire on a CWE's directory on that day were
written into the config. That is circular -- the whitelist decides which
rules run, and a scan of the whitelist decided the whitelist. So a CWE
whose rule had not fired yet was written down with a whitelist that did
not contain its own rule, and could then never be measured.

The measurement found 67 of the 112 CWEs in that state: the engine
shipped a rule for the CWE, the config did not enable it, and the
coverage number said nothing about the rule.

THE INVARIANT

For every CWE that has a rule in the engine's live rule table, the
config for that CWE enables at least one of those rules.

The rule table is read from the engine's OWN `--list-rule-cwes` output,
not from a table maintained beside the engine, because the DFA rule ids
come from constructor-provided TaintRules and a source grep would miss
them.

    python scripts/check_juliet_configs.py --rule-cwes <tsv>
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
CONFIG_DIR = REPO / "juliet-coverage" / "configs"
ALIAS_FILE = REPO / "plumb-main" / "src" / "main" / "resources" / "legacy-rule-aliases.json"

ENGINE_AND_DOMAIN_TOKENS = {
    "ast", "dfa", "bu", "plumb", "orm", "servlet", "singleton",
    "style", "error", "bug", "taint", "security", "naming", "performance",
    "quality", "design", "concurrency", "numeric", "boundary", "pointer",
    "resource", "info", "ai", "general",
}

# How many rules the engine ships. The rule table the gate reads must
# have at least this many, or the gate is checking a subset and would
# pass while looking at less than the engine has.
#
# The number is a floor, not an exact count: a rule may be added later,
# and the gate must not fail for that. But a table that has LOST rules
# is a broken table, and a gate that cannot tell the difference would
# pass while checking less.
EXPECTED_RULE_COUNT = 652


def norm_cwe(label: str) -> str:
    m = re.search(r"(\d+)", label or "")
    return m.group(1) if m else ""


def load_rule_cwes(path: Path) -> dict[str, list[str]]:
    out: dict[str, list[str]] = {}
    raw = path.read_bytes()
    text = None
    for enc in ("utf-8-sig", "utf-16", "utf-8"):
        try:
            text = raw.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    if text is None:
        return out
    for line in text.splitlines():
        parts = line.rstrip("\n").split("\t")
        if not parts or not parts[0]:
            continue
        out[parts[0].strip()] = [norm_cwe(c) for c in parts[1:] if c]
    return out


def rule_cwes_from_engine() -> dict[str, list[str]]:
    """The rule table, read from the LIVE engine.

    The table is produced by the engine itself (`--list-rule-cwes`), so
    it is the engine's own answer about which rule carries which CWE.
    A table maintained beside the engine would drift from it, and a
    drifted table makes the gate check the wrong thing.

    When the jar is not built, the caller falls back to a file.
    """
    jar = REPO / "plumb-main" / "target" / "plumb-main-0.1.0-SNAPSHOT-all.jar"
    if not jar.is_file():
        return {}
    proc = subprocess.run(
        ["java", "-jar", str(jar), "--list-rule-cwes"],
        capture_output=True, cwd=str(REPO), timeout=600)
    if proc.returncode != 0:
        return {}
    out: dict[str, list[str]] = {}
    text = proc.stdout.decode("utf-8", errors="replace")
    for line in text.splitlines():
        parts = line.rstrip("\n").split("\t")
        if not parts or not parts[0]:
            continue
        out[parts[0].strip()] = [norm_cwe(c) for c in parts[1:] if c]
    return out


def load_aliases() -> dict[str, str]:
    if not ALIAS_FILE.is_file():
        return {}
    return json.loads(ALIAS_FILE.read_text(encoding="utf-8"))


def rule_id_forms(rule_id: str, aliases: dict[str, str]) -> list[str]:
    forms: list[str] = []
    if not rule_id:
        return forms
    forms.append(rule_id)
    s = rule_id
    if s.startswith("plumb."):
        s = s[len("plumb."):]
        forms.append(s)
    if s.startswith("pl-") and len(s) > 4 and s[3].isdigit():
        dash = s.find("-", 3)
        if dash > 3:
            s = s[dash + 1:]
            forms.append(s)
    rest = s
    for _ in range(4):
        dot = rest.find(".")
        dash = rest.find("-")
        cut = -1
        if dot >= 0 and (dash < 0 or dot < dash):
            cut = dot
        elif dash >= 0:
            cut = dash
        if cut <= 0:
            break
        head = rest[:cut]
        if head not in ENGINE_AND_DOMAIN_TOKENS:
            break
        rest = rest[cut + 1:]
        if rest not in forms:
            forms.append(rest)
    return forms


def read_config(path: Path) -> tuple[str, list[str]]:
    mode = "unknown"
    rules: list[str] = []
    in_enabled = False
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.rstrip()
        if not line.strip() or line.strip().startswith("#"):
            continue
        m = re.match(r"^\s*mode\s*:\s*(\w+)", line)
        if m:
            mode = m.group(1)
        if re.match(r"^\s*enabled\s*:", line):
            in_enabled = True
            continue
        if in_enabled:
            s = line.strip()
            if s.startswith("- "):
                rules.append(s[2:].strip())
                continue
            if s and not s.startswith("-"):
                in_enabled = False
    return mode, rules


def selects(rule_id: str, entries: list[str], aliases: dict[str, str],
            form_owners: dict[str, int]) -> bool:
    rule_forms = rule_id_forms(aliases.get(rule_id, rule_id), aliases)
    for entry in entries:
        if not entry:
            continue
        if entry in rule_forms:
            return True
        canonical = aliases.get(entry, entry)
        for form in rule_id_forms(canonical, aliases):
            if form not in rule_forms:
                continue
            if form_owners.get(form.lower(), 0) > 1:
                continue
            return True
        bare = canonical
        if bare.startswith("ast-style-"):
            bare = bare[len("ast-style-"):]
        if bare.endswith("-style"):
            bare = bare[: -len("-style")]
        if bare in rule_forms and form_owners.get(bare.lower(), 0) <= 1:
            return True
    return False


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--rule-cwes", default="",
                    help="a rule table file. The default reads the LIVE "
                         "engine's own --list-rule-cwes output.")
    args = ap.parse_args()

    if args.rule_cwes:
        rule_cwes = load_rule_cwes(Path(args.rule_cwes))
        source = args.rule_cwes
    else:
        rule_cwes = rule_cwes_from_engine()
        source = "the live engine (--list-rule-cwes)"
    if not rule_cwes:
        print("ERROR: the rule table is empty: %s" % source)
        print("A gate that looks at nothing has not checked anything.")
        print("Build the scanner jar first:")
        print("  mvn -o -pl plumb-main -am -DskipTests -B install")
        return 2
    aliases = load_aliases()

    identities: set[str] = set()
    for rid in rule_cwes:
        identities.add(rid)
        identities.add(aliases.get(rid, rid))
    for alias, target in aliases.items():
        identities.add(alias)
        identities.add(aliases.get(target, target))
    form_owners: dict[str, int] = defaultdict(int)
    for ident in identities:
        for f in rule_id_forms(ident, aliases):
            form_owners[f.lower()] += 1

    rules_for_cwe: dict[str, list[str]] = defaultdict(list)
    for rid, cwes in rule_cwes.items():
        for c in cwes:
            rules_for_cwe[c].append(rid)

    problems = []
    dead_entries = []
    checked = 0
    # The number of configs that carry a rule for their own CWE. A
    # number of zero means the table and the configs have drifted apart,
    # and the gate below would then check nothing.
    with_own = 0
    for cfg in sorted(CONFIG_DIR.glob("CWE*.yaml")):
        cwe = cfg.stem[3:]
        mode, entries = read_config(cfg)
        if mode == "all":
            # A `mode: all` config enables every rule, so the invariant
            # holds by construction.
            continue
        checked += 1
        own = rules_for_cwe.get(cwe, [])
        if not own:
            continue
        with_own += 1
        enabled = [r for r in own if selects(r, entries, aliases, form_owners)]
        missing = [r for r in own if r not in enabled]
        if missing:
            problems.append((cwe, missing, entries))
        for e in entries:
            if not any(selects(rid, [e], aliases, form_owners) for rid in rule_cwes):
                dead_entries.append((cwe, e))

    print("per-CWE configs checked      : %d" % checked)
    print("configs with a rule of their own: %d" % with_own)
    print("a config that drops a rule of its own: %d" % len(problems))
    print("a whitelist entry that is dead: %d" % len(dead_entries))
    for cwe, missing, entries in problems:
        print("  CWE-%s does not enable %s" % (cwe, ", ".join(missing)))
    for cwe, e in dead_entries:
        print("  CWE-%s: the entry %r selects no rule the engine emits" % (cwe, e))

    if with_own == 0:
        print()
        print("ERROR: no config has a rule of its own, so the invariant was "
              "never checked. The rule table and the configs have drifted "
              "apart.")
        return 2

    # The rule table has to COVER the configs.
    #
    # A rule table that has lost a rule makes the CWE look like a CWE
    # with no rule at all, and the gate then skips it -- so a truncated
    # table would make the gate pass while checking less. The engine's
    # own rule count is the reference: the table must have as many rules
    # as the engine reports.
    table_rules = len(rule_cwes)
    expected = EXPECTED_RULE_COUNT
    if expected and table_rules < expected:
        print()
        print("ERROR: the rule table has %d rules and the engine reports %d. "
              "A truncated table makes the gate skip the CWEs whose rules "
              "are missing from it."
              % (table_rules, expected))
        return 2

    if problems:
        print()
        print("A CWE whose own rules the config does not enable cannot be "
              "measured for those rules: they were never allowed to run, and "
              "the coverage number says nothing about them.")
        return 1
    print()
    print("OK: every CWE with a rule in the engine enables every one of them.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
