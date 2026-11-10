#!/usr/bin/env python3
"""Check that the commit as staged states the size of the rule catalogue correctly.

docs/rules/RULES.md and docs/rules/RULE-TOOL-COMPARISON.md, and their Chinese
counterparts, quote the SIZE of the rule catalogue: how many sink patterns there
are, how many source patterns, how many families. Those numbers are MEASURED by
the generators, so the documents can only be correct if they are regenerated
whenever the catalogue changes.

A commit that adds a pattern without regenerating them leaves the repository
stating a number that is no longer true, and nothing in the commit says so. It
has happened twice: the catalogue grew by 47 patterns in one commit and the
documents kept the old number until a later commit noticed.

WHAT IS CHECKED IS THE INDEX, NOT THE WORKING TREE. The index is exported to a
temporary directory and the generators are run there, so what is judged is what
would be committed. A working tree that has been regenerated but not staged is
still a commit that would state the old number, and it is refused as such.

    python scripts/check_generated_documents.py

Exit code 0 when the commit is consistent, 1 when it is not.
"""
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent

# The files the generators read. A commit that touches one of them can change a
# number the derived documents quote, so the commit is what has to be checked.
# The list is the union of what scripts/gen_rules_overview.py,
# scripts/gen_tool_comparison.py and scripts/capability_baseline.py read.
GENERATOR_INPUTS = re.compile(
    r"^(plumb-dfa/src/main/resources/[^/]+\.yaml"
    r"|plumb-dfa/src/main/java/org/plumb/dfa/taint/(TaintRuleRegistry|InputSourcePatterns)\.java"
    r"|plumb-dfa/src/main/java/org/plumb/dfa/checkers/[^/]+\.java"
    r"|plumb-rules/src/main/java/org/plumb/rules/RuleRegistry\.java"
    r"|plumb-style/src/main/java/org/plumb/style/.*\.java"
    r"|scripts/capability_baseline\.py)$"
)

# The generators that quote the catalogue's size.
GENERATORS = ("gen_rules_overview", "gen_tool_comparison")

# The documents they write, in the order they are reported.
DOCUMENTS = (
    "docs/rules/RULES.md",
    "docs/zh/rules/RULES.md",
    "docs/rules/RULE-TOOL-COMPARISON.md",
    "docs/zh/rules/RULE-TOOL-COMPARISON.md",
)


def staged_paths():
    """The paths the index holds, as git sees them.

    GIT_INDEX_FILE is honoured, because the hook runs with the repository's own
    index and the mutation battery runs with a temporary one.
    """
    proc = subprocess.run(
        ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR"],
        cwd=REPO, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        raise RuntimeError("git diff --cached failed: %s" % proc.stderr.strip())
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def export_index(destination):
    """Copy the index into `destination`, so the generators read what would be committed."""
    prefix = str(destination).replace("\\", "/") + "/"
    proc = subprocess.run(
        ["git", "checkout-index", "-a", "--prefix=" + prefix],
        cwd=REPO, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        raise RuntimeError("git checkout-index failed: %s" % proc.stderr.strip())


def check_snapshot(snapshot):
    """Run the generators inside the snapshot; return what they say is stale."""
    failures = []
    for generator in GENERATORS:
        proc = subprocess.run(
            [sys.executable, "scripts/%s.py" % generator, "--check"],
            cwd=snapshot, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if proc.returncode != 0:
            failures.append((generator, (proc.stdout + proc.stderr).strip()))
    return failures


def main():
    touched = [path for path in staged_paths() if GENERATOR_INPUTS.match(path)]
    if not touched:
        print("ok: the commit does not touch a file the derived documents are built from")
        return 0

    snapshot = pathlib.Path(tempfile.mkdtemp(prefix="plumb-derived-documents-"))
    try:
        export_index(snapshot)
        failures = check_snapshot(snapshot)
    finally:
        shutil.rmtree(snapshot, ignore_errors=True)

    if not failures:
        print("ok: the derived documents match the catalogue this commit would record")
        return 0

    print("FAIL: the commit as staged is not self-consistent.")
    print("")
    print("The derived rule documents quote the SIZE of the rule catalogue. This commit")
    print("changes the catalogue, and the documents it would commit still quote the old")
    print("size, so the repository would state a number that is no longer true.")
    print("")
    print("The files that are stale:")
    for document in DOCUMENTS:
        print("    %s" % document)
    print("")
    for generator, output in failures:
        print("  %s says:" % generator)
        for line in output.splitlines():
            print("    %s" % line)
        print("")
    print("Regenerate them, and stage the result:")
    for generator in GENERATORS:
        print("    python scripts/%s.py" % generator)
    print("    git add docs/rules docs/zh/rules")
    print("")
    print("If the working tree ALREADY carries the regenerated documents, the problem is")
    print("that they were not staged: 'git add' them.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
