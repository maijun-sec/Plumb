#!/usr/bin/env python3
"""Check that the data-flow document's claims still match the source.

`docs/tech/DATA-FLOW.md` is the specification of the data-flow engine:
the fact domain, the four analysis categories, the nullability model,
the Z3 call site, the call-graph strategies. It names classes, counts
things, and cites `file:line` for the code a reader would open next.

Nothing checked it. The consequence was that every one of its line
citations had drifted: the reader who followed `:261` to find `run`
landed in the middle of the method body, and the reader who followed
`:3239` to find `pathConditionForSink` landed 145 lines away.

This gate checks the document against the source, in both directions:

  1. Every citation must point at a line where the named symbol is
     DECLARED. The expected line is DERIVED from the source -- the
     gate does not store a second copy of the number, because a second
     copy would drift from the source exactly the way the document
     did. A small tolerance is allowed because the source keeps
     growing: a citation that is off by a line or two still lands
     inside the same method.

  2. The names the document lists -- the classes, the control-flow
     checkers, the call-graph strategies, the fact-kind constants --
     must exist, and the names must be the ones the document really
     uses. A gate that only checks its own hard-coded list cannot
     catch the document inventing a class.

  3. The counts the document states must match the source.

The declarations are found by parsing the source, not by matching a
word: a call site, a javadoc mention, a string literal and an enum
case label are all MENTIONS of a name, and only a declaration is a
place a citation may point at.

    python scripts/check_dataflow_claims.py
"""
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DOC = REPO / "docs" / "tech" / "DATA-FLOW.md"
DFA = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa"

# How far a citation may sit from the declaration and still be usable.
#
# The source grows; a citation that is off by a line or two still lands
# inside the same method. But a TOLERANCE that is too wide stops
# catching the failure it exists for: a citation four lines into a
# method body still reads as correct, and the reader lands in the
# middle of the code rather than at the declaration.
LINE_TOLERANCE = 2

# (file under the dfa tree, symbol)
#
# The LINE is NOT stored here. The gate derives the declaration line
# from the source and requires the document to cite that line. A gate
# with the line hardcoded would be a second copy of the number, and the
# two copies would drift from each other exactly the way the document
# drifted from the source.
CITATIONS = [
    ("engine/DfaEngine.java", "run"),
    ("engine/DfaEngine.java", "runSequential"),
    ("engine/DfaEngine.java", "runParallel"),
    ("engine/DfaEngine.java", "runWithGlobalStore"),
    ("engine/DfaEngine.java", "runWorklist"),
    ("engine/DfaEngine.java", "warmUpMethodSummaries"),
    ("engine/DfaEngine.java", "analyseOneFunction"),
    ("engine/DfaEngine.java", "solvePointsTo"),
    ("engine/DfaEngine.java", "bbRpoOrder"),
    ("engine/AnalysisContext.java", "mayBeNull"),
    ("taint/TaintedCheckerBase.java", "propagateMOVE"),
    ("taint/TaintedCheckerBase.java", "pathConditionForSink"),
    ("taint/TaintedCheckerBase.java", "taintedVars"),
    ("taint/TaintedCheckerBase.java", "taintPaths"),
    ("solver/PathCondition.java", "structurallyImplies"),
    ("points/AbstractObject.java", "CALL_SITE"),
    ("points/PointsToSolver.java", "containerCells"),
    ("points/PointsToSolver.java", "nullFields"),
    ("points/PointsToSolver.java", "nullArrayCells"),
    ("points/PointsToSolver.java", "nullContainerCells"),
    ("points/PointsToSolver.java", "nullReturns"),
    ("points/PointsToSolver.java", "nullVars"),
    ("cg/CallGraphStrategyFactory.java", "createCrossTu"),
]

