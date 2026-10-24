#!/usr/bin/env python3
"""Format the Java sources, or report the ones that are not formatted.

This is the single implementation of the formatting rule; `format.ps1`
and `format.sh` are thin wrappers around it so that Windows, Linux and
macOS all enforce exactly the same set of files and the same exclusions.

Usage:
    python scripts/format.py            # rewrite the sources in place
    python scripts/format.py --check    # report unformatted files, change nothing

Exit code:
    0  everything is formatted (or was just formatted)
    1  --check found unformatted files
    2  the formatter could not be run

Why a script and not a Maven plugin:
    fmt-maven-plugin 2.9 bundles google-java-format 1.7, which predates
    text blocks and cannot parse this codebase -- it fails with
    "需要';'" on a triple-quoted string literal. Pinning a standalone
    jar keeps the formatter version explicit.

Why the exclusions:
    `plumb-dfa/src/main/resources/scripts/` holds Juliet corpus fixtures.
    They are TEST DATA, not Plumb source: they are deliberately
    malformed (two of them do not even parse), and reformatting them
    would corrupt the corpus the engine is measured against. The
    `testcase/` tree holds measurement fixtures with hand-placed line
    pins; reformatting those would move the lines the manifests point
    at, so they are excluded too.
"""
import argparse
import io
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _formatter import JVM_EXPORTS, format_source, formatter_jar  # noqa: E402

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Directory prefixes (relative to the repo root) that are NOT Plumb source.
EXCLUDED_PREFIXES = (
    "plumb-dfa/src/main/resources/scripts/",
    "testcase/",
    ".aethercode/",
    "target/",
)

# The module source roots that hold Plumb's own code.
SOURCE_ROOTS = (
    "plumb-parser/src",
    "plumb-tac/src",
    "plumb-rules/src",
    "plumb-style/src",
    "plumb-reporter/src",
    "plumb-dfa/src",
    "plumb-build/src",
    "plumb-ai/src",
    "plumb-main/src",
)


def is_excluded(rel_path):
    norm = rel_path.replace(os.sep, "/")
    for prefix in EXCLUDED_PREFIXES:
        if norm.startswith(prefix) or ("/" + prefix) in ("/" + norm):
            return True
    return False


def java_sources():
    """Every Plumb source file, excluding the fixtures."""
    out = []
    for root in SOURCE_ROOTS:
        base = os.path.join(REPO, root)
        if not os.path.isdir(base):
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d != "target"]
            for name in filenames:
                if not name.endswith(".java"):
                    continue
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, REPO)
                if is_excluded(rel):
                    continue
                out.append(full)
    return sorted(out)


def ensure_formatter():
    try:
        return formatter_jar()
    except Exception as exc:  # noqa: BLE001 - report and stop
        print("FAILED to obtain the formatter: %s" % exc)
        return None


def format_file(jar, path, write):
    """Format one file.

    Returns True when the file on disk was already formatted.

    `write` is False for --check: the formatted text is compared against
    the file but NEVER written back. A check that edits the tree is not
    a check -- it would make the working tree depend on whether a gate
    ran, and the pre-commit hook would silently reformat the whole
    repository on every commit.

    The formatter's output is decoded as UTF-8 EXPLICITLY. Decoding it
    with the platform locale corrupts every non-ASCII character in the
    file: this codebase has em-dashes and arrows in its javadoc, and a
    GBK console decodes the UTF-8 bytes as mojibake. The formatter then
    "changes" the file on every run, because the corrupted text it
    reads back is not the text it wrote. The file must be read and
    written as bytes and decoded as UTF-8 on both sides.

    An EMPTY output is treated as a failure, never as "the formatted
    file". google-java-format decides the language from the file
    extension and SKIPS a file it does not recognise -- printing
    "Skipping non-Java file: ..." to stderr and exiting 0 with EMPTY
    stdout. Writing that empty output would DESTROY the source. Since
    a Java source file is never legitimately empty, an empty result
    means the formatter did not process the file, and the file must be
    left alone.
    """
    with open(path, "r", encoding="utf-8", newline="") as fh:
        original = fh.read()
    formatted = format_source(jar, original, os.path.basename(path))
    if formatted == original:
        return True
    if write:
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(formatted)
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true",
                        help="report unformatted files instead of rewriting them")
    args = parser.parse_args()

    jar = ensure_formatter()
    if not jar:
        return 2

    files = java_sources()
    if not files:
        print("no source files found")
        return 2

    changed = []
    failed = []
    for path in files:
        rel = os.path.relpath(path, REPO).replace(os.sep, "/")
        try:
            if not format_file(jar, path, write=not args.check):
                changed.append(rel)
        except RuntimeError as exc:
            failed.append((rel, str(exc)))

    print("checked %d source files" % len(files))
    if changed:
        if args.check:
            print("%d file(s) are NOT formatted:" % len(changed))
            for rel in changed[:40]:
                print("    " + rel)
            if len(changed) > 40:
                print("    ... and %d more" % (len(changed) - 40))
        else:
            print("reformatted %d file(s)" % len(changed))
    else:
        print("all files are formatted")

    if failed:
        print("%d file(s) could not be parsed by the formatter:" % len(failed))
        for rel, err in failed[:10]:
            print("    %s: %s" % (rel, err.replace("\n", " ")[:200]))
        return 2

    if args.check and changed:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
