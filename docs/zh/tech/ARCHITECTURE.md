# 架构总览

[English](../../tech/ARCHITECTURE.md) | [中文](../README.md)

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
| `plumb-dsl-kotlin` | Kotlin DSL AST 规则引擎（用户自带 AST 规则，不写 Java） | plumb-parser, plumb-rules, plumb-reporter, plumb-style |
| `plumb-main` | CLI + YAML 配置 + 流水线编排 + build-system adapter | 上述全部 + plumb-build-core + plumb-build-maven |
| `plumb-build` | parent pom，聚合 build 自省子模块 | 无（pom） |
| `plumb-build-core` | BuildAdapter SPI + Maven / Gradle / Bazel / Ant adapter → sourceFiles/classpathJars | plumb-rules, maven-model |
| `plumb-build-maven` | Maven Resolver（Aether）算完整传递依赖树；读 settings.xml 的 mirror | plumb-build-core, maven-resolver |
| `plumb-build-capture` | ByteBuddy agent 抓真实 javac 命令行 | byte-buddy, byte-buddy-agent |
| `plumb-ai` | Re-Act 多轮 LLM agent（OpenAI / Anthropic / 兼容） | plumb-rules, plumb-reporter |

### 3.0 DFA checker 的实现形态

`plumb-dfa/src/main/java` 下共 151 个 `.java`，并非每个都是独立的分析器。按**实际实现
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

详细的污点/边界/指向/控制流四类分析见 [`DATA-FLOW.md`](../../tech/DATA-FLOW.md)。

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

#### 3.0.2 缓解措施是一个事实，而且有些事实是关于 sink 的接收者的

`UnsafeDeserializationChecker` 的规格里点名了 `ObjectInputFilter` 和 `resolveClass` 是它必须认的 sanitizer。很长时间里
没有任何东西实现它：这些名字只出现在文档里，代码里没有 —— 于是已经修掉反序列化 CVE 的构建照样被报出来。一条注册成
CRITICAL 严重级别却永远不可能触发的规则，比没有这条规则更糟，因为覆盖率报告会声称这个 CWE 已经处理了。

JEP-290 filter 现在是一个一等事实，由 `DeserializationFilterTracker`（`plumb-dfa/taint/`）持有。三条性质定义了它，每一条
都是 tracker 必须做对的判断：

| 性质 | 为什么是这样 |
| --- | --- |
| **它属于 stream，不属于 value。** | 同一批不可信字节，走有 filter 的 stream 是安全的，走没有 filter 的 stream 就不安全。filter 装在 STREAM 上，所以 sink 检查问的是它即将上报的那个**接收者**。 |
| **只有能 REJECT 才算限制。** | 只返回 `ALLOWED` 和 `UNDECIDED` 的 filter 拒绝不了任何东西，所以它什么也没限制 —— 那就是"部分缓解"，finding 必须还在。PATTERN filter 的 pattern 以 `!*` 结尾才算限制；不带 `!*` 的 pattern 会放行它没提到的每一个类。LAMBDA filter 的函数体里能返回 `REJECTED` 才算限制。 |
| **它只对自己的 family 生效。** | 一个 filter 说的是"这些类可以被重建"，它对日志、命令行、SQL 语句什么都没说。 |

tracker 的状态是**按函数**的（filter 的安装和读取在同一个函数里），但 lambda 的**函数体**是按程序索引一次的，位置在
`initEngine()` —— 在任何函数被访问之前，因为 sink 检查是在指令遍历**还在进行**的时候问"这个 stream 有 filter 吗"，而
lambda filter 只有在函数体已经被读过之后才能回答。

在这个事实能被表达之前，有三条 TAC 层面的不变量必须成立，而每一条都藏着一个真实的 finding：

- **try-with-resources 的资源是一个声明。** 资源声明在函数体之前翻译，资源变量是一个 local。否则函数体里对它的引用会
  落到名字解析器的 `this.<name>` 兜底分支，变成一个挂在 `this` 上、类型为 `UNKNOWN` 的 FIELDLOAD —— 而初始化表达式根本
  没被翻译，于是 taint 根本到不了它。这样一来，`try (ObjectInputStream ois = new
  ObjectInputStream(untrusted)) { ois.readObject(); }` 就**结构上不可能**产生 finding，而同样代码写在 try 外面却能。
- **lambda 的名字在整个程序里唯一。** 计数器放在 translator 上，不是每个方法自己的 builder 上。用 per-builder 计数器时，
  每个方法的第一个 lambda 都叫 `lambda$0`，任何**按名字**解析 lambda 的地方都会拿到任意一个答案 —— tracker 会因此
  认定一个宽松的 filter 也能 REJECT。
- **资源的 `close()` 是建模的，就当成一次普通 CALL。** Java 的 try-with-resources 在块结束时关闭资源，所以 TAC 把这次
  close 发射成对资源变量的一次调用。发射它正是让资源泄漏 checker 保持诚实的原因：checker 会在看不到 close 时报一个
  分配，所以一个**没有**关闭的 try-with-resources 会被报成泄漏 —— 那是对正确关闭了资源的代码的误报。

在资源泄漏 checker 能正确判断"关没关"之前，还有两条事实必须成立，而这两条都是被 try-with-resources 的工作暴露出来的
既有缺口：

- **对 local 的 close 会关闭它被赋值的那个分配。** TAC 把 `X x = new X(...); x.close();` 降成先 `NEW` 到一个**临时变量**、
  再 `MOVE` 到 local，所以分配在临时变量上，close 在 local 上。两者之间没有别名的话，checker 会对正确关闭了资源的代码
  报泄漏。
- **只在某条路径上的 close，不等于每条路径都 close 了。** 这条规则是在资源"不是每条路径都关闭"时触发的，所以分支臂里的
  close 不能解除这个义务。分支臂的结构是从 TAC 的 `BRANCH` 和 label 结构里读出来的。

