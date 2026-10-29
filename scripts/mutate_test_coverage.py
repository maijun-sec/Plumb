#!/usr/bin/env python3
"""Mutation battery for `scripts/check_test_coverage.py`.

A coverage gate drifts by *stopping to check*. Each mutant here is a
plausible way that happens:

  - a test file is removed from the allow list, so the build stops
    running it,
  - a test file is renamed so surefire's default pattern stops
    matching it,
  - the gate stops noticing that a file is `@Disabled`,
  - the gate stops noticing that a file has no `@Test` method, so it
    starts demanding that support classes be renamed,
  - the gate stops reading the surefire plugin and reads the failsafe
    plugin instead,
  - the gate stops reading the failsafe plugin, so integration tests
    are reported as holes,
  - the gate stops scanning a module,
  - the gate stops scanning anything.

Each mutant is applied to an in-memory BYTE snapshot, the gate is run,
and the file is restored byte-exactly. A mutant that survives is a hole
in the gate; a mutant that could not be built is reported separately,
because "could not build" is not "survived".

    python scripts/mutate_test_coverage.py
"""
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
GATE = REPO / "scripts" / "check_test_coverage.py"
PYTHON = sys.executable

DFA_POM = REPO / "plumb-dfa" / "pom.xml"
STYLE_TEST = REPO / "plumb-style" / "src" / "test" / "java" / "org" / "plumb" \
    / "style" / "style" / "R790DebugParentWalk.java"


def run_gate():
    proc = subprocess.run([PYTHON, str(GATE)], capture_output=True, text=True,
                          cwd=str(REPO), encoding="utf-8", errors="replace")
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def normalise(text):
    """Collapse the line endings so an anchor matches either way."""
    return text.replace("\r\n", "\n")


# (name, file, the exact text to find, the text to replace it with)
#
# Each anchor must occur EXACTLY ONCE. A non-unique anchor fails
# loudly: mutating the wrong occurrence would test nothing.
MUTANTS = [
    # --- a test is dropped from the allow list -----------------------
    #
    # This is the hole the gate exists for. The test file stays on
    # disk and keeps passing in isolation; the build simply stops
    # running it.
    ("a-test-is-dropped-from-the-allow-list",
     DFA_POM,
     "                        <include>org/plumb/dfa/checkers/taint/ResourceLeakAliasTest.java</include>\n",
     ""),

    # --- a test is renamed so the default pattern misses it ---------
    #
    # The module has no allow list for this class, so surefire's
    # default pattern is what runs it. Rename it away from *Test and
    # it stops running.
    #
    # The file chosen is one that is NOT @Disabled: a disabled file is
    # skipped by the gate on purpose, so renaming it would test
    # nothing. So the mutant also removes the @Disabled -- which is
    # the state a NEW test would be in: a test file that someone added
    # and did not give a runnable name.
    ("a-test-is-named-so-the-default-pattern-misses-it",
     STYLE_TEST,
     "@Disabled(\n    \"a debug print program, not a test: it asserts only that the source parses, and it\"\n        + \" was never run because its class name is not *Test -- which is exactly the\"\n        + \" hole scripts/check_test_coverage.py closes\")\nclass R790DebugParentWalk {",
     "class R790DebugParentWalkRenamed {"),

    # --- the gate stops noticing @Disabled --------------------------
    #
    # Without this, every disabled sweep file is reported as a hole,
    # and the gate cries wolf.
    ("the-gate-stops-noticing-disabled-files",
     GATE,
     '    return "@Disabled" in body',
     "    return False"),

    # --- the gate stops noticing that a file has no test methods ----
    #
    # Without this, the gate demands that every support class in the
    # test tree be renamed to *Test -- which is worse than the hole
    # the gate closes.
    ("the-gate-stops-noticing-helper-classes",
     GATE,
     '    return "@Test" in body',
     "    return True"),

    # --- the gate reads the wrong plugin ----------------------------
    #
    # The failsafe plugin has an <includes> block too. Reading that
    # one instead of surefire's would make the gate conclude that the
    # unit tests are not allowed to run.
    ("the-gate-reads-the-failsafe-plugin",
     GATE,
     '        r"<artifactId>maven-surefire-plugin</artifactId>(.*?)</plugin>", pom_text, re.S',
     '        r"<artifactId>maven-failsafe-plugin</artifactId>(.*?)</plugin>", pom_text, re.S'),

    # --- the gate stops reading the failsafe plugin -----------------
    #
    # Without this, every integration test is reported as a hole.
    ("the-gate-stops-reading-the-failsafe-plugin",
     GATE,
     '        r"<artifactId>maven-failsafe-plugin</artifactId>(.*?)</plugin>", pom_text, re.S',
     '        r"<artifactId>maven-does-not-exist-plugin</artifactId>(.*?)</plugin>", pom_text, re.S'),

    # --- the gate stops scanning a module ---------------------------
    ("the-gate-stops-scanning-plumb-dfa",
     GATE,
     '    "plumb-dfa",\n',
     ""),

    # --- the gate stops scanning anything --------------------------
    ("the-gate-stops-scanning",
     GATE,
     "    for module in MODULES:",
     "    for module in []:"),
]


def main():
    for path in (DFA_POM, STYLE_TEST, GATE):
        if not path.is_file():
            print("FAIL: %s does not exist" % path)
            return 1

    code, out = run_gate()
    if code != 0:
        print("FAIL: the gate does not pass on the current tree")
        print(out)
        return 1

    killed = 0
    survived = []
    broken = []

    for name, target, old, new in MUTANTS:
        original = target.read_bytes()
        text = original.decode("utf-8")
        flat = normalise(text)
        old_flat = normalise(old)
        new_flat = normalise(new)
        count = flat.count(old_flat)
        if count != 1:
            print("FAIL: %s -- the anchor occurs %d times, expected exactly 1"
                  % (name, count))
            broken.append(name)
            continue
        try:
            mutated = flat.replace(old_flat, new_flat)
            # Write the mutant back with the ORIGINAL line endings, so
            # the only difference is the mutation itself.
            if "\r\n" in text:
                mutated = mutated.replace("\n", "\r\n")
            target.write_bytes(mutated.encode("utf-8"))
            code, out = run_gate()
        finally:
            # Restore the EXACT bytes. A `git checkout --` would discard
            # any uncommitted work in the tree.
            target.write_bytes(original)

        if code == 0:
            survived.append(name)
            print("SURVIVED  %s" % name)
        else:
            killed += 1
            print("killed    %s" % name)

    print("")
    print("%d killed, %d survived, %d could not be built"
          % (killed, len(survived), len(broken)))

    if survived or broken:
        print("")
        print("FAIL: the gate is not checking what it claims to check")
        for name in survived:
            print("  survived: %s" % name)
        for name in broken:
            print("  could not build: %s" % name)
        return 1

    print("OK: every mutant was killed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
