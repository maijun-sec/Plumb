#!/usr/bin/env python3
"""
Generate a markdown catalog of every plumb-style AstChecker.

For each rule we extract:
  - Rule ID  (pl-NNNN-*)
  - Rule class  (FQN)
  - Category  (bug / design / error / naming / performance / security / style)
  - Mapping to PMD / SpotBugs / Checkstyle / SonarQube
    (parsed from the class Javadoc and from common naming
    conventions)
  - Description  (the class Javadoc)
  - Constraints  (the rule's parameters, with name, type,
    default value, and doc string)

Output: docs/plumb-style-rules.md
"""
import re
import sys
from pathlib import Path

MAIN_ROOT = Path("plumb-style/src/main/java/org/plumb/style")
DOC_DIR = Path("docs")
RULE_ID_RE = re.compile(
    r'new RuleId\(\s*"([^"]+)",\s*"([^"]+)"\)',
    re.DOTALL,
)
# Some checkers hold the rule id in a `static final String` constant and
# pass the constant to `new RuleId(...)` instead of an inline literal.
RULE_ID_CONST_RE = re.compile(
    r'static\s+final\s+String\s+(\w+)\s*=\s*"([^"]+)"',
    re.DOTALL,
)
PKG_RE = re.compile(r"^package\s+([\w\.]+)\s*;", re.MULTILINE)
PARAMS_RE = re.compile(
    r'new RuleParameter\(\s*"([^"]+)"\s*,\s*'
    r'"([^"]*)"\s*,\s*'
    r'RuleParameter\.Type\.(\w+)\s*,\s*'
    r'"([^"]*)"',
    re.DOTALL,
)
# Match a `RuleRegistry.*` constant reference where the
# constant is something we use to look up a RuleDef. We
# accept any all-caps prefix (STYLE_, BUG_, PERF_, NAMING_,
# BIG_, RANDOM_, ..., the convention is loose). The suffix
# becomes the placeholder short name.
REGISTRY_CONST_RE = re.compile(
    r"RuleRegistry\.[A-Z][A-Z0-9]*_(?!METHOD)(\w+)"
)
# Match a `RuleRegistry.BUG_*` / `.PERF_*` / `.NAMING_*`
# constant reference (some checkers use these in addition
# to STYLE_*).
CATEGORY_BY_DIR = {
    "bug": "Bug 模式 (容易导致运行时错误的写法)",
    "design": "设计 (复杂度、耦合度)",
    "error": "错误处理 (异常、try/catch、finalize)",
    "naming": "命名 (标识符、命名风格)",
    "performance": "性能 (低效实现、可优化写法)",
    "security": "安全 (注入、硬编码、加密)",
    "style": "风格 (代码风格、格式)",
}


def find_fqn(name: str, pkg: str) -> str:
    return f"{pkg}.{name}"


def extract_class_javadoc(text: str) -> str:
    """Return the body of the class Javadoc, stripped of
    the leading ` * ` decoration and trailing ` */`."""
    # The Javadoc sits between the package + imports
    # block and the class declaration. We pick the first
    # `/**` ... `*/` block.
    m = re.search(r"/\*\*(.*?)\*/", text, re.DOTALL)
    if not m:
        return ""
    body = m.group(1)
    # Strip leading ` * ` and standalone `*` lines.
    lines = []
    for line in body.splitlines():
        s = line.strip()
        if s.startswith("* "):
            s = s[2:]
        elif s == "*":
            s = ""
        lines.append(s)
    cleaned = "\n".join(lines).strip()
    # Strip the trailing <p>Parameters:</p><ul>...</ul>
    # block — the parameter table is rendered separately
    # by the markdown emitter, and including both would be
    # noise.
    cleaned = re.sub(
        r"<p>\s*Parameters:\s*</p>\s*<ul>.*?</ul>",
        "",
        cleaned,
        flags=re.DOTALL | re.IGNORECASE,
    )
    # Strip remaining HTML tags.
    cleaned = re.sub(r"</?p\s*/?>", "", cleaned)
    cleaned = re.sub(r"<ul>", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"</ul>", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"<li>(.*?)</li>", r"- \1", cleaned, flags=re.DOTALL)
    cleaned = re.sub(r"<pre>(.*?)</pre>", r"```\n\1\n```", cleaned, flags=re.DOTALL)
    cleaned = re.sub(r"<code>(.*?)</code>", r"`\1`", cleaned)
    # Strip the {@code ...} markers — they were for
    # Javadoc rendering, not for markdown.
    cleaned = re.sub(r"\{@code\s+([^}]+)\}", r"`\1`", cleaned)
    # Unescape HTML entities.
    cleaned = cleaned.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    # Compress runs of blank lines.
    cleaned = re.sub(r"\n{3,}", "\n\n", cleaned)
    return cleaned.strip()


