#!/usr/bin/env python3
"""Check docs/tech/STYLE-RULES.md against the source.

The document describes the style-rule architecture: the interface, the
dispatcher, the registry, the categories, the aliases.  Each named
symbol must exist, and each count must be re-derived.

The script prints what it derives before it compares.

Exit code 0 when the document matches the source, 1 otherwise.
"""
import io
import pathlib
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent
DOC = REPO / "docs" / "tech" / "STYLE-RULES.md"
STYLE = REPO / "plumb-style" / "src" / "main" / "java" / "org" / "plumb" / "style"

doc = DOC.read_text(encoding="utf-8", errors="replace")
failures = []
info = []


def note(msg):
    info.append(msg)
    print(msg)


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    text = re.sub(r"//[^\n]*", " ", text)
    return text


sources = {}
for p in STYLE.rglob("*.java"):
    sources.setdefault(p.stem, []).append((p, strip_comments(read(p))))
note("indexed %d style source files" % sum(len(v) for v in sources.values()))

# A class the document names may live in ANOTHER module: Finding is in
# plumb-reporter, ParsedSource and CompilationUnit in plumb-parser.
OTHER_ROOTS = [
    REPO / "plumb-reporter" / "src" / "main" / "java",
    REPO / "plumb-parser" / "src" / "main" / "java",
    REPO / "plumb-rules" / "src" / "main" / "java",
    REPO / "plumb-tac" / "src" / "main" / "java",
]
for root in OTHER_ROOTS:
    if not root.is_dir():
        continue
    for p in root.rglob("*.java"):
        if p.stem not in sources:
            sources[p.stem] = [(p, strip_comments(read(p)))]

# ---------------------------------------------------------------- 1. classes
named = set()
for m in re.finditer(r"`([A-Z][A-Za-z0-9_]*)`", doc):
    named.add(m.group(1))
# a name that is a SUFFIX of a longer identifier in the document (e.g.
# "Check" inside "EmptyCatchCheck") is not a class name
longer = set()
for m in re.finditer(r"`([A-Z][A-Za-z0-9_]*[A-Z][A-Za-z0-9_]*)`", doc):
    longer.add(m.group(1))
missing = []
for c in sorted(named):
    if c in sources:
        continue
    # a config key (a category name, a rule id) is not a class
    if re.fullmatch(r"[a-z][a-z0-9\-]*", c):
        continue
    if c.islower():
        continue
    # a field name, not a class
    if re.fullmatch(r"[A-Z][A-Z0-9_]*", c) and "_" in c:
        continue
    # a suffix of a longer class name in the document
    if any(l.endswith(c) and l != c for l in longer):
        continue
    # an external type: JavaParser's AST
    if c in ("CompilationUnit", "Node", "Type", "ResolvedType", "ResolvedDeclaration",
             "TypeSolver", "ImportDeclaration", "MethodDeclaration"):
        continue
    missing.append(c)
if missing:
    failures.append("the document names class(es) that do not exist: %s" % missing)
else:
    note("all %d backticked class names exist" % len(named))

# ---------------------------------------------------------------- 2. members
members = set()
# `Class.member` and `Class.member(args)` both name a member.
for m in re.finditer(r"`([A-Z][A-Za-z0-9_]*)\.([a-zA-Z][A-Za-z0-9_]*)", doc):
    members.add((m.group(1), m.group(2)))
bad = []
for cls, member in sorted(members):
    bodies = sources.get(cls)
    if bodies is None:
        continue
    if not any(re.search(r"\b%s\b" % re.escape(member), b) for _, b in bodies):
        bad.append("%s.%s" % (cls, member))
if bad:
    failures.append("the document names member(s) that do not exist: %s" % bad)
else:
    note("all %d Class.member references resolve" % len(members))

# ---------------------------------------------------------------- 3. the interface
iface = sources.get("AstChecker")
if iface:
    body = iface[0][1]
    for method in ("name", "init", "finish", "rule", "getAiPrompt", "visit"):
        if not re.search(r"\b%s\s*\(" % method, body):
            failures.append("AstChecker has no %s method" % method)
    n_visits = len(re.findall(r"default\s+void\s+visit\s*\(", body))
    note("AstChecker has %d visit overloads" % n_visits)
    # the document states the number
    m = re.search(r"(\d+) 个 per-node visit 重载", doc)
    if m and int(m.group(1)) != n_visits:
        failures.append("the document says %s visit overloads; the source has %d"
                        % (m.group(1), n_visits))
    m = re.search(r"\*\*(\d+) 个\*\*", doc)
    if m and int(m.group(1)) != n_visits:
        failures.append("the document says %s per-node visit overloads; the "
                        "source has %d" % (m.group(1), n_visits))

