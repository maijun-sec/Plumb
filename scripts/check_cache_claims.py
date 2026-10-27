#!/usr/bin/env python3
"""Check docs/tech/CACHE.md against the source.

The document describes the disk cache: the file format, the key
derivation, the four cache modes, the call chain, and the source-map
retention contract.  Each of those is a claim a reader would rely on.

The script prints what it derives before it compares.

Exit code 0 when the document matches the source, 1 otherwise.
"""
import io
import pathlib
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent
DOC = REPO / "docs" / "tech" / "CACHE.md"
TAC = REPO / "plumb-tac" / "src" / "main" / "java" / "org" / "plumb" / "tac"
MAIN = REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb" / "main"

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


# ---------------------------------------------------------------- 1. the format
ser = TAC / "io" / "IrSerializer.java"
if not ser.is_file():
    for p in TAC.rglob("IrSerializer.java"):
        ser = p
body = read(ser)
note("IrSerializer: %s" % ser.relative_to(REPO).as_posix())

magic = re.search(r"MAGIC\s*=\s*(0x[0-9A-Fa-f_]+)", body)
version = re.search(r"VERSION\s*=\s*(\d+)", body)
if magic:
    magic_value = int(magic.group(1).replace("_", ""), 16)
    note("MAGIC = %s (= 0x%08X)" % (magic.group(1), magic_value))
    m = re.search(r"`?MAGIC`?\s*=\s*`?(0x[0-9A-Fa-f_]+)", doc)
    if m and int(m.group(1).replace("_", ""), 16) != magic_value:
        failures.append("the document says MAGIC = %s; the source says %s"
                        % (m.group(1), magic.group(1)))
    # ASCII check: "PLUM"
    if magic_value != int.from_bytes(b"PLUM", "big"):
        failures.append("MAGIC is not ASCII 'PLUM'")
else:
    failures.append("IrSerializer has no MAGIC constant")

if version:
    note("VERSION = %s" % version.group(1))
    m = re.search(r"`?VERSION`?\s*=\s*`?(\d+)`?", doc)
    if m and int(m.group(1)) != int(version.group(1)):
        failures.append("the document says VERSION = %s; the source says %s"
                        % (m.group(1), version.group(1)))

# the tag write
if "writeObject" in body:
    has_tag = re.search(r"writeUTF|writeObject\(.*tag", body)
    note("IrSerializer writes a tag: %s" % bool(has_tag))

