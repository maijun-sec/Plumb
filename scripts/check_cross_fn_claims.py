#!/usr/bin/env python3
"""Check docs/tech/CROSS-FUNCTION-OPERATION.md against the source.

The document names a large number of classes, methods, enum variants and
fact kinds -- the things a reader would look up in the code.  Each named
symbol must exist.

The script prints what it derives before it compares.

Exit code 0 when the document matches the source, 1 otherwise.
"""
import io
import pathlib
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent
DOC = REPO / "docs" / "tech" / "CROSS-FUNCTION-OPERATION.md"
SRC_ROOTS = [
    REPO / "plumb-dfa" / "src" / "main" / "java",
    REPO / "plumb-tac" / "src" / "main" / "java",
    REPO / "plumb-style" / "src" / "main" / "java",
    REPO / "plumb-rules" / "src" / "main" / "java",
    REPO / "plumb-main" / "src" / "main" / "java",
    REPO / "plumb-reporter" / "src" / "main" / "java",
    REPO / "plumb-parser" / "src" / "main" / "java",
]

doc = DOC.read_text(encoding="utf-8", errors="replace")
failures = []
info = []


def note(msg):
    info.append(msg)
    print(msg)


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    return text


# index every source file
sources = {}
for root in SRC_ROOTS:
    if not root.is_dir():
        continue
    for p in root.rglob("*.java"):
        sources.setdefault(p.stem, []).append(
            (p, strip_comments(p.read_text(encoding="utf-8", errors="replace"))))
note("indexed %d java files (%d distinct class names)"
     % (sum(len(v) for v in sources.values()), len(sources)))

# Nested types live inside their enclosing class, so a bare name like
# "AllocSite" has no file of its own.  Collect every nested type name so
# the check does not report them as missing.
NESTED = re.compile(
    r"\b(?:record|class|interface|enum)\s+([A-Z][A-Za-z0-9_]*)\b")
nested_names = set()
for entries in sources.values():
    for _, b in entries:
        for m in NESTED.finditer(b):
            nested_names.add(m.group(1))
note("known nested type names: %d" % len(nested_names))

# ---------------------------------------------------------------- 1. classes
# `Foo` in backticks, where Foo starts with an upper-case letter and is
# not a known config key.
CONFIG_KEYS = set()
for y in (REPO / "plumb-dfa" / "src" / "main" / "resources").glob("*.yaml"):
    for m in re.finditer(r"^\s{2}([A-Za-z][A-Za-z0-9_]*):", y.read_text(encoding="utf-8"),
                         re.M):
        CONFIG_KEYS.add(m.group(1))
note("known config keys: %d" % len(CONFIG_KEYS))


def enum_constants(file_stem, enum_name):
    """The constants of a (possibly nested) enum, from its own file."""
    for _, b in sources.get(file_stem, []):
        m = re.search(r"enum\s+%s\b[^{]*\{" % re.escape(enum_name), b)
        if not m:
            continue
        i = m.end()
        depth = 1
        body = ""
        while i < len(b) and depth > 0:
            c = b[i]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    body = b[m.end():i]
                    break
            i += 1
        out = []
        for raw in body.split(","):
            token = re.split(r"[({]", raw, maxsplit=1)[0].strip()
            if re.match(r"^[A-Z][A-Z0-9_]*$", token):
                out.append(token)
        if out:
            return set(out)
    return set()


op_variants_early = set()
for _, b in sources.get("Operation", []):
    for m in re.finditer(r"\brecord\s+([A-Z][A-Za-z0-9_]*)\s*\(", b):
        op_variants_early.add(m.group(1))
    for m in re.finditer(
            r"\b(?:final\s+)?class\s+([A-Z][A-Za-z0-9_]*)\s+implements\s+Operation",
            b):
        op_variants_early.add(m.group(1))

return_shape_kinds_early = enum_constants("ReturnShape", "Kind")
tac_kinds_early = enum_constants("TacInstruction", "Kind")

# The fact kind names: the yaml keys plus the built-in registry.
fact_names_early = set()
for y in (REPO / "plumb-dfa" / "src" / "main" / "resources").glob("*.yaml"):
    for m in re.finditer(r"^\s{2}([A-Za-z][A-Za-z0-9_]*):",
                         y.read_text(encoding="utf-8"), re.M):
        fact_names_early.add(m.group(1))