def extract_rule_id(text: str) -> tuple[str, str, str, str, list[str]] | None:
    """Return (pl_id, short_name, severity, type, cwes) from
    the DEFAULT_RULE constant, or None if no RuleDef is found."""
    m = RULE_ID_RE.search(text)
    if not m:
        # Some checkers declare the id (and sometimes the short name) as
        # `static final String` constants instead of inline literals:
        #     private static final String RULE_ID = "ast-security-runtime-exec";
        #     private static final String RULE_NAME = "RUNTIME_EXEC";
        #     new RuleId(RULE_ID, RULE_NAME)
        # Resolve the constants so those checkers are not dropped.
        cm = RULE_ID_CONST_RE.search(text)
        if cm:
            const_name, const_value = cm.group(1), cm.group(2)
            cm2 = re.search(
                r'new\s+RuleId\(\s*' + re.escape(const_name) + r'\s*,\s*([\w"]+)',
                text,
            )
            if cm2:
                short_tok = cm2.group(1)
                if short_tok.startswith('"'):
                    short = short_tok.strip('"')
                else:
                    sm = re.search(
                        r'static\s+final\s+String\s+' + re.escape(short_tok)
                        + r'\s*=\s*"([^"]+)"',
                        text,
                    )
                    if not sm:
                        return None
                    short = sm.group(1)
                return _finish_rule(
                    _FakeMatch(const_value, short, cm2.end()), text
                )
        # Some checkers (e.g. CatchGenericCheck) look up the
        # rule via `ctx.rules().byId(RuleRegistry.STYLE_*)`.
        # The actual rule is registered elsewhere (in
        # RuleRegistry) but we still want a doc entry, so
        # synthesise a placeholder ID and short name.
        # Look for the registration constant — they're
        # defined in plumb-rules.
        return None
    return _finish_rule(m, text)


class _FakeMatch:
    """Minimal stand-in for a re.Match with .group(1)/.group(2)/.end().

    `end` is the offset just past the matched `new RuleId(...)` call so the
    severity / type / CWE window can be read from the source that follows.
    """

    def __init__(self, g1: str, g2: str, end: int = 0) -> None:
        self._g = (g1, g2)
        self._end = end

    def group(self, n: int) -> str:
        return self._g[n - 1]

    def end(self) -> int:
        return self._end


def _finish_rule(m, text: str) -> tuple[str, str, str, str, list[str]]:
    pl_id, short = m.group(1), m.group(2)
    # Look at the surrounding RuleDef ctor to extract
    # severity, type, cwes.
    after = text[m.end():]
    window = after[:1500]
    sev_m = re.search(r"RuleDef\.Severity\.(\w+)", window)
    typ_m = re.search(r"RuleDef\.Type\.(\w+)", window)
    sev = sev_m.group(1) if sev_m else "?"
    typ = typ_m.group(1) if typ_m else "?"
    cwes = re.findall(r'List\.of\(\s*"(CWE-[\d]+)"', window)
    return pl_id, short, sev, typ, cwes


