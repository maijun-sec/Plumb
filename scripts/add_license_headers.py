#!/usr/bin/env python3
"""Put the licence header on every Java source.

Usage:
    python scripts/add_license_headers.py            # add the header where missing
    python scripts/add_license_headers.py --check    # report the files that lack it

The header text comes from scripts/license_header.py, which is also
what the gate checks against and what the generator emits. One text,
three consumers -- otherwise they drift and the gate starts failing on
files it just "fixed".

Which files are in scope is NOT decided here. It is decided by
scripts/format.py, which already knows the module source roots and
the fixture exclusions (the Juliet corpus and the testcase tree are
measurement data, not Plumb sources, and a licence header on a
fixture would move the lines the manifests pin).

That sharing is deliberate: the two gates must agree about what a
"Plumb source file" is. If the licence gate and the formatting gate
had separate file lists, a file could be formatted but unlicenced, and
nobody would notice.
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _formatter import format_paths, formatter_jar  # noqa: E402
from format import is_excluded  # noqa: E402
from license_header import header_text  # noqa: E402

REPO = Path(__file__).resolve().parents[1]


def source_roots():
    """The module source roots, read out of scripts/format.py.

    Read out of the formatter rather than repeated here: a second copy
    of the list is a second truth, and the day a module is added, one
    of the two lists would be wrong.
    """
    fmt = (REPO / "scripts" / "format.py").read_text(encoding="utf-8")
    marker = "SOURCE_ROOTS = ("
    start = fmt.index(marker) + len(marker)
    end = fmt.index(")", start)
    roots = []
    for line in fmt[start:end].splitlines():
        line = line.strip().strip(",").strip('"').strip("'")
        if line:
            roots.append(line)
    return roots


# The files a GENERATOR owns. The generator emits the header itself,
# so the applier must not add a second one on top of it.
#
# A file that is both generated and hand-edited is a contradiction, but
# the generated one wins: the generator is what the build runs, and
# whatever it does not emit is gone on the next run.
GENERATED_JAVA = {
    "plumb-style/src/main/java/org/plumb/style/CheckerCategories.java",
    "plumb-rules/src/main/java/org/plumb/rules/framework/FrameworkInputAnnotations.java",
}


def java_sources():
    """Every Plumb Java source, with the same exclusions the formatter uses."""
    out = []
    for root in source_roots():
        base = REPO / root
        if not base.is_dir():
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d != "target"]
            for name in filenames:
                if not name.endswith(".java"):
                    continue
                full = Path(dirpath) / name
                rel = full.relative_to(REPO)
                if is_excluded(rel.as_posix()):
                    continue
                if rel.as_posix() in GENERATED_JAVA:
                    continue
                out.append(full)
    return sorted(out)


def has_header(raw):
    """Does this file already start with the header?

    The check is on the EXACT text, not on a keyword. A file that
    says "Copyright (c) 2026 someone-else" is not licensed by this
    project, and a gate that accepted it would be reporting on the
    wrong thing.

    It compares BYTES, with the line ending normalised, because the
    header text is LF and the checked-out tree is CRLF. Comparing the
    decoded strings would fail on every file on Windows, and the
    failure would look like "no file has a header" rather than "the
    comparison forgot about the line endings".
    """
    expected = header_text().encode("utf-8").replace(b"\r\n", b"\n")
    actual = raw.replace(b"\r\n", b"\n")
    return actual.startswith(expected)


def apply(path):
    """Add the header if it is missing. Returns True if the file changed.

    The header is written with the file's OWN line ending. The tree is
    checked out with CRLF on Windows and LF elsewhere, and a file that
    mixes the two is a file that every diff tool then disagrees about.

    The line ending is detected from the RAW BYTES, not from a decoded
    string: Python's text mode normalises CRLF to LF on the way in, so
    a decoded string cannot tell the two apart.
    """
    raw = path.read_bytes()
    # The guard must be the SAME check the gate uses. Comparing against
    # the raw header bytes instead would fail on every CRLF file: the
    # header text is LF, the tree is CRLF, so a file that already had
    # the header would look headerless and get a SECOND one. The
    # symptom is 800 files with the header twice, and it is silent --
    # the applier reports "added the header to 806 files" as success.
    if has_header(raw):
        return False
    newline = b"\r\n" if b"\r\n" in raw else b"\n"
    header = header_text().encode("utf-8")
    if newline != b"\n":
        header = header.replace(b"\n", newline)
    # The body is written back unchanged, so every byte below the
    # header is preserved exactly.
    path.write_bytes(header + raw)
    return True


def main():
    check = "--check" in sys.argv

    files = java_sources()
    if not files:
        print("ERROR: no Java sources found", file=sys.stderr)
        return 2

    if check:
        missing = []
        for path in files:
            if not has_header(path.read_bytes()):
                missing.append(path.relative_to(REPO).as_posix())
        print("checked %d Java source file(s)" % len(files))
        if missing:
            print("MISSING the licence header in %d file(s):" % len(missing))
            for rel in missing[:20]:
                print("    %s" % rel)
            if len(missing) > 20:
                print("    ... and %d more" % (len(missing) - 20))
            return 1
        print("all files carry the licence header")
        return 0

    changed = []
    for path in files:
        if apply(path):
            changed.append(path.relative_to(REPO).as_posix())

    print("checked %d Java source file(s)" % len(files))
    print("added the header to %d file(s)" % len(changed))

    # The header is a comment, but the formatter still has to be happy
    # with the result: a file whose header pushes the package
    # declaration onto a new line, or that the formatter reflows, would
    # fail the formatting gate on the next commit. Formatting here
    # keeps the two gates consistent.
    if changed:
        jar = formatter_jar()
        format_paths(jar, [str(p) for p in files], replace=True)
        print("reformatted the tree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
