#!/usr/bin/env python3
"""Check docs/tech/TAC.md against the source.

Every claim checked here is a STRUCTURAL claim: a class name, a field
name, an enum constant list, a count.  These are the claims a reader
would use to write code against the IR, so they must be true.

The script prints what it DERIVES before it compares, so a mismatch can
be diagnosed (is the document wrong, or is the measurement wrong?).

Exit code 0 when the document matches the source, 1 otherwise.
"""
import io
import pathlib
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent
TAC_SRC = REPO / "plumb-tac" / "src" / "main" / "java" / "org" / "plumb" / "tac"
DOC = REPO / "docs" / "tech" / "TAC.md"

doc = DOC.read_text(encoding="utf-8", errors="replace")
failures = []
info = []


def note(msg):
    info.append(msg)
    print(msg)


def strip_comments(text):
    """Remove block and line comments, so an enum scan cannot pick up
    words that only appear in javadoc."""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    return text


def enum_body(text, enum_name):
    """The body of a (possibly nested) enum, comments removed.

    An enum body ends either at a closing brace or at a top-level
    semicolon (the `CONST;` form, which is what the operator enums use).
    """
    text = strip_comments(text)
    # The enum name may be followed by an `implements`/`extends` clause
    # before the brace: `public enum BinaryOperator implements
    # java.io.Serializable {`.
    start = re.search(r"enum\s+%s\b[^{]*\{" % re.escape(enum_name), text)
    if not start:
        return None
    i = start.end()
    depth = 1
    while i < len(text) and depth > 0:
        c = text[i]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return text[start.end():i]
        i += 1
    return text[start.end():i]


def enum_consts(text, enum_name):
    """The constants of an enum, in declaration order.

    A constant is a bare identifier on its own, optionally followed by
    a body.  This deliberately does NOT scan for ALL_CAPS words: that
    picks up words from javadoc and from the enum's own type names.

    The body of a `CONST;`-style enum continues after the semicolon
    (the members follow), so only the part BEFORE the first top-level
    semicolon holds the constants.
    """
    body = enum_body(text, enum_name)
    if body is None:
        return None
    # cut at the first top-level semicolon: that ends the constant list
    depth = 0
    cut = len(body)
    for i, c in enumerate(body):
        if c in "({":
            depth += 1
        elif c in ")}":
            depth -= 1
        elif c == ";" and depth == 0:
            cut = i
            break
    body = body[:cut]
    out = []
    for raw in body.split(","):
        # cut at the first '(' or '{' -- a constant with a body
        token = re.split(r"[({]", raw, maxsplit=1)[0].strip()
        if not token:
            continue
        if not re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", token):
            continue
        if token in ("enum", "public", "private", "static", "final"):
            continue
        out.append(token)
    return out


def java_files(root):
    return sorted(p for p in root.rglob("*.java"))


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


# ---------------------------------------------------------------- 1. IR classes
# The document says the IR classes are 10, and names them.
claimed_ir = re.search(r"等 (\d+) 个", doc)
ir_classes_claimed = int(claimed_ir.group(1)) if claimed_ir else None

# The classes the document names as the serialisable IR.
named = re.search(
    r"IR 类（(.+?)）全部 `implements Serializable`", doc)
named_classes = []
if named:
    named_classes = re.findall(r"`([A-Za-z0-9_]+)`", named.group(1))
note("document names %d IR classes: %s" % (len(named_classes), named_classes))

# Derive the real set: classes under the tac package tree whose name starts
# with Tac, plus MergedProgram and SourceMap, that implement Serializable.
derived = []
for p in java_files(TAC_SRC):
    # Comments must be stripped: several classes carry a comment that
    # MENTIONS "Serializable" while not implementing it.
    body = strip_comments(read(p))
    name = p.stem
    if not re.search(r"\b(?:implements|extends)\b[^{]*\bSerializable\b", body):
        continue
    if name.startswith("Tac") or name in ("MergedProgram", "SourceMap"):
        derived.append(name)
derived_set = set(derived)
note("source has %d serialisable IR classes: %s"
     % (len(derived_set), sorted(derived_set)))

if ir_classes_claimed is not None and ir_classes_claimed != len(derived_set):
    failures.append("TAC.md says %d IR classes; the source has %d"
                    % (ir_classes_claimed, len(derived_set)))

for c in named_classes:
    if c not in derived_set:
        failures.append("TAC.md names %s as a serialisable IR class; it is not one"
                        % c)

# ---------------------------------------------------------------- 2. instruction kinds
m = re.search(r"`TacInstruction\.Kind` 共 (\d+) 种", doc)
kinds_claimed = int(m.group(1)) if m else None
note("document claims %s instruction kinds" % kinds_claimed)

insn_file = TAC_SRC / "ir" / "TacInstruction.java"
if not insn_file.is_file():
    # find it
    cands = [p for p in java_files(TAC_SRC) if p.stem == "TacInstruction"]
    insn_file = cands[0] if cands else None