def load_registry_rules() -> dict[str, tuple[str, str, str, str, list[str]]]:
    """Index `plumb-rules/.../RuleRegistry.java` by the checker FQN it registers.

    Some checkers do not declare a `RuleDef` of their own — they look the rule
    up by id from `RuleRegistry` at report time. Their canonical metadata
    (id / short name / severity / type / cwes) lives in the registry, so the
    generator reads it from there instead of dropping the rule.

    The registry entries look like:
        register(def(
            STYLE_SYSTEM_OUT,
            "STYLE_SYSTEM_OUT",
            "Avoid System.out / System.err in production code",
            RuleDef.Severity.LOW,
            RuleDef.Type.STYLE,
            List.of(),
            "org.plumb.style.SystemOutCheck"));
    """
    registry = Path("plumb-rules/src/main/java/org/plumb/rules/RuleRegistry.java")
    if not registry.exists():
        return {}
    text = registry.read_text(encoding="utf-8", errors="ignore")
    out: dict[str, tuple[str, str, str, str, list[str]]] = {}
    pattern = re.compile(
        r'def\(\s*([A-Z][A-Z0-9_]*)\s*,\s*"([^"]+)"\s*,'
        r'\s*"((?:[^"\\]|\\.)*)"\s*,'
        r'\s*RuleDef\.Severity\.(\w+)\s*,'
        r'\s*RuleDef\.Type\.(\w+)\s*,'
        r'\s*List\.of\(([^)]*)\)\s*,'
        r'\s*"([\w.]+)"\s*\)',
        re.DOTALL,
    )
    for m in pattern.finditer(text):
        const, short, _desc, sev, typ, cwe_blob, fqn = m.groups()
        cwes = re.findall(r'"(CWE-[\d]+)"', cwe_blob)
        # The id is the constant's value; resolve it from the constant table.
        id_m = re.search(
            r'String\s+' + re.escape(const) + r'\s*=\s*"([^"]+)"', text
        )
        if not id_m:
            continue
        out[fqn] = (id_m.group(1), short, sev, typ, cwes)
    return out


def extract_description(text: str) -> str:
    """Prefer the Javadoc; fall back to the second string
    argument of the RuleDef ctor."""
    jd = extract_class_javadoc(text)
    if jd:
        # Strip the first sentence (often a tagline) and
        # use the full text as the description.
        return jd
    return ""


def extract_params(text: str) -> list[tuple[str, str, str, str]]:
    return [(m.group(1), m.group(2), m.group(3), m.group(4))
            for m in PARAMS_RE.finditer(text)]


def detect_equivalents(javadoc: str, class_name: str) -> dict[str, list[str]]:
    """Heuristically map a checker to its PMD / SpotBugs /
    Checkstyle / SonarQube equivalent. We look for explicit
    mentions in the Javadoc, and fall back to a lookup
    table derived from common naming patterns."""
    text = javadoc + " " + class_name
    out: dict[str, list[str]] = {
        "PMD": [],
        "SpotBugs": [],
        "Checkstyle": [],
        "SonarQube": [],
    }
    lower = text.lower()

    # Explicit mentions in Javadoc like "Checkstyle's `Foo`"
    # or "SonarQube S1234".
    for m in re.finditer(r"Checkstyle['\u2019]?s?\s+`?([A-Z][A-Za-z0-9]+)`?", text):
        out["Checkstyle"].append(m.group(1))
    for m in re.finditer(r"PMD['\u2019]?s?\s+`?([A-Z][A-Za-z0-9]+)`?", text):
        out["PMD"].append(m.group(1))
    for m in re.finditer(r"SpotBugs['\u2019]?s?\s+`?([A-Z][A-Za-z0-9]+)`?", text):
        out["SpotBugs"].append(m.group(1))
    for m in re.finditer(r"SonarQube['\u2019]?s?\s+`?([A-Z][A-Za-z0-9]+)`?", text):
        out["SonarQube"].append(m.group(1))
    for m in re.finditer(r"\bsonar[\s-]?java\s+(?:rule\s+)?(\d+)", text, re.IGNORECASE):
        out["SonarQube"].append(f"java:S{m.group(1)}")

    # Naming-based fallbacks
    name_to_pmd = {
        "EmptyBlockCheck": "EmptyControlStatement / EmptyCodeBlock",
        "EmptyMethodCheck": "EmptyMethodInAbstractClass",
        "UnnecessaryReturnCheck": "UnnecessaryReturn",
        "EmptyControlStatementCheck": "EmptyControlStatement",
        "UseDiamondOperatorCheck": "UseDiamondOperator",
        "UnnecessaryFullyQualifiedClassNameCheck": "UnnecessaryFullyQualifiedName",
        "ArrayNamingCheck": "ArrayNamingStyle",
        "MethodNamingConventionsCheck": "MethodNamingConventions",
        "LongVariableCheck": "LongVariable",
        "ShortVariableCheck": "ShortVariable",
        "ConstantNamingCheck": "ConstantNamingConventions",
        "LocalVariableNameCheck": "LocalVariableNamingConventions",
        "MethodNameCheck": "MethodNamingConventions",
        "ClassNamingConventionsCheck": "ClassNamingConventions",
    }
    if class_name in name_to_pmd and not out["PMD"]:
        out["PMD"].append(name_to_pmd[class_name])

    name_to_checkstyle = {
        "JavadocMethodCheck": "JavadocMethod",
        "ConstantNamingCheck": "ConstantName",
        "LocalVariableNameCheck": "LocalVariableName",
        "MethodNameCheck": "MethodName",
        "TypeNameCheck": "TypeName",
        "MemberNameCheck": "MemberName",
        "UnusedImportsCheck": "UnusedImports",
        "LineLengthCheck": "LineLength",
        "LeftCurlyCheck": "LeftCurly",
        "RightCurlyCheck": "RightCurly",
        "MethodLengthCheck": "MethodLength",
        "ParameterNumberCheck": "ParameterNumber",
        "MagicNumberCheck": "MagicNumber",
        "AvoidStarImportCheck": "AvoidStarImport",
        "RedundantImportCheck": "RedundantImport",
    }
    if class_name in name_to_checkstyle and not out["Checkstyle"]:
        out["Checkstyle"].append(name_to_checkstyle[class_name])

    name_to_spotbugs = {
        "InsecureCryptoCheck": "CUSTOM_MESSAGE_DIGEST_MD5 / CIPHER_DES",
        "EmptyCryptoCheck": "STATIC_METHOD_UNSUPPORTED",
        "SwitchDensityCheck": "SF_SWITCH_NO_DEFAULT",
    }
    if class_name in name_to_spotbugs and not out["SpotBugs"]:
        out["SpotBugs"].append(name_to_spotbugs[class_name])

    # de-dup
    for k, v in out.items():
        out[k] = sorted(set(v))
    return out


