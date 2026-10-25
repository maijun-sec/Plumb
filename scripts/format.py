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
    `plumb-dfa/src/test/resources/juliet-corpus/` holds Juliet corpus
    fixtures. They are TEST DATA, not Plumb source: they are deliberately
    malformed (two of them do not even parse), and reformatting them
    would corrupt the corpus the engine is measured against. The
    `testcase/` tree holds measurement fixtures with hand-placed line
    pins; reformatting those would move the lines the manifests point
    at, so they are excluded too.

Why the whole tree in one formatter run:
    The pre-commit hook calls --check on every commit. Invoking the
    formatter once per file costs a JVM start-up per file -- 11 minutes
    over this repository, which is exactly long enough that people
    start passing --no-verify. One run over all the files costs about
    30 seconds. The gate is only useful if it is cheap enough to keep.
"""
import argparse
import io
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _formatter import format_paths, formatter_jar  # noqa: E402

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# Directory prefixes (relative to the repo root) that are NOT Plumb source.
EXCLUDED_PREFIXES = (
    "plumb-dfa/src/test/resources/juliet-corpus/",
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

    try:
        if args.check:
            unformatted = format_paths(jar, files, replace=False)
        else:
            format_paths(jar, files, replace=True)
            # After rewriting, ask again so the report names the files
            # that are STILL not formatted. Anything left here is a
            # file the formatter could not converge on, which is worth
            # surfacing rather than hiding behind "reformatted N".
            unformatted = format_paths(jar, files, replace=False)
    except RuntimeError as exc:
        print("the formatter could not process the sources:")
        print(str(exc))
        return 2

    print("checked %d source files" % len(files))
    if unformatted:
        print("%d file(s) are NOT formatted:" % len(unformatted))
        for path in unformatted[:40]:
            print("    " + os.path.relpath(path, REPO).replace(os.sep, "/"))
        if len(unformatted) > 40:
            print("    ... and %d more" % (len(unformatted) - 40))
        if args.check:
            return 1
        return 2

    print("all files are formatted")
    return 0


if __name__ == "__main__":
    sys.exit(main())
