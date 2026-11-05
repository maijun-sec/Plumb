#!/usr/bin/env python3
"""Re-anchor the documentation's line citations onto the CURRENT declarations.

The problem this solves
-----------------------

`docs/tech/DATA-FLOW.md` cites specific source lines -- "the guard tracker is
at `:916`" -- and the gate `scripts/check_dataflow_claims.py` checks that
each citation lands on the declaration it names. When the source moves, the
citation goes stale and the gate fails.

There is an existing tool for this: `scripts/shift_citations_after_header.py`.
It handles ONE cause of drift -- the licence header, which moved every line
down by a uniform amount. It works by subtracting the header height.

But the drift is not always uniform. A source file that gains a method in the
middle, or loses one, moves the declarations BELOW it by an amount that has
nothing to do with the header. Subtracting a constant does not fix that, and
the tool leaves those citations alone -- it reports "would change N
citation(s)" and the gate still fails.

So this script does the general thing: for every citation the gate knows
about, it asks the GATE where the declaration is now, and moves the citation
onto that line.

THE GATE IS THE AUTHORITY
--------------------------

The question "which line declares `runSequential`" has exactly one owner:
`gate.declarations(path, symbol)`. It knows the difference between a
DECLARATION and a call site, between a method and an enum constant, and
between a mention in a javadoc and a declaration.

A second, hand-rolled regex for "where is this symbol declared" would be a
second opinion about the same population -- and the second opinion is what
drifted in the first place. So this script imports the gate and asks it.

THE GATE'S OWN MATCHING IS REPLICATED EXACTLY
-----------------------------------------------

This is the part that has to be right. The gate does not say "the symbol's
FIRST declaration"; it says:

    want = the declaration NEAREST to one of the document's citations
    nearest_cite = the citation NEAREST to want
    pass iff |nearest_cite - want| <= LINE_TOLERANCE

A symbol with several declarations (an overload, a field declared twice in
different scopes) is attributed to the one the document is already pointing
at. If this script picked "the first declaration" it would move a perfectly
good citation to a DIFFERENT declaration -- a change that satisfies the gate
while making the document wrong.

So the script computes `want` with the gate's formula, and rewrites only the
citation the gate flagged as `nearest_cite`. A citation that is already
within the tolerance is left alone, so running the script twice changes
nothing.

Usage:
    python scripts/reanchor_doc_citations.py           # rewrite
    python scripts/reanchor_doc_citations.py --check   # report only
"""
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "scripts"))

import check_dataflow_claims as gate  # noqa: E402

# The documents that cite Java line numbers. Both languages of every
# document are listed: the Chinese document is a TRANSLATION of the
# English one, and a citation is a citation -- the line number is the same
# number whether the sentence around it is English or Chinese.
DOCS = [
    "docs/tech/DATA-FLOW.md",
    "docs/zh/tech/DATA-FLOW.md",
]

DFA = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa"

# The citation shape: `:261`, `:136-144`, optionally inside backticks or
# parentheses. The number is what the reader follows.
NUMBER_RE = re.compile(r"(?P<prefix>:)(?P<num>\d+)(?P<tail>(?:\s*-\s*\d+)?)")

# The file-anchored RANGE citation: `AnalysisContext.java:549-553`.
RANGE_RE = re.compile(r"`(?P<file>[A-Za-z0-9_./-]*\.java):(?P<start>\d+)-(?P<end>\d+)`")


def range_target(rel_file, start):
    """The declaration a file-anchored range should start at.

    The gate requires the range's start to be a declaration line in the
    file the range names. The declaration it should point at is the one
    NEAREST the range's current start -- the same "nearest" rule the bare
    citations use, and for the same reason: a file can declare several of
    the symbols the gate knows, and the range is about the one it is
    already pointing near.
    """
    base = rel_file.rsplit("/", 1)[-1]
    prefix = rel_file.rsplit("/", 1)[0] if "/" in rel_file else ""
    decls = []
    for rel, symbol in list(gate.CITATIONS) + list(gate.EXTRA_CITATIONS):
        if rel.rsplit("/", 1)[-1] != base:
            continue
        if prefix and rel.rsplit("/", 1)[0] != prefix:
            continue
        target = DFA / rel
        if not target.is_file():
            continue
        decls.extend(gate.declarations(target, symbol))
    if not decls:
        return None
    return min(decls, key=lambda d: abs(d - start))


def the_call_site():
    """The single Z3 call site, located the way the gate locates it.

    The gate does not find it by a symbol name: it finds the one invocation
    of the bridge on the sink reporting path (`solverBridge.check(...)`).
    Asking the gate's own regex is the only way to agree with it.
    """
    z3 = DFA / "taint" / "TaintedCheckerBase.java"
    if not z3.is_file():
        return None
    body = gate.code_lines(gate.read(z3))
    call_sites = [i for i, line in enumerate(body, 1)
                  if re.search(r"solverBridge\.check\s*\(", line)]
    if len(call_sites) != 1:
        return None
    return ("taint/TaintedCheckerBase.java", "z3", z3, call_sites)


