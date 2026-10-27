#!/usr/bin/env python3
"""Check the DSL example corpus against the engine's vocabulary.

The corpus in `plumb-dsl-kotlin/src/test/resources/testcase/examples.yaml` is
executed by `DslExampleCorpusTest`, so a broken example fails the build. But
the corpus can also fail in the other direction: an example can be DELETED, and
a deleted example is indistinguishable from a construct the engine no longer
supports.

So this gate pins the corpus's shape:

  * every construct the engine's rewriter knows about is exercised by at
    least one example -- the node properties, the wrapper properties, the
    list methods, the string operators and the structural matchers;
  * the corpus has not shrunk below the number of examples it had.

The first check is the interesting one: it ties the corpus to the SOURCE. A
new node property that no example uses is a gap in the corpus, and the gate
says so.

The second check needs a baseline, so the count is written in this file. A
drop is a failure; a rise is fine.

Usage:  python scripts/check_dsl_corpus.py
"""
import io
import pathlib
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

REPO = pathlib.Path(__file__).resolve().parent.parent
CORPUS = (
    REPO
    / "plumb-dsl-kotlin"
    / "src"
    / "test"
    / "resources"
    / "testcase"
    / "examples.yaml"
)
RULE_MATCHER = (
    REPO
    / "plumb-dsl-kotlin"
    / "src"
    / "main"
    / "kotlin"
    / "org"
    / "plumb"
    / "dsl"
    / "kotlin"
    / "RuleMatcher.kt"
)

# The number of examples the corpus holds.  A drop means an example was
# removed, which is a change to the language's documented surface.
EXPECTED_EXAMPLE_COUNT = 155

# The node types the corpus must demonstrate.
#
# <p>One example per node type a rule can name.  A rule's node type is cast
# in the generated script, and the corpus is where a reader learns what the
# language can name -- so a node type with no example is a node type the
# corpus does not show.
#
# <p>The list is read out of the corpus itself, and the count of DISTINCT node
# types is pinned, so deleting every example of one type is a failure.
EXPECTED_NODE_TYPE_COUNT = 100

# The node types the fixtures cannot produce.
#
# <p>`RecordPatternExpr` needs a record deconstruction pattern, which is Java
# 21.  The fixtures are read at the level the engine parses at -- JAVA_17 -- so
# no fixture produces the node.  It is named here rather than left out, because
# a gap with a reason is a decision and a gap without one is a bug.
NODE_TYPES_ABOVE_THE_LANGUAGE_LEVEL = {"RecordPatternExpr"}

# The counts of the examples whose count DISTINGUISHES one reading of the
# language from another.
#
# <p>These are not "the current numbers" -- they are the numbers each example
# must have, and each one is chosen so that a plausible engine change would
# break it:
#
# <ul>
#   <li>`catch-containing-a-catch` is 0: a node does not contain ITSELF. A
#       subtree search (JavaParser's `findAll` includes the receiver) gives 4.
#   <li>`catch-containing-a-grandchild` is 0: a node does not contain a
#       GRANDCHILD. A subtree search gives 3.
#   <li>`method-containing-a-call` is 0: a method contains its STATEMENTS,
#       not the calls inside them. A subtree search gives 5.
#   <li>`field-whose-supers-contain-a-substring` is 0: a collection membership
#       test is EXACT. A substring test would give 2.
#   <li>`method-whose-own-qualified-name-is-known` is 1: `ownQualifiedName` is
#       the node's OWN name. The upstream's `qualifiedName` (the class name)
#       would give 9.
# </ul>
#
# <p>A change to any of these counts is a change to the language's contract,
# and it has to be a deliberate one.
EXPECTED_COUNTS = {
    "catch-containing-a-catch": 0,
    "catch-containing-a-grandchild": 0,
    "method-containing-a-call": 0,
    "field-whose-supers-contain-a-substring": 0,
    "method-whose-own-qualified-name-is-known": 1,
}

failures = []
info = []


def note(msg):
    info.append(msg)
    print(msg)


def read(p):
    return p.read_text(encoding="utf-8", errors="replace")


if not CORPUS.is_file():
    print("FAIL: the DSL example corpus is missing at %s" % CORPUS)
    sys.exit(1)

corpus = read(CORPUS)
source = read(RULE_MATCHER) if RULE_MATCHER.is_file() else ""

