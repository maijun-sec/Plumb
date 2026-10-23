# Plumb 规则清单

本文档列出 Plumb 自带的所有规则、其参数、严重度、类型、CWE 标签、emit-id 命名风格，以及如何启用 / 禁用 / 修改参数。

> **自动生成文档**：
> - `docs/plumb-style-rules.md` — 每条 style rule 的 Javadoc + 等价工具（PMD/SpotBugs/SonarQube/Coverity）映射，由 `scripts/gen_rules_doc.py` 生成
> - `docs/plumb-style-rules-index.md` — style rule 索引，由 `scripts/gen_rules_index.py` 生成
> - `juliet-coverage/data/plumb_rule_to_cwe.json`（367 entries）— rule → CWE 映射，**人工维护**（仓库里没有自动 rebuild 脚本）
>
> 本文档里的每个数字都可以从下列来源复现：
> `plumb-dfa/src/main/resources/sink-families.yaml`、
> `plumb-dfa/src/main/resources/checkers.yaml`、
> `plumb-style/src/main/java/org/plumb/style/**`、
> `plumb-main/src/main/java/org/plumb/main/ScanRunner.java`。

## 1. 数量概览

| 类别 | 模块 | 数量 | 实现方式 | 阶段 |
| --- | --- | ---: | --- | --- |
| Taint source 模式 | plumb-dfa | **19** | `sink-families.yaml` 的 5 个 `taint.sources.*` family | taint |
| Taint sink 模式 | plumb-dfa | **397** | `sink-families.yaml` 的 34 个 `*.sinks` family | taint |
| DFA checker（YAML 声明） | plumb-dfa | **16**（14 启用 / 2 禁用） | `checkers.yaml` | dfa |
| DFA checker（实际注册） | plumb-main | **66** | `ScanRunner.buildDfaCheckers()` | dfa |
| Style / 命名 / 设计 | plumb-style | **582** | JavaParser AST visitor | style |

> 以上数字均为**从配置文件实测**，不是手工维护：
> - source/sink 模式：`plumb-dfa/src/main/resources/sink-families.yaml`（39 个 family，
>   19 条 source + 397 条 sink 正则）
> - DFA checker：`plumb-dfa/src/main/resources/checkers.yaml:19-179`（16 项）
> - style 规则：`AstCheckerRegistry.builtins()` 启动时 classpath 扫描动态加载，不写进
>   `RuleRegistry` 常量表。**新增一个 `*Check.java` 类即可自动注册**，但**必须**同时往
>   `CheckerCategories.java` 加 `m.put(...)` —— 否则静默丢失（Lesson 1319）。
>
> ⚠️ **不要用 `plumb-rules` 的 `RuleRegistry.java` 推断规则总数**：它有 84 处 `register(...)`，
>   其中大量 `org.plumb.dfa.checker.*` / `org.plumb.dfa.taint.*` 类名在 `plumb-dfa` 下并不存在
>   （例如 `:1010` 指向 `org.plumb.dfa.taint.TaintAnalysis`），该 registry 已陈旧。

### 1.1 Style 规则子目录分布（实测）

按 `plumb-style/src/main/java/org/plumb/style/**` 里声明了 `implements AstChecker` /
`extends AstChecker` 的类统计：

| 子目录 | 数量 | 主题 |
| --- | ---: | --- |
| `plumb-style/style/` | **354** | 编码风格（注释、Javadoc、缩进、imports、控制流、声明） |
| `plumb-style/performance/` | **63** | 性能（字符串、boxed、BigDecimal、Random） |
| `plumb-style/naming/` | **51** | 命名约定（类型、方法、字段、参数、局部变量、包） |
| `plumb-style/security/` | **41** | 安全相关（deserialization、URL sanitization） |
| `plumb-style/error/` | **35** | 错误处理（catch、finally、throw、synchronized） |
| `plumb-style/bug/` | **20** | 明确 bug 模式（compareTo、equals、null 检查） |
| `plumb-style/design/` | **18** | 设计度量（圈复杂度、耦合、深度） |
| `plumb-style/quality/` | **10** | 质量（regex confusion、ORM 模式） |
| `plumb-style/concurrency/` | **2** | 并发（servlet 原子性、singleton race） |
| `plumb-style/`（根目录） | **1** | `ObjectHijackCheck`（不属于任何子目录分类） |
| `plumb-style/annotations/` | 0 | 注解 IR 工具（不是 checker） |
| **合计** | **595** | — |

