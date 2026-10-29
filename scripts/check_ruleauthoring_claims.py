#!/usr/bin/env python3
"""Check that the rule-authoring document still matches the source.

`docs/rules/RULE-AUTHORING.md` tells a reader how to ADD a rule. Every
instruction in it is an instruction to touch a specific file, with a
specific API, at a specific place. An instruction that names a file
that moved, a method that was renamed, or a script that does not
exist sends the reader down a path that does not work -- and the
reader only finds out after they have written half a checker.

So the claims checked here are all things a reader would act on:

  1. Every file the document names exists -- AND the document still
     names it (a gate that checks a file the document dropped is a
     gate that quietly stopped checking anything).
  2. Every command the document tells the reader to run is a command
     that works: the launcher has to exist, the CLI flag has to be a
     real flag, the jar path has to be the one the build produces.
  3. The counts the document states are the counts the source has,
     derived from the source rather than pinned in the gate.
  4. The API the document quotes is the API the source declares.
  5. The names the document says do not exist really do not exist.
  6. The document does not tell the reader to hand-edit a generated
     file -- that edit is lost on the next regeneration.
  7. The document does not tell the reader to hand-edit a generated
     file -- that edit is lost on the next regeneration.
  8. The distinction the document draws between Plumb's own aliases
     and the external-tool mapping is the distinction the source
     actually makes.

The Chinese tree (`docs/zh/rules/RULE-AUTHORING.md`) is the same
document in another language and carries the same claims, so it is
checked the same way. The wording of a check has to accept both
languages -- otherwise the Chinese tree is held to a standard the
English one is not.

    python scripts/check_ruleauthoring_claims.py
"""
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DOC = REPO / "docs" / "rules" / "RULE-AUTHORING.md"
ZH_DOC = REPO / "docs" / "zh" / "rules" / "RULE-AUTHORING.md"
STYLE = REPO / "plumb-style" / "src" / "main" / "java" / "org" / "plumb" / "style"
DFA = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa"
RULES = REPO / "plumb-rules" / "src" / "main" / "java" / "org" / "plumb" / "rules"
MAIN = REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb" / "main"
RES = REPO / "plumb-dfa" / "src" / "main" / "resources"

# The files the document tells the reader to touch. Each has to exist.
FILES = [
    "plumb-style/src/main/java/org/plumb/style/security/HardcodedCredentialsCheck.java",
    "plumb-style/src/main/java/org/plumb/style/error/EmptyCatchBlockCheck.java",
    "plumb-style/src/main/java/org/plumb/style/style/BadShiftCheck.java",
    "plumb-style/src/main/java/org/plumb/style/CheckerCategories.java",
    "plumb-style/src/main/java/org/plumb/style/AstCheckerRegistry.java",
    "plumb-style/src/main/java/org/plumb/style/AstChecker.java",
    "plumb-style/src/main/java/org/plumb/style/AstDispatcher.java",
    "plumb-style/src/main/java/org/plumb/style/ObjectHijackCheck.java",
    "plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintRuleRegistry.java",
    "plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintedCheckerBase.java",
    "plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintRule.java",
    "plumb-dfa/src/main/java/org/plumb/dfa/config/SinkFamily.java",
    "plumb-main/src/main/java/org/plumb/main/ScanRunner.java",
    "plumb-dfa/src/main/resources/checkers.yaml",
    "plumb-dfa/src/main/resources/cwe-mappings.yaml",
    "plumb-dfa/src/main/resources/sink-families.yaml",
    "scripts/gen_rules_doc.py",
    "scripts/gen_categories.py",
]

# The CLI flags the document tells the reader to use. Each has to be a
# real flag of the real CLI.
CLI_FLAGS = [
    "--list-rules",
    "--rule-prompts-dir",
]

# The API the document quotes, as (file, symbol).
API = [
    ("plumb-style/src/main/java/org/plumb/style/AstCheckerRegistry.java", "builtins"),
    ("plumb-style/src/main/java/org/plumb/style/AstCheckerRegistry.java", "byCategory"),
    ("plumb-style/src/main/java/org/plumb/style/AstCheckerRegistry.java", "load"),
    ("plumb-style/src/main/java/org/plumb/style/AstChecker.java", "visit"),
    ("plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintedCheckerBase.java", "getAiPrompt"),
    ("plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintedCheckerBase.java", "isSanitizer"),
    ("plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintRule.java", "of"),
    ("plumb-dfa/src/main/java/org/plumb/dfa/config/SinkFamily.java", "relaxedForm"),
    ("plumb-dfa/src/main/java/org/plumb/dfa/config/SinkFamily.java", "matchPattern"),
    ("plumb-main/src/main/java/org/plumb/main/ScanRunner.java", "buildDfaCheckers"),
]

# The names the document says do not exist.
MUST_NOT_EXIST = [
    ("plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintCatalog.java",
     "there is no TaintCatalog"),
]

# The scripts the document tells the reader NOT to edit by hand.
GENERATED = [
    "plumb-style/src/main/java/org/plumb/style/CheckerCategories.java",
]

