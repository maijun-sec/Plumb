#!/usr/bin/env python3
"""Prove that the measurement gate bites.

A gate that checks nothing reports success. This script breaks the
measurement on purpose -- one defect at a time -- and checks that the gate
FAILS each time. A mutation that survives is a hole in the gate.

    python scripts/mutate_juliet_measurement.py
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MEASUREMENT = REPO / "juliet-coverage" / "scripts" / "measurement"
GATE = REPO / "scripts" / "check_juliet_measurement.py"


def run_gate() -> bool:
    """True when the gate passes."""
    proc = subprocess.run(
        [sys.executable, str(GATE)],
        capture_output=True,
        text=True,
        cwd=str(REPO),
    )
    return proc.returncode == 0


# Each mutation is (name, file to patch, original text, replacement text).
MUTATIONS = [
    (
        "a method's span is dropped",
        MEASUREMENT / "measure.py",
        "        m = DECL.match(stripped) or DECL_WITH_COMMENT.match(stripped)\n"
        "        if not m:\n"
        "            continue\n"
        "        name = m.group(\"name\")",
        "        m = DECL.match(stripped) or DECL_WITH_COMMENT.match(stripped)\n"
        "        if not m:\n"
        "            continue\n"
        "        name = m.group(\"name\")\n"
        "        if name == 'helperBad':\n"
        "            continue",
    ),
    (
        "a declaration with a trailing comment stops matching",
        MEASUREMENT / "measure.py",
        "        m = DECL.match(stripped) or DECL_WITH_COMMENT.match(stripped)",
        "        m = DECL.match(stripped)",
    ),
    (
        "the helper is attributed to nobody",
        MEASUREMENT / "measure.py",
        "                    out.setdefault(helper, method)",
        "                    pass",
    ),
    (
        "a helper is attributed to any method, not just bad*/good*",
        MEASUREMENT / "measure.py",
        "        if not (method.startswith(\"bad\") or method.startswith(\"good\")):\n            continue",
        "        pass",
    ),
]


def main() -> int:
    if not run_gate():
        print("FAIL: the gate does not pass on the unmodified tree")
        return 1

    survivors = []
    for name, path, original, replacement in MUTATIONS:
        backup = path.read_text(encoding="utf-8")
        if original not in backup:
            print(f"SKIP  {name}: the pattern is not in {path.name}")
            continue
        try:
            path.write_text(backup.replace(original, replacement, 1), encoding="utf-8")
            if run_gate():
                survivors.append(name)
                print(f"SURVIVED  {name}")
            else:
                print(f"KILLED    {name}")
        finally:
            path.write_text(backup, encoding="utf-8")

    if not run_gate():
        print("FAIL: the gate does not pass after the mutations were reverted")
        return 1

    if survivors:
        print()
        print(f"FAIL: {len(survivors)} mutation(s) survived")
        for s in survivors:
            print(f"  - {s}")
        return 1
    print()
    print(f"OK: all {len(MUTATIONS)} mutations were killed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
