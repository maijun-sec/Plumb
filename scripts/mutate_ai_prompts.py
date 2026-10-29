#!/usr/bin/env python3
"""Mutation battery for `scripts/check_ai_prompts.py`.

A prompt gate drifts by *stopping to check*. Each mutant here is a
plausible way that happens:

  - a prompt loses one of its five analysis questions,
  - the reachability explanation is removed,
  - the implicit guarantees are dropped from the third prompt,
  - the two policies are swapped, so the names lie,
  - a deprecated alias stops resolving to the named prompt,
  - the runner stops using the active prompt.

Each mutant is applied to an in-memory BYTE snapshot, the gate is run,
and the file is restored byte-exactly. A mutant that survives is a hole
in the gate; a mutant that could not be built is reported separately,
because "could not build" is not "survived".

    python scripts/mutate_ai_prompts.py
"""
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
GATE = REPO / "scripts" / "check_ai_prompts.py"
PYTHON = sys.executable

PROMPTS = REPO / "plumb-ai" / "src" / "main" / "java" / "org" / "plumb" / "ai" / "prompts" / "FalsePositivePrompts.java"
AGENT = REPO / "plumb-ai" / "src" / "main" / "java" / "org" / "plumb" / "ai" / "prompts" / "AgentPrompts.java"
RUNNER = REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb" / "main" / "ScanRunner.java"


def run_gate():
    proc = subprocess.run([PYTHON, str(GATE)], capture_output=True, text=True,
                          cwd=str(REPO), encoding="utf-8", errors="replace")
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def normalise(text):
    """Collapse the line endings so an anchor matches either way."""
    return text.replace("\r\n", "\n")


# (name, file, the exact text to find, the text to replace it with)
#
# Each anchor must occur EXACTLY ONCE. A non-unique anchor fails
# loudly: mutating the wrong occurrence would test nothing.
MUTANTS = [
    # --- a prompt loses an analysis question --------------------------
    #
    # The shared method is referenced by all three prompts, so removing
    # a question from it hits every prompt at once. That is the point:
    # the question is load-bearing for the whole family.
    ("question-1-removed",
     PROMPTS,
     '          + "QUESTION 1 -- DOES THE SOURCE REALLY INTRODUCE ATTACKER-CONTROLLED DATA?\\n"\n',
     '          + "QUESTION 1 -- (removed)\\n"\n'),
    ("question-2-removed",
     PROMPTS,
     '          + "QUESTION 2 -- DOES THE SINK REALLY CAUSE THIS KIND OF PROBLEM?\\n"\n',
     '          + "QUESTION 2 -- (removed)\\n"\n'),
    ("question-3-removed",
     PROMPTS,
     '          + "QUESTION 3 -- IS THE TRACE ACTUALLY REACHABLE, AND IS EVERY HOP REAL?\\n"\n',
     '          + "QUESTION 3 -- (removed)\\n"\n'),
    ("question-4-removed",
     PROMPTS,
     '          + "QUESTION 4 -- CAN THE CONDITIONS ON THE PATH BE SATISFIED?\\n"\n',
     '          + "QUESTION 4 -- (removed)\\n"\n'),
    ("question-5-removed",
     PROMPTS,
     '          + "QUESTION 5 -- IS THERE AN EFFECTIVE SANITISER ON THE PATH?\\n"\n',
     '          + "QUESTION 5 -- (removed)\\n"\n'),

    # --- the reachability explanation goes ---------------------------
    ("reachability-hop-removed",
     PROMPTS,
     '          + "  - CALL-BY-VALUE SEMANTICS: when a tainted value is passed to a method,\\n"\n',
     '          + "  - (removed)\\n"\n'),

    # --- the implicit guarantees go ----------------------------------
    ("implicit-guarantees-removed",
     PROMPTS,
     '          + "  - DOCUMENTED NON-NULL CONTRACT: the Javadoc for the parameter, field, or "\n',
     '          + "  - (removed) "\n'),

    # --- the policies are swapped, so the names lie -------------------
    #
    # The gate checks that each prompt states the default its NAME
    # promises. Swapping the two bodies makes the names wrong, which is
    # exactly the drift the rename exists to prevent.
    ("strict-prompt-defaults-to-fp",
     PROMPTS,
     '          + "neutralises the issue). Be strict but fair: default to TP if you cannot "\n',
     '          + "neutralises the issue). Be strict but fair: default to FP if you cannot "\n'),

    # --- a deprecated alias stops resolving ---------------------------
    ("alias-points-at-the-wrong-prompt",
     AGENT,
     "      FalsePositivePrompts.FALSE_POSITIVE_REDUCTION_WITH_IMPLICIT_GUARANTEES;",
     "      FalsePositivePrompts.FALSE_POSITIVE_REDUCTION;"),

    # --- the alias holds a COPY of the text ---------------------------
    #
    # A copy is a second source of truth: the named prompt could be
    # edited and the alias would keep the old text.
    ("alias-holds-a-copy",
     AGENT,
     "  public static final String FP_REDUCER = FalsePositivePrompts.FALSE_POSITIVE_REDUCTION;",
     '  public static final String FP_REDUCER = "a copy of the prompt text";'),

    # --- the runner stops using the active prompt --------------------
    ("runner-uses-the-deprecated-alias",
     RUNNER,
     "org.plumb.ai.prompts.FalsePositivePrompts.FALSE_POSITIVE_REDUCTION,",
     "org.plumb.ai.prompts.AgentPrompts.FP_REDUCER,"),
]


def main():
    for path in (PROMPTS, AGENT, RUNNER):
        if not path.is_file():
            print("FAIL: %s does not exist" % path)
            return 1

    code, out = run_gate()
    if code != 0:
        print("FAIL: the gate does not pass on the current tree")
        print(out)
        return 1

    killed = 0
    survived = []
    broken = []

    for name, target, old, new in MUTANTS:
        original = target.read_bytes()
        text = original.decode("utf-8")
        flat = normalise(text)
        old_flat = normalise(old)
        new_flat = normalise(new)
        count = flat.count(old_flat)
        if count != 1:
            print("FAIL: %s -- the anchor occurs %d times, expected exactly 1"
                  % (name, count))
            broken.append(name)
            continue
        try:
            mutated = flat.replace(old_flat, new_flat)
            # Write the mutant back with the ORIGINAL line endings, so
            # the only difference is the mutation itself.
            if "\r\n" in text:
                mutated = mutated.replace("\n", "\r\n")
            target.write_bytes(mutated.encode("utf-8"))
            code, out = run_gate()
        finally:
            # Restore the EXACT bytes. A `git checkout --` would discard
            # any uncommitted work in the tree.
            target.write_bytes(original)

        if code == 0:
            survived.append(name)
            print("SURVIVED  %s" % name)
        else:
            killed += 1
            print("killed    %s" % name)

    print()
    print("%d/%d mutant(s) killed" % (killed, len(MUTANTS)))
    if broken:
        print("could not build: %s" % ", ".join(broken))
        return 1
    if survived:
        print("survived: %s" % ", ".join(survived))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
