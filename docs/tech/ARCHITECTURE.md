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
| Taint 分析为主 | per-checker 正向数据流（`TaintedCheckerBase` 统一传播），source/sink 正则表驱动 |
| 多源输入 | JavaParser（源码）+ SootUp（字节码）都进同一份 TAC IR |
| 风格类检查独立 | AST checker 不依赖 TAC/DFA |
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
   AstDispatcher → 595 checks           │                              │
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
                              Phase 5: DFA
                              ┌────────┬────────┬────────┐
                              │ taint  │ dfa    │ path-  │
                              │        │ (ir)   │ cond   │
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
| `plumb-style` | AST visitor checker（JavaParser 单一来源）；数量见 `docs/rules/RULES.md` | plumb-parser, plumb-rules, plumb-reporter |
| `plumb-dfa` | 污点 / 边界 / 指向 / 控制流四类分析的统一 DFA 引擎 | plumb-tac, plumb-rules, plumb-reporter |
| `plumb-main` | CLI + YAML 配置 + 流水线编排 + build-system adapter | 上述全部 + plumb-build |
| `plumb-build` | BuildAdapter：Maven / Gradle / Bazel / Ant 自动探 → sourceFiles/classpathJars | （外部工具） |
| `plumb-ai` | Re-Act 多轮 LLM agent（OpenAI / Anthropic / 兼容） | plumb-rules, plumb-reporter |

### 3.0 DFA checker 的实现形态

`plumb-dfa/src/main/java` 下共 150 个 `.java`，并非每个都是独立的分析器。按**实际实现
机制**可分成几类（数量为实测）：

| 形态 | 数量 | 含义 |
| --- | ---: | --- |
| **TaintedCheckerBase 子类** | 47 | 继承统一污点传播基类，`checkers/taint/` 下绝大多数（不含基类自身） |
| **直接 `implements DfaChecker`** | 19 | 自带逻辑，不共享传播层；`checkers/controlflow/` 的 15 个全在此列 |
| **PathSensitiveCheckerBase 子类** | 1 | 反向检测底座（alloc-without-close 类） |
| **求解器 / 格 / 引擎 / SPI** | 其余 | `points/`、`solver/`、`summary/`、`taint/`、`engine/` |

其中：

- **注解通道**：数据流之外，靠 `TacFunction.classAnnotations()` / `methodAnnotations()` /
  `paramAnnotations()` 做结构判定（Spring `@GetMapping`、`@RequestParam`、`@NonNull`）
- **SRC-正则**：走 `SourceReader` 读源文件、纯正则匹配，TAC IR 完全不用（legacy）

详细的污点/边界/指向/控制流四类分析见 [`DATA-FLOW.md`](./DATA-FLOW.md)。

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

`plumb-ai` 和 `plumb-build` 都是 `plumb-main` 的**编译期依赖**（`pom.xml` 里是普通
`<dependency>`，没有 `<optional>`）——`plumb-main` 直接 import 它们的类型，所以
剥掉这两个模块就编不过。

可选的是**运行时行为**，不是依赖本身：

- `plumb-ai`：`--ai-dedup` 不开的时候，静态分析照常跑；LLM 端点配不通也只是
  把 verdict 标成 UNKNOWN。
- `plumb-build`：不给 `--build-system` / `--project-root` 时，build-system
  adapter 不会被调用，扫描目标由 `--input` 决定。

### 3.2 关键设计决策

以下 8 条解释 Plumb 在架构层面的取舍。

#### 1. Fact 的 identity 来自 `TacOperand`
IR 变量是 `TacOperand.TacVar`（含 `name` / `type` / `isTemp` 三个字段），
`equals` / `hashCode` 覆盖**全部三个字段**（`Objects.hash(name, type, isTemp)`）。
`Fact` 持有 `TacOperand` 本身，所以 fact bag 的 key 就是这个对象 —— 同名但类型
不同、或一个是 temp 一个不是的变量，**不会**被当成同一个 fact 的主体。
代价是 translator 必须保证同一函数内的 SSA 版本名唯一，否则两个语义不同的
变量会共享 identity。

#### 2. Call graph 边用 call-site-style key
边用 `wrapper/1` 这样的 call-site-style key，函数用 qualified signature。避免完整限定名解析；
同名不同 arity 容易区分。代价是 overload 无法仅凭名字消歧，需要 type-aware matching 补充。

#### 3. 独立的 rule registry，避免重复上报
敏感数据类规则与普通污点规则各用自己的 registry 与 sink family，互不干扰：user input → log
走 log-injection，sensitive data → log 走 sensitive-data-leak，两套规则独立触发。