`scripts/mutate_deserialization_filter.py` 会变异 tracker 里五个承重的判断，并断言测试能抓住每一个：宽松的 pattern、
从不 REJECT 的 lambda、装到错误 stream 上的 filter、不限 family 的抑制、以及根本不抑制。

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

`plumb-ai` 和 `plumb-build-core` 都是 `plumb-main` 的**编译期依赖**（`pom.xml` 里是普通
`<dependency>`，没有 `<optional>`）——`plumb-main` 直接 import 它们的类型，所以
剥掉这两个模块就编不过。`plumb-build-maven` 同样如此：`plumb-main` 依赖它，
`MavenResolverAdapter` 才会被 ServiceLoader 发现。

可选的是**运行时行为**，不是依赖本身：

- `plumb-ai`：`--ai-dedup` 不开的时候，静态分析照常跑；LLM 端点配不通也只是
  把 verdict 标成 UNKNOWN。
- `plumb-build-*`：不给 `--build-system` / `--project-root` 时，build-system
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

→ **整体原则**：单遍 dispatch + name-keyed IR + summary 复用 + 独立 registry + bounded
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

`plumb-build` 把「手动喂 `--source-root` / `--classpath`」自动化。它是一个 **parent pom**，
底下挂三个子模块：

```
┌───────────────────────────────────────────────────────────┐
│ plumb-build (parent pom)                                  │
│                                                           │
│  ┌──────────────────┐   ┌──────────────────┐             │
│  │ plumb-build-core │   │ plumb-build-maven│             │
│  │                  │   │                  │             │
│  │ BuildSystemReg.  │   │ TransitiveResolv.│             │
│  │    ├── Maven     │   │ MavenSettings    │             │
│  │    ├── Gradle    │   │ MavenResolverAd. │             │
│  │    ├── Bazel     │   │                  │             │
│  │    └── Ant       │   └──────────────────┘             │
│  └──────────────────┘                                    │
│                                                           │
│  ┌──────────────────┐                                    │
│  │plumb-build-      │   真实编译抓取（ByteBuddy agent）    │
│  │capture           │                                    │
│  │ CaptureAgent     │                                    │
│  │ CompilerHooks    │                                    │
│  │ CompilerEntryPt. │                                    │
│  │ JavacOptions     │                                    │
│  └──────────────────┘                                    │
└───────────────────────────────────────────────────────────┘
              │
              ▼
       ScanConfig.inputs / sourceRoots / moduleRoots / classpathJars
              │
              ▼
       ScanRunner 接受 BuildAdapter 喂的路径，继续走标准流水线
```

实际类：

- `plumb-build/plumb-build-core/src/main/java/org/plumb/build/` —— `BuildAdapter`、`BuildProject`、
  `BuildSystemRegistry`、`MavenAdapter`、`GradleAdapter`、`BazelAdapter`、`AntAdapter`、
  `SourceFiles`、`BuildAdapterException`
- `plumb-build/plumb-build-maven/src/main/java/org/plumb/build/maven/` —— `MavenSettings`、
  `TransitiveResolver`、`MavenResolverAdapter`、`MavenPom`、`ModuleDescriptor`
- `plumb-build/plumb-build-capture/src/main/java/org/plumb/build/capture/` —— `CaptureAgent`、
  `CompilerHooks`、`CompilerEntryPoints`、`GradleHooks`、`CaptureRecorder`、`CaptureEntry`、
  `CaptureSink`、`CaptureReader`、`JavacOptions`、`CompiledSourcesAdvice`、`CompiledSourcesBridge`

每个 adapter 实现 `BuildAdapter.discover(Path)` 返回 `BuildProject`：

- `MavenAdapter` 解析 `pom.xml`，把 `<sourceDirectory>` / `<testSourceDirectory>` 当 sourceFiles，
  遍历 m2 仓库下依赖当 classpathJars
- `MavenResolverAdapter`（`maven-resolver`）走 Maven Resolver（Aether）算**完整传递依赖树**，
  顺带读 `settings.xml` 的 mirror / localRepository
- `GradleAdapter` 同 Maven，但解析 `build.gradle(.kts)`
- `BazelAdapter` 解析 `BUILD` 文件里的 `java_library` / `java_binary` 规则
- `AntAdapter` 解析 `build.xml` 的 `<javac>` task

`BuildSystemRegistry.detect(projectRoot)` 按 `maven-resolver` → `maven` → `gradle` → `bazel` →
`ant` 顺序探测。`maven-resolver` 排在 `maven` 前面，因为它算出的传递依赖树更准；
`maven` 作为兜底，因为它无外部依赖、不会因为本地仓库坏了就失败。

### 7.0 两条取 classpath 的路子

| | `maven`（core） | `maven-resolver`（maven 模块） | `plumb-build-capture` |
| --- | --- | --- | --- |
| 手段 | 静态解析 `pom.xml` | Aether 解析传递依赖 | 抓真实 javac 命令行 |
| 传递依赖 | ❌ 只取直接声明的 | ✅ 完整闭包 + 版本冲突消解 | ✅ 编译器实际看到的 |
| 依赖 | 只 `maven-model` | Aether 全家桶 | ByteBuddy |
| 适合 | 快速、离线、够用 | 依赖复杂的 Maven 工程 | 构建脚本拼出来的 classpath |

`plumb-build-capture` 是前两者的补充，不是替代：它解决「classpath 是脚本 / 插件 / IDE 拼出来的」
这类静态分析搞不定的工程。用法：

```bash
# 编译时挂 agent，把真实 javac 命令行落盘
PLUMB_CAPTURE_DIR=/tmp/captures \
  javac -javaagent:plumb-build-capture.jar -d out Foo.java

# 之后再读回来
java -cp plumb-build-capture.jar ... # CaptureReader.readAll(Path.of("/tmp/captures"))
```

### 7.0a 编译参数的两面，以及把它们合起来的门禁

编译参数这件事的两面回答的是同一批问题，而且是配合着用的：一个构建既靠**读它的 pom**
来描述，也靠**抓它真正编译了什么**来描述。

