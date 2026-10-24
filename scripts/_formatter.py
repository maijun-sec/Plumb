#!/usr/bin/env python3
"""Run the pinned google-java-format on Java source.

One implementation, used by:
  - scripts/format.py           (the pre-commit gate)
  - scripts/gen_categories.py   (a generator that must emit formatted code)
  - scripts/gen_rules_doc.py    (a generator that quotes source)
  - scripts/gen_rules_index.py  (a generator that quotes source)

The generators NEED this: they compare their rendered output against the
file on disk, byte for byte. A generator that emits unformatted code
would make `gen_categories.py --check` and `format.py --check` fight
each other -- the generator writes unformatted code, the formatter
rewrites it, and the check fails again. Emitting FORMATTED code makes
the two agree.

Why a standalone jar and not a Maven plugin:
    fmt-maven-plugin 2.9 bundles google-java-format 1.7, which predates
    text blocks and cannot parse this codebase -- it fails with
    "需要';'" on a triple-quoted string literal.

Two entry points, for two different jobs:

  format_source(jar, source, hint)
      Formats ONE piece of source held in memory. Used by the
      generators. Spawns a JVM; that is unavoidable here because the
      source does not exist on disk yet.

  format_paths(jar, paths, replace)
      Formats many files on disk in a SINGLE JVM. Used by format.py.
      This is the one that matters for speed -- see the note below.

Why batch mode matters:
    The formatter is invoked once per file in the naive version, and
    each invocation pays a JVM startup plus a classpath scan. Measured
    on this machine: 0.885 s per file, so a full check over the 767
    source files took 11.3 MINUTES. A pre-commit hook that costs
    11 minutes trains developers to reach for `--no-verify`, which
    defeats the entire point of the gate.

    The same 767 files go through ONE JVM in 16 seconds, because
    google-java-format accepts many file arguments and reuses the
    compiler front end across them. That is a ~40x difference, and it
    is the difference between a gate people keep and a gate people
    bypass.
"""
import os
import subprocess
import sys

GJF_VERSION = "1.37.0"
GJF_JAR_NAME = "google-java-format-%s-all-deps.jar" % GJF_VERSION
GJF_URL = (
    "https://repo1.maven.org/maven2/com/google/googlejavaformat/google-java-format/"
    "%s/%s" % (GJF_VERSION, GJF_JAR_NAME)
)

# google-java-format uses javac internals, which are not exported by
# default on JDK 16+.
JVM_EXPORTS = [
    "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
    "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
]


def _default_m2():
    return os.environ.get("PLUMB_M2", r"D:\cache\mvn_repo")


def formatter_jar():
    """Path to the pinned formatter, downloading it if necessary."""
    local = os.path.join(
        _default_m2(), "com", "google", "googlejavaformat", "google-java-format",
        GJF_VERSION, GJF_JAR_NAME)
    if os.path.isfile(local):
        return local
    os.makedirs(os.path.dirname(local), exist_ok=True)
    print("downloading the formatter from %s" % GJF_URL, file=sys.stderr)
    import urllib.request
    urllib.request.urlretrieve(GJF_URL, local)
    return local


def _java_cmd(jar):
    return ["java"] + JVM_EXPORTS + ["-jar", jar]


def format_source(jar, source, filename_hint="Generated.java"):
    """Format Java source text held in memory.

    `filename_hint` must end in ".java": google-java-format decides the
    language from the file extension and SKIPS a file it does not
    recognise, printing "Skipping non-Java file" to stderr and exiting 0
    with EMPTY stdout. Handing it a non-Java name would silently
    return an empty string, and the caller would write an empty file.

    The source is passed through a temporary file with the right
    extension for the same reason.

    The formatter's output is decoded as UTF-8 EXPLICITLY. Decoding it
    with the platform locale corrupts every non-ASCII character: this
    codebase has em-dashes and arrows in its javadoc, and a GBK console
    decodes the UTF-8 bytes as mojibake. The formatter then "changes"
    the file on every run, because the corrupted text it reads back is
    not the text it wrote.

    An EMPTY output is treated as a failure, never as "the formatted
    file". google-java-format skips a file it does not recognise and
    exits 0 with empty stdout; writing that empty output would
    DESTROY the source.
    """
    import tempfile

    work = tempfile.mkdtemp(prefix="plumb-fmt-")
    try:
        path = os.path.join(work, filename_hint)
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(source)
        proc = subprocess.run(
            _java_cmd(jar) + [path], capture_output=True)
        if proc.returncode != 0:
            raise RuntimeError(proc.stderr.decode("utf-8", "replace")[:2000])
        out = proc.stdout.decode("utf-8")
        if not out.strip():
            stderr = proc.stderr.decode("utf-8", "replace").strip()
            raise RuntimeError(
                "the formatter produced no output for %s. stderr: %s"
                % (filename_hint, stderr[:400] or "<none>"))
        return out
    finally:
        import shutil
        shutil.rmtree(work, ignore_errors=True)


def format_paths(jar, paths, replace=False):
    """Format many files on disk in ONE JVM.

    Returns the list of paths that are not formatted.

    `replace=False` is a READ-ONLY check: it uses --dry-run, which
    prints the paths whose contents would change and changes nothing.
    `--set-exit-if-changed` turns "something would change" into exit
    code 1, so the caller does not have to compare exit codes to a
    parse of stdout.

    `replace=True` rewrites the files in place with --replace.

    A file the formatter cannot parse is reported separately rather
    than aborting the run: the caller needs to know WHICH files are
    broken, and it still needs the verdict on the files that are fine.

    The paths handed back are exactly the ones passed in, compared by
    absolute path, so the caller can map them back to its own labels.
    """
    if not paths:
        return []

    absolute = [os.path.abspath(p) for p in paths]
    by_abs = {os.path.abspath(p): p for p in paths}

    # google-java-format is invoked with all the files at once. The
    # command line would overflow on a huge tree, so the batch is
    # chunked; the chunk size is comfortably below every platform's
    # limit and still large enough that the JVM start-up cost is
    # amortised.
    chunk_size = 200
    unformatted = []
    failed = []

    for start in range(0, len(absolute), chunk_size):
        chunk = absolute[start:start + chunk_size]
        if replace:
            args = _java_cmd(jar) + ["--replace"] + chunk
        else:
            args = _java_cmd(jar) + ["--dry-run", "--set-exit-if-changed"] + chunk
        proc = subprocess.run(args, capture_output=True)
        stdout = proc.stdout.decode("utf-8", "replace")
        stderr = proc.stderr.decode("utf-8", "replace")

        if replace:
            if proc.returncode != 0:
                failed.append((chunk, stderr[:2000]))
        else:
            # Exit code 1 with --set-exit-if-changed means "at least
            # one file would change", and stdout lists them.
            if proc.returncode not in (0, 1):
                failed.append((chunk, stderr[:2000]))
            for line in stdout.splitlines():
                candidate = line.strip()
                if not candidate:
                    continue
                if os.path.abspath(candidate) in by_abs:
                    unformatted.append(by_abs[os.path.abspath(candidate)])

    if failed:
        messages = []
        for chunk, err in failed:
            messages.append("batch of %d file(s): %s" % (len(chunk), err))
        raise RuntimeError("\n".join(messages))

    return unformatted
