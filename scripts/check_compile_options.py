#!/usr/bin/env python3
"""Check the capture's option table against the compiler's own list.

The option table is supposed to be the compiler's option set, so the
compiler is what it is compared against: `javac --help` and
`javac --help-extra` are the authority on which options exist and how
they are spelled.

A table written from memory drifts, and it drifts silently.  The double-dash
spelling of an option is the usual casualty: `--class-path` was missing,
and the cost was the WHOLE CLASSPATH -- the option was unknown, and the
value that followed it was not a source file either, so both vanished.

The check is one-directional on purpose: an option javac lists must be in
our table.  The other direction is not a defect -- we support options from
other compilers, and options javac has since retired -- so those are
reported but do not fail the gate.

The families are the fiddly part.  javac writes `-proc:{none,only,full}`
and `-g:{lines,vars,source}`, so the help lists the FAMILY, and the
individual values are what a command line actually carries.  The gate
expands a family into its values before comparing, because the table holds
the values.

Usage:  python scripts/check_compile_options.py
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

# The options that are NOT compilation options.  They are diagnostics, help
# or JVM flags, and a capture that treated them as compile options would
# report a compile that never happened.
NOT_COMPILATION = {
    "--help",
    "-help",
    "-?",
    "--help-extra",
    "-X",
    "--version",
    "-version",
    "-J",
    "-Xdoclint",
    "-Xdiags",
    "-XprintProcessorInfo",
    "-XprintRounds",
    "-Xpkginfo",
    "-Xplugin",
    "-Xprefer",
}

# The options javac lists as a family: the name, and the values it takes.
# The table holds the VALUES, because a command line carries a value.
FAMILIES = {
    "-proc": ["none", "only", "full"],
    "-implicit": ["none", "class"],
    "-g": ["lines", "vars", "source", "none"],
}

# The options that are attached to their value: `-Akey[=value]`.  The table
# holds the option itself, and javac's help writes the option plus a
# placeholder -- so the help's spelling is the option with something
# appended to it.
ATTACHED = {"-A"}

# The options whose help spelling carries a VALUE, so the table's name is a
# PREFIX of what the help writes.  javac writes "-Djava.ext.dirs" and the
# table holds "-Djava.ext.dirs", but the help's spelling of an attached
# value runs the two together.
PREFIXES = {"-Djava": "-Djava.ext.dirs", "-Djava.endorsed": "-Djava.endorsed.dirs"}

failures = []
notes = []


def note(msg):
    notes.append(msg)
    print(msg)


if not SOURCE.is_file():
    print("FAIL: the option table is missing at %s" % SOURCE)
    sys.exit(1)

source = SOURCE.read_text(encoding="utf-8", errors="replace")

# The option names the table declares.
#
# The reading spans lines, because a long option name is written as a
# multi-line `opt(...)` call -- and a gate that missed it would report a
# perfectly good option as missing.
declared = set(re.findall(r'opt\(\s*"([^"]+)"', source))
note("the table declares %d option name(s)" % len(declared))


def javac_help():
    """The compiler's own option list, from its help."""
    text = ""
    for flag in ("--help", "--help-extra"):
        try:
            result = subprocess.run(
                ["javac", flag],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=120,
            )
            text += result.stdout or ""
            text += result.stderr or ""
        except (OSError, subprocess.SubprocessError):
            return None
    return text


help_text = javac_help()
if help_text is None:
    print("FAIL: javac could not be run, so the option list could not be read")
    print("      The compiler is the yardstick; without it there is nothing to check.")
    sys.exit(1)

# The option spellings the compiler lists.
listed = set()
for line in help_text.splitlines():
    stripped = line.strip()
    if not stripped.startswith("-"):
        continue
    for match in re.finditer(r"(?<![\w-])(--?[A-Za-z][\w-]*)", stripped):
        listed.add(match.group(1))

note("javac lists %d option spelling(s)" % len(listed))

# Expand a family into the values it takes.
expanded = set()
for name in listed:
    if name in FAMILIES:
        for value in FAMILIES[name]:
            expanded.add("%s:%s" % (name, value))
    else:
        expanded.add(name)

# javac writes the attached options as the option plus a placeholder, so
# the spelling is "-Akey" rather than "-A".  The placeholder is dropped:
# the option is what the table holds.
for name in list(expanded):
    for attached in ATTACHED:
        if name.startswith(attached) and len(name) > len(attached):
            expanded.discard(name)
            expanded.add(attached)

def known(name):
    """True when the table knows this spelling.

    <p>Three ways to know it: the exact spelling, a PREFIX match for an
    option whose help spelling carries a value, or the family the value
    belongs to.
    """
    if name in declared:
        return True
    for prefix, full in PREFIXES.items():
        if name.startswith(prefix) and full in declared:
            return True
    return False


missing = sorted(
    name
    for name in expanded
    if not known(name) and name not in NOT_COMPILATION
)

if missing:
    failures.append(
        "javac lists %d option(s) the table does not know: %s"
        % (len(missing), ", ".join(missing))
    )
else:
    note("every option javac lists is in the table")

# The reverse direction is reported but does not fail: the table knows
# options from other compilers, and options javac has retired.
extra = sorted(declared - expanded)
if extra:
    note(
        "the table knows %d option(s) javac does not list -- other compilers, "
        "or options javac has retired: %s" % (len(extra), ", ".join(extra))
    )

print()
if failures:
    print("FAIL: %d defect(s) in the option table" % len(failures))
    for f in failures:
        print("  " + f)
    sys.exit(1)
print("OK: the option table knows every option javac has")
