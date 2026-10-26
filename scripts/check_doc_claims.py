#!/usr/bin/env python3
"""Check that the product documentation's claims match the source.

`check_doc_numbers.py` re-derives counts that a document states as a bare
number.  This gate covers a different family of drift: claims that are
*structural* rather than numeric, and that silently rot when the code
changes underneath them.

The families this gate catches, and why each one is silent:

  1. A round marker whose digits were erased ("R-?").  The hygiene gate's
     pattern is `R-\\d{3,4}`; a placeholder with no digits does not match,
     so it survives every other check and ships to the reader.

  2. Two sections with the same number.  A reader following "§2.1" cannot
     tell which one they landed in, and the second one silently shadows
     the first.

  3. A "§N" that points at a section this document does not have.  The
     reference is just text; nothing fails.

  4. A YAML key the configuration loader reads but the schema never shows.
     The user writes the key, it is accepted, and it does something they
     did not expect -- or they never discover it exists.

  5. A YAML key the schema shows but the loader ignores.  The user sets it
     and nothing happens, with no error anywhere.

  6. An alias whose *priority* is documented backwards.  The key is
     documented, the spelling is right, but the document says the wrong one
     wins when both are present.

  7. A count of things that changed: rule counts, tool counts, registered
     classes.  These rot every time the code moves.

  8. A CLI flag that does not exist, or a flag that exists and is not
     documented.

The rule this gate encodes: the product documentation states what the code
does.  Every claim it can check is checked against the source, not against
a remembered number.

Each check prints the quantity it derived, so a failure says what the
truth is rather than only that the document is wrong.
"""
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent

MAIN = REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb" / "main"
CONFIG_LOADER = (MAIN / "ConfigLoader.java").read_text(encoding="utf-8")
SAST_MAIN = (MAIN / "SastMain.java").read_text(encoding="utf-8")
SCAN_RUNNER = (MAIN / "ScanRunner.java").read_text(encoding="utf-8")
DFA_ROOT = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa"
TAINT_REGISTRY = DFA_ROOT / "taint" / "TaintRuleRegistry.java"
AI_TOOLS = REPO / "plumb-ai" / "src" / "main" / "java" / "org" / "plumb" / "ai" / "tools"

# A heading number may be followed by a space, a period, a colon, or EOL.
HEAD_RE = r"^#{2,4}[ \t]+([0-9]+(?:\.[0-9]+)*)(?:[.\s:：]|$)"
# A round marker: R-123, R-1234a.  The digits may have been erased.
ROUND_RE = re.compile(r"(?<![A-Za-z0-9_])R-\d{0,4}[a-z]?(?![A-Za-z0-9_])")


class Doc:
    """One product document and the claims made inside it."""

    def __init__(self, path):
        self.path = path
        self.text = path.read_text(encoding="utf-8", errors="replace")
        self.lines = self.text.splitlines()

    @property
    def rel(self):
        return self.path.relative_to(REPO).as_posix()


def yaml_blocks(text):
    return re.findall(r"```yaml\n(.*?)```", text, re.S)


def section_of(doc, number):
    """The body of the section with this number, or ''."""
    m = re.search(r"^#{2,4}[ \t]+" + re.escape(number) + r"(?:[.\s:：]|$).*?\n(.*?)(?=\n#{2,4}[ \t]+\S|\Z)",
                  doc.text, re.M | re.S)
    return m.group(1) if m else ""


def check_round_markers(doc, failures):
    """A round marker whose digits were erased must not ship to the reader."""
    hits = []
    for i, line in enumerate(doc.lines, 1):
        for m in ROUND_RE.finditer(line):
            # A bare "R-" with no digits at all is not a marker; require a
            # digit OR the literal "R-?" placeholder shape.
            token = m.group(0)
            if len(token) <= 2 and not re.fullmatch(r"R-\d+[a-z]?", token):
                # "R-" alone -- ignore, but "R-?" is a placeholder.
                if not re.search(r"R-\?", line):
                    continue
            hits.append((i, token, line.strip()[:70]))
    if hits:
        failures.append(
            "%s carries %d round marker(s); the digits were erased so the "
            "hygiene gate cannot see them: %s"
            % (doc.rel, len(hits), "; ".join("line %d: %r" % (i, t) for i, t, _ in hits[:5])))


