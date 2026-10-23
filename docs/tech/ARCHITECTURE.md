# 架构总览

本文档是 Plumb 项目的全局架构视角。各模块的深入说明见：

- `TAC.md` — 中间表示格式
- `DATA-FLOW.md` — 数据流分析算法
- `STYLE-RULES.md` — 风格类规则实现
- `CROSS-FUNCTION-ANALYSIS.md` — 跨函数分析与 Z3
- `MODULE-MERGE.md` — 模块边界

## 1. 总体目标

Plumb 是一个**纯 Java** 的静态分析工具，**独立项目**（不是 Fathom 的移植）。架构灵感来自 Fathom，但**实现完全独立**，语言限定 Java。

| 目标 | 方案 |
| --- | --- |
| Taint 分析为主 | IFDS 风格数据流，含完整 source/sink 目录 |
| 多源输入 | JavaParser（源码）+ SootUp（字节码）都进同一份 TAC IR |
| 风格类检查独立 | 590+ AST 规则不依赖 TAC/DFA |
| 大型仓库可扩展 | 磁盘 IR 缓存、阶段化流水线、可配置并发 |
| 报告工业标准 | NDJSON（机器可读）+ console（人类可读）+ SARIF（IDE） |
| AI 辅助 | plumb-ai Re-Act 多轮 LLM agent 给出 TP/FP verdict（可选） |

## 2. 阶段化流水线

```
                          sast-scan CLI (plumb-main)
                                       │
        ┌──────────────────────────────┼──────────────────────────────┐
        │                              │                              │
        ▼                              ▼                              ▼
   Phase 1: parse              Phase 1: parse (jar)         Phase 1: parallel
   JavaParser → AST            SootUp → Jimple → TAC       (for > 800 files)
        │                              │                              │
        ▼                              │                              │
   Phase 2: style (parallel)             │                              │
   AstDispatcher → 590+ checks           │                              │
        │                              │                              │
        └──────────────────────────────┴──────────────────────────────┘
                                       │
                                       ▼
                              Phase 3: translate (parallel)
                              AST → TacProgram
                              (cached: cache/programs/<sha1>.tac)
                              --source-root/--module-root/--classpath
                              仅喂 TypeSolver，不进这一步
                                       │
                                       ▼
                              Phase 4: merge
                              Multi-file → MergedProgram
                              (cached: cache/merged.tac)
                                       │
                                       ▼
                              Phase 5: DFA (sequential)
                              ┌────────┬────────┬────────┐
                              │ taint  │ dfa    │ unified│
                              │ (IFDS) │ (ir)   │ (path- │
                              │        │        │  cond) │
                              └────────┴────────┴────────┘
                                       │
                                       ▼
                              Phase 6: emit
                              NDJSON / console / SARIF
                                       │
                                       ▼
                              Phase 7 (optional): AI dedup
                              plumb-ai Re-Act agent
                              → ai_verdict / ai_reason in NDJSON
```

每个阶段的并发模型和资源使用：

| Phase | 并行度 | 内存峰值 | 磁盘产物 |
| --- | --- | --- | --- |
| 1. parse | `nCPU-1` | 1 个 CompilationUnit | 无 |
| 2. style | `nCPU-1` | 1 个 CompilationUnit + StyleContext | 无 |
| 3. translate | `nCPU-1` | 1 个 TacProgram | `cache/programs/<sha1>.tac` |
| 4. merge | 否（顺序） | 1 个 MergedProgram | `cache/merged.tac` |
| 5a. taint | 否 | 1 个 MergedProgram + summary cache | `cache/callgraph.tac` |
| 5b. dfa | 否 | 1 个 MergedProgram | 无 |
| 5c. unified | 部分 | call graph + Z3 ctx | 无 |
| 6. emit | 否 | 0（流式） | `report/findings.{ndjson,console,sarif}` |
| 7. ai-dedup | `ai.maxConcurrent` | LLM response 缓冲 | （写回 NDJSON 的字段） |

## 3. 各模块一句话

