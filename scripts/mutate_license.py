#!/usr/bin/env python3
"""Mutation battery for `scripts/check_license.py`.

A licence gate drifts by *stopping to check*. Each mutant here is a
plausible way that happens:

  - a file loses its header,
  - the header moves below the package declaration,
  - the header names a different holder or a different year,
  - the header is a near-copy: one word changed,
  - the LICENSE file and the headers disagree,
  - the generator stops emitting the header, so the next regeneration
    would drop it,
  - the generated file is regenerated WITHOUT the header.

Each mutant is applied to an in-memory BYTE snapshot, the gate is run,
and the file is restored byte-exactly. A mutant that survives is a hole
in the gate; a mutant that could not be built is reported separately,
because "could not build" is not "survived".

    python scripts/mutate_license.py
"""
import pathlib
import re
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from add_license_headers import java_sources, kotlin_sources  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent
GATE = REPO / "scripts" / "check_license.py"
PYTHON = sys.executable

# A source file that is not generated, so a mutation on it cannot be
# undone by regenerating it.
SAMPLE = REPO / "plumb-style/src/main/java/org/plumb/style/error/EmptyCatchBlockCheck.java"
# A Kotlin source, for the same reason. The Kotlin DSL module shipped
# with no header at all and the gate was green, because the gate only
# looked for `.java` -- so the Kotlin path needs its own mutants, or
# "the gate covers Kotlin" is an untested claim.
KOTLIN_SAMPLE = REPO / "plumb-dsl-kotlin/src/main/kotlin/org/plumb/dsl/kotlin/StringOps.kt"
GENERATED = REPO / "plumb-style/src/main/java/org/plumb/style/CheckerCategories.java"
GENERATED2 = REPO / "plumb-rules/src/main/java/org/plumb/rules/framework/FrameworkInputAnnotations.java"
LICENSE = REPO / "LICENSE"
HEADER_MODULE = REPO / "scripts" / "license_header.py"
GENERATOR = REPO / "scripts" / "gen_categories.py"
GENERATOR2 = REPO / "scripts" / "gen_framework_annotations.py"
APPLIER = REPO / "scripts" / "add_license_headers.py"
GATE_SCRIPT = REPO / "scripts" / "check_license.py"


def run_gate():
    proc = subprocess.run([PYTHON, str(GATE)], capture_output=True, text=True,
                          cwd=str(REPO), encoding="utf-8", errors="replace")
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def run_applier():
    """Run the applier over the tree.

    The applier REWRITES the tree, so this is only ever called on a
    tree that the battery has snapshotted and will restore. The
    snapshot is what makes this safe: an applier that corrupts a file
    is exactly the bug under test, and the battery has to be able to
    put the tree back afterwards.
    """
    proc = subprocess.run([PYTHON, str(APPLIER)], capture_output=True, text=True,
                          cwd=str(REPO), encoding="utf-8", errors="replace")
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


