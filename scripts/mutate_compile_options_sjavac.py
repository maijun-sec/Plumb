#!/usr/bin/env python3
"""Mutation test for scripts/check_compile_options_sjavac.py.

A gate that passes because it checked nothing is worse than no gate.  This
script breaks the sjavac option table in the ways that would actually cost a
capture something, and checks that the gate notices.

The mutants are chosen for what they cost, not for variety:

  * losing an sjavac-only option entirely;
  * attributing an option to sjavac that the real sjavac FORWARDS to javac --
    which would make javac's own lookup reject it;
  * attributing an option to javac that the real sjavac CONSUMES -- which
    would make sjavac's lookup reject it.

Usage:  python scripts/mutate_compile_options_sjavac.py
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
GATE = REPO / "scripts" / "check_compile_options_sjavac.py"

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
    same way -- ECJ and sjavac both have {@code -copy} -- and the capture's
    index holds one option per name, so removing only one of them leaves the
    other in place and the mutation never happens.
    """
    pattern = r'\n *out\.add\(\s*opt\(\s*"%s".*?\);' % re.escape(spelling)
    return re.sub(pattern, "", text, flags=re.S)


def attribute_to(text, spelling, compiler):
    """Change the compiler an option is attributed to."""
    pattern = r'(out\.add\(\s*opt\(\s*"%s".*?Effect\.\w+,\s*)(null|Compiler\.\w+)(\))' % re.escape(
        spelling
    )
    return re.sub(pattern, r"\1Compiler." + compiler + r"\3", text, count=1, flags=re.S)


MUTANTS = [
    # The sjavac-only options.  Losing one means the option is unknown, AND
    # the value that follows it is read as a source file.
    ("the table loses --state-dir=", lambda t: remove_the_entry(t, "--state-dir=")),
    ("the table loses --permit-artifact=", lambda t: remove_the_entry(t, "--permit-artifact=")),
    ("the table loses --compare-found-sources", lambda t: remove_the_entry(t, "--compare-found-sources")),
    ("the table loses --log=", lambda t: remove_the_entry(t, "--log=")),
    ("the table loses -copy", lambda t: remove_the_entry(t, "-copy")),
    # The direction that matters most: the real sjavac CONSUMES -implicit: and
    # hands it to no one, so claiming it for javac would make sjavac's lookup
    # reject it and the option's value would be read as a source file.
    ("-implicit: is claimed for javac", lambda t: attribute_to(t, "-implicit:", "JAVAC")),
    # And the other direction: the real sjavac FORWARDS -encoding to javac, so
    # claiming it for sjavac would make javac's own lookup reject it.
    ("a forwarded option is claimed for sjavac", lambda t: attribute_to(t, "-encoding", "SJAVAC")),
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