| 模块 | 一句话 | 依赖 |
| --- | --- | --- |
| `plumb-parser` | 把 .java 文本转成 JavaParser 的 CompilationUnit | javaparser-symbol-solver-core |
| `plumb-tac` | CompilationUnit → TacProgram，含 CFG builder、IR 序列化 | plumb-parser, sootup |
| `plumb-rules` | 规则元数据（ID/Name/Severity/Type/CWE） | 无 |
| `plumb-reporter` | Finding 模型 + NDJSON/console/SARIF 输出 | plumb-rules |
| `plumb-style` | 590+ AST visitor 规则（JavaParser 单一来源） | plumb-parser, plumb-rules, plumb-reporter |
| `plumb-dfa` | IFDS taint + 经典 DFA（div-by-zero、overflow、resource leak） | plumb-tac, plumb-rules, plumb-reporter |
| `plumb-dfa` | 新一代 DFA 引擎：call graph strategies、Z3 路径条件、function summary | plumb-tac, plumb-rules, plumb-reporter |
| `plumb-main` | CLI + YAML 配置 + 流水线编排 + build-system adapter | 上述全部 + plumb-build |
| `plumb-build` | BuildAdapter：Maven / Gradle / Bazel / Ant 自动探 → sourceFiles/classpathJars | （外部工具） |
| `plumb-ai` | Re-Act 多轮 LLM agent（OpenAI / Anthropic / 兼容） | plumb-rules, plumb-reporter |

### 3.0 DFA 引擎的 SPI 与分类

plumb-dfa 模块里 109 个 `.java` 不全是"真 DFA"。审计（`plumb-dfa/docs/AUDIT-checkers-2026-10-12.md`）按"实际实现机制"归成 5 类：

| 标签 | 含义 | 典型形态 |
| --- | --- | --- |
| **DFA-真** | TAC IR + 跨语句 def-use + 跨函数 summary | MOVE/BINOP/FIELDLOAD 跟踪 + 关键事件触发 |
| **DFA-注解通道** | 数据流之外，靠 `TacFunction.classAnnotations()/methodAnnotations()/paramAnnotations()` 做结构判定 | Spring `@GetMapping` / `@RequestParam` / `@NonNull` |
| **DFA-taint** | 继承 `TaintedCheckerBase` / `PathSensitiveCheckerBase`，自动 taint 传播 | `taint/*` 包下绝大多数 |
| **SRC-正则** | 走 `SourceReader` 读源文件、纯正则匹配，TAC IR 完全不用 | 几条 R759 留下来的 legacy（绝大多数已 STUB） |
| **AI-启发** | 静态部分故意宽松，AI dedup 做主过滤 | CleartextSocketUsage / ErrorMessageInfoLeak / SuspiciousComment |
| **STUB** | R787 阶段已迁移到 plumb-style，构造时抛 UOE | 15 个 `*Checker` 占位类 |
| **HELPER** | 不是 DfaChecker，是公用工具类 | EntropyDetector / LiteralSourcedTracker / SourceReader |

每条规则的具体归类、为什么用 DFA、误报画像见 `plumb-dfa/docs/AUDIT-checkers-2026-10-12.md`（93 KB，每个 checker 一段）。

#### 3.0.1 三条核心 SPI

```
DfaChecker (plumb-dfa/engine/DfaChecker.java):
  initEngine(ctx)                                  // 引擎启动一次
  for each fn in reverse post-order:
      initFunction(ctx, fn)
      for each ins in fn:
          checkInstruction(ins, fn, ctx)           // 主热路径
      finishFunction(ctx, fn)
      summary = summarizeFunction(ctx, fn)         // 给调用方的副作用清单
      ctx.summaryCache().put(fn.signature, summary)
  finish(ctx)                                      // 引擎关停一次

TaintedCheckerBase (plumb-dfa/taint/TaintedCheckerBase.java):
  - 派生自 TaintRule(ruleId, sourceKind, sinkFamilyKey, cwe, severity, TaintDomain)
  - 子类只覆写 4 个 hook: isSourceFact / sinkArgumentIndex / findingMessage / 扩展 checkInstruction
  - 整体传播规则 (MOVE / BINOP / CALL 内部跨函数) 由基类负责
  - 加一条 sink 规则在 taint/ 下往往是几行代码

PathSensitiveCheckerBase (plumb-dfa/taint/PathSensitiveCheckerBase.java):
  - alloc-without-close 类的反向检测底座
  - 跟踪 allocSites 与 closeSites、finishFunction 时让子类决定
  - ResourceLeakChecker 的父类
```

直接 findings（bug 完全在函数体里）通过 `ctx.reporter().report(...)` 立刻发，**不进 summary**；只有"调用方需要知道的事"才进 `FunctionSummary`（R-7 约束）。

