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


def swap_in_entry(text, first_arg, old_token, new_token, occurrence=1):
    """Swap one token inside the option entry that starts with `first_arg`.

    The entries in the table are formatted across several lines when
    they are long:

        out.add(
            opt(
                "-cp",
                "--classpath",
                Value.PATH_LIST,
                ...),

    So a mutation that replaces the whole call as one string matches
    nothing -- and "matched nothing" is reported as a NO-OP, which
    says the mutation proves NOTHING.  The option table is what is
    being checked, not its layout.

    So the entry is located by its FIRST argument and the token is
    swapped inside that entry only.

    `occurrence` picks WHICH copy of the token is swapped.  An option
    whose spelling and canonical name are the same string has the
    token twice, and the canonical name is the second one.
    """
    pattern = re.compile(
        r'opt\(\s*"' + re.escape(first_arg) + r'".*?\)', re.S)
    match = pattern.search(text)
    if not match:
        return text
    entry = match.group(0)
    if entry.count(old_token) < occurrence:
        return text
    idx = -1
    for _ in range(occurrence):
        idx = entry.index(old_token, idx + 1)
    new_entry = (entry[:idx] + new_token
                 + entry[idx + len(old_token):])
    return text[:match.start()] + new_entry + text[match.end():]


def remove_entry(text, first_arg):
    """Remove the whole option entry that starts with `first_arg`."""
    pattern = re.compile(
        r'\n *out\.add\(\s*opt\(\s*"' + re.escape(first_arg)
        + r'".*?\)\s*,?\s*\)', re.S)
    match = pattern.search(text)
    if not match:
        return text
    return text[:match.start()] + text[match.end():]


MUTANTS = [
    (
        "an option is moved to the wrong repetition rule",
        "source",
        lambda t: swap_in_entry(
            t, "-cp", "Repeat.LAST_WINS", "Repeat.ALL_APPLY"),
    ),
    (
        "an option loses its value kind",
        "source",
        lambda t: swap_in_entry(
            t, "-encoding", "Value.SINGLE", "Value.NONE"),
    ),
    (
        "an option that does not compile is marked as compiling",
        "source",
        lambda t: swap_in_entry(
            t, "-proc:only", "Effect.NOT_COMPILATION", "Effect.COMPILATION"),
    ),
    (
        "an option is removed from the table",
        "source",
        lambda t: remove_entry(t, "--add-modules"),
    ),
    (
        "an option's canonical name is changed",
        "source",
        lambda t: swap_in_entry(
            t, "--release", '"--release"', '"--release-level"', occurrence=2),
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
    # The per-compiler cases.  ECJ and sjavac spell an option the same way
    # and mean different things by it -- ECJ's `-copy` is last-wins and
    # sjavac's is all-apply -- so a gate that compared the spelling alone
    # would see only one of them.
    (
        "an option's repetition rule is moved to the other compiler",
        "source",
        lambda t: swap_in_entry(
            t, "-copy", "Repeat.LAST_WINS", "Repeat.ALL_APPLY"),
    ),
    (
        "an option is moved from one compiler to another",
        "source",
        lambda t: swap_in_entry(
            t, "-annotationpath", "Compiler.ECJ", "Compiler.SJAVAC"),
    ),
    (
        "the reference gives an option to a compiler we do not",
        "baseline",
        lambda t: t.replace(
            "[[option]]\nname = --state-dir\naliases = [--state-dir=]\n"
            "value = single\nrepeat = last-wins\ncompiler = sjavac\n",
            "[[option]]\nname = --state-dir\naliases = [--state-dir=]\n"
            "value = single\nrepeat = last-wins\ncompiler = ecj\n",
        ),
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
