#!/usr/bin/env python3
"""Check that the documentation index lists exactly the documents that exist.

`docs/README.md` is the index. It points at every other document in the
directory, so a reader can find anything from one page.

Two ways that can go wrong, and both are silent:

  1. A document exists but the index does not mention it. Nothing breaks;
     the document is simply unreachable from the index. A reader who does
     not know the filename never finds it.

  2. The index lists a document that was renamed or deleted. The link
     breaks -- but `linkcheck.py` only proves the *link target* resolves,
     and a dead relative link inside a markdown table looks like any
     other link. It does catch that one, so case 2 is already covered.

What is NOT covered anywhere is case 1: the orphan document.

The rule this gate encodes: the index is the complete list of the
product documentation. Every `.md` under `docs/` is listed, and every
listed path exists.

The index also lists two documents that live OUTSIDE `docs/` (the
testcase support matrix and the testcase README). Those are checked as
"listed targets exist", and they are excluded from the "must be listed"
side -- the index is the index of `docs/`, not of the whole repository.
"""
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DOCS = REPO / "docs"
INDEX = DOCS / "README.md"

# A markdown link: [text](target)
LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")


def index_targets(text):
    """The relative paths the index links to."""
    out = []
    for m in LINK.finditer(text):
        raw = m.group(1).split("#")[0].strip()
        if not raw:
            continue
        if raw.startswith(("http://", "https://", "mailto:")):
            continue
        out.append(raw.replace("\\", "/"))
    return out


def doc_files():
    """Every markdown document under docs/, relative to docs/."""
    out = set()
    for p in DOCS.rglob("*.md"):
        if p == INDEX:
            continue
        out.add(p.relative_to(DOCS).as_posix())
    return out


def main():
    failures = []

    if not INDEX.is_file():
        print("FAIL: %s does not exist" % INDEX)
        return 1

    text = INDEX.read_text(encoding="utf-8", errors="replace")
    targets = index_targets(text)

    # (1) Every listed target must exist, relative to docs/.
    for target in targets:
        # The index links to the testcase tree with a `../` prefix.
        resolved = (DOCS / target).resolve()
        if not resolved.exists():
            failures.append("the index links to %s, which does not exist" % target)

    # (2) Every document under docs/ must be listed.
    #     A target is "a listing of X" when it ends with `/X`.
    listed = set()
    for target in targets:
        listed.add(target.rsplit("/", 1)[-1] if "/" in target else target)

    for rel in sorted(doc_files()):
        name = rel.rsplit("/", 1)[-1]
        if name not in listed:
            failures.append(
                "%s is not listed in docs/README.md -- a reader cannot find it "
                "from the index" % rel)

    if failures:
        print("FAIL: the documentation index does not match the documentation")
        for f in failures:
            print("  %s" % f)
        print()
        print("The index is the complete list of the product documentation.")
        print("Add the missing document, or remove it from the tree.")
        return 1

    print("  ok: the index lists all %d document(s), and every link resolves"
          % len(doc_files()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
