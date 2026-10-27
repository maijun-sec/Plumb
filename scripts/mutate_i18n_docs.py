#!/usr/bin/env python3
"""Prove that the i18n documentation gate actually catches drift.

A gate that has never failed is not known to work. This script mutates
the documentation tree in a series of ways that each model a plausible
real defect, runs the gate, and requires the gate to fail. A mutant that
survives means the gate is checking air.

Every mutation is applied to a byte-exact copy of the original file and
restored from an in-memory binary snapshot afterwards -- never through
`git checkout --`, which would silently discard uncommitted work in a
shared working tree.

The mutants:

    1. delete a Chinese document            -> orphaned Chinese page
    2. delete an English document           -> orphaned English page
    3. add a Chinese document with no peer  -> spurious Chinese page
    4. remove a heading from the Chinese    -> structural divergence
    5. change a heading level               -> structural divergence
    6. remove a table row from the Chinese  -> dropped information
    7. remove a code block from the Chinese -> dropped example
    8. break a relative link in the Chinese -> dead link
    9. break a relative link in the English -> dead link
   10. remove the language link from English
   11. remove the language link from Chinese
   12. add a Chinese copy of a generated doc -> generated file must not be
                                              translated

Run:

    python scripts/mutate_i18n_docs.py
"""
import pathlib
import re
import shutil
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parent.parent
GATE = REPO / "scripts" / "check_i18n_docs.py"
DOCS = REPO / "docs"
ZH = DOCS / "zh"

sys.path.insert(0, str(REPO / "scripts"))


def run_gate():
    """Run the gate, return (exit_code, output)."""
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
        self.data = path.read_bytes() if path.is_file() else None
        self.existed = path.is_file()

    def restore(self):
        if self.existed:
            self.path.write_bytes(self.data)
        elif self.path.exists():
            self.path.unlink()


def unique_anchor(text, pattern):
    """Find exactly one match, or fail loudly.

    A non-unique anchor means the mutation would hit the wrong place and
    the mutant would be testing something other than what it claims.
    """
    matches = list(re.finditer(pattern, text, flags=re.MULTILINE))
    if len(matches) != 1:
        raise RuntimeError(
            "anchor %r matched %d times, expected exactly 1"
            % (pattern, len(matches)))
    return matches[0]


# --- the mutants -------------------------------------------------------

def mutant_delete_chinese(ctx):
    """A Chinese page that lost its English peer."""
    target = ZH / "usage" / "ENVIRONMENT.md"
    snap = Snapshot(target)
    target.unlink()
    return [snap], "docs/zh/usage/ENVIRONMENT.md deleted"


def mutant_delete_english(ctx):
    """An English page that lost its Chinese peer."""
    target = DOCS / "usage" / "ENVIRONMENT.md"
    snap = Snapshot(target)
    target.unlink()
    return [snap], "docs/usage/ENVIRONMENT.md deleted"


def mutant_extra_chinese(ctx):
    """A Chinese page with no English counterpart at all."""
    target = ZH / "usage" / "ORPHAN.md"
    target.parent.mkdir(parents=True, exist_ok=True)
    snap = Snapshot(target)
    target.write_text("# orphan\n\nno english peer\n", encoding="utf-8")
    return [snap], "docs/zh/usage/ORPHAN.md added"


def mutant_drop_heading(ctx):
    """The Chinese page lost a section the English page has."""
    target = ZH / "usage" / "ENVIRONMENT.md"
    snap = Snapshot(target)
    text = target.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    # Find the first line that is a level-2 heading.
    idx = None
    for i, line in enumerate(lines):
        if re.match(r"^## .+$", line):
            idx = i
            break
    if idx is None:
        raise RuntimeError("no level-2 heading found")
    # Remove the heading and the blank line after it.
    del lines[idx:idx + 2]
    target.write_text("".join(lines), encoding="utf-8")
    return [snap], "a heading removed from the Chinese document"


def mutant_change_heading_level(ctx):
    """The Chinese page has a section at the wrong depth."""
    target = ZH / "usage" / "ENVIRONMENT.md"
    snap = Snapshot(target)
    text = target.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    # Find the first line that is a level-2 heading.
    idx = None
    for i, line in enumerate(lines):
        if re.match(r"^## .+$", line):
            idx = i
            break
    if idx is None:
        raise RuntimeError("no level-2 heading found")
    lines[idx] = "### " + lines[idx][3:]
    target.write_text("".join(lines), encoding="utf-8")
    return [snap], "a heading level changed in the Chinese document"


def mutant_drop_table_row(ctx):
    """The Chinese table is missing a row the English table has."""
    target = ZH / "usage" / "ENVIRONMENT.md"
    snap = Snapshot(target)
    text = target.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    # Find a data row (a table row that is not the separator).
    idx = None
    for i, line in enumerate(lines):
        if line.strip().startswith("|") and not re.match(
                r"^\s*\|[\s:|-]+\|\s*$", line):
            idx = i
            break
    if idx is None:
        raise RuntimeError("no table row found to remove")
    del lines[idx]
    target.write_text("".join(lines), encoding="utf-8")
    return [snap], "a table row removed from the Chinese document"


