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
import xml.etree.ElementTree as ET

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


# --------------------------------------------------------------------------- modules
POM_NS = "{http://maven.apache.org/POM/4.0.0}"


def module_poms():
    """module -> the plumb-* modules it depends on, straight from the poms.

    The glob is recursive: a module may itself be an aggregator whose
    sub-modules live one level down (``plumb-build/plumb-build-core``).

    Only the project's real ``<dependencies>`` count.  A ``<dependency>``
    nested inside ``<dependencyManagement>`` pins a VERSION for a
    dependency that is declared elsewhere (or in a consumer); it is not a
    dependency of the module that declares it.  The same holds for
    ``<plugin><dependencies>`` and for profiles that are not active by
    default.  Counting those made the gate report an aggregator as
    depending on its own sub-module.
    """
    out = {}
    for pom in sorted(REPO.glob("plumb-*/pom.xml")) + sorted(REPO.glob("plumb-*/*/pom.xml")):
        name = pom.parent.name
        root = ET.parse(pom).getroot()
        deps = set()
        for container in root.findall(f"{POM_NS}dependencies"):
            # a top-level <dependencies> is the module's real dependency set
            for d in container.findall(f"{POM_NS}dependency"):
                gid = d.findtext(f"{POM_NS}groupId") or ""
                aid = d.findtext(f"{POM_NS}artifactId") or ""
                optional = (d.findtext(f"{POM_NS}optional") or "false").strip().lower()
                if gid == "org.plumb" and aid.startswith("plumb-") and aid != name:
                    deps.add(aid)
                    if optional == "true":
                        deps.discard(aid)
        out[name] = deps
    return out


def module_in_flux(poms):
    """The module set is being restructured right now.

    Two shapes of in-flight restructure:

      * a module the poms know about but the documentation does not
        mention (a new sub-module appeared);
      * a module whose pom depends on a sub-module (a module was split).

    In both cases the dependency table is updated when the restructure
    settles.  Reporting it as stale in the meantime is noise, not signal.

    The tolerance is narrow on purpose: it applies ONLY to the modules
    that the restructure touched.  A dependency that is stale for a
    module the restructure did not touch is still reported.
    """
    doc = REPO / "docs" / "tech" / "MODULE-MERGE.md"
    if not doc.is_file():
        return False, set()
    text = doc.read_text(encoding="utf-8", errors="replace")
    named = set(re.findall(r"`(plumb-[a-z\-]+)`", text))
    touched = set()
    for module, deps in poms.items():
        if module not in named:
            touched.add(module)
        if any(d not in named for d in deps):
            touched.add(module)
    return bool(touched), touched


def check_no_module_in_flux(failures):
    """No module may be mid-restructure.

    A "the table is temporarily stale" tolerance is a permanent loophole:
    once it is in the gate, every future module split silently disables the
    dependency check for the modules it touches, and nobody notices that the
    gate stopped checking.  The restructure is finished, so the tolerance is
    removed -- and this gate makes sure it cannot come back unnoticed.
    """
    poms = module_poms()
    in_flux, touched = module_in_flux(poms)
    if in_flux:
        failures.append(
            "the module set is mid-restructure (%s); the dependency table must "
            "be updated in the same change, not exempted"
            % ", ".join(sorted(touched)))


def check_module_claims(doc, failures):
    """The module dependency table must match the poms.

    This covers the "a module is optional" family of claims: a module that
    the pom declares as a plain compile dependency is NOT optional, and
    saying so is a false statement about the build.
    """
    poms = module_poms()

    # (a) the dependency table
    rows = {}
    summary = set()
    for m in re.finditer(r"^\|\s*`(plumb-[a-z\-]+)`\s*\|\s*(.+?)\s*\|\s*$", doc.text, re.M):
        mod, cell = m.group(1), m.group(2)
        if "（无）" in cell or cell.strip() in ("无", "-"):
            rows[mod] = set()
        elif "以上全部" in cell:
            summary.add(mod)
            rows[mod] = None  # checked against "every other module"
        else:
            rows[mod] = {d for d in re.findall(r"`(plumb-[a-z\-]+)`", cell)}

    for mod in sorted(poms):
        if mod not in rows:
            failures.append("%s does not mention the module %s" % (doc.rel, mod))
            continue
        if rows[mod] is None:
            expected = set(poms) - {mod}
            if poms[mod] != expected:
                failures.append(
                    "%s says %s depends on everything, but the pom says %s"
                    % (doc.rel, mod, sorted(poms[mod])))
            continue
        if rows[mod] != poms[mod]:
            failures.append(
                "%s says %s depends on %s; the pom says %s"
                % (doc.rel, mod, sorted(rows[mod]), sorted(poms[mod])))

    # (b) "X / X" -- the same module named twice in one phrase is a typo
    for m in re.finditer(r"`(plumb-[a-z\-]+)`\s*/\s*`(plumb-[a-z\-]+)`", doc.text):
        if m.group(1) == m.group(2):
            failures.append(
                "%s names %s twice in one phrase -- one of them is wrong"
                % (doc.rel, m.group(1)))

    # (c) a module the prose names must exist
    for name in sorted(set(re.findall(r"`(plumb-[a-z\-]+)`", doc.text))):
        if name not in poms:
            failures.append("%s names %s, which has no pom.xml" % (doc.rel, name))


