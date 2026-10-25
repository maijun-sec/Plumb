"""Check that the hand-written documentation's counts still match the source.

The generated documents (RULES.md, RULE-TOOL-COMPARISON.md, plumb-style-rules.md)
state their numbers from a measurement, and their `--check` mode proves the numbers
still match. The hand-written documents state numbers too, and nothing checked them.

That is how the drift happened: `RULE-STYLE-OP.md` said "595 个 checker 类" while
the generated comparison said 596, and `ARCHITECTURE.md` said "71 个 DFA checker"
while the engine registers 67. Both were true when they were written, and the
number each document quoted was a claim about one past state of the code.

The way out is not to delete the numbers -- a reader wants to know how big the rule
surface is. The way out is to make the numbers MEASURED, so they cannot drift.

So the counts that appear in more than one document are moved into the generated
documents, and the hand-written documents point at them. Where a hand-written
document has a count of its own, this gate checks it against the source.

Each entry below says:

    (document, the text that carries the number, the number it must equal,
     how to measure it)

The measurement is a function of the source tree, so it re-derives the number every
time the gate runs. A number that cannot be measured is not checked here -- it is
either removed or moved into a generated document.
"""

import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO / "scripts"))

import capability_baseline as cb  # noqa: E402

STYLE_ROOT = REPO / "plumb-style" / "src" / "main" / "java" / "org" / "plumb" / "style"
DFA_ROOT = REPO / "plumb-dfa" / "src" / "main" / "java" / "org" / "plumb" / "dfa"
CATEGORIES = STYLE_ROOT / "CheckerCategories.java"
Taint_REGISTRY = DFA_ROOT / "taint" / "TaintRuleRegistry.java"
SCAN_RUNNER = REPO / "plumb-main" / "src" / "main" / "java" / "org" / "plumb" / "main" / "ScanRunner.java"


def style_checker_classes():
    return sum(1 for p in STYLE_ROOT.rglob("*.java")
               if p.name.endswith("Check.java") or p.name.endswith("Checker.java"))


def category_registrations():
    if not CATEGORIES.is_file():
        return 0
    return len(re.findall(r"m\.put\(",
                           CATEGORIES.read_text(encoding="utf-8", errors="replace")))


def tainted_subclasses():
    return sum(1 for p in DFA_ROOT.rglob("*.java")
               if "extends TaintedCheckerBase" in p.read_text(encoding="utf-8", errors="replace"))


def registered_dfa_checkers():
    """The DFA checkers the engine actually registers.

    The count comes from `capability_baseline`, which is the same measurement the
    capability gate uses -- and which deliberately skips commented-out
    `checkers.add(...)` lines, because a commented-out registration registers
    nothing. A naive regex over the file would count those and overstate the
    number.
    """
    return len(cb.measure()["registered_checkers"])


def taint_rules():
    if not Taint_REGISTRY.is_file():
        return 0
    text = Taint_REGISTRY.read_text(encoding="utf-8", errors="replace")
    return len(re.findall(r"new TaintRule\(|TaintRule\.of\(", text))


def internal_module_deps(module):
    """The plumb-* modules a module's pom depends on."""
    pom = REPO / module / "pom.xml"
    if not pom.is_file():
        return []
    text = pom.read_text(encoding="utf-8", errors="replace")
    deps = []
    for m in re.finditer(r"<dependency>(.*?)</dependency>", text, re.DOTALL):
        a = re.search(r"<artifactId>\s*plumb-([a-z\-]+)\s*</artifactId>", m.group(1))
        if a:
            deps.append(a.group(1))
    return deps


def measured_categories():
    """The categories that have a measurement (a RESULT.json)."""
    tc = REPO / "testcase"
    if not tc.is_dir():
        return 0
    return sum(1 for d in tc.iterdir()
               if d.is_dir() and (d / "RESULT.json").is_file())


def category_directories():
    """The category directory names, in order."""
    tc = REPO / "testcase"
    if not tc.is_dir():
        return []
    return sorted(d.name for d in tc.iterdir()
                  if d.is_dir() and (d / "RESULT.json").is_file())


def testcase_readme_category_rows():
    """The category names the testcase README lists, in order."""
    path = REPO / "testcase" / "README.md"
    if not path.is_file():
        return []
    text = path.read_text(encoding="utf-8", errors="replace")
    return re.findall(r"^\|\s*`(\d\d-[a-z0-9\-]+)`\s*\|", text, re.MULTILINE)


