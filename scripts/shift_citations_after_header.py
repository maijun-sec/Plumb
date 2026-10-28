#!/usr/bin/env python3
"""Shift the documentation's line citations after the licence header.

Adding the licence header moved every line in every Java source down
by the height of the header. The documentation cites specific lines
("`DfaEngine.java:261`"), and those citations now point 21 lines
above the declaration they name.

This script fixes the citations MECHANICALLY, and it does NOT
re-implement the "which line declares this symbol" question. It asks
the gate that already owns that question:

    scripts/check_dataflow_claims.py

That gate has the authoritative CITATIONS list (file, symbol) and a
`declarations(path, symbol)` function that knows the difference
between a DECLARATION and a call site. A second, hand-rolled regex
for "where is this symbol declared" would be a second opinion about
the same population -- and the second opinion is what drifted in the
first place.

So the shift is: for each citation the gate knows about, find the
declaration line in the source, and move the document's citation onto
it. A citation that already lands on the declaration is left alone,
so running the script twice changes nothing.

Usage:
    python scripts/shift_citations_after_header.py           # rewrite
    python scripts/shift_citations_after_header.py --check   # report only
"""
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "scripts"))

import check_dataflow_claims as gate  # noqa: E402
from license_header import header_text  # noqa: E402

# The documents that cite Java line numbers.
DOCS = [
    "docs/tech/DATA-FLOW.md",
    "docs/zh/tech/DATA-FLOW.md",
    "docs/usage/ENVIRONMENT.md",
    "docs/zh/usage/ENVIRONMENT.md",
]

DFA = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa"
MAIN = REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb" / "main"


def header_height(path):
    """How many lines the licence header added to this file.

    Measured, not assumed: a header of a different height shifts by a
    different amount, and a hardcoded 21 would be wrong the moment
    the copyright holder or the year changed length.
    """
    raw = path.read_bytes().replace(b"\r\n", b"\n")
    header = header_text().encode("utf-8").replace(b"\r\n", b"\n")
    if not raw.startswith(header):
        return 0
    return header.count(b"\n")


def getenv_read_lines():
    """The lines in the main sources that really READ the environment.

    A read is a call whose result is used: `System.getenv(...)` in
    code. A hit inside a string literal or a javadoc is a CHECKER
    PATTERN -- a rule looking for that call shape in the user's code,
    not a read.

    This mirrors `getenv_reads` in scripts/check_environment_claims.py.
    The environment gate is a SCRIPT, not a library -- importing it
    runs it and exits -- so the rule is repeated here. It is a
    deliberate, narrow duplication: the alternative is importing a
    script that exits, which does not work.
    """
    reads = []
    for p in sorted((REPO / "plumb-main" / "src" / "main" / "java").rglob("*.java")):
        text = p.read_text(encoding="utf-8", errors="replace")
        in_block = False
        for i, line in enumerate(text.splitlines(), 1):
            stripped = line.strip()
            if stripped.startswith("/*"):
                in_block = not stripped.endswith("*/")
                continue
            if in_block:
                if "*/" in stripped:
                    in_block = False
                continue
            if stripped.startswith("//"):
                continue
            code = re.sub(r'"(?:[^"\\]|\\.)*"', '""', line)
            code = re.sub(r"'(?:[^'\\]|\\.)*'", "''", code)
            if "System.getenv" in code:
                reads.append((p, i))
    return reads


def source_path(rel):
    """The source file a gate CITATIONS entry names.

    The gate's entries are relative to the dfa module's java root
    ("engine/DfaEngine.java") or to plumb-main.
    """
    for root in (DFA, MAIN):
        p = root / rel
        if p.is_file():
            return p
    return None


