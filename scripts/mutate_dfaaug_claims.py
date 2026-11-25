#!/usr/bin/env python3
"""Mutation battery for `scripts/check_dfaaug_claims.py`.

A design document drifts by *describing a mechanism that changed*. Each
mutant here is a plausible way that happens:

  - the naming convention table goes back to the wrong rule ids,
  - a checker that was removed is still described,
  - a checker that was registered is still described as unregistered,
  - a snippet goes back to using an API that does not exist,
  - a variant that became inert is described as live,
  - a class that does not exist is named.

Each mutant is applied to an in-memory BYTE snapshot, the gate is run,
and the file is restored byte-exactly. A mutant that survives is a hole
in the gate; a mutant that could not be built is reported separately,
because "could not build" is not "survived".

    python scripts/mutate_dfaaug_claims.py
"""
import pathlib
import re
import subprocess
import sys

# A gate that dies while printing its result has measured nothing. The
# console's code page is not UTF-8 on every machine, so force the streams
# to UTF-8 and replace what cannot be encoded instead of crashing.
#
# reconfigure() and not a fresh TextIOWrapper: this file is imported as a
# module by another gate, and a second wrapper would take over the same
# buffer. The first wrapper would then be collected, and collecting it
# closes the buffer the new one is still using -- the next gate to wrap
# the stream would die on a closed buffer, having measured nothing.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


REPO = pathlib.Path(__file__).resolve().parent.parent
GATE = REPO / "scripts" / "check_dfaaug_claims.py"
DOC = REPO / "docs" / "tech" / "DFA-AUGMENTATION-DESIGN.md"
ZH_DOC = REPO / "docs" / "zh" / "tech" / "DFA-AUGMENTATION-DESIGN.md"
SCAN_RUNNER = (REPO / "plumb-main" / "src" / "main" / "java" / "org" /
               "plumb" / "main" / "ScanRunner.java")
SCAN_CONFIG = (REPO / "plumb-main" / "src" / "main" / "java" / "org" /
               "plumb" / "main" / "ScanConfig.java")
DFA_ENGINE = (REPO / "plumb-dfa" / "src" / "main" / "java" / "org" /
              "plumb" / "dfa" / "engine" / "DfaEngine.java")
PYTHON = sys.executable


def run_gate():
    proc = subprocess.run([PYTHON, str(GATE)], capture_output=True, text=True,
                          cwd=str(REPO), encoding="utf-8", errors="replace")
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


