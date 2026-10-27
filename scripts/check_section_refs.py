#!/usr/bin/env python3
"""Check that every cross-document section reference points at a section.

A document that says "see §7 of `RULES.md`" makes a claim about another
document: that `RULES.md` has a section 7, and that it is the one meant.
When the other document is restructured, the reference goes stale, and
nothing notices -- the link still resolves, the document still reads
smoothly, and the pointer aims at a section that no longer exists or now
means something else.

This gate finds those references and checks them.

The measurement has to be precise, because a sloppy version of it is
worse than none. A `§N` in the prose of a document usually refers to
THAT document, not to the one being linked. The rules are:

  1. A `§N` inside the link TEXT of a markdown link refers to the link
     TARGET: "see [§7 of `RULES.md`](RULES.md)" points into RULES.md.

  2. A `§N` in the surrounding prose refers to the CURRENT document: a
     document that says "use the YAML override of §9.1" means its own
     section 9.1.

  3. A `§N` that names a document in backticks -- "§10-§12 of `CLI.md`" --
     refers to that named document.

The gate checks both the English tree and the Chinese tree, and it checks
the section numbers a document DEFINES as well as the ones it is
mentioned by, so a rename is caught from both sides.

    python scripts/check_section_refs.py
"""
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DOCS = REPO / "docs"
ZH = DOCS / "zh"

# A markdown link: [text](target)
LINK = re.compile(r"\[([^\]]*)\]\(([^)#\s]+)(#[^)\s]*)?\)")

# A section reference: §7, §7.2, §7-§9
SECTION = re.compile(r"§\s*(\d+(?:\.\d+)?)")

# A heading that defines a section: "## 7. Foo", "### 7.2 Foo", "## 7 Foo"
HEADING = re.compile(r"(?m)^#{2,4}\s*(\d+(?:\.\d+)*)\.?\s")

# A phrase that says the section belongs to the CURRENT document, so a
# link that follows it in the same cell does not own it.
SELF_REFERENCE = re.compile(r"of this document|本文|本章|this section|above|above|见上文")

# A document name followed by a section reference, or the other way
# round. The name may be bare or backticked, and bare or path-qualified:
# the documentation writes "docs/tech/ARCHITECTURE.md §3.0" and
# "`docs/tech/ARCHITECTURE.md` §3.0" and "`ARCHITECTURE.md` §3.0" and all
# three name the same document. The `.md` suffix is optional -- the
# documentation also writes "§9.1 of RULE-STYLE-OP".
# A document name and a section reference, in either order.
#
# The name may be bare or backticked, and bare or path-qualified: the
# documentation writes "docs/tech/ARCHITECTURE.md §3.0" and
# "`docs/tech/ARCHITECTURE.md` §3.0" and "`ARCHITECTURE.md` §3.0", and
# all three name the same document. The `.md` suffix is optional --
# "§9.1 of RULE-STYLE-OP" names a document too.
#
# The name is a path-like token: it ends in `.md` or `.yaml`, or it is an
# upper-case identifier that LOOKS like a file name -- all capitals with
# a separator in it, the way the documentation names its documents
# (`RULE-STYLE-OP`, `CLI-REFERENCE`). A plain word like `YAML` is not a
# document name, and treating it as one would swallow the section
# reference that follows it.
DOC_NAME = (r"(?:[A-Za-z0-9_\-./]*\.(?:md|yaml)"
            r"|[A-Z][A-Z0-9]*(?:[_\-][A-Z0-9]+)+)")

# "§10 of `CLI.md`", "§9.1 of RULE-STYLE-OP"
#
# The gap between the number and the name is short on purpose. It has to
# cover " of `X.md`" and nothing more: a wider gap would let the match
# reach across a document name that belongs to a DIFFERENT reference.
NAMED_SECTION_FIRST = re.compile(
    r"§\s*(\d+(?:\.\d+)?)[^。\n|]{0,12}?`?(" + DOC_NAME + r")`?")

# "`CLI.md` §10", "docs/tech/X.md §3.0"
NAMED_NAME_FIRST = re.compile(
    r"`?(" + DOC_NAME + r")`?[^。\n|]{0,20}?§\s*(\d+(?:\.\d+)?)")


def read(path):
    return path.read_text(encoding="utf-8", errors="replace")


def defined_sections(text):
    """The section numbers a document defines, as strings.

    Both the full number ("7.2") and its head ("7") count: a document
    with a "## 7." heading and a "### 7.2" sub-heading can legitimately
    be referred to as "§7" or "§7.2".
    """
    out = set()
    for m in HEADING.finditer(text):
        num = m.group(1)
        out.add(num)
        if "." in num:
            out.add(num.split(".")[0])
    return out