#### 4. 字符串前缀区分用户类与 JDK 类
用 string-prefix 与 simple-name 集合区分用户类 / JDK 类。Bounded cost（O(1) 查表 + 固定
集合），无 classloader 依赖。保守回退：未知类型仍触发 → 零覆盖率损失。这是「精度提升但不牺牲
覆盖」的控制范式。

#### 5. Dedup key 含 sink identity
`ruleId + line + sinkSig + fn.signature()`，而非仅 `ruleId + line + fn.signature()`。同一行
有多个 sink（println + throw）时每条都应上报。

#### 6. 1-hop def chase 替代跨函数 const-folding
在 check 内部用 defs map 追一跳，不在 registry 层级加跨 call 的常量折叠。Bounded cost
（每参数 O(1) 查表），覆盖常见情形。代价：helper 链仍会漏，这被接受为 bounded analysis 的
合理代价。

#### 7. Synthetic `<init>` 恢复 instance field 初始化
Translator 自动生成 synthetic `<init>`，否则 instance field 的 FIELDSTORE 会在 IR 里静默
丢失——这是一个整维度的丢失。

#### 8. Annotation IR + 分层 DI 抑制
`TacFunction` 携带 class-level + method-level + field-level 三维 annotation 列表，消费方据此
抑制 Spring / Jakarta DI placeholder 造成的误报。class-level 与 method-level 一起判定，避免
两遍 visitor。

**整体原则**：单遍 dispatch + name-keyed IR + summary 复用 + 独立 registry + bounded
analysis。

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

## 6. 项目路径族（TypeSolver 解耦）

Plumb 严格区分「喂 TypeSolver 的路径」与「扫 findings 的路径」，在 CLI / Config / Scanner
三层解耦：

| 路径族 | flag | 进入 TAC | 产生 finding | 喂 TypeSolver |
| --- | --- | --- | --- | --- |
| `--input` | `-i` | ✅ | ✅ | ✅ |
| `--source-root` | `--source-root` | ❌ | ❌ | ✅ |
| `--module-root` | `--module-root` | ❌ | ❌ | ✅ |
| `--classpath` | `--classpath` | ❌ | ❌ | ✅ |

**核心规则**：`--classpath` 路径**永不**翻译成 TAC IR。它存在的唯一目的是让 `--input` 的用户
源码能解析外部类型引用。

实现：

- `ScanConfig.sourceRoots` / `moduleRoots` / `classpathJars` 三个 `List<Path>` 字段
- `SastMain.main` 解析这 3 个 flag
- `JavaSourceParser` 拿这三个 list 喂 `JavaParserTypeSolver`，但**绝不**把它们当输入文件遍历
- TAC translate 阶段只看 `cfg.inputs`

## 7. Build-system 自动发现（plumb-build）

`plumb-build` 模块把「手动喂 `--source-root` / `--classpath`」自动化：

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

实际类（`plumb-build/src/main/java/org/plumb/build/`）：`BuildAdapter`、`BuildProject`、
`BuildSystemRegistry`、`MavenAdapter`、`GradleAdapter`、`BazelAdapter`、`AntAdapter`、
`SourceFiles`、`BuildAdapterException`。

每个 adapter 实现 `BuildAdapter.discover(Path)` 返回 `BuildProject`：

- `MavenAdapter` 解析 `pom.xml`，把 `<sourceDirectory>` / `<testSourceDirectory>` 当 sourceFiles，
  遍历 m2 仓库下依赖当 classpathJars
- `GradleAdapter` 同 Maven，但解析 `build.gradle(.kts)`
- `BazelAdapter` 解析 `BUILD` 文件里的 `java_library` / `java_binary` 规则
- `AntAdapter` 解析 `build.xml` 的 `<javac>` task

`BuildSystemRegistry.detect(projectRoot)` 按 `pom.xml` → `build.gradle` → `BUILD` →
`build.xml` 顺序探测。

### 7.1 关键设计决策

1. **零侵入集成** —— adapter 只通过 `BuildProject` 数据类跟 main pipeline 交互，
   `ScanRunner` / `ScanConfig` / `JavaSourceParser` 无需改动。
2. **ServiceLoader SPI** —— 不用 Spring / OSGi / DI 容器，走 JDK 标准 SPI
   （`META-INF/services/org.plumb.build.BuildAdapter`）。新增 adapter = 加一个 services 文件 +
   一个 class，不需要改 `plumb-main` 或 registry 代码。
3. **Parser-light** —— 不依赖 Maven Embedder / Gradle Tooling API / Bazel Build API（避免
   巨大依赖 + running daemon + JDK 版本兼容麻烦），用文件解析覆盖常见用例。
4. **默认保守** —— adapter 找不到依赖 / 解析错误 → warning，不 fail。用户永远可以用
   `--classpath` 兜底。