# ---------------------------------------------------------------- 3b. StyleContext
# The document's StyleContext table lists methods that must exist.
ctx = sources.get("StyleContext")
if ctx:
    cbody = ctx[0][1]
    # the table starts after the header and runs until a line that is not
    # a table row
    lines = doc.splitlines()
    start = None
    for i, line in enumerate(lines):
        if "主要公开 API" in line:
            start = i
            break
    if start is None:
        failures.append("the document no longer has a StyleContext API table")
    else:
        listed = set()
        in_table = False
        # the header line itself is not a table row; the table starts
        # after it
        for line in lines[start + 1:]:
            if not line.strip():
                if in_table:
                    break
                continue
            if not line.startswith("|"):
                break
            in_table = True
            if set(line) <= set("| -"):
                continue
            listed |= set(re.findall(r"`(\w+)\s*\(", line))
            listed |= set(re.findall(r"`(\w+)\.\.\.`", line))
        note("the document's StyleContext table lists: %s" % sorted(listed))
        if not listed:
            failures.append("the document's StyleContext table lists no "
                            "methods -- the check cannot verify it")
        for method in sorted(listed):
            if not re.search(r"\b%s\s*\(" % re.escape(method), cbody):
                failures.append("the document's StyleContext table lists %s, "
                                "which StyleContext does not declare" % method)

# ---------------------------------------------------------------- 4. the dispatcher
# The document's §4 code block lists the dispatcher's public API.
disp = sources.get("AstDispatcher")
if disp:
    body = disp[0][1]
    m = re.search(r"##\s*4\..*?```java(.*?)```", doc, re.S)
    if m:
        listed = set(re.findall(r"\b(\w+)\s*\(", m.group(1)))
        note("the document's AstDispatcher block lists: %s" % sorted(listed))
        for method in sorted(listed):
            if not re.search(r"\b%s\s*\(" % re.escape(method), body):
                failures.append("the document's AstDispatcher block lists %s, "
                                "which the class does not declare" % method)
    else:
        failures.append("the document no longer has an AstDispatcher code block")

# ---------------------------------------------------------------- 3c. the AstChecker block
# The document's §3 code block lists the interface's methods.  Each must
# be declared by the interface -- a reader implementing a checker needs
# the exact signatures.
if iface:
    m = re.search(r"##\s*3\..*?```java(.*?)```", doc, re.S)
    if m:
        block = m.group(1)
        # the method names in the block: "String name();" / "void init(...)"
        listed = set(re.findall(r"[\w.]+\s+(\w+)\s*\(", block))
        note("the document's AstChecker block lists: %s" % sorted(listed))
        if not listed:
            failures.append("the document's AstChecker block lists no methods "
                            "-- the check cannot verify it")
        ibody = iface[0][1]
        for method in sorted(listed):
            if not re.search(r"\b%s\s*\(" % re.escape(method), ibody):
                failures.append("the document's AstChecker block lists %s, "
                                "which the interface does not declare" % method)
    else:
        failures.append("the document no longer has an AstChecker code block")

# ---------------------------------------------------------------- 5. the registry
reg = sources.get("AstCheckerRegistry")
if reg:
    body = reg[0][1]
    if not re.search(r"\bbyCategory\s*\(", body):
        failures.append("AstCheckerRegistry has no byCategory method")
    note("AstCheckerRegistry.byCategory exists")

# ---------------------------------------------------------------- 6. counts
# "500+ 风格类规则" -- how many checker classes are there?
checker_files = [p for p in STYLE.rglob("*.java")
                 if re.search(r"\bimplements\s+AstChecker\b", strip_comments(read(p)))]
note("style classes implementing AstChecker: %d" % len(checker_files))

m = re.search(r"(\d+)\+?\s*风格类规则", doc)
if m:
    claimed = m.group(1)
    if claimed.isdigit() and int(claimed) > len(checker_files):
        failures.append(
            "the document says %s style rules; the source has %d AstChecker "
            "implementations" % (claimed, len(checker_files)))
    else:
        note("the document's rule count (%s) is consistent with %d checker "
             "classes" % (claimed, len(checker_files)))

# ---------------------------------------------------------------- 7. round markers
for m in re.finditer(r"(?<![A-Za-z0-9_])R-\d{0,4}[a-z]?(?![A-Za-z0-9_])", doc):
    failures.append("the document carries a round marker: %s" % m.group(0))

print()
if failures:
    print("FAIL: %d defect(s) in docs/tech/STYLE-RULES.md" % len(failures))
    for f in failures:
        print("  " + f)
    sys.exit(1)
print("OK: docs/tech/STYLE-RULES.md matches the source")