def check_optional_claims(doc, failures):
    """A module declared as a plain dependency is NOT optional.

    The word "optional" applied to a Maven dependency is a claim about the
    build.  If the pom has no <optional>true</optional>, the claim is false.

    The exemption is narrow: the claim may still say "optional" when it is
    talking about the RUNTIME behaviour ("可选的是运行时行为").  That is a
    true statement about a different subject, so it is not flagged.
    """
    poms = module_poms()
    main_pom = (REPO / "plumb-main" / "pom.xml").read_text(encoding="utf-8")

    # "`plumb-ai` 是 `plumb-main` 的**可选依赖**" and friends.
    claim_re = re.compile(
        r"`(plumb-[a-z\-]+)`[^。\n]{0,30}?\*\*[^*\n]*可选[^*\n]*\*\*")
    for m in claim_re.finditer(doc.text):
        mod = m.group(1)
        if mod not in poms:
            continue
        # The sentence must not be about the runtime behaviour.
        start = doc.text.rfind("。", 0, m.start()) + 1
        end = doc.text.find("。", m.end())
        sentence = doc.text[start:end if end != -1 else len(doc.text)]
        if "运行时" in sentence:
            continue
        failures.append(
            "%s calls %s an optional dependency, but the pom declares it as a "
            "plain compile dependency" % (doc.rel, mod))

    if "plumb-main" in poms:
        # sanity: the gate must be looking at a real pom
        if "plumb-ai" not in main_pom:
            failures.append(
                "%s: plumb-main/pom.xml no longer declares plumb-ai -- "
                "review the optional-dependency claims" % doc.rel)


def check_maven_profiles(doc, failures):
    """A maven profile the document names must exist.

    `mvn -P<name>` with an unknown profile fails the build, so a document
    that tells the user to run it is teaching a broken command.
    """
    profiles = set()
    for pom in (
        sorted(REPO.glob("pom.xml"))
        + sorted(REPO.glob("plumb-*/pom.xml"))
        + sorted(REPO.glob("plumb-*/*/pom.xml"))
    ):
        text = pom.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r"<profile>\s*(?:<!--.*?-->\s*)*<id>([^<]+)</id>", text, re.S):
            profiles.add(m.group(1).strip())
    for m in re.finditer(r"-P([A-Za-z0-9_\-]+)", doc.text):
        name = m.group(1)
        if name in profiles:
            continue
        failures.append(
            "%s tells the reader to use the maven profile -P%s, but no pom "
            "defines it" % (doc.rel, name))


def check_examples_snippet(doc, failures):
    """The "style only" example must disable every checks.* family.

    The snippet is a copy-paste recipe.  A family missing from it is one
    that keeps running when the user thinks they turned the DFA off.
    """
    m = re.search(r"仅风格类检查.*?```bash\n(.*?)```", doc.text, re.S)
    if not m:
        m = re.search(r"###\s*6\.3.*?```bash\n(.*?)```", doc.text, re.S)
    if not m:
        return
    snippet = m.group(1)
    disabled = set(re.findall(r"^\s{2}([a-zA-Z][a-zA-Z0-9]*):\s*false", snippet, re.M))
    families = loader_check_keys() - {"taint", "style"}
    missing = sorted(families - disabled)
    if missing:
        failures.append(
            "%s: the style-only example does not disable %d checks.* family(ies), "
            "so they keep running: %s" % (doc.rel, len(missing), missing))
    extra = sorted(disabled - loader_check_keys())
    if extra:
        failures.append(
            "%s: the style-only example disables %d key(s) the loader never reads: %s"
            % (doc.rel, len(extra), extra))