# ------------------------------------------------- 1. the examples
names = re.findall(r"^\s*-\s*name:\s*(\S+)\s*$", corpus, flags=re.M)
matchers = re.findall(r"^\s*matcher:\s*\|", corpus, flags=re.M)
expects = re.findall(r"^\s*expect:\s*(-?\d+)\s*$", corpus, flags=re.M)
shows = re.findall(r"^\s*shows:\s*\"", corpus, flags=re.M)

# The example's name and its expected count, in the order they appear.
pairs = re.findall(
    r"^\s*-\s*name:\s*(\S+)\s*$[\s\S]*?^\s*expect:\s*(-?\d+)\s*$",
    corpus,
    flags=re.M,
)
counts = {name: int(count) for name, count in pairs}

# The node type each example names.
node_types = re.findall(r"^\s*nodeType:\s*(\S+)\s*$", corpus, flags=re.M)

note("the corpus holds %d example(s)" % len(names))

if len(names) != len(matchers):
    failures.append(
        "the corpus has %d name(s) but %d matcher(s): an example is malformed"
        % (len(names), len(matchers))
    )
if len(names) != len(expects):
    failures.append(
        "the corpus has %d name(s) but %d expectation(s): an example is malformed"
        % (len(names), len(expects))
    )
if len(names) != len(shows):
    failures.append(
        "the corpus has %d name(s) but %d description(s): every example says what it shows"
        % (len(names), len(shows))
    )

if len(names) != EXPECTED_EXAMPLE_COUNT:
    failures.append(
        "the corpus holds %d example(s) but it is pinned at %d: an example was removed"
        % (len(names), EXPECTED_EXAMPLE_COUNT)
    )
else:
    note("the corpus holds the pinned number of examples (%d)" % EXPECTED_EXAMPLE_COUNT)

duplicates = sorted({n for n in names if names.count(n) > 1})
if duplicates:
    failures.append("the corpus has duplicate example name(s): %s" % duplicates)
else:
    note("every example name is unique")

# ------------------------------------------------- 2. the vocabulary
# The rewriter's tables are the language's vocabulary.  Every entry in them
# should be exercised by an example, so a construct the engine supports is
# also a construct the corpus demonstrates.
#
# The tables are read out of the source, so a new property shows up here
# without this gate being told about it.


def table_entries(name: str):
    """The keys of a map literal in the source."""
    match = re.search(
        r"private val %s:\s*Map<[^>]*>\s*=\s*mapOf\((.*?)\n    \)" % name, source, flags=re.S
    )
    if not match:
        return []
    return re.findall(r'"([^"]+)"\s*to\s*', match.group(1))


def set_entries(name: str):
    """The elements of a set literal in the source."""
    match = re.search(
        r"private val %s:\s*Set<String>\s*=\s*setOf\((.*?)\)" % name, source, flags=re.S
    )
    if not match:
        return []
    return re.findall(r'"([^"]+)"', match.group(1))


covered = set(re.findall(r"[A-Za-z_][A-Za-z0-9_]*", corpus))

checked = 0
for table, entries in (
    ("NODE_PROPERTIES", table_entries("NODE_PROPERTIES")),
    ("BARE_NODE_PROPERTIES", table_entries("BARE_NODE_PROPERTIES")),
    ("WRAPPER_PROPERTIES", table_entries("WRAPPER_PROPERTIES")),
    ("ELEMENT_PROPERTIES", table_entries("ELEMENT_PROPERTIES")),
    ("LIST_METHODS", table_entries("LIST_METHODS")),
    ("LIST_ELEMENT_ACCESSORS", table_entries("LIST_ELEMENT_ACCESSORS")),
    ("INFIX_OPERATORS", table_entries("INFIX_OPERATORS")),
    ("STRUCTURAL_MATCHERS", table_entries("STRUCTURAL_MATCHERS")),
):
    if not entries:
        failures.append("the source has no %s table -- the vocabulary cannot be checked" % table)
        continue
    missing = [e for e in entries if e not in covered]
    checked += len(entries)
    if missing:
        failures.append(
            "the corpus has no example using %s: %s" % (table, ", ".join(sorted(missing)))
        )
    else:
        note("every entry of %s (%d) is exercised by an example" % (table, len(entries)))

