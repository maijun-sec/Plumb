#!/usr/bin/env python3
"""Mutation battery for `scripts/check_vendor_attribution.py`.

A gate that forbids a word drifts by *stopping to forbid it*. Each
mutant here is a plausible way that happens:

  - the vendor's name is put back into a live source file,
  - the vendor's name is put back into a rule configuration file,
  - the vendor's name is put back into ordinary prose,
  - the gate stops walking the tree,
  - the gate's search pattern stops matching,
  - the gate's word boundary is loosened,
  - the gate's self-check is neutered,
  - the gate's coverage floor is lowered,
  - the gate stops checking its allow list for dead entries,
  - the gate stops skipping the evaluation material.

Two kinds of mutant, and the difference matters.

A mutant that INJECTS the name is killed when the gate FINDS it: the
gate must fail on the mutated tree.

A mutant that WEAKENS the gate is only killed when the gate then MISSES
something it should have found -- and on a clean tree there is nothing
to miss. So a weakening mutant is applied together with a PLANT: a real
file in the tree that carries a real violation for the duration of the
mutation. The gate must report the plant. If it does not, the
weakening went unnoticed, which is exactly the hole the mutant
represents.

That is the whole discipline: **a gate that stops checking is
indistinguishable from a gate that works, unless something is there to
be found.** So the battery always puts something there.

Each mutant is applied to an in-memory BYTE snapshot, the gate is run,
and the file is restored byte-exactly. A mutant that survives is a hole
in the gate; a mutant that could not be built is reported separately,
because "could not build" is not "survived".

    python scripts/mutate_vendor_attribution.py
"""
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
GATE = REPO / "scripts" / "check_vendor_attribution.py"
PYTHON = sys.executable

# A live source file that the gate must watch.
SOURCE = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa" / "config" / "ConfigLoader.java"

# A rule configuration file that the gate must watch.
CONFIG = REPO / "plumb-dfa" / "src" / "main" / "resources" / "sink-families.yaml"

# Ordinary prose that the gate must watch.
PROSE = REPO / "docs" / "tech" / "LANGUAGE-RULES.md"

# The file the PLANTS are written into. It is a real source file, and
# the plant is removed again when the mutation ends.
PLANT_FILE = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa" \
    / "config" / "ConfigLoader.java"


def run_gate():
    proc = subprocess.run([PYTHON, str(GATE)], capture_output=True, text=True,
                          cwd=str(REPO), encoding="utf-8", errors="replace")
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def normalise(text):
    """Collapse the line endings so an anchor matches either way."""
    return text.replace("\r\n", "\n")


# The text the plant carries. It names the vendor, so the gate must
# report it -- unless the mutant has weakened the gate so much that the
# gate stops seeing it.
PLANT_TEXT = "\n  // the option table was measured against Coverity's compiler\n"

