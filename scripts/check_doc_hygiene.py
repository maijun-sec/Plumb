#!/usr/bin/env python3
"""Keep product documentation free of process history and unreproducible measurements.

`docs/` describes **what the product does today**. Three kinds of content do not belong there,
and each one has a different failure mode:

  1. **Process history.** "R-812 refactored the matcher", "this used to be System.out.println",
     "the pre-R774 behaviour". A reader of the product documentation has no access to the round
     numbers, so these sentences are unreadable *and* they describe a past state rather than
     the current one. The round numbers live in git history, which is exactly where a history
     belongs.

  2. **Unreproducible measurements.** A number that cannot be re-derived from the repository is
     an assertion, not a measurement. The Juliet corpus and the scan output it produces are
     deliberately not in version control, so any "precision 0.4537" is a claim about one past
     run. A product document that quotes it invites the reader to treat it as current.

  3. **Third-party numbers presented as data.** Another tool's rule count comes from somebody
     else's benchmark. It is fine to say "PMD is AST-only"; it is not fine to state a specific
     rule count as if this repository had measured it.

This gate reads every markdown file under `docs/` and fails on the patterns above.

What it deliberately does NOT do:

  * forbid the word "Juliet" -- a rule's *design* may legitimately be motivated by a Juliet
    test case shape, and that is a statement about the rule, not a measurement;
  * forbid every round number -- a rule may be *named* after one, and the rule's own
    identifier is not process history;
  * read `docs/plumb-style-rules.md` for round markers, because that file is GENERATED from
    the checkers' javadoc: the source is the thing to fix, and the gate below covers it.

    python scripts/check_doc_hygiene.py           # check
    python scripts/check_doc_hygiene.py --list    # list every hit, for a bulk fix
"""

import argparse
import io
import pathlib
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent
DOCS = REPO / "docs"

# The generated rule catalogue mirrors the checkers' javadoc. If a round marker is in the
# javadoc it will be in the catalogue, so the catalogue is reported as a SYMPTOM and the
# javadoc is reported as the PLACE. The gate still fails -- the point is that somebody has to
# look.
GENERATED_DOCS = {
    "plumb-style-rules.md",
    "plumb-style-rules-index.md",
}

