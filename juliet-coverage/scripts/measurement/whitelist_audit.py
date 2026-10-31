#!/usr/bin/env python3
"""Audit the per-CWE whitelists against the LIVE engine.

THE QUESTION

For each Juliet CWE, the engine has some rules whose own CWE label names
it. The per-CWE config enables a handful of rules. Two things can then
be wrong, and they are NOT the same thing:

  (a) CONFIGURATION DEFECT -- the config does not enable the rule that
      covers the CWE under test, so the measurement reads "recall 0.000"
      while the rule was never allowed to run.
  (b) RULE DEFECT -- the rule was enabled and it did not find the
      defect.

Telling them apart needs to know WHICH RULES A WHITELIST ACTUALLY
SELECTS, and that answer belongs to the engine's own filter -- the one
that strips the `plumb.` prefix, the `pl-<NNNN>-` numeric prefix, and
the leading engine/domain tokens, and that refuses an ambiguous bare
slug. So this script is a PORT of that filter, and the port is
VALIDATED BY THE SCAN: every rule that actually fired in a CWE's scan
must be a rule the port says the whitelist selects. A port that
disagrees with the engine fails loudly instead of quietly reporting the
wrong cause.

    python whitelist_audit.py --findings <dir> --rule-cwes <tsv> --out <dir>
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent.parent
sys.path.insert(0, str(HERE))
import measure  # noqa: E402

ALIAS_FILE = REPO / "plumb-main" / "src" / "main" / "resources" / "legacy-rule-aliases.json"

# The engine and domain tokens the rule id peeling knows about.
# Copied from ScanRunner.ENGINE_AND_DOMAIN_TOKENS -- the filter's
# vocabulary IS the filter, so a port with its own vocabulary would
# compute a different set of spellings and agree with nothing.
ENGINE_AND_DOMAIN_TOKENS = {
    # engines
    "ast", "dfa", "bu", "plumb", "orm", "servlet", "singleton",
    # domains
    "style", "error", "bug", "taint", "security", "naming", "performance",
    "quality", "design", "concurrency", "numeric", "boundary", "pointer",
    "resource", "info", "ai", "general",
}


def load_aliases() -> dict[str, str]:
    if not ALIAS_FILE.is_file():
        return {}
    return json.loads(ALIAS_FILE.read_text(encoding="utf-8"))


def canonical(rule_id: str, aliases: dict[str, str]) -> str:
    return aliases.get(rule_id, rule_id)


def rule_id_forms(rule_id: str, aliases: dict[str, str]) -> list[str]:
    """The spellings a rule id answers to. A port of ScanRunner.ruleIdForms."""
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


class Filter:
    """A port of the engine's whitelist filter."""

    def __init__(self, live_ids: list[str], aliases: dict[str, str]):
        self.aliases = aliases
        self.live_ids = live_ids
        # Every identity the engine's live set carries: the rule id, the
        # legacy registry name, and the checker's class name. The rule
        # ids are what --list-rule-cwes prints; the legacy names are
        # reachable through the alias table.
        identities: set[str] = set()
        for rid in live_ids:
            identities.add(rid)
            identities.add(canonical(rid, aliases))
        for alias, target in aliases.items():
            identities.add(alias)
            identities.add(canonical(target, aliases))
        self.identities = sorted(identities)
        # form -> how many identities own it
        self.form_owners: Counter = Counter()
        for ident in self.identities:
            for f in rule_id_forms(ident, aliases):
                self.form_owners[f.lower()] += 1

    def is_ambiguous(self, form: str) -> bool:
        return self.form_owners.get(form.lower(), 0) > 1

    def selects(self, rule_id: str, entries: list[str]) -> bool:
        """Whether a whitelist containing `entries` admits `rule_id`."""
        rule_forms = rule_id_forms(canonical(rule_id, self.aliases), self.aliases)
        for entry in entries:
            if not entry:
                continue
            if entry in rule_forms:
                return True
            canonical_entry = canonical(entry, self.aliases)
            for entry_form in rule_id_forms(canonical_entry, self.aliases):
                if entry_form not in rule_forms:
                    continue
                if self.is_ambiguous(entry_form):
                    continue
                return True
            bare = canonical_entry
            if bare.startswith("ast-style-"):
                bare = bare[len("ast-style-"):]
            if bare.endswith("-style"):
                bare = bare[: -len("-style")]
            if bare in rule_forms and not self.is_ambiguous(bare):
                return True
        return False

    def selected_rules(self, entries: list[str]) -> set[str]:
        return {rid for rid in self.live_ids if self.selects(rid, entries)}

    def selected_all(self) -> set[str]:
        """Every live rule -- what a `mode: all` config selects."""
        return set(self.live_ids)