### 3.1 模块依赖图

```
                    ┌──────────────┐
                    │ plumb-parser │
                    └──────┬───────┘
                           │
                ┌──────────┴──────────┐
                ▼                     ▼
          ┌──────────┐          ┌──────────┐
          │ plumb-tac │          │ plumb-   │
          │           │          │ rules    │
          └─────┬─────┘          └────┬─────┘
                │                     │
                │              ┌──────┴──────┐
                ▼              ▼             │
        ┌──────────┐   ┌──────────┐         │
        │ plumb-dfa│   │ plumb-   │         │
        │          │   │ style    │         │
        └────┬─────┘   └────┬─────┘         │
             │              │              │
             │       ┌──────┴──────┐       │
             ▼       ▼             ▼       ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ plumb-   │ │ plumb-   │ │ plumb-   │
        │ reporter │ │ style    │ │ dfa      │
        └────┬─────┘ └──────────┘ └────┬─────┘
             │                         │
             └────────────┬────────────┘
                          ▼
                  ┌──────────────┐
                  │ plumb-main   │
                  └──────┬───────┘
                         │
              ┌──────────┼──────────┐
              ▼          ▼          ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ plumb-   │ │ plumb-   │ │ plumb-   │
        │ ai       │ │ build    │ │ reporter │
        └──────────┘ └──────────┘ └──────────┘
```

`plumb-ai` 和 `plumb-build` 都是 `plumb-main` 的**可选依赖**——剥掉它们不影响静态分析本身。

### 3.2 Plumb 关键设计决策

R115-R125 11 轮累计的 8 条关键技术决策（来自 `docs/process/ROADMAP-R115-R125.md` 关键技术决策段）：

#### 1. TAC 用 `String` name 而非 `TacVar` object 作 key (R99)
TAC 的 IR 用 var name 字符串作为 identity key。简化 hash/equals；`TacVar` 的 type/isTemp 字段不影响 identity。SSA 风格 IR 的轻量化变体。

#### 2. `CallResolver` name-based 解析 (R116)
Call graph 边用 call-site-style key（`wrapper/1`），function 用 qualified signature。避免 full qualified name 解析；同 name 不同 arity 容易区分。局限：overloads 没法 disambiguate；R123 起的 type-aware matching 是补丁。

#### 3. Independent catalog 避免 double-report (R121)
`TaintCatalog.sensitiveBuiltin()` 独立于 `builtin()`，`SensitiveDataLeakCheck` 用独立 TaintAnalysis 实例。User input → log 走 LOG_INJECTION，sensitive → log 走 SENSITIVE_DATA_LEAK；两套规则独立 fire 无冲突。Multi-catalog 架构是敏感数据泄漏检测的 natural fit。

#### 4. TypeTable 的 receiverClassName + KNOWN_JDK_SIMPLE (R123)
用 string-prefix 跟 simple-name set 区分用户类 / JDK 类。Bounded cost（O(1) HashMap 查 + 60 个 string 集合），无 classloader 依赖。保守 fall-back：未知类型 → 仍 fire → 零覆盖率损失。精度提升无覆盖损失的精细控制范式。

#### 5. Dedup key 含 sink identity (R121 顺手修)
`ruleId + line + sinkSig + fn.signature()` 而非 `ruleId + line + fn.signature()`。同 line 不同 sink（println + throw）每条都报。

#### 6. 1-hop def chase 替代跨函数 const-folding (R122)
在 check 内部用 defs map 一跳，不在 catalog 层级加跨 call const-folding。Bounded cost（O(1) lookup per arg），覆盖 80% 常见 case。局限：helper 链仍漏；可接受因为 helper 模式相对少。Bounded analysis 是静态分析工具的实用选择。

#### 7. Synthetic `<init>` 修复 instance field init (R124)
Translator 自动生成 synthetic `<init>` 方法恢复 instance field 初始化。修复一个 silent IR drop 隐藏的整个维度（FIELDSTORE）。

#### 8. Annotation IR + dual-tier DI suppression (R125)
`TacFunction` 加 class-level + method-level + field-level 3 维 annotation 列表。`FieldHardcodedPasswordCheck` 消费：Spring / Jakarta DI placeholder 抑制 false positive。Class-level + method-level annotation 一起判定，避免 2 遍 visitor。

