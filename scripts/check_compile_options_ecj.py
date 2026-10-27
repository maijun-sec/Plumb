#!/usr/bin/env python3
"""Check the capture's ECJ option table against the real ECJ.

The option table is supposed to be the compiler's option set, so the compiler
is what it is compared against.  For javac that is `javac --help`; for ECJ it
is the compiler's own `-help` output.

A table written from memory drifts, and it drifts silently.  The four gaps
this gate was written for were all found by running the compiler: `-annotationpath`,
`-warn:none`, `-?:warn` and `-help:warn`.  The first of them cost the whole
annotation path, exactly like the missing `--class-path` cost javac its
classpath: the option was unknown, and the value that followed it was not a
source file either, so both vanished.

The check is one-directional on purpose: an option ECJ lists must be in the
table.  The other direction is not a defect -- the table knows javac's options
and ECJ's retired ones -- so those are reported but do not fail the gate.

The ATTRIBUTION is checked too, and it is the part that a name-only check
misses: the capture's lookup is compiler-aware, so an option that belongs to
another compiler is rejected.  ECJ and sjavac both have `-copy`, so a table
that attributed sjavac's `-copy` to ECJ would look complete by name while the
capture dropped the option.

Usage:  python scripts/check_compile_options_ecj.py [--ecj <jar>]
"""
import io
import pathlib
import re
import subprocess
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent
SOURCE = (
    REPO
    / "plumb-build/plumb-build-capture/src/main/java/org/plumb/build/capture/CompilerOption.java"
)

DEFAULT_ECJ = r"D:\cache\mvn_repo\org\eclipse\jdt\ecj\3.33.0\ecj-3.33.0.jar"

# The options that are not compilation options: help, version, and the
# ignored ones the compiler lists for compatibility.
NOT_COMPILATION = {
    "-?",
    "-help",
    "-v",
    "-version",
    "-showversion",
    "-J",
    "-X",
    "-O",
    "-nowarn",
    "-deprecation",
    "-warn",
    "-properties",
    "-maxProblems",
    "-proceedOnError",
    "-failOnWarning",
    "-verbose",
    "-referenceInfo",
    "-progress",
    "-time",
    "-noExit",
    "-repeat",
    "-inlineJSR",
    "-enableJavadoc",
    "-genericsignature",
    "-Xemacs",
    "-missingNullDefault",
    "-preserveAllLocals",
    "-log",
    "-err",
    "-info",
    "-classNames",
    "-XprintProcessorInfo",
    "-XprintRounds",
}


def ecj_help(jar):
    """The real ECJ's help text."""
    # The java on the PATH, the same way the javac gate finds javac: the
    # compiler is run from the environment, not from a hard-coded path.
    java = "java.exe" if sys.platform == "win32" else "java"
    result = subprocess.run(
        [java, "-jar", str(jar), "-help"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.stdout + result.stderr


def options_from_help(help_text):
    """The option spellings the help lists.

    The help writes an option at the start of a line, indented, and the
    spellings are separated by spaces.  A line may also be a continuation of
    the previous option's description, so only the lines that look like an
    option list are read.
    """
    found = set()
    for line in help_text.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        # An option line starts with a dash (or @ for an argument file).
        if not (stripped.startswith("-") or stripped.startswith("@")):
            continue
        # The rest of the line may hold several spellings, then a
        # description.  The spellings are the tokens before the first token
        # that is not an option.
        tokens = stripped.split()
        for token in tokens:
            if not token.startswith("-"):
                break
            # A token like "-cp" is an option; a token like "-classpath" too.
            # But a description word starting with a dash is not.
            if not re.match(r"^-[A-Za-z?][\w.:=+-]*$", token):
                break
            found.add(token)
    return found


def table_owners():
    """The spelling of every option in the table, and the compiler it belongs to.

    <p>The index holds one option per name, so a later entry wins -- the same
    rule the capture's own index follows.
    """
    source = SOURCE.read_text(encoding="utf-8", errors="replace")
    owners = {}
    for match in re.finditer(
        r"opt\(\s*\"([^\"]+)\"\s*,\s*\"([^\"]*)\"[^;]*?Effect\.\w+,\s*(null|Compiler\.\w+)\)",
        source,
        flags=re.S,
    ):
        own, _canonical, owner = match.groups()
        owners[own] = owner
    return owners


def main():
    jar = DEFAULT_ECJ
    if "--ecj" in sys.argv:
        jar = sys.argv[sys.argv.index("--ecj") + 1]
    if not pathlib.Path(jar).is_file():
        print("FAIL: the ECJ jar is not at %s" % jar)
        print("      The compiler is the yardstick; without it there is nothing to check.")
        sys.exit(1)

    help_text = ecj_help(jar)
    if "Eclipse Compiler for Java" not in help_text:
        print("FAIL: %s did not print the ECJ help" % jar)
        sys.exit(1)

    compiler_options = options_from_help(help_text)
    owners = table_owners()

    print("ECJ lists %d option spelling(s)" % len(compiler_options))
    print()

    failures = []

    missing = sorted(
        name
        for name in compiler_options
        if name not in owners and name not in NOT_COMPILATION
    )
    if missing:
        failures.append(
            "the real ECJ has %d option(s) the table does not know: %s"
            % (len(missing), ", ".join(missing))
        )
    else:
        print("every option the real ECJ lists is in the table")

    # The attribution.  An option ECJ has must be reachable by ECJ's lookup.
    unreachable = sorted(
        name
        for name in compiler_options
        if name in owners
        and owners[name] not in ("null", "Compiler.ECJ")
        and name not in NOT_COMPILATION
    )
    if unreachable:
        failures.append(
            "the real ECJ has %d option(s) the ECJ lookup would reject: %s"
            % (len(unreachable), ", ".join(unreachable))
        )
    else:
        print("every option the real ECJ lists is reachable by ECJ's lookup")

    print()
    if failures:
        print("FAIL: %d defect(s) in the ECJ option table" % len(failures))
        for f in failures:
            print("  " + f)
        sys.exit(1)
    print("OK: the option table knows every option the real ECJ has")


if __name__ == "__main__":
    main()
