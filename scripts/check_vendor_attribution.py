#!/usr/bin/env python3
"""Check that the vendor's name stays out of the live source and the prose.

The project measures itself against other analyzers. Naming them in a
COMPARISON document is the point of that document. Naming them in the
SOURCE, in the RULES, or in the CONFIGURATION is not: it says the rule
came from somebody else's catalogue rather than from this project's own
measurement, and it puts a vendor's name into every downstream report
and every downstream configuration file.

So the gate has a precise scope:

  IN SCOPE -- live source, configuration, gates, tests, and ordinary prose:
    the vendor's name must not appear.

  OUT OF SCOPE, and deliberately so:
    - the tool-comparison document (and its Chinese counterpart), which
      exists to hold the comparison table;
    - the generator that produces that document;
    - the evaluation material under juliet-coverage/, which records a
      cross-tool alias mapping that was measured elsewhere and is kept
      as data.

Two things this gate does that a naive word scan does not.

  FIRST, it reads EVERY file as text, not a chosen set of suffixes. A
  leak into a file type nobody thought of -- a .gradle, a .sql, a
  .properties -- is still a leak. A suffix allowlist is a hole shaped
  exactly like "the file type we forgot".

  SECOND, it checks that it is actually checking. A gate that scans
  zero files, or whose search pattern matches nothing, passes
  vacuously -- and a gate that passes vacuously is indistinguishable
  from a gate that works. So the gate asserts its own coverage: it
  found the allowed files, and it can find a violation it plants in a
  scratch file. A gate that cannot find a planted violation is broken,
  and it says so.

The scope is written as an ALLOW list of files, not a deny list of
words. A deny list of words would have to be updated every time the
project mentions another tool; an allow list of files says where the
comparison lives, and everything else is checked.

    python scripts/check_vendor_attribution.py
"""
import os
import pathlib
import re
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[1]

# The vendor whose name must not leak into the live tree.
VENDOR = "coverity"

# The files that may name the vendor, and WHY.
#
# Each entry is a reason, not an excuse: "the comparison table" and
# "the generator of the comparison table" are the same reason, and
# "evaluation material measured elsewhere" is a different one.
#
# The gate's own files are on the list because a gate that forbids a
# word has to name the word it forbids -- and so does the battery that
# injects it. A gate that hid the word would be a gate nobody could
# audit.
ALLOWED = {
    "docs/rules/RULE-TOOL-COMPARISON.md":
        "the tool comparison table: the vendor is one of its columns",
    "docs/zh/rules/RULE-TOOL-COMPARISON.md":
        "the Chinese tool comparison table: the vendor is one of its columns",
    "scripts/gen_tool_comparison.py":
        "the generator of the comparison table: it writes the vendor column",
    "scripts/check_doc_hygiene.py":
        "the documentation hygiene gate: it names the vendor to forbid "
        "quoting another tool's rule COUNT",
    "scripts/check_vendor_attribution.py":
        "this gate: it has to name the word it forbids",
    "scripts/mutate_vendor_attribution.py":
        "this gate's mutation battery: it has to inject the word it forbids",
}

# The directories that are not the live tree at all.
#
# `.aethercode/` holds the baseline snapshots the project was grown
# from -- a copy of an older tree, kept for reference. `juliet-coverage/`
# holds evaluation material measured against an external corpus.
# Neither is shipped, neither is compiled, and neither is the product.
SKIP_DIRS = {
    ".git", ".aethercode", "target", "build", "_audit-scratch",
    "juliet-coverage", "__pycache__", "node_modules", ".idea",
}

# The word pattern. Word-bounded, so `coverity` is caught but
# `discoverity` is not -- a gate that cries wolf gets switched off.
PATTERN = re.compile(
    r"(?<![A-Za-z0-9])" + re.escape(VENDOR) + r"(?![A-Za-z0-9])",
    re.IGNORECASE,
)

# The minimum number of files the scan must have read. Below this the
# scan is not looking at the tree, and a scan that is not looking at
# the tree finds nothing and passes.
MIN_FILES_SCANNED = 100


def _parts(path):
    """The path's parts, lower-cased.

    The lower-casing matters on Windows, where a checkout can
    uppercase a directory name.
    """
    return {p.lower() for p in path.parts}


