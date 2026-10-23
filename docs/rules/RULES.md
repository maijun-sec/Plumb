# Plumb 规则清单

本文档列出 Plumb 自带的所有规则、其参数、严重度、类型、CWE 标签、emit-id 命名风格，以及如何启用 / 禁用 / 修改参数。

> **自动生成文档**：
> - `doc/plumb-style-rules.md`（5600+ 行）— 每条 style rule 的 Javadoc + 等价工具（PMD/SpotBugs/SonarQube/Coverity）映射，由 `scripts/gen_rules_doc.py` 生成
> - `doc/plumb-style-rules-index.md`（508 行）— style rule 索引，由 `scripts/gen_rules_index.py` 生成
> - `juliet-coverage/data/plumb_rule_to_cwe.json`（393 entries）— rule → CWE 双向映射，由 `tmp/r837_extract_mapping.py` 从 source 自动 grep 重建（**不要人工维护**）

## 1. 数量概览（R-837 后）

| 类别 | 模块 | 数量 | 实现方式 | 阶段 |
| --- | --- | ---: | --- | --- |
| Taint sources | plumb-rules | 10 | IFDS 数据流 | taint |
| Taint sinks | plumb-rules | 17 | IFDS 数据流 | taint |
| DFA 检查器 | plumb-dfa | 36 | Intraprocedural + cross-function | dfa |
| Style / 命名 / 设计 | plumb-style | **590** | JavaParser AST visitor | style |
| DFA unified | plumb-dfa | **66** | 新一代 DFA 引擎（IFDS + 区间 + Z3） | dfa |
| **合计** | — | **~720** | — | — |

> 风格类 590 条由 `StyleCheckRegistry.discoverAvailable()` 在启动时通过 classpath 扫描动态加载；不写进 `RuleRegistry` 的常量表。**新增一个 `*Check.java` 类即可自动被注册**，但**必须**同时往 `CheckerCategories.java` 加 `m.put(...)` —— 否则静默丢失（Lesson 1319）。

### 1.1 Style 规则子目录分布（实测）

| 子目录 | 数量 | 主题 |
| --- | ---: | --- |
| `plumb-style/style/` | **352** | 编码风格（注释、Javadoc、缩进、imports、控制流、声明） |
| `plumb-style/performance/` | **63** | 性能（字符串、boxed、BigDecimal、Random） |
| `plumb-style/naming/` | **51** | 命名约定（类型、方法、字段、参数、局部变量、包） |
| `plumb-style/security/` | **40** | 安全相关（deserialization、URL sanitization） |
| `plumb-style/error/` | **35** | 错误处理（catch、finally、throw、synchronized） |
| `plumb-style/bug/` | **19** | 明确 bug 模式（compareTo、equals、null 检查） |
| `plumb-style/design/` | **18** | 设计度量（圈复杂度、耦合、深度、NPath） |
| `plumb-style/quality/` | **10** | 质量（regex confusion、ORM 模式） |
| `plumb-style/concurrency/` | **2** | 并发（servlet 原子性、singleton race） |
| `plumb-style/annotations/` | 0 | 注解 IR 工具（不是 checker） |
| **合计** | **590** | — |

### 1.2 工具覆盖（R294 达成 · 4 工具 95%+）

Plumb 对主流 SAST 工具的等价覆盖（实测 2026-08-03，R293 终止）：

| 工具 | 目标 | 实际覆盖 | 完成 R |
| --- | --- | ---: | --- |
| **PMD** | 95% | ~302 / 318 rules | R289 |
| **SonarQube java** | 95% | ~671 / 706 rules | R292 |
| **SpotBugs** | 95% | ~492 / 518 rules | R293 |
| **Checkstyle** | 95% | ~146 / 150 rules | R287 |
| **总 Plumb rules** | — | **2436** | R271–R293 |

每条 Plumb 规则通过 `RuleDef.aliases` 字段携带 PMD / SpotBugs / SonarQube / Coverity 的对应 ID（见 `doc/plumb-style-rules.md`）。迁移历史与 pitfall 见 `docs/R294-FINAL-COVERAGE-REPORT.md`。

**完整对比（实现机制 / CWE 覆盖 / Juliet 实测 / 误报率 / 性能 / AI 集成）见 `RULE-TOOL-COMPARISON.md`**。

### 1.3 按实现机制分类（4 大 taxonomy）

`docs/CHECKERS.md`（R684 终态）把所有 626 个 checker 按"实现机制"归为 4 类：

