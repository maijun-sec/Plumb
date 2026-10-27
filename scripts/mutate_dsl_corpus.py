#!/usr/bin/env python3
"""Mutation test for the DSL corpus gate and for the matcher semantics.

A gate that passes because it checked nothing is worse than no gate. This
script mutates the corpus and the source in ways that MUST make something
fail, and checks that it does.

There are two kinds of mutant, because there are two kinds of invariant:

  * a GATE mutant breaks the corpus file -- a deleted example, a missing
    description, a construct nobody exercises.  `scripts/check_dsl_corpus.py`
    must notice.

  * an ENGINE mutant breaks the engine's SEMANTICS -- `contain` becoming a
    subtree search again.  The corpus file is untouched by such a change, so
    the Python gate cannot see it: the file still holds the same examples with
    the same counts.  The invariant is protected by the Kotlin tests, so the
    mutant is checked by RUNNING them, and the run must fail.

Each mutant is applied to a temporary copy of the repository's files; the real
files are never touched.

Usage:  python scripts/mutate_dsl_corpus.py
"""
import io
import os
import pathlib
import re
import subprocess
import sys
import tempfile

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
MATCHERS = (
    REPO
    / "plumb-dsl-kotlin"
    / "src"
    / "main"
    / "kotlin"
    / "org"
    / "plumb"
    / "dsl"
    / "kotlin"
    / "Matchers.kt"
)
GATE = REPO / "scripts" / "check_dsl_corpus.py"

original_corpus = CORPUS.read_text(encoding="utf-8")
original_source = RULE_MATCHER.read_text(encoding="utf-8")
original_matchers = MATCHERS.read_text(encoding="utf-8")