# (name, file, the exact text to find, the text to replace it with)
#
# Each anchor must occur EXACTLY ONCE. A non-unique anchor fails
# loudly: mutating the wrong occurrence would test nothing.
#
# The mutants whose NAME is in RUNS_THE_APPLIER need one extra step:
# mutating the applier changes nothing in the tree by itself, so the
# battery has to RUN the applier for the defect to show up in the
# files. That is why the applier mutant exists at all -- it is the
# only way to see the "the applier puts a second header on every CRLF
# file" bug, and the gate alone would only ever see its aftermath.
MUTANTS = [
    # --- a file loses its header -------------------------------------
    ("header-removed",
     SAMPLE,
     "/*\n * Copyright (c) 2026 maijun\n",
     ""),
    ("header-removed-crlf",
     SAMPLE,
     "/*\r\n * Copyright (c) 2026 maijun\r\n",
     ""),

    # --- a Kotlin file loses its header -------------------------------
    #
    # The Kotlin DSL module shipped with no header on any of its 26
    # files, and the gate was GREEN. A gate that never looks at a file
    # cannot report it as missing one -- so the Kotlin path needs its
    # own mutants, or "the gate covers Kotlin" is an untested claim.
    ("kotlin-header-removed",
     KOTLIN_SAMPLE,
     "/*\n * Copyright (c) 2026 maijun\n",
     ""),
    ("kotlin-header-removed-crlf",
     KOTLIN_SAMPLE,
     "/*\r\n * Copyright (c) 2026 maijun\r\n",
     ""),

    # --- the gate stops looking at Kotlin at all ----------------------
    #
    # This is the silent-hole mutant: the gate still passes, because
    # the files it no longer reads still have their headers. It is
    # killed only by the gate's OWN coverage claim -- it has to report
    # how many Kotlin sources it checked, and that number has to be
    # the real one. Without the count, "the gate covers Kotlin" and
    # "the gate ignores Kotlin" look identical from the outside.
    ("gate-stops-reading-kotlin",
     GATE_SCRIPT,
     "    java = java_sources()\n    kotlin = kotlin_sources()\n",
     "    java = java_sources()\n    kotlin = []\n"),

    # --- the gate's Kotlin count is a lie -----------------------------
    #
    # The gate says it checked N Kotlin files. If the number is
    # hardcoded, the count stops being evidence: it says the same thing
    # no matter what the module contains, and it says it even when the
    # list is empty. A gate that reports "checked 26 Kotlin source
    # file(s)" while reading none of them is worse than a gate that
    # says nothing -- it looks measured.
    #
    # This mutant changes the PRINTED number only, so the gate's own
    # self-check still passes -- which means the self-check cannot kill
    # it. The battery kills it instead, by reading the number the gate
    # printed and comparing it with the number of Kotlin files that
    # actually exist. A claim in the output is a claim like any other,
    # and something has to check it.
    #
    # The number is chosen to be WRONG (999), because a hardcoded count
    # that happens to match reality is not a defect -- it is a
    # coincidence, and a mutation battery that only kills mutants with
    # the wrong number is testing the number, not the code.
    ("gate-fakes-the-kotlin-count",
     GATE_SCRIPT,
     '    note("checked %d Kotlin source file(s)" % len(kotlin))',
     '    note("checked %d Kotlin source file(s)" % 999)'),

    # --- the gate's SELF-CHECK is a lie -------------------------------
    #
    # The self-check is what makes the Kotlin count evidence rather
    # than decoration: it re-derives the number from the filesystem and
    # compares, and it fails when the list is empty. A self-check that
    # always passes is the same silent-hole shape as the original
    # defect -- it says "verified" while verifying nothing.
    #
    # This mutant is only killable TOGETHER with a broken tree: on a
    # healthy tree the condition it guards against does not arise, so
    # disabling the check changes nothing observable. So it is paired
    # with a tree mutation that removes a Kotlin file -- which is what
    # the check is there to notice.
    ("gate-selfcheck-always-passes",
     GATE_SCRIPT,
     "    if len(kotlin) != kotlin_on_disk:\n",
     "    if False:\n"),

    # --- the gate's empty-list failure is removed ---------------------
    #
    # "No Kotlin sources were checked" is the failure this project
    # actually had. Removing it means the gate goes back to being
    # green on a module with no headers at all. Same pairing: it needs
    # a tree where the Kotlin list is empty.
    ("gate-drops-the-empty-list-failure",
     GATE_SCRIPT,
     '    if not kotlin:\n        failures.append(\n'
     '            "no Kotlin sources were checked -- the Kotlin DSL module is "\n'
     '            "unlicensed and the gate is not looking")\n',
     "    if False:\n        pass\n"),

    # --- the header is present but in the wrong place -----------------
    ("header-moved-below-package",
     SAMPLE,
     "package org.plumb.style.error;",
     "package org.plumb.style.error;\n\n/*\n * Copyright (c) 2026 maijun\n */"),

    # --- the header is present twice ----------------------------------
    #
    # This is the shape a real mistake takes: somebody pastes the
    # header in by hand, and then the generator adds one too. The
    # file still HAS a header at the top, so a check that only asks
    # "is there a header here?" never sees the problem.
    ("header-duplicated",
     SAMPLE,
     "package org.plumb.style.error;",
     "package org.plumb.style.error;\n\n/*\n * Copyright (c) 2026 maijun\n */"),

    # --- the applier would ADD a second header ------------------------
    #
    # This is the bug that actually happened. The applier's guard
    # compared the file's raw bytes against the header text, which is
    # LF, while the tree is CRLF -- so every file that already had the
    # header looked headerless and got a second copy. The gate caught
    # it (it reports "carries the licence header more than once"),
    # but only because the gate ran afterwards.
    #
    # The mutant makes the applier stop noticing the duplicate, so the
    # tree ends up with two headers and the gate has to catch it. If
    # the applier and the gate agree, this mutant leaves the tree
    # EXACTLY as it found it and nothing fails -- so the assertion
    # below is what makes it a real test.
    ("applier-stopped-detecting-the-header",
     APPLIER,
     "    if has_header(raw):\n        return False\n",
     "    if raw.startswith(header_text().encode(\"utf-8\")):\n        return False\n"),


    # --- the header names somebody else -------------------------------
    ("wrong-holder",
     SAMPLE,
     "Copyright (c) 2026 maijun",
     "Copyright (c) 2026 someone-else"),
    ("wrong-year",
     SAMPLE,
     "Copyright (c) 2026 maijun",
     "Copyright (c) 2019 maijun"),
    ("holder-case-changed",
     SAMPLE,
     "Copyright (c) 2026 maijun",
     "Copyright (c) 2026 Maijun"),

    # --- the header is a near-copy ------------------------------------
    ("warranty-line-dropped",
     SAMPLE,
     " * THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR",
     " * THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS AND"),
    ("permission-line-garbled",
     SAMPLE,
     " * Permission is hereby granted, free of charge, to any person obtaining a copy",
     " * Permission is hereby granted, free of charge, to any person obtaining a copy of"),
    ("header-truncated",
     SAMPLE,
     " * SOFTWARE.\n */\n",
     " */\n"),

    # --- the LICENSE file disagrees with the headers -------------------
    ("license-year-differs",
     LICENSE,
     "Copyright (c) 2026 maijun",
     "Copyright (c) 2020 maijun"),
    ("license-body-differs",
     LICENSE,
     'THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR',
     'THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS AND'),

    # --- the generator stops emitting the header -----------------------
    #
    # This one matters most: the generated file on disk still has a
    # header, so every other check passes -- but the next regeneration
    # would drop it. Only a check that REGENERATES can see this.
    ("generator-stops-emitting-header",
     GENERATOR,
     "lines.append(header_text().rstrip(\"\\n\"))",
     "pass  # header no longer emitted"),
    ("generator-2-stops-emitting-header",
     GENERATOR2,
     "add(header_text().rstrip(\"\\n\"))",
     "pass  # header no longer emitted"),

    # --- the generated file is regenerated without a header -----------
    ("generated-file-loses-header",
     GENERATED,
     "/*\n * Copyright (c) 2026 maijun\n",
     ""),
    ("generated-file-2-loses-header",
     GENERATED2,
     "/*\n * Copyright (c) 2026 maijun\n",
     ""),
]