if insn_file is not None:
    body = read(insn_file)
    consts = enum_consts(body, "Kind")
    if consts:
        note("source has %d Kind constants: %s" % (len(consts), consts))
        if kinds_claimed is not None and kinds_claimed != len(consts):
            failures.append(
                "TAC.md says TacInstruction.Kind has %d kinds; the source has %d"
                % (kinds_claimed, len(consts)))

        # The instruction table: every ALL_CAPS row between the "共 N 种"
        # line and the next heading is a claimed kind.  The rows are read
        # from the TABLE, not from the known constants -- otherwise a row
        # naming a kind that does not exist is invisible to the check.
        table = []
        in_table = False
        for line in doc.splitlines():
            if re.search(r"`TacInstruction\.Kind`\s*共\s*\d+\s*种", line):
                in_table = True
                continue
            if in_table:
                if line.startswith("#"):
                    break
                rm = re.match(r"^\|\s*`([A-Z][A-Z0-9_]*)`\s*\|", line)
                if rm:
                    table.append(rm.group(1))
        note("document's instruction table lists %d kinds: %s"
             % (len(table), table))
        missing = [c for c in consts if c not in table]
        if missing:
            failures.append(
                "TAC.md's instruction table omits %d kind(s) the source has: %s"
                % (len(missing), missing))
        extra = [t for t in table if t not in consts]
        if extra:
            failures.append(
                "TAC.md's instruction table lists kind(s) the source does not have: %s"
                % extra)

# ---------------------------------------------------------------- 3. operators
def find_enum_consts(cls_name):
    """The constants of a top-level enum with the given name.

    The enum may live in a sub-package (the operators live in `tac/ir`
    next to the instructions that use them), so the whole tree is
    searched, and the file whose stem matches the enum name wins.
    """
    for p in java_files(TAC_SRC):
        if p.stem == cls_name:
            consts = enum_consts(read(p), cls_name)
            if consts is not None:
                return consts
    return None
for enum_name in ("BinaryOperator", "UnaryOperator"):
    real = find_enum_consts(enum_name)
    if real is None:
        failures.append("TAC.md documents %s but the source has no such enum"
                        % enum_name)
        continue
    note("%s has %d constants: %s" % (enum_name, len(real), real))
    # the document's code block for this enum
    m = re.search(r"enum\s+%s\s*\{(.*?)\}" % enum_name, doc, re.S)
    if not m:
        failures.append("TAC.md does not document %s" % enum_name)
        continue
    claimed = [c.strip() for c in m.group(1).replace("\n", " ").split(",")]
    claimed = [c for c in claimed if c and re.match(r"^[A-Z][A-Z_0-9]*$", c)]
    note("TAC.md claims %s has: %s" % (enum_name, claimed))
    if set(claimed) != set(real):
        failures.append(
            "TAC.md's %s differs from the source: document-only=%s source-only=%s"
            % (enum_name, sorted(set(claimed) - set(real)),
               sorted(set(real) - set(claimed))))

# ---------------------------------------------------------------- 4. TacType.Kind
for p in java_files(TAC_SRC):
    if p.stem == "TacType":
        body = read(p)
        kinds = enum_consts(body, "Kind")
        if kinds:
            note("TacType.Kind has %d constants: %s" % (len(kinds), kinds))
            # the document's TacType code block -- scoped to the block that
            # mentions TacType, so the Const.Kind enum is not picked up.
            m = re.search(r"class\s+TacType.*?enum\s+Kind\s*\{(.*?)\n\s{2,}\}",
                          doc, re.S)
            if not m:
                m = re.search(r"class\s+TacType.*?enum\s+Kind\s*\{(.*?)\}", doc, re.S)
            if m:
                claimed = [c.strip() for c in
                           re.findall(r"\b([A-Z][A-Z0-9_]*)\b", m.group(1))]
                claimed = [c for c in claimed if c.isupper() or "_" in c]
                if set(claimed) != set(kinds):
                    failures.append(
                        "TAC.md's TacType.Kind differs: document-only=%s source-only=%s"
                        % (sorted(set(claimed) - set(kinds)),
                           sorted(set(kinds) - set(claimed))))
            else:
                failures.append("TAC.md does not document TacType.Kind")

# ---------------------------------------------------------------- 5. field names
FIELD_CLAIMS = [
    ("TacProgram", "sourcePath"),
    ("TacProgram", "typeName"),
    ("TacProgram", "functions"),
    ("MergedProgram", "sources"),
    ("TacFunction", "name"),
    ("TacFunction", "signature"),
    ("TacFunction", "enclosingClass"),
    ("TacFunction", "returnType"),
    ("TacFunction", "params"),
    ("TacFunction", "locals"),
    ("TacFunction", "instructions"),
    ("TacOperand", "name"),
    ("TacOperand", "type"),
    ("TacOperand", "isTemp"),
    ("TacOperand", "kind"),
    ("TacOperand", "value"),
    ("TacCfg", "functionSig"),
    ("TacCfg", "blocks"),
    ("TacCfg", "successors"),
    ("TacCfg", "exceptionSuccessors"),
    ("TacCfg", "entryId"),
    ("TacBasicBlock", "id"),
    ("TacBasicBlock", "indices"),
]

