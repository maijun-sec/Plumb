#!/usr/bin/env python3
"""Mutation test for scripts/check_compile_crosscheck.py.

A gate that passes because it checked nothing is worse than no gate.  This
script mutates the cross-check in ways that MUST make the gate fail, and
checks that it does.

Each mutant is applied to a temporary copy of the repository's files; the
real files are never touched.

Usage:  python scripts/mutate_compile_crosscheck.py
"""
import io
import pathlib
import re
import subprocess
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent

CROSS_CHECK = (
    REPO
    / "plumb-build/plumb-build-maven/src/main/java/org/plumb/build/maven/CompileCrossCheck.java"
)
DISCREPANCY = (
    REPO / "plumb-build/plumb-build-core/src/main/java/org/plumb/build/CompileDiscrepancy.java"
)
ADAPTER = (
    REPO
    / "plumb-build/plumb-build-maven/src/main/java/org/plumb/build/maven/MavenResolverAdapter.java"
)
MAIN = REPO / "plumb-main/src/main/java/org/plumb/main/SastMain.java"

GATE = REPO / "scripts" / "check_compile_crosscheck.py"

MUTABLE = {
    "cross_check": CROSS_CHECK,
    "discrepancy": DISCREPANCY,
    "adapter": ADAPTER,
    "main": MAIN,
}
ORIGINAL = {name: path.read_text(encoding="utf-8", errors="replace") for name, path in MUTABLE.items()}


def restore():
    for name, path in MUTABLE.items():
        path.write_text(ORIGINAL[name], encoding="utf-8")


def run_gate():
    result = subprocess.run(
        [sys.executable, str(GATE)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.returncode, result.stdout


def drop_a_parameter(text, constant):
    """Stop the cross-check from comparing a parameter.

    The whole comparison call is removed.  Replacing the parameter's NAME
    would not do: the name appears in the constant, in the comment and in
    the JSON field, so the name would still be there and a gate that looked
    for it would pass.
    """
    # The call spans from the constant to the closing paren of the call.
    pattern = re.compile(
        r"compare\w*\(\s*\n?\s*byParameter,\s*\n?\s*" + constant + r",.*?\);",
        flags=re.S,
    )
    return pattern.sub("/* the comparison is removed */", text, count=1)


def resolve_the_difference(text):
    """Make the cross-check keep only the declared value."""
    return text.replace(
        "new CompileDiscrepancy(parameter, declared, observed, detail)",
        "new CompileDiscrepancy(parameter, declared, declared, detail)",
        1,
    )


def report_unknowns_as_differences(text):
    """Make the cross-check report an unknown value as a difference."""
    return text.replace(
        "if (declared == null || observed == null) {\n      return;\n    }",
        "if (declared == null && observed == null) {\n      return;\n    }",
        1,
    )


def drop_the_sorting(text):
    """Make the differences come out in an arbitrary order.

    Every occurrence has to go: the map is named in the declaration AND in
    the two comparison methods' parameters, and a gate that looks for the
    type would still find one of them.
    """
    return text.replace("TreeMap<String, CompileDiscrepancy>", "LinkedHashMap<String, CompileDiscrepancy>").replace(
        "import java.util.TreeMap;", "import java.util.LinkedHashMap;", 1
    )


def drop_the_adapter_sorting(text):
    """Make the adapter's result order depend on the inputs' order."""
    return text.replace("out.sort(", "if (false) out.sort(", 1)


def drop_the_cli_call(text):
    """Make the CLI stop running the cross-check."""
    return text.replace("resolver.crossCheck(projectRoot, captureDir);", "List.of();", 1)


MUTANTS = [
    ("the cross-check stops comparing the language level", "cross_check", lambda t: drop_a_parameter(t, "LANGUAGE_LEVEL")),
    ("the cross-check stops comparing the encoding", "cross_check", lambda t: drop_a_parameter(t, "ENCODING")),
    ("the cross-check stops comparing the classpath", "cross_check", lambda t: drop_a_parameter(t, "CLASSPATH")),
    ("the cross-check stops comparing the source files", "cross_check", lambda t: drop_a_parameter(t, "SOURCE_FILES")),
    ("the cross-check resolves the difference instead of reporting it", "cross_check", resolve_the_difference),
    ("the cross-check reports an unknown value as a difference", "cross_check", report_unknowns_as_differences),
    ("the cross-check stops sorting its differences", "cross_check", drop_the_sorting),
    ("the adapter stops sorting the result", "adapter", drop_the_adapter_sorting),
    ("the CLI stops running the cross-check", "main", drop_the_cli_call),
]

survivors = []
no_ops = []
try:
    for name, target, mutate in MUTANTS:
        restore()
        path = MUTABLE[target]
        path.write_text(mutate(ORIGINAL[target]), encoding="utf-8")

        # A mutant that did not change anything proves nothing: it would be
        # reported as "the gate let a drift through" when in fact the drift
        # was never introduced.
        changed = any(
            p.read_text(encoding="utf-8") != o for p, o in zip(MUTABLE.values(), ORIGINAL.values())
        )
        if not changed:
            no_ops.append(name)
            print("NO-OP     %s  (the mutation did not apply)" % name)
            continue

        code, out = run_gate()
        if code == 0:
            survivors.append(name)
            print("SURVIVED  %s" % name)
        else:
            first = ""
            for line in out.splitlines():
                if line.startswith("  "):
                    first = line.strip()
                    break
            print("killed    %s  (%s)" % (name, first))
finally:
    restore()

print()
if no_ops:
    print("FAIL: %d mutant(s) did not apply -- they prove nothing" % len(no_ops))
    for s in no_ops:
        print("  " + s)
    sys.exit(1)
if survivors:
    print("FAIL: %d mutant(s) survived -- the gate is checking air" % len(survivors))
    for s in survivors:
        print("  " + s)
    sys.exit(1)
print("OK: all %d mutants were killed" % len(MUTANTS))