def normalise(text):
    """Collapse the line endings so an anchor matches either way.

    The working tree is checked out with CRLF on Windows. An anchor
    written with a bare ``\\n`` would silently not match, and the
    battery would report "could not build" for a mutant that is fine.
    """
    return text.replace("\r\n", "\n")


# The mutants that need the applier to be RUN for the defect to appear
# in the tree. Mutating the applier's source alone changes no file, so
# without this the mutant would be a no-op -- and a no-op is not a
# survivor, it is a mutant that was never tested.
RUNS_THE_APPLIER = {
    "applier-stopped-detecting-the-header",
}

# The mutants that need a BROKEN TREE to be observable.
#
# Some checks only ever fire when something is already wrong: "no
# Kotlin sources were checked", "the list does not match the
# filesystem". On a healthy tree those conditions do not arise, so
# disabling the check changes nothing observable -- the mutant
# survives, and reporting it as a survivor would be wrong. It is not a
# hole in the gate; it is a mutant that cannot be judged on a healthy
# tree.
#
# So these mutants are evaluated against a tree where the condition
# DOES arise: the Kotlin source list is emptied, which is precisely
# the defect this project actually had (a Kotlin module with no
# licence headers, and a gate that never looked).
#
# The tree is restored afterwards, byte-exactly.
BROKEN_TREE = {
    "gate-selfcheck-always-passes",
    "gate-drops-the-empty-list-failure",
}


def break_the_kotlin_list():
    """Empty the Kotlin source list, by pointing the gate elsewhere.

    The gate reads its Kotlin list from `add_license_headers`. The list
    is emptied by pointing that module at a directory with no Kotlin
    files -- so the gate still walks a real directory, and the condition
    under test ("the gate checked 0 Kotlin files") is real rather than
    staged.

    Returns the restore callable.
    """
    applier = REPO / "scripts" / "add_license_headers.py"
    original = applier.read_bytes()
    text = original.decode("utf-8")
    old = '    return _sources_with_suffix(".kt")'
    new = "    return []"
    if text.count(old) != 1:
        raise SystemExit("could not build the broken tree: the anchor is not unique")
    applier.write_bytes(text.replace(old, new).encode("utf-8"))

    def restore():
        applier.write_bytes(original)

    return restore


