"""Mutation harness for the deserialization-filter mitigation.

Each mutant breaks ONE decision in the tracker and the tests must catch it.
The point is not that the code is covered: it is that the tests can tell a
mitigation from a non-mitigation.  A tracker that asked only "is there a
filter?" would pass every test that had a filter, and would be wrong.

Usage:
    python mutate_deserialization_filter.py [--list]
"""

from __future__ import annotations

import argparse
import pathlib
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent

TRACKER = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa" / "taint" / "DeserializationFilterTracker.java"
CHECKER = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa" / "taint" / "TaintedCheckerBase.java"

# Each mutant: (name, file, old text, new text)
MUTANTS = [
    (
        "pattern-without-reject-all-is-still-a-mitigation",
        TRACKER,
        '    return trimmed.endsWith("!*");',
        '    return trimmed != null && !trimmed.isEmpty();  // MUTANT',
    ),
    (
        "a-lambda-that-never-rejects-is-still-a-mitigation",
        TRACKER,
        '      if (STATUS_REJECTED.equals(ins.fieldName)) {',
        '      if (ins.fieldName != null) {  // MUTANT',
    ),
    (
        "the-filter-is-not-attached-to-the-stream",
        TRACKER,
        "      restrictingStreams.put(stream.name(), \"filter\");",
        "      restrictingStreams.put(\"someone-else\", \"filter\");  // MUTANT",
    ),
    (
        "the-mitigation-suppresses-every-sink-family",
        CHECKER,
        "    if (isDeserializationFamily(family) && deserializationFilters.restricts(av)) {",
        "    if (deserializationFilters.restricts(av)) {  // MUTANT",
    ),
    (
        "the-mitigation-is-never-applied",
        CHECKER,
        "    if (isDeserializationFamily(family) && deserializationFilters.restricts(av)) {",
        "    if (false && isDeserializationFamily(family) && deserializationFilters.restricts(av)) {  // MUTANT",
    ),
]


def run_tests() -> bool:
    """Run the deserialization filter tests. True when they pass."""
    result = subprocess.run(
        [
            "mvn.cmd",
            "-o",
            "-q",
            "-pl",
            "plumb-dfa",
            "test",
            "-Dtest=DeserializationFilterTest",
            "-DfailIfNoTests=false",
        ],
        cwd=REPO,
        capture_output=True,
        text=True,
    )
    return result.returncode == 0


def apply_mutant(path: pathlib.Path, old: str, new: str) -> bool:
    """Apply a mutation. False when the anchor text was not found (NO-OP)."""
    original = path.read_text(encoding="utf-8")
    if old not in original:
        return False
    path.write_text(original.replace(old, new, 1), encoding="utf-8")
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--list", action="store_true", help="list the mutants and exit")
    args = parser.parse_args()

    if args.list:
        for name, path, _old, _new in MUTANTS:
            print(f"{name}  ({path.relative_to(REPO)})")
        return 0

    if not run_tests():
        print("ERROR: the tests do not pass before any mutation, so a survivor proves nothing.")
        return 1

    survivors = []
    noops = []
    for name, path, old, new in MUTANTS:
        backup = path.read_text(encoding="utf-8")
        try:
            if not apply_mutant(path, old, new):
                noops.append(name)
                print(f"NO-OP   {name}: the anchor text was not found, so nothing changed")
                continue
            if run_tests():
                survivors.append(name)
                print(f"SURVIVED {name}")
            else:
                print(f"KILLED   {name}")
        finally:
            path.write_text(backup, encoding="utf-8")

    print()
    print(f"mutants: {len(MUTANTS)}  killed: {len(MUTANTS) - len(survivors) - len(noops)}"
          f"  survived: {len(survivors)}  no-op: {len(noops)}")
    if survivors:
        print("survivors: " + ", ".join(survivors))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
