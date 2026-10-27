#!/usr/bin/env python3
"""Prove that the section-reference gate actually catches stale references.

A gate that has never failed is not known to work. This script makes
deliberate drift in the section references and requires the gate to fail
for each one.

The mutants:

    1. a document's section reference points at a section that does not
       exist in the target document
    2. a document's OWN section reference points at a section it does
       not define
    3. the target document loses the section the reference names
    4. the Chinese tree's reference drifts while the English one stays
    5. a reference that names a document without its extension stops
       resolving

Every mutation is applied to a byte-exact snapshot and restored from
memory afterwards -- never through `git checkout --`, which would
discard uncommitted work in a shared working tree.

Run:

    python scripts/mutate_section_refs.py
"""
import pathlib
import re
import shutil
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent
GATE = REPO / "scripts" / "check_section_refs.py"
DOCS = REPO / "docs"
ZH = DOCS / "zh"

sys.path.insert(0, str(REPO / "scripts"))


def run_gate():
    import subprocess
    proc = subprocess.run(
        [sys.executable, str(GATE)],
        cwd=str(REPO), capture_output=True, text=True, encoding="utf-8",
        errors="replace")
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


class Snapshot:
    """A byte-exact snapshot of a file, restorable without git."""

    def __init__(self, path):
        self.path = path
        self.existed = path.is_file()
        self.data = path.read_bytes() if self.existed else None

    def restore(self):
        if self.existed:
            self.path.write_bytes(self.data)
        elif self.path.exists():
            self.path.unlink()


# A document with a known cross-reference, and a section that exists.
SOURCE = DOCS / "tech" / "STYLE-RULES.md"
TARGET = DOCS / "rules" / "RULE-STYLE-OP.md"


def mutant_reference_to_missing_section(ctx):
    """A reference points at a section the target does not have."""
    snap = Snapshot(SOURCE)
    text = snap.data.decode("utf-8")
    # The reference to RULE-STYLE-OP §9.1 is real; point it at §99.9.
    m = re.search(r"§9\.1 of RULE-STYLE-OP", text)
    if not m:
        raise RuntimeError("the reference to RULE-STYLE-OP §9.1 is gone")
    new = text[:m.start()] + "§99.9 of RULE-STYLE-OP" + text[m.end():]
    snap.path.write_text(new, encoding="utf-8")
    return [snap], "a cross-document reference points at a missing section"


def mutant_self_reference_missing(ctx):
    """A document refers to a section of itself that it does not define."""
    snap = Snapshot(SOURCE)
    text = snap.data.decode("utf-8")
    m = re.search(r"§9\.1 of RULE-STYLE-OP", text)
    if not m:
        raise RuntimeError("the reference to RULE-STYLE-OP §9.1 is gone")
    new = text[:m.start()] + "§99.9 of this document" + text[m.end():]
    snap.path.write_text(new, encoding="utf-8")
    return [snap], "a document refers to a section of itself that it lacks"


def mutant_target_loses_section(ctx):
    """The target document loses the section the reference names."""
    snap = Snapshot(TARGET)
    text = snap.data.decode("utf-8")
    # Rename the "### 9.1 ..." heading so that §9.1 no longer exists.
    m = re.search(r"(?m)^### 9\.1 .+$", text)
    if not m:
        raise RuntimeError("the 9.1 heading is gone")
    new = text[:m.start()] + "### 9.1x " + text[m.start() + len("### 9.1 "):]
    snap.path.write_text(new, encoding="utf-8")
    return [snap], "the target document loses the section the reference names"


def mutant_chinese_drifts(ctx):
    """The Chinese tree's reference drifts while the English one stays."""
    snap = Snapshot(ZH / "tech" / "STYLE-RULES.md")
    text = snap.data.decode("utf-8")
    m = re.search(r"§9\.1", text)
    if not m:
        raise RuntimeError("the Chinese reference to §9.1 is gone")
    new = text[:m.start()] + "§99.9" + text[m.end():]
    snap.path.write_text(new, encoding="utf-8")
    return [snap], "the Chinese reference drifts while the English one stays"


def mutant_extension_less_name(ctx):
    """A reference names a document without its extension and stops
    resolving -- the shape the documentation uses most often."""
    snap = Snapshot(SOURCE)
    text = snap.data.decode("utf-8")
    m = re.search(r"§9\.1 of RULE-STYLE-OP", text)
    if not m:
        raise RuntimeError("the reference to RULE-STYLE-OP §9.1 is gone")
    new = text[:m.start()] + "§9.1 of RULE-STYLE-OP-NOPE" + text[m.end():]
    snap.path.write_text(new, encoding="utf-8")
    return [snap], "a reference names a document that does not exist"


MUTANTS = [
    ("reference-to-missing-section", mutant_reference_to_missing_section),
    ("self-reference-missing", mutant_self_reference_missing),
    ("target-loses-section", mutant_target_loses_section),
    ("chinese-drifts", mutant_chinese_drifts),
    ("extension-less-name", mutant_extension_less_name),
]


def main():
    failures = []

    code, out = run_gate()
    if code != 0:
        print("FAIL: the gate does not pass on the unmodified tree")
        print(out)
        return 1
    print("  ok: the gate passes on the unmodified tree")

    survivors = []
    for name, fn in MUTANTS:
        snaps = []
        try:
            snaps, description = fn(None)
        except RuntimeError as exc:
            # A mutation that could not be built proves nothing, and a
            # silently skipped mutation is a hole in the battery.
            print("  ERROR %-28s could not build the mutation: %s"
                  % (name, exc))
            survivors.append(name)
            continue

        code, out = run_gate()
        ok = code != 0
        print("  %s %-28s %s" % ("ok  " if ok else "SURV", name, description))
        if not ok:
            survivors.append(name)
            print(out)

        for snap in snaps:
            snap.restore()

    code, out = run_gate()
    if code != 0:
        print("FAIL: the tree was not restored -- the gate now fails")
        print(out)
        return 1

    if survivors:
        print()
        print("FAIL: %d mutant(s) survived: %s"
              % (len(survivors), ", ".join(survivors)))
        return 1

    print()
    print("  ok: all %d mutant(s) killed, and the tree was restored"
          % len(MUTANTS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