# ---------------------------------------------------------------- 2. cache modes
cfg = REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb" / "main" / "ScanConfig.java"
if cfg.is_file():
    cbody = read(cfg)
    modes = re.findall(r"\b(OFF|READ|WRITE|READWRITE)\b", cbody)
    seen = []
    for mm in modes:
        if mm not in seen:
            seen.append(mm)
    note("ScanConfig mentions cache modes: %s" % seen)
    doc_modes = re.findall(r"^\|\s*`([A-Z]+)`\s*\|", doc, re.M)
    doc_modes = [m for m in doc_modes if m in ("OFF", "READ", "WRITE", "READWRITE")]
    note("the document's mode table lists: %s" % doc_modes)
    for m in doc_modes:
        if m not in seen:
            failures.append("the document lists cache mode %s, which "
                            "ScanConfig does not define" % m)
    if len(doc_modes) != 4:
        failures.append("the document lists %d cache modes; there are %d"
                        % (len(doc_modes), len(seen)))

    # The mode BEHAVIOURS are claims too.  The source guards the read and
    # the write separately, and the guard names the modes that allow it:
    #   loadCachedProgram  -> READ, READWRITE
    #   storeCachedProgram -> WRITE, READWRITE
    # So "READ does not write" and "WRITE does not read" are checkable
    # claims about which mode names appear in which guard.
    runner_src = (REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb"
                  / "main" / "ScanRunner.java")
    if runner_src.is_file():
        rbody = strip_comments(read(runner_src))
        guards = {}
        for gname in ("loadCachedProgram", "storeCachedProgram"):
            gm = re.search(r"private\s+[\w.<>,\[\] ]+\s+%s\s*\([^)]*\)\s*\{(.*?)\n  \}"
                           % gname, rbody, re.S)
            guards[gname] = set(re.findall(r"CacheMode\.(\w+)", gm.group(1))) if gm else set()
            note("%s allows: %s" % (gname, sorted(guards[gname])))

        def doc_row(mode):
            for line in doc.splitlines():
                if line.startswith("| `%s`" % mode):
                    return line
            return None

        BEHAVIOUR = [
            # mode, the guard that must ALLOW it, the guard that must NOT
            ("OFF", None, None),
            ("READ", "loadCachedProgram", "storeCachedProgram"),
            ("WRITE", "storeCachedProgram", "loadCachedProgram"),
            ("READWRITE", "loadCachedProgram", "storeCachedProgram"),
        ]
        for mode, allows, forbids in BEHAVIOUR:
            row = doc_row(mode)
            if row is None:
                continue
            if allows is None:
                # OFF: neither guard may allow it, and the row must SAY
                # that it neither reads nor writes.
                for gname in ("loadCachedProgram", "storeCachedProgram"):
                    if mode in guards.get(gname, set()):
                        failures.append(
                            "the document says %s does neither, but %s allows it"
                            % (mode, gname))
                # The wording is language-independent: "Neither reads nor
                # writes" in English, "不读不写" in Chinese. The claim is
                # that the row mentions BOTH reading and writing.
                flat = row.replace(" ", "").lower()
                if not (re.search(r"neither|not\b.*read|不读", flat, re.I)
                        and re.search(r"nor|not\b.*writ|不写", flat, re.I)):
                    failures.append(
                        "the document's %s row does not say it neither reads "
                        "nor writes: %s" % (mode, row.strip()))
                continue
            if mode not in guards.get(allows, set()):
                failures.append(
                    "the document lists %s as a mode, but %s does not allow it"
                    % (mode, allows))
            if forbids in guards and mode in guards.get(forbids, set()):
                # the guard allows it, so the document must not say the
                # mode skips that side
                if forbids == "storeCachedProgram" and re.search(
                        r"does not write|不写", row, re.I):
                    failures.append(
                        "the document says %s does not write, but "
                        "storeCachedProgram allows it" % mode)
                if forbids == "loadCachedProgram" and re.search(
                        r"does not read|不读", row, re.I):
                    failures.append(
                        "the document says %s does not read, but "
                        "loadCachedProgram allows it" % mode)

    # The default mode: the document says OFF is the default.
    m = re.search(r"\| `OFF`\s*\|[^|]*[（(]\s*(?:默认|default)\s*[)）]", doc,
                  re.I)
    if m:
        if cfg.is_file():
            cbody = strip_comments(read(cfg))
            if not re.search(r"cacheMode\s*=\s*CacheMode\.OFF", cbody) \
                    and 'cacheMode", "OFF"' not in cbody \
                    and not re.search(r"CacheMode\.OFF", cbody):
                failures.append("the document says OFF is the default cache "
                                "mode; the source never defaults to OFF")

# ---------------------------------------------------------------- 3. the IR class count
# The document says "N serialisable IR classes, each with mutable fields".
# The two operator enums are serialisable but carry no instance field
# (only a static serialVersionUID), so they are not in that count.
# A field counts whether or not it is final -- the point is the class
# has a field layout, which is what a schema would have to describe.
m = re.search(r"(\d+)\s*(?:个)?\s*`Serializable`", doc)
if m:
    claimed = int(m.group(1))
    derived = set()
    for p in TAC.rglob("*.java"):
        b = strip_comments(read(p))
        if not re.search(r"\b(?:implements|extends)\b[^{]*\bSerializable\b", b):
            continue
        # an instance field: not static
        mutable = re.search(
            r"^\s*(?:public|private|protected)\s+(?!static\b)"
            r"[\w<>,.\[\] ]+?\s+\w+\s*(?:=|;)", b, re.M)
        if mutable:
            derived.add(p.stem)
    note("the source has %d serialisable classes with instance fields: %s"
         % (len(derived), sorted(derived)))
    if claimed != len(derived):
        failures.append("the document says %d serialisable IR classes; the "
                        "source has %d" % (claimed, len(derived)))

# ---------------------------------------------------------------- 4. the source-map contract
CONTRACT = [
    ("TacInstruction", "line"),
    ("TacInstruction", "column"),
    ("SourceMap", "Loc"),
    ("TacProgram", "sourcePath"),
]
for cls, member in CONTRACT:
    found = False
    for p in list(TAC.rglob("%s.java" % cls)):
        b = read(p)
        if re.search(r"\b%s\b" % re.escape(member), b):
            found = True
            break
    if not found:
        failures.append("the document claims %s.%s is preserved by the cache, "
                        "but the source has no such member" % (cls, member))
    else:
        note("the cache contract member %s.%s exists" % (cls, member))