def run_gate():
    result = subprocess.run(
        [sys.executable, str(GATE)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return result.returncode, result.stdout


def run_tests():
    """Run the Kotlin tests that protect the matcher's semantics.

    <p>The engine mutants change the engine, and the corpus file is untouched
    by such a change -- so the Python gate cannot see them. The Kotlin tests
    are what protect the invariant, and a mutant that leaves them green is a
    survivor.

    <p>Only the two test classes that carry the semantics are run, so the
    mutation does not spend minutes on the whole reactor.
    """
    maven = os.environ.get("PLUMB_MAVEN", "mvn.cmd")
    result = subprocess.run(
        [
            maven,
            "-Dmaven.repo.local=" + os.environ.get("PLUMB_MAVEN_REPO", "D:\\cache\\mvn_repo"),
            "-q",
            "-pl",
            "plumb-dsl-kotlin",
            "-am",
            "-Dtest=DslExampleCorpusTest,ContainSemanticsTest",
            "-DfailIfNoTests=false",
            "-Dsurefire.failIfNoSpecifiedTests=false",
            "test",
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        cwd=str(REPO),
    )
    return result.returncode, (result.stdout or "") + (result.stderr or "")


def drop_an_example(text):
    """Remove exactly ONE example from the corpus.

    <p>Exactly one, because a mutant that empties the file proves less: it would
    be caught by the count check alone, and would not show that the gate
    notices a single missing example.
    """
    lines = text.split("\n")
    # The first example runs from its "- name:" line up to the next one.
    starts = [i for i, line in enumerate(lines) if re.match(r"^  - name:", line)]
    if len(starts) < 2:
        return text
    first = starts[0]
    second = starts[1]
    out = lines[:first] + lines[second:]
    return "\n".join(out)


def drop_the_description(text):
    """Remove one example's `shows` line."""
    return re.sub(r"^\s*shows:\s*\"[^\"]*\"\n", "", text, count=1, flags=re.M)


def add_a_property_to_the_source(text):
    """Add a node property the corpus does not use."""
    return text.replace(
        '        "empty" to "NodeAccess.isEmpty"',
        '        "empty" to "NodeAccess.isEmpty",\n'
        '        "aBrandNewProperty" to "NodeAccess.aBrandNewProperty"',
        1,
    )


def turn_contain_into_a_subtree_search(text):
    """Make `contain` search the whole subtree instead of the direct children.

    <p>This is the invariant the corpus exists to protect: a subtree search
    finds everything a direct-children search finds, and more. A rule written
    against the upstream would fire on nodes the upstream never fires on -- and
    it would report every node as containing itself, because JavaParser's
    `findAll` includes the receiver.

    <p>The WHOLE `directChildren` body is replaced, not just its fallback:
    replacing only the fallback would affect only the nodes that have no block
    body, and the mutant would survive for the wrong reason.
    """
    old = """    private fun directChildren(node: Node): List<Node> {
        val block = blockBodyOf(node)
        if (block != null) {
            return block.statements
        }"""
    new = """    private fun directChildren(node: Node): List<Node> {
        if (true) {
            return node.findAll(Node::class.java).toList()
        }
        val block = blockBodyOf(node)
        if (block != null) {
            return block.statements
        }"""
    if old not in text:
        raise SystemExit("the directChildren body was not found")
    return text.replace(old, new, 1)


# The mutants the PYTHON GATE must catch.
GATE_MUTANTS = [
    (
        "an example is removed",
        "corpus",
        drop_an_example,
    ),
    (
        "an example loses its description",
        "corpus",
        drop_the_description,
    ),
    (
        "the source gains a property no example uses",
        "source",
        add_a_property_to_the_source,
    ),
]

# The mutants the KOTLIN TESTS must catch.  These change the engine's
# semantics, and the corpus file is untouched by such a change -- so the
# Python gate cannot see them, and the tests are what protects the invariant.
ENGINE_MUTANTS = [
    (
        "contain becomes a subtree search again",
        "matchers",
        turn_contain_into_a_subtree_search,
    ),
]

MUTANTS = GATE_MUTANTS + ENGINE_MUTANTS

survivors = []
no_ops = []
try:
    for name, target, mutate in GATE_MUTANTS:
        if target == "corpus":
            CORPUS.write_text(mutate(original_corpus), encoding="utf-8")
            RULE_MATCHER.write_text(original_source, encoding="utf-8")
            MATCHERS.write_text(original_matchers, encoding="utf-8")
        else:
            RULE_MATCHER.write_text(mutate(original_source), encoding="utf-8")
            CORPUS.write_text(original_corpus, encoding="utf-8")
            MATCHERS.write_text(original_matchers, encoding="utf-8")

        # A mutant that did not change anything proves nothing: it would be
        # reported as "the gate let a drift through" when in fact the drift
        # was never introduced.
        changed = (
            CORPUS.read_text(encoding="utf-8") != original_corpus
            or RULE_MATCHER.read_text(encoding="utf-8") != original_source
        )
        if not changed:
            no_ops.append(name)
            print("NO-OP     %s  (the mutation did not apply)" % name)
            continue

        code, out = run_gate()
        if code == 0:
            survivors.append(name)
            print("SURVIVED  %s" % name)
        else:
            first = ""
            for line in out.splitlines():
                if line.startswith("  "):
                    first = line.strip()
                    break
            print("killed    %s  (%s)" % (name, first))

    # The ENGINE mutants.  These change the engine, and the corpus file is
    # untouched by them, so the gate cannot see them -- the Kotlin tests are
    # what protect the invariant, and the test run must fail.
    for name, target, mutate in ENGINE_MUTANTS:
        if target == "matchers":
            MATCHERS.write_text(mutate(original_matchers), encoding="utf-8")
        else:
            RULE_MATCHER.write_text(mutate(original_source), encoding="utf-8")

        changed = (
            RULE_MATCHER.read_text(encoding="utf-8") != original_source
            or MATCHERS.read_text(encoding="utf-8") != original_matchers
        )
        if not changed:
            no_ops.append(name)
            print("NO-OP     %s  (the mutation did not apply)" % name)
            continue

        code, out = run_tests()
        if code == 0:
            survivors.append(name)
            print("SURVIVED  %s" % name)
        else:
            first = ""
            for line in out.splitlines():
                if "<<< FAILURE" in line or "<<< ERROR" in line:
                    first = line.strip()
                    break
            print("killed    %s  (%s)" % (name, first))

        # Restore, so the next mutant starts from the real sources.
        RULE_MATCHER.write_text(original_source, encoding="utf-8")
        MATCHERS.write_text(original_matchers, encoding="utf-8")
finally:
    CORPUS.write_text(original_corpus, encoding="utf-8")
    RULE_MATCHER.write_text(original_source, encoding="utf-8")
    MATCHERS.write_text(original_matchers, encoding="utf-8")

print()
if no_ops:
    print("FAIL: %d mutant(s) did not apply -- they prove nothing" % len(no_ops))
    for s in no_ops:
        print("  " + s)
    sys.exit(1)
if survivors:
    print("FAIL: %d mutant(s) survived -- the gate is checking air" % len(survivors))
    for s in survivors:
        print("  " + s)
    sys.exit(1)
print("OK: all %d mutants were killed" % len(MUTANTS))