5. **Classpath 委派 m2 repo** —— 跟 Gradle / Bazel 的默认行为一致。
6. **Multi-module source dedup** —— Bazel / Gradle 可能有多个 target 引用同一 `.java`；adapter
   在 project 级别 dedupe。

### 7.2 实现约束（踩坑点）

以下是实现 `plumb-build` 时必须遵守的约束，记录在此以免重复踩坑：

- **Maven pom 解析**：`maven-model` + `plexus-utils`（MavenXpp3Reader）是读 `pom.xml` 的最低
  依赖组合；不需要 `maven-core` / `maven-resolver`（带 daemon + embedder 副作用）。
- **测试隔离**：测试必须用 hermetic empty local repo，不能用开发者真实的 `~/.m2/repository`
  ——环境不确定会让测试 flaky。
- **Bazel glob 字面量**：Bazel `glob(["**/*.java"])` 里的 `*/` 不能直接写进 Java 源码字符串
  （javac 会误解析为 block comment 结束符），必须拆分拼接。
- **Ant 相对路径**：`<pathelement path="lib/jar"/>` 是 relative to projectRoot（跟 javac 自身
  行为一致），必须以 projectRoot 为 base 解析，不能直接 `Path.of("lib/jar")`。

## 8. rule-id 一致性

style 规则的 emit-id 采用 `ast-<category>-<bare>` 命名（category 取 checker 所在子包名：
`style` / `naming` / `security` / `error` / `bug` / `design` / `performance` /
`quality` / `concurrency`）。实测 `plumb-style/src/main/java` 下 579 个
`new RuleId("...")` 字面量里，**没有一个以 `-style` 结尾**：

| 前缀 | 数量 | | 前缀 | 数量 |
| --- | ---: | --- | --- | ---: |
| `ast-style` | 347 | | `ast-security` | 37 |
| `ast-performance` | 62 | | `ast-error` | 34 |
| `ast-naming` | 50 | | `ast-bug` | 19 |
| `ast-design` | 18 | | `ast-quality` | 10 |
| `ast-concurrency` | 2 | | `dfa-style` / `dfa-security` | 3 / 1 |

这个命名被**三处**引用，任一处失同步都会静默丢 finding：

| 引用方 | 作用 |
| --- | --- |
| `*Check.java` 的 `new RuleId("ast-style-foo", ...)` | 产生 emit id |
| `plumb-style/.../CheckerCategories.java` | classpath-scan 注册表（594 项 `m.put(...)`） |
| `plumb-main/.../ScanRunner.java::isRuleIdAllowed`（`:1823`、`:1834`） | 规则过滤；需要同时接受 canonical id、bare slug 与短横线形式 |

**数据流**：

```
*Check.java                 new RuleId("ast-style-foo", ...)
    │                              │
    ▼                              ▼
emit rule_id="ast-style-foo"
    │
    ▼
ScanRunner.isRuleIdAllowed(emit, registry)   ← canonical / bare / 短横线多形态匹配
    │
    ▼
emit 进入最终 finding 集合
    │
    ▼
NDJSON 输出，cwe 字段来自该规则的 RuleDef.cwe()
```

DFA 侧的 finding key → CWE 映射来自 `plumb-dfa/src/main/resources/cwe-mappings.yaml`，
与 AST 侧的 `RuleDef.cwe()` 是两份独立来源。

**别名**：带 `-style` 后缀的字符串（`ast-style-random-iv-cbc-style`）只作为
`RuleDef` 的 **alias** 保留（用于兼容老的 YAML 白名单），不是任何规则的
emit-id。`ScanRunner.isRuleIdAllowed` 里的
`ast-style-` 前缀 / `-style` 后缀剥离逻辑就是为匹配这些 alias 服务的。

**架构要点**：

1. 命名规范一改，**所有引用方**都得改：emit-id ↔ mapping ↔ yaml whitelist ↔ Scanner 匹配。
   漏一个就静默丢 finding，不报错。
2. 映射应**自动重建**而非人工维护：`scripts/gen_rules_index.py` 从 source 生成索引，让 mapping
   与 source 保持一致。
3. classpath-scan 注册机制要**双校验**：checker 必须同时 (a) 在 `CHECKER_CATEGORIES` 注册 +
   (b) 提供 `rule()` 方法。少一个就 silently drop。

## 9. 测试体系

每个模块独立 mvn 测试。各模块的测试数量由 `scripts/gen_rules_overview.py` 从 surefire 报告
实测生成，见 [`RULES.md`](../rules/RULES.md) 的测试统计一节。

端到端测量由 `testcase/` 下的 category 完成：每个 category 有独立的 fixture、manifest 与
`RESULT.json`，逐 case 记录 TP / TN / FN / FP。支持的 category 与当前实测结果见
[`testcase/SUPPORT-MATRIX.md`](../../testcase/SUPPORT-MATRIX.md)。