**整体原则**：单遍 dispatch + name-keyed IR + 两阶段 summary + multi-catalog + bounded analysis。8 条决策共同构成 Plumb 跟其他工具的差异化。

## 4. 数据契约

### 4.1 Finding

```java
public final class Finding {
    Path file;
    int line, column;
    RuleDef rule;     // 来自 plumb-rules
    String message;
    Severity severity;  // INFO/LOW/MEDIUM/HIGH/CRITICAL
    String dataflowHead; // 可选，taint 类的数据流链
    String aiVerdict;    // 可选，"TP"|"FP"|"UNKNOWN"
    String aiReason;     // 可选，LLM 给的简短理由
    String sinkFunction; // 可选，DFA 类规则专用的 sink 名
}
```

`Finding` 在 `plumb-reporter` 模块。`plumb-dfa` / `plumb-style` / `plumb-ai` 都 import 这个。

### 4.2 RuleDef

```java
public final class RuleDef {
    RuleId id;             // UUID + name
    String description;
    Severity severity;     // INFO/LOW/MEDIUM/HIGH/CRITICAL
    Type type;             // TAINT/STYLE/NAMING/...
    List<String> cwe;
    String checkerClass;   // "org.plumb.style.EmptyCatchCheck"
    boolean enabled;
    List<String> aliases;  // 外部工具的对应 ID（PMD/SpotBugs/...）
    List<RuleParameter> parameters;
}
```

### 4.3 TacProgram

详见 `TAC.md`。所有 IR 类实现 `Serializable` 以支持磁盘缓存。

## 5. 缓存与持久化

详见 `CACHE.md`。当前实现：

- `plumb-tac/.../io/IrSerializer.java` 提供 `writeProgram` / `readProgram` / `writeMerged` / `readMerged` / `writeObject` / `readObject`
- `plumb-main/.../OutputLayout.java` 管理 `<outputDir>/cache/` 和 `<outputDir>/report/` 目录结构
- 缓存 key = `SHA-1(canonicalize(source-path))`，保证不同进程对同一文件得到同一 key
- `cache-mode`：`off` / `read` / `write` / `readwrite`

## 6. R-797：项目路径族（TypeSolver 解耦）

R797 把"喂 TypeSolver 的路径"和"扫 findings 的路径"在 CLI / Config / Scanner 三层严格解耦：

| 路径族 | flag | 进入 TAC | 产生 finding | 喂 TypeSolver |
| --- | --- | --- | --- | --- |
| `--input` | `-i` | ✅ | ✅ | ✅ |
| `--source-root` | `--source-root` | ❌ | ❌ | ✅ |
| `--module-root` | `--module-root` | ❌ | ❌ | ✅ |
| `--classpath` | `--classpath` | ❌ | ❌ | ✅ |

**Cardinal rule**：`--classpath` 路径**永不**翻译成 TAC IR。它存在的唯一目的是让 `--input` 的用户源码能解析外部类型引用。

实现：

- `ScanConfig.sourceRoots` / `moduleRoots` / `classpathJars` 三个新 `List<Path>` 字段
- `SastMain.main` 解析这 3 个新 flag
- `JavaSourceParser` 拿这三个 list 喂 `JavaParserTypeSolver`，但**绝不**把它们当输入文件遍历
- `TAC translate` 阶段只看 `cfg.inputs`

报告：juliet-coverage/reports/r797/PLAN.md。

## 7. R-833：Build-system 自动发现

R833 用 `plumb-build` 模块把"手动喂 `--source-root` / `--classpath`"自动化：

```
┌──────────────────┐
│ plumb-build      │
│                  │
│  BuildSystemReg. │
│    ├── Maven     │──── discover(projectRoot) ──── BuildProject
│    ├── Gradle    │       ├ sourceFiles: List<Path>
│    ├── Bazel     │       ├ sourceRoots: List<Path>
│    └── Ant       │       ├ moduleRoots: List<Path>
│                  │       └ classpathJars: List<Path>
└──────────────────┘
              │
              ▼
       ScanConfig.inputs / sourceRoots / moduleRoots / classpathJars
              │
              ▼
       ScanRunner 接受 BuildAdapter 喂的路径，继续走标准流水线
```

每个 adapter 实现 `BuildAdapter.discover(Path)` 返回 `BuildProject`：

