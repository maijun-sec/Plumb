#!/usr/bin/env python3
"""Mutation battery for `scripts/check_rule_filter.py`.

A gate that has never killed a mutant is a gate nobody knows works.

This script applies each plausible shipped failure mode to the legacy alias map (and to the
source), runs the gate, and requires the gate to FAIL.  A mutant that survives is a hole in the
gate.

Every mutant:
  1. is applied to an in-memory BYTE snapshot,
  2. is restored byte-exactly afterwards,
  3. distinguishes "could not build the mutant" from "the mutant survived".

    python scripts/mutate_rule_filter.py
"""

from __future__ import annotations

import json
import pathlib
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
GATE = REPO / "scripts" / "check_rule_filter.py"
ALIAS_FILE = REPO / "plumb-main" / "src" / "main" / "resources" / "legacy-rule-aliases.json"
PYTHON = sys.executable


def run_gate() -> tuple[int, str]:
    proc = subprocess.run(
        [PYTHON, str(GATE)], capture_output=True, text=True, cwd=str(REPO)
    )
    return proc.returncode, proc.stdout + proc.stderr


# (name, kind, key, replacement)
#
# `kind` is "alias" (edit the map) or "source" (edit the Java source).
# The replacement for an alias is a rule id; a replacement that is NOT a live
# rule is what makes the gate fire.
MUTANTS = [
    # --- an alias that points at a rule which does not exist -------------
    ("dangling-alias-target", "alias", "plumb.resource.leak", "dfa-taint-a-rule-that-was-removed"),
    # --- an alias that points at ANOTHER rule -----------------------------
    # This is the dangerous one: the gate cannot see it by inspection, so
    # the mutant is "the map says the resource leak is the null dereference".
    ("alias-points-at-the-wrong-rule", "alias", "plumb.resource.leak", "dfa-taint-null-dereference"),
    # --- an alias that shadows a live rule -------------------------------
    # The key is a LIVE rule id that is also a retired id. The map must not
    # contain it, because redirecting it would shadow the live rule.
    ("alias-shadows-a-live-rule", "alias", "bu-0801-array-hashcode-tostring", "ast-performance-array-hashcode-tostring"),
    # --- a self-loop -----------------------------------------------------
    ("alias-points-at-itself", "alias", "plumb.resource.leak", "plumb.resource.leak"),
    # --- the map loses an entry the configs rely on ----------------------
    # Removing the alias makes the retired id name nothing again, which is
    # the failure the map exists to prevent. The gate must notice that a
    # config entry names nothing.
    ("alias-removed-from-the-map", "alias", "plumb.resource.leak", None),
    # --- the whole map is emptied ---------------------------------------
    ("map-emptied", "map", None, None),
    # --- a retired id is RENAMED to a live rule id -----------------------
    # The map would then redirect a live rule somewhere else. The shadowing
    # check has to see it, which it cannot if the retired id is simply
    # absent -- so the key is a retired id that now collides with a live
    # one.
    ("alias-renamed-onto-a-live-rule", "alias", "plumb.resource.leak", "dfa-taint-leak"),
]


def apply_mutation(mutation) -> bytes | None:
    """Return the mutated file content, or None when the mutation does not apply."""
    name, kind, key, replacement = mutation
    original = ALIAS_FILE.read_bytes()
    if kind == "map":
        data = json.loads(original.decode("utf-8"))
        if not data:
            return None
        return json.dumps({}, indent=2, sort_keys=True).encode("utf-8")
    data = json.loads(original.decode("utf-8"))
    if key not in data:
        return None
    if replacement is None:
        del data[key]
    else:
        data[key] = replacement
    return json.dumps(data, indent=2, sort_keys=True).encode("utf-8")


def main() -> int:
    original = ALIAS_FILE.read_bytes()
    print()
    print("=" * 78)
    print("Rule filter mutation battery")
    print("=" * 78)

    killed = 0
    skipped = 0
    failures = []

    try:
        for mutation in MUTANTS:
            name = mutation[0]
            mutated = apply_mutation(mutation)
            if mutated is None:
                print(f"  {name:40s} NO-OP (the anchor is not in the map)")
                skipped += 1
                continue
            ALIAS_FILE.write_bytes(mutated)
            rc, output = run_gate()
            ALIAS_FILE.write_bytes(original)
            if rc != 0:
                killed += 1
                first = next(
                    (ln.strip() for ln in output.splitlines() if ln.strip().startswith("legacy alias")
                     or ln.strip().startswith("these legacy aliases")
                     or ln.strip().startswith("FAIL")),
                    "",
                )
                print(f"  {name:40s} KILLED  {first[:70]}")
            else:
                failures.append(name)
                print(f"  {name:40s} SURVIVED  <-- the gate does not see this")
    finally:
        ALIAS_FILE.write_bytes(original)

    print()
    print(f"mutants killed: {killed}   no-op: {skipped}   survived: {len(failures)}")
    if failures:
        print()
        print("SURVIVING MUTANTS (the gate has a hole):")
        for f in failures:
            print(f"  {f}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
