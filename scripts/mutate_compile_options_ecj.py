#!/usr/bin/env python3
"""Mutation test for scripts/check_compile_options_ecj.py.

A gate that passes because it checked nothing is worse than no gate.  This
script breaks the ECJ option table in the ways that would actually cost a
capture something, and checks that the gate notices.

The mutants are the four gaps the gate was written for, plus the attribution
mistake that a name-only check would miss.

Usage:  python scripts/mutate_compile_options_ecj.py
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
GATE = REPO / "scripts" / "check_compile_options_ecj.py"

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


def remove_the_entry(text, spelling):
    """Remove EVERY entry for one option spelling.

    <p>Every entry, not just the first: two compilers may spell an option the
    same way -- ECJ and sjavac both have `-copy` -- and the capture's index
    holds one option per name, so removing only one of them leaves the other
    in place and the mutation never happens.
    """
    pattern = r'\n *out\.add\(opt\(\s*"%s".*?\);' % re.escape(spelling)
    return re.sub(pattern, "", text, flags=re.S)


def attribute_to(text, spelling, compiler):
    """Change the compiler an option is attributed to."""
    pattern = r'(out\.add\(opt\(\s*"%s".*?Effect\.\w+,\s*)(null|Compiler\.\w+)(\))' % re.escape(
        spelling
    )
    return re.sub(pattern, r"\1Compiler." + compiler + r"\3", text, count=1, flags=re.S)


MUTANTS = [
    # The four gaps the gate was written for.  `-annotationpath` is the one
    # that cost the whole annotation path: the option was unknown, and the
    # path that followed it was not a source file either, so both vanished.
    ("the table loses -annotationpath", lambda t: remove_the_entry(t, "-annotationpath")),
    ("the table loses -warn:none", lambda t: remove_the_entry(t, "-warn:none")),
    ("the table loses -?:warn", lambda t: remove_the_entry(t, "-?:warn")),
    ("the table loses -help:warn", lambda t: remove_the_entry(t, "-help:warn")),
    # An option ECJ has, attributed to another compiler: ECJ's lookup would
    # reject it, and the value that followed it would be read as a source.
    #
    # The option has to be one the compiler LISTS.  ECJ accepts `-src` and
    # `-profile` but does not advertise them in `-help`, so a mutant built on
    # them would be invisible to a gate that reads the help -- it would report
    # "survived" when in fact the gate never had anything to check.
    ("an ECJ option is claimed for sjavac", lambda t: attribute_to(t, "-annotationpath", "SJAVAC")),
    ("an ECJ option is claimed for javac", lambda t: attribute_to(t, "-warn:none", "JAVAC")),
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