def mutant_drop_code_block(ctx):
    """The Chinese page lost an example the English page has."""
    target = ZH / "usage" / "ENVIRONMENT.md"
    snap = Snapshot(target)
    text = target.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    start = None
    for i, line in enumerate(lines):
        if line.strip().startswith("```"):
            start = i
            break
    if start is None:
        raise RuntimeError("no code block found to remove")
    end = None
    for j in range(start + 1, len(lines)):
        if lines[j].strip().startswith("```"):
            end = j
            break
    if end is None:
        raise RuntimeError("unterminated code block")
    del lines[start:end + 1]
    target.write_text("".join(lines), encoding="utf-8")
    return [snap], "a code block removed from the Chinese document"


def _break_a_content_link(text, excluded):
    """Point the first NON-language markdown link at a missing target.

    The language link must be left alone: breaking it would test the
    language-switch check rather than the link check, and two mutants
    testing the same defect would hide a hole in the battery.
    """
    for m in re.finditer(r"\]\(([^)]+\.md)\)", text):
        target = m.group(1)
        if any(bad in target for bad in excluded):
            continue
        return text[:m.start(1)] + "../tech/NO-SUCH-DOC.md" + text[m.end(1):]
    raise RuntimeError("no content link found to break")


def mutant_break_zh_link(ctx):
    """A Chinese page links to a target that does not exist."""
    target = ZH / "usage" / "ENVIRONMENT.md"
    snap = Snapshot(target)
    text = target.read_text(encoding="utf-8")
    new = _break_a_content_link(text, excluded=["ENVIRONMENT.md"])
    target.write_text(new, encoding="utf-8")
    return [snap], "a relative link in the Chinese document broken"


def mutant_break_en_link(ctx):
    """An English page links to a target that does not exist."""
    target = DOCS / "usage" / "ENVIRONMENT.md"
    snap = Snapshot(target)
    text = target.read_text(encoding="utf-8")
    new = _break_a_content_link(text, excluded=["ENVIRONMENT.md"])
    target.write_text(new, encoding="utf-8")
    return [snap], "a relative link in the English document broken"


def mutant_drop_en_language_link(ctx):
    """The English page no longer offers the Chinese version."""
    target = DOCS / "usage" / "ENVIRONMENT.md"
    snap = Snapshot(target)
    text = target.read_text(encoding="utf-8")
    if "./zh/usage/ENVIRONMENT.md" not in text:
        raise RuntimeError("the language link is not there to remove")
    new = text.replace("./zh/usage/ENVIRONMENT.md", "./zh/NOPE.md")
    target.write_text(new, encoding="utf-8")
    return [snap], "the language link removed from the English document"


def mutant_drop_zh_language_link(ctx):
    """The Chinese page no longer offers the English version."""
    target = ZH / "usage" / "ENVIRONMENT.md"
    snap = Snapshot(target)
    text = target.read_text(encoding="utf-8")
    m = unique_anchor(text, r"\]\((\.\./[^)]*ENVIRONMENT\.md)\)")
    new = text[:m.start(1)] + "../usage/NOPE.md" + text[m.end(1):]
    target.write_text(new, encoding="utf-8")
    return [snap], "the language link removed from the Chinese document"


def mutant_translate_generated(ctx):
    """A Chinese copy of a GENERATED document appears."""
    target = ZH / "plumb-style-rules-index.md"
    snap = Snapshot(target)
    target.write_text("# generated\n\nmust not be translated\n", encoding="utf-8")
    return [snap], "a Chinese copy of a generated document added"


MUTANTS = [
    ("delete-chinese", mutant_delete_chinese),
    ("delete-english", mutant_delete_english),
    ("extra-chinese", mutant_extra_chinese),
    ("drop-heading", mutant_drop_heading),
    ("change-heading-level", mutant_change_heading_level),
    ("drop-table-row", mutant_drop_table_row),
    ("drop-code-block", mutant_drop_code_block),
    ("break-zh-link", mutant_break_zh_link),
    ("break-en-link", mutant_break_en_link),
    ("drop-en-language-link", mutant_drop_en_language_link),
    ("drop-zh-language-link", mutant_drop_zh_language_link),
    ("translate-generated", mutant_translate_generated),
]


def main():
    if not ZH.is_dir():
        print("FAIL: docs/zh/ does not exist")
        return 1

    # The baseline must pass, or the mutants prove nothing.
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
            # The mutation could not be built. That is NOT a surviving
            # mutant -- it is a broken test, and it must be reported as
            # such, because a silently skipped mutation is a hole in the
            # battery.
            print("  ERROR %-26s could not build the mutation: %s"
                  % (name, exc))
            survivors.append(name)
            continue

        code, out = run_gate()
        ok = code != 0
        print("  %s %-26s %s" % ("ok  " if ok else "SURV", name, description))
        if not ok:
            survivors.append(name)
            print(out)

        for snap in snaps:
            snap.restore()

    # Everything must be restored; a battery that leaves the tree dirty
    # corrupts every later run.
    code, out = run_gate()
    if code != 0:
        print("FAIL: the tree was not restored -- the gate now fails")
        print(out)
        return 1

    if survivors:
        print()
        print("FAIL: %d mutant(s) survived: %s"
              % (len(survivors), ", ".join(survivors)))
        print("A surviving mutant means the gate does not check that defect.")
        return 1

    print()
    print("  ok: all %d mutant(s) killed, and the tree was restored"
          % len(MUTANTS))
    return 0


if __name__ == "__main__":
    sys.exit(main())