def is_checker(text: str) -> bool:
    return bool(re.search(r"\bimplements\s+AstChecker\b|\bextends\s+\w*AstChecker", text))


def collect() -> list[dict]:
    rows = []
    registry_rules = load_registry_rules()
    for p in sorted(MAIN_ROOT.rglob("*.java")):
        if "$" in p.name:
            continue
        rel = p.relative_to(MAIN_ROOT)
        simple = p.stem
        parent = rel.parent
        category = parent.parts[0] if parent.parts else "(root)"
        if category not in CATEGORY_BY_DIR:
            continue
        pkg = "org.plumb.style." + category + "." + simple
        try:
            text = p.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        if not is_checker(text):
            continue
        rule = extract_rule_id(text)
        if not rule:
            # The checker does not declare its own RuleDef — it looks the
            # rule up by id from RuleRegistry at report time. Read the
            # canonical metadata from there.
            reg = registry_rules.get(pkg) or registry_rules.get(
                "org.plumb.style." + simple
            )
            if reg is not None:
                rule = reg
            else:
                # Fall back: synthesise a minimal entry from
                # a RuleRegistry constant, if any.
                rm = REGISTRY_CONST_RE.search(text)
                if rm is None:
                    continue
                pl_id = "pl-registry-" + rm.group(1).lower()
                short = rm.group(1)
                rule = (pl_id, short, "?", "?", [])
        pl_id, short, sev, typ, cwes = rule
        params = extract_params(text)
        desc = extract_description(text)
        eqs = detect_equivalents(desc, simple)
        rows.append({
            "category": category,
            "simple": simple,
            "fqn": pkg,
            "pl_id": pl_id,
            "short": short,
            "severity": sev,
            "type": typ,
            "cwes": cwes,
            "description": desc,
            "params": params,
            "equivalents": eqs,
        })
    return rows


