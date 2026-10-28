#!/usr/bin/env python3
"""Mutation battery for `scripts/check_dataflow_claims.py`.

A gate that has never killed a mutant is a gate nobody knows works.
This script applies each plausible shipped failure mode to the
document (and to the source), runs the gate, and requires the gate to
FAIL. A mutant that survives is a hole in the gate.

Every mutant:
  1. is applied to an in-memory BYTE snapshot,
  2. is restored byte-exactly afterwards,
  3. distinguishes "could not build the mutant" from "the mutant
     survived".

    python scripts/mutate_dataflow_claims.py
"""
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent
GATE = REPO / "scripts" / "check_dataflow_claims.py"
DOC = REPO / "docs" / "tech" / "DATA-FLOW.md"
ZH_DOC = REPO / "docs" / "zh" / "tech" / "DATA-FLOW.md"
PYTHON = sys.executable


def run_gate():
    proc = subprocess.run([PYTHON, str(GATE)], capture_output=True, text=True,
                          cwd=str(REPO))
    return proc.returncode, proc.stdout + proc.stderr


# (name, file, the exact text to find, the text to replace it with)
#
# Each anchor must be UNIQUE in its file. A non-unique anchor fails
# loudly rather than mutating the wrong line.
MUTANTS = [
    # --- a stale line citation -------------------------------------
    # --- a stale line citation ---------------------------------------
    #
    # The anchors for these are DERIVED from the document at run
    # time, not hard-coded. A hard-coded line number is a second
    # source of truth: when the source file grows -- or when the
    # licence header moves every line -- the anchor stops matching,
    # the battery reports "could not build", and that says NOTHING
    # about the gate. See derive_citation_mutants() below.

    # --- a class that does not exist --------------------------------
    ("missing-class",
     DOC,
     "`engine/DfaEngine.java`",
     "`engine/DfaEngineTypo.java`"),
    ("missing-control-flow-checker",
     DOC,
     "`XxeChecker`",
     "`XxeCheckerTypo`"),

    # --- a wrong count ----------------------------------------------
    ("wrong-fact-kind-count",
     DOC,
     "registers **15** built-in kinds",
     "registers **14** built-in kinds"),
    ("wrong-checker-count",
     DOC,
     "There are 16 classes under `checkers/controlflow/`",
     "There are 17 classes under `checkers/controlflow/`"),
    ("wrong-binding-annotation-count",
     DOC,
     "with **30** binding annotations",
     "with **31** binding annotations"),
    ("wrong-tainted-subclass-count",
     DOC,
     "there are 47 `extends TaintedCheckerBase`",
     "there are 48 `extends TaintedCheckerBase`"),

    # --- a fact-kind row that names a kind that does not exist ------
    ("phantom-fact-kind",
     DOC,
     "| `plumb.nullable.may-be-null` | `MAY_BE_NULL` |",
     "| `plumb.nullable.maybe-null` | `MAY_BE_NULL` |"),

    # --- a call-graph strategy the factory does not accept ----------
    ("unknown-call-graph-strategy",
     DOC,
     "`vta`",
     "`vtaX`"),

    # --- a documented absence that now exists -----------------------
    #
    # The document says `TaintCatalog` does not exist. Making the
    # document name it as if it did must fail: the gate checks the
    # ABSENCE, so the mutation has to remove the absence claim.
    ("documented-absence",
     DOC,
     "there is no `TaintCatalog`",
     "there is a `TaintCatalog`"),
]


def derive_z3_citation_mutant():
    """Build the Z3 call-site mutant FROM the document, not from a copied number.

    A hard-coded anchor is a second source of truth: when the source file
    grows and the citation moves, the anchor stops matching and the battery
    reports "could not build" -- which says nothing about the gate.  So the
    anchor is read out of the document at run time, and only the REPLACEMENT
    is hard-coded.

    Returns None when the document does not carry the citation at all, which
    is itself a defect the gate should catch -- so the caller reports it.
    """
    text = DOC.read_text(encoding="utf-8")
    match = re.search(r"TaintedCheckerBase\.java:(\d+)", text)
    if not match:
        return None
    return (
        "stale-citation-z3-call-site",
        DOC,
        "TaintedCheckerBase.java:%s" % match.group(1),
        "TaintedCheckerBase.java:3000",
    )