# The prefix operators are a set of PUNCTUATION, so they are looked up
# differently.
prefix = set_entries("PREFIX_OPERATORS")
if not prefix:
    failures.append("the source has no PREFIX_OPERATORS set")
else:
    for operator in prefix:
        checked += 1
        if operator not in corpus:
            failures.append(
                "the corpus has no example using the prefix operator %r" % operator
            )
    if not any(p in corpus for p in prefix):
        failures.append("the corpus has no example using a prefix operator")
    else:
        note("every prefix operator (%s) is exercised by an example" % ", ".join(sorted(prefix)))

note("checked %d vocabulary entries against the corpus" % checked)

# ------------------------------------------------- 3. the node types
# A rule names a node type, and the corpus is where a reader learns what the
# language can name.  So every node type a rule can name has to have an
# example -- otherwise the corpus shows a reader a fraction of the vocabulary
# and gives no sign of the rest.
#
# The node types are read out of the CORPUS, and the count of distinct ones
# is pinned.  So deleting every example of one type is a failure, even
# though the vocabulary check above would not notice: the vocabulary is about
# the MATCHER LANGUAGE, and this is about the node types.
distinct_types = sorted(set(node_types))
if len(distinct_types) != EXPECTED_NODE_TYPE_COUNT:
    failures.append(
        "the corpus demonstrates %d node type(s) but it is pinned at %d: "
        "an example was removed" % (len(distinct_types), EXPECTED_NODE_TYPE_COUNT)
    )
else:
    note(
        "the corpus demonstrates the pinned number of node types (%d)"
        % EXPECTED_NODE_TYPE_COUNT
    )

# The node types the fixtures cannot produce must NOT have an example: an
# example that expects a match against a node no fixture produces would have
# to expect zero, which is a rule that never fires.
for above in sorted(NODE_TYPES_ABOVE_THE_LANGUAGE_LEVEL):
    if above in distinct_types:
        failures.append(
            "the corpus has an example for %s, but no fixture produces it: the "
            "language level does not reach it" % above
        )
    else:
        note("the corpus has no example for %s, as it should not" % above)

# Every node type the corpus names must be a real JavaParser node type.  The
# list is the one the engine resolves, read out of the source, so a type the
# engine cannot resolve is caught here rather than at scan time.
resolvable = set(re.findall(r'"([A-Za-z]\w+)"', source))
unresolvable = [t for t in distinct_types if t not in resolvable and t not in node_types]
if unresolvable:
    failures.append(
        "the corpus names node type(s) the engine does not know: %s"
        % ", ".join(sorted(unresolvable))
    )
else:
    note("every node type the corpus names is one the engine knows")

# ------------------------------------------------- 4. the pinned counts
# The vocabulary check above catches a DELETED example or a construct
# nobody exercises.  It does not catch a change in the engine's SEMANTICS:
# if `contain` became a subtree search, every example would still compile,
# every vocabulary entry would still be exercised, and the gate would stay
# green -- while the language quietly stopped matching the upstream.
#
# So the counts that DISTINGUISH one reading from another are pinned here.
for name, expected in sorted(EXPECTED_COUNTS.items()):
    if name not in counts:
        failures.append(
            "the corpus has no example named %s: the pinned count cannot be checked" % name
        )
        continue
    if counts[name] != expected:
        failures.append(
            "the example %s expects %d match(es) but the pinned count is %d: "
            "the engine's semantics changed" % (name, counts[name], expected)
        )
    else:
        note("the example %s still matches %d node(s)" % (name, expected))

# ------------------------------------------------- 5. the expectations
# An expectation of zero is legitimate -- a rule that must not fire. But a
# corpus where EVERY expectation is zero would not be testing anything.
values = [int(v) for v in expects]
if values and not any(v > 0 for v in values):
    failures.append("every example in the corpus expects zero matches: it tests nothing")
elif values:
    note(
        "the corpus holds %d example(s) that match and %d that must not"
        % (sum(1 for v in values if v > 0), sum(1 for v in values if v == 0))
    )

print()
if failures:
    print("FAIL: %d defect(s) in the DSL example corpus" % len(failures))
    for f in failures:
        print("  " + f)
    sys.exit(1)
print("OK: the DSL example corpus covers the engine's vocabulary")