`CheckerCategories.CHECKER_CATEGORIES` 里登记了 **593** 条 —— 差 2 条：
`ObjectHijackCheck` 没有登记（`byCategory(...)` 加载不到它），
`UseObjectForClearerAPI` 和 `IncorrectReturnValueCheckChecker` 的类名不以
`Check` 结尾（`*Check.java` glob 匹配不到）。详见
[`../tech/STYLE-RULES.md`](../tech/STYLE-RULES.md) 第 6 节。

### 1.2 外部工具等价覆盖

每条 Plumb 规则通过 `RuleDef.aliases` 字段携带 PMD / SpotBugs / SonarQube / Coverity
的对应 ID。**每条规则实际映射到哪些外部工具 ID，见
[`../plumb-style-rules.md`](../plumb-style-rules.md) 的「外部等价规则」小节** ——
那是由 `scripts/gen_rules_doc.py` 从源码里的 aliases / Javadoc 解析出来的，
不需要手工维护对照表。

覆盖比例随规则集变化，本文档不再手写一个会过期的百分比。

**完整对比（实现机制 / CWE 覆盖 / Juliet 实测 / 误报率 / 性能 / AI 集成）见 `RULE-TOOL-COMPARISON.md`**。

### 1.3 按实现机制分类

checker 按"跑在哪一层"分成两大类，AI 不构成第三类——它是 findings 的后置 dedup 层，
不写新 checker：

| 类别 | 位置 | 数量（实测） | 触发器 | 适用场景 |
| --- | --- | ---: | --- | --- |
| **Style**（AST-only） | `plumb-style/` | **595** 个 checker 类 | 仅 JavaParser | 编码风格、命名、纯结构 bug |
| **DFA** | `plumb-dfa/` | **60** 个继承 `TaintedCheckerBase` + **18** 个直接 `implements DfaChecker` | TAC + CFG（污点 / 数值 / 资源 / 控制流） | 需跨语句 def-use、type-tracking、source-to-sink 数据流 |

DFA 内部的实现形态细分见 [`../tech/ARCHITECTURE.md`](../tech/ARCHITECTURE.md) §3.0，
四类分析（污点 / 边界 / 指向 / 控制流）见 [`../tech/DATA-FLOW.md`](../tech/DATA-FLOW.md)。

### 1.4 Style 规则命名 vs DFA 规则命名的差异

| 维度 | Style | DFA checker | Taint rule |
| --- | --- | --- | --- |
| 写在哪 | `plumb-style/<cat>/*Check.java` | `plumb-dfa/checkers/<family>/*Checker.java` | `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintRuleRegistry.java` |
| 怎么注册 | classpath scan + `CheckerCategories` | `ScanRunner.buildDfaCheckers()` | `TaintRuleRegistry` 的 `m.put(...)` |
| 命名风格 | `ast-<category>-<bare>` | `dfa-taint-<bare>` | `dfa-taint-<bare>` |
| 默认开/关 | 大多 on | 受 `checkers.yaml` 的 `enabled` 控制 | 全部 on（不可单独关） |
| 加新规则步骤 | 写 `*Check.java` + 在 `CheckerCategories.java` 加 `m.put(...)` | 写 `<Name>Checker.java` + 在 `ScanRunner.buildDfaCheckers()` 加一行 + 在 `checkers.yaml` 加条目 | 在 `TaintRuleRegistry` 加一条 `m.put(...)` |

`TaintRuleRegistry` 当前有 **12** 条 taint rule：
`dfa-taint-sql-injection`、`dfa-taint-ldap-injection`、
`dfa-taint-command-injection`、`dfa-taint-format-string`、`dfa-taint-xss`、
`dfa-taint-log-injection`、`dfa-taint-path-traversal`、`dfa-taint-ssrf`、
`dfa-taint-xxe`、`dfa-taint-unsafe-deserialization`、`dfa-taint-weak-crypto`、
`dfa-taint-weak-prng`。

## 2. 严重度

`RuleDef.Severity` 定义 5 档：