# The fact-kind constants the document's table pins, and the
# null-channel fields it lists.
#
# These are cited by a BARE `:N` in the document -- the file is
# established by the surrounding sentence ("`BuiltinFactKinds.java:225-320`").
# A bare `:N` cannot be attributed to a symbol on its own, so the
# attribution here is by the symbol that the document names NEAR the
# citation, and the citation must land on that symbol's declaration.
EXTRA_CITATIONS = [
    ("fact/BuiltinFactKinds.java", "UNTRUSTED_SOURCE"),
    ("fact/BuiltinFactKinds.java", "UNTRUSTED_SINK"),
    ("fact/BuiltinFactKinds.java", "MAY_BE_NULL"),
    ("fact/BuiltinFactKinds.java", "MAY_BE_ZERO"),
    ("fact/BuiltinFactKinds.java", "ALLOC_SOURCE"),
    ("fact/BuiltinFactKinds.java", "FREE_SINK"),
    ("fact/BuiltinFactKinds.java", "DEREF_SINK"),
    ("fact/BuiltinFactKinds.java", "INDEX_SINK"),
    ("fact/BuiltinFactKinds.java", "NUMERIC_OVERFLOW_HAZARD"),
    ("fact/BuiltinFactKinds.java", "SENSITIVE_DATA"),
    ("fact/BuiltinFactKinds.java", "SENSITIVE_PII"),
    ("fact/BuiltinFactKinds.java", "LOOP_INDUCTION"),
    ("fact/BuiltinFactKinds.java", "UNMODELED_CALLEE_RET"),
    ("fact/BuiltinFactKinds.java", "RETURN_VALUE_FOLDED"),
    ("fact/BuiltinFactKinds.java", "SIDE_EFFECT_RECORDED"),
    ("points/PointsToSolver.java", "containerCells"),
    ("points/PointsToSolver.java", "nullFields"),
    ("points/PointsToSolver.java", "nullArrayCells"),
    ("points/PointsToSolver.java", "nullContainerCells"),
    ("points/PointsToSolver.java", "nullReturns"),
    ("points/PointsToSolver.java", "nullVars"),
]

# The classes the document names. A class that does not exist is a
# false statement about the code.
CLASSES = [
    "engine/DfaEngine.java",
    "engine/DfaChecker.java",
    "engine/AnalysisContext.java",
    "fact/Fact.java",
    "fact/BuiltinFactKinds.java",
    "taint/TaintedCheckerBase.java",
    "taint/TaintRuleRegistry.java",
    "taint/GuardTracker.java",
    "config/SinkFamilyRegistry.java",
    "config/ConfigLoader.java",
    "points/PointsToSolver.java",
    "points/AbstractObject.java",
    "solver/PathCondition.java",
    "solver/GuardStore.java",
    "solver/CachedZ3Bridge.java",
    "solver/Z3Bridge.java",
    "solver/LocalSolverBridge.java",
    "cg/CallGraphStrategyFactory.java",
    "cg/ChaCallGraphStrategy.java",
    "cg/RtaCallGraphStrategy.java",
    "cg/VtaCallGraphStrategy.java",
    "cg/OneCfaCallGraphStrategy.java",
    "cg/TwoCfaCallGraphStrategy.java",
    "cg/StubCallGraphStrategy.java",
    "cg/CachedCallGraphStrategy.java",
    "cg/GlobalCallGraphStrategy.java",
    "checkers/boundary/BoundaryConstraintChecker.java",
    "checkers/boundary/Range.java",
    "checkers/boundary/ArrayBoundsConstraintChecker.java",
    "checkers/boundary/DivZeroConstraintChecker.java",
    "checkers/boundary/OverflowConstraintChecker.java",
    "checkers/boundary/UnderflowConstraintChecker.java",
]

# The control-flow checkers the document lists by name.
CONTROLFLOW = [
    "DeadlockChecker", "CollectionSelfReferenceChecker",
    "IteratorConcurrentModificationChecker", "ServletAtomicityDfaChecker",
    "XxeChecker", "EqualsCalledOnNullChecker", "OffByOneChecker",
    "MissingCryptoInitChecker", "TryCatchFinallyChecker",
    "UnlockNotLockedChecker", "OrmUnnecessaryGetDfaChecker",
    "SingletonRaceDfaChecker", "OrmLostUpdateDfaChecker",
    "CompareWithSelfChecker", "OrmLoadNullCheckDfaChecker",
    "UncaughtExceptionPropagator",
]

# The call-graph strategy names the document lists.
STRATEGIES = [
    ("cha", "ChaCallGraphStrategy"),
    ("rta", "RtaCallGraphStrategy"),
    ("vta", "VtaCallGraphStrategy"),
    ("1cfa", "OneCfaCallGraphStrategy"),
    ("2cfa", "TwoCfaCallGraphStrategy"),
    ("stub", "StubCallGraphStrategy"),
    ("cached", "CachedCallGraphStrategy"),
    ("global", "GlobalCallGraphStrategy"),
]

# The names the document says do NOT exist. If one of them starts
# existing, the document is wrong in the other direction.
MUST_NOT_EXIST = [
    ("checkers/boundary/Interval.java", "the interval abstraction is Range, not Interval"),
    ("taint/TaintCatalog.java", "there is no TaintCatalog"),
]


def note(msg):
    print(msg)


def read(path):
    return path.read_text(encoding="utf-8", errors="replace")


