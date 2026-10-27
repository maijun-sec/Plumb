#!/usr/bin/env python3
"""Mutation test for scripts/check_capture_options.py.

A gate that passes because it checked nothing is worse than no gate.  This
script mutates the option table and the reference baseline in ways that MUST
make the gate fail, and checks that it does.

Each mutant is applied to the real files and then reverted; the working tree
is left as it was found.

Usage:  python scripts/mutate_capture_options.py
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
BASELINE = (
    REPO
    / "plumb-build/plumb-build-capture/src/main/resources/coverity-java-options.toml"
)
GATE = REPO / "scripts" / "check_capture_options.py"

original_source = SOURCE.read_text(encoding="utf-8")
original_baseline = BASELINE.read_text(encoding="utf-8")


def run_gate():
    result = subprocess.run(
        [sys.executable, str(GATE)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.returncode, result.stdout


MUTANTS = [
    (
        "an option is moved to the wrong repetition rule",
        "source",
        lambda t: t.replace(
            'out.add(opt("-cp", "--classpath", Value.PATH_LIST, Repeat.LAST_WINS, '
            "Effect.COMPILATION, null));",
            'out.add(opt("-cp", "--classpath", Value.PATH_LIST, Repeat.ALL_APPLY, '
            "Effect.COMPILATION, null));",
        ),
    ),
    (
        "an option loses its value kind",
        "source",
        lambda t: t.replace(
            'out.add(opt("-encoding", "--encoding", Value.SINGLE, Repeat.LAST_WINS, '
            "Effect.COMPILATION, null));",
            'out.add(opt("-encoding", "--encoding", Value.NONE, Repeat.LAST_WINS, '
            "Effect.COMPILATION, null));",
        ),
    ),
    (
        "an option that does not compile is marked as compiling",
        "source",
        lambda t: t.replace(
            'out.add(opt("-proc:only", "-proc:only", Value.NONE, Repeat.LAST_WINS, '
            "Effect.NOT_COMPILATION, null));",
            'out.add(opt("-proc:only", "-proc:only", Value.NONE, Repeat.LAST_WINS, '
            "Effect.COMPILATION, null));",
        ),
    ),
    (
        "an option is removed from the table",
        "source",
        lambda t: t.replace(
            'out.add(opt("--add-modules", "--add-modules", Value.COMMA_LIST, '
            "Repeat.ALL_APPLY, Effect.COMPILATION, null));",
            "",
        ),
    ),
    (
        "an option's canonical name is changed",
        "source",
        lambda t: t.replace(
            'out.add(opt("--release", "--release", Value.SINGLE, Repeat.LAST_WINS, '
            "Effect.COMPILATION, null));",
            'out.add(opt("--release", "--release-level", Value.SINGLE, Repeat.LAST_WINS, '
            "Effect.COMPILATION, null));",
        ),
    ),
    (
        "the reference gains an option we do not have",
        "baseline",
        lambda t: t.replace(
            "[[option]]\nname = --release\n",
            "[[option]]\nname = --release\n\n[[option]]\nname = --not-implemented\n"
            'aliases = []\nvalue = single\nrepeat = last-wins\n',
        ),
    ),
    (
        "the reference loses an option we do have",
        "baseline",
        lambda t: t.replace("[no-value]\n--enable-preview\n", "[no-value]\n"),
    ),
]

survivors = []
no_ops = []
try:
    for name, target, mutate in MUTANTS:
        SOURCE.write_text(original_source, encoding="utf-8")
        BASELINE.write_text(original_baseline, encoding="utf-8")
        if target == "source":
            SOURCE.write_text(mutate(original_source), encoding="utf-8")
        else:
            BASELINE.write_text(mutate(original_baseline), encoding="utf-8")

        # A mutant that did not change anything proves nothing.
        changed = (
            SOURCE.read_text(encoding="utf-8") != original_source
            or BASELINE.read_text(encoding="utf-8") != original_baseline
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
    SOURCE.write_text(original_source, encoding="utf-8")
    BASELINE.write_text(original_baseline, encoding="utf-8")

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