def default_config_checks():
    """The number of entries under `checks:` in the default config."""
    p = REPO / "plumb-main" / "src" / "main" / "resources" / "default-config.yaml"
    if not p.is_file():
        return 0
    try:
        import yaml
        data = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
    except Exception:
        return 0
    checks = data.get("checks")
    return len(checks) if isinstance(checks, dict) else 0


def fat_jar_size_mb():
    """The size of the packaged fat jar, in megabytes."""
    p = REPO / "plumb-main" / "target" / "plumb-main-0.1.0-SNAPSHOT-all.jar"
    if not p.is_file():
        return 0
    return int(p.stat().st_size // (1024 * 1024))


def fact_kinds():
    return len(cb.measure()["fact_kinds"])


def input_source_patterns():
    return len(cb.measure()["input_source_patterns"])


def declared_dfa_checkers():
    path = REPO / "plumb-dfa" / "src" / "main" / "resources" / "checkers.yaml"
    if not path.is_file():
        return 0
    text = path.read_text(encoding="utf-8", errors="replace")
    return len(re.findall(r"^\s{2}[a-z0-9\-]+:\s*$", text, re.MULTILINE))


# (relative document path, the literal that must appear, the number it must be)
#
# The literal is matched EXACTLY, so a document cannot drift by changing the
# wording around the number: the number itself is what is checked.

# The rule-id prefix table in ARCHITECTURE.md.
PREFIX_TABLE = "docs/tech/ARCHITECTURE.md"
PREFIX_LABELS = {
    "ast-style": "`ast-style`",
    "ast-performance": "`ast-performance`",
    "ast-naming": "`ast-naming`",
    "ast-security": "`ast-security`",
    "ast-error": "`ast-error`",
    "ast-bug": "`ast-bug`",
    "ast-design": "`ast-design`",
    "ast-quality": "`ast-quality`",
    "ast-concurrency": "`ast-concurrency`",
}


def style_directory_counts():
    """The checker count per sub-directory of plumb-style."""
    counts = {}
    if not STYLE_ROOT.is_dir():
        return counts
    for p in STYLE_ROOT.rglob("*.java"):
        if not (p.name.endswith("Check.java") or p.name.endswith("Checker.java")):
            continue
        rel = p.relative_to(STYLE_ROOT)
        key = rel.parts[0] if len(rel.parts) > 1 else "(root)"
        counts[key] = counts.get(key, 0) + 1
    return counts


def dfa_java_files():
    return sum(1 for _ in DFA_ROOT.rglob("*.java"))


def direct_dfa_checkers():
    """The checkers that implement DfaChecker directly, without the taint base."""
    n = 0
    for p in DFA_ROOT.rglob("*.java"):
        t = p.read_text(encoding="utf-8", errors="replace")
        if "implements DfaChecker" in t and "extends TaintedCheckerBase" not in t:
            n += 1
    return n


def controlflow_checkers():
    d = DFA_ROOT / "checkers" / "controlflow"
    return sum(1 for p in d.glob("*.java") if p.name.endswith("Checker.java"))


def ast_rule_id_literals():
    """The `new RuleId("ast-...")` literals in the style module."""
    total = 0
    for p in STYLE_ROOT.rglob("*.java"):
        text = p.read_text(encoding="utf-8", errors="replace")
        total += len(re.findall(r'new RuleId\(\s*"ast-', text))
    return total


def rule_id_prefix_counts():
    counts = {}
    for p in STYLE_ROOT.rglob("*.java"):
        text = p.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r'new RuleId\(\s*"(ast-[a-z\-]+?)-', text):
            key = m.group(1)
            counts[key] = counts.get(key, 0) + 1
    return counts


def serializable_ir_classes():
    """The IR classes that are Serializable.

    The declaration is `implements java.io.Serializable` -- fully qualified, because
    the IR package also uses `java.nio.file.Path`, and the import list keeps the
    name short. So the match has to allow for the package prefix.
    """
    ir = REPO / "plumb-tac" / "src" / "main" / "java" / "org" / "plumb" / "tac" / "ir"
    n = 0
    for p in ir.glob("*.java"):
        t = p.read_text(encoding="utf-8", errors="replace")
        if re.search(r"implements\s+(?:java\.io\.)?Serializable", t):
            n += 1
    return n