# (name, file, the exact text to find, the text to replace it with)
#
# Each anchor must occur EXACTLY ONCE. A non-unique anchor fails
# loudly: mutating the wrong occurrence would test nothing.
MUTANTS = [
    # --- the rule ids go back to the wrong ones ------------------------
    ("wrong-ast-rule-id",
     DOC,
     "| AST | `ast-<category>-<bare>` | `ast-quality-uninit-nonnull` |",
     "| AST | `ast-<category>-<bare>` | `ast-style-uninit-nonnull-style` |"),
    ("wrong-dfa-rule-id",
     DOC,
     "| DFA, structural | `dfa-<category>-<bare>` | `dfa-controlflow-deadlock-ab-ba` |",
     "| DFA, structural | `dfa-<category>-<bare>` | `plumb.dfa.deadlock-ab-ba` |"),
    ("wrong-taint-rule-id",
     DOC,
     "| DFA, taint | `dfa-taint-<bare>` | `dfa-taint-sql-injection` |",
     "| DFA, taint | `dfa-taint-<bare>` | `plumb.taint.sql-injection` |"),
    ("wrong-prose-ast-rule-id",
     DOC,
     "rule id is **`ast-quality-uninit-nonnull`**",
     "rule id is **`ast-style-uninit-nonnull-style`**"),
    ("wrong-orm-rule-id",
     DOC,
     "`orm-load-null-check-dfa`, `orm-unnecessary-get-dfa`, `orm-lost-update-dfa`,",
     "`dfa-orm-load-null-check`, `dfa-orm-unnecessary-get`, `dfa-orm-lost-update`,"),

    # --- a class that does not exist ----------------------------------
    ("phantom-checker",
     DOC,
     "### 3.2 -DA: OrmLoadNullCheckDfaChecker",
     "### 3.2 -DA: OrmLoadNullCheckDfaTypoChecker"),
    ("phantom-applier",
     DOC,
     "the caller-side machinery writes",
     "the caller-side `SummaryApplierX` writes"),

    # --- the 'not registered' claim flips ------------------------------
    ("unregistered-becomes-registered",
     DOC,
     "are still in the source code but are **not registered**",
     "are still in the source code and are **registered**"),

    # --- the registration stops being conditional ---------------------
    #
    # The document says the two race checkers are not registered BY
    # DEFAULT. That is a claim about two things: the registration has
    # to be conditional, and the switch has to be off by default. Both
    # are checkable, so both are mutated here.
    ("unconditional-registration",
     SCAN_RUNNER,
     "    if (config.runSingletonRace)\n"
     "      checkers.add(new org.plumb.dfa.checkers.controlflow.SingletonRaceDfaChecker());",
     "    checkers.add(new org.plumb.dfa.checkers.controlflow.SingletonRaceDfaChecker());"),
    ("switch-defaults-to-true",
     SCAN_CONFIG,
     "public boolean runSingletonRace = false;",
     "public boolean runSingletonRace = true;"),
    # The document's own account of the mechanism goes stale: it says
    # the branch adds nothing, while the runner adds the checker.
    ("doc-says-the-branch-is-empty",
     DOC,
     "they are added only under the `config.runServletAtomicity` and `config.runSingletonRace`\n"
     "switches, and both switches default to `false` in `ScanConfig`, because the `plumb-style` AST\n"
     "equivalents cover the same CWE with better precision.",
     "the `config.runServletAtomicity` and `config.runSingletonRace` branches are deliberately\n"
     "**empty**, because the `plumb-style` AST equivalents cover the same CWE with better precision."),
    ("zh-doc-says-the-branch-is-empty",
     ZH_DOC,
     "它们只在 `config.runServletAtomicity` 和 `config.runSingletonRace` 两个开关下才被加入，\n"
     "而这两个开关在 `ScanConfig` 里默认是 `false`，因为 `plumb-style` 的 AST 等价实现\n"
     "以更高的精度覆盖同样的 CWE。把开关打开即可启用。",
     "`config.runServletAtomicity` 和 `config.runSingletonRace` 两个分支是**故意留空的**，\n"
     "因为 `plumb-style` 的 AST 等价实现以更高的精度覆盖同样的 CWE。"),

    # --- the API goes back to something that does not exist -----------
    ("invented-api-strip-type",
     DOC,
     "no shared `stripType` helper in the engine",
     "a shared `stripType` helper in the engine"),
    ("invented-api-const-null",
     DOC,
     "there is no `Const.NULL`",
     "there is a `Const.NULL`"),
    ("invented-field-written-fields",
     DOC,
     "not a\n`state.writtenFields` set",
     "not a\n`state.readFields` set"),
    # --- the mechanism description goes back to the wrong one ---------
    ("wrong-summary-type",
     DOC,
     "FunctionSummary summary = functionSummaries.get(calleeSig);",
     "MethodSummary summary = cache.get(calleeSig);"),
    ("wrong-cache-key",
     DOC,
     "`getByCanonicalString(String)` for the canonical spelling",
     "`getByString(String)` for the canonical spelling"),
    ("wrong-inert-variant",
     DOC,
     "only declared and never produced: `Operation.UnmodelledCall`",
     "only declared and never produced: `Operation.Sink`"),
    # The variant the document says is LIVE must really be produced.
    # The producer writes it under its fully-qualified name, so this
    # mutant is also the witness that the construction probe sees
    # every spelling: a probe that only matched `new Operation.` would
    # survive this mutant.
    ("live-variant-not-produced",
     DFA_ENGINE,
     "b.recordReturnOp(new org.plumb.dfa.summary.Operation.PointsToPropagate(labels));",
     "b.recordReturnOp(null);"),
    # A document that stops naming the live variant has lost the claim.
    ("live-variant-claim-dropped",
     DOC,
     "`Operation.PointsToPropagate` is the opposite case",
     "The points-to variant is the opposite case"),
    # --- the signature format goes back to the wrong one ---------------
    ("wrong-signature-format",
     DOC,
     '"package.Class#method(paramType,...)"',
     '"package.Class.method(paramType,...)"'),
    ("wrong-switch",
     DOC,
     "For a checker that has both sides, the switch is the `checks.<rule>: {enabled: true|false}`",
     "For a checker that has both sides, the switch is the `rules.enabled: {enabled: true|false}`"),

    # --- the same drift, in the CHINESE document -----------------------
    #
    # The Chinese tree is the same document in another language, so it
    # carries the same claims. A gate that only checks the English one
    # would let the Chinese tree describe a mechanism the code no
    # longer has.
    ("zh-wrong-ast-rule-id",
     ZH_DOC,
     "| AST | `ast-<category>-<bare>` | `ast-quality-uninit-nonnull` |",
     "| AST | `ast-<category>-<bare>` | `ast-style-uninit-nonnull-style` |"),
    ("zh-wrong-dfa-rule-id",
     ZH_DOC,
     "| DFA，结构性 | `dfa-<category>-<bare>` | `dfa-controlflow-deadlock-ab-ba` |",
     "| DFA，结构性 | `dfa-<category>-<bare>` | `plumb.dfa.deadlock-ab-ba` |"),
    ("zh-phantom-checker",
     ZH_DOC,
     "### 3.2 -DA: OrmLoadNullCheckDfaChecker",
     "### 3.2 -DA: OrmLoadNullCheckDfaTypoChecker"),
    ("zh-invented-api-const-null",
     ZH_DOC,
     "也**没有**\n`Const.NULL`",
     "也有\n`Const.NULL`"),
    ("zh-wrong-signature-format",
     ZH_DOC,
     '"package.Class#method(paramType,...)"',
     '"package.Class.method(paramType,...)"'),
]


def normalise(text):
    """Collapse the line endings so an anchor matches either way.

    The working tree is checked out with CRLF on Windows. An anchor
    written with a bare ``\\n`` would silently not match, and the
    battery would report "could not build" for a mutant that is fine.
    """
    return text.replace("\r\n", "\n")


def main():
    if not DOC.is_file():
        print("FAIL: %s does not exist" % DOC)
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
        if not target.is_file():
            print("FAIL: %s -- %s does not exist" % (name, target))
            return 1
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
