#!/usr/bin/env python3
"""Run the pinned google-java-format on a piece of Java source.

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


def format_source(jar, source, filename_hint="Generated.java"):
    """Format Java source text.

    `filename_hint` must end in ".java": google-java-format decides the
    language from the file extension and SKIPS a file it does not
    recognise, printing "Skipping non-Java file" to stderr and exiting 0
    with EMPTY stdout. Handing it a non-Java name would silently
    return an empty string, and the caller would write an empty file.

    The source is passed through a temporary file with the right
    extension for the same reason.
    """
    import tempfile

    work = tempfile.mkdtemp(prefix="plumb-fmt-")
    try:
        path = os.path.join(work, filename_hint)
        with open(path, "w", encoding="utf-8", newline="") as fh:
            fh.write(source)
        proc = subprocess.run(
            ["java"] + JVM_EXPORTS + ["-jar", jar, path], capture_output=True)
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