`plumb-ai` 的真实 LLM 端到端测试需要 `PLUMB_AI_E2E=1` 才会运行。

## 10. 扩展点

| 想要 | 在哪里加 |
| --- | --- |
| 新加 taint source / sink 模式 | `plumb-dfa/src/main/resources/sink-families.yaml`（新增 `families.<name>.patterns`） |
| 新加 fact kind | `plumb-dfa/src/main/resources/fact-kinds.yaml`（配置清单）或 `plumb-dfa/.../fact/BuiltinFactKinds.java` 的 `registryWithBuiltins()`（内置清单） |
| 新加 taint 规则 | `plumb-dfa/.../checkers/taint/<Name>Checker.java`，继承 `TaintedCheckerBase` |
| 新加边界规则 | `plumb-dfa/.../checkers/boundary/`，继承 `BoundaryConstraintChecker` |
| 新加控制流检查 | `plumb-dfa/.../checkers/controlflow/`，实现 `DfaChecker` |
| 启用 / 关闭某 checker | `plumb-dfa/src/main/resources/checkers.yaml` |
| 新加 style 规则 | `plumb-style/<category>/<Name>Check.java` + 在 `CheckerCategories.java` 加 `m.put(...)` |
| 新加 CLI flag | `plumb-main/.../SastMain.java` 的 `buildOptions()` |
| 新加 YAML 段 | `plumb-main/.../ConfigLoader.java` 的 `fromMapImpl()` |
| 新加输出格式 | `plumb-reporter/.../FindingFormatter.java` + `NdjsonEmitter` / `SarifEmitter` 同级 |
| 新加 call graph 策略 | `plumb-dfa/.../cg/CallGraphStrategy.java` + `CallGraphStrategyFactory.java` |
| 新加 build-system adapter | `plumb-build/.../<Name>Adapter.java` 实现 `BuildAdapter` + `META-INF/services` 注册 |
| 新加 AI rule prompt | checker 的 `getAiPrompt(Finding)` 方法（内联）或 `--rule-prompts-dir/<id>.md` 覆盖 |

### 10.1 加新 style 规则的具体步骤

1. 在 `plumb-style/src/main/java/org/plumb/style/<category>/FooCheck.java` 写规则，实现 `AstChecker`
   接口（覆写自己关心的那个 per-node `visit(X, ctx)` 重载）
2. **在 `CheckerCategories.java` 加 `m.put("FooCheck", "<category>");`**（按字母序；
   该文件由 `scripts/gen_categories.py` 生成）
3. rebuild：`mvn -o -pl plumb-style install`
4. 验证：`sast-scan --list-rules | grep FooCheck`

⚠️ 第 2 步漏掉的后果是**静默丢失**：`AstCheckerRegistry.byCategory(...)` 找不到这条规则，
不 emit 也不报错。（`AstCheckerRegistry.builtins()` 走 classpath 扫描，不依赖
`CheckerCategories`；漏登记影响的是按分类加载的路径。）
classpath-scan 注册机制要求同时满足 (a) 在 `CHECKER_CATEGORIES` 注册 +
(b) 提供 `rule()` 方法，少一个就 silently drop。

## 11. 已知边界

以下行为是当前实现的已知边界，不是缺陷承诺：

- **规则 id 匹配**：`<category>-<bare>` 是 canonical emit id；`-style` 后缀是兼容 alias，
  不是独立规则。
- **虚调用解析**：按方法名 + 参数个数匹配（`ChaCallGraphStrategy`），不做传递式的类层次
  闭包；`classToParent()` / `classToChildren()` 表会被填充并跨编译单元聚合，但目前没有分析
  消费它们。
- **数组污点**：不跨函数边界传播。
- **路径敏感性**：由 `SolverZ3Bridge` / `BoundaryConstraintChecker` 实现，但不是每条 sink 都
  走这条路。
- **缺失的事实不等于安全**：空的 points-to 集合、缺失的 null 事实、may-alias 重叠，都不能
  用来证明一段代码是安全的。

## 12. 产物体积

发布产物是 `plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar`。产品资源只有引擎运行
需要的配置：

| 资源 | 作用 |
| --- | --- |
| `sink-families.yaml` | sink family 与 pattern 清单 |
| `fact-kinds.yaml` | fact kind 清单（与内置 kind 合并） |
| `checkers.yaml` | DFA checker 开关与参数 |
| `cwe-mappings.yaml` | DFA finding key → CWE 映射 |
| `receiver-passthrough.yaml` | receiver 透传 pattern |
| `tuning.yaml` | 引擎调参 |

测试语料在 `plumb-dfa/src/test/resources/juliet-corpus/`，属于测试资源，
不随产品 jar 发布。