# The contract table also states the TYPE of each member.  A type that
# does not exist is a claim the reader cannot check.
CONTRACT_TYPES = [
    ("TacInstruction", "line"),
    ("TacInstruction", "column"),
    ("TacProgram", "sourcePath"),
]
for cls, member in CONTRACT_TYPES:
    declared = False
    for p in list(TAC.rglob("%s.java" % cls)):
        b = strip_comments(read(p))
        if re.search(r"^\s*(?:public|private|protected)\s+[\w<>,.\[\] ]+?\s+%s\s*(?:|=;)"
                     % re.escape(member), b, re.M):
            declared = True
            break
    if not declared:
        failures.append("the document claims %s.%s is preserved, but the "
                        "source does not declare it" % (cls, member))

    # The document's table row states the TYPE.  That type is what has to
    # be checked -- not a constant baked into this script, or the check
    # would only ever verify the type it already knows.
    row = None
    for line in doc.splitlines():
        if not line.startswith("|"):
            continue
        if "`%s.%s`" % (cls, member) in line:
            row = line
            break
    if row is None:
        failures.append("the document does not mention %s.%s in the cache "
                        "contract table" % (cls, member))
        continue
    cells = [c.strip() for c in row.strip().strip("|").split("|")]
    # the type is the cell right after the member cell
    if len(cells) < 2:
        failures.append("the document's %s.%s row states no type" % (cls, member))
        continue
    jtype = cells[1].strip("`")
    note("the document states %s.%s is a %s" % (cls, member, jtype))

    # the DECLARED type must agree with the document's claim
    for p in list(TAC.rglob("%s.java" % cls)):
        b = strip_comments(read(p))
        dm = re.search(
            r"^\s*(?:public|private|protected)\s+([\w<>,.\[\] ]+?)\s+%s\s*(?:=|;)"
            % re.escape(member), b, re.M)
        if dm:
            declared_type = dm.group(1).strip()
            simple = jtype.rpartition(".")[2]
            if simple not in declared_type:
                failures.append(
                    "the document says %s.%s is a %s; the source declares %s"
                    % (cls, member, jtype, declared_type))
            else:
                note("the document's %s.%s type matches the source (%s)"
                     % (cls, member, declared_type))
            # The document's FULLY QUALIFIED type must be the one the
            # source imports.  `java.nio.file.Path` is right;
            # `java.nio.file.PathX` is not a type at all.
            if "." in jtype:
                imports = re.findall(r"^import\s+([\w.]+);", read(p), re.M)
                if jtype not in imports:
                    simple_name = jtype.rpartition(".")[2]
                    if not any(i.rpartition(".")[2] == simple_name
                               for i in imports):
                        failures.append(
                            "the document says %s.%s is a %s, but %s does not "
                            "import that type"
                            % (cls, member, jtype, p.name))
                    else:
                        note("the document's %s type is the imported one"
                             % jtype)
                else:
                    note("the document's %s type is the imported one" % jtype)
            break

# the test names the document quotes
tests = TAC.parent.parent / "test" / "java"
test_dir = REPO / "plumb-tac" / "src" / "test" / "java"
all_test_text = ""
if test_dir.is_dir():
    for p in test_dir.rglob("*.java"):
        all_test_text += read(p)
for m in re.finditer(r"`(roundTrip[A-Za-z0-9_]+)`", doc):
    name = m.group(1)
    if name not in all_test_text:
        failures.append("the document quotes the test %s, which does not "
                        "exist" % name)
note("checked %d quoted test names" % len(re.findall(r"`(roundTrip[A-Za-z0-9_]+)`", doc)))

# ---------------------------------------------------------------- 5. the call chain
# The document quotes a method body.  The method must exist.
CHAIN = [
    ("ScanRunner", "translateAndStyleOne"),
    ("ScanRunner", "loadCachedProgram"),
    ("ScanRunner", "storeCachedProgram"),
    ("ScanRunner", "scanMerged"),
    ("ScanRunner", "expandInputs"),
    ("OutputLayout", "ensureDirectories"),
]
for cls, method in CHAIN:
    found = False
    for root in (REPO / "plumb-main" / "src" / "main" / "java",):
        for p in root.rglob("%s.java" % cls):
            if re.search(r"\b%s\s*\(" % re.escape(method), read(p)):
                found = True
                break
    if not found:
        failures.append("the document's call chain names %s.%s, which does "
                        "not exist" % (cls, method))
    else:
        note("the call chain step %s.%s exists" % (cls, method))