# (name, file, the exact text to find, the text to replace it with,
#  whether to plant a violation alongside the mutation)
#
# Each anchor must occur EXACTLY ONCE. A non-unique anchor fails
# loudly: mutating the wrong occurrence would test nothing.
#
# A mutant that INJECTS the name is killed by the gate finding it. A
# mutant that WEAKENS the gate is only killed if the gate then misses
# something it should have found -- and on a clean tree there is
# nothing to miss. So a weakening mutant names a PLANT: a file in the
# tree that carries a real violation for the duration of the mutation.
# The gate must report the plant. If it does not, the weakening went
# unnoticed, which is exactly the hole the mutant represents.
#
# That is the whole discipline: **a gate that stops checking is
# indistinguishable from a gate that works, unless something is there to
# be found.** So the battery always puts something there.
MUTANTS = [
    # --- the name comes back into the live source --------------------
    #
    # The injected text must contain the vendor's ACTUAL name. A mutant
    # that says "the vendor's tool" injects no name at all, so the
    # gate has nothing to find and the mutant survives -- which proves
    # nothing about the gate.
    ("vendor-name-in-a-java-source-file",
     SOURCE,
     "public final class ConfigLoader {",
     "public final class ConfigLoader {\n  // the option table was measured against Coverity's compiler",
     False),

    # --- the name comes back into the rule configuration -------------
    ("vendor-name-in-a-rule-configuration",
     CONFIG,
     "families:\n  #",
     "families:\n  # the families were shaped like Coverity's catalogue\n  #",
     False),

    # --- the name comes back into ordinary prose ---------------------
    ("vendor-name-in-prose",
     PROSE,
     "# Per-language rules: where the language actually enters",
     "# Per-language rules, compared with Coverity's own layout",
     False),

    # --- the gate stops walking the tree -----------------------------
    #
    # The gate's scan walks a root it is given. If the walk stops, the
    # gate finds nothing -- so a planted violation goes unseen too.
    ("the-gate-stops-walking-the-tree",
     GATE,
     '    for path in sorted(root.rglob("*")):',
     "    for path in []:",
     True),

    # --- the gate's pattern stops matching --------------------------
    #
    # A pattern that cannot match cannot find a violation, so the gate
    # passes on a tree that has one.
    ("the-gate-loses-its-pattern",
     GATE,
     'PATTERN = re.compile(\n    r"(?<![A-Za-z0-9])" + re.escape(VENDOR) + r"(?![A-Za-z0-9])",\n    re.IGNORECASE,\n)',
     'PATTERN = re.compile(r"a-pattern-that-matches-nothing")',
     True),

    # --- the gate's word boundary is loosened ------------------------
    #
    # Without the boundary, "discoverity" matches. That makes the gate
    # report code that has nothing to do with the vendor -- a gate that
    # cries wolf is a gate that gets switched off. The plant is a word
    # that merely CONTAINS the vendor's name, so with the boundary the
    # gate must ignore it, and without the boundary it must report it.
    ("the-gate-loses-the-word-boundary",
     GATE,
     'PATTERN = re.compile(\n    r"(?<![A-Za-z0-9])" + re.escape(VENDOR) + r"(?![A-Za-z0-9])",\n    re.IGNORECASE,\n)',
     'PATTERN = re.compile(re.escape(VENDOR), re.IGNORECASE)',
     True),

    # --- the gate's self-check is neutered --------------------------
    #
    # The self-check is what proves the gate can still find a
    # violation. Without it, a gate that stopped working would pass.
    ("the-gate-loses-its-self-check",
     GATE,
     "    problem = self_check()\n    if problem is not None:",
     "    problem = None\n    if problem is not None:",
     True),

    # --- the gate's minimum coverage is lowered to nothing -----------
    ("the-gate-loses-its-coverage-floor",
     GATE,
     "MIN_FILES_SCANNED = 100",
     "MIN_FILES_SCANNED = 0",
     True),

    # --- the allow list stops being checked for dead entries --------
    #
    # An allow list that names a renamed file permits a path that is
    # not checked -- a hole in the allow list.
    ("the-gate-stops-checking-the-allow-list",
     GATE,
     '    missing = [name for name in ALLOWED if not (REPO / name).is_file()]',
     "    missing = []",
     True),

    # --- the gate stops skipping the evaluation material ------------
    #
    # The comparison table's generator names the vendor legitimately.
    # If the skip goes away, the gate reports its own allowed file.
    ("the-gate-stops-skipping-the-evaluation-material",
     GATE,
     '    "juliet-coverage", "__pycache__", "node_modules", ".idea",',
     '    "__pycache__", "node_modules", ".idea",',
     False),
]


def apply_mutation(target, old, new):
    """Write the mutation into the target. Returns the original bytes."""
    original = target.read_bytes()
    text = original.decode("utf-8")
    flat = normalise(text)
    old_flat = normalise(old)
    new_flat = normalise(new)
    count = flat.count(old_flat)
    if count != 1:
        return original, count
    mutated = flat.replace(old_flat, new_flat)
    # Write the mutant back with the ORIGINAL line endings, so the
    # only difference is the mutation itself.
    if "\r\n" in text:
        mutated = mutated.replace("\n", "\r\n")
    target.write_bytes(mutated.encode("utf-8"))
    return original, 1


def apply_plant(target):
    """Write a real violation into the target. Returns the original bytes."""
    original = target.read_bytes()
    text = original.decode("utf-8")
    flat = normalise(text)
    marker = "public final class ConfigLoader {"
    if flat.count(marker) != 1:
        return original, 0
    mutated = flat.replace(marker, marker + PLANT_TEXT)
    if "\r\n" in text:
        mutated = mutated.replace("\n", "\r\n")
    target.write_bytes(mutated.encode("utf-8"))
    return original, 1


def main():
    for path in (SOURCE, CONFIG, PROSE, GATE, PLANT_FILE):
        if not path.is_file():
            print("FAIL: %s does not exist" % path)
            return 1

    code, out = run_gate()
    if code != 0:
        print("FAIL: the gate does not pass on the current tree")
        print(out)
        return 1

    # The gate must FIND a planted violation -- that is the property
    # the weakening mutants are measured against.
    plant_original, ok = apply_plant(PLANT_FILE)
    try:
        code, out = run_gate()
    finally:
        PLANT_FILE.write_bytes(plant_original)
    if code == 0:
        print("FAIL: the gate does not find a planted violation")
        print("  The gate must report the plant; it reported nothing.")
        print("  Every weakening mutant below is measured against this.")
        return 1
    print("ok: the gate finds a planted violation")
    print("")

    killed = 0
    survived = []
    broken = []

    for name, target, old, new, needs_plant in MUTANTS:
        plant_original = None
        try:
            if needs_plant:
                plant_original, ok = apply_plant(PLANT_FILE)
                if not ok:
                    print("FAIL: %s -- the plant could not be written" % name)
                    broken.append(name)
                    continue
            original, ok = apply_mutation(target, old, new)
            if ok != 1:
                print("FAIL: %s -- the anchor occurs %d times, expected exactly 1"
                      % (name, ok))
                broken.append(name)
                continue
            code, out = run_gate()
        finally:
            # Restore the EXACT bytes. A `git checkout --` would discard
            # any uncommitted work in the tree.
            target.write_bytes(original)
            if plant_original is not None:
                PLANT_FILE.write_bytes(plant_original)

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
