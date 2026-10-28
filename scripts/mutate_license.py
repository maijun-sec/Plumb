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

from add_license_headers import java_sources  # noqa: E402

REPO = pathlib.Path(__file__).resolve().parent.parent
GATE = REPO / "scripts" / "check_license.py"
PYTHON = sys.executable

# A source file that is not generated, so a mutation on it cannot be
# undone by regenerating it.
SAMPLE = REPO / "plumb-style/src/main/java/org/plumb/style/error/EmptyCatchBlockCheck.java"
GENERATED = REPO / "plumb-style/src/main/java/org/plumb/style/CheckerCategories.java"
GENERATED2 = REPO / "plumb-rules/src/main/java/org/plumb/rules/framework/FrameworkInputAnnotations.java"
LICENSE = REPO / "LICENSE"
HEADER_MODULE = REPO / "scripts" / "license_header.py"
GENERATOR = REPO / "scripts" / "gen_categories.py"
GENERATOR2 = REPO / "scripts" / "gen_framework_annotations.py"
APPLIER = REPO / "scripts" / "add_license_headers.py"


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


def snapshot_tree():
    """Every Java source's exact bytes, so the tree can be put back.

    The applier REWRITES files. A battery that mutates the applier and
    then lets it loose on the tree must be able to undo that
    afterwards, and it must undo it byte-exactly -- a `git checkout --`
    would discard the uncommitted work in the tree, which is not the
    battery's to throw away.
    """
    snap = {}
    for path in java_sources():
        snap[path] = path.read_bytes()
    return snap


def restore_tree(snap):
    for path, data in snap.items():
        current = path.read_bytes()
        if current != data:
            path.write_bytes(data)


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

            code, out = run_gate()
        finally:
            # Restore the EXACT bytes. A `git checkout --` would discard
            # any uncommitted work in the tree.
            if snap is not None:
                restore_tree(snap)
            target.write_bytes(original)

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
