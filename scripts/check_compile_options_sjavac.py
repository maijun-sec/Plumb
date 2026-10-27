#!/usr/bin/env python3
"""Compare the capture's sjavac option table against the real sjavac.

sjavac was removed from the JDK after Java 11, so the reference is a JDK 11.
The authority is `com.sun.tools.sjavac.options.Option`: every constant in that
enum carries the spelling the compiler accepts, and the enum IS the compiler's
option table.

The enum is read by reflection, because `jdk.compiler` does not export
`com.sun.tools.sjavac.options`.  The dump program is compiled and run with the
JDK 11's own javac and java.

Two things are measured:

  * which spellings the compiler knows, and
  * whether the compiler CONSUMES the option or FORWARDS it to javac.

The second one is the fact that matters.  sjavac keeps its own option table and
hands everything it does not recognise to javac.  An option sjavac forwards is
a javac option, and attributing it to sjavac would make javac's own lookup
reject it.  So the table has to agree with the compiler on both.

Usage:  python scripts/check_compile_options_sjavac.py [--jdk <jdk-home>]
"""
import io
import pathlib
import re
import subprocess
import sys
import tempfile

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent
SOURCE = (
    REPO
    / "plumb-build/plumb-build-capture/src/main/java/org/plumb/build/capture/CompilerOption.java"
)

DEFAULT_JDK = r"D:\cache\jdk-probe\jdk11\jdk-11.0.32.1+1"

# The program that reads the compiler's own option table and classifies every
# argument.  It is written to a temporary file, compiled with the JDK 11's
# javac, and run with the JDK 11's java -- so the reference is the compiler and
# not a copy of the compiler's source.
PROBE = r"""
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class SjavacTable {
  public static void main(String[] args) throws Exception {
    Class<?> optionClass = Class.forName("com.sun.tools.sjavac.options.Option");
    Field argField = optionClass.getField("arg");
    Field descriptionField = optionClass.getDeclaredField("description");
    descriptionField.setAccessible(true);
    Method values = optionClass.getMethod("values");

    Class<?> optionsClass = Class.forName("com.sun.tools.sjavac.options.Options");
    Method parseArgs = optionsClass.getMethod("parseArgs", String[].class);
    Method getJavacArgs = optionsClass.getMethod("getJavacArgs");

    for (Object option : (Object[]) values.invoke(null)) {
      String arg = (String) argField.get(option);
      String description = (String) descriptionField.get(option);

      // The value form: does the compiler take the value in the NEXT token?
      // The options that spell a trailing "=" or ":" are the ones where this
      // is not obvious, so every option is probed both ways.
      String separate = classify(parseArgs, getJavacArgs, arg, "value");
      String joined = classify(parseArgs, getJavacArgs, arg, arg + "value");

      System.out.println("OPT\t" + arg + "\t" + escape(description)
          + "\t" + separate + "\t" + joined);
    }

    // The options sjavac does NOT have are handed on to javac.  They are not
    // in sjavac's own enum, so the enum cannot show them -- but the
    // classification still can, and the capture needs to know that they are
    // javac's options rather than sjavac's.
    //
    // The list is javac's own option set.  It is spelled out here rather than
    // derived, because the point is to measure which of javac's options
    // sjavac forwards -- and the answer decides the table's attribution.
    String[] javacOptions = {
      "-encoding", "-source", "-target", "--release", "-g", "-g:none",
      "-nowarn", "-proc:none", "-proc:only", "-proc:full", "-processor",
      "-processorpath", "--processor-path", "-bootclasspath", "--boot-class-path",
      "--add-modules", "--add-exports", "--add-reads", "--patch-module",
      "-Xlint", "-parameters", "-A", "-Xmaxerrs", "-Xmaxwarns", "-nowarn",
      "--enable-preview", "-implicit:none", "-implicit:class", "--system",
      "-s", "-processor-module-path",
    };
    for (String arg : javacOptions) {
      String separate = classify(parseArgs, getJavacArgs, arg, "value");
      String joined = classify(parseArgs, getJavacArgs, arg, arg + "value");
      System.out.println("JAVAC\t" + arg + "\t" + escape("a javac option")
          + "\t" + separate + "\t" + joined);
    }
  }

  private static String classify(Method parseArgs, Method getJavacArgs,
      String option, String value) {
    try {
      Object options = parseArgs.invoke(null, (Object) (Object[]) new String[] {option, value});
      @SuppressWarnings("unchecked")
      List<String> javacArgs = (List<String>) getJavacArgs.invoke(options);
      return javacArgs.contains(option) ? "FORWARDED" : "CONSUMED";
    } catch (Exception e) {
      return "REJECTED";
    }
  }

  private static String escape(String s) {
    return s.replace("\t", " ").replace("\r", " ").replace("\n", " ");
  }
}
"""