| Class | 数量 | 触发器 | 例子 | 适用场景 |
| --- | ---: | --- | --- | --- |
| **A. AST-only** (`AstChecker`) | **515** | 仅 JavaParser | `EmptyCatchBlockCheck`, `CompareStringsWithEqualsCheck` | 编码风格、命名、纯结构 bug |
| **B. DFA-direct** (`DfaChecker`) | **69** | TAC + CFG（无 taint） | `InsecureCryptoChecker`, `ReachableAssertionChecker` | 需跨语句 def-use、type-tracking、注解通道 |
| **C. Taint-primary** (`TaintedCheckerBase`) | **42** | TAC + taint engine + summary | `SqlInjectionChecker`, `CommandInjectionChecker` | source-to-sink 数据流 |
| **D. AI-primary** | 0（内置） | n/a | n/a | AI 是 dedup 层，不写新 checker |

完整 626 checker 索引见 `docs/CHECKERS.md`。其中 B/C 类的"为什么用 DFA 而不是 AST"逐条分析见 `plumb-dfa/docs/AUDIT-checkers-2026-10-12.md`（93 KB，按 DFA-真 / DFA-注解通道 / DFA-taint / SRC-正则 / AI-启发 / STUB 6 标签细分）。

### 1.4 Style 规则命名 vs DFA 规则命名的差异

| 维度 | Style (590) | DFA unified (66) | Taint (27) |
| --- | --- | --- | --- |
| 写在哪 | `plumb-style/<cat>/*Check.java` | `plumb-dfa/checkers/<family>/*Checker.java` | `plumb-rules/.../TaintRule.java` |
| 怎么注册 | classpath scan + CheckerCategories | `RuleRegistry.withBuiltinDefaults()` | `BuiltinTaintRules` |
| 命名风格 | `ast-style-<bare>-style` | `plumb.<family>.<bare>` | `TAINT_<KIND>_<NAME>` |
| 默认开/关 | 大多 on | 大多 on | 全部 on（不可单独关） |
| 加新规则步骤 | `gen_categories.py` | `RuleDef.of(...)` + `RuleRegistry.withBuiltinDefaults()` | `BuiltinTaintRules` + `TaintCatalog` |

> **R-837 P1 修过的坑**：80 个 style checker 缺 `CheckerCategories` 注册 + 缺 `rule()` method，emit 静默丢失。Lesson 1319 已加 warning log + codemod。

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

R-836 起，emit-id 改成新的三段式命名。R-791 老风格仍兼容（Lesson 1320）。

### 4.1 三种风格对照

| 来源 | 例子 | 风格 | 备注 |
| --- | --- | --- | --- |
| Style check | `ast-style-random-iv-cbc-style` | `ast-style-<bare>-style` | R-836 引入。`plumb-style/<cat>/RandomIvCbcCheck` |
| Style check (R-791 旧) | `plumb.style.random-iv-cbc` | `plumb.style.<bare>` | 仅兼容保留，新规则不再用 |
| DFA unified | `plumb.boundary.integer-overflow` | `plumb.<family>.<bare>` | `plumb-dfa/checkers/boundary/OverflowConstraintChecker` |
| DFA unified | `plumb.taint.null-dereference` | `plumb.taint.<bare>` | `plumb-dfa/checkers/taint/NullDerefChecker` |
| Taint rule | `TAINT_SINK_SQL_INJECTION` | `TAINT_<KIND>_<NAME>` | `plumb-rules/.../TaintRule.java`，带 CWE-89 |

### 4.2 yaml whitelist 写法

```yaml
rules:
  enabled:
    - ast-style-random-iv-cbc-style   # R-836 风格（推荐）
    - plumb.style.random-iv-cbc       # R791 旧风格（兼容保留）
    - random-iv-cbc                  # bare slug（也会被剥 prefix + suffix 后匹配）
    - plumb.boundary.integer-overflow  # DFA unified
    - TAINT_SINK_SQL_INJECTION        # taint rule name
    - bu-0401-weak-crypto             # UUID
```

三种风格 **任意一种** 写出来都能被 `ScanRunner.isRuleIdAllowed` 匹配（line 1874-1896 + 2459-2461）。

### 4.3 CWE mapping 的稳定 key

`juliet-coverage/data/plumb_rule_to_cwe.json` 用 **emit-id 字符串** 作 key。R-837 P0 后映射条目从 278 涨到 393（自动从 source 重建）。改 emit-id 字符串时**必须**同步更新 mapping —— 用 `tmp/r837_extract_mapping.py` 自动扫源码 rebuild，不要手改。

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