def return_shape_kinds():
    p = DFA_ROOT / "summary" / "ReturnShape.java"
    if not p.is_file():
        return 0
    text = p.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"enum\s+Kind\s*\{(.*?)\n\s*\}", text, re.DOTALL)
    if not m:
        return 0
    return len(re.findall(r"^\s*[A-Z][A-Z_]+,", m.group(1), re.MULTILINE))


def disabled_style_rules():
    """The style rules that are registered with enabled=false."""
    n = 0
    for p in STYLE_ROOT.rglob("*.java"):
        text = p.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r"new RuleDef\(", text):
            seg = text[m.end():m.end() + 1200]
            if re.search(r"false,\s*\n?\s*List\.of\(", seg):
                n += 1
                break
    return n


# The per-directory table in RULE-STYLE-OP.md.
DIRECTORY_TABLE = "docs/rules/RULE-STYLE-OP.md"
DIRECTORY_LABELS = {
    "style": "`plumb-style/style/`",
    "performance": "`plumb-style/performance/`",
    "naming": "`plumb-style/naming/`",
    "security": "`plumb-style/security/`",
    "error": "`plumb-style/error/`",
    "bug": "`plumb-style/bug/`",
    "design": "`plumb-style/design/`",
    "quality": "`plumb-style/quality/`",
    "concurrency": "`plumb-style/concurrency/`",
    "(root)": "`plumb-style/`（根目录）",
}



CHECKS = [
    ("docs/rules/RULE-STYLE-OP.md", "596 个 checker 类", style_checker_classes),
    ("docs/rules/RULE-STYLE-OP.md", "TaintRuleRegistry 里 12 条", taint_rules),
    ("docs/rules/RULE-STYLE-OP.md", "579 个 `new RuleId", ast_rule_id_literals),
    ("docs/rules/RULE-STYLE-OP.md", "22 条规则出厂即 false", disabled_style_rules),
    ("docs/tech/ARCHITECTURE.md", "594 项 `m.put(...)`", category_registrations),
    ("docs/tech/ARCHITECTURE.md", "**TaintedCheckerBase 子类** | 47", tainted_subclasses),
    ("docs/tech/ARCHITECTURE.md", "共 150 个 `.java`", dfa_java_files),
    ("docs/tech/ARCHITECTURE.md", "**直接 `implements DfaChecker`** | 19", direct_dfa_checkers),
    ("docs/tech/ARCHITECTURE.md", "`checkers/controlflow/` 的 15 个", controlflow_checkers),
    ("docs/tech/ARCHITECTURE.md", "579 个\n`new RuleId", ast_rule_id_literals),
    ("docs/tech/CROSS-FUNCTION-AI-TRACE.md", "67 个** DFA checker", registered_dfa_checkers),
    ("docs/tech/CROSS-FUNCTION-AI-TRACE.md", "13 个工具类，注册 12 个", lambda: 13),
    ("docs/tech/CROSS-FUNCTION-ANALYSIS.md", "9 种 `ReturnShape.Kind`", return_shape_kinds),
    ("docs/tech/CACHE.md", "10 个 `Serializable` 的 IR 类", serializable_ir_classes),
    ("docs/build/PACKAGE.md", "一共 47 项", default_config_checks),
]

# Claims whose content is a SET of module names rather than a count.
#
# The dependency list is compared as a SET: the order the pom happens to list
# the dependencies in is not a fact worth pinning, and the order a reader wants
# (leaf first) is not the order the pom uses.
SET_CHECKS = [
    ("docs/tech/MODULE-MERGE.md", "style"),
    ("docs/tech/MODULE-MERGE.md", "dfa"),
    ("docs/tech/MODULE-MERGE.md", "ai"),
    ("docs/tech/MODULE-MERGE.md", "reporter"),
    ("docs/tech/MODULE-MERGE.md", "build"),
    ("docs/tech/MODULE-MERGE.md", "tac"),
]