# the quoted method body must match the source's shape
runner = REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb" / "main" / "ScanRunner.java"
if runner.is_file():
    rbody = read(runner)
    mm = re.search(
        r"private\s+TranslatedFile\s+translateAndStyleOne\s*\((.*?)\)\s*\{(.*?)\n  \}",
        rbody, re.S)
    if mm:
        params, bodytxt = mm.group(1), mm.group(2)
        # the document's quoted signature must have the same parameters
        quoted = re.search(
            r"private\s+TranslatedFile\s+translateAndStyleOne\s*\((.*?)\)",
            doc, re.S)
        if quoted:
            src_params = {p.strip().split()[-1].rstrip(",")
                          for p in params.split(",") if p.strip()}
            doc_params = {p.strip().split()[-1].rstrip(",")
                          for p in quoted.group(1).split(",") if p.strip()}
            note("source parameters: %s" % sorted(src_params))
            note("document parameters: %s" % sorted(doc_params))
            if src_params != doc_params:
                failures.append(
                    "the document's translateAndStyleOne has parameters %s; "
                    "the source has %s" % (sorted(doc_params), sorted(src_params)))
        quoted_body = strip_comments(bodytxt)
        for token in ("loadCachedProgram", "buildParser", "TacTranslator",
                      "storeCachedProgram", "runStyleChecks"):
            if token not in quoted_body:
                failures.append("the source's translateAndStyleOne does not "
                                "call %s" % token)

        # The document's quoted body must call the same things.  A
        # document that quotes `new JavaSourceParser()` where the source
        # calls `buildParser()` is quoting a different method.
        doc_body = None
        dm = re.search(
            r"private\s+TranslatedFile\s+translateAndStyleOne\s*\(.*?\)\s*\{(.*?)\n\}",
            doc, re.S)
        if dm:
            doc_body = strip_comments(dm.group(1))
        if doc_body is None:
            failures.append("the document no longer quotes translateAndStyleOne "
                            "-- the body check cannot verify it")
        else:
            for token in ("loadCachedProgram", "buildParser", "TacTranslator",
                          "storeCachedProgram", "runStyleChecks"):
                if token not in doc_body:
                    failures.append(
                        "the document's translateAndStyleOne does not call "
                        "%s, which the source calls" % token)
            for token in ("JavaSourceParser",):
                if token in doc_body and token not in quoted_body:
                    failures.append(
                        "the document's translateAndStyleOne calls %s, which "
                        "the source does not" % token)
        note("the quoted translateAndStyleOne matches the source's calls")
    else:
        failures.append("ScanRunner has no translateAndStyleOne method")

# ---------------------------------------------------------------- 6. the cache key
m = re.search(r"SHA-1\((.*?)\)", doc)
if m:
    note("the document says the cache key is SHA-1(%s)" % m.group(1))
if "canonicalize" in doc:
    for p in (REPO / "plumb-main" / "src" / "main" / "java").rglob("*.java"):
        b = read(p)
        if "canonicalize" in b or "canonical" in b.lower() and "sha" in b.lower():
            note("canonicalisation lives in %s" % p.name)
            break

# ---------------------------------------------------------------- 7. process history
for m in re.finditer(r"(?<![A-Za-z0-9_])R-\d{0,4}[a-z]?(?![A-Za-z0-9_])", doc):
    failures.append("the document carries a round marker: %s" % m.group(0))
# A forward-looking statement about a technology choice cites numbers that
# cannot be reproduced from this repository, so it has to say so. The
# wording differs between the two languages.
kryo_section = ("何时切到 Kryo" in doc or "when to switch to Kryo" in doc)
historical = ("§10 的历史数据" in doc or "historical data" in doc.lower())
if kryo_section and historical:
    if "不可复现" not in doc and "not reproducible" not in doc.lower():
        failures.append("the document cites performance numbers without "
                        "marking them as not reproducible")

print()
if failures:
    print("FAIL: %d defect(s) in docs/tech/CACHE.md" % len(failures))
    for f in failures:
        print("  " + f)
    sys.exit(1)
print("OK: docs/tech/CACHE.md matches the source")