def derive_citation_mutants():
    """Build the stale-citation mutants FROM the document.

    A hard-coded anchor is a second source of truth: when the source
    file grows -- or when the licence header moves every line down --
    the anchor stops matching, the battery reports "could not build",
    and that says nothing about the gate. So each anchor is read out
    of the document at run time, and only the REPLACEMENT is
    hard-coded.

    Each mutant moves ONE citation to a line that is definitely
    wrong, so the gate has to notice that the citation no longer
    lands on the declaration.
    """
    text = DOC.read_text(encoding="utf-8")
    out = []

    # (name, a regular expression that finds the citation)
    shapes = [
        ("stale-citation-method",
         r"(The real scheduling happens in `runWorklist` \(`:(\d+)`\))"),
        ("stale-citation-field",
         r"(- `nullFields` `:(\d+)`)"),
        ("stale-citation-fact-kind",
         r"(\| `plumb\.call\.unmodeled-callee-ret` \| `UNMODELED_CALLEE_RET`"
         r" \| `:(\d+)` \|)"),
        ("stale-citation-range",
         r"(\(`points/AbstractObject\.java:(\d+)-(\d+)`\))"),
    ]

    for name, pattern in shapes:
        m = re.search(pattern, text)
        if not m:
            # The document does not carry the citation in that shape
            # any more. That is itself a defect the gate should
            # catch, so the caller reports it.
            out.append((name, None, None, None))
            continue
        whole = m.group(1)
        if name == "stale-citation-range":
            # A range: move the WHOLE range to a wrong place.
            start = int(m.group(2))
            end = int(m.group(3))
            new = whole.replace("%d-%d" % (start, end),
                                "%d-%d" % (start + 900, end + 900))
        else:
            num = int(m.group(2))
            new = whole.replace(":%d" % num, ":%d" % (num + 900))
        out.append((name, whole, new, None))

    return out


def main():
    if not DOC.is_file():
        print("FAIL: %s does not exist" % DOC)
        return 1

    # The baseline must pass, or the battery proves nothing.
    code, out = run_gate()
    if code != 0:
        print("FAIL: the gate does not pass on the current tree")
        print(out)
        return 1

    killed = 0
    survived = []
    broken = []

    # The Z3 call-site citation is DERIVED from the document, so it cannot
    # drift away from the document it is supposed to be checking.
    mutants = list(MUTANTS)
    derived = derive_z3_citation_mutant()
    if derived is None:
        print("FAIL: the document does not cite a TaintedCheckerBase.java line, "
              "so the Z3 call-site citation cannot be mutated")
        return 1
    mutants.append(derived)

    # The stale-citation mutants are derived the same way: their
    # anchors are read out of the document, so a line number that
    # moves does not turn into a "could not build" that says nothing
    # about the gate.
    for name, old, new, _ in derive_citation_mutants():
        if old is None:
            print("FAIL: %s -- the document does not carry the citation in "
                  "the shape the mutant needs" % name)
            return 1
        mutants.append((name, DOC, old, new))

    for name, target, old, new in mutants:
        if not target.is_file():
            print("FAIL: %s -- %s does not exist" % (name, target))
            return 1
        original = target.read_bytes()
        text = original.decode("utf-8")
        count = text.count(old)
        if count != 1:
            print("FAIL: %s -- the anchor occurs %d times, expected exactly 1"
                  % (name, count))
            broken.append(name)
            continue
        try:
            target.write_bytes(text.replace(old, new).encode("utf-8"))
            code, out = run_gate()
        finally:
            # Restore the EXACT bytes, not a git checkout: the working
            # tree may hold uncommitted work that a checkout would
            # discard.
            target.write_bytes(original)

        if code == 0:
            survived.append(name)
            print("SURVIVED  %s" % name)
        else:
            killed += 1
            print("killed    %s" % name)

    print()
    print("%d/%d mutant(s) killed" % (killed, len(mutants)))
    if broken:
        print("could not build: %s" % ", ".join(broken))
        return 1
    if survived:
        print("survived: %s" % ", ".join(survived))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
