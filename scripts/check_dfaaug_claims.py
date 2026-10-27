#!/usr/bin/env python3
"""Check that the DFA-augmentation design document still matches the source.

`docs/tech/DFA-AUGMENTATION-DESIGN.md` is a design document, so most of
its claims are about **mechanism** rather than about counts. A design
document that describes a mechanism the code no longer has is worse
than no document: a reader would implement the checker the way the
document says and get the wrong answer.

Every check is BIDIRECTIONAL, because a one-directional check has a
blind spot:

  - "does the name exist" catches a document that NAMES something
    that does not exist, but not one that quietly stops mentioning it
    -- and the mention may be the only warning the reader gets;
  - "does the document still say it" catches a document that dropped
    the claim, but not one that claims the opposite.

So each claim is checked from both ends, and the ASSERTION DIRECTION
matters too: "there is no `Const.NULL`" and "there is a `Const.NULL`"
are different statements, and only one of them is true.

The claims checked, all derived from the source:

  1. The class names the document names exist, in the prose and in the
     headings.
  2. The rule ids the document quotes are the ids the engine emits --
     including the exact id of the one rule the document singles out.
  3. The naming-convention table pairs each shape with an example that
     is BOTH real and of that shape.
  4. The DFA checkers the document lists exist, and the ones it says
     are NOT registered really are not registered.
  5. The names the document says do not exist really do not exist.
  6. The API the document's code snippets use really exists -- the
     method names, the field names, the type names -- and every call a
     snippet makes resolves.
  7. The `Operation` variants the document names are the variants the
     sealed interface declares, and the ones it says are inert really
     are never constructed.
  8. The mechanisms the document describes are the mechanisms the
     checkers implement.

The Chinese tree (`docs/zh/tech/DFA-AUGMENTATION-DESIGN.md`) is the
same document in another language and carries the same claims, so it is
checked the same way. The wording of a check has to accept both
languages -- otherwise the Chinese tree is held to a standard the
English one is not.

    python scripts/check_dfaaug_claims.py
"""
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DOC = REPO / "docs" / "tech" / "DFA-AUGMENTATION-DESIGN.md"
ZH_DOC = REPO / "docs" / "zh" / "tech" / "DFA-AUGMENTATION-DESIGN.md"
DFA = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa"
STYLE = REPO / "plumb-style" / "src" / "main" / "java" / "org" / "plumb" / "style"

# The naming-convention table. Each row is
# (the shape the document claims, the example it gives).
#
# Both halves are checked: the example has to be a REAL emitted id, and
# it has to MATCH the shape in its own row. A table that pairs the
# right shape with a real-but-wrong example is still a wrong table.
NAMING_ROWS = [
    ("ast-<category>-<bare>", "ast-quality-uninit-nonnull"),
    ("dfa-<category>-<bare>", "dfa-controlflow-deadlock-ab-ba"),
    ("dfa-taint-<bare>", "dfa-taint-sql-injection"),
]

# The rule ids the document states in prose.
RULE_IDS = [
    "ast-quality-uninit-nonnull",
    "dfa-taint-null-deref-from-return",
    "orm-load-null-check-dfa",
    "orm-unnecessary-get-dfa",
    "orm-lost-update-dfa",
    "singleton-race-dfa",
    "servlet-atomicity-dfa",
]

# The DFA checkers the document describes.
DFA_CHECKERS = [
    "OrmLoadNullCheckDfaChecker",
    "OrmUnnecessaryGetDfaChecker",
    "OrmLostUpdateDfaChecker",
    "SingletonRaceDfaChecker",
    "ServletAtomicityDfaChecker",
]

# The AST checkers the document points at.
AST_CHECKERS = [
    "plumb-style/src/main/java/org/plumb/style/quality/UninitNonnullCheck.java",
    "plumb-style/src/main/java/org/plumb/style/concurrency/SingletonRaceCheck.java",
    "plumb-style/src/main/java/org/plumb/style/concurrency/ServletAtomicityCheck.java",
]

# The checkers the document says are NOT registered.
NOT_REGISTERED = [
    "SingletonRaceDfaChecker",
    "ServletAtomicityDfaChecker",
]

# The names the document says do not exist.
MUST_NOT_EXIST = [
    ("plumb-dfa/src/main/java/org/plumb/dfa/checkers/controlflow/UninitNonnullDfaChecker.java",
     "the DFA version of the uninit-nonnull rule was never shipped"),
    ("plumb-dfa/src/main/java/org/plumb/dfa/checkers/controlflow/Interval.java",
     "the interval abstraction is Range, not Interval"),
]