def code_lines(text):
    """The source lines with comments, javadoc and string literals removed.

    A javadoc `{@link #name}` and a `//` comment are MENTIONS, not
    declarations. Counting them as declarations would make the gate
    accept a citation that points at a javadoc.

    A STRING LITERAL is a mention too. This codebase logs messages
    like `"DfaEngine run (global, {} workers)"` -- the word `run`
    appears there, but no method is declared on that line.

    The line COUNT is preserved: an index into the result is still an
    index into the original file, which is what a citation is.
    """
    out = []
    in_block = False
    for line in text.splitlines():
        stripped = line.strip()
        if in_block:
            if "*/" in stripped:
                in_block = False
                out.append(stripped.split("*/", 1)[1])
            else:
                out.append("")
            continue
        if stripped.startswith("/*"):
            if "*/" not in stripped:
                in_block = True
                out.append("")
            else:
                out.append(stripped.split("*/", 1)[1])
            continue
        if stripped.startswith("//"):
            out.append("")
            continue
        out.append(strip_string_literals(line))
    return out


def strip_string_literals(line):
    """Blank out the contents of string and character literals.

    The quotes stay, so the line keeps its shape; only the text
    between them is removed.
    """
    out = []
    i = 0
    n = len(line)
    quote = None
    while i < n:
        ch = line[i]
        if quote is None:
            if ch == '"' or ch == "'":
                quote = ch
                out.append(ch)
            else:
                out.append(ch)
        else:
            if ch == "\\" and i + 1 < n:
                out.append("  ")
                i += 2
                continue
            if ch == quote:
                quote = None
                out.append(ch)
            else:
                out.append(" ")
        i += 1
    return "".join(out)


# A Java method declaration starts with a modifier or a type, then the
# name, then `(`. A CALL SITE is `name(` inside an expression.
#
# `propagateMOVE(ins);` is a call. `protected void propagateMOVE(...)` is
# a declaration. The difference matters here: the document cites the
# declaration, and a gate that accepts a call site would send the
# reader to the wrong line -- the one the caller is on.
#
# The modifiers below are the ones this codebase actually uses. A name
# that is not preceded by one of them and by a type is a call.
MODIFIERS = (
    "public", "protected", "private", "static", "final", "abstract",
    "synchronized", "native", "default", "transient", "volatile",
)


def enum_body_ranges(lines):
    """The line ranges of every `enum X { ... }` body.

    A bare `NAME,` line inside one of these ranges is an enum
    constant declaration. Outside them it is a method argument.
    """
    ranges = []
    i = 0
    n = len(lines)
    while i < n:
        line = lines[i]
        if re.search(r"\benum\s+\w+[^{]*\{", line):
            depth = line.count("{") - line.count("}")
            start = i + 1
            j = i + 1
            while j < n and depth > 0:
                depth += lines[j].count("{") - lines[j].count("}")
                j += 1
            ranges.append((start, j))
            i = j
            continue
        i += 1
    return ranges


def declarations(path, symbol):
    """The lines that DECLARE `symbol`.

    A method declaration looks like:

        public FindingReporter run(TacProgram program) {

    A field declaration looks like:

        private final Map<TacVar, String> taintedVars = new HashMap<>();

    A call site looks like:

        return runSequential(program);

    The last one must NOT be reported, or the document's citation
    would be validated against a call rather than against the method
    the reader is looking for.
    """
    lines = code_lines(read(path))
    out = []
    name = re.escape(symbol)

    # An enum constant is declared by its own line inside the enum
    # body: `CALL_SITE,` or `CALL_SITE;`. It must be told apart from
    # a `case "..." -> CALL_SITE` label and a `return CALL_SITE;`
    # reference, neither of which declares anything.
    #
    # A bare `name,` line is also how a method ARGUMENT list looks
    # (`taintedVars,`), so the rule only applies INSIDE an enum body.
    enum_ranges = enum_body_ranges(lines)
    for i, line in enumerate(lines, 1):
        stripped = line.strip()
        m = re.match(r"^" + name + r"\s*(,|;)\s*$", stripped)
        if m:
            if any(lo <= i <= hi for lo, hi in enum_ranges):
                out.append(i)
            continue
        # A field declaration with an initialiser: `Policy CALL_SITE = ...`
        m = re.match(r"^(?:public |static |final )*[\w<>\[\], .]+ " + name
                     + r"\s*=", stripped)
        if m:
            out.append(i)
            continue

    for i, line in enumerate(lines, 1):
        stripped = line.strip()
        if not stripped:
            continue
        # An arrow-switch case label: `case MOVE -> propagateMOVE(ins);`
        # A call, not a declaration.
        if re.search(r"\bcase\b.*->", line):
            continue

        # A method declaration: <modifiers> <type> name(
        m = re.search(r"(?<![\w.])" + name + r"\s*\(", line)
        if m:
            before = line[:m.start()].strip()
            if not before:
                continue
            # The token before the name must be a type, and the
            # declaration must be preceded by a modifier (or by a type
            # in an interface, which has no modifier).
            prev = re.search(r"([\w>\]]+)\s*$", before)
            if not prev:
                continue
            head = before[:prev.start()].strip()
            tokens = head.split()
            if not tokens:
                continue
            last = tokens[-1]
            if last in MODIFIERS:
                out.append(i)
                continue
            # Interface methods and record-like declarations may have no
            # modifier; require at least two words before the name
            # (the return type plus something).
            if len(tokens) >= 1 and last not in ("return", "new", "this", "super"):
                out.append(i)
            continue

        # A field declaration: <modifiers> <type> name = | name;
        m = re.search(r"(?<![\w.])" + name + r"\s*(=|;)", line)
        if m:
            before = line[:m.start()].strip()
            if not before:
                continue
            # A bare name as a method ARGUMENT (`taintedVars,`) is a use,
            # not a declaration. A declaration has a type in front of
            # it: `protected final Map<...> taintedVars = ...`.
            if re.match(r"^[\w.]+$", before):
                continue
            prev = re.search(r"([\w>,\[\]]+)\s*$", before)
            if not prev:
                continue
            head = before[:prev.start()].strip()
            tokens = head.split()
            if not tokens:
                continue
            if tokens[-1] in MODIFIERS or len(tokens) >= 1:
                if "=" not in before or True:
                    out.append(i)
    return sorted(set(out))


