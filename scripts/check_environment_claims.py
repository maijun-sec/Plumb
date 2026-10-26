#!/usr/bin/env python3
"""Check docs/usage/ENVIRONMENT.md against the source.

The environment-variable document is the easiest one to get wrong: a
variable that is "not read" is a claim a user acts on, and a line
citation that points at the wrong line is a claim a reader checks.

Three things are checked:

  1. every line citation resolves to the line it claims;
  2. every variable the document says is NOT read really is not read
     anywhere -- neither in Java nor in the shipped launcher scripts;
  3. every variable the document says IS read really is read.

Exit code 0 when the document matches the source, 1 otherwise.
"""
import io
import pathlib
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent
DOC = REPO / "docs" / "usage" / "ENVIRONMENT.md"

MODULES = ("plumb-main", "plumb-ai", "plumb-dfa", "plumb-style",
           "plumb-rules", "plumb-tac", "plumb-reporter", "plumb-parser",
           "plumb-build")

JAVA_MAIN = sorted(
    p for root in MODULES
    for p in (REPO / root / "src" / "main").rglob("*.java")
    if (REPO / root / "src" / "main").is_dir())
JAVA_TEST = sorted(
    p for root in MODULES
    for p in (REPO / root / "src" / "test").rglob("*.java")
    if (REPO / root / "src" / "test").is_dir())
LAUNCHERS = sorted((REPO / "scripts").glob("dist-jlink-*"))

doc = DOC.read_text(encoding="utf-8", errors="replace")
failures = []
info = []


def note(msg):
    info.append(msg)
    print(msg)


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


# ------------------------------------------------- 1. line citations
# A citation looks like `ConfigLoader.java:435` or
# `plumb-ai/.../LlmConnection.java:147` or `default-config.yaml:182-193`.
CITE = re.compile(
    r"`([A-Za-z0-9_./\-]+\.(?:java|yaml|yml|sh|ps1))"
    r"(?::(\d+)(?:-(\d+))?)?`")

# Build an index: file name -> list of (path, lines)
by_name = {}
for p in JAVA_MAIN + JAVA_TEST:
    by_name.setdefault(p.name, []).append((p, read(p).splitlines()))
for p in LAUNCHERS:
    by_name.setdefault(p.name, []).append((p, read(p).splitlines()))
for p in (REPO / "plumb-main" / "src" / "main" / "resources").glob("*.yaml"):
    by_name.setdefault(p.name, []).append((p, read(p).splitlines()))

note("indexed %d candidate files" % sum(len(v) for v in by_name.values()))

cited = 0
for m in CITE.finditer(doc):
    name = m.group(1)
    short = name.split("/")[-1]
    if not m.group(2):
        continue
    lo = int(m.group(2))
    hi = int(m.group(3)) if m.group(3) else lo
    cited += 1
    entries = by_name.get(short)
    if not entries:
        failures.append("the document cites %s, which does not exist" % name)
        continue
    # the citation is satisfied if ANY file of that name has the line
    ok = False
    for p, lines in entries:
        if lo < 1 or hi > len(lines):
            continue
        if p.name == short and name.endswith(p.name):
            ok = True
            break
    if not ok:
        failures.append(
            "the document cites %s:%d-%s, which is not a valid line range in "
            "that file" % (short, lo, hi))
note("checked %d line citations" % cited)

# ------------------------------------------------- 1b. the getenv citations
# The two reads are cited by line.  The cited line must BE the read.
# There are two ConfigLoader classes (plumb-main and plumb-dfa), so the
# file is selected by the module in its path, not by name alone.
#
# The check is DERIVED, not pinned: the gate finds the reads itself (see
# getenv_reads below) and then requires the document to cite exactly
# those lines.  A stale citation fails; the fix is to update the
# document, not to weaken the gate.
MAIN_CONFIG_LOADER = (REPO / "plumb-main" / "src" / "main" / "java"
                      / "org" / "plumb" / "main" / "ConfigLoader.java")
cl_lines = read(MAIN_CONFIG_LOADER).splitlines()


def _cl_is_read(lo):
    """Is ConfigLoader.java line `lo` one of the environment reads?"""
    if not (1 <= lo <= len(cl_lines)):
        return False
    line = cl_lines[lo - 1]
    code = re.sub(r'"(?:[^"\\]|\\.)*"', '""', line)
    return "System.getenv" in code


# ------------------------------------------------- 2. getenv reads
def getenv_reads(paths):
    """The lines that really READ an environment variable.

    A read is a call whose result is used: `System.getenv(...)` in code.
    A hit inside a string literal or a javadoc is a CHECKER PATTERN --
    a rule looking for that call shape in the user's code, not a read.
    """
    reads = []
    for p in paths:
        text = read(p)
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
            # strip string literals
            code = re.sub(r'"(?:[^"\\]|\\.)*"', '""', line)
            code = re.sub(r"'(?:[^'\\]|\\.)*'", "''", code)
            if "System.getenv" in code:
                reads.append((p, i, code.strip()))
    return reads


main_reads = getenv_reads(JAVA_MAIN)
test_reads = getenv_reads(JAVA_TEST)
note("real getenv reads in main sources: %d" % len(main_reads))
for p, i, c in main_reads:
    note("    %s:%d  %s" % (p.relative_to(REPO).as_posix(), i, c))