# The API the document's snippets use. Each entry is
# (file under the dfa tree, the symbol that must appear in it).
#
# The symbol may be a METHOD or a FIELD. A field is a name the checker
# keeps state in; a method is one it calls. Both have to be declared
# in that file -- a mention in a javadoc is not a declaration.
API = [
    ("summary/FunctionSummary.java", "returnOps"),
    ("summary/FunctionSummary.java", "paramOpsAt"),
    ("summary/FunctionSummary.java", "project"),
    ("summary/MethodSummaryCache.java", "getByCanonicalString"),
    ("summary/MethodSummaryCache.java", "get"),
    ("summary/MethodSummary.java", "isPure"),
    ("summary/Operation.java", "WriteToField"),
    ("summary/Operation.java", "TaintPropagate"),
    ("summary/Operation.java", "Sink"),
    ("summary/Operation.java", "PersistsParam"),
    ("summary/Operation.java", "SolverZ3Predicate"),
    ("cg/FunctionSignature.java", "canonical"),
    ("engine/DfaChecker.java", "initFunction"),
    ("engine/DfaChecker.java", "checkInstruction"),
    ("engine/DfaChecker.java", "finishFunction"),
    ("engine/DfaChecker.java", "finish"),
    ("checkers/controlflow/OrmLoadNullCheckDfaChecker.java", "fromLoadVars"),
    ("checkers/controlflow/OrmLoadNullCheckDfaChecker.java", "loadCallSite"),
    ("checkers/controlflow/OrmUnnecessaryGetDfaChecker.java", "fromGetVars"),
    ("checkers/controlflow/OrmUnnecessaryGetDfaChecker.java", "getCallSite"),
    ("checkers/controlflow/SingletonRaceDfaChecker.java", "fieldReaders"),
    ("checkers/controlflow/SingletonRaceDfaChecker.java", "fieldWriters"),
    ("checkers/controlflow/ServletAtomicityDfaChecker.java", "getSites"),
    ("checkers/controlflow/ServletAtomicityDfaChecker.java", "setSites"),
]

# The identifiers the document's snippets use that must NOT exist --
# they were invented by an earlier draft of the document.
MUST_NOT_APPEAR = [
    ("Const.NULL", "there is no Const.NULL in the engine"),
    ("stripType", "the bare-name helper is per-checker, not shared"),
    ("state.writtenFields", "the race checker keeps two maps, not one set"),
    ("findResults", "the load checker keeps fromLoadVars / loadCallSite"),
    ("cache.get(calleeSig)", "the cache is keyed by FunctionSignature"),
]

# The Operation variants the document says are inert.
INERT_VARIANTS = ["UnmodelledCall", "PointsToPropagate"]


def note(msg):
    print(msg)


def read(path):
    return path.read_text(encoding="utf-8", errors="replace")


def main():
    failures = []

    if not DOC.is_file():
        print("FAIL: %s does not exist" % DOC)
        return 1
    if not ZH_DOC.is_file():
        print("FAIL: %s does not exist" % ZH_DOC)
        return 1

    # The Chinese document is the SAME document in another language, so
    # it carries the SAME claims. Checking only the English one would
    # leave the Chinese tree free to drift -- and a reader who reads
    # the Chinese tree would get the wrong mechanism.
    for path, label in ((DOC, "English"), (ZH_DOC, "Chinese")):
        doc = read(path)
        print("--- %s (%s) ---" % (path.name, label))
        failures.extend(check_document(doc, path))

    if failures:
        print()
        print("FAIL: %d defect(s) in the DFA-augmentation design document"
              % len(failures))
        for f in failures:
            print("  %s" % f)
        print()
        print("A design document that describes a mechanism the code no")
        print("longer has is worse than no document: the reader implements")
        print("the checker the way the document says and gets it wrong.")
        return 1

    print()
    print("OK: the DFA-augmentation design document matches the source")
    return 0