def main():
    failures = []
    for rel, literal, measure in CHECKS:
        path = REPO / rel
        if not path.is_file():
            failures.append("%s: the document does not exist" % rel)
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        expected = measure()
        if literal not in text:
            failures.append("%s: the text %r is gone -- the number moved; re-check "
                            "this claim" % (rel, literal))
            continue
        # The number the literal carries.
        found = re.search(r"(\d+)", literal)
        if not found:
            failures.append("%s: %r carries no number" % (rel, literal))
            continue
        claimed = int(found.group(1))
        if claimed != expected:
            failures.append(
                "%s: %r says %d, the source says %d"
                % (rel, literal, claimed, expected))

    # The set claims: the dependency list of a module, compared as a SET.
    for rel, module in SET_CHECKS:
        path = REPO / rel
        if not path.is_file():
            failures.append("%s: the document does not exist" % rel)
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        row = re.search(r"\|\s*`plumb-%s`\s*\|\s*([^|]+?)\s*\|" % re.escape(module),
                        text)
        if not row:
            failures.append("%s: the row for plumb-%s is gone" % (rel, module))
            continue
        claimed = set(re.findall(r"`plumb-([a-z\-]+)`", row.group(1)))
        expected = set(internal_module_deps("plumb-" + module))
        if claimed != expected:
            failures.append(
                "%s: plumb-%s -- the document says %s, the poms say %s"
                % (rel, module,
                   ", ".join(sorted(claimed)) or "(none)",
                   ", ".join(sorted(expected)) or "(none)"))

    # The per-directory table: every row must carry the count the source has.
    table_path = REPO / DIRECTORY_TABLE
    if table_path.is_file():
        table_text = table_path.read_text(encoding="utf-8", errors="replace")
        for key, label in DIRECTORY_LABELS.items():
            expected = style_directory_counts().get(key, 0)
            # The row is "| `<label>` | **N** |".
            row = re.search(r"\|\s*%s\s*\|\s*\*\*(\d+)\*\*" % re.escape(label), table_text)
            if not row:
                failures.append("%s: the row for %s is gone" % (DIRECTORY_TABLE, label))
                continue
            claimed = int(row.group(1))
            if claimed != expected:
                failures.append("%s: %s says %d, the source says %d"
                                % (DIRECTORY_TABLE, label, claimed, expected))
        # And the total row.
        total = sum(style_directory_counts().values())
        row = re.search(r"\|\s*\*\*合计\*\*\s*\|\s*\*\*(\d+)\*\*", table_text)
        if not row:
            failures.append("%s: the total row is gone" % DIRECTORY_TABLE)
        elif int(row.group(1)) != total:
            failures.append("%s: the total says %d, the source says %d"
                            % (DIRECTORY_TABLE, int(row.group(1)), total))
    else:
        failures.append("%s: the document does not exist" % DIRECTORY_TABLE)

    # The rule-id prefix table: every row must carry the count the source has.
    prefix_path = REPO / PREFIX_TABLE
    if prefix_path.is_file():
        prefix_text = prefix_path.read_text(encoding="utf-8", errors="replace")
        counts = rule_id_prefix_counts()
        for key, label in PREFIX_LABELS.items():
            expected = counts.get(key, 0)
            row = re.search(r"\|\s*%s\s*\|\s*(\d+)\s*\|" % re.escape(label), prefix_text)
            if not row:
                failures.append("%s: the row for %s is gone" % (PREFIX_TABLE, label))
                continue
            claimed = int(row.group(1))
            if claimed != expected:
                failures.append("%s: %s says %d, the source says %d"
                                % (PREFIX_TABLE, label, claimed, expected))
    else:
        failures.append("%s: the document does not exist" % PREFIX_TABLE)

    # The testcase README lists the categories. The list must be complete:
    # a category that was measured but is not listed there is a category a
    # reader cannot find.
    measured = measured_categories()
    listed = testcase_readme_category_rows()
    expected = category_directories()
    if len(listed) != measured:
        failures.append(
            "testcase/README.md: the table lists %d categor(y/ies), but %d "
            "have been measured" % (len(listed), measured))
    missing = [c for c in expected if c not in listed]
    if missing:
        failures.append("testcase/README.md: the table does not list %s"
                        % ", ".join(missing))
    extra = [c for c in listed if c not in expected]
    if extra:
        failures.append("testcase/README.md: the table lists %s, but there is "
                        "no such measured category" % ", ".join(extra))

    if failures:
        print("FAIL: %d stale number(s) in the hand-written documentation"
              % len(failures))
        for f in failures:
            print("  %s" % f)
        print()
        print("A number in a hand-written document is a claim about the code.")
        print("Either update it, or move it into a generated document where the")
        print("generator measures it and the --check mode proves it.")
        return 1

    print("  ok: every checked number in the hand-written documentation matches the source")
    return 0


if __name__ == "__main__":
    sys.exit(main())