| 问题 | `plumb-build-maven`——构建**声明**了什么 | `plumb-build-capture`——编译器**执行**了什么 |
| --- | --- | --- |
| 语言级别 | 编译插件的 `<release>`，然后 `<source>` | `--release`，然后 `-source` |
| class 文件版本 | 插件的 `<target>` | `--target` |
| 编码 | 插件的 `<encoding>`，然后 `project.build.sourceEncoding` | `-encoding` |
| classpath | Maven Resolver 算出来的传递闭包 | 编译器收到的 `-classpath` |
| 输出目录 | pom 的 `<outputDirectory>` | 编译器收到的 `-d` |
| 源码根 | pom 的 `<sourceDirectory>` | 编译器拿到的那些文件 |

**两面的意思必须一样。** 两边对某个参数的理解不一致时，必有一边是错的，而分析结果是错在
没人看得见的地方。所以 `scripts/check_compile_parameters.py` 把两边**对着彼此**检查：

- Maven 侧读的每个编译参数，在 capture 的选项表里都有对应——pom 的 `<release>` 和 capture
  的 `--release` 是同一个参数；
- 两边在**谁说了算的顺序**上一致：`<release>` 压过 `<source>`，`--release` 压过 `-source`。
  声明了 `<source>8</source>` 又钉了 `<release>17</release>` 的构建是按 17 编译的，
  报成 8 的那一面就是自信地答错；
- 两边都认为**没声明的参数是未知**，不是默认值。上游项目把 `"1.8"` 和 `"UTF-8"` 写死了，
  于是每个项目都被报成"以 1.8、UTF-8 编译"——自信地答错比没有答案更糟，因为分析器
  分不出这个值是猜的。

`scripts/mutate_compile_parameters.py` 是这个门禁自己的变异测试：六种故意的漂移，每一种都
必须让门禁失败。

**一处有意的差异，写出来而不是藏起来。** classpath 在两边都是参数，但取法不同：Maven 侧走
Maven Resolver 算依赖闭包，因为插件自己声明的 classpath 不是编译器拿到的东西；capture 侧
看的是编译器真正收到的 classpath。门禁检查的是**两种取法都还在**，这样任何一边都不能被
悄悄删掉。

### 7.0aa Maven 侧读的是构建声明了什么，而且要顺着父链读

多模块构建的编译参数是从**父 pom** 继承来的，而且这些参数不在一个地方：它们在
`maven-compiler-plugin` 的配置里，会被属性覆盖，而属性的值本身又可能是一个属性。

所以 `MavenPom` 是**顺着父链**读编译插件的配置的，模块自己的配置排在最前面。它遵守的规则
每一条都是量出来的：

- 插件的**配置压过**属性，因为插件的配置才是编译插件真正用的；
- `<release>` **压过** `<source>`，因为 `--release` 同时钉住语言级别**和**平台 API，
  两个都声明的构建是按 release 编译的；
- `${...}` 占位符**会被解析**，解析不出来就留作未知，而不是把占位符本身当版本传下去；
- **没声明的参数保持未知**。上游项目把 `"1.8"` 和 `"UTF-8"` 写死了，于是每个项目都被报成
  "以 1.8、UTF-8 编译"。

插件的配置里还有两样不是参数、但会改变编译的东西，也一并读：

- `<compilerArgs>`——插件传给编译器的额外参数；
- `<annotationProcessorPaths>`——注解处理器在哪找，这和 classpath 是两条不同的路径。

两者都会进到 `ModuleDescriptor`，解析适配器也会读它们，所以把处理器放在单独路径上的构建，
描述出来是对的。

模块自己的**目录**也是从 pom 读的——`<sourceDirectory>`、`<testSourceDirectory>`、
`<build><outputDirectory>`、`<build><testOutputDirectory>`——而不是假定就是
`src/main/java`、`src/test/java`、`target/classes`、`target/test-classes`。
把源码放在别处的构建，现在描述出来是对的。

### 7.0ab 两面互相对照，差异被报出来

§7.0a 的两面回答同一批问题，所以一个构建可以查它自己的**内部一致性**：pom 声明的，
对照编译器真正拿到的。

`plumb-build-core` 放 `CompileDiscrepancy`——一处差异，带着声明值、观测值，以及它们不一致
的那个参数。`plumb-build-maven` 放 `CompileCrossCheck`，它读 capture 目录和 pom，产出这些
差异。

三条规则让这个对照可信：

- **任何一边缺的值是未知，不是差异。** 构建没传 `-encoding` 时 capture 不知道编码，
  classpath 是继承来的时候 pom 也不知道 classpath。把这些报成差异就是噪声，而噪声是门禁
  慢慢没人看的开始。
- **差异保留两边的值。** 只说"它们不一样"的差异没法处理；说"pom 声明 1.8、编译器拿到的是
  17"才能处理。
- **差异的排序是确定的**，所以同一个构建每次产出同样的报告，两次报告之间的 diff 才说明
  真有东西变了。

这个对照是**一份报告，不是把差异抹平**。悄悄抹平的差异就是自信地答错：它的意义就是让
"构建说一套、编译器做另一套"这件事显出来。

`plumb-main` 把它暴露成 `--capture-dir <dir>`，
`scripts/check_compile_crosscheck.py` 是让这个对照保持诚实的门禁，
`scripts/mutate_compile_crosscheck.py` 是它的变异测试。

### 7.0.1 capture 挂哪些入口

「javac 命令行」不是一个方法，而是**十五个**，取决于是怎么走到编译器的：