| 严重度 | 含义 | 典型规则 |
| --- | --- | --- |
| `CRITICAL` | 立即修复 | `TAINT_SINK_COMMAND_INJECTION`, `HARDCODED_CREDENTIALS`, `STATIC_API_KEY`, `PREDICTABLE_KEY_PASSWORD` |
| `HIGH` | 必查 | `TAINT_SINK_SQL_INJECTION`, `WEAK_CRYPTO`, `MISSING_AUTHORIZATION`, `NULL_DEREF`, `XXE`, `ITERATOR_CONCURRENT_MODIFICATION` |
| `MEDIUM` | 应查 | `ASSIGNMENT_IN_OPERAND`, `DEAD_STORE`, `MISSING_INPUT_VALIDATION`, `INFORMATION_EXPOSURE`, `DIV_BY_ZERO`, `INT_OVERFLOW` |
| `LOW` | 建议 | `STYLE_*`, `NAMING_*`, `INFINITE_LOOP`, `EMPTY_CATCH_HANDLER` |
| `INFO` | 仅信息 | `TAINT_SOURCE_*` |

## 3. 类型

`RuleDef.Type` 13 个：

| Type | 用途 |
| --- | --- |
| `TAINT` | 数据流（source-to-sink） |
| `DFA` | 数据流（数值 / 资源） |
| `STYLE` | 编码风格 |
| `NAMING` | 命名约定 |
| `SECURITY` | 安全相关 |
| `CORRECTNESS` | 正确性 |
| `PERFORMANCE` | 性能 |
| `BUG` | 明显错误 |
| `BAD_PRACTICE` | 反模式 |
| `MULTITHREADING` | 并发 |
| `MALICIOUS_CODE` | 恶意代码 |
| `I18N` | 国际化 |
| `OTHER` | 其他 |

## 4. Emit-id 命名规范

emit-id 有四种风格。`ScanRunner.isRuleIdAllowed` 对它们做多形态匹配。

### 4.1 四种风格对照

| 来源 | 例子 | 风格 | 备注 |
| --- | --- | --- | --- |
| Style check | `ast-style-random-iv-cbc` | `ast-<category>-<bare>` | category = checker 所在子包（style / naming / security / error / bug / design / performance / quality / concurrency）。`plumb-style/style/RandomIvCheck` |
| DFA taint checker | `dfa-taint-null-dereference` | `dfa-taint-<bare>` | `plumb-dfa/checkers/taint/NullDerefChecker` |
| Taint rule（registry） | `dfa-taint-sql-injection` | `dfa-taint-<bare>` | `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintRuleRegistry.java` 的 12 条 |
| RuleRegistry UUID | `bu-0401-weak-crypto` | `bu-<NNNN>-<bare>` | `plumb-rules/.../RuleRegistry.java` |

实测 `plumb-style/src/main/java` 下 582 个 `new RuleId("...")` 字面量里，
**没有一个以 `-style` 结尾**。`ast-style-random-iv-cbc-style` 这类带 `-style`
后缀的字符串只作为 `RuleDef` 的 **alias** 保留（兼容老 YAML 白名单），
不再是任何规则的 emit-id。

### 4.2 yaml whitelist 写法

```yaml
rules:
  enabled:
    - ast-style-random-iv-cbc        # style emit-id
    - ast-style-random-iv-cbc-style  # 老 alias（兼容保留）
    - random-iv-cbc                  # bare slug（也会被剥 prefix 后匹配）
    - dfa-taint-null-dereference     # DFA taint emit-id
    - TAINT_SINK_SQL_INJECTION        # taint rule name
    - bu-0401-weak-crypto             # UUID
```

以上风格 **任意一种** 写出来都能被 `ScanRunner.isRuleIdAllowed` 匹配
（`plumb-main/.../ScanRunner.java:1882-1924`）。

### 4.3 CWE mapping 的稳定 key

`juliet-coverage/data/plumb_rule_to_cwe.json` 当前有 **367** 条映射。key 有两种形态：

- 规则短名（大写下划线），例如 `ABSENT_CODEBLOCK_IN_CASE`
- emit-id 字符串，例如 `ast-style-empty-catch-style`

两种 key 都可以被 `ScanRunner.isRuleIdAllowed` 匹配。**改 emit-id 字符串时必须同步更新
这个 mapping 文件** —— 它是人工维护的，仓库里没有自动 rebuild 脚本；漏改会让
Juliet 的 `cwe_matched` 评估路径静默跳过对应 finding。

## 5. Taint 规则（27 条）

### 5.1 Sources（10 条，全部 `INFO`）