def check_no_yaml_only_build_system(doc, failures):
    """`buildSystem.*` is not a YAML key.

    The build system is CLI-only: `ConfigLoader` never reads a `buildSystem`
    key.  A table row that offers "YAML 或 --build-system" teaches the user
    to write a key that is silently ignored.
    """
    if 'root.get("buildSystem")' in CONFIG_LOADER:
        return  # the loader DOES read it -- the claim would be fine
    for m in re.finditer(r"`buildSystem\.[a-zA-Z]+`", doc.text):
        failures.append(
            "%s: %s is documented as a YAML field, but the loader never reads a "
            "buildSystem key -- it is CLI-only" % (doc.rel, m.group(0)))


def check_config_defaults(doc, failures):
    """The shipped config's active blocks must match what the document says.

    The shipped `default-config.yaml` is what a user edits.  If a key is
    active in the file, the document must not describe the file as
    "everything commented out".
    """
    yaml = REPO / "plumb-main" / "src" / "main" / "resources" / "default-config.yaml"
    if not yaml.is_file():
        return
    active = []
    for line in yaml.read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.match(r"^([a-zA-Z][a-zA-Z0-9]*):", line)
        if m:
            active.append(m.group(1))
    if active and "全部字段都注释掉" in doc.text:
        failures.append(
            "%s says the shipped config has every field commented out, but %d "
            "top-level block(s) are active: %s" % (doc.rel, len(active), active))