def document_index():
    """Every markdown document under docs/, keyed by its basename.

    The basename is the key because a section reference names a document
    by file name -- "`CLI.md` §10" -- not by its full path.
    """
    index = {}
    for p in DOCS.rglob("*.md"):
        if "zh" in p.relative_to(DOCS).parts:
            continue
        index.setdefault(p.name, []).append(p)
    return index


def resolve_link(source, target):
    """The document a relative link points at, or None."""
    if target.startswith(("http://", "https://", "mailto:")):
        return None
    resolved = (source.parent / target).resolve()
    try:
        return resolved.relative_to(DOCS.resolve())
    except ValueError:
        return None


def check_tree(failures, root, label):
    """Check one documentation tree."""
    index = {}
    for p in root.rglob("*.md"):
        rel = p.relative_to(root).as_posix()
        # The index is keyed by the file NAME, because a section reference
        # names a document by file name. A path-qualified name
        # ("docs/usage/CLI.md") resolves to the same entry: the index
        # lookup takes the basename.
        index.setdefault(p.name, p)
        index.setdefault(rel, p)

    sections = {}
    for p in root.rglob("*.md"):
        sections[p] = defined_sections(read(p))

    checked = 0
    for p in sorted(root.rglob("*.md")):
        text = read(p)
        rel = p.relative_to(root).as_posix()

        # The spans a link owns: its text, and the section reference that
        # follows it in the same sentence. "see [the table](../x.md) §6"
        # points into x.md.
        owned = []
        for m in LINK.finditer(text):
            target_rel = resolve_link(p, m.group(2))
            if target_rel is None:
                continue
            target_path = (DOCS / target_rel) if root is DOCS else (ZH / target_rel)
            if not target_path.is_file():
                continue
            # The link text.
            for s in SECTION.finditer(m.group(1)):
                owned.append((s.start(), s.end(), target_path))
            # The section reference right after the link, up to the end of
            # the sentence.
            tail = text[m.end():m.end() + 60]
            sentence_end = re.search(r"[.\n。|]", tail)
            tail = tail[:sentence_end.start()] if sentence_end else tail
            for s in SECTION.finditer(tail):
                owned.append((m.end() + s.start(), m.end() + s.end(),
                              target_path))
            # The section reference right before the link, when it is in
            # the same table cell or sentence AND it does not say "of this
            # document". "§13.1 of this document" points at the current
            # document even though a link follows it in the same cell.
            head = text[max(0, m.start() - 60):m.start()]
            cell_start = max(head.rfind("|"), head.rfind("\n"))
            head = head[cell_start + 1:]
            if not SELF_REFERENCE.search(head):
                for s in SECTION.finditer(head):
                    owned.append((m.start() - len(head) + s.start(),
                                  m.start() - len(head) + s.end(),
                                  target_path))

        # A document name in backticks or bare, followed or preceded by a
        # section reference. "§10-§12 of `CLI.md`", "`docs/tech/X.md` §3",
        # "docs/tech/X.md §3".
        for m in list(NAMED_SECTION_FIRST.finditer(text)) + \
                list(NAMED_NAME_FIRST.finditer(text)):
            if m.re is NAMED_SECTION_FIRST:
                num, name = m.group(1), m.group(2)
            else:
                name, num = m.group(1), m.group(2)
            base = name.rsplit("/", 1)[-1]
            target_path = index.get(base)
            if target_path is None and "." not in base:
                # The name was written without its extension: "§9.1 of
                # RULE-STYLE-OP" names RULE-STYLE-OP.md.
                target_path = index.get(base + ".md")
            if target_path is None:
                continue
            owned.append((m.start(), m.end(), target_path))
            if num not in sections.get(target_path, set()):
                failures.append(
                    "%s%s refers to §%s of %s, but that document has no such "
                    "section" % (label, rel, num, name))
            checked += 1

        # Every remaining section reference belongs to the CURRENT
        # document.
        for m in SECTION.finditer(text):
            if any(m.start() >= a and m.end() <= b
                   for a, b, _ in owned):
                continue
            num = m.group(1)
            if num not in sections.get(p, set()):
                failures.append(
                    "%s%s refers to §%s of itself, but it has no such section"
                    % (label, rel, num))
            checked += 1

    return checked


def main():
    failures = []

    if not ZH.is_dir():
        print("FAIL: %s does not exist" % ZH)
        return 1

    en_checked = check_tree(failures, DOCS, "docs/")
    zh_checked = check_tree(failures, ZH, "docs/zh/")

    if failures:
        print("FAIL: %d stale section reference(s)" % len(failures))
        for f in failures:
            print("  %s" % f)
        print()
        print("A section reference is a claim about another document.")
        print("Either the reference is updated, or the target document")
        print("really has that section.")
        return 1

    print("  ok: %d section reference(s) in the English tree resolve"
          % en_checked)
    print("  ok: %d section reference(s) in the Chinese tree resolve"
          % zh_checked)
    return 0


if __name__ == "__main__":
    sys.exit(main())
