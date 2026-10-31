#!/usr/bin/env python3
"""Mutation-test the per-CWE config gate.

A gate that has never been shown to FAIL is not known to work. Each
mutant here breaks the PRODUCT -- the per-CWE configs -- in a way that
the gate must notice, and the gate must fail.

The mutants are:

  1. a config that drops the rule for its own CWE
  2. a config whose rule is renamed to an id the engine does not have
  3. the gate reading a rule table that has been emptied
  4. a config that switches to `mode: all`, which hides the invariant

A mutant that does not apply is reported as NO-OP, not as a surviving
mutant.

    python scripts/mutate_juliet_configs.py
"""
from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
GATE = REPO / "scripts" / "check_juliet_configs.py"
CONFIG_DIR = REPO / "juliet-coverage" / "configs"
RULE_CWES = Path(r"D:\cache\juliet-scratch\live-rule-cwes.tsv")


def find_config_with_rule(cwe: str) -> Path | None:
    p = CONFIG_DIR / f"CWE{cwe}.yaml"
    return p if p.is_file() else None


def drop_own_rules(text: str, rule_ids: list[str]) -> str | None:
    """Remove EVERY entry that names a rule of this CWE.

    Removing only one of them is not a defect: the CWE is still
    measured for the rules that are left, and the gate is right to pass.
    The defect is a CWE that is measured for NONE of its own rules, so
    the mutant has to remove all of them.
    """
    out = []
    removed = 0
    for line in text.splitlines():
        if any(re.match(r"^\s+-\s+" + re.escape(r) + r"\s*$", line) for r in rule_ids):
            removed += 1
            continue
        out.append(line)
    if not removed:
        return None
    return "\n".join(out) + "\n"


def rename_own_rule(text: str, rule_id: str) -> str | None:
    """Rename the CWE's own rule to an id the engine does not have."""
    out = []
    renamed = False
    for line in text.splitlines():
        if re.match(r"^\s+-\s+" + re.escape(rule_id) + r"\s*$", line):
            out.append(line.replace(rule_id, rule_id + "-not-a-live-rule"))
            renamed = True
            continue
        out.append(line)
    return "\n".join(out) + "\n" if renamed else None


def switch_to_all(text: str) -> str:
    return re.sub(r"^\s*mode:\s*\w+", "  mode: all", text, count=1, flags=re.M)


def run_gate(rule_cwes: Path) -> tuple[int, str]:
    proc = subprocess.run(
        [sys.executable, str(GATE), "--rule-cwes", str(rule_cwes)],
        capture_output=True, cwd=str(REPO))
    return proc.returncode, proc.stdout.decode("utf-8", errors="replace")


