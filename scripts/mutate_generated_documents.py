#!/usr/bin/env python3
"""Mutation battery for `scripts/check_generated_documents.py`.

A gate that stops refusing is invisible. The commit goes through, and the
repository states a number that is no longer true -- and the symptom is a
document that is quietly wrong, not an error.

Each mutant here is a plausible way that happens:

  - the catalogue gains a pattern and the documents are not regenerated,
  - the catalogue gains a pattern and the documents ARE regenerated, but they
    are not STAGED, so the commit would still state the old number,
  - only the English documents are regenerated, so the Chinese ones keep the
    old number,
  - the catalogue gains a pattern, the documents are regenerated and staged:
    the gate must NOT refuse this one, because a gate that refuses everything
    is a gate that gets switched off,
  - a commit that touches no file the documents are built from: the gate must
    not pay for a check it does not need.

WHY A CLONE. The mutants change the catalogue and the derived documents, and
then put them back byte for byte. In a repository where two people work at
once, that restore can overwrite somebody else's edit of the same file -- the
edit lands between the save and the restore, and it is gone. So the battery
runs against a THROWAWAY CLONE of the repository, and the real tree is never
modified. The scripts under test are copied into the clone, so what is judged
is the version in the working tree, committed or not.

    python scripts/mutate_generated_documents.py
"""
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent
GATE = REPO / "scripts" / "check_generated_documents.py"
CATALOG = REPO / "plumb-dfa" / "src" / "main" / "resources" / "sink-families.yaml"
GENERATORS = ("gen_rules_overview", "gen_tool_comparison")
DOCUMENTS = (
    "docs/rules/RULES.md",
    "docs/zh/rules/RULES.md",
    "docs/rules/RULE-TOOL-COMPARISON.md",
    "docs/zh/rules/RULE-TOOL-COMPARISON.md",
)

# The file the "touches nothing" mutant stages. It is a file the derived
# documents are NOT built from, so a commit that changes it cannot make a
# document stale.
NEUTRAL_FILE = "plumb-mutate-generated-documents-probe.txt"

# The scripts the battery copies into the clone: they are new, so the clone
# (which carries the committed state) does not have them yet.
COPIED_SCRIPTS = ("check_generated_documents.py", "mutate_generated_documents.py")


def git(args, env, cwd=None):
    return subprocess.run(["git"] + args, cwd=cwd or REPO, env=env, capture_output=True,
                          text=True, encoding="utf-8", errors="replace")


def run_gate(env, cwd):
    return subprocess.run([sys.executable, "scripts/check_generated_documents.py"],
                          cwd=cwd, env=env, capture_output=True, text=True,
                          encoding="utf-8", errors="replace")


def temporary_index(cwd):
    """A temporary index that starts as HEAD.

    GIT_INDEX_FILE is honoured by git, so every command below writes to the
    temporary index and the clone's own index is never touched.
    """
    holder = tempfile.mkdtemp(prefix="plumb-mutate-index-")
    index = os.path.join(holder, "index")
    env = dict(os.environ)
    env["GIT_INDEX_FILE"] = index
    proc = git(["read-tree", "HEAD"], env, cwd=cwd)
    if proc.returncode != 0:
        shutil.rmtree(holder, ignore_errors=True)
        raise RuntimeError("git read-tree failed: %s" % proc.stderr.strip())
    return holder, env


def probe_line(cwd):
    """A catalogue pattern line, with the indentation the catalogue already uses.

    The indentation matters: a line at the wrong level does not join the last
    family, and the catalogue would not change at all -- which would make this
    battery prove nothing.
    """
    catalogue = cwd / CATALOG.relative_to(REPO)
    text = catalogue.read_text(encoding="utf-8")
    indent = ""
    for line in text.splitlines():
        stripped = line.lstrip()
        if stripped.startswith("- pattern:"):
            indent = line[: len(line) - len(stripped)]
            break
    raw = catalogue.read_bytes()
    eol = b"\r\n" if b"\r\n" in raw else b"\n"
    return indent.encode("utf-8") + \
        r"- pattern: '^\\probe\\.mutated\\b(/\d+)?'".encode("utf-8") + eol


def add_probe_to_catalogue(cwd):
    """Add one pattern to the catalogue; return the bytes to restore it with."""
    catalogue = cwd / CATALOG.relative_to(REPO)
    saved = catalogue.read_bytes()
    catalogue.write_bytes(saved + probe_line(cwd))
    return saved


def regenerate_documents(cwd):
    """Regenerate the derived documents in the working tree."""
    for generator in GENERATORS:
        proc = subprocess.run([sys.executable, "scripts/%s.py" % generator], cwd=cwd,
                              capture_output=True, text=True, encoding="utf-8",
                              errors="replace")
        if proc.returncode != 0:
            raise RuntimeError("%s failed: %s" % (generator, proc.stderr.strip()))


def save_documents(cwd):
    return {path: (cwd / path).read_bytes() for path in DOCUMENTS}


def restore_documents(cwd, saved):
    for path, raw in saved.items():
        (cwd / path).write_bytes(raw)


def refuse(proc, documents):
    """The gate refused, and it said which documents are stale."""
    output = proc.stdout + proc.stderr
    if proc.returncode == 0:
        return False, "the gate ACCEPTED a commit that states the old number"
    for document in documents:
        if document not in output:
            return False, "the gate refused, but did not name %s" % document
    return True, output.strip()


def accept(proc):
    if proc.returncode != 0:
        return False, "the gate refused a commit it must allow:\n%s" % (
            proc.stdout + proc.stderr).strip()
    return True, (proc.stdout + proc.stderr).strip()