def is_skipped(rel):
    """Whether a path is outside the live tree."""
    return bool(_parts(rel) & SKIP_DIRS)


def read_text(path):
    """The text of a file, or None when it is not text.

    Every file is tried. A file that is not UTF-8 text is skipped --
    a jar or a png in the tree is not a place the vendor's name could
    have leaked into a way anybody would read.
    """
    try:
        data = path.read_bytes()
    except OSError:
        return None
    if b"\x00" in data[:8192]:
        # A NUL byte in the head means this is binary.
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def vendor_lines(text):
    """The lines of a text that name the vendor."""
    for i, line in enumerate(text.splitlines(), 1):
        if PATTERN.search(line):
            yield i, line.strip()


def scan(root):
    """Scan a tree. Returns (violations, allowed_hits, files_scanned)."""
    violations = []
    allowed_hits = {}
    scanned = 0

    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        rel = path.relative_to(root)
        if is_skipped(rel):
            continue
        text = read_text(path)
        if text is None:
            continue
        scanned += 1
        hits = list(vendor_lines(text))
        if not hits:
            continue
        pure = pathlib.PurePosixPath(rel.as_posix())
        if pure.as_posix() in ALLOWED:
            allowed_hits[pure.as_posix()] = len(hits)
        else:
            violations.append((pure.as_posix(), hits))

    return violations, allowed_hits, scanned


def self_check():
    """The gate checks that it can still find a violation.

    Two assertions, because they fail in different ways:

      1. the planted violation is FOUND -- so the scan and the pattern
         still work;
      2. the planted violation is the ONLY one -- so the scan does not
         report everything, which would be a gate that fails always.

    The scratch file uses a suffix nobody uses on purpose: it proves
    the gate reads every file, not a list of known suffixes.
    """
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        planted = root / "leak.qqq"
        planted.write_text("a line that mentions %s by name\n" % VENDOR,
                           encoding="utf-8")
        (root / "clean.qqq").write_text("a line that mentions nothing\n",
                                        encoding="utf-8")
        violations, _allowed, scanned = scan(root)
        if len(violations) != 1:
            return ("the gate cannot find a violation it planted: it found %d "
                    "of 1, in %d scanned file(s)" % (len(violations), scanned))
        if violations[0][0] != "leak.qqq":
            return ("the gate found the wrong violation: %s" % violations[0][0])
    return None


def main():
    problem = self_check()
    if problem is not None:
        print("FAIL: the gate is not checking")
        print("  %s" % problem)
        return 1

    violations, allowed_hits, scanned = scan(REPO)

    print("scanned %d file(s) in the live tree" % scanned)

    if scanned < MIN_FILES_SCANNED:
        print("")
        print("FAIL: the scan read too few files to be meaningful")
        print("  %d file(s) read, at least %d expected" % (scanned, MIN_FILES_SCANNED))
        print("  A scan that reads almost nothing finds nothing and passes.")
        return 1

    if allowed_hits:
        print("")
        print("the vendor is allowed in %d file(s):" % len(allowed_hits))
        for name in sorted(allowed_hits):
            print("  %s (%d mention(s))" % (name, allowed_hits[name]))

    # Every allowed file must still be reachable -- an allow list that
    # names a file that was renamed is a hole in the allow list.
    missing = [name for name in ALLOWED if not (REPO / name).is_file()]
    if missing:
        print("")
        print("FAIL: the allow list names %d file(s) that do not exist" % len(missing))
        for name in missing:
            print("  %s" % name)
        print("  Either the file was renamed -- and the allow list is now")
        print("  permitting a path that is not checked -- or it was deleted.")
        return 1

    if violations:
        print("")
        print("FAIL: the vendor's name is in the live tree")
        for name, hits in violations:
            print("  %s" % name)
            for lineno, text in hits:
                print("    line %d: %s" % (lineno, text[:120]))
        print("")
        print("The vendor's name belongs in the tool-comparison table and")
        print("nowhere else. The rule names, the configuration, and the")
        print("prose describe what THIS project detects.")
        return 1

    print("")
    print("OK: the vendor's name is only in the comparison table")
    return 0


if __name__ == "__main__":
    sys.exit(main())