# The external tools the generated rule catalogue maps onto. The
# generator's tool list is the authority; the document has to agree
# with it, including in what it does NOT claim.
EXTERNAL_TOOLS = ["PMD", "SpotBugs", "Checkstyle", "SonarQube"]

# The tool names the project recognises.
#
# This is a VOCABULARY, not a list of what the document may say. The
# check asks whether a tool the document promises as covered is one the
# generator maps onto; to ask that, the gate has to recognise the tool
# names that exist.
#
# It is the generator's own list, plus the tools the project's other
# documents already name, so a promise for a tool nobody has heard of
# is not silently accepted.
#
# The point of this shape is that the gate does not have to keep a list
# of the tools it must NOT accept. A hand-written "do not promise this
# tool" list would have to name the excluded tool in the document, and
# a denial that has to be written down is a name that keeps being
# written down.
KNOWN_EXTERNAL_TOOLS = [
    "PMD",
    "SpotBugs",
    "Checkstyle",
    "SonarQube",
    "FindBugs",
]


def note(msg):
    print(msg)


def read(path):
    return path.read_text(encoding="utf-8", errors="replace")


def strip_comments(text):
    """Remove block and line comments.

    A javadoc `{@link #isSanitizer}` proves nothing about the method
    existing, and a commented-out override must not count as an
    override. Both are declarations of the source, so both have to be
    stripped before the source is measured.
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def dfa_checker_prompt_overrides():
    """The DFA checker classes that override getAiPrompt.

    `TaintedCheckerBase` is the abstract base class: it supplies the
    generated prompt, and it is counted SEPARATELY from the concrete
    checkers, because the document counts them separately.
    """
    concrete = []
    base = None
    for p in sorted(DFA.rglob("*.java")):
        body = strip_comments(read(p))
        if not re.search(r"public\s+String\s+getAiPrompt\s*\(", body):
            continue
        if p.name == "TaintedCheckerBase.java":
            base = p
            continue
        concrete.append(p.name)
    return concrete, base is not None


def style_prompt_overrides():
    """The plumb-style checkers that override getAiPrompt."""
    out = []
    for p in sorted(STYLE.rglob("*.java")):
        if re.search(r"public\s+String\s+getAiPrompt\s*\(",
                     strip_comments(read(p))):
            out.append(p.relative_to(REPO).as_posix())
    return out


def visit_overload_count():
    """The number of per-node visit overloads AstChecker declares."""
    p = STYLE / "AstChecker.java"
    if not p.is_file():
        return None
    return len(re.findall(r"default\s+void\s+visit\(\s*[\w.]+\s+n\s*,\s*StyleContext",
                           strip_comments(read(p))))


def generator_external_tools():
    """The external tools the rule-catalogue generator maps onto.

    Read out of `scripts/gen_rules_doc.py` rather than pinned here: the
    generator is the authority on which columns the generated table
    has, and a gate that pinned the list would not notice the
    generator changing.
    """
    p = REPO / "scripts" / "gen_rules_doc.py"
    if not p.is_file():
        return None
    body = read(p)
    m = re.search(r"for\s+tool\s+in\s+\[([^\]]+)\]", body)
    if not m:
        return None
    return re.findall(r'"([^"]+)"', m.group(1))




def promised_external_tools(text):
    """The tool names the text PROMISES as covered by the mapping.

    A tool is "promised" when the text names it inside a coverage
    clause -- "it covers X, Y and Z", "it covers X, Y, Z and W", and
    the Chinese equivalents.

    The names that can be promised are the project's vocabulary of tool
    names, so a promise for a tool nobody has heard of is not silently
    accepted.

    The check looks at a COVERAGE CLAUSE rather than anywhere in the
    text: a tool mentioned for another reason -- a comparison table, a
    sentence about what the project does NOT do -- is not a promise.
    """
    out = set()
    for m in re.finditer(
            r"(covers?|mapped?\s+onto|equivalent\s+to|\u8986\u76d6|\u5bf9\u5e94)"
            r"([^.]{0,160})",
            text, re.I):
        clause = m.group(2)
        for tool in KNOWN_EXTERNAL_TOOLS:
            if re.search(r"\b%s\b" % re.escape(tool), clause):
                out.add(tool)
    return out


def rule_def_alias_list():
    """The alias list of one AST rule, read out of the source.

    The document quotes a `RuleDef` with a canonical id and two
    aliases. Both halves have to be real: an id the engine does not
    emit, or an alias the rule does not carry, would send the reader
    to a spelling that does not work.
    """
    p = STYLE / "error" / "EmptyCatchBlockCheck.java"
    if not p.is_file():
        return None
    body = strip_comments(read(p))
    m = re.search(r'new\s+RuleId\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)', body)
    if not m:
        return None
    ids = (m.group(1), m.group(2))
    # The alias list is the LAST `List.of(...)` before the RuleDef
    # closes -- the CWE list is an earlier one.
    tail = body[m.end():]
    al = re.findall(r'List\.of\(\s*((?:"[^"]+"\s*,\s*)*"[^"]+")\s*\)', tail)
    if not al:
        return (ids, ())
    aliases = re.findall(r'"([^"]+)"', al[-1])
    return (ids, tuple(aliases))


def sentence_around(doc, start, end):
    """The sentence that spans doc[start:end].

    A sentence ends at a full stop in either language, at a semicolon,
    or at the start of a new LIST ITEM. The last one matters: a
    numbered step ("2. ...") is its own sentence, and without it the
    step's text runs back into the previous one -- which would let the
    previous step's generator name excuse a hand-edit instruction in
    the next step.
    """
    before = doc[:start]
    breaks = [before.rfind(". "), before.rfind("。"),
              before.rfind("; "), before.rfind("；"),
              before.rfind("\n- "), before.rfind("\n* "),
              # A numbered list item: the "N. " at the start of a line.
              max((m.start() for m in re.finditer(r"(?m)^\s*\d+\.\s",
                                                 before)), default=-1)]
    begin = max(breaks)
    after = doc[end:]
    tail = [after.find(". "), after.find("。"),
            after.find("; "), after.find("；"),
            after.find("\n- "), after.find("\n* "),
            min((m.start() for m in re.finditer(r"(?m)^\s*\d+\.\s",
                                               after)), default=-1)]
    tail = [t for t in tail if t != -1]
    stop = min(tail) if tail else len(after)
    return doc[begin:end + stop]


def check_document(doc, path):
    failures = []
    prefix = "%s: " % path.name

    # (1) The files the document names must exist.
    file_checked = 0
    for rel in FILES:
        if (REPO / rel).is_file():
            file_checked += 1
            continue
        failures.append(prefix +
                        "the document names %s, but that file does not exist"
                        % rel)
    note("checked %d named file(s)" % file_checked)

    # (1b) The document must actually name them. A file the gate
    #      checks but the document no longer mentions is a gate that
    #      has quietly stopped checking anything.
    #
    #      The document may name the file by its path (the three
    #      prompt-override checkers are given as full paths) or by
    #      its class name (`AstChecker`, `TaintedCheckerBase`). Both
    #      count as naming it.
    for rel in FILES:
        base = rel.rsplit("/", 1)[-1]
        stem = base[:-len(".java")] if base.endswith(".java") else base
        if base not in doc and stem not in doc:
            failures.append(prefix +
                            "the document no longer names %s, so the file it "
                            "tells the reader to touch is undocumented" % stem)
    note("checked %d named file(s) still mentioned" % len(FILES))

    # (2) The CLI flags must be real flags of the real CLI.
    #
    #     The flag the document mentions has to be the flag the CLI
    #     declares -- not merely one that CONTAINS it as a substring.
    #     `--rule-prompt-dir` contains `--rule-prompts-dir`, so a
    #     substring test would accept a flag that does not exist.
    cli = MAIN / "SastMain.java"
    if cli.is_file():
        body = strip_comments(read(cli))
        for flag in CLI_FLAGS:
            if not re.search(r'"' + re.escape(flag.lstrip("-")) + r'"',
                             body):
                failures.append(prefix +
                                "the document tells the reader to use %s, but "
                                "the CLI does not have that option" % flag)
        note("checked %d CLI flag(s)" % len(CLI_FLAGS))
    else:
        failures.append(prefix + "%s does not exist" % cli)

    # (2a) Every long option the document tells the reader to use has
    #      to be a real option. This is the BROAD version of check
    #      (2): it catches a flag that is not on the list above,
    #      because the list above is the set the gate was told about.
    #
    #      Only `--long` options are checked -- a short option like
    #      `-h` is far too easy to hit by accident.
    cli_flags_found = 0
    for flag in sorted(set(re.findall(r"--[a-z][a-z0-9\-]+", doc))):
        cli_flags_found += 1
        if not re.search(r'"' + re.escape(flag.lstrip("-")) + r'"', body):
            failures.append(prefix +
                            "the document tells the reader to use %s, but "
                            "the CLI does not have that option" % flag)
    note("checked %d distinct CLI option(s) in the document" % cli_flags_found)

    # (2b) The verification command must be runnable.
    #
    #      A command that names a script the repository does not have
    #      is the most expensive kind of wrong instruction: the reader
    #      writes the checker, then cannot verify it.
    for cmd in re.findall(r"`(bin/[\w.\-]+)", doc):
        if (REPO / cmd).exists():
            continue
        failures.append(prefix +
                        "the document tells the reader to run %s, but that "
                        "script does not exist" % cmd)
    note("checked the launcher script(s) the document names")

    # (2c) The verification command must name the jar the build
    #      actually produces. The packaged jar path is derived from
    #      the MODULE's artifactId and version, so a stale path in the
    #      document is a command that fails.
    #
    #      The module's own artifactId is the one that is NOT inside
    #      the <parent> block -- a pom lists the parent's artifactId
    #      first, and reading that one gives the wrong jar name.
    #
    #      The VERSION may be inherited: `plumb-main` declares no
    #      <version> of its own and takes it from the parent. A check
    #      that silently finds no version is worse than no check at
    #      all -- it reports success while testing nothing -- so the
    #      parent is consulted as the fallback.
    pom = REPO / "plumb-main" / "pom.xml"
    if pom.is_file():
        body = read(pom)
        without_parent = re.sub(r"<parent>.*?</parent>", "", body, flags=re.S)
        without_build = re.sub(r"<build>.*?</build>", "", without_parent,
                               flags=re.S)
        artifact = re.search(r"<artifactId>([^<]+)</artifactId>", without_build)
        version = re.search(r"<version>([^<]+)</version>", without_build)
        if version is None:
            parent = re.search(r"<parent>(.*?)</parent>", body, re.S)
            if parent:
                version = re.search(r"<version>([^<]+)</version>",
                                    parent.group(1))
        if not artifact or not version:
            failures.append(prefix +
                            "cannot derive the packaged-jar path from "
                            "plumb-main/pom.xml -- the check cannot verify "
                            "the document's verification command")
        else:
            expected = "plumb-main/target/%s-%s-all.jar" % (
                artifact.group(1), version.group(1))
            # The document must name the packaged jar at least once.
            if "plumb-main/target/" not in doc:
                failures.append(prefix +
                                "the document does not tell the reader how to "
                                "run the packaged jar, so the verification "
                                "step is missing")
            elif expected not in doc:
                failures.append(prefix +
                                "the document tells the reader to run a jar "
                                "that is not the one the build produces "
                                "(expected %s)" % expected)
            else:
                note("checked the packaged-jar path (%s)" % expected)

            # And it must not ALSO name a jar that the build does not
            # produce. Requiring the right path is not enough: a
            # document that names both the right jar and a stale one
            # still sends the reader to a command that fails.
            named_jars = sorted(set(re.findall(
                r"plumb-main/target/[\w.\-]*\.jar", doc)))
            for named in named_jars:
                if named != expected:
                    failures.append(prefix +
                                    "the document tells the reader to run %s, "
                                    "but the build produces %s"
                                    % (named, expected))
            note("checked %d named jar path(s)" % len(named_jars))

    # (3) The counts the document states.
    concrete, has_base = dfa_checker_prompt_overrides()
    style_overrides = style_prompt_overrides()
    overloads = visit_overload_count()

    # (3a) The DfaChecker override count.
    #
    #      The count is stated in prose in both languages, but the
    #      wording differs ("20 `DfaChecker`s plus `TaintedCheckerBase`"
    #      vs "20 个 `DfaChecker` + `TaintedCheckerBase`"). The number
    #      is the same in both, and it is the number that has to be
    #      right -- so the pattern accepts either wording.
    m = re.search(
        r"(\d+)\s*(?:个\s*)?`?DfaChecker`?s?\s*(?:plus|\+|个|，|,)\s*"
        r"`?TaintedCheckerBase`?", doc)
    if not m:
        failures.append(prefix +
                        "the document does not state the DfaChecker prompt "
                        "override count in the form the gate expects")
    else:
        claimed = int(m.group(1))
        if claimed != len(concrete):
            failures.append(prefix +
                            "the document says %d DfaChecker prompt overrides, "
                            "the source has %d" % (claimed, len(concrete)))
        else:
            note("checked the DfaChecker override count (%d)" % claimed)
    if not has_base:
        failures.append(prefix +
                        "the document says TaintedCheckerBase supplies the "
                        "generated prompt, but it does not override getAiPrompt")
    elif "TaintedCheckerBase" not in doc:
        failures.append(prefix +
                        "the document no longer names TaintedCheckerBase, so "
                        "the generated prompt source is undocumented")

    # (3b) The plumb-style overrides are a LIST, not a number: every
    #      file the source has has to be named.
    for rel in style_overrides:
        base = rel.rsplit("/", 1)[-1]
        if base not in doc:
            failures.append(prefix +
                            "the source has a prompt override in %s, "
                            "but the document does not name it" % base)
    note("checked %d plumb-style prompt override(s)" % len(style_overrides))

    # (3c) The dispatch mechanism. The document says AstDispatcher
    #      builds its table by REFLECTING over the visit overloads the
    #      checker declares. That is a claim about the code, and the
    #      code has to agree.
    dispatcher = STYLE / "AstDispatcher.java"
    if dispatcher.is_file():
        body = strip_comments(read(dispatcher))
        reflects = bool(re.search(r"getMethods\s*\(|getDeclaredMethods\s*\(",
                                  body))
        if reflects:
            stated = re.search(
                r"reflect\w*\s*(?:over|on)|反射", doc, re.I)
            if not stated:
                failures.append(prefix +
                                "the document does not describe the "
                                "AstDispatcher dispatch as reflection, but "
                                "the dispatcher reflects over the checkers")
        else:
            failures.append(prefix +
                            "the document describes the AstDispatcher as "
                            "reflecting over the checkers, but it does not")
    else:
        failures.append(prefix + "%s does not exist" % dispatcher)

    # (3d) The visit overload count. The document must not state a
    #      node-type count that the source does not have. The wording
    #      differs between the two languages, so the check is on the
    #      NUMBER next to the node-type phrase -- and if the document
    #      states no number at all, that is fine, because the
    #      reflection mechanism is the load-bearing claim.
    if overloads is not None:
        m = re.search(r"(\d+)\s*(?:node types|种节点)", doc)
        if m and int(m.group(1)) != overloads:
            failures.append(prefix +
                            "the document says %s node types, AstChecker "
                            "declares %d visit overloads"
                            % (m.group(1), overloads))
        elif m:
            note("checked the visit-overload count (%d)" % overloads)

    # (4) The API the document quotes must be declared.
    api_checked = 0
    for rel, symbol in API:
        p = REPO / rel
        if not p.is_file():
            failures.append(prefix + "%s does not exist" % rel)
            continue
        body = strip_comments(read(p))
        if not re.search(r"\b" + re.escape(symbol) + r"\s*\(", body):
            failures.append(prefix +
                            "%s does not declare %s -- the document quotes it"
                            % (rel, symbol))
            continue
        api_checked += 1
    note("checked %d API symbol(s)" % api_checked)

    # (5) The names the document says do not exist must not exist.
    #
    #     And the document has to keep SAYING they do not exist. The
    #     file check above is one-directional: it catches a file that
    #     appeared. It cannot catch a document that dropped the
    #     warning, or that started claiming the name IS real.
    for rel, why in MUST_NOT_EXIST:
        if (REPO / rel).is_file():
            failures.append(prefix +
                            "%s now exists, but the document says it does not "
                            "(%s)" % (rel, why))
        stem = rel.rsplit("/", 1)[-1].rsplit(".", 1)[0]
        denied = False
        claimed = False
        for raw in re.split(r"\n\s*\n", doc):
            if stem not in raw:
                continue
            paragraph = re.sub(r"\s+", " ", raw)
            if re.search(r"(no\s+|not\s+|never\s+|there is no|没有|不存在|"
                         r"从未存在|不是)", paragraph, re.I):
                denied = True
            # A paragraph that claims the class HOLDS something is
            # claiming it exists.
            if re.search(re.escape(stem) + r"[^.]{0,40}\b(holds?|holds the|"
                         r"carries?|contains?)\b", paragraph, re.I):
                claimed = True
        if not denied:
            failures.append(prefix +
                            "the document no longer says %s does not exist, "
                            "so the reader is not warned about it" % stem)
        if claimed:
            failures.append(prefix +
                            "the document claims %s holds something, but it "
                            "does not exist" % stem)
    note("checked %d documented absence(s)" % len(MUST_NOT_EXIST))

    # (6) The generated file must be marked as generated, and the
    #     document must say so.
    #
    #     A reader who hand-edits an auto-generated file loses the edit
    #     on the next regeneration, and nothing tells them.
    for rel in GENERATED:
        p = REPO / rel
        if not p.is_file():
            continue
        body = read(p)
        if "AUTO-GENERATED" not in body:
            failures.append(prefix +
                            "%s is not marked as auto-generated -- the "
                            "document tells the reader not to edit it by hand"
                            % rel)
        if "gen_categories.py" not in doc:
            failures.append(prefix +
                            "the document does not say that %s is generated "
                            "by scripts/gen_categories.py"
                            % rel.rsplit("/", 1)[-1])
    note("checked %d generated file(s)" % len(GENERATED))

    # (7) The document must not tell the reader to hand-edit a
    #     generated file. A `m.put(...)` instruction aimed at
    #     CheckerCategories is exactly that.
    #
    #     The `m.put` is matched WITHOUT requiring backticks: the
    #     instruction is just as wrong whether the call is quoted or
    #     not, and requiring the quoting would let the unquoted form
    #     through.
    #
    #     The unit is the SENTENCE that holds the `m.put`, not a
    #     character window: a wide window picks up the generator name
    #     from a neighbouring sentence and lets the instruction
    #     through.
    for m in re.finditer(r"m\.put\s*\(", doc):
        sentence = sentence_around(doc, m.start(), m.end())
        if "CheckerCategories" in sentence and "gen_categories" not in sentence:
            failures.append(prefix +
                            "the document tells the reader to add an m.put to "
                            "CheckerCategories, but that file is generated -- "
                            "the edit would be lost")
    note("checked the hand-edit instructions")

    # (7a) The same check for the instruction that names the generator
    #      but still points at the generated file as the thing to edit.
    #      The wording differs between the languages, so both a
    #      "run the generator" phrasing and the Chinese one count.
    #
    #      Again the unit is the sentence, not a window.
    for m in re.finditer(r"[Cc]heckerCategories", doc):
        sentence = sentence_around(doc, m.start(), m.end())
        # An instruction to ADD an entry by hand.
        if re.search(r"(加|add)\s*(一[条个]|an?\s+)?\s*`?m\.put", sentence,
                     re.I):
            if "gen_categories" not in sentence:
                failures.append(prefix +
                                "the document tells the reader to hand-add an "
                                "entry to CheckerCategories, but that file is "
                                "generated -- the edit would be lost")
    note("checked the hand-add instructions")

    # (7a) The same check for the instruction that names the generator
    #      but still points at the generated file as the thing to edit.
    #      The wording differs between the languages, so both a
    #      "run the generator" phrasing and the Chinese one count.
    for m in re.finditer(r"[Cc]heckerCategories", doc):
        window = doc[max(0, m.start() - 300):m.end() + 300]
        # An instruction to ADD an entry by hand.
        if re.search(r"(加|add)\s+(一[条个]|an?\s+)\s*`?m\.put|"
                     r"加\s*`?m\.put", window, re.I):
            if "gen_categories" not in window:
                failures.append(prefix +
                                "the document tells the reader to hand-add an "
                                "entry to CheckerCategories, but that file is "
                                "generated -- the edit would be lost")
    note("checked the hand-add instructions")

    # (8) The external-tool mapping. The generator's tool list is the
    #     authority; the document has to name the same tools, and it
    #     must NOT promise a column the generator does not produce.
    gen_tools = generator_external_tools()
    if gen_tools is None:
        failures.append(prefix +
                        "cannot read the external-tool list out of "
                        "scripts/gen_rules_doc.py -- the check cannot verify "
                        "the document's claim")
    else:
        for tool in gen_tools:
            if tool not in doc:
                failures.append(prefix +
                                "the document does not name %s, but the rule "
                                "catalogue maps onto it" % tool)
        note("checked %d external tool(s)" % len(gen_tools))
        # Every tool the document PROMISES as covered has to be one the
        # generator maps onto. A tool that is not in the generator's
        # list has no column in the generated table, so a document that
        # promises one sends the reader looking for it.
        #
        # The check is positive: it reads the tools the document names
        # as covered and asks whether the generator covers them too.
        # That needs no list of the tools to REJECT, so no excluded tool
        # has to be named anywhere -- not in the document, and not here.
        promised = promised_external_tools(doc)
        for tool in sorted(promised):
            if tool not in gen_tools:
                failures.append(prefix +
                                "the document promises a %s column, but the "
                                "rule catalogue does not map onto it -- the "
                                "generated table has no such column" % tool)
        note("checked %d promised external tool(s)" % len(promised))

    # (8a) The distinction the document draws: `RuleDef.aliases` holds
    #      Plumb's OWN spellings, and the external mapping is a
    #      SEPARATE, generated thing.
    #
    #      The source has to agree: the alias list of a real AST rule
    #      has to be Plumb-shaped (rule-id-like), not a tool-prefixed
    #      external id.
    alias_info = rule_def_alias_list()
    if alias_info is None:
        failures.append(prefix +
                        "cannot read the quoted RuleDef out of "
                        "EmptyCatchBlockCheck.java -- the check cannot verify "
                        "the document's alias example")
    else:
        (rule_id, short_name), aliases = alias_info
        # The document has to quote the rule id and the short name in
        # the EXACT form the source declares them. A presence check
        # alone would accept a document that quotes the right id in
        # one place and a wrong one in the snippet.
        quoted = re.findall(r'new RuleId\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)',
                            doc)
        if not quoted:
            failures.append(prefix +
                            "the document no longer quotes a RuleId "
                            "constructor, so the rule example is gone")
        elif (rule_id, short_name) not in quoted:
            failures.append(prefix +
                            "the document quotes the RuleId %r, but the "
                            "source declares %r"
                            % (quoted[0], (rule_id, short_name)))
        if rule_id not in doc:
            failures.append(prefix +
                            "the document quotes the rule id %s, but the "
                            "source does not declare it" % rule_id)
        if short_name not in doc:
            failures.append(prefix +
                            "the document quotes the short name %s, but the "
                            "source does not declare it" % short_name)
        for alias in aliases:
            # The alias has to appear as a WHOLE token. A plain
            # substring test would accept a document that quotes
            # "empty-catch-block-v2" as containing "empty-catch-block".
            if not re.search(r'"' + re.escape(alias) + r'"', doc):
                failures.append(prefix +
                                "the document quotes the alias %s, but the "
                                "source does not declare it" % alias)
        # The quoted aliases must NOT be tool-prefixed external ids.
        # If they were, the document's central distinction -- that
        # `RuleDef.aliases` is Plumb's own spellings -- would be false.
        for alias in aliases:
            if re.match(r"^(?:PMD|SpotBugs|Checkstyle|SonarQube|FindBugs)\s*:",
                        alias):
                failures.append(prefix +
                                "the document claims RuleDef.aliases holds "
                                "Plumb's own spellings, but the source alias "
                                "%s is a tool-prefixed external id" % alias)
        note("checked the quoted RuleDef (%s, %d alias(es))"
             % (rule_id, len(aliases)))

    # (8b) The document has to keep SAYING the distinction, in either
    #      language, and the direction of the statement matters.
    #
    #      "They are NOT PMD / SpotBugs ids" and "They ARE the PMD /
    #      SpotBugs ids" are opposite claims, and only one of them is
    #      true. A presence check cannot tell them apart.
    if not re.search(r"RuleDef\.?`?\s*aliases|aliases", doc):
        failures.append(prefix +
                        "the document no longer mentions RuleDef.aliases, so "
                        "the distinction between Plumb's own aliases and the "
                        "external mapping has been lost")
    else:
        # Read the DIRECTION of the document's claim about
        # overridability... (see below)
        #
        # The denial and the claim are read over the WHOLE document,
        # not a paragraph window: the sentence that draws the
        # distinction ("They are NOT PMD ids") can sit a code block
        # away from the sentence that introduces the alias list, and a
        # window would miss it.
        #
        # The two are still read independently: a denial anywhere
        # satisfies "the distinction is stated", and a claim ANYWHERE
        # is caught -- so a document that denies it in one paragraph
        # and claims it in another still fails.
        denied = False
        claimed = False
        for raw in re.split(r"\n\s*\n", doc):
            paragraph = re.sub(r"\s+", " ", raw)
            # A denial: "they are NOT PMD / SpotBugs ids".
            if re.search(r"(are\s+\*\*not\*\*|are\s+not|is\s+not|"
                         r"\*\*不是\*\*|不是|并非)"
                         r"[^.]{0,60}"
                         r"(PMD|SpotBugs|Checkstyle|SonarQube)", paragraph,
                         re.I):
                denied = True
            # A claim: "they ARE the PMD / SpotBugs ids".
            if re.search(r"(are\s+the|is\s+the|就是|是)"
                         r"[^.]{0,60}"
                         r"(PMD|SpotBugs|Checkstyle|SonarQube)\s*"
                         r"(ids?|equivalent)", paragraph, re.I):
                claimed = True
        if not denied:
            failures.append(prefix +
                            "the document does not say that RuleDef.aliases "
                            "is NOT an external-tool mapping -- the "
                            "distinction it is supposed to draw is missing")
        if claimed:
            failures.append(prefix +
                            "the document claims RuleDef.aliases IS an "
                            "external-tool mapping, but it holds Plumb's own "
                            "spellings")
    note("checked the alias/mapping distinction wording")

    # (8c) The document states WHICH tools the mapping covers. That
    #      list is the load-bearing claim -- a document that drops one
    #      tool from the coverage list, while still naming it in an
    #      example, has mis-described the mapping.
    #
    #      The coverage list is the sentence that says what the
    #      mapping covers. The pattern requires the mapping context
    #      alongside it, because the Chinese word for "covers" also
    #      appears in "three-level override chain" -- a paragraph about
    #      the AI prompt override chain must not be mistaken for the
    #      external-tool coverage list.
    coverage = None
    for raw in re.split(r"\n\s*\n", doc):
        paragraph = re.sub(r"\s+", " ", raw)
        if not re.search(r"(It covers|它覆盖)", paragraph):
            continue
        # It has to be about the external-tool mapping.
        if not re.search(r"(external|等价|PMD|SpotBugs|Checkstyle|SonarQube)",
                         paragraph):
            continue
        coverage = paragraph
        break
    if coverage is None:
        failures.append(prefix +
                        "the document no longer states which external tools "
                        "the rule-catalogue mapping covers")
    else:
        # The COVERAGE LIST is a sentence, not a paragraph. The
        # paragraph around it mentions the tools for other reasons --
        # the generator's own examples ("Checkstyle's `Foo`") -- and a
        # paragraph-wide test would accept a coverage list that has
        # dropped a tool.
        cover_sentence = None
        for raw in re.split(r"\n\s*\n", doc):
            flat = re.sub(r"\s+", " ", raw)
            m = re.search(r"(It covers|它覆盖)([^.]*)", flat)
            if not m:
                continue
            cover_sentence = m.group(2)
            break
        if cover_sentence is None:
            failures.append(prefix +
                            "the document does not state the external-tool "
                            "coverage list as a sentence")
        else:
            for tool in gen_tools:
                if tool not in cover_sentence:
                    failures.append(prefix +
                                    "the document's coverage list does not "
                                    "name %s, but the rule catalogue maps "
                                    "onto it" % tool)
            # Every tool the coverage sentence promises has to be one
            # the generator maps onto. A tool named in a DENIAL is
            # fine -- the document has to be explicit about what the
            # mapping does not cover -- but a tool PROMISED is not.
            for tool in promised_external_tools(cover_sentence):
                if tool not in gen_tools:
                    failures.append(prefix +
                                    "the document's coverage list promises %s, "
                                    "but the rule catalogue does not map onto "
                                    "it -- the generated table has no such "
                                    "column" % tool)
        note("checked the external-tool coverage list")

    # (9) The CWE mapping key format. The document states the key
    #      format; the YAML's own header states it too, and the two
    #      have to agree.
    cwe = RES / "cwe-mappings.yaml"
    if cwe.is_file():
        body = read(cwe)
        m = re.search(r'\{CheckerName\}(?:_)?\{?(\w*)\}?', body)
        fmt = "{CheckerName}" in body
        if not fmt:
            failures.append(prefix +
                            "the CWE mapping YAML does not document the "
                            "{CheckerName} key format the document states")
        elif "{CheckerName}" not in doc:
            failures.append(prefix +
                            "the document no longer states the {CheckerName} "
                            "CWE mapping key format")
        note("checked the CWE mapping key format")
    else:
        failures.append(prefix + "%s does not exist" % cwe)

    # (10) The sanitizer hook. The document says `isSanitizer(...)` is
    #      a private static method a subclass CANNOT override, so a new
    #      sanitizer goes in that list. The source has to agree: if the
    #      method were overridable, the document would send the reader
    #      to override a method that cannot be overridden.
    base_file = DFA / "taint" / "TaintedCheckerBase.java"
    if base_file.is_file():
        body = strip_comments(read(base_file))
        decl = re.search(
            r"(?:(private|protected|public)\s+)?(?:static\s+)?(?:final\s+)?"
            r"boolean\s+isSanitizer\s*\(", body)
        if decl is None:
            failures.append(prefix +
                            "TaintedCheckerBase does not declare isSanitizer "
                            "-- the document describes it")
        else:
            visibility = decl.group(1) or "package"
            is_static = "static" in decl.group(0)
            overridable = visibility in ("protected", "public")

            # Read the DIRECTION of the document's claim about
            # overridability. The claim lives in a paragraph, and the
            # wording differs between the two languages, so the
            # paragraph is the unit -- a sentence split would cut the
            # name off from the negation.
            #
            # "a subclass cannot override it" and "a subclass can
            # override it" are opposite claims, and only one of them
            # is true. A presence check cannot tell them apart.
            says_overridable = False
            says_not_overridable = False
            for raw in re.split(r"\n\s*\n", doc):
                if "isSanitizer" not in raw:
                    continue
                paragraph = re.sub(r"\s+", " ", raw)
                if re.search(r"(cannot\s+be\s+overridden|can't\s+be\s+overridden|"
                             r"not\s+overridable|cannot\s+override|"
                             r"覆盖不了|不能覆盖|无法覆盖|不可覆盖)",
                             paragraph, re.I):
                    says_not_overridable = True
                if re.search(r"(can\s+be\s+overridden|can\s+override|"
                             r"is\s+overridable|overridable\s+method|"
                             r"可以覆盖|可覆盖|能覆盖)",
                             paragraph, re.I):
                    says_overridable = True

            if overridable and not says_overridable:
                failures.append(prefix +
                                "the document does not say isSanitizer can be "
                                "overridden, but the source declares it %s"
                                % visibility)
            if not overridable and says_overridable:
                failures.append(prefix +
                                "the document says isSanitizer can be "
                                "overridden, but the source declares it %s"
                                % visibility)
            if not overridable and not says_not_overridable:
                failures.append(prefix +
                                "the document does not say isSanitizer cannot "
                                "be overridden, but the source declares it %s"
                                % visibility)
            if not is_static:
                failures.append(prefix +
                                "the document describes isSanitizer as static, "
                                "but the source does not")
            note("checked the sanitizer hook (visibility %s, static %s)"
                 % (visibility, is_static))
    else:
        failures.append(prefix + "%s does not exist" % base_file)

    # (11) The TaintRule metadata fields the document names. The
    #      record's components are the authority.
    taint_rule = DFA / "taint" / "TaintRule.java"
    if taint_rule.is_file():
        body = strip_comments(read(taint_rule))
        m = re.search(r"record\s+TaintRule\s*\(([^)]*)\)", body, re.S)
        if not m:
            failures.append(prefix +
                            "TaintRule is not a record with components -- the "
                            "document lists its metadata fields")
        else:
            components = re.findall(r"\b(\w+)\s*[,)]", m.group(1))
            for field in ("ruleId", "sourceKindName", "sinkFamilyKey", "cwe",
                          "domain"):
                if field not in components:
                    failures.append(prefix +
                                    "TaintRule does not declare the field %s "
                                    "-- the document lists it as the metadata"
                                    % field)
                if field not in doc:
                    failures.append(prefix +
                                    "the document no longer names the TaintRule "
                                    "field %s" % field)
            note("checked %d TaintRule metadata field(s)" % len(components))
    else:
        failures.append(prefix + "%s does not exist" % taint_rule)

    return failures


def main():
    failures = []
    for path in (DOC, ZH_DOC):
        if not path.is_file():
            print("FAIL: %s does not exist" % path)
            return 1
        print("--- %s ---" % path.name)
        failures.extend(check_document(read(path), path))

    if failures:
        print()
        print("FAIL: %d defect(s) in the rule-authoring document"
              % len(failures))
        for f in failures:
            print("  %s" % f)
        print()
        print("The rule-authoring document is a set of instructions. An")
        print("instruction that names a file that moved, or a script that")
        print("does not exist, costs the reader the whole checker they")
        print("were writing.")
        return 1

    print()
    print("OK: the rule-authoring document matches the source")
    return 0


if __name__ == "__main__":
    sys.exit(main())