def render(rows: list[dict]) -> str:
    out: list[str] = []
    out.append("# plumb-style — AST 规则目录")
    out.append("")
    out.append(
        "本文档列出 `plumb-style` 模块下的所有 AST 风格/结构检查器。"
        "每个规则都对应一个 JavaParser AST 访问器（见 `AstChecker` 接口），"
        "由 `AstDispatcher` 单次遍历 CompilationUnit 触发。"
    )
    out.append("")
    out.append("## 目录")
    out.append("")
    out.append("- [架构与运行机制](#架构与运行机制)")
    out.append("- [统计概览](#统计概览)")
    out.append("- [按分类索引](#按分类索引)")
    out.append("- [规则详细列表](#规则详细列表)")
    out.append("- [新增 / 修改规则](#新增--修改规则)")
    out.append("")

    out.append("## 架构与运行机制")
    out.append("")
    out.append("**plumb-style** 是 plumb 项目的纯静态分析模块：")
    out.append("")
    out.append("```")
    out.append("  Java 源文件")
    out.append("       │")
    out.append("       ▼")
    out.append("  JavaSourceParser ──► CompilationUnit (AST)")
    out.append("       │")
    out.append("       ▼")
    out.append("  AstDispatcher.run(ctx)  ◄── 一次完整 AST 遍历")
    out.append("       │                    ◄── 对每个节点")
    out.append("       │                        调用每个 checker 的 visit(...)")
    out.append("       ▼")
    out.append("  StyleContext.findings() ──► List<Finding>")
    out.append("```")
    out.append("")
    out.append("**关键设计**：")
    out.append("")
    out.append("- `AstChecker` 是单次遍历的访问器接口。每个 checker 实现自己关心的"
                "`visit(MethodDeclaration, ctx)` / `visit(IfStmt, ctx)` / "
                "`visit(MethodCallExpr, ctx)` 等方法，未实现的方法默认 no-op。")
    out.append("- `AstDispatcher` 是单次遍历的派发器。它对每个 AST 节点，按注册顺序"
                "调用每个 checker 对应的 visit 方法。")
    out.append("- 这意味着 *N* 个 checker 遍历一份 *M* 节点的 AST，只需 *O(M·N)* 次方法分派，"
                "而不是每个 checker 独立遍历一次 (那样是 *O(M·N²)* 节点访问)。")
    out.append("- 每个 checker 通过 `ctx.report(line, col, rule, message, severity, dataflowHead)`"
                " 上报违规。`RuleDef` 提供规则 ID、严重度、类型 (BUG / STYLE / BAD_PRACTICE) 等元数据。")
    out.append("- 注册方式有三种：")
    out.append(f"  - `AstCheckerRegistry.builtins()` — 全部 {len(rows)} 条")
    out.append("  - `AstCheckerRegistry.load(\"Foo.*\")` — 按简单名 glob 过滤")
    out.append("  - `AstCheckerRegistry.byCategory(\"security\")` — 按子包分类过滤")
    # A rule id can be shared by more than one checker class when one of them
    # looks the rule up by id instead of declaring its own RuleDef.
    id_counts: dict[str, int] = {}
    for r in rows:
        id_counts[r["pl_id"]] = id_counts.get(r["pl_id"], 0) + 1
    shared = {k: v for k, v in id_counts.items() if v > 1}
    if shared:
        out.append("")
        out.append(
            f"- 下列 {len(shared)} 个规则 ID 被多个 checker 共享 —— "
            "其中一个 checker 直接内联声明 `RuleId`，另一个则通过 "
            "`ctx.rules().byId(RuleRegistry.<CONST>)` 查同一规则："
        )
        out.append("")
        for pid in sorted(shared):
            owners = sorted(
                r["simple"] for r in rows if r["pl_id"] == pid
            )
            out.append(f"  - `{pid}` — " + ", ".join(f"`{o}`" for o in owners))
    out.append("")
    # Stats
    cat_count: dict[str, int] = {}
    for r in rows:
        cat_count[r["category"]] = cat_count.get(r["category"], 0) + 1
    out.append("## 统计概览")
    out.append("")
    out.append(f"- 总规则数: **{len(rows)}**")
    out.append("")
    out.append("| 分类 | 数量 | 说明 |")
    out.append("| ---- | ---- | ---- |")
    for c in sorted(cat_count.keys()):
        out.append(f"| `{c}` | {cat_count[c]} | {CATEGORY_BY_DIR[c]} |")
    out.append("")

    # Index by category
    out.append("## 按分类索引")
    out.append("")
    by_cat: dict[str, list[dict]] = {}
    for r in rows:
        by_cat.setdefault(r["category"], []).append(r)
    for cat in sorted(by_cat.keys()):
        out.append(f"### {cat}  ({len(by_cat[cat])} 条)")
        out.append("")
        out.append("| 规则 ID | 短名 | 严重度 | 类型 | 规则类 |")
        out.append("| ------- | ---- | ------ | ---- | ------ |")
        for r in sorted(by_cat[cat], key=lambda r: r["pl_id"]):
            out.append(
                f"| `{r['pl_id']}` | `{r['short']}` | {r['severity']} | {r['type']} | "
                f"`{r['simple']}` |"
            )
        out.append("")

    # Detailed listing
    out.append("## 规则详细列表")
    out.append("")
    out.append("> 阅读约定：每条规则按 `规则 ID / 短名 / 严重度 / 类型 / 规则类` 列出，"
                "并附 ① 规则说明（来自源码 Javadoc）② 与 PMD / SpotBugs / Checkstyle /"
                " SonarQube 的等价映射 ③ 可调参数表。")
    out.append("")
    for r in rows:
        out.append(f"### {r['pl_id']}  —  `{r['short']}`")
        out.append("")
        out.append(f"- **规则类**: `{r['fqn']}`")
        out.append(f"- **分类**: `{r['category']}`")
        out.append(f"- **严重度**: {r['severity']}")
        out.append(f"- **类型**: {r['type']}")
        if r["cwes"]:
            out.append(f"- **CWE**: {', '.join(r['cwes'])}")
        eqs = r["equivalents"]
        eqs_lines = []
        for tool in ["PMD", "SpotBugs", "Checkstyle", "SonarQube"]:
            if eqs.get(tool):
                eqs_lines.append(f"  - **{tool}**: {', '.join(eqs[tool])}")
        if eqs_lines:
            out.append("- **外部等价规则**:")
            out.extend(eqs_lines)
        else:
            out.append("- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)")
        out.append("")
        out.append("**规则说明**")
        out.append("")
        # Description body
        desc = r["description"] or "(无说明)"
        # Escape pipe and brackets in markdown
        for line in desc.splitlines():
            if line.strip():
                out.append(f"> {line.strip()}")
            else:
                out.append(">")
        out.append("")
        if r["params"]:
            out.append("**规则约束 / 可调参数**")
            out.append("")
            out.append("| 参数名 | 类型 | 默认值 | 说明 |")
            out.append("| ------ | ---- | ------ | ---- |")
            for name, doc, typ, default in r["params"]:
                out.append(
                    f"| `{name}` | {typ} | `{default}` | {doc} |"
                )
            out.append("")
        else:
            out.append("**规则约束 / 可调参数**: (无)")
            out.append("")

    out.append("## 新增 / 修改规则")
    out.append("")
    out.append("新增一条规则的步骤（无需改 plumb-style 的 package 布局）：")
    out.append("")
    out.append("1. 在合适的子包下新建 `*Check.java`（如 "
                "`src/main/java/org/plumb/style/naming/MyNewCheck.java`）。"
                "package 声明要与目录一致：`package org.plumb.style.naming;`。")
    out.append("2. `implements AstChecker`，实现 `name()`、`init(StyleContext)`，以及"
                " 需要的 `visit(...)` 方法。")
    out.append("3. 在 `visit(...)` 内部用 `ctx.report(line, col, rule, msg, severity, dataflowHead)` 上报。")
    out.append("4. 在 `src/test/java/org/plumb/style/` 下加一个对应的 `MyNewCheckTest.java`"
                "（或加进 `AllCheckersSmokeTest`），用最小源码片段验证 fire / no-fire。")
    out.append("5. 跑 `python scripts/gen_rules_doc.py` 重新生成本文档。")
    out.append("")

    return "\n".join(out) + "\n"


def main() -> int:
    rows = collect()
    print(f"Collected {len(rows)} checker rows")
    DOC_DIR.mkdir(parents=True, exist_ok=True)
    md = render(rows)
    out_path = DOC_DIR / "plumb-style-rules.md"
    out_path.write_text(md, encoding="utf-8")
    print(f"Wrote {out_path}  ({len(md):,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
