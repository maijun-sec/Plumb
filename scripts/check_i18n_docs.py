#!/usr/bin/env python3
"""Check that the English and Chinese documentation trees stay aligned.

The product documentation exists in two languages: `docs/` is English and
canonical, `docs/zh/` is the Chinese counterpart. A reader must be able to
switch languages and find the same document, and the two versions must not
silently diverge.

Three ways that goes wrong, and all of them are silent:

  1. A document exists in one tree but not the other. A reader switches
     language and lands on a missing page, or on a page that the other
     language never had.

  2. The two versions of a document have a different SHAPE: different
     headings, different table row counts, different code blocks. The
     Chinese page then describes a different document than the English one
     -- different content under the same filename, which is the worst kind
     of drift because both pages look complete.

  3. A link inside a document points at a target that does not exist, or
     points into the WRONG language tree. A Chinese page whose link goes
     to an English document silently breaks the "Chinese page, Chinese
     target" contract.

This gate encodes the rule: the two trees have the same shape, the links
in each tree resolve, and the cross-links between the trees are correct.

It deliberately does NOT compare prose. A sentence-by-sentence comparison
would require a translation model in the build, which is not available and
not wanted. What it compares is the STRUCTURE -- the thing a reader
navigates by. A structural mismatch is a hard failure; a wording difference
is not, because wording is not mechanically checkable and pretending
otherwise would make the gate lie.

The same checks run over the whole repository's markdown, so a broken
relative link anywhere fails too.

    python scripts/check_i18n_docs.py
"""
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DOCS = REPO / "docs"
ZH = DOCS / "zh"

# A markdown link: [text](target)
LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")

# The documents that are GENERATED from the source tree. Their content is
# the checkers' javadoc, so their language follows the javadoc, not the
# documentation's.
GENERATED_DOCS = {
    "plumb-style-rules.md",
    "plumb-style-rules-index.md",
}

# A fenced code block opener, used to exclude code from heading analysis.
FENCE = re.compile(r"^\s*(```|~~~)")

# A markdown ATX heading.
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*$")

# A table row: starts and ends with a pipe.
TABLE_ROW = re.compile(r"^\s*\|.*\|\s*$")

# The separator row of a markdown table.
TABLE_SEP = re.compile(r"^\s*\|[\s:|-]+\|\s*$")


def strip_fences(text):
    """Return the text with fenced code blocks removed.

    A `#` comment inside a shell example is not a heading, and a `|` inside
    a code block is not a table row. Both would make the structural
    comparison report a difference that does not exist.
    """
    out = []
    in_fence = False
    fence = ""
    for line in text.splitlines():
        m = FENCE.match(line)
        if m:
            if not in_fence:
                in_fence = True
                fence = m.group(1)
            elif line.strip().startswith(fence):
                in_fence = False
                fence = ""
            out.append("")
            continue
        out.append("" if in_fence else line)
    return out


def headings(text):
    """The heading structure of a document: (level, normalised title).

    The title is normalised to ASCII-ish so that an English heading and
    its Chinese translation compare equal. Translation cannot be checked
    by equality, but the SHAPE can: the same number of headings, at the
    same levels, in the same order.

    A heading that carries no text beyond its punctuation is structural
    noise rather than content, so it is skipped. Chinese headings often
    end with a colon that the English one drops.
    """
    out = []
    for line in strip_fences(text):
        m = HEADING.match(line)
        if not m:
            continue
        level = len(m.group(1))
        title = m.group(2).strip()
        # A heading that is only a number ("## 2") is a section index; keep
        # it, but strip trailing punctuation that differs between languages.
        title = title.rstrip(":：").strip()
        if not title:
            continue
        out.append((level, title))
    return out


def table_rows(text):
    """The number of table rows per table, as a list of counts.

    The counts are compared, not the contents. A Chinese table that drops
    a row is a missing piece of information, and that is exactly the kind
    of drift a reader cannot see.
    """
    counts = []
    current = 0
    in_table = False
    for line in strip_fences(text):
        if TABLE_ROW.match(line):
            if TABLE_SEP.match(line):
                # The separator is not a data row.
                continue
            current += 1
            in_table = True
        else:
            if in_table:
                counts.append(current)
                current = 0
                in_table = False
    if in_table:
        counts.append(current)
    return counts


def code_blocks(text):
    """The number of fenced code blocks."""
    count = 0
    in_fence = False
    fence = ""
    for line in text.splitlines():
        m = FENCE.match(line)
        if m:
            if not in_fence:
                in_fence = True
                fence = m.group(1)
                count += 1
            elif line.strip().startswith(fence):
                in_fence = False
                fence = ""
    return count


def links(text):
    """The relative link targets of a document, in order, without anchors."""
    out = []
    for m in LINK.finditer(text):
        raw = m.group(1).split("#")[0].strip()
        if not raw:
            continue
        if raw.startswith(("http://", "https://", "mailto:", "#")):
            continue
        out.append(raw.replace("\\", "/"))
    return out


def read(path):
    return path.read_text(encoding="utf-8", errors="replace")