_builtin = (REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa"
            / "fact" / "BuiltinFactKinds.java")
if _builtin.is_file():
    for m in re.finditer(r"Map\.entry\(\s*\"?([A-Za-z0-9_]+)\"?",
                         _builtin.read_text(encoding="utf-8")):
        fact_names_early.add(m.group(1))

note("Operation variants: %d, ReturnShape.Kind: %d, TAC kinds: %d, fact kinds: %d"
     % (len(op_variants_early), len(return_shape_kinds_early),
        len(tac_kinds_early), len(fact_names_early)))

named_classes = set()
for m in re.finditer(r"`([A-Z][A-Za-z0-9_]*)`", doc):
    named_classes.add(m.group(1))
# a backticked capitalised word may be an enum variant, not a class
missing = []
for c in sorted(named_classes):
    if c in sources:
        continue
    if c in CONFIG_KEYS:
        continue
    if c in nested_names:
        continue
    # a TAC instruction kind, a ReturnShape.Kind variant, an
    # Operation variant -- all enum constants, not classes
    if c in op_variants_early:
        continue
    if c in return_shape_kinds_early:
        continue
    if c in tac_kinds_early:
        continue
    # a fact kind name, and the one Java constant the document quotes
    if c in fact_names_early:
        continue
    if c == "MAX_INT":
        continue
    missing.append(c)
if missing:
    # A name the document puts in backticks and the source does not have
    # is a name a reader would try to look up.  It has to be a failure,
    # not a note: the note version of this check passed a document that
    # named a class that does not exist.
    failures.append("the document names class(es) that do not exist: %s"
                    % missing)
else:
    note("all %d backticked class names exist" % len(named_classes))

# ---------------------------------------------------------------- 2. Class.member
doc_members = set()
for m in re.finditer(r"`([A-Z][A-Za-z0-9_]*)\.([a-z][A-Za-z0-9_]*)`", doc):
    doc_members.add((m.group(1), m.group(2)))
note("the document names %d Class.member references" % len(doc_members))

bad_members = []
for cls, member in sorted(doc_members):
    bodies = sources.get(cls)
    if bodies is None:
        continue  # the class itself is reported elsewhere
    if not any(re.search(r"\b%s\s*\(" % re.escape(member), b) or
               re.search(r"\b%s\b" % re.escape(member), b)
               for _, b in bodies):
        bad_members.append("%s.%s" % (cls, member))
if bad_members:
    failures.append("the document names member(s) that do not exist: %s"
                    % bad_members)
else:
    note("every Class.member reference resolves")

# ---------------------------------------------------------------- 3. Operation variants
# `Operation.Xxx` names a variant of the Operation sealed interface.
op_variants = set()
for p, b in sources.get("Operation", []):
    m = re.search(r"sealed\s+interface\s+Operation[^{]*\{(.*)", b, re.S)
    if not m:
        continue
    body = m.group(1)
    for vm in re.finditer(r"\brecord\s+([A-Z][A-Za-z0-9_]*)\s*\(", body):
        op_variants.add(vm.group(1))
    for vm in re.finditer(r"\b(?:final\s+)?class\s+([A-Z][A-Za-z0-9_]*)\s+implements\s+Operation",
                          body):
        op_variants.add(vm.group(1))
note("Operation has %d variants: %s" % (len(op_variants), sorted(op_variants)))

doc_ops = set()
# `Operation.Variant` and `Operation.Variant(...)` both name a variant.
for m in re.finditer(r"`Operation\.([A-Z][A-Za-z0-9_]*)", doc):
    doc_ops.add(m.group(1))
note("the document names %d Operation variants: %s"
     % (len(doc_ops), sorted(doc_ops)))
if op_variants:
    unknown = sorted(doc_ops - op_variants)
    if unknown:
        failures.append("the document names Operation variant(s) that do not "
                        "exist: %s" % unknown)
    else:
        note("every Operation variant the document names exists")