# (name, pattern, explanation)
RULES = [
    (
        "round-marker",
        re.compile(r"(?<![A-Za-z0-9_])R-?\d{3,4}[a-z]?(?![A-Za-z0-9_])"),
        "round number: this is process history, and it belongs in git history",
    ),
    (
        "past-state",
        re.compile(
            r"(本轮|这一轮|那一轮|该轮|当时|历史上|曾经|早期版本|原来|之前的历史"
            r"|pre-\d|重构前)"
        ),
        "past-state wording: a product document describes the current state",
    ),
    (
        "unreproducible-measurement",
        re.compile(
            r"(无法复现|不可复现|不可从本仓库|本仓库无法复现|历史测量|历史记录|"
            r"当时的 recall|该轮 baseline)"
        ),
        "unreproducible measurement: quote only numbers this repository can re-derive",
    ),
    (
        "juliet-measurement",
        # A Juliet NUMBER. A mention of the corpus as a TOPIC ("see the comparison document")
        # is fine -- a precision figure is not, because the corpus is not in version control
        # and the number is a claim about one past run.
        re.compile(
            r"(Juliet|JULIET)[^。\n|]{0,60}?"
            r"(\d+\.\d{2,}|\d{1,3}\s*%|prec[a-z]*\s*[=:：]\s*\d|recall\s*[=:：]\s*\d|"
            r"误报率\s*[=:：]?\s*\d|准确率\s*[=:：]?\s*\d|召回率\s*[=:：]?\s*\d)"
        ),
        "Juliet measurement: the corpus is not in version control, so the number is a claim "
        "about one past run",
    ),
    (
        "third-party-count",
        # Another tool's rule COUNT. "PMD is AST-only" is fine; "PMD has 318 rules" is a
        # figure this repository has not measured.
        re.compile(
            r"(PMD|SonarQube|Sonar|SpotBugs|FindBugs|Coverity|Checkstyle)[^。\n|]{0,30}"
            r"\b(\d{2,3})\s*(条|个规则|rules?)\b"
        ),
        "third-party rule count: this repository has not measured another tool",
    ),
    (
        "evaluation-framing",
        # The product documentation must not be written AROUND an evaluation corpus.
        #
        # Two shapes are caught here:
        #
        #   1. the corpus as a SOURCE OF TRUTH for the product ("the mapping lives in
        #      juliet-coverage/data/...", "see juliet-coverage/"), because that directory
        #      is evaluation material, not a product path; and
        #   2. the corpus as a MEASURING STICK for the product ("the rule fires on 17
        #      Juliet files", "Juliet precision", "0 TP / 100% FP on the Juliet
        #      baseline"), because that is a claim about one benchmark run rather than
        #      about the rule.
        #
        # A mention of the corpus as a plain example of the code SHAPE a rule targets is
        # allowed -- that is a statement about the rule. So is naming a helper class the
        # engine actually recognises (`IO.staticReturnsFalse()`); that is an identifier,
        # not a measurement.
        re.compile(
            r"(juliet-coverage/|juliet_testcases)"
            r"|(?<![A-Za-z0-9_])(?:Juliet|JULIET)(?![A-Za-z0-9_])[^。\n|]{0,50}?"
            r"(corpus|测试集|测试样本|测试用例|baseline|precision|prec\b|recall\b|"
            r"\d+\s*(?:个)?\s*(?:corpus|files?|测试))"
            r"|\d+\s*TP\s*/"
        ),
        "evaluation framing: the product documentation must describe the product, not the "
        "benchmark it was measured against",
    ),
    (
        "dash-opening",
        # A comment BLOCK whose FIRST line opens with a dash. A line that
        # opens with a dash is usually a continuation of the line before
        # it, and that is fine. But a BLOCK whose first line opens with a
        # dash has lost its subject: there is nothing before it to
        # continue from.
        #
        #     /*
        #      * -- CORS misconfiguration (CWE-942).
        #      */
        #
        # The subject was a round marker that the cleanup removed.
        re.compile(r"^\s*(?://|\*)\s*(?:--|—|–)\s"),
        "dash opening: the comment block lost its subject",
    ),
    (
        "benchmark-claim",
        # A BENCHMARK CLAIM in a product source comment. A source comment
        # documents the code; a comment that reports "0 TP / 100% FP on
        # Juliet" is a claim about one past measurement, and the corpus is
        # not in version control, so nobody can re-derive it.
        #
        # The three parts:
        #   * a TP or FP count -- "0 TP / 100% FP", "3 TP";
        #   * a percentage presented as a measurement -- "60% FP";
        #   * the corpus named as the measuring stick -- "on Juliet", "the
        #     Juliet baseline".
        #
        # A rule's DESIGN may be motivated by the shape of a corpus case.
        # That is a statement about the rule and is allowed: it names the
        # corpus without attaching a number to it.
        re.compile(
            r"\b\d+\s*TP\b|\b\d+\s*FP\b|\bTP\s*/\s*\d|\bFP\s*/\s*\d|"
            r"\b\d{1,3}\s*%\s*(?:FP|TP)\b|"
            r"(?<![A-Za-z0-9_])(?:Juliet|JULIET)(?![A-Za-z0-9_])"
            r"[^。\n|]{0,40}?(?:baseline|测试集|corpus)"
        ),
        "benchmark claim: a source comment reports a measurement instead of "
        "describing the code",
    ),
    (
        "dangling-subject",
        # Removing a round marker from the middle of a Chinese sentence can leave the
        # sentence without its subject:
        #
        #     "`CATCH_HANDLER` 是 R261 加入的"  ->  "`CATCH_HANDLER` 是 加入的"
        #
        # The marker WAS the subject. Removing it without replacing it leaves a
        # dangling predicate.
        #
        # The trace is the SPACE: a Chinese sentence never puts a space between a
        # copula and its predicate, so a space there means something was removed and
        # nothing took its place.
        re.compile(
            r"(?:是|为|由|在|从|跟|和|对|把|被|给|改成|扩展成|实现为|定义为|"
            r"当作|当成|变成|成为|继承自|源自|来自)\s+"
            r"(?:加入的|增加的|引入的|实现的|提供的|支持的|修复的|改写的|新增的|"
            r"删除的|替换的|扩展的|调整的|优化的|重写的|提出的|新增|加入|引入|"
            r"实现|提供|支持|修复|改写|删除|替换|扩展|调整|优化|重写|提出|建成|"
            r"补充|配套)"
        ),
        "dangling subject: a round marker was removed from the middle of a sentence "
        "and nothing replaced it",
    ),
]
# The rule catalogue is generated; the javadoc it mirrors is the real place to fix.
JAVADOC_ROOTS = [
    REPO / "plumb-style" / "src" / "main" / "java",
    REPO / "plumb-dfa" / "src" / "main" / "java",
    REPO / "plumb-tac" / "src" / "main" / "java",
    REPO / "plumb-rules" / "src" / "main" / "java",
    REPO / "plumb-reporter" / "src" / "main" / "java",
    REPO / "plumb-ai" / "src" / "main" / "java",
    REPO / "plumb-main" / "src" / "main" / "java",
    REPO / "plumb-build" / "src" / "main" / "java",
    REPO / "plumb-parser" / "src" / "main" / "java",
]