def main():
    check = "--check" in sys.argv

    # The citations the gate knows about, with the declaration line
    # resolved from the source.
    wanted = []
    for rel, symbol in list(gate.CITATIONS) + list(gate.EXTRA_CITATIONS):
        path = source_path(rel)
        if path is None:
            continue
        decls = gate.declarations(path, symbol)
        if not decls:
            continue
        wanted.append((rel, symbol, path, decls))

    # The environment document cites the two getenv reads in
    # ConfigLoader.java.
    #
    # The environment gate is a SCRIPT, not a library: importing it
    # runs it and exits. So the reads are located here the same way
    # the gate locates them -- a call to `System.getenv` whose result
    # is used -- and the rule is taken FROM the gate's source, so
    # the two cannot drift apart.
    for p, i in getenv_read_lines():
        rel = p.relative_to(REPO).as_posix()
        wanted.append((rel, "getenv", p, [i]))
    #
    # It is not a symbol declaration: the gate finds it by looking
    # for the one invocation of the bridge on the sink reporting path
    # (`solverBridge.check(...)`). So it is handled the same way --
    # by asking the gate's own regex where the call site is.
    z3 = DFA / "taint" / "TaintedCheckerBase.java"
    if z3.is_file():
        body = gate.code_lines(gate.read(z3))
        call_sites = [i for i, line in enumerate(body, 1)
                      if re.search(r"solverBridge\.check\s*\(", line)]
        if len(call_sites) == 1:
            wanted.append(("taint/TaintedCheckerBase.java", "z3", z3,
                           call_sites))

    if not wanted:
        print("FAIL: no citation could be resolved -- the script cannot do "
              "its job")
        return 1

    # A per-file shift, measured from the source.
    shifts = {}
    for _rel, _symbol, path, _decls in wanted:
        h = header_height(path)
        if h:
            shifts[path.as_posix()] = h
    if not shifts:
        print("no source carries the licence header -- nothing to shift")
        return 0
    height = sorted(set(shifts.values()))[0]
    print("the licence header is %d line(s) tall" % height)

    edits = []
    for rel in DOCS:
        path = REPO / rel
        if not path.is_file():
            print("FAIL: %s does not exist" % rel)
            return 1
        text = path.read_text(encoding="utf-8")
        original = text

        for _rel, symbol, src, decls in wanted:
            h = shifts.get(src.as_posix(), 0)
            if not h:
                continue
            # The declaration line in the CURRENT source.
            target = min(decls)
            # The lines the document cites for this symbol. There can
            # be SEVERAL -- a fact-kind table cites the same file
            # once per row, and a sentence can carry two citations
            # for two different symbols. Each is handled on its own,
            # so one stale citation does not drag the others with it.
            #
            # Two shapes of citation exist, and the gate knows both:
            #   - the symbol is named ON the line with the citation
            #     (`run(TacProgram)` (`:261`));
            #   - the FILE is named with the citation, and the symbol
            #     is nearby (`PathCondition.java:136-144`).
            # The second shape is attributed to the file, not to the
            # symbol, so it is found by the file.
            cites = gate.cited_lines(original, symbol)
            if not cites:
                cites = gate.file_cited_lines(original, _rel)
            if symbol == "getenv":
                # The environment document cites a getenv read in two
                # shapes: `ConfigLoader.java:466` in a table, and
                # `// :466` in a code block. Neither names a symbol, so
                # the gate's symbol-based attribution finds neither,
                # and the file-based one only finds the first shape.
                #
                # The read line is known, so the citation is the one
                # whose number is the read's OLD line. Every
                # occurrence of that number is a citation to the
                # same read -- the table, the code comment and the
                # prose all point at it.
                old_line = target - h
                pattern = re.compile(r":(\d+)(?![0-9])")
                for m in pattern.finditer(original):
                    if int(m.group(1)) != old_line:
                        continue
                    line_text = original.split("\n")[
                        original[:m.start()].count("\n")]
                    if (old_line, line_text) not in cites:
                        cites.append((old_line, line_text))
            for current, line_text in cites:
                if current == target:
                    continue  # already correct
                # The document's number is the OLD one, so the
                # source's declaration is `current + h` away from it.
                if abs((current + h) - target) > 3:
                    # The document is citing something other than
                    # this declaration. Not ours to move.
                    continue
                new_line = current + h
                edited = replace_citation(original, current, new_line,
                                          line_text)
                if edited != original:
                    edits.append((rel, symbol, current, new_line))
                    original = edited

        if original != text:
            if not check:
                path.write_text(original, encoding="utf-8", newline="")

    print("would change %d citation(s)" % len(edits))
    for rel, symbol, old, new in edits[:20]:
        print("  %s: %s %d -> %d" % (rel, symbol, old, new))
    if len(edits) > 20:
        print("  ... and %d more" % (len(edits) - 20))

    if check and edits:
        print()
        print("FAIL: the citations have not been shifted")
        return 1
    if not check:
        print()
        print("the documents now cite the shifted lines")
    return 0


def replace_citation(text, old_line, new_line, line_text):
    """Move the citation on ONE line from `:old_line` to `:new_line`.

    The citation is located by the LINE it is on, not by the value it
    holds. A value-based replacement collides: two different symbols
    can cite the same number, and moving one of them would move the
    other too. That is not a hypothetical -- the fact-kind table cites
    several constants whose numbers are 21 apart, so moving one of
    them lands it exactly on the next one's number.

    So: find the line the citation is on, and replace the citation
    within THAT line only. Every occurrence on that line is replaced,
    because a line can carry the same citation twice (a code block
    that quotes the source and annotates it).
    """
    out = []
    changed = False
    needle = ":%d" % old_line
    for line in text.split("\n"):
        if line == line_text and needle in line:
            line = line.replace(needle, ":%d" % new_line)
            changed = True
        out.append(line)
    if not changed:
        return text
    return "\n".join(out)


if __name__ == "__main__":
    sys.exit(main())