| 入口 | 谁会走这条路 |
| --- | --- |
| `com.sun.tools.javac.Main#compile(String[])` | `javac` 命令行（静态那一层） |
| `com.sun.tools.javac.Main#compile(String[], PrintWriter)` | 同上，诊断走调用方给的 writer |
| `com.sun.tools.javac.main.Main#compile(String[])` | `javac` 命令行（实例那一层） |
| `com.sun.tools.javac.main.Main#compile(String[], Context)` | 带 context 的调用路径 |
| `com.sun.tools.javac.api.JavacTool#getTask(...)` | **Gradle / Maven / IDE**（`javax.tools` API） |
| `com.sun.tools.javac.api.JavacTool#run(...)` | 老式 run API |
| `com.sun.tools.javac.api.JavacTaskImpl#call()` | 任务驱动（build tool / IDE） |
| `com.sun.tools.javac.api.JavacTaskImpl#generate()` | 任务驱动（IDE 路径） |
| `com.sun.tools.javac.main.JavaCompiler#parse(...)` | **编译器解析的每一个源文件** |
| `com.sun.tools.javac.main.JavaCompiler#compile(...)` | **每一次编译，在它结束的那一刻** |
| `com.sun.tools.javac.main.JavaCompiler#generate(...)` | IDE 的 generate 路径（它不走 `compile`） |
| `org.eclipse.jdt.internal.compiler.batch.Main#compile(String[])` | Eclipse 编译器（ECJ） |
| `com.sun.tools.sjavac.client.ClientMain#run(String[])` | sjavac client |
| `com.sun.tools.sjavac.server.ServerMain#run(String[])` | sjavac server |

只挂一个的后果是**静默失配**：扫描照跑，但那个路径上的编译根本没有 capture。
所以入口清单是**声明式**的（`CompilerEntryPoints`），加一个入口是改数据不是改代码。

**入口的类名是实测出来的，不是照文档抄的。** 这一条踩过坑：
`javax.tools.JavaCompiler` 在现代 JDK 上是个**接口**，只有 `getTask` 和
`getStandardFileManager`，**根本没有 `run` 方法** —— 挂在它上面的 hook 永远不会触发，
所有用 run 式 API 的调用方拿到的 capture 是空的。`run` 实际住在
`com.sun.tools.javac.api.JavacTool` 上。`CompilerEntryPointsTest` 现在会对每个入口
**用真实的类反射核对方法名和参数个数**，所以再写错类名会直接让构建失败。

挂法用 ByteBuddy 的 **`Advice`**（inline 织入），不用手写 ASM visitor：
`Advice` 把 hook 的字节码**复制进**被测方法，所以不需要 super 方法、不需要 delegation target，
`Main#compile(String[])` 这种静态方法照样能挂。手写 visitor 要自己算参数的 local slot
（静态方法 0，实例方法 1），算错就破坏编译器的栈。

### 7.0.1.1 编译器真正编译了哪些源文件

命令行给出的是编译器**被交给**的源文件。编译器还会编译它**自己找到**的源文件：
一个被引用但没被点名的类，会在 source path 上被找到、解析、编译。
只列命令行源文件的 capture 因此漏掉了编译器真编译的代码 —— 而那正是静态分析要看的代码。

**知道这件事的地方是 `JavaCompiler#parse(JavaFileObject, CharSequence)`。**
编译器解析的每一个源文件都从这里过，不管它是怎么来的：命令行给的那些走 `parseFiles`，
source path 上找到的那些走 `readSourceFile`。所以在这里挂一个 hook，
看到的就是编译器解析过的源文件集合 —— 而 source path 上没人引用的文件从不被解析，所以不会出现。

**file manager 的 content cache 先试过，不好使。** 它看起来是最对的地方：它就是编译器自己
读过的文件记录。但正常编译之后它是**空的** —— 编译器读源文件内容走的是 file object，
不是 manager 的 cache。这是实测出来的，不是猜的：显式源文件加 source path 源文件的一次
真实编译之后，cache 里零个条目。一个从来不填的 cache 回答不了这个问题。

**capture 在编译结束的时候写，不是在开始的时候写。** 编译器真正编译了哪些源文件，
只有到结束才知道，所以开始时写的 capture 报不出来。选项在开始时就知道，
所以先**暂存**着，等到编译结束再写。

**暂存的编译是"匹配"出来的，不是按位置取的。** 一次编译会经过好几个入口
—— 命令行能走到其中三个 —— 所以同一次编译会被暂存好几次。
而且一个构建里可能同时有不止一次编译在进行：Gradle 就会在上一模块的编译**还没结束**时
把下一个模块的任务建出来。所以暂存的编译是**匹配**出来的：源文件正好是编译器实际解析过的
那一批。取"最近暂存的那个"会把这一个模块的编译报成下一个模块的选项。

### 7.0.2 IDE 路径的选项从哪来

`JavacTaskImpl#call()` 和 `#generate()` **不带编译选项** —— 选项在 `getTask` 创建任务时就定好了。

而且**取不回来**：`com.sun.tools.javac.api` 这个包没有被 `jdk.compiler` 模块导出，
反射读任务上的 `options` 字段会抛 `InaccessibleObjectException`，
除非给 JVM 加 `--add-opens jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED` ——
让 agent 依赖这个 flag 就等于在普通构建里用不了。

所以选项在**还能看见它的地方**记住：`getTask` 同时拿到选项和它返回的任务，
把两者按**对象身份**关联起来；任务被驱动时再按身份取回来。
`IdentityHashMap` 是这里的关键 —— 用 `equals` 会把两个内容相同的任务混成一对。

### 7.0.3 capture 记的是什么

不是 argv，是**这次编译实际用的东西**：classpath 的**实际内容**（不是 `-classpath` 参数本身）、
source path、encoding、`--release`、源文件清单、输出目录、编译用的 JDK 版本。
理由对任何编译器都成立：`javac` 没给 `-classpath` 和 `javac -classpath ""` 意思不一样，
只有把环境一起看才能还原。

### 7.0.4 选项表是数据，不是开关

编译器的选项在 **`CompilerOption`** 里描述，是一张表。每一条说明：

| 字段 | 决定什么 |
| --- | --- |
| 名字 | 编译器怎么拼这个选项（`-cp`、`--classpath`……） |
| 规范名 | 这个选项记在哪个名字下——同义写法共用一个 |
| 取值形态 | `none` / `single` / `path-list` / `comma-list` |
| 重复规则 | `last-wins`（classpath）还是 `all-apply`（`--add-modules`） |
| 作用 | `compilation` 还是 `not-compilation`（`-Xprint`、`-proc:only`） |
| 归属编译器 | 属于哪个编译器，或者全都适用 |