# A Java string literal, with the escapes handled.
STRING_LITERAL = re.compile(r'"((?:\\.|[^"\\])*)"')


def comment_lines(text):
    """The comment lines of a java file, with their line numbers.

    <p>The round marker is only process history when it is in a COMMENT. In an identifier
    (`class R812StaticFieldDeclTest`) it is part of a name, and renaming it is a different
    change with a different risk.
    """
    out = []
    in_block = False
    for lineno, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        is_comment = stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*")
        if in_block:
            is_comment = True
        out.append((lineno, line, is_comment))
        if in_block and "*/" in line:
            in_block = False
        if stripped.endswith("/*") and not (stripped.endswith("*/")):
            in_block = True
    return out


def scan_docs():
    """Every hit in the markdown documentation.

    The scope includes the repository's root README and its Chinese
    counterpart. The root README is the first document a reader opens, so
    a stale claim there is at least as damaging as one in `docs/` -- and
    before this was added, nothing checked it at all. A round number in
    the root README was therefore invisible to every gate.
    """
    hits = []
    paths = sorted(DOCS.rglob("*.md"))
    for extra in (REPO / "README.md", REPO / "README.zh.md"):
        if extra.is_file():
            paths.append(extra)
    for path in paths:
        text = path.read_text(encoding="utf-8", errors="replace")
        for name, pattern, why in RULES:
            for m in pattern.finditer(text):
                lineno = text[:m.start()].count("\n") + 1
                line = _line_at(text, lineno)
                # A sentence that SAYS a number is not reproducible is the correct
                # behaviour, not a violation: the reader is being told not to trust it.
                if name in ("unreproducible-measurement", "past-state") \
                        and _is_disclaimer(_context(text, lineno)):
                    continue
                hits.append((path, lineno, name, m.group(0), why))
    return hits


def _line_at(text, lineno):
    lines = text.splitlines()
    return lines[lineno - 1] if 0 < lineno <= len(lines) else ""


def _context(text, lineno):
    """The line, plus its neighbours.

    <p>A disclaimer is usually a PARAGRAPH: the warning is on the first line and the numbers
    follow. So the check looks at the surrounding lines, not just the one that matched.
    """
    lines = text.splitlines()
    lo = max(0, lineno - 4)
    hi = min(len(lines), lineno + 3)
    return "\n".join(lines[lo:hi])


