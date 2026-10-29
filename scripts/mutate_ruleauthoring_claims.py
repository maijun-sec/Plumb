#!/usr/bin/env python3
"""Mutation battery for `scripts/check_ruleauthoring_claims.py`.

An authoring document drifts by *describing an instruction that changed*.
Each mutant here is a plausible way that happens:

  - a count goes stale,
  - the verification command points at a launcher that does not exist,
  - the reader is told to hand-edit a generated file,
  - the alias/external-tool distinction is dropped or inverted,
  - a sanitizer hook is described as overridable when it is private,
  - the mechanism of the AST dispatcher is described wrongly.

Each mutant is applied to an in-memory BYTE snapshot, the gate is run,
and the file is restored byte-exactly. A mutant that survives is a hole
in the gate; a mutant that could not be built is reported separately,
because "could not build" is not "survived".

    python scripts/mutate_ruleauthoring_claims.py
"""
import pathlib
import re
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
GATE = REPO / "scripts" / "check_ruleauthoring_claims.py"
DOC = REPO / "docs" / "rules" / "RULE-AUTHORING.md"
ZH_DOC = REPO / "docs" / "zh" / "rules" / "RULE-AUTHORING.md"
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
    # --- a count goes stale ------------------------------------------
    ("stale-dfa-override-count",
     DOC,
     "- 20 `DfaChecker`s plus `TaintedCheckerBase`",
     "- 18 `DfaChecker`s plus `TaintedCheckerBase`"),
    ("stale-visit-overload-count",
     DOC,
     "reflecting over the `visit(NodeType, StyleContext)`",
     "reflecting over the 43 node types"),

    # --- the verification command points at nothing -------------------
    ("nonexistent-launcher",
     ZH_DOC,
     "`java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar --list-rules | grep FooCheck`",
     "`bin/sast-scan --list-rules | grep FooCheck`"),
    ("wrong-jar-name",
     DOC,
     "java -jar plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar --list-rules | grep FooCheck",
     "java -jar plumb-main/target/plumb-0.1.0-SNAPSHOT-all.jar --list-rules | grep FooCheck"),

    # --- the reader is told to hand-edit a generated file -------------
    ("hand-edit-generated-file",
     ZH_DOC,
     "2. 跑 `python scripts/gen_categories.py` 重新生成 `CheckerCategories.java`。",
     "2. 在 `CheckerCategories.java` 加 `m.put(\"FooCheck\", \"<category>\");`（按字母序）。"),
    ("hand-edit-generated-file-en",
     DOC,
     "2. Run `python scripts/gen_categories.py` to regenerate `CheckerCategories.java`.",
     "2. Add `m.put(\"FooCheck\", \"<category>\");` to `CheckerCategories.java`."),

    # --- the alias / external-tool distinction -------------------------
    ("aliases-claimed-as-external",
     DOC,
     "They are **not**\nPMD / SpotBugs / Checkstyle / SonarQube ids.",
     "They are the\nPMD / SpotBugs / Checkstyle / SonarQube ids."),
    ("an-unmapped-tool-promised",
     DOC,
     "It covers **PMD,\nSpotBugs, Checkstyle and SonarQube**",
     "It covers **PMD,\nSpotBugs, Checkstyle, SonarQube and FindBugs**"),
    ("external-tool-dropped",
     DOC,
     "It covers **PMD,\nSpotBugs, Checkstyle and SonarQube**",
     "It covers **PMD,\nSpotBugs and SonarQube**"),
    ("wrong-external-tool-name",
     ZH_DOC,
     "它覆盖 **PMD、SpotBugs、Checkstyle、SonarQube**，仅此四个。",
     "它覆盖 **PMD、SpotBugs、SonarQube**，仅此三个。"),

    # --- the sanitizer hook is described wrongly -----------------------
    ("sanitizer-claimed-overridable",
     DOC,
     "which is a **private static** method with a hardcoded list of callee-signature prefixes -- a subclass cannot override it",
     "which is an overridable method a subclass can override"),
    ("sanitizer-claimed-overridable-zh",
     ZH_DOC,
     "它是**private static** 方法、\n   里面是一张写死的 callee 签名前缀清单——子类覆盖不了它",
     "它是一个可覆盖的方法、子类可以覆盖它"),

    # --- the AST dispatcher mechanism ---------------------------------
    ("dispatcher-mechanism-wrong",
     DOC,
     "builds its dispatch table by reflecting over the `visit(NodeType, StyleContext)`\nmethods a checker declares",
     "calls a hard-coded list of 43 node types one by one"),
    ("dispatcher-mechanism-wrong-zh",
     ZH_DOC,
     "`AstDispatcher` 的分发表是通过反射\nchecker 声明的 `visit(NodeType, StyleContext)` 方法建出来的",
     "`AstDispatcher` 的分发表是对 43 种节点各调一次 `visit(X, Void)` 建的"),

    # --- a named file that does not exist -----------------------------
    ("phantom-file",
     DOC,
     "`plumb-style/src/main/java/org/plumb/style/style/BadShiftCheck.java`",
     "`plumb-style/src/main/java/org/plumb/style/style/BadShiftTypoCheck.java`"),

    # --- the CWE mapping key format ----------------------------------
    ("wrong-cwe-key-format",
     DOC,
     "`{CheckerName}`, or\n`{CheckerName}_{SinkFamilyShortName}`",
     "`{Checker}`, or\n`{Checker}_{Sink}`"),

    # --- the quoted RuleDef ------------------------------------------
    ("wrong-rule-id",
     DOC,
     'new RuleId("ast-error-empty-catch-block", "EMPTY_CATCH_BLOCK"),',
     'new RuleId("ast-error-empty-catch-block-style", "EMPTY_CATCH_BLOCK"),'),
    ("wrong-alias",
     DOC,
     'List.of("ast-error-catch-block-strict", "empty-catch-block");',
     'List.of("ast-error-catch-block-strict", "empty-catch-block-v2");'),

    # --- the TaintRule metadata --------------------------------------
    ("wrong-taintrule-field",
     DOC,
     "(`ruleId` / `sourceKindName` / `sinkFamilyKey` / `cwe` / `severityLabel` / `domain`)",
     "(`ruleId` / `kind` / `sink` / `cwe` / `severityLabel` / `domain`)"),

    # --- a flag the CLI does not have ---------------------------------
    ("nonexistent-cli-flag",
     DOC,
     "The user's `--rule-prompts-dir` still overrides",
     "The user's `--rule-prompt-dir` still overrides"),

    # --- a class the document says does not exist ---------------------
    ("taintcatalog-claimed",
     DOC,
     "(there is no `TaintCatalog` class -- the early documentation mentioned a class that never existed)",
     "(the `TaintCatalog` class holds the source and sink patterns)"),
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
