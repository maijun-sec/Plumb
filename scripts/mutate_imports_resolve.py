#!/usr/bin/env python3
"""Mutation battery for `scripts/check_imports_resolve.py`.

The gate answers "does every tracked file's import resolve inside the
tree". A gate like that drifts by *stopping to check*, and the ways it
can stop are all about what it reads:

  - a tracked file imports a package the tree does not have,
  - a NEW tracked file imports a package the tree does not have,
  - the import is one level deeper than a package that exists, so the
    prefix matches but the package does not,
  - the gate stops reading the commit and reads the working tree, where
    the violation does not exist.

Each mutant is a COMMIT, built from HEAD's tree with one file changed.
The commit is written with `git commit-tree` against a TEMPORARY index,
so the repository's own index and working tree are never touched: the
mutant exists only as an object, and the gate is pointed at it with
`--ref`.

The last mutant is the one that matters most for this gate: the
violation exists in the commit and NOT in the working tree, so a gate
that reads the checkout instead of the commit passes it. That is the
hole the gate is built to close, and the battery reports it if it
reopens.

    python scripts/mutate_imports_resolve.py
"""
import os
import pathlib
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent
GATE = REPO / "scripts" / "check_imports_resolve.py"
PYTHON = sys.executable

# A tracked file that exists at HEAD, and a package that does not.
VICTIM = "plumb-dfa/src/main/java/org/plumb/dfa/summary/FunctionSummary.java"
MISSING_PACKAGE = "org.plumb.dfa.phantom"
NEW_FILE = "plumb-dfa/src/main/java/org/plumb/dfa/summary/Phantom.java"


def git(*args, index=None, stdin=None):
    """Run a git command, optionally against a temporary index."""
    env = dict(os.environ)
    if index is not None:
        env["GIT_INDEX_FILE"] = str(index)
    result = subprocess.run(
        ["git", "-C", str(REPO), *args],
        input=stdin,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"git {' '.join(args)} failed: {result.stderr.decode('utf-8', 'replace')}"
        )
    return result.stdout.decode("utf-8", "replace")


def run_gate(ref):
    """Run the gate against a commit; return (exit code, output)."""
    proc = subprocess.run(
        [PYTHON, str(GATE), "--ref", ref],
        capture_output=True,
        text=True,
        cwd=str(REPO),
        encoding="utf-8",
        errors="replace",
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def build_commit(index, changes):
    """Build a commit from HEAD's tree with the given changes applied.

    `changes` maps a path to its new content, or to None for "remove it".
    """
    git("read-tree", "HEAD", index=index)
    for path, content in changes.items():
        if content is None:
            git("update-index", "--force-remove", path, index=index)
            continue
        blob = git("hash-object", "-w", "--stdin", stdin=content.encode("utf-8")).strip()
        git("update-index", "--add", "--cacheinfo", f"100644,{blob},{path}", index=index)
    tree = git("write-tree", index=index).strip()
    return git("commit-tree", tree, "-p", "HEAD", "-m", "mutant").strip()


def victim_content():
    """The tracked file's current content at HEAD."""
    return git("show", f"HEAD:{VICTIM}")


def mutants():
    """The (name, changes) pairs: each a commit the gate must reject."""
    original = victim_content()

    with_bogus_import = original.replace(
        "package org.plumb.dfa.summary;",
        "package org.plumb.dfa.summary;\n\nimport org.plumb.dfa.phantom.Missing;",
        1,
    )
    deeper = original.replace(
        "package org.plumb.dfa.summary;",
        "package org.plumb.dfa.summary;\n\nimport org.plumb.dfa.summary.nosuch.Missing;",
        1,
    )
    new_file = (
        "package org.plumb.dfa.summary;\n\n"
        "import org.plumb.dfa.phantom.Missing;\n\n"
        "final class Phantom {}\n"
    )
    return [
        ("a-tracked-file-imports-a-package-the-tree-lacks",
         {VICTIM: with_bogus_import}),
        ("a-new-tracked-file-imports-a-package-the-tree-lacks",
         {NEW_FILE: new_file}),
        ("an-import-names-a-package-one-level-deeper-than-any",
         {VICTIM: deeper}),
    ]


def main():
    # The control: the gate must PASS on the real tree, or "the gate
    # fails" proves nothing.
    code, output = run_gate("HEAD")
    if code != 0:
        print("CONTROL FAILED: the gate rejects HEAD, so it cannot prove anything.")
        print(output)
        return 1
    print("control: the gate accepts HEAD")

    with tempfile.TemporaryDirectory() as tmp:
        index = pathlib.Path(tmp) / "mutant-index"
        survivors = []
        for name, changes in mutants():
            commit = build_commit(index, changes)
            code, output = run_gate(commit)
            caught = code != 0
            print(f"{'caught ' if caught else 'SURVIVED'}  {name}")
            if not caught:
                survivors.append(name)
                print(output)

    print()
    if survivors:
        print(f"{len(survivors)} mutant(s) survived: the gate is not checking:")
        for name in survivors:
            print(f"  {name}")
        return 1
    print("OK: every mutant was caught")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