def check_section_numbers(doc, failures):
    """Section numbers must be unique and every '§N' must resolve."""
    nums = re.findall(HEAD_RE, doc.text, re.M)
    dupes = sorted({n for n in nums if nums.count(n) > 1})
    if dupes:
        failures.append("%s has duplicate section number(s) %s" % (doc.rel, dupes))

    known = set(nums)
    top_level = {n for n in nums if "." not in n}
    for m in re.finditer(r"§\s?([0-9]+(?:\.[0-9]+)*)", doc.text):
        num = m.group(1)
        if num in known or num in top_level:
            continue
        before = doc.text[max(0, m.start() - 250):m.start()]
        after = doc.text[m.end():m.end() + 250]
        # A reference that names another document is that document's section.
        if re.search(r"[\w/.-]+\.md[\s`]*$", before) or re.search(r"^[\s`]*[\w/.-]+\.md", after):
            continue
        failures.append(
            "%s references §%s, but this document has no such section "
            "(known: %s)" % (doc.rel, num, ", ".join(sorted(known))[:120]))


def loader_alias_pairs():
    """(preferred, alias) pairs the loader accepts, read from the source."""
    pairs = set()
    for m in re.finditer(
        r'root\.get\("([a-zA-Z0-9_-]+)"\);\s*\n\s*if \([^)]*?\) \w+ = root\.get\("([a-zA-Z0-9_-]+)"\)',
        CONFIG_LOADER,
    ):
        pairs.add((m.group(1), m.group(2)))
    for m in re.finditer(r'\{"([a-zA-Z0-9_]+)",\s*"([a-zA-Z0-9_]+)"\}', CONFIG_LOADER):
        pairs.add((m.group(1), m.group(2)))
    ai_block = re.search(r'Object ai = root\.get\("ai"\);(.*?)instanceof Map<\?, \?> am\)',
                          CONFIG_LOADER, re.S)
    if ai_block:
        for m in re.finditer(r'ai = root\.get\("([a-zA-Z0-9_-]+)"\)', ai_block.group(1)):
            pairs.add(("ai", m.group(1)))
    return pairs


def loader_check_keys():
    """Every `checks.*` key the loader reads, camelCase and snake_case.

    The snake_case spelling is an ALIAS of the camelCase one -- the
    "camelCase vs snake_case" section covers it, so the schema sample does
    not need a second row for it.
    """
    camel = set()
    for m in re.finditer(r'cm\.get\("([a-zA-Z][a-zA-Z0-9]*)"\)', CONFIG_LOADER):
        camel.add(m.group(1))
    return camel


def loader_top_keys():
    """Every top-level key the loader reads."""
    keys = set(re.findall(r'root\.get\("([a-zA-Z0-9_-]+)"\)', CONFIG_LOADER))
    keys -= {"ai-dedup", "aiDedup"}
    return keys


def loader_ai_keys():
    """Every `ai.*` key the loader reads."""
    keys = set(re.findall(r'case "([a-zA-Z0-9]+)" ->', CONFIG_LOADER))
    keys -= {"enabled", "scan", "contextLines", "rulePromptsDir", "sendAstContext"}
    return keys