def _is_disclaimer(block):
    """Does this block tell the reader that a number cannot be trusted here?"""
    return bool(re.search(
        r"(不可复现|无法复现|不是当前|不作为|请勿依赖|以实际|自行重测|重新测量|仅供参考|"
        r"当时|历史)",
        block))


def scan_javadoc():
    """Every hit in the java sources' comments."""
    hits = []
    for root in JAVADOC_ROOTS:
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*.java")):
            text = path.read_text(encoding="utf-8", errors="replace")
            for lineno, line, is_comment in comment_lines(text):
                if not is_comment:
                    continue
                for name, pattern, why in RULES:
                    if name in ("third-party-count", "juliet-measurement",
                                "dash-opening"):
                        continue
                    for m in pattern.finditer(line):
                        hits.append((path, lineno, name, m.group(0), why))
            # The dash-opening rule is about a BLOCK, not a line, so it is
            # checked on the first line of each contiguous comment run.
            for lineno in comment_block_starts(text):
                line = text.splitlines()[lineno - 1]
                for name, pattern, why in RULES:
                    if name != "dash-opening":
                        continue
                    for m in pattern.finditer(line):
                        hits.append((path, lineno, name, m.group(0), why))
    return hits


def comment_block_starts(text):
    """The line numbers of the first CONTENT line of each comment block.

    A run of `//` lines is ONE block, and only its first line carries
    content. A `/* ... */` block is one block, and its first CONTENT line
    is the one after the opening `/**` -- the opening line itself is just
    the delimiter.
    """
    starts = []
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        stripped = lines[i].strip()
        if stripped.startswith("//"):
            starts.append(i + 1)
            i += 1
            while i < len(lines) and lines[i].strip().startswith("//"):
                i += 1
            continue
        if stripped.startswith("/*"):
            # The first line of the block that carries content.
            j = i + 1
            while j < len(lines):
                s = lines[j].strip()
                if s.startswith("*/"):
                    break
                body = s[1:].strip() if s.startswith("*") else s
                if body:
                    starts.append(j + 1)
                    break
                j += 1
            while j < len(lines) and "*/" not in lines[j]:
                j += 1
            i = j + 1
            continue
        i += 1
    return starts


def scan_product_resources():
    """Evaluation material that is packaged into the product.

    A file under `src/main/resources` is packaged into the product jar and shipped to
    every user. So the question is not "is this file useful" but "does the product need
    it at runtime". The corpus fixtures and the round-named evaluation outputs do not:
    the runtime CWE mapping comes from `cwe-mappings.yaml`, and the AST CWE tag comes
    from `RuleDef.cwe()`.

    The check therefore rejects two things under `src/main/resources`:

      1. a round-named path segment (`r696_out`, `r697_scan`, ...), and
      2. a large fixture tree -- more than a few hundred files, which is the shape a
         corpus takes and the shape no configuration takes.

    A configuration directory with a handful of YAML files is fine and is not flagged.

    The CONTENT of a shipped configuration is checked too. A shipped YAML is a
    user-facing document: the user copies it and edits it. So it must not carry
    round markers (process history) or benchmark claims (measurements this
    repository cannot re-derive) any more than `docs/` must.
    """
    hits = []
    for root in REPO.glob("plumb-*/src/main/resources"):
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*")):
            if not path.is_file():
                continue
            rel = path.relative_to(REPO).as_posix()
            parts = path.relative_to(root).parts
            for seg in parts[:-1]:
                if re.match(r"^r\d{3,4}[a-z]?$", seg):
                    hits.append((path, 0, "round-named-resource-dir", seg,
                                 "a round-named directory under src/main/resources is "
                                 "evaluation material, and it is packaged into the product"))
                    break
            else:
                # Count the files in this directory tree once, at its top level.
                continue
    # The fixture-tree check is per top-level directory of the resources root.
    for root in REPO.glob("plumb-*/src/main/resources"):
        if not root.is_dir():
            continue
        for entry in sorted(root.iterdir()):
            if not entry.is_dir():
                continue
            count = sum(1 for p in entry.rglob("*") if p.is_file())
            if count > 50:
                hits.append((entry, 0, "fixture-tree-in-product", str(count),
                             "a %d-file tree under src/main/resources is a test fixture, "
                             "and it is packaged into the product" % count))
    # The CONTENT of a shipped configuration.
    for root in REPO.glob("plumb-*/src/main/resources"):
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*.yaml")):
            text = path.read_text(encoding="utf-8", errors="replace")
            for lineno, line in enumerate(text.splitlines(), 1):
                if not line.strip().startswith("#"):
                    continue
                for name, pattern, why in RULES:
                    if name not in ("round-marker", "benchmark-claim",
                                    "past-state", "unreproducible-measurement"):
                        continue
                    for m in pattern.finditer(line):
                        hits.append((path, lineno, name, m.group(0), why))
    return hits