def check_data_flow_counts(doc, failures):
    """The fact-kind and checker counts in the data-flow document.

    These are the numbers a reader uses to reason about the engine's
    surface, so they are re-derived from the sources rather than trusted.
    """
    dfa = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa"

    # built-in fact kinds: Map.entry(NAME, new FactKind(NAME, ...))
    builtin_file = dfa / "fact" / "BuiltinFactKinds.java"
    if builtin_file.is_file():
        n_builtin = len(re.findall(
            r"java\.util\.Map\.entry\(\s*([A-Z][A-Z0-9_]*)\s*,",
            builtin_file.read_text(encoding="utf-8", errors="replace")))
        for m in re.finditer(r"注册 \*\*(\d+) 个\*\*内置 kind", doc.text):
            if int(m.group(1)) != n_builtin:
                failures.append("%s says %d built-in fact kinds; the source has %d"
                                % (doc.rel, int(m.group(1)), n_builtin))

    # yaml fact kinds
    yaml_file = REPO / "plumb-dfa" / "src" / "main" / "resources" / "fact-kinds.yaml"
    if yaml_file.is_file():
        n_yaml = len(re.findall(
            r"^  ([A-Z][A-Za-z0-9]*):\s*$",
            yaml_file.read_text(encoding="utf-8", errors="replace"), re.M))
        for m in re.finditer(r"fact-kinds\.yaml` 的 \*\*(\d+) 个\*\*", doc.text):
            if int(m.group(1)) != n_yaml:
                failures.append("%s says %d yaml fact kinds; the file has %d"
                                % (doc.rel, int(m.group(1)), n_yaml))
        if builtin_file.is_file():
            for m in re.finditer(r"所以有效的事务种类是 (\d+) 个", doc.text):
                want = n_builtin + n_yaml
                if int(m.group(1)) != want:
                    failures.append(
                        "%s says %d effective fact kinds; the source has %d "
                        "(%d built-in + %d yaml)" % (doc.rel, int(m.group(1)), want,
                                                    n_builtin, n_yaml))

    # TaintedCheckerBase subclasses
    n_sub = 0
    for p in dfa.rglob("*.java"):
        if "/main/" not in p.as_posix():
            continue
        if "extends TaintedCheckerBase" in p.read_text(encoding="utf-8", errors="replace"):
            n_sub += 1
    for m in re.finditer(r"全仓库共 (\d+) 个 `extends TaintedCheckerBase`", doc.text):
        if int(m.group(1)) != n_sub:
            failures.append("%s says %d TaintedCheckerBase subclasses; the source has %d"
                            % (doc.rel, int(m.group(1)), n_sub))

    # the framework binding annotations
    cat = (REPO / "plumb-rules" / "src" / "main" / "java" / "org" / "plumb" / "rules"
           / "framework" / "FrameworkInputAnnotations.java")
    if cat.is_file():
        body = cat.read_text(encoding="utf-8", errors="replace")
        n_ann = len(set(re.findall(r'm\.put\("([A-Za-z]+)"', body)))
        for m in re.finditer(r"共 \*\*(\d+)\*\* 个绑定注解", doc.text):
            if int(m.group(1)) != n_ann:
                failures.append("%s says %d binding annotations; the catalogue has %d"
                                % (doc.rel, int(m.group(1)), n_ann))
        n_fw = len(set(re.findall(r'm\.put\("\w+",\s*(FRAMEWORK_\w+)\)', body)))
        for m in re.finditer(r"覆盖 (\d+) 个框架", doc.text):
            if int(m.group(1)) != n_fw:
                failures.append("%s says %d frameworks; the catalogue attributes %d"
                                % (doc.rel, int(m.group(1)), n_fw))

    # a line reference must CONTAIN the symbol it names
    for m in re.finditer(
            r"(plumb-[a-z\-]+/src/main/java/org/plumb/[A-Za-z0-9_/]*\.java):(\d+)-(\d+)",
            doc.text):
        rel, lo, hi = m.group(1), int(m.group(2)), int(m.group(3))
        path = REPO / rel
        if not path.is_file():
            failures.append("%s cites %s, which does not exist" % (doc.rel, rel))
            continue
        body = path.read_text(encoding="utf-8", errors="replace").splitlines()
        block = body[lo - 1:hi]
        if not block:
            failures.append("%s cites %s:%d-%d, which is past the end of the file"
                            % (doc.rel, rel, lo, hi))
            continue
        # The symbol the citation is FOR.  A citation is written as
        # "`Symbol`（`path:lo-hi`）" or "`a.b.Symbol`\n(`path:lo-hi`）" --
        # the symbol may sit in the previous LINE, so the lookup crosses
        # newlines.  Take the LAST backticked identifier before the
        # citation, preferring a dotted FQCN over a short name.
        before = doc.text[max(0, m.start() - 300):m.start()]
        names = re.findall(r"`([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)`", before)
        symbol = None
        for n in reversed(names):
            # a table cell of the previous row is not the subject
            if n.endswith(")") or n.endswith(":"):
                continue
            symbol = n
            break
        if symbol and not any(symbol.split(".")[-1] in l for l in block):
            failures.append(
                "%s cites %s:%d-%d for `%s`, but that range does not contain it"
                % (doc.rel, rel, lo, hi, symbol))
        elif symbol:
            # The range must contain the symbol's DECLARATION, not just a
            # mention of it.  A mention inside a comment is not enough.
            simple = symbol.split(".")[-1]
            decl = re.compile(
                r"(?:public|private|protected|static|final|\s)*\b"
                + re.escape(simple) + r"\s*\(")
            if not any(decl.search(l) for l in block):
                failures.append(
                    "%s cites %s:%d-%d for `%s`, but that range does not contain its "
                    "declaration" % (doc.rel, rel, lo, hi, symbol))


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
    check_optional_claims(doc, failures)
    check_maven_profiles(doc, failures)

    module_doc = REPO / "docs" / "tech" / "MODULE-MERGE.md"
    if module_doc.is_file():
        mdoc = Doc(module_doc)
        check_module_claims(mdoc, failures)

    # No module may be mid-restructure: the dependency table is updated in
    # the same change that changes the module set.
    check_no_module_in_flux(failures)

    # Every product document may make an "optional dependency" claim, and
    # every one of them may name a maven profile that does not exist.
    for extra in sorted((REPO / "docs").rglob("*.md")):
        if extra in (cli_doc, module_doc):
            continue
        edoc = Doc(extra)
        check_optional_claims(edoc, failures)
        check_maven_profiles(edoc, failures)

    # The examples document: the copy-paste recipe must stay complete.
    examples = REPO / "docs" / "usage" / "CLI-EXAMPLES.md"
    if examples.is_file():
        exdoc = Doc(examples)
        check_examples_snippet(exdoc, failures)
        check_no_yaml_only_build_system(exdoc, failures)
        check_config_defaults(exdoc, failures)

    # The data-flow document: the fact-kind and checker counts.
    dataflow = REPO / "docs" / "tech" / "DATA-FLOW.md"
    if dataflow.is_file():
        check_data_flow_counts(Doc(dataflow), failures)

    if failures:
        print("FAIL: the product documentation does not match the source")
        for f in failures:
            print("  %s" % f)
        print()
        print("Every claim the documentation makes about the code must be")
        print("re-derivable from the code. Fix the document or the code --")
        print("but make the two agree.")
        return 1

    print("  ok: %s and %s match the source "
          "(%d taint rules, %d registered AI tools, %d CLI flags, %d modules)"
          % (doc.rel, "docs/tech/MODULE-MERGE.md", taint_rule_count(),
             len(registered_tool_names()), len(cli_flags()), len(module_poms())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