- `MavenAdapter` 解析 `pom.xml`，把 `<sourceDirectory>` / `<testSourceDirectory>` 当 sourceFiles，遍历 `~/.m2` 下所有依赖当 classpathJars
- `GradleAdapter` 同 Maven，但解析 `build.gradle(.kts)`
- `BazelAdapter` 解析 `BUILD` 文件里的 `java_library` / `java_binary` 规则
- `AntAdapter` 解析 `build.xml` 的 `<javac>` task

`BuildSystemRegistry.detect(projectRoot)` 自动按 `pom.xml` → `build.gradle` → `BUILD` → `build.xml` 顺序探测。

### 7.1 R-833 关键设计决策

`plumb-build` 模块的 6 条核心设计原则（来自 `docs/process/R833-BUILD-SYSTEM-ADAPTERS-2026-10-15.md` §7）：

1. **零侵入集成** —— adapter 只通过 `BuildProject` 数据类跟 main pipeline 交互，`ScanRunner` / `ScanConfig` / `JavaSourceParser` 0 改动。
2. **ServiceLoader SPI** —— 不用 Spring / OSGi / DI 容器，走 JDK 标准 SPI。新增 adapter = 加一个 `META-INF/services` 文件 + 一个 class，不需要改 `plumb-main` 或 registry 代码。
3. **Parser-light** —— 不依赖 Maven Embedder / Gradle Tooling API / Bazel Build API（避免 ~200 MB 依赖 + running daemon + JDK 17/21 兼容麻烦），用 regex + 文件 walk 覆盖 80% 用例。
4. **默认保守** —— adapter 找不到依赖 / 解析错误 → warning，不 fail。User 永远可以用 `--classpath` 兜底。
5. **Classpath 委派 m2 repo** —— 跟 Gradle / Bazel 的 default 行为一致（Gradle 用 `mavenLocal()` 默认；Bazel 通过 `rules_jvm_external` 用 m2 cache）。
6. **Multi-module source dedup** —— Bazel / Gradle 可能多个 target 引用同一 .java；adapter 在 project 级别 dedupe，但 per-module 仍报全部 sources。

**关键 Lessons**（避免重复踩坑）：

- **Lesson 1290**：`maven-model:3.9.9` + `plexus-utils:3.5.1` 是 MavenXpp3Reader 读 pom.xml 的最低依赖组合；不需要 `maven-core` / `maven-resolver`（带 daemon + embedder 副作用）。
- **Lesson 1291**：测试时**强制用 hermetic empty local repo**（`tmp/empty-repo`），不能用 `~/.m2/repository`——开发者环境不确定，会让测试 flaky。
- **Lesson 1292**：Bazel `glob(["**/*.java"])` 里的 `**/*.java` 在 Java 源码里**不写** `*/`（否则 javac 误解析为 block comment 结束符）。用 `&#42;&#42;/&#42;.java` HTML entity 或单独常量拼接。
- **Lesson 1293**：Ant `<pathelement path="lib/jar"/>` 是 **relative to projectRoot**（跟 javac 自身行为一致），不能直接 `Path.of("lib/jar")`——必须以 projectRoot 为 base 解析。

## 8. R-836/R-837：rule-id 一致性

R-836 给 emit-id 引入新命名规范（`ast-style-<bare>-style`）后，3 个旧组件失同步：

| 组件 | 旧值 | 新值 | R-837 修法 |
| --- | --- | --- | --- |
| `juliet-coverage/data/plumb_rule_to_cwe.json` | 278 entries, R-791 命名 | 393 entries, R-836 命名 | `tmp/r837_extract_mapping.py` 从 source 自动 grep `new RuleId(...)` + `new TaintRule(...)` 重建 mapping（Lesson 1318） |
| `plumb-style/.../CheckerCategories.java` | 511 entries | 591 entries | `tmp/r837_fix_categories.py` + `r837_fix_categories_all.py` 扫 9 个子目录补齐；`registerFromChecker` 的 NoSuchMethodException swallow 是 P1 root cause（Lesson 1319） |
| `plumb-main/.../ScanRunner.java::isRuleIdAllowed` | endsWith(`.${bare}`) 假设 yaml entry 末尾是 `.`（R-791 plumb.style.${bare} 风格） | endsWith 循环加双向 normalize（剥 `ast-style-` prefix + `-style` suffix 跟 bare slug 比） | 修复 line 1874-1896 + 2459-2461（Lesson 1320） |

**修复后的数据流**：