def the_wanted():
    """Every (file, symbol, source, declaration lines) the gate knows.

    The declaration lines are the gate's own, resolved from the source --
    never stored here, so they cannot go stale.
    """
    wanted = []
    for rel, symbol in list(gate.CITATIONS) + list(gate.EXTRA_CITATIONS):
        path = DFA / rel
        if not path.is_file():
            continue
        decls = gate.declarations(path, symbol)
        if not decls:
            continue
        wanted.append((rel, symbol, path, decls))
    call_site = the_call_site()
    if call_site is not None:
        wanted.append(call_site)
    return wanted


def citations_for(doc, rel, symbol):
    """The citations the gate attributes to this symbol, in this document.

    Two shapes, and the gate knows both:
      - the symbol is named ON the line with the citation
        (`run(TacProgram)` (`:261`));
      - the FILE is named with the citation, and the symbol is nearby
        (`PathCondition.java:136-144`).

    The second shape is attributed to the file, not to the symbol.
    """
    cites = gate.cited_lines(doc, symbol)
    if not cites:
        cites = gate.file_cited_lines(doc, rel)
    return cites


def index_of_line(doc, line_text):
    """The index of the first line in the document that is exactly this text.

    The gate hands back the whole line it matched. A line that appears
    twice in the document is ambiguous -- but the gate attributes a
    citation to the FIRST occurrence of the text it matched, so the
    first occurrence is the one to rewrite.
    """
    idx = doc.find(line_text)
    if idx < 0:
        return None
    return doc[:idx].count("\n")


def exclusive_citation(line, symbol, all_symbols, all_cites):
    """The citation on this line that belongs to THIS symbol.

    The gate's `cited_lines` attributes every citation on a line to every
    symbol named on it. That is the right thing for a CHECK -- the gate
    only asks "is one of these citations close enough to the declaration"
    -- but it is the wrong thing for a REWRITE, where each citation has
    to be moved to the right place.

    The line

        - State: `taintedVars: Map<TacVar, String>` (`:216`) and
          `taintPaths` (`:245`)

    names two symbols and carries two citations. `taintPaths` is
    declared at 245 and `taintedVars` at 236. A rewrite that took "the
    citation nearest to the declaration" would move `:245` -- the
    CORRECT citation -- onto 236, and leave `:216` -- the WRONG one --
    in place. The document would then be wrong in the opposite
    direction, and the gate would still fail.

    So the attribution here is POSITIONAL: the citation that follows a
    symbol's mention belongs to that symbol. A symbol with no citation
    after it gets the last one on the line, which is the shape the gate
    itself assumes when a sentence ends with one citation.
    """
def assign_citations(line, symbols):
    """Which citation on this line belongs to which symbol.

    The gate's `cited_lines` attributes every citation on a line to every
    symbol named on it. That is the right thing for a CHECK -- the gate
    only asks "is one of these citations close enough to the declaration"
    -- but it is the wrong thing for a REWRITE, where each citation has
    to be moved to the right place.

    Two shapes need this care.

    The line

        - State: `taintedVars: Map<TacVar, String>` (`:216`) and
          `taintPaths` (`:245`)

    names two symbols and carries two citations. `taintPaths` is
    declared at 245 and `taintedVars` at 236. A rewrite that took "the
    citation nearest to the declaration" would move `:245` -- the
    CORRECT citation -- onto 236, and leave `:216` -- the WRONG one --
    in place. The document would then be wrong in the opposite
    direction, and the gate would still fail.

    The line

        | Forward traversal | `analyseOneFunction` (`:1726`), traversing
          in `bbRpoOrder(fn)` order |

    names two symbols and carries ONE citation, and the symbol the
    citation belongs to is the one that comes FIRST. Here the order of
    the mentions matters, not the order of the citations.

    So the assignment is: order the symbols by where they are MENTIONED
    on the line, then hand each one the next citation that has not been
    handed out yet. A symbol with no citation left falls back to the
    line's last citation -- the shape the gate itself assumes when a
    sentence ends with a single citation.
    """
    cites = []
    for m in NUMBER_RE.finditer(line):
        cites.append((m.start(), int(m.group("num"))))
    if not cites:
        return {}

    mentions = []
    for symbol in symbols:
        m = re.search(r"(?<![\w])" + re.escape(symbol) + r"(?![\w])", line)
        if m is not None:
            mentions.append((m.start(), symbol))
    mentions.sort()

    out = {}
    used = set()
    for _pos, symbol in mentions:
        remaining = [c for i, c in cites if i not in used]
        if not remaining:
            out[symbol] = cites[-1][1]
            continue
        chosen = remaining[0]
        for i, c in cites:
            if c == chosen and i not in used:
                used.add(i)
                break
        out[symbol] = chosen
    return out