| 规则 ID | 规则名 | CWE | 说明 |
| --- | --- | --- | --- |
| `bu-0001-taint-request-param` | `TAINT_SOURCE_REQUEST_PARAM` | CWE-20 | 来自 HTTP request param (`getParameter`, `getPathVariable`, `@PathVariable`, `@RequestParam`) |
| `bu-0002-taint-request-header` | `TAINT_SOURCE_REQUEST_HEADER` | CWE-20 | 来自 HTTP header (`getHeader`, `@RequestHeader`) |
| `bu-0003-taint-system-in` | `TAINT_SOURCE_SYSTEM_IN` | CWE-20 | 来自 `System.in` / `System.console().readLine()` |
| `bu-0004-taint-file-read` | `TAINT_SOURCE_FILE_READ` | CWE-20 | 来自 `Files.read*` / `InputStreamReader(FileInputStream)` |
| `bu-0005-taint-network-recv` | `TAINT_SOURCE_NETWORK_RECV` | CWE-20 | 来自 `Socket.getInputStream().read*` |
| `bu-0006-taint-console-read` | `TAINT_SOURCE_CONSOLE_CONSOLE_READ` | CWE-20 | 来自 `Console.readLine` / `BufferedReader.readLine` |
| `bu-0007-taint-db-result` | `TAINT_SOURCE_DB_RESULT` | CWE-20 | 来自 `ResultSet.getString` 等 |
| `bu-0008-taint-source-sensitive-data` | `TAINT_SOURCE_SENSITIVE_DATA` | CWE-532 | 来自密码 / 凭证变量（敏感数据源） |
| `bu-0009-taint-source-system-property` | `TAINT_SOURCE_SYSTEM_PROPERTY` | CWE-20 | 来自 `System.getProperty` / `System.getenv` |
| `bu-0010-taint-source-properties-file` | `TAINT_SOURCE_PROPERTIES_FILE` | CWE-20 | 来自 `Properties.getProperty` / `.get` |

### 5.2 Sinks（17 条）

| 规则 ID | 规则名 | 严重度 | CWE | 说明 |
| --- | --- | --- | --- | --- |
| `bu-0101-taint-sink-sql` | `TAINT_SINK_SQL_INJECTION` | HIGH | CWE-89 | `Statement.executeQuery` / `prepareStatement` 参数被污染 |
| `bu-0102-taint-sink-cmd` | `TAINT_SINK_COMMAND_INJECTION` | CRITICAL | CWE-78, CWE-77 | `Runtime.exec` / `ProcessBuilder.command` 参数被污染 |
| `bu-0103-taint-sink-path` | `TAINT_SINK_PATH_TRAVERSAL` | HIGH | CWE-22 | `Files.newInputStream(path)` 等 path 参数被污染 |
| `bu-0104-taint-sink-xss` | `TAINT_SINK_XSS` | HIGH | CWE-79 | `HttpServletResponse.getWriter().print(...)` 等被污染 |
| `bu-0105-taint-sink-deserialize` | `TAINT_SINK_UNSAFE_DESERIALIZE` | CRITICAL | CWE-502 | `ObjectInputStream.readObject` 参数被污染 |
| `bu-0106-taint-sink-url` | `TAINT_SINK_SSRF` | HIGH | CWE-918 | `URL.openConnection` / `openStream` 参数被污染 |
| `bu-0107-taint-sink-reflection` | `TAINT_SINK_REFLECTION` | HIGH | CWE-470 | `Class.forName` / `Method.invoke` target 被污染 |
| `bu-0108-taint-sink-log` | `TAINT_SINK_LOG_INJECTION` | LOW | CWE-117 | 日志条目被污染 |
| `bu-0109-taint-sink-xpath` | `TAINT_SINK_XPATH_INJECTION` | HIGH | CWE-643 | XPath 表达式被污染 |
| `bu-0110-taint-sink-ldap` | `TAINT_SINK_LDAP_INJECTION` | HIGH | CWE-90 | LDAP search/lookup 被污染 |
| `bu-0111-taint-sink-xml` | `TAINT_SINK_XML_INJECTION` | HIGH | CWE-91 | XML transform/parse 被污染 |
| `bu-0112-taint-sink-header` | `TAINT_SINK_HEADER_INJECTION` | MEDIUM | CWE-93 | HTTP response header 被污染 |
| `bu-0113-taint-sink-format` | `TAINT_SINK_FORMAT_STRING` | MEDIUM | CWE-134 | format string 被污染 |
| `bu-0114-taint-sink-nosql` | `TAINT_SINK_NOSQL_INJECTION` | HIGH | CWE-943 | NoSQL query 被污染 |
| `bu-0115-taint-sink-ognl` | `TAINT_SINK_OGNL_INJECTION` | CRITICAL | CWE-94, CWE-95 | OGNL/EL 表达式被污染 |
| `bu-0116-taint-sink-regex` | `TAINT_SINK_REGEX_INJECTION` | MEDIUM | CWE-1333 | 正则表达式被污染（ReDoS） |
| `bu-0117-taint-sink-open-redirect` | `TAINT_SINK_OPEN_REDIRECT` | MEDIUM | CWE-601 | 重定向 URL 被污染 |
| `bu-0708-taint-sink-ext-control` | `TAINT_SINK_EXT_CONTROL` | MEDIUM | CWE-15 | `setCatalog` / `setSchema` / JNDI bind 接受污染值 |