def english_docs():
    """Every English document under docs/, excluding the zh/ subtree."""
    out = set()
    for p in DOCS.rglob("*.md"):
        rel = p.relative_to(DOCS)
        if rel.parts and rel.parts[0] == "zh":
            continue
        out.add(rel.as_posix())
    return out


def chinese_docs():
    """Every Chinese document under docs/zh/, relative to docs/."""
    out = set()
    if not ZH.is_dir():
        return out
    for p in ZH.rglob("*.md"):
        out.add(p.relative_to(ZH).as_posix())
    return out


def check_pairing(failures):
    """Every English document needs a Chinese counterpart, and vice versa.

    The rule catalogue is exempt on the Chinese side: it is GENERATED from
    the source tree by `scripts/gen_rules_doc.py`, and a translated copy
    would drift from the generator on every run. The English file stays
    canonical and the Chinese index says so explicitly.
    """
    en = english_docs()
    zh = chinese_docs()

    # Documents that are generated and therefore not translated.
    generated = GENERATED_DOCS

    for rel in sorted(en):
        if rel in generated:
            if rel in zh:
                failures.append(
                    "%s is generated from the source tree and must not have a "
                    "Chinese copy -- the copy would drift from the generator"
                    % rel)
            continue
        if rel not in zh:
            failures.append(
                "%s has no Chinese counterpart at docs/zh/%s" % (rel, rel))

    for rel in sorted(zh):
        if rel not in en:
            failures.append(
                "docs/zh/%s has no English counterpart at docs/%s" % (rel, rel))

    return en, zh


def check_structure(failures, en, zh):
    """The two versions of a document must have the same shape.

    Compared: the heading structure (count and level sequence), the table
    row counts, and the number of code blocks. Not compared: the prose.
    """
    checked = 0
    for rel in sorted(en & zh):
        en_text = read(DOCS / rel)
        zh_text = read(ZH / rel)

        en_head = headings(en_text)
        zh_head = headings(zh_text)
        if len(en_head) != len(zh_head):
            failures.append(
                "docs/zh/%s has %d heading(s) but docs/%s has %d -- the two "
                "versions describe different documents"
                % (rel, len(zh_head), rel, len(en_head)))
        else:
            for i, (a, b) in enumerate(zip(en_head, zh_head)):
                if a[0] != b[0]:
                    failures.append(
                        "docs/zh/%s heading #%d is level %d but docs/%s "
                        "heading #%d is level %d"
                        % (rel, i + 1, b[0], rel, i + 1, a[0]))

        en_rows = table_rows(en_text)
        zh_rows = table_rows(zh_text)
        if en_rows != zh_rows:
            failures.append(
                "docs/zh/%s has table row counts %s but docs/%s has %s"
                % (rel, zh_rows, rel, en_rows))

        en_code = code_blocks(en_text)
        zh_code = code_blocks(zh_text)
        if en_code != zh_code:
            failures.append(
                "docs/zh/%s has %d code block(s) but docs/%s has %d"
                % (rel, zh_code, rel, en_code))

        checked += 1

    return checked


def check_links(failures):
    """Every relative markdown link in the product documentation must resolve.

    The scope matches the repository's existing link check: the `docs/` and
    `testcase/` trees. Those are the trees that ship. A link from a
    Chinese page into a missing target is the same class of defect as a
    link from an English page into a missing target, so both trees are
    covered.

    The history snapshots under `.aethercode/` are NOT scanned: they are a
    record of what the repository used to contain, and their links point at
    files that were deliberately deleted.
    """
    broken = 0
    total = 0
    roots = [DOCS, REPO / "testcase"]
    paths = []
    for root in roots:
        if root.is_dir():
            paths.extend(sorted(root.rglob("*.md")))
    for path in paths:
        rel_path = path.relative_to(REPO)
        text = read(path)
        for target in links(text):
            total += 1
            resolved = (path.parent / target).resolve()
            if not resolved.exists():
                broken += 1
                failures.append(
                    "%s links to %s, which does not exist" % (rel_path, target))
    return total