def reanchor(doc, wanted):
    """Move the stale citations onto their declarations.

    Returns (new_doc, report). The report names every move, so the change
    is legible rather than silent.
    """
    report = []
    # The lines to rewrite, as line index -> list of (number, replacement).
    plan = {}

    # Every symbol the gate knows, so a line's citations can be attributed
    # by position rather than by "nearest to the declaration".
    known = [symbol for _rel, symbol, _src, _decls in wanted]
    doc_lines = doc.split("\n")
    # The per-line assignment, computed once per line: several symbols can
    # share a line, and they must agree on which citation is whose.
    assignment_cache = {}

    def assignment_for(line_index):
        if line_index not in assignment_cache:
            assignment_cache[line_index] = assign_citations(doc_lines[line_index], known)
        return assignment_cache[line_index]

    for rel, symbol, src, decls in wanted:
        cites = citations_for(doc, rel, symbol)
        if not cites:
            continue
        # The declaration the citation SHOULD point at: the one NEAREST
        # to one of the document's citations. This is the gate's own
        # choice, and it matters when a symbol has several declarations.
        want = min(decls, key=lambda d: min(abs(c - d) for c, _ in cites))

        # The citation that belongs to THIS symbol. A line can carry
        # several symbols and several citations, so the attribution is
        # positional -- see `assign_citations`.
        #
        # A symbol can also be MENTIONED on a line whose citation belongs
        # to a different symbol. `bbRpoOrder` appears in a table row that
        # cites `analyseOneFunction`, and on its own line that cites
        # itself. The citation that belongs to the symbol is the one the
        # line's assignment gives it AND that sits nearest the
        # declaration -- so the mention in passing does not drag the
        # rewrite onto the other symbol's citation.
        owned = []
        for current, line_text in cites:
            line_index = index_of_line(doc, line_text)
            if line_index is None:
                continue
            if assignment_for(line_index).get(symbol) != current:
                continue
            owned.append((current, line_index))
        if not owned:
            # The symbol is attributed to the file rather than named on
            # the line; fall back to the citation nearest the declaration,
            # which is what the gate itself would compare against.
            nearest = min(cites, key=lambda c: abs(c[0] - want))
            line_index = index_of_line(doc, nearest[1])
            if line_index is None:
                continue
            owned = [(nearest[0], line_index)]
        current, line_index = min(owned, key=lambda o: abs(o[0] - want))
        if abs(current - want) <= gate.LINE_TOLERANCE:
            continue  # already anchored
        plan.setdefault(line_index, []).append((current, want, rel, symbol))

    if not plan:
        return doc, report

    lines = doc.split("\n")
    for line_index, moves in plan.items():
        # The line is rewritten IN PLACE, move by move. A line can carry
        # two citations for two different symbols -- `taintedVars` (`:216`)
        # and `taintPaths` (`:245`) on the same line -- and each move
        # must see the PREVIOUS move's result. Building every move from
        # the original text would let the second move discard the first.
        for current, want, rel, symbol in moves:
            line = lines[line_index]
            new_line = None
            for m in NUMBER_RE.finditer(line):
                if int(m.group("num")) != current:
                    continue
                # A range citation keeps its span: the START becomes the
                # declaration (that is the line the reader lands on) and
                # the END keeps its distance, so the range still covers
                # what it covered.
                tail = m.group("tail")
                new_line = (
                    line[:m.start()]
                    + m.group("prefix")
                    + str(want)
                    + tail
                    + line[m.end():]
                )
                break
            if new_line is None:
                continue
            report.append(
                "  %s: %s %d -> %d" % (rel, symbol, current, want))
            lines[line_index] = new_line

    # The file-anchored RANGE citations. The gate requires a range's START
    # to be a declaration line in the file it names -- the reader lands on
    # the start, so the start has to be where the thing is declared.
    #
    # The declaration to point at is the one NEAREST to the range's own
    # start, for the same reason the bare citations use it: a file can
    # declare several of the symbols the gate knows, and the range is
    # about the one it is closest to.
    for line_index, line in enumerate(lines):
        for m in RANGE_RE.finditer(line):
            rel_file = m.group(1)
            start = int(m.group(2))
            end = int(m.group(3))
            target = range_target(rel_file, start)
            if target is None or target == start:
                continue
            lines[line_index] = (
                line[:m.start()]
                + "`%s:%d-%d`" % (rel_file, target, end)
                + line[m.end():]
            )
            report.append(
                "  %s: range %d-%d -> %d-%d" % (rel_file, start, end, target, end))

    return "\n".join(lines), report


def main():
    check = "--check" in sys.argv
    wanted = the_wanted()
    if not wanted:
        print("FAIL: no citation could be resolved -- the script cannot do its job")
        return 1

    changed = 0
    for rel in DOCS:
        path = REPO / rel
        if not path.is_file():
            print("FAIL: %s does not exist" % rel)
            return 1
        original = path.read_text(encoding="utf-8")
        updated, report = reanchor(original, wanted)
        if updated == original:
            continue
        changed += 1
        print("%s:" % rel)
        for line in report:
            print(line)
        if not check:
            # CRLF and UTF-8 without BOM: the repository convention.
            path.write_bytes(updated.replace("\n", "\r\n").encode("utf-8"))

    if not changed:
        print("every citation already lands on its declaration")
        return 0
    if check:
        print("FAIL: the citations are not anchored to the declarations")
        return 1
    print("rewrote %d document(s)" % changed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