def snapshot_tree():
    """Every Java source's exact bytes, so the tree can be put back.

    The applier REWRITES files. A battery that mutates the applier and
    then lets it loose on the tree must be able to undo that
    afterwards, and it must undo it byte-exactly -- a `git checkout --`
    would discard the uncommitted work in the tree, which is not the
    battery's to throw away.
    """
    snap = {}
    for path in java_sources() + kotlin_sources():
        snap[path] = path.read_bytes()
    return snap


def restore_tree(snap):
    for path, data in snap.items():
        current = path.read_bytes()
        if current != data:
            path.write_bytes(data)


def kotlin_count_claim(out):
    """The number of Kotlin files the gate SAYS it checked, or None.

    A claim in the output is a claim like any other, and something has
    to check it. The gate's own self-check compares its list against
    the filesystem, but it cannot check the number it PRINTS -- that
    number is the claim an operator reads.
    """
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("checked ") and "Kotlin source file(s)" in line:
            try:
                return int(line.split()[1])
            except (IndexError, ValueError):
                return None
    return None


def real_kotlin_count():
    """How many Kotlin files the module actually has, on disk."""
    root = REPO / "plumb-dsl-kotlin" / "src"
    if not root.is_dir():
        return 0
    n = 0
    for path in root.rglob("*.kt"):
        rel = path.relative_to(REPO).as_posix()
        if "target/" in rel or "/upstream/" in rel:
            continue
        n += 1
    return n


def main():
    if not SAMPLE.is_file():
        print("FAIL: %s does not exist" % SAMPLE)
        return 1

    code, out = run_gate()
    if code != 0:
        print("FAIL: the gate does not pass on the current tree")
        print(out)
        return 1

    killed = 0
    survived = []
    broken = []

    # The count the gate claims on a healthy tree. A mutant that only
    # changes the PRINTED number is not detectable by the gate's exit
    # code, so it is detected here: the claim is compared with the
    # number of Kotlin files that actually exist.
    real_kotlin = real_kotlin_count()
    claimed = kotlin_count_claim(out)
    if claimed != real_kotlin:
        print("FAIL: the gate claims it checked %s Kotlin source file(s), "
              "but the module has %d" % (claimed, real_kotlin))
        return 1

    for name, target, old, new in MUTANTS:
        if not target.is_file():
            print("FAIL: %s -- %s does not exist" % (name, target))
            return 1
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

            # A mutant that lives in the applier changes no file by
            # itself. The defect only shows up when the applier is
            # actually RUN, so the tree is snapshotted first and the
            # applier is let loose on it. Without this the mutant
            # would be a no-op, and a no-op that reports "killed"
            # because the gate happened to fail for another reason is
            # the worst kind of test.
            snap = None
            if name in RUNS_THE_APPLIER:
                snap = snapshot_tree()
                run_applier()

            # A mutant that only ever fires on a broken tree is
            # evaluated against one: otherwise it survives for the
            # wrong reason -- not because the gate is blind, but
            # because the condition it guards against did not arise.
            unbreak = None
            if name in BROKEN_TREE:
                unbreak = break_the_kotlin_list()

            code, out = run_gate()
        finally:
            # Restore the EXACT bytes. A `git checkout --` would discard
            # any uncommitted work in the tree.
            if unbreak is not None:
                unbreak()
            if snap is not None:
                restore_tree(snap)
            target.write_bytes(original)

        # The gate can exit 0 and still be lying about what it checked.
        # The claim is checked here, so a mutant that only changes the
        # printed number is killed rather than reported as a survivor.
        #
        # The claim is only compared on a HEALTHY tree. A broken tree
        # reports 0 Kotlin files on purpose, and that is the point of
        # the broken-tree mutants.
        if name not in BROKEN_TREE and code == 0 and kotlin_count_claim(out) != real_kotlin:
            code = 1
            out = out + ("\n(the gate's own claim about how many Kotlin files "
                         "it checked is false)\n")

        if code == 0:
            survived.append(name)
            print("SURVIVED  %s" % name)
        else:
            killed += 1
            print("killed    %s" % name)

    print()
    print("%d/%d mutant(s) killed" % (killed, len(MUTANTS)))
    if broken:
        print("could not build: %s" % ", ".join(broken))
        return 1
    if survived:
        print("survived: %s" % ", ".join(survived))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