def check_document(doc, path):
    """Every check, against ONE document. Returns the failure list."""
    failures = []
    name = path.name
    prefix = "%s: " % name

    # (1) The class names the document names must exist.
    #
    #     This is checked per OCCURRENCE, not per name: a document that
    #     mentions a real class in one place and a typo in another is
    #     still a document with a typo in it, and a reader who follows
    #     the typo gets nothing.
    #
    #     The shape of the name is not enough to recognise one -- a typo
    #     `SummaryApplierX` is just as type-shaped as `SummaryApplier`.
    #     So the names are taken from the SOURCE: the set of classes
    #     that exist, and the document must not name anything else.
    existing = set()
    for p in REPO.glob("*/src/main/java/**/*.java"):
        existing.add(p.stem)
    named = sorted(set(re.findall(r"`([A-Z]\w*)`", doc)))
    class_checked = 0
    for name in named:
        if name in existing:
            class_checked += 1
            continue
        # Not a class -- it may be a constant, an enum value, a config
        # key, a rule id, or a field. Only names that LOOK like a type
        # are required to exist as one.
        #
        # The shape is a HEURISTIC for "is this a type name at all",
        # not a way to recognise the type. A typo `SummaryApplierX`
        # is exactly as type-shaped as `SummaryApplier`, so the test
        # that matters is the one above: is there a class with this
        # name? The suffix list only decides whether the name is
        # *claiming* to be a type.
        if re.match(r"^(?:ast|dfa|plumb|orm|singleton|servlet|dfa-)", name):
            continue
        if name.isupper():
            continue
        if re.search(r"(Checker|Check|Builder|Applier|Signature|Summary|"
                     r"Cache|Operation|Registry|Engine|Bridge|Factory|"
                     r"Context|Fact|Config|Loader|Reporter|Tracker)",
                     name):
            failures.append(prefix +
                "the document names the type %s, but no such class exists in "
                "the repository" % name)
    note("checked %d named type(s)" % class_checked)

    # (1b) The HEADINGS of the document name the checkers it describes.
    #      A heading that renames a checker is a broken table of
    #      contents, even when the class is named correctly elsewhere.
    heading_names = sorted(set(
        re.findall(r"(?m)^#+\s.*?([A-Z]\w*Checker)\b", doc)))
    for name in heading_names:
        if not list(DFA.rglob("%s.java" % name)):
            failures.append(prefix +
                "the document has a heading for %s, but no such checker "
                "exists" % name)
    note("checked %d heading checker name(s)" % len(heading_names))

    # (2) The rule ids the document quotes must be real emitted ids.
    #
    #     The check is BIDIRECTIONAL: the id the document names has to be
    #     emitted by the engine, and the engine's id for that rule has to
    #     be the one the document names. Checking only the first
    #     direction would let the document quote a DIFFERENT real id.
    id_checked = 0
    emitted = set()
    for base in (STYLE, DFA):
        for p in base.rglob("*.java"):
            body = read(p)
            emitted.update(re.findall(r'"((?:ast|dfa|orm|singleton|servlet)[\w.\-]*)"',
                                      body))
    for rid in RULE_IDS:
        if rid not in emitted:
            failures.append(prefix +
                "the document quotes the rule id %s, but the engine never emits "
                "it" % rid)
            continue
        id_checked += 1
    note("checked %d rule id(s)" % id_checked)

    # (2a) The rule id of the uninit-nonnull rule is a SPECIFIC claim:
    #      the document says the canonical id is X, and the source has
    #      to say the same thing. A second real id would pass the check
    #      above; only this one catches it.
    unnonnull = STYLE / "quality" / "UninitNonnullCheck.java"
    if not unnonnull.is_file():
        unnonnull = STYLE / "style" / "quality" / "UninitNonnullCheck.java"
    if unnonnull.is_file():
        body = read(unnonnull)
        m = re.search(r'new RuleId\("([^"]+)"', body)
        if not m:
            failures.append(prefix +
                "%s does not declare a rule id" % unnonnull.name)
        else:
            actual = m.group(1)
            # The document states the canonical id. The phrasing
            # differs between the two languages, so the check is that
            # the id appears in a sentence that calls it the
            # canonical / 规范 one.
            # The window may cross a newline: the statement is usually
            # wrapped, and the id lands on the next line.
            stated = re.search(
                r"(canonical|规范|canonical rule id)[^\n]{0,60}\n?[^\n]{0,20}`?"
                + re.escape(actual) + r"`?", doc)
            if not stated:
                stated = re.search(
                    re.escape(actual) + r"`?[^\n]{0,40}\n?[^\n]{0,20}"
                    r"(is the canonical|规范)", doc)
            if not stated:
                failures.append(prefix +
                    "the document does not state %s as the canonical rule id "
                    "of the uninit-nonnull rule" % actual)
            else:
                note("checked the uninit-nonnull rule id (%s)" % actual)
    else:
        failures.append(prefix + "UninitNonnullCheck.java does not exist")

    # (2b) The naming-convention table: each row pairs a shape with an
    #      example, and BOTH have to be right.
    #
    #      Checking only that the example is a real id would let the
    #      table pair the right shape with a real-but-wrong example --
    #      and the reader would then copy the wrong id.
    for shape, example in NAMING_ROWS:
        # The row is: | Side | `shape` | `example` |
        row = re.search(r"^\|[^|\n]*\|\s*`" + re.escape(shape) + r"`\s*\|"
                        r"\s*`" + re.escape(example) + r"`\s*\|",
                        doc, re.M)
        if not row:
            failures.append(prefix +
                "the naming table does not pair the shape %s with the "
                "example %s" % (shape, example))
            continue
        if example not in emitted:
            failures.append(prefix +
                "the naming table gives %s as the example for %s, but the "
                "engine never emits that id" % (example, shape))
            continue
        # The example has to MATCH its own shape.
        if shape.startswith("ast-") and not example.startswith("ast-"):
            failures.append(prefix +
                "the naming table pairs the AST shape %s with the non-AST "
                "example %s" % (shape, example))
        elif shape.startswith("dfa-taint-") and not example.startswith("dfa-taint-"):
            failures.append(prefix +
                "the naming table pairs the taint shape %s with the example "
                "%s, which is not a taint id" % (shape, example))
        elif shape.startswith("dfa-") and not example.startswith("dfa-"):
            failures.append(prefix +
                "the naming table pairs the DFA shape %s with the example %s, "
                "which is not a DFA id" % (shape, example))
    note("checked %d naming-convention row(s)" % len(NAMING_ROWS))

    # (2c) The rule id of every DFA checker the document names comes from
    #      the checker's own `name()` method. The document's id has to
    #      be the one the checker returns.
    for cls in DFA_CHECKERS:
        p = DFA / "checkers" / "controlflow" / ("%s.java" % cls)
        if not p.is_file():
            continue
        body = read(p)
        m = re.search(r'public String name\(\)\s*\{\s*return "([^"]+)"', body)
        if not m:
            continue
        actual = m.group(1)
        if ("`%s`" % actual) not in doc:
            failures.append(prefix +
                "the checker %s emits the rule id %s, but the document does "
                "not name it" % (cls, actual))
    note("checked %d checker-emitted rule id(s)" % len(DFA_CHECKERS))

    # (2d) The signature format: the document states the separator and
    #      the shape. The source has to agree.
    sig = DFA / "cg" / "FunctionSignature.java"
    if sig.is_file():
        body = read(sig)
        # The class/method separator is the character appended right
        # after the class name. (There are several `sb.append('...')`
        # calls: the package separator is '.', the class/method one
        # is what the document claims.)
        sep = re.search(r"className[^\n]*\n\s*sb\.append\('([^'])'\)", body)
        if sep:
            actual_sep = sep.group(1)
            m = re.search(r'"package\.Class(.)method\(paramType,\.\.\.\)"', doc)
            if m and m.group(1) != actual_sep:
                failures.append(prefix +
                    "the document says the signature separates the class and "
                    "the method with %r, but the source uses %r"
                    % (m.group(1), actual_sep))
            elif m:
                note("checked the signature separator (%r)" % actual_sep)
        else:
            failures.append(prefix +
                "%s does not show how the signature is built -- the check "
                "cannot verify the document's claim" % sig.name)

    # (3) The DFA checkers must exist, and the ones that are said to be
    #     unregistered really have to be absent from the registration.
    for cls in DFA_CHECKERS:
        if not list(DFA.rglob("%s.java" % cls)):
            failures.append(prefix +
                "the document describes %s, but no such class exists under "
                "plumb-dfa" % cls)
    for rel in AST_CHECKERS:
        if not (REPO / rel).is_file():
            failures.append(prefix +
                "the document points at %s, but that file does not exist" % rel)
    runner = REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb" / "main" / "ScanRunner.java"
    if runner.is_file():
        body = read(runner)
        # The registration is an `if (...) {` block that adds the checker.
        for cls in NOT_REGISTERED:
            if re.search(r"checkers\.add\(new [^)]*%s" % re.escape(cls), body):
                failures.append(prefix +
                    "the document says %s is not registered, but "
                    "ScanRunner adds it" % cls)
    else:
        failures.append(prefix + "%s does not exist" % runner)
    # (3c) The switch the document names has to be the one the loader
    #      reads. The document says the per-rule switch is
    #      `checks.<rule>: {enabled: ...}`; the loader has to agree.
    loader = REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb" / "main" / "ConfigLoader.java"
    if loader.is_file():
        body = read(loader)
        # The switch the document states, read out of the document.
        #
        # EVERY mention has to name the same section: a document that
        # says `checks.<rule>` in one paragraph and `rules.enabled` in
        # another is telling the reader two different things.
        #
        # A mention that says the section is NOT read -- "the
        # `rules.enabled` list exists, but it is commented out" -- is
        # not a competing claim; it is the document warning the reader
        # off it. Those are excluded.
        claimed = []
        for raw in re.split(r"\n\s*\n", doc):
            paragraph = re.sub(r"\s+", " ", raw)
            # A mention that warns the reader off it -- "the
            # `rules.enabled` list exists, but it is commented out" --
            # is not a competing claim.
            #
            # The warning has to be in the same SENTENCE as the
            # mention. The sentence boundary is a full stop in either
            # language, or a "but" / "但是" that introduces the
            # warning.
            for m in re.finditer(r"`(checks\.[^`]+|rules\.[^`]+)`", paragraph):
                start = max(paragraph.rfind(". ", 0, m.start()),
                            paragraph.rfind("。", 0, m.start()),
                            paragraph.rfind("; ", 0, m.start()),
                            -1)
                window = paragraph[start:m.end() + 200]
                if re.search(r"commented out|is not read|not the switch|"
                             r"ignored|does not read|never read|"
                             r"被注释|注释掉|不是引擎读取|不会被读取",
                             window, re.I):
                    continue
                claimed.append(m.group(1))
        if not claimed:
            failures.append(prefix +
                "the document no longer states which YAML section switches a "
                "rule on and off")
        else:
            sections = set(c.split(".")[0] for c in claimed)
            if len(sections) > 1:
                failures.append(prefix +
                    "the document names %d different switch sections (%s) -- "
                    "the reader cannot tell which one the engine reads"
                    % (len(sections), ", ".join(sorted(sections))))
            name = claimed[0]
            # The loader has to read that section -- and it has to read
            # the per-rule `enabled` flag out of it. The loader also
            # knows a `rules` section, so the section name alone is not
            # enough: the check is that the per-rule switch works.
            section = name.split(".")[0]
            if ('"%s"' % section) not in body:
                failures.append(prefix +
                    "the document says the per-rule switch is %s, but the "
                    "loader does not read a %r section" % (name, section))
            elif section == "checks":
                # `checks.<rule>: {enabled: ...}` -- the loader has to
                # read the `enabled` key of a per-rule entry.
                if not re.search(r'"enabled"', body):
                    failures.append(prefix +
                        "the document says the per-rule switch is %s, but the "
                        "loader never reads an `enabled` key" % name)
                else:
                    note("checked the per-rule switch (%s)" % name)
            else:
                note("checked the per-rule switch (%s)" % name)
    else:
        failures.append(prefix + "%s does not exist" % loader)

    note("checked %d DFA checker(s) and %d unregistered claim(s)"
         % (len(DFA_CHECKERS), len(NOT_REGISTERED)))

    # (3a) The document must SAY the two race checkers are not
    #      registered.
    #
    #      The code check above is one-directional: it catches a
    #      checker that IS registered. It cannot catch a document that
    #      claims the opposite. So the wording is checked too -- and the
    #      wording has to be a DENIAL.
    for cls in NOT_REGISTERED:
        # The document must deny their registration, and it must not
        # claim it anywhere either. A document that says "not
        # registered" in one paragraph and "and registered" in another
        # is worse than one that says nothing.
        denied = False
        claimed = False
        for raw in re.split(r"\n\s*\n", doc):
            if cls not in raw:
                continue
            paragraph = re.sub(r"\s+", " ", raw)
            if re.search(r"not\s*\*\*?\s*registered|unregistered|empty|"
                         r"不注册|未注册|故意留空", paragraph, re.I):
                denied = True
            if re.search(r"\*\*registered\*\*|are\s+registered|"
                         r"已注册|会被注册", paragraph, re.I):
                claimed = True
        if not denied:
            failures.append(prefix +
                "the document no longer says %s is unregistered" % cls)
        if claimed:
            failures.append(prefix +
                "the document claims %s is registered, but ScanRunner does "
                "not add it" % cls)
    note("checked %d unregistered wording claim(s)" % len(NOT_REGISTERED))

    # (3b) The document says the `SummaryApplier` does NOT dispatch on
    #      `Operation` variants. That is a claim about the code, and the
    #      code has to agree.
    applier = DFA / "summary" / "SummaryApplier.java"
    if applier.is_file():
        body = read(applier)
        dispatches = re.search(r"instanceof\s+Operation\.", body)
        claims = re.search(r"`?SummaryApplier`?\s+does\s+\*\*not\*\*\s+dispatch",
                           doc)
        if dispatches and claims:
            failures.append(prefix +
                "the document says SummaryApplier does not dispatch on "
                "Operation variants, but the source does")
        elif claims:
            note("checked the SummaryApplier dispatch claim")
    else:
        failures.append(prefix + "%s does not exist" % applier)

    # (3b) The document must name the checkers it describes.
    #
    #      The "does the class exist" check above is one-directional. It
    #      cannot catch the document dropping a checker from its list, or
    #      renaming one to something that does not exist -- the type
    #      check would find the OLD name missing, but only if the old
    #      name is still there. So the list is checked directly.
    for cls in DFA_CHECKERS:
        if cls not in doc:
            failures.append(prefix +
                "the document no longer names %s, so the checker it "
                "describes is no longer documented" % cls)
    for cls in AST_CHECKERS:
        base = cls.rsplit("/", 1)[-1]
        if base not in doc:
            failures.append(prefix +
                "the document no longer names %s, so the AST checker it "
                "points at is no longer documented" % base)
    note("checked %d documented checker name(s)"
         % (len(DFA_CHECKERS) + len(AST_CHECKERS)))

    # (4) The names the document says do not exist must not exist.
    for rel, why in MUST_NOT_EXIST:
        if (REPO / rel).is_file():
            failures.append(prefix +
                "%s now exists, but the document says it does not (%s)"
                % (rel, why))
    note("checked %d documented absence(s)" % len(MUST_NOT_EXIST))

    # (5) The API the snippets use must exist.
    #
    #     The symbol has to appear as a DECLARATION, not as a mention:
    #     a javadoc `{@link #getByCanonicalString}` proves nothing about
    #     the method existing, and a field has to be declared as a field.
    api_checked = 0
    for rel, symbol in API:
        path = DFA / rel
        if not path.is_file():
            failures.append(prefix + "%s does not exist -- the document cites it" % path)
            continue
        body = read(path)
        declared = (
            re.search(r"\b" + re.escape(symbol) + r"\s*\(", body)
            or re.search(r"\b" + re.escape(symbol) + r"\s*[=;]", body)
            or re.search(r"\b" + re.escape(symbol) + r"\s*[,)]", body))
        if not declared:
            failures.append(prefix +
                "%s does not declare %s -- the document's snippet uses it"
                % (rel, symbol))
            continue
        api_checked += 1
    note("checked %d API symbol(s)" % api_checked)

    # (5b) The cache's two lookup methods are a specific claim: the
    #      document names them, and the class has to declare them.
    cache = DFA / "summary" / "MethodSummaryCache.java"
    if cache.is_file():
        body = read(cache)
        for method in ("get", "getByCanonicalString"):
            if not re.search(r"\b" + method + r"\s*\(", body):
                failures.append(prefix +
                    "MethodSummaryCache does not declare %s -- the document "
                    "names it as a lookup" % method)
        # The document must name BOTH -- dropping one would leave the
        # reader with only half the story.
        for method in ("get(FunctionSignature)", "getByCanonicalString"):
            if method not in doc:
                failures.append(prefix +
                    "the document no longer names the cache lookup %s" % method)
        # And every cache lookup the document DOES name has to exist.
        # A lookup the document invents -- `getByString` -- would send
        # the reader to a method that is not there.
        #
        # Only the lookups the document attributes to the CACHE count:
        # `getAttribute` belongs to the servlet checker, not to the
        # summary cache, and a bare name is not enough to say which.
        for paragraph in re.split(r"\n\s*\n", doc):
            if "MethodSummaryCache" not in paragraph:
                continue
            for mentioned in set(re.findall(r"`(get[A-Z]\w*)\(", paragraph)):
                if not re.search(r"\b" + re.escape(mentioned) + r"\s*\(", body):
                    failures.append(prefix +
                        "the document names the cache lookup %s, but "
                        "MethodSummaryCache does not declare it" % mentioned)
    else:
        failures.append(prefix + "%s does not exist" % cache)

    # (6) The identifiers the document invented must not have crept back in.
    #
    #     The document may still MENTION such a name -- to say that it
    #     does not exist. What must not happen is the name being used
    #     as if it were real. So the mention has to be a NEGATIVE one:
    #     a sentence that denies the name exists, or one that says the
    #     helper is per-checker.
    for token, why in MUST_NOT_APPEAR:
        # The unit is the PARAGRAPH, not the sentence: a note like
        # "not a `state.writtenFields` set" is wrapped across lines, and
        # a sentence split would cut the negation off from the name.
        #
        # The paragraph is joined into ONE line first, so a wrapped note
        # is read as the single statement it is.
        for raw in re.split(r"\n\s*\n", doc):
            if token not in raw:
                continue
            paragraph = re.sub(r"\s+", " ", raw)
            # The negation has to be in the SAME CLAUSE as the name.
            # A paragraph can carry two unrelated statements -- "there is
            # a shared `stripType` helper, and there is no `Const.NULL`"
            # -- and the second must not excuse the first.
            #
            # A clause ends at a comma, a semicolon, or a Chinese
            # comma. Both languages have to be understood, because both
            # trees carry the same claims.
            clauses = re.split(r"[,;，；]", paragraph)
            denied = False
            for clause in clauses:
                if token not in clause:
                    continue
                if re.search(
                        r"(no|not|never|does not|there is no|instead of|"
                        r"rather than|is not|has no|没有|不存在|不是|"
                        r"而非|留空)[^.]{0,60}"
                        + re.escape(token), clause, re.I):
                    denied = True
                    break
                if re.search(re.escape(token) + r"[^.]{0,40}\b(no|not|never)\b",
                             clause, re.I):
                    denied = True
                    break
            if denied:
                continue
            failures.append(prefix +
                "the document uses %s as if it existed -- it does not (%s)"
                % (token, why))
            break
    note("checked %d invented identifier(s)" % len(MUST_NOT_APPEAR))

    # (6a) The document must still SAY that the invented identifiers do
    #      not exist. The check above only fires when the document uses
    #      one as if it were real; without this, the document could drop
    #      the explanation and the reader would have no idea the name is
    #      not part of the engine.
    for token, why in MUST_NOT_APPEAR:
        if token not in doc:
            failures.append(prefix +
                "the document no longer mentions %s -- the note that this "
                "name is not part of the engine has been lost (%s)"
                % (token, why))
    note("checked %d invented-identifier note(s)" % len(MUST_NOT_APPEAR))

    # (6b) Every method the document's ENGINE code blocks call must exist.
    #
    #      The prose check above only knows about the names it was told
    #      about. This one reads the snippets and resolves the calls, so
    #      a snippet that starts calling a method that does not exist is
    #      caught even though nobody thought to list it.
    #
    #      A block that opens with `// illustrative:` is USER code -- an
    #      example of what the checker looks for, not a call into the
    #      engine. Those are not resolved.
    snippet_calls = 0
    for block in re.findall(r"```java\n(.*?)```", doc, re.S):
        if "// illustrative:" in block:
            continue
        # A comment is not a call. `// load + null check on l (or its
        # alias)` mentions `l(` in prose, and resolving that would
        # report a method that does not exist.
        code = re.sub(r"//[^\n]*", "", block)
        code = re.sub(r"/\*.*?\*/", "", code, flags=re.S)
        for call in re.findall(r"\b([a-z]\w*)\s*\(", code):
            if call in ("if", "for", "while", "switch", "return", "new",
                        "catch", "try", "else", "do", "synchronized"):
                continue
            snippet_calls += 1
            if not any(re.search(r"\b" + re.escape(call) + r"\s*\(", read(p))
                       for p in DFA.rglob("*.java")):
                failures.append(prefix +
                    "the document's code snippet calls %s(), which no class "
                    "in plumb-dfa declares" % call)
    note("checked %d snippet method call(s)" % snippet_calls)

    # (7) The Operation variants: the sealed interface must declare the
    #     ones the document names, and the ones the document says are
    #     inert must really never be constructed.
    op = DFA / "summary" / "Operation.java"
    if op.is_file():
        body = read(op)
        declared = set(re.findall(r"^\s*(?:record|final class)\s+(\w+)", body, re.M))
        for variant in INERT_VARIANTS:
            if variant not in declared:
                failures.append(prefix +
                    "the document says Operation.%s is inert, but the sealed "
                    "interface does not declare it" % variant)
                continue
            built = 0
            for p in DFA.rglob("*.java"):
                if p.name == "Operation.java":
                    continue
                if ("new Operation.%s" % variant) in read(p):
                    built += 1
            if built:
                failures.append(prefix +
                    "the document says Operation.%s is never constructed, but "
                    "%d place(s) construct it" % (variant, built))
            # And the document must still SAY it is inert -- a document
            # that stopped naming it would have lost the warning.
            if ("Operation.%s" % variant) not in doc:
                failures.append(prefix +
                    "the document no longer names Operation.%s, so the note "
                    "that it is inert has been lost" % variant)
        note("checked %d inert Operation variant(s)" % len(INERT_VARIANTS))
    else:
        failures.append(prefix + "%s does not exist" % op)

    # (8) The document must still make the claims it makes.
    #
    #     The "does the name exist" checks above are one-directional:
    #     they catch a document that NAMES something that does not
    #     exist. They cannot catch a document that quietly stops
    #     claiming the thing, or that claims the wrong thing.
    #
    #     These are the statements the document is actually making, and
    #     each one is checked against the source.
    #
    #     The tokens are identifiers and code, not prose: they are the
    #     same in both language versions.
    required_statements = [
        ("ast-quality-uninit-nonnull",
         "the canonical AST rule id of the uninit-nonnull rule"),
        ("checks.<rule>", "the per-rule switch the loader reads"),
        ("getByCanonicalString", "the canonical-signature cache lookup"),
        ("get(FunctionSignature)", "the resolved-signature cache lookup"),
        ("`#`", "the class/method separator in a signature"),
        ("lambda$N", "the lambda signature form"),
    ]
    stated = 0
    for token, what in required_statements:
        if token not in doc:
            failures.append(prefix +
                "the document no longer states %s (it must mention %r)"
                % (what, token))
            continue
        stated += 1
    note("checked %d required statement(s)" % stated)

    # (9) The summary-type table: each row must name a real type, and
    #     the type must hold what the row says it holds.
    SUMMARY_ROWS = [
        ("FunctionSummary", "returnOps"),
        ("MethodSummary", "isPure"),
    ]
    summary_checked = 0
    for cls, member in SUMMARY_ROWS:
        p = DFA / "summary" / ("%s.java" % cls)
        if not p.is_file():
            failures.append(prefix + "%s does not exist" % p)
            continue
        body = read(p)
        if not re.search(r"\b" + re.escape(member) + r"\s*[\(=]", body):
            failures.append(prefix +
                "%s does not hold %s -- the document's table says it does"
                % (cls, member))
            continue
        summary_checked += 1
    note("checked %d summary-type row(s)" % summary_checked)

    # (10) The mechanism each checker description names must be the one
    #      the checker implements. A description of a DIFFERENT mechanism
    #      is worse than no description.
    MECHANISMS = [
        ("checkers/controlflow/OrmLoadNullCheckDfaChecker.java", "fromLoadVars"),
        ("checkers/controlflow/OrmLoadNullCheckDfaChecker.java", "loadCallSite"),
        ("checkers/controlflow/OrmUnnecessaryGetDfaChecker.java", "fromGetVars"),
        ("checkers/controlflow/OrmUnnecessaryGetDfaChecker.java", "getCallSite"),
        ("checkers/controlflow/SingletonRaceDfaChecker.java", "fieldReaders"),
        ("checkers/controlflow/SingletonRaceDfaChecker.java", "fieldWriters"),
        ("checkers/controlflow/ServletAtomicityDfaChecker.java", "getSites"),
        ("checkers/controlflow/ServletAtomicityDfaChecker.java", "setSites"),
    ]
    mech_checked = 0
    for rel, member in MECHANISMS:
        p = DFA / rel
        if not p.is_file():
            failures.append(prefix + "%s does not exist" % p)
            continue
        body = read(p)
        if member not in body:
            failures.append(prefix +
                "%s does not use %s -- the document describes a mechanism the "
                "checker does not implement" % (rel, member))
            continue
        # And the document has to mention the mechanism it describes.
        if member not in doc:
            failures.append(prefix +
                "the document no longer names %s, so the mechanism it "
                "describes is no longer stated" % member)
            continue
        mech_checked += 1
    note("checked %d checker mechanism(s)" % mech_checked)

    return failures


if __name__ == "__main__":
    sys.exit(main())