def read_config(path: Path) -> tuple[str, list[str]]:
    mode = "unknown"
    rules: list[str] = []
    in_enabled = False
    for raw in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw.rstrip()
        if not line.strip() or line.strip().startswith("#"):
            continue
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
        m = re.match(r"^\s*mode\s*:\s*(\w+)", line)
        if m:
            mode = m.group(1)
    return mode, rules


def load_live_rule_cwes(path: Path) -> dict[str, list[str]]:
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
        out[parts[0].strip()] = [measure.norm_cwe(c) for c in parts[1:] if c]
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--findings", required=True)
    ap.add_argument("--rule-cwes", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    findings_dir = Path(args.findings)
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    rule_cwes = load_live_rule_cwes(Path(args.rule_cwes))
    aliases = load_aliases()
    flt = Filter(sorted(rule_cwes), aliases)

    rules_for_cwe: dict[str, set[str]] = defaultdict(set)
    for rid, cwes in rule_cwes.items():
        for c in cwes:
            rules_for_cwe[c].add(rid)

    config_dir = REPO / "juliet-coverage" / "configs"
    rows = []
    violations = []
    for cfg in sorted(config_dir.glob("CWE*.yaml")):
        cwe = cfg.stem[3:]
        mode, entries = read_config(cfg)
        all_mode = mode == "all"
        selected = flt.selected_all() if all_mode else flt.selected_rules(entries)
        # A `mode: all` config enables EVERY rule, so "the CWE's own
        # rules" is the whole catalogue and the question "did the config
        # enable them" is vacuous. Such a config is not a configuration
        # defect; it is a deliberate no-whitelist measurement.
        all_mode = mode == "all"
        # The CWE's own rules are always reported -- they are what says
        # whether the engine has a rule for this CWE at all.
        #
        # But the "did the config enable them" verdict is only meaningful
        # for a whitelist. A `mode: all` config enables EVERY rule, so
        # the question is vacuous: there is no whitelist to get wrong.
        own = sorted(rules_for_cwe.get(cwe, []))
        missing = [] if all_mode else [r for r in own if r not in selected]
        dead = [e for e in entries
                if not any(flt.selects(rid, [e]) for rid in flt.live_ids)]
        off_cwe = sorted(r for r in selected
                         if rule_cwes.get(r) and cwe not in rule_cwes[r])

        # Validate the port against what actually fired.
        fired: set[str] = set()
        nd = findings_dir / f"CWE{cwe}.ndjson"
        if nd.is_file():
            with nd.open("r", encoding="utf-8", errors="replace") as fh:
                for raw in fh:
                    raw = raw.strip()
                    if not raw:
                        continue
                    try:
                        d = json.loads(raw)
                    except json.JSONDecodeError:
                        continue
                    rid = d.get("rule_id")
                    if rid:
                        fired.add(rid)
        unexplained = sorted(r for r in fired if r not in selected)
        # A `mode: all` config selects EVERY rule, so every rule that
        # fired is explained by construction. The port's prediction is
        # only meaningful for a whitelist.
        if all_mode:
            unexplained = []

        rows.append({
            "cwe": cwe, "mode": mode, "entries": entries,
            "selected_count": len(selected),
            "own_rules": own,
            "own_enabled": [r for r in own if r in selected],
            "own_not_enabled": missing,
            "missing_own_rules": missing,
            "dead_entries": dead,
            "off_cwe_selected": off_cwe,
            "fired": sorted(fired),
            "unexplained_fired": unexplained,
            # A CWE whose own rules are NOT enabled at all cannot say
            # anything about the rule: the rule was never allowed to run.
            # That is a CONFIGURATION gap, and its coverage number is
            # uninterpretable rather than zero.
            "config_covers_own_rules": (True if all_mode
                                       else bool([r for r in own if r in selected])),
            "has_own_rules": bool(own),
            "mode_all": all_mode,
        })
        if unexplained:
            violations.append((cwe, unexplained))
        if missing:
            violations.append((cwe, ["MISSING OWN RULE: " + m for m in missing]))

    (out_dir / "whitelist-audit.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")

    print("=" * 96)
    print("PER-CWE WHITELIST AUDIT -- against the live engine")
    print("=" * 96)
    print("%-9s %-10s %8s %8s %8s %8s" % (
        "CWE", "mode", "entries", "selected", "own", "missing"))
    print("-" * 96)
    n_missing = 0
    n_dead = 0
    n_unexplained = 0
    for r in rows:
        print("%-9s %-10s %8d %8d %8d %8d" % (
            "CWE-" + r["cwe"], r["mode"], len(r["entries"]), r["selected_count"],
            len(r["own_rules"]), len(r["missing_own_rules"])))
        n_missing += len(r["missing_own_rules"])
        n_dead += len(r["dead_entries"])
        n_unexplained += len(r["unexplained_fired"])
    print("-" * 96)
    print("configs audited                : %d" % len(rows))
    print("own rules NOT enabled by config : %d" % n_missing)
    print("whitelist entries that select  : %d" % n_dead)
    print("  nothing (dead entries)")
    print("fired rules the port cannot     : %d" % n_unexplained)
    print("  explain -- the port is wrong")

    print()
    print("=" * 96)
    print("CONFIGURATION GAPS -- the config enables NONE of the CWE's own rules")
    print("  (the coverage number for these CWEs is UNINTERPRETABLE, not zero)")
    print("=" * 96)
    n_gap = 0
    for r in rows:
        if not r["has_own_rules"] or r["config_covers_own_rules"]:
            continue
        n_gap += 1
        print("  CWE-%-4s own rule(s) NOT enabled: %s" % (r["cwe"], ", ".join(r["own_rules"])))
    if not n_gap:
        print("  none")

    print()
    print("=" * 96)
    print("PARTIAL COVERAGE -- some of the CWE's own rules are not enabled")
    print("=" * 96)
    for r in rows:
        if not r["has_own_rules"] or not r["config_covers_own_rules"]:
            continue
        if not r["missing_own_rules"]:
            continue
        print("  CWE-%-4s enabled: %s" % (r["cwe"], ", ".join(r["own_enabled"])))
        print("           not enabled: %s" % ", ".join(r["missing_own_rules"]))

    print()
    print("=" * 96)
    print("DEAD WHITELIST ENTRIES -- the entry selects no rule the engine emits")
    print("=" * 96)
    any_dead = False
    for r in rows:
        if not r["dead_entries"]:
            continue
        any_dead = True
        print("  CWE-%-4s %s" % (r["cwe"], ", ".join(r["dead_entries"])))
    if not any_dead:
        print("  none")

    print()
    print("=" * 96)
    print("PORT VALIDATION -- a rule that fired but the port does not predict")
    print("=" * 96)
    if not violations or all(not v[1] or v[1][0].startswith("MISSING") for v in violations):
        print("  none -- the port agrees with the engine on every rule that fired")
    else:
        for cwe, bad in violations:
            print("  CWE-%-4s %s" % (cwe, ", ".join(bad)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