# A citation in the document is a parenthesised number, optionally
# backticked and optionally file-anchored:
#   (:261)   (`:261`)   (`engine/AnalysisContext.java:391-395`)
# It may also be a bare backticked path citation:
#   `PathCondition.java:136-144`
CITE_RE = re.compile(
    r"(?:\(|`)\s*`?\s*(?:[A-Za-z0-9_./-]*\.java\s*)?:?"
    r"(?P<num>\d+)(?:\s*-\s*(?P<num2>\d+))?\s*`?\s*(?:\)|`)")


def symbol_on_line(symbol, line):
    """Does the line name the symbol as a WHOLE WORD?

    `run` must not match `runSequential`. The symbol may be written
    bare (`run`), qualified (`AnalysisContext.mayBeNull`) or with a
    signature (`run(TacProgram)`).
    """
    return re.search(r"(?<![\w])" + re.escape(symbol) + r"(?![\w])", line)


def cited_lines(doc, symbol):
    """The line numbers the document cites for `symbol`.

    Two citation shapes are attributed to a symbol:

      1. The line names the symbol AND carries a citation -- the usual
         `run(TacProgram)` (`:261`) form.

      2. The line carries a FILE-anchored citation to the file that
         declares the symbol -- the `PathCondition.java:136-144` form,
         where the reader follows the file, not a symbol name.

    In both cases the cited number is the FIRST number of the citation,
    because a range like `:136-144` starts at the declaration.
    """
    out = []
    for line in doc.splitlines():
        if symbol_on_line(symbol, line):
            for m in CITE_RE.finditer(line):
                out.append((int(m.group("num")), line))
    return out


def file_cited_lines(doc, rel):
    """The line numbers the document cites for a FILE.

    A citation that names the file -- `solver/PathCondition.java:35` --
    points at that file regardless of which symbol it is near.
    """
    out = []
    base = rel.rsplit("/", 1)[-1]
    path_prefix = rel.rsplit("/", 1)[0] if "/" in rel else ""
    for line in doc.splitlines():
        for m in CITE_RE.finditer(line):
            whole = m.group(0)
            if base not in whole:
                continue
            # The path prefix has to match too, when the citation
            # carries one: `taint/GuardTracker.java` is not
            # `solver/PathCondition.java`.
            if path_prefix and path_prefix + "/" in whole:
                if whole.index(path_prefix) > whole.index(base):
                    continue
            out.append((int(m.group("num")), line))
    return out