```
*Check.java                 new RuleId("ast-style-foo-style", ...)
    │                              │
    ▼                              ▼
emit rule_id="ast-style-foo-style"
    │
    ▼
ScanRunner.isRuleIdAllowed(emit, cfg.enabledRuleNames)
    │   endsWith(".foo-style") ← 不匹配
    │   剥 "ast-style-" + "-style" → "foo" ← 匹配
    │
    ▼
emit 进入最终 finding 集合
    │
    ▼
NDJSON 输出，cwe 字段来自 plumb_rule_to_cwe.json["ast-style-foo-style"]
```

**架构教训**：

1. 命名规范改了，**所有引用方**都得改：emit-id ↔ mapping ↔ yaml whitelist ↔ Scanner 匹配。一个漏了就静默丢 finding。
2. 自动化映射重建 vs 人工维护：每次发版前 `gen_rules_index.py` + `r837_extract_mapping.py` 应该同时跑，让 mapping 与 source 保持一致。
3. classpath-scan 注册机制要"双校验"：checker 同时要 (a) 在 `CHECKER_CATEGORIES` 注册 + (b) 提供 `rule()` 方法。少一个就 silently drop。

## 9. 测试体系

每个模块独立 mvn 测试：

| 模块 | 测试数（~） | 测试类型 |
| --- | ---: | --- |
| plumb-parser | 3 | 单元 |
| plumb-tac | 42 | 单元 + round-trip |
| plumb-rules | 5 | 单元 |
| plumb-reporter | 1 | 单元 |
| plumb-style | 200+ | 单元（每条规则） |
| plumb-dfa | 80+ | 单元 + Juliet 测试样本 |
| plumb-dfa | 50+ | 单元 + Juliet |
| plumb-ai | 5（默认 `@EnabledIfEnvironmentVariable` skip，需 `PLUMB_AI_E2E=1`） | 真实 LLM E2E |
| plumb-build | 8 | 单元 |
| plumb-main | 19 | 端到端 E2E |

整体回归用 Juliet Java corpus 跑全量（112 个 corpus），参考 juliet-coverage/。最新数据点（R-837 step_f）：cwe_matched eval prec 0.4537 vs R791 baseline 0.4378（+1.59 pp）。

## 10. 扩展点

| 想要 | 在哪里加 |
| --- | --- |
| 新加 taint source / sink | `plumb-dfa/.../taint/TaintCatalog.java` |
| 新加 taint sanitizer | 同上 |
| 新加 DFA checker | `plumb-dfa/.../checker/<Name>Check.java` |
| 新加 unified checker | `plumb-dfa/.../checkers/<Name>Checker.java` |
| 新加 style 规则 | `plumb-style/<category>/<Name>Check.java` + 加 `m.put(...)` 到 `CheckerCategories.java`（自动生成脚本：`scripts/gen_categories.py`） |
| 新加 CLI flag | `plumb-main/.../SastMain.java` 的 `buildOptions()` |
| 新加 YAML 段 | `plumb-main/.../ConfigLoader.java` 的 `fromMapImpl()` |
| 新加输出格式 | `plumb-reporter/.../FindingFormatter.java` + `NdjsonEmitter.java` / `SarifEmitter.java` 同级 |
| 新加 call graph 策略 | `plumb-dfa/.../cg/CallGraphStrategy.java` + `CallGraphStrategyFactory.java` |
| 新加 build-system adapter | `plumb-build/.../<Name>Adapter.java` 实现 `BuildAdapter` 接口 + `BuildSystemRegistry.byName(...)` |
| 新加 AI rule prompt | checker 的 `getAiPrompt(Finding)` 方法（内联）或 `--rule-prompts-dir/<id>.md` 覆盖 |

### 10.1 加新 style 规则的具体步骤

1. 在 `plumb-style/src/main/java/org/plumb/style/<category>/FooCheck.java` 写规则，实现 `AstCheck` 接口
2. 在 `plumb-style/.../CheckerCategories.java` 加 `m.put("FooCheck", "<category>");`（按字母序）
4. **重要**：跑 `scripts/gen_categories.py` 自动重生成 `CheckerCategories.java`（不要手写）
3. 跑 `mvn -pl plumb-style install` rebuild
4. `bin/sast-scan --list-rules | grep FooCheck` 验证

如果忘了 (2)/(3)，`StyleCheckRegistry.discoverAvailable()` 会找不到规则，**静默丢失**——emit 不出来但报错也没有。Lesson 1319 的修复就是为这种情况加 warning log。

