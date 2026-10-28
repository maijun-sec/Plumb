#!/usr/bin/env python3
"""Mutation test for scripts/check_compile_options.py.

A gate that passes because it checked nothing is worse than no gate.  This
script removes an option from the table and checks that the gate notices.

The mutant is the one that matters: the option the table was missing cost
the whole classpath, and a gate that cannot see a missing option cannot
keep the table honest.

Usage:  python scripts/mutate_compile_options.py
"""
import io
import pathlib
import re
import subprocess
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent
SOURCE = (
    REPO
    / "plumb-build/plumb-build-capture/src/main/java/org/plumb/build/capture/CompilerOption.java"
)
GATE = REPO / "scripts" / "check_compile_options.py"

original = SOURCE.read_text(encoding="utf-8")


def run_gate():
    result = subprocess.run(
        [sys.executable, str(GATE)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.returncode, result.stdout


def remove_an_option(text):
    """Remove the table's entry for one option."""
    return re.sub(
        r'\n *out\.add\(\s*opt\(\s*"-source".*?\);', "", text, count=1, flags=re.S
    )


def remove_the_classpath_option(text):
    """Remove the table's entry for javac's own --class-path spelling."""
    return re.sub(
        r'\n *out\.add\(\s*opt\(\s*"--class-path".*?\);', "", text, count=1, flags=re.S
    )


def remove_the_proc_family(text):
    """Remove the table's entries for the annotation-processing switch."""
    return re.sub(
        r'\n *out\.add\(\s*opt\(\s*"-proc:none".*?\);', "", text, count=1, flags=re.S
    )


MUTANTS = [
    ("the table loses --source", remove_an_option),
    ("the table loses --class-path", remove_the_classpath_option),
    ("the table loses -proc:none", remove_the_proc_family),
]

survivors = []
no_ops = []
try:
    for name, mutate in MUTANTS:
        SOURCE.write_text(mutate(original), encoding="utf-8")

        # A mutant that did not change anything proves nothing: it would be
        # reported as "the gate let a drift through" when in fact the drift
        # was never introduced.
        if SOURCE.read_text(encoding="utf-8") == original:
            no_ops.append(name)
            print("NO-OP     %s  (the mutation did not apply)" % name)
            continue

        code, out = run_gate()
        if code == 0:
            survivors.append(name)
            print("SURVIVED  %s" % name)
        else:
            first = ""
            for line in out.splitlines():
                if line.startswith("  "):
                    first = line.strip()
                    break
            print("killed    %s  (%s)" % (name, first))
finally:
    SOURCE.write_text(original, encoding="utf-8")

print()
if no_ops:
    print("FAIL: %d mutant(s) did not apply -- they prove nothing" % len(no_ops))
    for s in no_ops:
        print("  " + s)
    sys.exit(1)
if survivors:
    print("FAIL: %d mutant(s) survived -- the gate is checking air" % len(survivors))
    for s in survivors:
        print("  " + s)
    sys.exit(1)
print("OK: all %d mutants were killed" % len(MUTANTS))