note("real getenv reads in test sources: %d" % len(test_reads))
for p, i, c in test_reads:
    note("    %s:%d  %s" % (p.relative_to(REPO).as_posix(), i, c))

# The document's line citations must name the lines the reads are ON.
# The gate derives the read lines, so the check follows the code instead
# of a hardcoded number that goes stale when the file shifts.
if main_reads:
    read_lines = {p.name: sorted(i for pp, i, _ in main_reads if pp.name == p.name)
                  for p, _, _ in main_reads}
    cl_read_lines = set(read_lines.get("ConfigLoader.java", []))
    cited_lines = set()
    for m in re.finditer(r"`ConfigLoader\.java:(\d+)`", doc):
        cited_lines.add(int(m.group(1)))
    for m in re.finditer(r"//\s*:(\d+)", doc):
        cited_lines.add(int(m.group(1)))
    note("the reads are on lines %s; the document cites %s"
         % (sorted(cl_read_lines), sorted(cited_lines)))
    if cited_lines != cl_read_lines:
        failures.append(
            "the document cites ConfigLoader.java line(s) %s, but the "
            "environment reads are on %s"
            % (sorted(cited_lines), sorted(cl_read_lines)))
    for lo in sorted(cited_lines):
        if not _cl_is_read(lo):
            failures.append(
                "the document cites ConfigLoader.java:%d for an environment "
                "read, but that line is: %s" % (lo, cl_lines[lo - 1].strip()))

# The document says there are exactly TWO reads in the main code.
if len(main_reads) != 2:
    failures.append(
        "the document says the main code reads the environment in two places; "
        "the source has %d: %s"
        % (len(main_reads), ["%s:%d" % (p.name, i) for p, i, _ in main_reads]))
else:
    names = sorted(p.name for p, _, _ in main_reads)
    if names != ["ConfigLoader.java", "ConfigLoader.java"]:
        failures.append("the document says both reads are in ConfigLoader.java; "
                        "they are in %s" % names)

# The document STATES the count in prose.  That number has to agree with
# the source too -- a document that says "three places" while the source
# has two is exactly the kind of drift this gate exists to catch.
COUNT_CLAIM = re.compile(r"环境变量\*\*读取\*\*只有\s*`?[^`]*`?\s*的\s*([一二三四五六七八九十两\d]+)\s*处")
stated = COUNT_CLAIM.search(doc)
if not stated:
    failures.append("the document no longer states the number of environment "
                    "reads -- the check cannot verify it")
else:
    CN = {"一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6,
          "七": 7, "八": 8, "九": 9, "十": 10}
    raw = stated.group(1)
    n = int(raw) if raw.isdigit() else CN.get(raw)
    note("the document states %s environment reads" % raw)
    if n is None:
        failures.append("cannot interpret the stated read count: %r" % raw)
    elif n != len(main_reads):
        failures.append(
            "the document states %d environment reads; the source has %d"
            % (n, len(main_reads)))

# ------------------------------------------------- 3. the "not read" table
# Every variable the document marks as not read must really be unread --
# in the Java code AND in the shipped launchers.
NOT_READ = re.findall(r"\|\s*`([A-Z_][A-Z0-9_]*)`\s*\|\s*❌\s*未读取", doc)
note("the document marks %d variables as not read: %s"
     % (len(NOT_READ), NOT_READ))

launcher_text = "\n".join(read(p) for p in LAUNCHERS)
for var in NOT_READ:
    if var in launcher_text:
        failures.append(
            "the document says %s is not read, but the shipped launcher "
            "scripts read it" % var)

# And the reverse: a variable the document says IS read must be read.
# The document marks a variable as read in two shapes:
#   | `VAR` | ✅ ... |            (the "indirect" marker)
#   | `VAR` | `scripts/...` | ... |  (the launcher table)
IS_READ = re.findall(r"\|\s*`([A-Z_][A-Z0-9_]*)`\s*\|\s*✅", doc)
# the launcher table: "| `VAR` | `scripts/...` / `scripts/...` | ... |"
# The cell may list several script paths separated by " / ", so the check
# is "the cell mentions scripts/", not "the cell is one backticked path".
for line in doc.splitlines():
    m = re.match(r"\|\s*`([A-Z_][A-Z0-9_]*)`\s*\|([^|]*)\|", line)
    if m and "scripts/" in m.group(2):
        IS_READ.append(m.group(1))
IS_READ = sorted(set(IS_READ))
note("the document marks %d variables as read: %s" % (len(IS_READ), IS_READ))
for var in IS_READ:
    if var not in launcher_text:
        failures.append(
            "the document says %s is read, but no launcher script reads it" % var)
    if var in NOT_READ:
        failures.append(
            "the document marks %s as both read and not read" % var)

# The variables the document says are NOT read must not be read in Java
# either (except the ones it marks as test-only).
for var in NOT_READ:
    for p, i, c in main_reads:
        if ('"%s"' % var) in c:
            failures.append(
                "the document says %s is not read, but %s:%d reads it"
                % (var, p.name, i))

print()
if failures:
    print("FAIL: %d defect(s) in docs/usage/ENVIRONMENT.md" % len(failures))
    for f in failures:
        print("  " + f)
    sys.exit(1)
print("OK: docs/usage/ENVIRONMENT.md matches the source")