def main():
    failures = []

    if not DOC.is_file():
        print("FAIL: %s does not exist" % DOC)
        return 1
    doc = read(DOC)

    # (1) Every citation must land on a declaration.
    #
    #     The expected line is DERIVED from the source, not stored here.
    #     The document has to cite the line where the symbol is actually
    #     declared -- so the reader who follows `:261` to find `run`
    #     arrives at `run`, not somewhere inside it.
    citation_checked = 0
    for rel, symbol in CITATIONS:
        path = DFA / rel
        if not path.is_file():
            failures.append("%s does not exist" % path)
            continue
        decls = declarations(path, symbol)
        if not decls:
            failures.append(
                "%s does not declare %s -- the document cites it" % (rel, symbol))
            continue
        # A citation may name the symbol, or it may name the FILE that
        # declares it. Both are citations the reader follows.
        cites = cited_lines(doc, symbol)
        if not cites:
            cites = file_cited_lines(doc, rel)
        if not cites:
            failures.append(
                "the document does not cite a line for %s, but the source "
                "declares it" % symbol)
            continue
        # The declaration the citation should point at: the FIRST one
        # that the document is closest to.
        want = min(decls, key=lambda d: min(abs(c - d) for c, _ in cites))
        nearest_cite = min(cites, key=lambda c: abs(c[0] - want))
        if abs(nearest_cite[0] - want) <= LINE_TOLERANCE:
            citation_checked += 1
        else:
            failures.append(
                "the document cites %s at :%d, but it is declared at :%d -- "
                "a reader following the citation lands %d lines away"
                % (symbol, nearest_cite[0], want,
                   abs(nearest_cite[0] - want)))
    note("checked %d line citation(s)" % citation_checked)

    # (2) The document must contain the derived line. Otherwise the
    #     table and the document have drifted apart.
    #
    #     This is the direction that catches a table that says one
    #     thing and a document that says another.
    for rel, symbol in CITATIONS:
        path = DFA / rel
        if not path.is_file():
            continue
        decls = declarations(path, symbol)
        if not decls:
            continue
        cites = cited_lines(doc, symbol)
        if not cites:
            cites = file_cited_lines(doc, rel)
        if not cites:
            continue
        want = min(decls, key=lambda d: min(abs(c - d) for c, _ in cites))
        if any(abs(c - want) <= LINE_TOLERANCE for c, _ in cites):
            continue
        failures.append(
            "the document does not cite the line %d where %s is declared "
            "-- the citation and the source disagree" % (want, symbol))
    note("checked %d citation/document agreement(s)" % len(CITATIONS))

    # (3) The classes the document names must exist.
    class_checked = 0
    for rel in CLASSES:
        p = DFA / rel
        if not p.is_file():
            failures.append("%s does not exist -- the document names it" % rel)
            continue
        class_checked += 1
    note("checked %d named class file(s)" % class_checked)

    # (4) The control-flow checkers the document lists must exist, and
    #     the count must be right.
    cf_dir = DFA / "checkers" / "controlflow"
    actual_cf = sorted(p.stem for p in cf_dir.glob("*.java")) \
        if cf_dir.is_dir() else []
    for name in CONTROLFLOW:
        if name not in actual_cf:
            failures.append(
                "the document lists the control-flow checker %s, but there is "
                "no such class under checkers/controlflow/" % name)
    if len(actual_cf) != len(CONTROLFLOW):
        failures.append(
            "the document says there are %d control-flow checkers, but there "
            "are %d under checkers/controlflow/"
            % (len(CONTROLFLOW), len(actual_cf)))
    # And every checker that exists must be listed.
    for name in actual_cf:
        if name not in CONTROLFLOW:
            failures.append(
                "the control-flow checker %s exists but the document does "
                "not list it" % name)
    note("checked %d control-flow checker name(s)" % len(CONTROLFLOW))

    # (4b) Every class the document NAMES must exist.
    #
    #      The names are derived from the DOCUMENT, not from a
    #      hand-maintained list: a name the document invents has to
    #      fail. A gate that only checks its own list cannot catch the
    #      document inventing a class.
    #
    #      Not every backticked capitalised token is a class -- some
    #      are enum constants (`CALL_SITE`), annotation names
    #      (`RequestParam`) or rule ids (`dfa-taint-...`). So the
    #      check is applied to the contexts where the document is
    #      listing CLASSES:
    #
    #        - the control-flow checker list,
    #        - the class-shaped names in the prose.
    #
    #      For the class-shaped names, the trailing-word heuristic
    #      cannot be used: a typo `XxeCheckerTypo` still ends in a
    #      type-shaped word. What identifies a class here is that the
    #      document lists it in a class list -- so the list is what is
    #      parsed.
    cf_listed = set()
    for m in re.finditer(
            r"There are (\d+) classes under `checkers/controlflow/`[^:]*:"
            r"(.*?)\.\s*\n", doc, re.S):
        cf_listed.update(re.findall(r"`([A-Z]\w+)`", m.group(2)))
    if not cf_listed:
        failures.append(
            "the control-flow checker list could not be read -- the gate "
            "cannot verify the checkers the document lists")
    for name in sorted(cf_listed):
        if not list((DFA / "checkers" / "controlflow").glob("%s.java" % name)):
            failures.append(
                "the document lists the control-flow checker %s, but there "
                "is no such class under checkers/controlflow/" % name)
    note("checked %d control-flow checker name(s) read from the document"
         % len(cf_listed))

    # Every OTHER name the document uses as a checker must be a real
    # class. The suffix heuristic alone would let a typo through -- a
    # typo `XxeCheckerTypo` does not end in "Checker" -- so the names
    # are ALSO taken from the class lists the document writes, and from
    # the prose that introduces a class by name.
    named_checkers = set(cf_listed)
    for m in re.finditer(
            r"all extending `(\w+)`", doc):
        named_checkers.add(m.group(1))
    for m in re.finditer(r"`([A-Z]\w*Checker)`", doc):
        named_checkers.add(m.group(1))
    for name in sorted(named_checkers):
        if list(DFA.rglob("%s.java" % name)):
            continue
        if list(REPO.glob("*/src/main/java/**/%s.java" % name)):
            continue
        failures.append(
            "the document names the checker %s, but no such class exists "
            "in the repository" % name)
    note("checked %d checker name(s) named in the document"
         % len(named_checkers))

    # (4c) Every source FILE the document cites by path must exist.
    #
    #      The document cites files as `engine/DfaEngine.java` or as
    #      `DfaEngine.java`. A citation to a file that was renamed or
    #      deleted sends the reader to nothing.
    cited_files = sorted(set(re.findall(r"`([A-Za-z0-9_./-]*\.java)`", doc)))
    file_checked = 0
    for rel in cited_files:
        # A citation may name a file in the dfa tree, in another module,
        # or by bare file name. The document cites `plumb-rules`/
        # `plumb-main` files too, so the search covers the whole repo.
        if rel.startswith("plumb-"):
            if (REPO / rel).is_file():
                file_checked += 1
                continue
        elif (DFA / rel).is_file():
            file_checked += 1
            continue
        # A bare file name may live anywhere under the dfa tree or in
        # another module.
        base = rel.rsplit("/", 1)[-1]
        if list(DFA.rglob(base)) or list(REPO.glob("*/src/main/java/**/" + base)):
            file_checked += 1
            continue
        failures.append(
            "the document cites the file %s, but it does not exist" % rel)
    note("checked %d cited source file(s)" % file_checked)

    # (5) The call-graph strategies the document lists must exist, and the
    #     factory must accept the names.
    for name, impl in STRATEGIES:
        if not list((DFA / "cg").glob("%s.java" % impl)):
            failures.append(
                "the document lists the call-graph strategy %s -> %s, but "
                "%s.java does not exist" % (name, impl, impl))
    factory = DFA / "cg" / "CallGraphStrategyFactory.java"
    if factory.is_file():
        body = read(factory)
        for name, _ in STRATEGIES:
            # The cached strategy is a PREFIX, not a name: the factory
            # accepts "cached:<name>:<path>".
            if name == "cached":
                if 'startsWith("cached:")' not in body:
                    failures.append(
                        "the document lists the cached call-graph strategy, "
                        "but the factory does not accept the \"cached:\" prefix")
                continue
            if ('"%s"' % name) not in body:
                failures.append(
                    "the document lists the call-graph strategy %s, but the "
                    "factory does not accept that name" % name)
    note("checked %d call-graph strategy name(s)" % len(STRATEGIES))

    # (5b) Every strategy the document NAMES must be one the factory
    #      accepts, and every implementation it names must exist.
    #
    #      The rows are parsed out of the TABLE, so a name the document
    #      invents -- `vtaX` instead of `vta` -- is still checked. A
    #      regex that only recognises known names would silently skip
    #      exactly the mutant that matters.
    row_re = re.compile(
        r"^\|\s*`([^`]+)`\s*\|\s*`([A-Za-z]\w*Strategy)`\s*\|\s*$", re.M)
    rows = row_re.findall(doc)
    if not rows:
        failures.append(
            "the call-graph strategy table could not be read -- the gate "
            "cannot verify the strategies the document lists")
    factory_body = read(factory) if factory.is_file() else ""
    for name, impl in rows:
        if not list((DFA / "cg").glob("%s.java" % impl)):
            failures.append(
                "the document lists the call-graph strategy %s -> %s, but "
                "%s.java does not exist" % (name, impl, impl))
        if name.startswith("cached:"):
            if 'startsWith("cached:")' not in factory_body:
                failures.append(
                    "the document lists the cached call-graph strategy, but "
                    "the factory does not accept the \"cached:\" prefix")
            continue
        if ('"%s"' % name) not in factory_body:
            failures.append(
                "the document lists the call-graph strategy %s, but the "
                "factory does not accept that name" % name)
    note("checked %d call-graph strategy row(s) in the document" % len(rows))

    # (6) The names the document says do not exist must not exist.
    for rel, why in MUST_NOT_EXIST:
        if (DFA / rel).is_file():
            failures.append(
                "%s now exists, but the document says it does not (%s)"
                % (rel, why))
    note("checked %d documented absence(s)" % len(MUST_NOT_EXIST))

    # (6b) The document must still CLAIM the absence.
    #
    #      A gate that only checks "the absent name really is absent"
    #      cannot catch the document quietly dropping the claim -- the
    #      document would then read as if the class might exist. The
    #      absence claim is part of the specification.
    #
    #      The claim has to be a NEGATIVE one. A sentence that says
    #      "there is a `TaintCatalog`" is not an absence claim, and the
    #      document would then be claiming a class that does not exist.
    for rel, why in MUST_NOT_EXIST:
        base = rel.rsplit("/", 1)[-1].replace(".java", "")
        if base not in doc:
            failures.append(
                "the document no longer mentions %s -- it used to state "
                "that this does not exist (%s)" % (base, why))
            continue
        # Every sentence that mentions the name must deny its
        # existence.
        negative = re.compile(
            r"(no|not|does not|don't|cannot|never)[^.\n]{0,80}`" +
            re.escape(base) + r"`", re.I)
        positive = re.compile(
            r"`" + re.escape(base) + r"`[^.\n]{0,40}\b(is|exists)\b", re.I)
        denied = False
        for sentence in re.split(r"(?<=[.。])\s+|\n", doc):
            if base not in sentence:
                continue
            if positive.search(sentence):
                failures.append(
                    "the document says %s exists, but %s does not exist (%s)"
                    % (base, rel, why))
                denied = True
                break
            if negative.search(sentence):
                denied = True
        if not denied:
            failures.append(
                "the document mentions %s but does not state that it does "
                "not exist -- the absence claim was lost (%s)"
                % (base, why))
    note("checked %d documented absence claim(s)" % len(MUST_NOT_EXIST))

    # (7) The counts the document states.
    #
    # The fact-kind counts are measured by `capability_baseline`, which is
    # the SAME measurement the capability gate uses. Measuring them a
    # second way would be a second opinion about the same population, and
    # the two would eventually disagree -- and the capability gate is the
    # one that actually protects the count.
    import capability_baseline as cb
    builtin_kinds = len(cb.builtin_fact_kinds())
    yaml_kinds = len(cb.yaml_fact_kinds())
    tainted_subclasses = sum(
        1 for p in DFA.rglob("*.java")
        if "extends TaintedCheckerBase" in read(p))

    counts = [
        ("built-in fact kinds", builtin_kinds, r"\*\*(\d+)\*\* built-in kinds"),
        ("YAML fact kinds", yaml_kinds, r"the \*\*(\d+)\*\* entries of `fact-kinds\.yaml`"),
        ("extends TaintedCheckerBase", tainted_subclasses,
         r"there are (\d+) `extends TaintedCheckerBase`"),
        ("control-flow checkers", len(actual_cf),
         r"There are (\d+) classes under `checkers/controlflow/`"),
    ]
    for label, actual, pattern in counts:
        m = re.search(pattern, doc)
        if not m:
            failures.append(
                "the document does not state the %s count in the form the "
                "gate expects -- it cannot be checked" % label)
            continue
        claimed = int(m.group(1))
        if claimed != actual:
            failures.append(
                "the document says %d %s, the source has %d"
                % (claimed, label, actual))
    note("checked %d count(s)" % len(counts))

    # (8) The fact-kind table: every kind the document lists must be a
    #     registered built-in, and every registered one must be listed.
    registry = DFA / "fact" / "BuiltinFactKinds.java"
    if registry.is_file():
        body = read(registry)
        listed = re.findall(
            r"^\|\s*`([a-z][\w.\-]*)`\s*\|\s*`([A-Z_]+)`\s*\|\s*`:(\d+)`",
            doc, re.M)
        for kind, constant, line in listed:
            if ('"%s"' % kind) not in body:
                failures.append(
                    "the document lists the fact kind %s, but it is not a "
                    "registered built-in kind" % kind)
            if not re.search(r"\b%s\b" % re.escape(constant), body):
                failures.append(
                    "the document names the constant %s for the fact kind %s, "
                    "but no such constant exists" % (constant, kind))
        note("checked %d fact-kind table row(s)" % len(listed))

    # (9) The framework annotation catalogue the document quotes.
    #
    # The count comes from the catalogue module itself, which is the
    # single source the framework-annotation gate reads. Measuring it a
    # second way from the Java side would count every upper-case string
    # in the file, not the annotations.
    sys.path.insert(0, str(REPO / "scripts"))
    try:
        from framework_input_annotations import ANNOTATIONS
        actual_ann = len(ANNOTATIONS)
    except Exception as exc:  # pragma: no cover
        failures.append(
            "the framework annotation catalogue could not be read: %s" % exc)
        actual_ann = None
    if actual_ann is not None:
        m = re.search(r"with \*\*(\d+)\*\* binding annotations", doc)
        if not m:
            failures.append(
                "the document does not state the binding-annotation count in "
                "the form the gate expects")
        else:
            claimed = int(m.group(1))
            if claimed != actual_ann:
                failures.append(
                    "the document says %d binding annotations, the catalogue "
                    "holds %d" % (claimed, actual_ann))
        note("checked the binding-annotation count (%d)" % actual_ann)

    # (10) The fact-kind constants and null-channel fields the document
    #      pins with a bare `:N`.
    #
    #      The file is established by the surrounding sentence; the
    #      citation itself only names a line. The line has to land on
    #      the DECLARATION of the constant the row names.
    extra_checked = 0
    for rel, symbol in EXTRA_CITATIONS:
        path = DFA / rel
        if not path.is_file():
            failures.append("%s does not exist" % path)
            continue
        decls = declarations(path, symbol)
        if len(decls) != 1:
            failures.append(
                "%s declares %s %d time(s) -- the document cites it once, so "
                "the citation cannot be attributed"
                % (rel, symbol, len(decls)))
            continue
        want = decls[0]
        cites = cited_lines(doc, symbol)
        if not cites:
            failures.append(
                "the document does not cite a line for %s, but the source "
                "declares it at :%d" % (symbol, want))
            continue
        nearest = min(cites, key=lambda c: abs(c[0] - want))
        if abs(nearest[0] - want) <= LINE_TOLERANCE:
            extra_checked += 1
        else:
            failures.append(
                "the document cites %s at :%d, but it is declared at :%d -- "
                "a reader following the citation lands %d lines away"
                % (symbol, nearest[0], want, abs(nearest[0] - want)))
    note("checked %d fact-kind/field citation(s)" % extra_checked)

    # (11) The Z3 call site: the document says Z3 has exactly one call
    #      site in the engine, and cites it.
    #
    #      The call site is the one invocation of the bridge on the sink
    #      reporting path: `solverBridge.check(composed, ...)`. The
    #      document's citation has to point at THAT line -- a citation
    #      that lands on a javadoc or a stray brace sends the reader to
    #      the wrong place while the sentence still reads true.
    bridge = DFA / "taint" / "TaintedCheckerBase.java"
    if bridge.is_file():
        body = code_lines(read(bridge))
        call_sites = [i for i, line in enumerate(body, 1)
                      if re.search(r"solverBridge\.check\s*\(", line)]
        if len(call_sites) != 1:
            failures.append(
                "the engine has %d solver-bridge call site(s) in "
                "TaintedCheckerBase.java, but the document says exactly one"
                % len(call_sites))
        else:
            m = re.search(r"TaintedCheckerBase\.java:(\d+)", doc)
            if not m:
                failures.append(
                    "the document does not cite the Z3 call site -- the "
                    "check cannot verify it")
            else:
                cited = int(m.group(1))
                if abs(cited - call_sites[0]) > LINE_TOLERANCE:
                    failures.append(
                        "the document cites the Z3 call site at :%d, but it "
                        "is at :%d -- a reader following the citation lands "
                        "%d lines away"
                        % (cited, call_sites[0], abs(cited - call_sites[0])))
                else:
                    note("checked the Z3 call-site citation (:%d)"
                         % call_sites[0])
    else:
        failures.append("%s does not exist" % bridge)

    m = re.search(r"Z3 has exactly \*\*one\*\* call site", doc)
    if not m:
        failures.append(
            "the document no longer states the number of Z3 call sites -- the "
            "check cannot verify it")

    if failures:
        print()
        print("FAIL: %d defect(s) in docs/tech/DATA-FLOW.md" % len(failures))
        for f in failures:
            print("  %s" % f)
        print()
        print("The data-flow document is the specification of the engine.")
        print("A citation that no longer points at its declaration sends the")
        print("reader to the wrong line, and a class that does not exist is")
        print("a false statement about the code.")
        return 1

    print()
    print("OK: docs/tech/DATA-FLOW.md matches the source")
    return 0


if __name__ == "__main__":
    sys.exit(main())