**为什么用表而不是 `switch`。** 第一版实现把十来个选项名硬编码在一个 `switch` 里，
凡是不认识的就落到默认分支，把**任何以 `.java` 结尾的参数**当成源文件。
于是 `--processor-path some/dir.jar` 被整个丢掉，
而取值恰好以 `.java` 结尾的选项被误认成源文件。现在加一个选项是**一行数据**。

**重复规则才是决定这次编译到底做了什么的那部分。** `-classpath` 给两次意味着
**最后那个**生效，不是两个都生效——javac 不会把它们拼起来。
`--add-modules` 给两次意味着**两个都**生效。这条读错任何一个，
得到的 classpath 或模块集合就是从来没存在过的东西。

**取值形态决定值怎么切。** 路径列表按**平台**的分隔符切，
所以 Windows 的盘符不会被切坏。逗号列表——`--add-exports a/b=c/d`——按逗号切，
在 Unix 上按路径分隔符切其实是错的，尽管在 Windows 上碰巧对。

**编译器之间有差异，表里就写清楚了。** ECJ 有一些 javac 没有的选项——`-src`、`-profile`、
`-log`——用 javac 的表去解析 ECJ 的参数，会把一个值当成 javac 根本没有的选项吞掉。
所以每个选项知道自己属于哪个编译器，查表是分编译器做的。