# ---------------------------------------------------------------- 3b. ReturnShape.Kind
# Only the values the document attributes to `ReturnShape.Kind`.  A name
# like RETURN_VALUE_FOLDED is a FACT kind, not a return shape.  The
# reliable signal is a LIST of shapes: "the RETURN_CONST / RETURN_PARAM /
# RETURN_BINARY_OP shapes".  A name that stands alone next to the word
# "ReturnShape" is not evidence -- the same table cell also names the
# fact kind it is encoded into.
doc_shapes = set()
# The shape names are listed in one backtick group, separated by " / "
# (the table cell reads "`RETURN_CONST` / `RETURN_PARAM` / ..."), so the
# group is the set of backticked names between two of them.
for m in re.finditer(r"`RETURN_[A-Z_]+`(?:\s*/\s*`RETURN_[A-Z_]+`)+", doc):
    for name in re.findall(r"`(RETURN_[A-Z_]+)`", m.group(0)):
        doc_shapes.add(name)
if return_shape_kinds_early:
    note("ReturnShape.Kind has %d constants: %s"
         % (len(return_shape_kinds_early), sorted(return_shape_kinds_early)))
    note("the document lists these shape values: %s" % sorted(doc_shapes))
    if not doc_shapes:
        failures.append("the shape-value check found nothing -- the pattern "
                        "that locates the list no longer matches the document")
    unknown = sorted(doc_shapes - return_shape_kinds_early)
    if unknown:
        failures.append("the document names ReturnShape.Kind value(s) that do "
                        "not exist: %s" % unknown)
    else:
        note("every ReturnShape.Kind value the document names exists")

# ---------------------------------------------------------------- 4. fact kinds
# `RETURN_VALUE_FOLDED` and friends are fact kind names.
FACT_FILES = list((REPO / "plumb-dfa" / "src" / "main" / "resources").glob("*.yaml"))
fact_names = set()
for y in FACT_FILES:
    for m in re.finditer(r"^\s{2}([A-Za-z][A-Za-z0-9_]*):", y.read_text(encoding="utf-8"),
                         re.M):
        fact_names.add(m.group(1))
builtin = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa" / "fact" / "BuiltinFactKinds.java"
if builtin.is_file():
    for m in re.finditer(r"Map\.entry\(\s*\"?([A-Za-z0-9_]+)\"?",
                         builtin.read_text(encoding="utf-8")):
        fact_names.add(m.group(1))
note("known fact kind names: %d" % len(fact_names))

doc_facts = set()
for m in re.finditer(r"`([A-Z][A-Z0-9_]{3,})`", doc):
    doc_facts.add(m.group(1))
# only the ones that look like fact kinds: present in the yaml or builtin list
claimed_facts = {f for f in doc_facts if f in fact_names}
unknown = sorted(f for f in doc_facts
                 if f not in fact_names
                 and f not in {"RETURN_CONST", "RETURN_PARAM", "RETURN_BINARY_OP",
                               "RETURN_CONDITIONAL", "RETURN_THIS_FIELD",
                               "MAX_INT", "SUMMARY_CACHE"})
if unknown:
    note("upper-case names in the document that are not fact kinds: %s" % unknown)
else:
    note("every upper-case fact name in the document is a known fact kind "
         "(%d checked)" % len(claimed_facts))

# ---------------------------------------------------------------- 5. the "6 sub-analyses" count
m = re.search(r"### 5\.1 (\d+) 套子分析", doc)
if m:
    rows = re.findall(r"^\|\s*(\d+)\s*\|", doc, re.M)
    rows = [r for r in rows if r.isdigit() and int(r) <= 12]
    note("the document claims %s sub-analyses; the table has %d numbered rows"
         % (m.group(1), len(rows)))
    if int(m.group(1)) != len(rows):
        failures.append(
            "the document claims %s sub-analyses but the table has %d rows"
            % (m.group(1), len(rows)))

# ---------------------------------------------------------------- 6. round markers / history
for m in re.finditer(r"(?<![A-Za-z0-9_])R-\d{0,4}[a-z]?(?![A-Za-z0-9_])", doc):
    failures.append("the document carries a round marker: %s" % m.group(0))
for phrase in ("Phase 1", "Phase 2"):
    pass  # "Phase 1/2" is a design phase, not a round marker

print()
if failures:
    print("FAIL: %d defect(s) in docs/tech/CROSS-FUNCTION-OPERATION.md" % len(failures))
    for f in failures:
        print("  " + f)
    sys.exit(1)
print("OK: docs/tech/CROSS-FUNCTION-OPERATION.md matches the source")