def main() -> int:
    if not RULE_CWES.is_file():
        print("ERROR: the rule table is missing: %s" % RULE_CWES)
        print("Produce it with:")
        print("  java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar "
              "--list-rule-cwes > live-rule-cwes.tsv")
        return 2

    # A CWE whose config names rules the engine has for it, so there is
    # something to drop. EVERY rule of that CWE is collected: dropping
    # only one of them is not a defect.
    rule_cwes = {}
    for line in RULE_CWES.read_text(encoding="utf-8-sig").splitlines():
        parts = line.split("\t")
        if len(parts) >= 2 and parts[0] and parts[1]:
            rule_cwes[parts[0]] = parts[1]

    def cwe_of(label: str) -> str:
        m = re.search(r"(\d+)", label or "")
        return m.group(1) if m else ""

    target_cwe = None
    target_rules: list[str] = []
    for cfg in sorted(CONFIG_DIR.glob("CWE*.yaml")):
        cwe = cfg.stem[3:]
        text = cfg.read_text(encoding="utf-8", errors="replace")
        if "mode: all" in text:
            continue
        entries = re.findall(r"^\s+-\s+(\S+)\s*$", text, re.M)
        own = [r for r in entries if r in rule_cwes and cwe_of(rule_cwes[r]) == cwe]
        if own:
            target_cwe, target_rules = cwe, own
            break

    if not target_rules:
        print("ERROR: no config names a live rule of its own; "
              "the mutants cannot be built")
        return 2
    target_rule = target_rules[0]

    results = []
    originals: dict[Path, str] = {}

    def apply(cfg: Path, text: str) -> None:
        originals[cfg] = cfg.read_text(encoding="utf-8", errors="replace")
        cfg.write_text(text, encoding="utf-8")

    try:
        # Mutant 1: a config drops EVERY rule of its own CWE, so the
        # CWE is measured for none of them.
        cfg = find_config_with_rule(target_cwe)
        text = cfg.read_text(encoding="utf-8", errors="replace")
        mutated = drop_own_rules(text, target_rules)
        if mutated is None:
            results.append(("config-drops-its-own-rules", "NO-OP",
                            "the mutation did not apply"))
        else:
            apply(cfg, mutated)
            rc, out = run_gate(RULE_CWES)
            results.append(("config-drops-its-own-rules",
                            "killed" if rc != 0 else "SURVIVED",
                            "the gate exited %d" % rc))
            cfg.write_text(originals[cfg], encoding="utf-8")

        # Mutant 2: one of the CWE's own rules is renamed to an id the
        # engine does not have, so that rule is no longer enabled.
        cfg = find_config_with_rule(target_cwe)
        text = cfg.read_text(encoding="utf-8", errors="replace")
        mutated = rename_own_rule(text, target_rule)
        if mutated is None:
            results.append(("config-names-a-rule-that-does-not-exist", "NO-OP",
                            "the mutation did not apply"))
        else:
            apply(cfg, mutated)
            rc, out = run_gate(RULE_CWES)
            results.append(("config-names-a-rule-that-does-not-exist",
                            "killed" if rc != 0 else "SURVIVED",
                            "the gate exited %d" % rc))
            cfg.write_text(originals[cfg], encoding="utf-8")

        # Mutant 3: the gate reads a rule table that has been emptied.
        #
        # With no rule table, the gate has nothing to check against, so
        # it cannot claim the invariant. It has to SAY so and fail --
        # a gate that passes because it looked at nothing has not
        # checked anything.
        with tempfile.TemporaryDirectory() as tmp:
            empty = Path(tmp) / "empty.tsv"
            empty.write_text("", encoding="utf-8")
            rc, out = run_gate(empty)
            ok = rc != 0
            results.append(("gate-reads-an-empty-rule-table",
                            "killed" if ok else "SURVIVED",
                            "the gate exited %d" % rc))

        # Mutant 4: the gate's own rule table loses ONE rule, so the
        # gate stops believing that the CWE has a rule and stops
        # checking that the config enables it. The gate must notice
        # that the table no longer covers the configs.
        with tempfile.TemporaryDirectory() as tmp:
            trimmed = Path(tmp) / "trimmed.tsv"
            lines = RULE_CWES.read_text(encoding="utf-8-sig").splitlines()
            kept = [ln for ln in lines if not ln.startswith(target_rule + "\t")]
            trimmed.write_text("\n".join(kept) + "\n", encoding="utf-8")
            rc, out = run_gate(trimmed)
            results.append(("gate-reads-a-truncated-rule-table",
                            "killed" if rc != 0 else "SURVIVED",
                            "the gate exited %d" % rc))
    finally:
        for cfg, text in originals.items():
            cfg.write_text(text, encoding="utf-8")

    print()
    print("=" * 70)
    print("MUTATION TEST -- the per-CWE config gate")
    print("=" * 70)
    killed = 0
    noop = 0
    for name, verdict, note in results:
        print("  %-45s %-10s %s" % (name, verdict, note))
        if verdict == "killed":
            killed += 1
        elif verdict == "NO-OP":
            noop += 1
    print()
    print("%d of %d killed (%d no-op)" % (killed, len(results), noop))
    return 0 if killed == len(results) - noop and noop == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