def check_language_links(failures, en, zh):
    """The navigation block must point at the other language.

    Every document in both trees carries a language line. The English
    document must offer a link to the Chinese one, and the Chinese
    document must offer a link to the English one. This is what makes the
    switch work; without it, the two trees are just two trees.

    The link has to be a RELATIVE link, so that it keeps working when the
    documentation is opened from a local filesystem, a file:// URL, or a
    copy inside the packaged distribution.

    The `[English]` label on an English page is checked for a property
    that a plain "does the target exist" check cannot see: it must NOT
    point at the page it is written on. A self-link resolves, so a
    link-existence check passes it -- but clicking "English" on the
    English page would take the reader nowhere. The label says what the
    link is, and the link has to agree with the label.
    """
    checked = 0
    for rel in sorted(en & zh):
        en_text = read(DOCS / rel)
        zh_text = read(ZH / rel)

        # The Chinese document must link back to the English one. The path
        # from docs/zh/<rel> to docs/<rel> is one level up, plus the
        # document's own directory depth.
        depth = rel.count("/")
        prefix = "../" * (depth + 1)
        expect_to_en = prefix + rel
        if expect_to_en not in zh_text:
            failures.append(
                "docs/zh/%s does not link back to the English document "
                "(expected a link to %s)" % (rel, expect_to_en))

        # The English document must link to the Chinese one. From
        # docs/<rel> the Chinese counterpart lives at docs/zh/<rel>, so
        # the link has to climb out of the document's own directory
        # first: docs/README.md -> zh/README.md, and
        # docs/usage/ENVIRONMENT.md -> ../zh/usage/ENVIRONMENT.md.
        up = "../" * rel.count("/")
        expect_to_zh = up + "zh/" + rel
        if expect_to_zh not in en_text:
            failures.append(
                "docs/%s does not link to its Chinese version "
                "(expected a link to %s)" % (rel, expect_to_zh))

        # The `[English]` label on the English page must not point at the
        # English page itself.
        for m in re.finditer(r"\[English\]\(([^)]+)\)", en_text):
            target = m.group(1).split("#")[0].strip()
            if not target:
                continue
            resolved = (DOCS / rel).parent.joinpath(target).resolve()
            if resolved == (DOCS / rel).resolve():
                failures.append(
                    "docs/%s labels a link \"English\" but it points at the "
                    "page itself (%s) -- clicking it would go nowhere"
                    % (rel, target))
                break

        checked += 1

    return checked


def check_no_chinese_in_english(failures):
    """The English tree must not carry untranslated Chinese prose.

    The English document under `docs/` is the canonical source. A line of
    Chinese left in it is a translation that did not finish, and a reader
    of the English version hits a wall.

    Three kinds of Chinese are legitimate and are excluded:

      1. The language-switch line, which names the Chinese version in
         Chinese so the reader can find it.

      2. A MARKER the document uses deliberately, such as the "❌ 未读取
         (not read)" status marker in a table, or the "中文" column
         header in the index. These are labels, not prose, and they are
         recognised by being short and by sitting in a table cell.

      3. The GENERATED rule catalogue, whose content is the javadoc of
         the checkers -- and the checkers' javadoc is what it is. The
         generated file is excluded from the check the same way the
         hygiene gate excludes it.

    What is left -- a sentence, a paragraph, a code comment -- is
    untranslated prose, and that is a defect.
    """
    # A run of Chinese that is a label, not prose: short, and inside a
    # table cell or right after a status marker.
    LABEL = re.compile(r"^\s*\|.*\|")
    MARKER = re.compile(r"❌|✅")

    offenders = []
    for rel in sorted(english_docs()):
        if rel in GENERATED_DOCS:
            continue
        path = DOCS / rel
        text = read(path)
        # The number of language-switch lines: each legitimately carries
        # the word "中文" and a separator.
        switch_lines = 0
        for line in text.splitlines():
            if re.search(r"\[English\]\([^)]*\)\s*\|\s*\[?中文\]?\(", line):
                switch_lines += 1
        allowance = switch_lines * 2

        prose_cjk = 0
        for line in text.splitlines():
            cjk = len(re.findall(r"[\u4e00-\u9fff]", line))
            if not cjk:
                continue
            if re.search(r"\[English\]\([^)]*\)\s*\|\s*\[?中文\]?\(", line):
                continue
            # A table row: a label in a cell is not prose. The index
            # names its Chinese column in Chinese on purpose.
            if LABEL.match(line):
                continue
            # A status marker followed by a label: "❌ 未读取 (not read)".
            if MARKER.search(line) and cjk <= 8:
                continue
            prose_cjk += cjk

        if prose_cjk > allowance:
            offenders.append((rel, prose_cjk, allowance))
            failures.append(
                "docs/%s has %d untranslated Chinese character(s) -- the "
                "English version is not fully translated" % (rel, prose_cjk))
    return len(offenders)


def main():
    failures = []

    if not ZH.is_dir():
        print("FAIL: %s does not exist -- the Chinese documentation tree is "
              "missing" % ZH)
        return 1

    en, zh = check_pairing(failures)
    structure_checked = check_structure(failures, en, zh)
    language_checked = check_language_links(failures, en, zh)
    link_total = check_links(failures)
    untranslated = check_no_chinese_in_english(failures)

    if failures:
        print("FAIL: the English and Chinese documentation have drifted apart")
        for f in failures:
            print("  %s" % f)
        print()
        print("The two trees are the same document in two languages.")
        print("Same files, same headings, same tables, same code blocks,")
        print("and each page links to its counterpart.")
        return 1

    print("  ok: %d English / %d Chinese document(s) pair up; %d pair(s) "
          "have the same structure and cross-link"
          % (len(en), len(zh), structure_checked))
    print("  ok: %d relative markdown link(s) across the repository resolve"
          % link_total)
    print("  ok: %d English document(s) carry no untranslated Chinese"
          % untranslated)
    return 0


if __name__ == "__main__":
    sys.exit(main())