**两个编译器可能把同一个选项拼成同一个样子，意思却不同。** ECJ 和 sjavac 都有 `-src`、
`-copy`、`-x`、`-i`、`-tr`、`-j`，其中几个的意思并不相同——sjavac 的 `-copy` 可以重复，
ECJ 的不行。所以索引的键是名字**加上**编译器，编译器自己那条优先于共享那条。见
[§7.0.5aa](#705aa-另外两个编译器也是量出来的)。

**哪些拼法存在，以编译器自己为准。** 这张表和它的测试都断言 javac 不认 `--class-path`。
这个断言是错的：javac 自己的帮助里就写着
`--class-path <path>, -classpath <path>, -cp <path>`，真的跑一次 javac 也接受这个写法。
这个错误信念的代价是**整条 classpath 没了**——选项不认识，紧跟其后的值又不是源文件，
两个一起消失。表里每个选项都对着真编译器验过，
`scripts/check_compile_options.py` 是让它们保持同步的门禁。

`--release` 与 `-source`、`-target` 分开记：`--release` 同时钉住语言级别
**和**平台 API，而 `-source` 只钉语言级别。折成一起就丢掉这个区别了。

### 7.0.5 这张表是对着一份参考模型量的

`plumb-build/plumb-build-capture/src/main/resources/java-compiler-options.yaml` 存的是选项的参考模型：
对每个 capture 认识的选项，记的是**这个选项的行为**——它带不带值、重复出现时后面的
是不是覆盖前面的、它会不会产出 class file、它属于哪个编译器。它就是标尺。

它是**参考，不是配置文件**：运行期没有任何东西读它，只有门禁读它，然后拿它和模块自己的
表对比。分类描述的是选项**做了什么**，不是谁标的，所以就算当初标它的那个模型不在了，
这份文件依然有用。

`scripts/check_capture_options.py` 把两边**双向**对比：参考模型各分类里的每个选项
在我们的表里都得存在，取值形态、重复规则、归属编译器都要一样；每个别名都得是我们认识的写法；
而我们有、参考模型没有的每个选项都必须**列为新增**，
所以「我们支持得更多」是一件看得见的事，而不是一个意外。

`scripts/mutate_capture_options.py` 是这个门禁自己的变异测试：十处刻意的偏移，
每一处都必须让门禁失败。它在第一版里找出了三个真实的漏洞——门禁只看选项的*第一种*写法、
从不核对规范名本身对不对，也从不核对参照物自己跟自己一致。

对比是**分编译器**做的。参照物用规范名指代一个选项、把各种写法列为别名，而两个编译器可能
把同一个选项拼成同一个样子，所以参照物的那一条会写明它属于哪个编译器，对比就对着**那个**
编译器的条目做。要是退而求其次拿"碰巧最后一条"来比，比的就是错的东西，恰好把最要命的那个
错误藏起来：属于一个编译器、却挂到另一个编译器名下的选项，正是 capture 那套分编译器的查找
会变成"选项丢失"的那类错误。

### 7.0.5a 编译器自己的选项清单才是尺子

`javac --help` 和 `javac --help-extra` 才是"有哪些选项、怎么拼"的权威，而**真跑一次
javac** 才是"这个选项收不收"的权威。这张表是凭记忆写的，而记忆会漏掉一个选项的双横线
写法。

漏掉的选项，以及漏掉之后各自丢了什么：

| 漏掉的选项 | 漏掉之后的代价 |
| --- | --- |
| `--source`、`--target` | 用长写法写语言级别的构建，语言级别未知 |
| `--class-path` | **整条 classpath**——选项不认识，紧跟的值也跟着消失 |
| `-proc:none`、`-proc:full` | 注解处理开关只认了一半 |
| `-implicit:none`、`-implicit:class` | 隐式编译开关只认了一半 |
| `-g:none`、`-g:lines`、`-g:vars`、`-g:source` | 调试信息开关只认了一半 |
| `-Akey[=value]` | 注解处理器自己的选项不认识 |
| `--default-module-for-created-files` | 生成的 class 文件属于哪个模块，不知道 |

漏一个选项不会报错：它只是静默地不被认识，紧跟其后的值被当成位置参数——而位置参数只有
以 `.java` 结尾才是源文件。所以漏一个选项，**丢的是两样东西**：选项本身和它的值。

### 7.0.5aa 另外两个编译器也是量出来的

`javac` 不是一个构建唯一会跑的编译器，另外两个编译器各自有自己的一套选项，同样是**对着真
编译器**量出来的。

**ECJ** 是跑它自己的 `-help` 量出来的（ECJ 3.33.0）。表里原本缺四个选项，第一个最要命：

| 漏掉的选项 | 漏掉之后的代价 |
| --- | --- |
| `-annotationpath` | **整条注解路径**——选项不认识，紧跟的路径也跟着消失 |
| `-warn:none` | ECJ 自己拼的警告开关不认识 |
| `-?:warn`、`-help:warn` | 高级警告选项不认识 |

`scripts/check_compile_options_ecj.py` 是门禁，`scripts/mutate_compile_options_ecj.py` 是它
自己的变异测试。门禁查两件事：ECJ 列出的每个选项都在表里；而且每个选项都能被 **ECJ 的
查找**找到——挂到别的编译器名下的选项，查找会拒绝它，于是选项被丢掉、它的值被当成源文件。

**sjavac** 是更有意思的一个，因为 **Java 11 之后 sjavac 从 JDK 里被删掉了**。现代 JDK 根本
没有 `com.sun.tools.sjavac`，所以尺子得是 JDK 11 自己的
`com.sun.tools.sjavac.options.Option` 枚举——那个枚举**就是** sjavac 的选项表，每个常量都带
着编译器接受的那种写法。枚举是反射读的，因为 `jdk.compiler` 不导出这个包。

这次量出三条决定表形状的事实：

1. **sjavac 有自己的选项表，而且它自己消费这些选项。** sjavac 不认识的，统统**转交给
   javac**。对着真编译器量出来，sjavac 转交的是 `-encoding`、`-source`、`-target`、
   `--release`、`-g`、`-nowarn`、`-proc:none`、`-processor`、`-processorpath`、
   `-bootclasspath`、`--add-modules`、`-Xlint`、`-parameters` 和 `-A`。这些是 **javac 的**
   选项，所以表里不能把它们算成 sjavac 的——否则 javac 自己的查找会拒绝它们。
2. **sjavac 自己的选项有 26 个**：`-src`、`--source-path`、`-sourcepath`、`--module-path`、
   `-p`、`--class-path`、`-classpath`、`-cp`、`-x`、`-i`、`-tr`、`-copy`、`-j`、`--server:`、
   `--startserver:`、`-implicit:`、`--log=`、`-verbose`、`--permit-artifact=`、
   `--permit-unidentified-artifacts`、`--permit-sources-without-package`、
   `--compare-found-sources`、`-d`、`-s`、`-h`、`--state-dir=`。
3. **sjavac 的选项值取自下一个 token。** `-j 2` 行，`-j=2` **被拒绝**；`--server:`、
   `--startserver:`、`--state-dir=` 同理。这个写法说的是值**可以**放在哪，不是说必须拼在
   一起。

`scripts/check_compile_options_sjavac.py` 是门禁。它读编译器自己的枚举，查 sjavac 的每个
选项都在表里，查 sjavac 的每个选项都能被 **sjavac 的**查找找到，并且查 sjavac **转交**给
javac 的选项没有被算成 sjavac 的。`scripts/mutate_compile_options_sjavac.py` 是它的变异测试。

**索引的键是"名字 + 编译器"。** 这不是洁癖：ECJ 和 sjavac 都有 `-src`、`-copy`、`-x`、
`-i`、`-tr`、`-j`，而且其中几个在两个编译器下意思并不相同——sjavac 的 `-copy` 可以重复，
ECJ 的不行。只按名字做键的索引**每个名字只放一个**选项，于是后一条悄悄把前一条盖掉，
**ECJ 的查找把这六个全拒绝了**：选项被丢掉，紧跟其后的值被当成源文件。所以索引放的是
`(名字, 编译器)` 一对的选项，编译器自己的那条优先于共享的那条。

### 7.0.5b 参数文件也是命令行的一部分

javac 遇到以 `@` 开头的参数时，会从那个文件里读选项。把 classpath 放进这种文件的构建，
所有信息都通过文件告诉了 javac，命令行本身几乎什么都不带——所以不打开那个文件的 capture
记下来的是一条 classpath 为空、语言级别未知的编译。

这个格式是对着 javac 量出来的，不是猜的：

- 文件按**空白**切分；
- token 两头的引号会被**剥掉**，但**不会**把它分组——所以 `-d "C:\a b"` 是选项 `-d`
  加值 `C:\a`，外加一个被 javac 当成源文件的落单的 `b"`；
- 读不了的文件就把原参数留着，所以记录里看得出这次编译是由参数文件驱动的。

### 7.0.6 一次编译只落一份

**一次编译会经过好几个入口**：命令行那条路先过 `com.sun.tools.javac.Main#compile`，
再过 `com.sun.tools.javac.main.Main#compile`；API 那条路先过 `JavacTool#getTask`，
再过 `JavacTaskImpl#call`。不去重的话，一次编译会落好几份几乎一样的记录。

去重的键**不是 argv**：CLI 和 API 走到同一次编译时 argv 是不同的，
而两次真正不同的编译 argv 反而可能一模一样。键是**这次编译的身份**
（源文件 + 输出目录 + classpath + source path + encoding + 语言级别），
见 `CaptureEntry.compileIdentity()`。源文件在比较前**先排序**，
因为同一组文件从 API 和命令行过来的顺序不一样。

### 7.0.7 Gradle：daemon、build cache，以及哪条通道能到达 JVM

Gradle 是最麻烦的一个：编译发生在 **daemon 进程**里（agent 挂的那个 JVM 看不到），
而且开了 build cache 时编译**根本没跑**（也就没什么可 capture）。

`GradleHooks` hook 的是 Gradle 自己的配置 API —— 下面那两个开关只存在于那里，
把两件事改掉：`StartParameter#setBuildCacheEnabled` 强制 false、
`DaemonParameters#isEnabled()` 强制 false。

这两个 hook 是**可选的** —— 关掉 daemon 会让构建变慢，没要 capture 的用户不该为此买单。
它们由 `plumb.capture.gradle.disable-daemon=true` 打开。

#### classpath 来自编译器 hook，不是 Gradle hook

显而易见的做法是 hook Gradle 自己的 compile spec
（`DefaultJvmLanguageCompileSpec#setCompileClasspath(List<File>)`），因为那正是 Gradle
把自己解析出来的 classpath 交给编译器的地方。**实测，这个 hook 从不触发。**

Gradle 在 daemon 里组装编译，但通过 `javax.tools` 在**同一个 JVM** 里*执行*它。
compile spec 住在 `gradle-language-jvm` jar 里，是被懒加载进 Gradle 自己的 class loader 的，
所以它压根没被加载进挂着 agent 的那个 JVM —— `Instrumentation#getAllLoadedClasses()`
里没有它。hook 它因此什么也记录不到；就算记录到了，也只是把编译器 hook 已经
准确记录过的那次编译又记了一份，而且那份更差。

所以 classpath 来自 **`JavacTool#getTask` hook**，而且它是完整的：capture 记下了上游模块的
**输出目录**、依赖 jar、module path、release 级别、encoding、source roots 和目标目录。
上游那些输出目录正是这件事之所以重要的原因：它们是只有那些模块构建完之后才存在的目录，
所以读 `build.gradle` 根本看不出它们 —— 但问编译器可以。

`GradleCaptureE2EIT` 断言的正是这一点，跑在真实的双模块 Gradle 构建上：
下游模块的 capture 记下了上游模块的输出目录和依赖的 jar。

#### 哪条通道能到达 Gradle 的 JVM

这一点不明显，弄错了会让配置**静默失效**。在 Gradle 9.2.0 上实测，
办法是读 Gradle fork 出来的 JVM 的命令行：

| 通道 | 能到达 Gradle 的 JVM 吗？ |
| --- | --- |
| `org.gradle.jvmargs` 里带 `-javaagent` | **能** |
| `org.gradle.jvmargs` 里带 `-D` | 不能 —— 每个 `-D` 都被丢掉 |
| `GRADLE_OPTS` | 不能 —— 它作用于 launcher，不是 daemon |
| `gradle.properties` 里的 `systemProp.name=value` | 不能 —— 它作用于 build JVM，而编译跑在 fork 出来的 daemon 里 |
| agent 自己的参数（`-javaagent:jar=key=value`） | **能** |
| 环境变量 | **能** —— JVM 会继承它 |

所以在 Gradle 里配置 agent 的办法，是把设置作为 **agent 自己的参数**传进去：

```properties
org.gradle.jvmargs=-Xmx1g \
  -javaagent:/path/plumb-build-capture.jar=plumb.capture.gradle.disable-daemon=true
```

agent 会把自己的参数转成 system property（`CaptureAgent.applyAgentArguments`），
于是这个 property 就在真正执行编译的那个 JVM 里被设好了。
显式设置的 system property 仍然优先于 agent 参数，因为它是被刻意设的。

环境变量 `PLUMB_CAPTURE_GRADLE_HOOKS` 是第二条通道，用于 agent 不是通过
`-javaagent` 挂上去的情况。

#### 一次编译一份记录

Gradle 的编译是通过 `javax.tools` 走到 hook 的，而一次 Gradle 构建会编译好几个模块。
每个模块的编译都是不同的编译，所以每个各自产出一份记录 —— 按编译身份
（`CaptureEntry.compileIdentity()`）的去重把它们区分开，因为源文件和输出目录都不一样。

### 7.1 关键设计决策

1. **零侵入集成** —— adapter 只通过 `BuildProject` 数据类跟 main pipeline 交互，
   `ScanRunner` / `ScanConfig` / `JavaSourceParser` 无需改动。
2. **ServiceLoader SPI** —— 不用 Spring / OSGi / DI 容器，走 JDK 标准 SPI
   （`META-INF/services/org.plumb.build.BuildAdapter`）。新增 adapter = 加一个 services 文件 +
   一个 class，不需要改 `plumb-main` 或 registry 代码。
3. **Parser-light（core）** —— 不依赖 Maven Embedder / Gradle Tooling API / Bazel Build API（避免
   巨大依赖 + running daemon + JDK 版本兼容麻烦），用文件解析覆盖常见用例。
   `plumb-build-maven` 是 core 之外的**增强路径**：它要 Aether，代价是依赖体积和一次真实的解析。
   注意它仍是 `plumb-main` 的编译期依赖（pom 里没有 `<optional>`）——「增强路径」说的是
   运行时要不要走它，不是构建期能不能缺它。
4. **默认保守** —— adapter 找不到依赖 / 解析错误 → warning，不 fail。用户永远可以用
   `--classpath` 兜底。
5. **Classpath 委派 m2 repo** —— 跟 Gradle / Bazel 的默认行为一致。
6. **Multi-module source dedup** —— Bazel / Gradle 可能有多个 target 引用同一 `.java`；adapter
   在 project 级别 dedupe。
7. **Aether 的 repo id 必须和 settings.xml 一致** —— 本地仓库每个 artifact 旁边的
   `_remote.repositories` 记着它是从哪个 repo id 下载的。硬编码 `central` 而本地仓库其实是
   走 `alimaven` mirror 填的，离线解析会报 `(present, but unavailable)`。
   所以 `MavenSettings` 读 `settings.xml` 的 `<mirrors>` 取真实 id + url。

### 7.2 实现约束（踩坑点）

以下是实现 `plumb-build` 时必须遵守的约束，记录在此以免重复踩坑：

- **Maven pom 解析**：`maven-model` + `plexus-utils`（MavenXpp3Reader）是读 `pom.xml` 的最低
  依赖组合；不需要 `maven-core` / `maven-resolver`（带 daemon + embedder 副作用）。
  → 这条约束针对 `plumb-build-core`。`plumb-build-maven` **就是要** `maven-resolver`，
  因为它算的是传递依赖树，那是静态读 pom 拿不到的。
- **测试隔离**：测试必须用 hermetic empty local repo，不能用开发者真实的 `~/.m2/repository`
  ——环境不确定会让测试 flaky。
  → `plumb-build-core` 遵守这条。`plumb-build-maven` 的 `TransitiveResolverTest` 是**例外**：
  它故意用真实本地仓库，因为要验证的正是「传递依赖真的解出来了」，用空仓库这个测试
  就没有意义了。它的断言不依赖具体机器上有哪个版本（找不到就跳过）。
- **Bazel glob 字面量**：Bazel `glob(["**/*.java"])` 里的 `*/` 不能直接写进 Java 源码字符串
  （javac 会误解析为 block comment 结束符），必须拆分拼接。
- **Ant 相对路径**：`<pathelement path="lib/jar"/>` 是 relative to projectRoot（跟 javac 自身
  行为一致），必须以 projectRoot 为 base 解析，不能直接 `Path.of("lib/jar")`。
- **ByteBuddy 版本必须支持目标 JDK**：1.15.11 不支持 Java 25（class file 69），
  报 `IllegalArgumentException: Java 25 (69) is not supported`。
  而且这个异常**只在 `AgentBuilder.Listener.onError` 里出现**，默认 listener 是 `NoOp` →
  完全静默。诊断手段：一定要挂 `Listener.Adapter` 打印 root cause。
- **`Advice` 能挂不 override 任何东西的方法**：早期结论是
  `com.sun.tools.javac.main.Main#compile(String[])` 没有父方法、`Advice` 会报
  `Cannot call super (or default) method`，因此要退回手写 ASM。**这个结论是错的** ——
  `Advice` 是**inline 织入**：把 hook 的字节码复制进被测方法，所以它既不需要 super 方法，
  也不需要 delegation target。实测 `Main#compile(String[])`、`JavacTool#getTask`、
  `JavacTaskImpl#call` 都能用 `Advice` 挂上。
  → 所以**不要手写 ASM**：`Advice` 免掉局部变量槽的手工计算（静态方法槽 0 是参数，
  实例方法槽 0 是 `this`、参数在槽 1 —— 算错就破坏编译器的栈），而且 JDK 内部类的
  字节码形态会变，手写 visitor 跟着一起烂。
- **agent 挂的类在 `jdk.compiler` 命名模块里**：被插桩的 `com.sun.tools.javac.Main`
  由 application class loader 加载，而 agent jar 只有 agent 自己的 loader 看得见 →
  `NoClassDefFoundError`。解法：把 hook 类**注入 bootstrap class loader**
  （`appendToBootstrapClassLoaderSearch`），它是所有 loader 的祖先，谁都看得见。
  代价：hook 类**不能引用 bootstrap 之外的东西**（不能用 SLF4J，也不能直接引用
  `javax.tools.FileObject`）。bootstrap jar 必须**依赖闭包完整**——包括嵌套类
  （`JavacOptions$Builder`）。`Advice` 会**吞掉 `Throwable`**，所以缺一个类不会报错，
  只会**静默不记录**。→ 用 `BootstrapInjectionTest` 读编译后 class 的常量池做结构门禁。
- **bootstrap 侧要通过导出的接口反射调用**：`com.sun.tools.javac` 的实现类不是导出 API，
  直接引用在 bootstrap 场景下不可达。源码路径等信息的读取一律走**导出的接口**
  （`javax.tools.JavaFileObject` 等）反射取。
- **classpath 要按平台分隔符切**：同时按 `:` 和 `;` 切会把 Windows 的盘符切碎
  （`C:\foo` → `C` + `\foo`）。→ Windows 只切 `;`，其它平台只切 `:`。
- **两个 javac 入口互不重叠**：命令行走 `com.sun.tools.javac.Main#compile`（静态），
  `javax.tools` API 走 `com.sun.tools.javac.main.Main#compile`（实例）。
  一次命令行编译会**先后经过这两个**，所以 capture 要去重。

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
[`testcase/SUPPORT-MATRIX.md`](../../../testcase/SUPPORT-MATRIX.md)。

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
| 新加 **Kotlin DSL** AST 规则（不写 Java） | 写一个规则文件（XML / YAML），用 `--dsl-rules <file>` 传进去。见 [`KOTLIN-DSL.md`](../../tech/KOTLIN-DSL.md) |
| 新加 CLI flag | `plumb-main/.../SastMain.java` 的 `buildOptions()` |
| 新加 YAML 段 | `plumb-main/.../ConfigLoader.java` 的 `fromMapImpl()` |
| 新加输出格式 | `plumb-reporter/.../FindingFormatter.java` + `NdjsonEmitter` / `SarifEmitter` 同级 |
| 新加 call graph 策略 | `plumb-dfa/.../cg/CallGraphStrategy.java` + `CallGraphStrategyFactory.java` |
| 新加 build-system adapter | `plumb-build/plumb-build-core/.../<Name>Adapter.java` 实现 `BuildAdapter` + `META-INF/services` 注册 |
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

### 10.2 用 Kotlin DSL 加 AST 规则（不写 Java）

不想写 Java checker 时，可以用 `plumb-dsl-kotlin` 的规则引擎：

1. 写一个规则文件（XML 或 YAML 格式）——见 [`KOTLIN-DSL.md`](../../tech/KOTLIN-DSL.md) §2
2. `sast-scan -i src/main/java --dsl-rules rules/ast-rules.yaml`

DSL 规则和内置 style 规则走**同一条**产出路径：命中后产生同样的 `Finding`，
进同样的 registry、同样的 whitelist / blacklist 过滤、同样的 reporter。

唯一的结构性差别是**触发方式**：内置 checker 靠 `AstDispatcher` 的 per-node
派发，而 DSL 规则在 `AstChecker.finish(ctx)` 里运行——因为 dispatcher 按精确
节点类派发，既不会把 `CompilationUnit` 送给 checker，也无法表达
`Statement` / `Comment` 这类抽象节点类型。细节见
[`KOTLIN-DSL.md`](../../tech/KOTLIN-DSL.md) §5.3。

数据流 / 污点规则**不在**这个引擎里，仍由 `plumb-dfa` 的 YAML 配置驱动。

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