def scan_user_visible_strings():
    """Round markers in the strings the USER reads.

    A round marker in a source comment is process history in a place where
    the reader can ignore it. A round marker in a string literal is
    different: the string is printed.

    The `--help` output, a log line, a finding message, a rule description,
    an LLM prompt header -- all of those reach the user, and all of them
    would carry a number the user has no way to interpret.
    """
    hits = []
    for root in JAVADOC_ROOTS:
        if not root.is_dir():
            continue
        for path in sorted(root.rglob("*.java")):
            text = path.read_text(encoding="utf-8", errors="replace")
            for m in STRING_LITERAL.finditer(text):
                body = m.group(1)
                for name, pattern, why in RULES:
                    if name != "round-marker":
                        continue
                    for hit in pattern.finditer(body):
                        lineno = text[:m.start()].count("\n") + 1
                        hits.append((path, lineno, name, hit.group(0), why))
    return hits


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--list", action="store_true", help="list every hit")
    args = parser.parse_args()

    doc_hits = scan_docs()
    java_hits = scan_javadoc()
    resource_hits = scan_product_resources()
    string_hits = scan_user_visible_strings()

    if args.list:
        for path, lineno, name, text, why in doc_hits + java_hits + string_hits:
            rel = path.relative_to(REPO)
            print("%s:%d  [%s] %r\n    %s" % (rel, lineno, name, text, why))
        for path, lineno, name, text, why in resource_hits:
            rel = path.relative_to(REPO)
            print("%s  [%s] %r\n    %s" % (rel, name, text, why))
        print()
        print("%d hit(s) in docs/, %d in java comments, %d in user-visible strings, "
              "%d in product resources"
              % (len(doc_hits), len(java_hits), len(string_hits),
                 len(resource_hits)))
        return 0

    if not doc_hits and not java_hits and not resource_hits and not string_hits:
        print("  ok: no process history or unreproducible measurement in the documentation")
        return 0

    by_file = {}
    for path, lineno, name, text, why in doc_hits + java_hits + string_hits:
        by_file.setdefault(path, []).append((lineno, name, text, why))

    for path in sorted(by_file):
        rel = path.relative_to(REPO)
        print("%s" % rel)
        for lineno, name, text, why in sorted(by_file[path])[:12]:
            print("  %5d  [%-26s] %r" % (lineno, name, text))
        more = len(by_file[path]) - 12
        if more > 0:
            print("  ... and %d more" % more)

    for path, lineno, name, text, why in resource_hits:
        rel = path.relative_to(REPO)
        print("%s\n  [%-26s] %r\n    %s" % (rel, name, text, why))

    print()
    print("FAIL: %d hit(s) in docs/, %d in java comments, %d in user-visible strings, "
          "%d in product resources"
          % (len(doc_hits), len(java_hits), len(string_hits), len(resource_hits)))
    print("Product documentation describes what the product does TODAY.")
    print("Round numbers and past measurements belong in git history.")
    print("Evaluation material belongs in test resources, not in the product jar.")
    print("A string the user reads is part of the product, so it follows the")
    print("same rule: no round numbers.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