## 11. 未来路线

- **R-output-dir v2**：写 merged.tac / callgraph.tac 到磁盘（当前只有 program cache）
- **R-参数化规则**：YAML `rules.parameters.<id>.<name>: <value>` 覆盖默认（路线图已写，详见 `doc/usage/CLI-REFERENCE.md` §12 + `doc/usage/CLI.md` §4 改规则参数）
- **R-ignore-file**：项目级 `.plumb-ignore` 文件
- **R-SARIF**（部分完成，Phase 6 已支持 `sarif` format）：输出 SARIF 2.1.0 给 IDE / GitHub Code Scanning
- **R-incremental-DFA**：监视 `cache/programs/<sha1>.tac` mtime，只对变更文件重跑 DFA
- **R-rule-profiles**：按语言 / 框架 / 业务场景打包的 rule preset
- **R-DFA sinkFunction enrichment**（Lesson 1320+）：`Finding.withSinkFunction()` + `FindingReporter` enrich 时填 `fn.name() + "()" + ":" + fn.returnType().name()`，修 CWE690 dfa-taint-null-dereference 退化

## 12. 全 Juliet corpus 评估的调度约束

Juliet Java Test Suite 共 112 corpus。要在本地跑完一遍做 cwe_matched eval，必须满足以下两个前提，否则必须分批：

### 12.1 时间约束

| 阶段 | 单 corpus 实测 | 全 112 corpus 估算 |
| --- | --- | --- |
| 单 CWE strip + scan | 5–10 min（CWE190 587 s、CWE191 446 s、CWE129 299 s） | 10–18 h 串行 |
| cwe_matched eval post-process | < 30 s / corpus | < 1 h |

外层调度器对单个 background shell task 的硬上限是 **30 min**（`maxRunMs=1800000ms`）。任何尝试"一条命令跑完 112 corpus"的 shell task 都会被这个上限强杀，必须改成：

- 每个 task 只跑 1–4 个 corpus（5–40 min 内完成）
- 用外部 scheduler（cron / 任务队列）串起来

### 12.2 磁盘约束

| 产物 | 单 corpus 体积（full / strip 后） | 全 112 corpus 估算 |
| --- | --- | --- |
| 全量 NDJSON | ~370 MB / corpus | ~41 GB |
| strip 后 NDJSON（推荐） | ~190 MB / corpus | ~21 GB |
| 压缩比（findings 去重后） | ~50% | — |

默认 D: 盘只有 5–6 GB free，**全量一次跑**必爆盘。必须：

- `tmp/r836_clean.py` 每批前清空 `r836_pipeline/corpus/`
- 或者只保留 strip 后的 NDJSON（直接覆盖 full，不留原始）
- 监控 free disk；剩余 < 3 GB 时暂停下一批

### 12.3 推荐分批策略

把 112 corpus 按 cwe_matched prec 倒序分成 4–5 批，每批 25 corpus 上下：

| 批 | 范围 | 推荐依据 |
| --- | --- | --- |
| 1 | prec ≥ 0.6（25 corpus） | 首批拿到最大 precision 增益 |
| 2 | 0.3 ≤ prec < 0.6（30 corpus） | 中间档 |
| 3 | prec < 0.3（25 corpus） | 噪声密集，需要更激进 enable/disable 调整 |
| 4 | zero-TP sink-based（9 corpus：491/499/500/561/568/580/581/582/607） | 低 ROI，跳过或专门写新 rule |

每批一个 background task，跑完收集 `eval_step_c/per_cwe.csv` + `per_rule.csv`，汇总到 `r836_pipeline/all_per_cwe.csv`。

### 12.4 评估产物的体积参考

实测单 corpus 体积（findings 去重后）：

| CWE | findings（去重后） | strip 前 KB | strip 后 KB | 压缩比 |
| --- | ---: | ---: | ---: | ---: |
| CWE190 | 777 693 | 377 259 | 186 356 | 49.4% |
| CWE191 | 612 976 | 305 472 | 148 070 | 48.5% |
| CWE129 | 571 176 | 294 530 | 162 717 | 55.2% |

strip 步骤（去重 source-location-only finding）是体积压缩的主力，**保留 strip 后 NDJSON 就够了**，full 版本除非要做 sink-based 二次对比，否则可以删。