## 6. DFA 检查器（plumb-dfa + plumb-dfa）

| 规则 ID（emit-id） | 规则名 | 严重度 | 启用开关 | CWE | 说明 |
| --- | --- | --- | --- | --- | --- |
| `bu-0401-weak-crypto` | `WEAK_CRYPTO` | HIGH | `checks.weakCrypto` | CWE-327 | MD5/SHA-1/DES/RC4/ECB 加密 |
| `bu-0402-insecure-random` | `INSECURE_RANDOM` | MEDIUM | `checks.insecureRandom` | CWE-330 | `java.util.Random` 用于安全场景 |
| `bu-0403-predictable-seed` | `PREDICTABLE_RANDOM_SEED` | HIGH | `checks.predictableSeed` | CWE-337 | 随机数种子是编译期常量 |
| `bu-0404-predictable-key-password` | `PREDICTABLE_KEY_PASSWORD` | CRITICAL | `checks.predictableKey` | CWE-321 | 密钥是字面量 |
| `bu-0405-static-api-key` | `STATIC_API_KEY` | CRITICAL | `checks.staticApiKey` | CWE-798 | static final 字段字面量 API key |
| `bu-0501-insecure-comms` | `INSECURE_COMMUNICATION` | MEDIUM | `checks.insecureComms` | CWE-319 | `http://` / `ftp://` |
| `bu-0502-cors-misconfig` | `CORS_MISCONFIGURATION` | HIGH | `checks.corsMisconfig` | CWE-942 | CORS `*` |
| `bu-0503-insecure-cookie` | `INSECURE_COOKIE` | MEDIUM | `checks.insecureCookie` | CWE-614 | Cookie 缺 Secure/HttpOnly |
| `bu-0601-bad-shift` | `BAD_SHIFT` | MEDIUM | `checks.badShift` | CWE-758 | shift count 越界 |
| `bu-0602-infinite-loop` | `INFINITE_LOOP` | LOW | `checks.infiniteLoop` | CWE-835 | 常真循环 |
| `bu-0603-constant-condition` | `CONSTANT_CONDITION` | LOW | `checks.constantCondition` | CWE-570 | 条件是常量 |
| `bu-0406-avoid-hardcoded-ip` | `AVOID_HARDCODED_IP` | MEDIUM | `<always on>` | CWE-547 | 硬编码 IP |
| `bu-0504-default-encoding` | `DEFAULT_ENCODING` | MEDIUM | `<always on>` | CWE-176 | 默认平台编码 |
| `bu-0506-bigdecimal-double-ctor` | `BIGDECIMAL_DOUBLE_CTOR` | MEDIUM | `<always on>` | CWE-682 | `new BigDecimal(double)` |
| `bu-0400-assignment-in-operand` | `ASSIGNMENT_IN_OPERAND` | MEDIUM | `<always on>` | CWE-481 | `if (a = b)` 类 |
| `bu-0701-sensitive-data-leak` | `SENSITIVE_DATA_LEAK` | CRITICAL | `checks.sensitiveDataLeak` | CWE-200 | 敏感数据流向 log/throw/file |
| `bu-0801-field-hardcoded-password` | `FIELD_HARDCODED_PASSWORD` | CRITICAL | `checks.fieldHardcodedPassword` | CWE-798 | 字段名是 password/secret，赋字面量 |
| `bu-0901-missing-authorization` | `MISSING_AUTHORIZATION` | HIGH | `checks.missingAuthorization` | CWE-862 | Web endpoint 缺 `@PreAuthorize` 等 |
| `bu-1001-missing-input-validation` | `MISSING_INPUT_VALIDATION` | MEDIUM | `checks.missingInputValidation` | CWE-20 | Web endpoint 缺 `@Valid` |
| `bu-1101-information-exposure` | `INFORMATION_EXPOSURE` | MEDIUM | `checks.informationExposure` | CWE-200 | 端点返回 user-declared 类 |
| `plumb.div.zero` | `DIV_BY_ZERO` | MEDIUM | `checks.divByZero` | CWE-369 | 除零 / 模零 |
| `plumb.resource.leak` | `RESOURCE_LEAK` | MEDIUM | `checks.resourceLeak` | CWE-404 | 资源未关闭 |
| `plumb.taint.null-dereference` | `NULL_DEREF` | HIGH | `checks.nullDeref` | CWE-476 | 可能 NPE |
| `plumb.boundary.integer-overflow` | `INT_OVERFLOW` | MEDIUM | `checks.intOverflow` | CWE-190 | 整数溢出 |
| `plumb.boundary.integer-underflow` | `INT_UNDERFLOW` | MEDIUM | `checks.intUnderflow` | CWE-191 | 整数下溢 |
| `plumb.boundary.array-bounds` | `ARRAY_BOUNDS` | MEDIUM | `checks.arrayBounds` | CWE-129 | 数组越界 |
| `plumb.api.unchecked-return` | `UNCHECKED_RETURN` | MEDIUM | `checks.uncheckedReturn` | CWE-252 | 未检查返回值 |
| `plumb.api.incorrect-return` | `INCORRECT_RETURN` | MEDIUM | `checks.incorrectReturn` | CWE-253 | 错误检查返回值 |
| `plumb.thread.run` | `THREAD_RUN` | HIGH | `checks.threadRun` | CWE-572 | `thread.run()` 而非 `.start()` |
| `plumb.api.string-equality` | `STRING_EQUALITY` | MEDIUM | `checks.stringEquality` | CWE-597 | `==` 比较 String |
| `plumb.loop.unchecked-condition` | `UNCHECKED_LOOP_CONDITION` | MEDIUM | `checks.uncheckedLoopCondition` | CWE-606 | 循环条件未检查 |
| `plumb.alloc.uncontrolled-mem` | `UNCONTROLLED_MEM_ALLOC` | HIGH | `checks.uncontrolledMemAlloc` | CWE-789 | 内存分配未受控 |
| `plumb.numeric.truncation` | `NUMERIC_TRUNCATION` | MEDIUM | `checks.numericTruncation` | CWE-197 | 数值截断 |
| `plumb.try.completion` | `TRY_CATCH_FINALLY` | MEDIUM | `checks.tryCatchFinally` | CWE-696 | try/catch/finally 反模式 |
| `plumb.loop.off-by-one` | `OFF_BY_ONE` | MEDIUM | `checks.offByOne` | CWE-193 | off-by-one |
| `plumb.api.equals-on-null` | `EQUALS_CALLED_ON_NULL` | HIGH | `checks.equalsCalledOnNull` | CWE-253 | `equals(null)` |
| `plumb.api.compare-with-self` | `COMPARE_WITH_SELF` | LOW | `checks.compareWithSelf` | CWE-496 | 自比较 |
| `plumb.xml.xxe` | `XXE` | HIGH | `checks.xxe` | CWE-611 | XML 外部实体 |
| `plumb.finally.return` | `RETURN_FROM_FINALLY` | HIGH | `checks.returnFromFinally` | CWE-584 | finally 中 return |
| `plumb.catch.empty` | `EMPTY_CATCH_HANDLER` | LOW | `checks.emptyCatchHandler` | CWE-390 | 空 catch |
| `plumb.concurrent.iterator-mod` | `ITERATOR_CONCURRENT_MODIFICATION` | HIGH | `checks.iteratorConcurrentModification` | CWE-754 | 迭代时修改集合 |
| `plumb.collection.self-ref` | `COLLECTION_SELF_REFERENCE` | MEDIUM | `checks.collectionSelfReference` | CWE-575 | 集合自引用 |
| `plumb.cross.uncaught-exception` | `UNCAUGHT_EXCEPTION_PROPAGATOR` | MEDIUM | `checks.uncaughtExceptionPropagator` | CWE-248 | 跨函数未捕获异常 |
| `plumb.catch.generic` | `CATCH_GENERIC` | LOW | `checks.catchGeneric` | CWE-396 | 抓 Exception/Throwable |
| `plumb.api.obsolete` | `OBSOLETE_FUNCTION` | LOW | `checks.obsoleteFunction` | CWE-477 | 已废弃 API |

> 上面 36 条 DFA + 10 条 legacy 内嵌规则（`DIV_BY_ZERO` / `RESOURCE_LEAK` / ...）的完整列表见 plumb-rules/RuleRegistry.withBuiltinDefaults() 的实际装载顺序。表格里的 emit-id 与启用开关对 R837 后的所有 DFA 引擎都准确。