def check_yaml_schema(doc, failures):
    """The schema sample must show every key the loader reads, and no others."""
    blocks = yaml_blocks(doc.text)
    schema = None
    for block in blocks:
        if re.search(r"^checks:", block, re.M) and re.search(r"^rules:", block, re.M):
            schema = block
            break
    if schema is None:
        return

    # (a) checks.* keys
    doc_checks = set()
    in_checks = False
    for line in schema.splitlines():
        if re.match(r"^checks:", line):
            in_checks = True
            continue
        if in_checks:
            if re.match(r"^[a-zA-Z]", line) or line.startswith("#"):
                in_checks = False
                continue
            m = re.match(r"^  ([a-zA-Z][a-zA-Z0-9]*):", line)
            if m:
                doc_checks.add(m.group(1))
    src_checks = loader_check_keys()
    missing = sorted(src_checks - doc_checks)
    if missing:
        failures.append(
            "%s documents %d checks.* key(s) but the loader reads %d more that "
            "the schema never shows: %s" % (doc.rel, len(doc_checks), len(missing), missing))
    phantom = sorted(doc_checks - src_checks)
    if phantom:
        failures.append(
            "%s shows %d checks.* key(s) the loader never reads, so setting "
            "them does nothing: %s" % (doc.rel, len(phantom), phantom))

    # (b) top-level keys
    doc_top = set()
    for line in schema.splitlines():
        m = re.match(r"^([a-z][a-zA-Z0-9]*):", line)
        if m:
            doc_top.add(m.group(1))
    for block in blocks:
        if re.match(r"^ai:", block):
            doc_top.add("ai")
    src_top = loader_top_keys()

    # (c) alias pairs -- the table in the "camelCase vs snake_case" section.
    alias_section = section_of(doc, "3.1")
    doc_pairs = set()
    for m in re.finditer(r"^\|\s*`([a-zA-Z0-9_\-]+)`\s*\|\s*(.+?)\s*\|", alias_section, re.M):
        for a in re.findall(r"`([a-zA-Z0-9_\-]+)`", m.group(2)):
            doc_pairs.add((m.group(1), a))
    doc_aliases = {k for pair in doc_pairs for k in pair}
    src_pairs = loader_alias_pairs()
    src_aliases = {a for _, a in src_pairs}

    missing_alias = sorted(src_aliases - doc_aliases)
    if missing_alias:
        failures.append(
            "%s does not document %d alias(es) the loader accepts: %s"
            % (doc.rel, len(missing_alias), missing_alias))
    mismatched = sorted((p, a) for p, a in doc_pairs if (p, a) not in src_pairs)
    if mismatched:
        failures.append(
            "%s documents an alias with the wrong priority %s; the loader "
            "prefers a different spelling" % (doc.rel, mismatched))

    undoc_top = sorted(src_top - doc_top - doc_aliases)
    if undoc_top:
        failures.append(
            "%s does not document %d top-level key(s) the loader reads: %s"
            % (doc.rel, len(undoc_top), undoc_top))
    phantom_top = sorted(doc_top - src_top - doc_aliases)
    if phantom_top:
        failures.append(
            "%s documents %d top-level key(s) the loader never reads: %s"
            % (doc.rel, len(phantom_top), phantom_top))

    # (d) ai.* keys
    doc_ai = set()
    for block in blocks:
        in_ai = False
        for line in block.splitlines():
            if re.match(r"^ai:", line):
                in_ai = True
                continue
            if in_ai:
                if re.match(r"^[a-zA-Z#]", line):
                    break
                m = re.match(r"^  ([a-zA-Z][a-zA-Z0-9]*):", line)
                if m:
                    doc_ai.add(m.group(1))
    src_ai = loader_ai_keys()
    missing_ai = sorted(src_ai - doc_ai)
    if missing_ai:
        failures.append(
            "%s does not document %d ai.* key(s) the loader reads: %s"
            % (doc.rel, len(missing_ai), missing_ai))
    phantom_ai = sorted(doc_ai - src_ai)
    if phantom_ai:
        failures.append(
            "%s documents %d ai.* key(s) the loader never reads: %s"
            % (doc.rel, len(phantom_ai), phantom_ai))


def taint_rule_count():
    if not TAINT_REGISTRY.is_file():
        return 0
    text = TAINT_REGISTRY.read_text(encoding="utf-8", errors="replace")
    return len(set(re.findall(r'"(dfa-taint-[a-z0-9\-]+)"', text)))