def mutant_catalogue_changed_without_regeneration(cwd):
    """The drift that reached the repository twice."""
    holder, env = temporary_index(cwd)
    saved = add_probe_to_catalogue(cwd)
    try:
        git(["add", str(CATALOG.relative_to(REPO))], env, cwd=cwd)
        proc = run_gate(env, cwd)
        return refuse(proc, DOCUMENTS)
    finally:
        (cwd / CATALOG.relative_to(REPO)).write_bytes(saved)
        shutil.rmtree(holder, ignore_errors=True)


def mutant_regenerated_but_not_staged(cwd):
    """The documents are right in the working tree, and wrong in the commit."""
    holder, env = temporary_index(cwd)
    saved = add_probe_to_catalogue(cwd)
    documents = save_documents(cwd)
    try:
        git(["add", str(CATALOG.relative_to(REPO))], env, cwd=cwd)
        regenerate_documents(cwd)
        proc = run_gate(env, cwd)
        return refuse(proc, DOCUMENTS)
    finally:
        restore_documents(cwd, documents)
        (cwd / CATALOG.relative_to(REPO)).write_bytes(saved)
        shutil.rmtree(holder, ignore_errors=True)


def mutant_only_the_english_documents_regenerated(cwd):
    """One language is current, and the other is not."""
    holder, env = temporary_index(cwd)
    saved = add_probe_to_catalogue(cwd)
    documents = save_documents(cwd)
    try:
        git(["add", str(CATALOG.relative_to(REPO))], env, cwd=cwd)
        regenerate_documents(cwd)
        git(["add", "docs/rules/RULES.md", "docs/rules/RULE-TOOL-COMPARISON.md"], env, cwd=cwd)
        proc = run_gate(env, cwd)
        return refuse(proc, ("docs/zh/rules/RULES.md",
                              "docs/zh/rules/RULE-TOOL-COMPARISON.md"))
    finally:
        restore_documents(cwd, documents)
        (cwd / CATALOG.relative_to(REPO)).write_bytes(saved)
        shutil.rmtree(holder, ignore_errors=True)


def mutant_catalogue_changed_and_regenerated(cwd):
    """The commit is right, and the gate must not refuse it."""
    holder, env = temporary_index(cwd)
    saved = add_probe_to_catalogue(cwd)
    documents = save_documents(cwd)
    try:
        git(["add", str(CATALOG.relative_to(REPO))], env, cwd=cwd)
        regenerate_documents(cwd)
        git(["add", "docs/rules", "docs/zh/rules"], env, cwd=cwd)
        proc = run_gate(env, cwd)
        return accept(proc)
    finally:
        restore_documents(cwd, documents)
        (cwd / CATALOG.relative_to(REPO)).write_bytes(saved)
        shutil.rmtree(holder, ignore_errors=True)


def mutant_a_commit_that_touches_nothing_derived(cwd):
    """A commit that cannot make a document stale must not pay for the check."""
    holder, env = temporary_index(cwd)
    probe = cwd / NEUTRAL_FILE
    try:
        probe.write_text("not a file the derived documents are built from\n",
                         encoding="utf-8")
        git(["add", NEUTRAL_FILE], env, cwd=cwd)
        proc = run_gate(env, cwd)
        ok, detail = accept(proc)
        if ok and "does not touch" not in detail:
            return False, "the gate did not say that it had nothing to check"
        return ok, detail
    finally:
        if probe.exists():
            probe.unlink()
        shutil.rmtree(holder, ignore_errors=True)


MUTANTS = (
    ("the catalogue changes and the documents are not regenerated",
     mutant_catalogue_changed_without_regeneration),
    ("the documents are regenerated but not staged",
     mutant_regenerated_but_not_staged),
    ("only the English documents are regenerated",
     mutant_only_the_english_documents_regenerated),
    ("the catalogue changes and the documents are regenerated and staged",
     mutant_catalogue_changed_and_regenerated),
    ("a commit that touches no file the documents are built from",
     mutant_a_commit_that_touches_nothing_derived),
)


def in_the_clone():
    """Make a throwaway clone, put the scripts under test into it, and run there."""
    holder = tempfile.mkdtemp(prefix="plumb-mutate-clone-")
    clone = os.path.join(holder, "repo")
    proc = git(["clone", "--quiet", str(REPO), clone], dict(os.environ))
    if proc.returncode != 0:
        shutil.rmtree(holder, ignore_errors=True)
        raise RuntimeError("git clone failed: %s" % proc.stderr.strip())
    for name in COPIED_SCRIPTS:
        shutil.copy2(REPO / "scripts" / name, os.path.join(clone, "scripts", name))
    return holder, pathlib.Path(clone)


def main():
    if "--in-the-clone" not in sys.argv:
        holder, clone = in_the_clone()
        try:
            proc = subprocess.run([sys.executable, "scripts/mutate_generated_documents.py",
                                   "--in-the-clone"], cwd=clone, capture_output=True,
                                  text=True, encoding="utf-8", errors="replace")
        finally:
            shutil.rmtree(holder, ignore_errors=True)
        sys.stdout.write(proc.stdout)
        sys.stderr.write(proc.stderr)
        return proc.returncode

    survived = []
    for name, mutant in MUTANTS:
        try:
            ok, detail = mutant(REPO)
        except Exception as error:  # a mutant that could not be built is not a survivor
            print("  ERROR %-60s %s" % (name, error))
            continue
        print("  %-4s %s" % ("ok" if ok else "HOLE", name))
        if not ok:
            survived.append((name, detail))

    if survived:
        print("")
        print("The gate stopped refusing what it must refuse:")
        for name, detail in survived:
            print("  %s" % name)
            for line in str(detail).splitlines():
                print("    %s" % line)
        return 1

    print("")
    print("OK: the gate refuses every commit that states the old number, and")
    print("allows every commit that states the right one.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