sources = {}
for p in java_files(TAC_SRC):
    sources.setdefault(p.stem, []).append(read(p))

missing_fields = []
for cls, field in FIELD_CLAIMS:
    bodies = sources.get(cls, [])
    if not bodies:
        failures.append("TAC.md documents class %s, which does not exist" % cls)
        continue
    if not any(re.search(r"\b%s\b" % re.escape(field), b) for b in bodies):
        missing_fields.append("%s.%s" % (cls, field))
if missing_fields:
    failures.append("TAC.md documents field(s) the source does not have: %s"
                    % missing_fields)
else:
    note("all %d documented field names exist in the source"
         % len(FIELD_CLAIMS))

# --- the reverse direction -------------------------------------------------
# The claims above are a fixed list, so they only catch the fields someone
# remembered to write down.  The real risk is the opposite: the document
# shows a field the source DOES NOT have, and a reader writes code against
# it.  So every field the document shows inside a class block is checked
# against that class.
#
# The class blocks in TAC.md look like:
#     public final class TacCfg implements Serializable {
#         private final String functionSig;
#         ...
#     }
DOC_CLASS_FIELDS = re.compile(
    r"(?:class|interface)\s+([A-Z][A-Za-z0-9_]*)[^{]*\{(.*?)\n\s*\}", re.S)
DOC_FIELD = re.compile(
    r"^\s*(?:public|private|protected)\s+(?:static\s+|final\s+|transient\s+)*"
    r"[\w<>,.\[\] ]+?\s+([a-z][A-Za-z0-9_]*)\s*(?:=|;)", re.M)

checked_blocks = 0
for m in DOC_CLASS_FIELDS.finditer(doc):
    cls = m.group(1)
    if cls not in sources:
        continue
    checked_blocks += 1
    body = strip_comments(m.group(2))
    for fm in DOC_FIELD.finditer(body):
        field = fm.group(1)
        if field in ("serialVersionUID",):
            continue
        # The field must be DECLARED in the source class, not merely
        # mentioned: `functionSig` contains the word "function", so a
        # word search would accept a `function` field that does not
        # exist.
        declared = any(
            re.search(r"^\s*(?:public|private|protected)\s+"
                      r"(?:static\s+|final\s+|transient\s+)*"
                      r"[\w<>,.\[\] ]+?\s+%s\s*(?:=|;)" % re.escape(field),
                      strip_comments(b), re.M)
            for b in sources[cls])
        if not declared:
            failures.append(
                "TAC.md shows %s.%s, but the source has no such field"
                % (cls, field))
note("checked the fields shown in %d class blocks" % checked_blocks)

# ---------------------------------------------------------------- 6. method names
# The methods the document names in `Class.method` form must exist.  The
# backtick form is what a reader would copy, so that is what is checked.
DOC_METHOD = re.compile(r"`([A-Z][A-Za-z0-9_]*)\.([a-z][A-Za-z0-9_]*)\(")
seen_methods = set()
for m in DOC_METHOD.finditer(doc):
    cls, method = m.group(1), m.group(2)
    if (cls, method) in seen_methods:
        continue
    seen_methods.add((cls, method))
    bodies = sources.get(cls, [])
    if not bodies:
        # the class may live outside the tac tree (e.g. TacCfgBuilder is
        # in plumb-tac but a referenced class may not be)
        continue
    if not any(re.search(r"\b%s\s*\(" % re.escape(method), b) for b in bodies):
        failures.append("TAC.md documents method %s.%s, which does not exist"
                        % (cls, method))
note("checked %d `Class.method` references" % len(seen_methods))

# ---------------------------------------------------------------- 7. IrSerializer API
ser = sources.get("IrSerializer", [])
if ser:
    for method in ("writeProgram", "readProgram", "writeMerged", "readMerged",
                   "writeObject", "readObject"):
        if not any(re.search(r"\b%s\s*\(" % method, b) for b in ser):
            failures.append("TAC.md documents IrSerializer.%s, which does not exist"
                            % method)
    note("IrSerializer methods checked: writeProgram/readProgram/writeMerged/"
         "readMerged/writeObject/readObject")

# ---------------------------------------------------------------- 8. round markers
for m in re.finditer(r"(?<![A-Za-z0-9_])R-\d{0,4}[a-z]?(?![A-Za-z0-9_])", doc):
    failures.append("TAC.md carries a round marker: %s" % m.group(0))
if "详细背景与 CWE396 调优过程" in doc:
    failures.append("TAC.md has a dangling section lead-in: "
                    "'详细背景与 CWE396 调优过程' with no section after it")

print()
if failures:
    print("FAIL: %d defect(s) in docs/tech/TAC.md" % len(failures))
    for f in failures:
        print("  " + f)
    sys.exit(1)
print("OK: docs/tech/TAC.md matches the source")