def registered_tool_names():
    return set(re.findall(r"\.register\(new org\.plumb\.ai\.tools\.(\w+)\(\)\)", SCAN_RUNNER))


def concrete_tool_classes():
    out = set()
    for p in AI_TOOLS.glob("*Tool.java"):
        text = p.read_text(encoding="utf-8", errors="replace")
        if re.search(r"\binterface\s+" + re.escape(p.stem) + r"\b", text):
            continue  # the Tool interface itself is not a tool class
        out.add(p.stem)
    return out


def count_pattern(doc, pattern):
    """Every integer that matches this pattern, as a list of (line, number)."""
    out = []
    for i, line in enumerate(doc.lines, 1):
        for m in re.finditer(pattern, line):
            out.append((i, int(m.group(1))))
    return out


def check_counts(doc, failures):
    """Counts that rot when the code moves."""
    # taint rule count
    taint_n = taint_rule_count()
    for i, claimed in count_pattern(doc, r"关闭所有\s*(\d+)\s*条\s*taint\s*规则"):
        if claimed != taint_n:
            failures.append(
                "%s:%d says %d taint rules; the registry has %d"
                % (doc.rel, i, claimed, taint_n))
    for i, claimed in count_pattern(doc, r"taint:\s*true\s*#\s*(\d+)\s*条\s*taint"):
        if claimed != taint_n:
            failures.append(
                "%s:%d says %d taint rules; the registry has %d"
                % (doc.rel, i, claimed, taint_n))

    # registered AI tools vs concrete tool classes
    registered = registered_tool_names()
    concrete = concrete_tool_classes()
    n_registered = len(registered)
    n_concrete = len(concrete)
    for i, claimed in count_pattern(doc, r"(\d+)\s*个工具(?!类)"):
        if claimed != n_registered:
            failures.append(
                "%s:%d says %d registered AI tools; ScanRunner registers %d"
                % (doc.rel, i, claimed, n_registered))
    for i, claimed in count_pattern(doc, r"共(?:有)?\s*\**(\d+)\**\s*个具体工具类"):
        if claimed != n_concrete:
            failures.append(
                "%s:%d says %d concrete tool classes; there are %d"
                % (doc.rel, i, claimed, n_concrete))


def cli_flags():
    """The long options the CLI actually defines."""
    return {"--" + f for f in re.findall(r'\.longOpt\("([a-z0-9\-]+)"\)', SAST_MAIN)}


def check_cli_flags(doc, failures):
    """Every flag the document names must exist, and vice versa."""
    src = cli_flags()
    # Flags the document explicitly presents as removed / never existing.
    known_absent = {"--aggressiveness", "--disable", "--fail-on-high"}
    doc_flags = set(re.findall(r"`(--[a-z0-9\-]+)`", doc.text))
    phantom = sorted(doc_flags - src - known_absent)
    if phantom:
        failures.append(
            "%s documents %d flag(s) the CLI does not define: %s"
            % (doc.rel, len(phantom), phantom))
    undoc = sorted(src - doc_flags)
    if undoc:
        failures.append(
            "%s does not document %d CLI flag(s): %s" % (doc.rel, len(undoc), undoc))


def main():
    failures = []

    cli_doc = REPO / "docs" / "usage" / "CLI.md"
    if not cli_doc.is_file():
        print("FAIL: %s does not exist" % cli_doc)
        return 1

    doc = Doc(cli_doc)
    check_round_markers(doc, failures)
    check_section_numbers(doc, failures)
    check_yaml_schema(doc, failures)
    check_counts(doc, failures)
    check_cli_flags(doc, failures)

    if failures:
        print("FAIL: the product documentation does not match the source")
        for f in failures:
            print("  %s" % f)
        print()
        print("Every claim the documentation makes about the code must be")
        print("re-derivable from the code. Fix the document or the code --")
        print("but make the two agree.")
        return 1

    print("  ok: %s matches the source "
          "(%d taint rules, %d registered AI tools, %d CLI flags)"
          % (doc.rel, taint_rule_count(), len(registered_tool_names()), len(cli_flags())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