def find_jdk():
    """The JDK 11 that carries sjavac.

    sjavac is gone from a modern JDK, so the reference has to come from a JDK
    that still has it.  The location is a parameter rather than a guess, and
    the gate FAILS when it is not there -- a gate that quietly skips is a gate
    that quietly stops checking.
    """
    for candidate in sys.argv[sys.argv.index("--jdk") + 1 :] if "--jdk" in sys.argv else []:
        return pathlib.Path(candidate)
    return pathlib.Path(DEFAULT_JDK)


def read_compiler_table(jdk):
    """The real sjavac's option table, read from the compiler itself."""
    java = jdk / "bin" / ("java.exe" if sys.platform == "win32" else "java")
    javac = jdk / "bin" / ("javac.exe" if sys.platform == "win32" else "javac")
    if not java.is_file() or not javac.is_file():
        return None, "the JDK 11 at %s has no java/javac" % jdk

    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        probe = tmp / "SjavacTable.java"
        probe.write_text(PROBE, encoding="utf-8")
        compile_result = subprocess.run(
            [str(javac), "-d", str(tmp), str(probe)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=300,
        )
        if compile_result.returncode != 0:
            return None, "the probe did not compile: %s" % compile_result.stderr.strip()

        run_result = subprocess.run(
            [
                str(java),
                "--add-modules",
                "jdk.compiler",
                "--add-exports",
                "jdk.compiler/com.sun.tools.sjavac.options=ALL-UNNAMED",
                "--add-opens",
                "jdk.compiler/com.sun.tools.sjavac.options=ALL-UNNAMED",
                "-cp",
                str(tmp),
                "SjavacTable",
            ],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=300,
        )
        if run_result.returncode != 0:
            return None, "the probe did not run: %s" % run_result.stderr.strip()
        return run_result.stdout, None


def table_options(compiler):
    """The option names the table declares for one compiler.

    Both the option's own spelling and its CANONICAL name are returned: the
    compiler spells an option one way, and the table's canonical name is the
    thing the capture reports.
    """
    source = SOURCE.read_text(encoding="utf-8", errors="replace")
    names = set()
    for match in re.finditer(
        r"opt\(\s*\"([^\"]+)\"\s*,\s*\"([^\"]*)\"[^;]*?Effect\.\w+,\s*(null|Compiler\.\w+)\)",
        source,
        flags=re.S,
    ):
        own, canonical, owner = match.groups()
        if owner not in ("null", "Compiler." + compiler):
            continue
        for name in (own, canonical):
            if name and not name.startswith("source_files"):
                names.add(name)
    return names


def table_owners():
    """The spelling of every option in the table, and the compiler it belongs to.

    <p>The NAME of an option is not enough to know whether the capture can use
    it.  Two compilers may spell an option the same way -- ECJ and sjavac both
    have {@code -copy} -- and the capture's lookup is compiler-aware: an option
    that belongs to one compiler is rejected by the other's lookup.  So a gate
    that only checked the names would pass a table where sjavac's {@code -copy}
    was attributed to ECJ, and the capture would drop the option and read its
    value as a source file.

    <p>The canonical name is not a spelling the compiler would accept, so the
    lookup is decided by the option's OWN spelling.
    """
    source = SOURCE.read_text(encoding="utf-8", errors="replace")
    owners = {}
    for match in re.finditer(
        r"opt\(\s*\"([^\"]+)\"\s*,\s*\"([^\"]*)\"[^;]*?Effect\.\w+,\s*(null|Compiler\.\w+)\)",
        source,
        flags=re.S,
    ):
        own, _canonical, owner = match.groups()
        if own.startswith("source_files"):
            continue
        # The index holds one option per name, so a later entry wins -- the
        # same rule the capture's own index follows.
        owners[own] = owner
    return owners


def main():
    jdk = find_jdk()
    if not jdk.is_dir():
        print("FAIL: the sjavac reference JDK is not at %s" % jdk)
        print("      sjavac was removed from the JDK after Java 11, so the")
        print("      reference has to be a JDK 11 that still carries it.")
        sys.exit(1)

    stdout, error = read_compiler_table(jdk)
    if stdout is None:
        print("FAIL: the real sjavac could not be read: %s" % error)
        sys.exit(1)

    rows = []
    javac_rows = []
    for line in stdout.splitlines():
        if line.startswith("OPT\t") or line.startswith("JAVAC\t"):
            parts = line.split("\t")
            entry = (parts[1], parts[2], parts[3], parts[4])
            if line.startswith("OPT\t"):
                rows.append(entry)
            else:
                javac_rows.append(entry)

    if not rows:
        print("FAIL: the real sjavac reported no options -- the reference is empty")
        sys.exit(1)

    print("the real sjavac has %d option(s)" % len(rows))

    # The options the real sjavac hands on to javac rather than consuming.
    # These are javac's options, and the capture has to treat them as javac's.
    FORWARDED = {row[0] for row in javac_rows if row[2] == "FORWARDED"}
    if FORWARDED:
        print("the real sjavac forwards %d javac option(s) to javac: %s"
              % (len(FORWARDED), ", ".join(sorted(FORWARDED))))

    table = table_options("SJAVAC")
    print("the table declares %d sjavac option(s)" % len(table))
    print()

    failures = []

    # The spelling the compiler uses is the one that matters.  The canonical
    # name is what the capture reports, and it is not a spelling the compiler
    # would accept -- so the compiler's spelling is what the table has to hold.
    compiler_spellings = {row[0] for row in rows}

    missing = sorted(compiler_spellings - table)
    if missing:
        failures.append(
            "the real sjavac has %d option(s) the table does not know: %s"
            % (len(missing), ", ".join(missing))
        )
    else:
        print("every option the real sjavac has is in the table")

    # The direction that matters most, and it is about the ATTRIBUTION rather
    # than the name.  The capture's lookup is compiler-aware: an option that
    # belongs to one compiler is rejected by the other's lookup.  So:
    #
    #   an option sjavac CONSUMES must be reachable by sjavac's lookup, and
    #   an option sjavac FORWARDS must NOT be claimed for sjavac, because
    #   then javac's own lookup would reject it.
    #
    # Checking the names alone would miss both: ECJ and sjavac both have
    # `-copy`, so a table that attributed sjavac's `-copy` to ECJ would look
    # complete while the capture dropped the option.
    owners = table_owners()

    unreachable = sorted(
        spelling
        for spelling in compiler_spellings
        if owners.get(spelling) not in ("null", "Compiler.SJAVAC")
    )
    if unreachable:
        failures.append(
            "the real sjavac consumes %d option(s) the sjavac lookup would reject: %s"
            % (len(unreachable), ", ".join(unreachable))
        )
    else:
        print("every option the real sjavac consumes is reachable by sjavac's lookup")

    wrongly_claimed = sorted(
        spelling
        for spelling in FORWARDED
        if owners.get(spelling) == "Compiler.SJAVAC"
    )
    if wrongly_claimed:
        failures.append(
            "the real sjavac forwards %d option(s) to javac, so they must not be "
            "claimed for sjavac: %s" % (len(wrongly_claimed), ", ".join(wrongly_claimed))
        )
    else:
        print("no option the real sjavac forwards to javac is claimed as sjavac's")

    # The value form.  sjavac takes the value of an option from the NEXT
    # token; the joined form is rejected for some options.  A table that read
    # the value from the option's own token would lose it.
    next_token_only = sorted(
        row[0] for row in rows if row[2] == "CONSUMED" and row[3] == "REJECTED"
    )
    if next_token_only:
        print(
            "%d sjavac option(s) take their value from the next token only: %s"
            % (len(next_token_only), ", ".join(next_token_only))
        )

    print()
    if failures:
        print("FAIL: %d defect(s) in the sjavac option table" % len(failures))
        for f in failures:
            print("  " + f)
        sys.exit(1)
    print("OK: the option table knows every option the real sjavac has")


if __name__ == "__main__":
    main()
