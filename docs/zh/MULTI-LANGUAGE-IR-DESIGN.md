# 多语言统一 IR 设计：Kotlin / Scala / 其他 JVM 语言

[English](../MULTI-LANGUAGE-IR-DESIGN.md) | [中文](README.md)

> 状态：设计稿（未实施）
> 日期：2026-10-31（v2，路线已按决策改为**源码 AST → IR 主线**）
> 范围：**IR 生成 + 数据流分析**。AST 规则不在本期范围，只在附录中给出各语言的成熟框架选型。

---

## 0. 结论先行

**"同一套 IR 完成全部分析"这个目标，Plumb 已经完成了 80%，剩下的 20% 是纯增量工作。**

关键发现（基于对现有代码的实际核查，不是推测）：

| 结论 | 证据 |
|---|---|
| IR 层**完全语言无关** | `plumb-tac/src/main/java/org/plumb/tac/ir/` 全包 grep 无任何 `javax`/`javaparser`/语言特定引用；`TacType` 只表达 JVM 类型系统（`CLASS`/`ARRAY`/primitives），Kotlin/Scala 的泛型/Union 类型同样能降级进去 |
| 已有**两个前端** | `plumb-tac/translate/`（JavaParser AST → TAC）+ `plumb-tac/bytecode/jimple/`（SootUp Jimple → TAC）。多语言前端是**同一模式的第三次实例**，不是新架构 |
| 规则层**零语言耦合** | `checkers.yaml` 用 `sink_family` / `source_kind` / `taint_domain` 声明规则；`TaintRule` 只有字符串 + domain 枚举。Kotlin/Scala 规则**不需要新语法**，复用现有 YAML |
| 分析引擎**无需改动** | `DfaChecker` 生命周期只消费 `TacInstruction` / `TacFunction`；Z3 求解器、Points-to、Summary 机制全部与语言无关 |
| IR 扩展面**很小且已有先例** | `SourceOrigin` 枚举已经证明了"给 `TacFunction` 加一个语言维度"是低风险操作，且 `SourceOrigin.java:24-27` 已写明序列化契约：字段新增向后兼容 |

### 本版（v2）的路线变更

v1 文档提出"字节码为默认通道 + 源码为增强通道"的混合路线。**v2 已按决策改为：源码 AST → IR 是唯一主线**，字节码通道降级为"库依赖分析通道"（只用于分析 `.jar`，不用于分析用户源码）。

变更理由（都是硬性技术约束，不是偏好）：

1. **字节码路径丢失源码级语义，无法支撑 Kotlin 的空安全与合成代码处理。**
2. **字节码路径看不见 lambda 体**（Kotlin 2.0+ 默认 `invokedynamic`），污点流直接断裂——这是漏报，不是精度下降。
3. **字节码路径拿不到 param 级注解**（`CLASS` 保留级别的框架注解不可见），框架规则（`@RequestMapping` / `@Transactional`）直接失效。
4. **字节码路径的 `@Metadata` 解析是长期负债**——Kotlin metadata 格式无稳定性保证。

字节码通道**不删除**，但它的职责收窄为：分析用户传入的第三方 `.jar`（这是 Plumb 现有的 `LIBRARY` origin 能力，与多语言无关）。

### 五个直接回答

**Q1：Coverity 除了 Java/Kotlin/Scala，还支持其他 JVM 语言吗？**

**不支持。** 见 §2.1。Black Duck 官方语言清单里的 JVM 语言只有 **Java / Kotlin / Scala** 三种。Groovy、Clojure、JRuby、Gosu、Xtend **均不在 Coverity 的语言支持列表中**。这反过来印证了我们的判断：JVM 生态里真正需要深度数据流分析的就是这三个。

**Q2：能不能直接把 Kotlin/Scala 源码通过 AST 转成 IR，而不是编译成 class 再转？**

**能，而且这正是 v2 的主线。** 两条已核实的落地路径：
- **Kotlin**：`Analysis API` 的 **Standalone 模式**（`buildStandaloneAnalysisAPISession`，官方提供，headless，纯 JVM 进程内运行完整 FIR 分析流水线），或 **Compiler Plugin API** 的 `IrGenerationExtension`（直接拿 Kotlin Backend IR 树）。
- **Scala 3**：**Compiler Plugin API** 的 `PluginPhase`（`dotty.tools.dotc.plugins.PluginPhase`），在 `typer` 之后、`pickler` 之前拿到**带完整类型信息的 `tpd.Tree`**。

细节与权衡见 §3.3 / §3.4。

**Q3：IR 要不要改？**

**要改，但改动面被严格压到 2 个字段 + 2 个 enum + 1 个 opcode + 1 个类型维度**（§3.2）。原则是：**IR 只表达"语义等价"的东西**——不为 Kotlin `when`、Scala `match` 增加语言特定 opcode，而是在前端降解成 `BRANCH` 链。

**Q4：Scala 2 支持吗？**

**不支持。** Scala 2 与 Scala 3 编译器内部 API 完全不同（`Symbol` vs `Denotation`，`Tree` vs `TypedTree`），Scala 2 已进入维护期（官方不再有 2.14，直接奔 Scala 3），为维护期语言写编译器插件 ROI 极差。见 §3.5。

**Q5：版本怎么定？包括 Java。**

**以最新稳定版为目标，但"跟版本"和"锁版本"是两件事**（§0.5）：

| 语言 | 目标版本 | 跟随策略 |
|---|---|---|
| Java | **25**（LTS） | **自由跟随**（JavaParser 是稳定 API）——**当前解析器只到 `JAVA_21`**，Java 22–25 需要升级 JavaParser 依赖，见 §0.5.6 |
| Kotlin | **2.4.21** | **锁小版本**（Analysis API 是实验性 API） |
| Scala | **3.10.0** | **锁小版本**（dotc 是内部 API） |

> **核查中发现一个真实缺陷**：`JavaSourceParser` 硬编码 `JAVA_17`，而 `JavaModule.languageLevel` 这个字段**传进去了但从来没人用**——整条链路是断的。实测 Java 21 的 switch pattern 在 `JAVA_17` 下**直接解析失败**。
> 更严重的是 TAC 翻译器对 Java 14+ 特性（`SwitchExpr` / `RecordDeclaration` / `PatternExpr`）**零覆盖**——即使解析成功也翻译不出正确 TAC。详见 §0.5.3，已列入 Phase 0 的 J1/J2。
>
> **已修复**：language level 链路与 Java 14+ 翻译都已落地，见 §0.5.6。
>
> **顺带的好消息**：Java `record` 的 accessor 是**编译器合成方法**，和 Kotlin `data class` 的 `componentN`、Scala `case class` 的 `copy` 是同一类东西。先做 Java 侧的"合成方法识别"，Kotlin/Scala 就有了一个已验证的实现范式。

### 0.6 当前状态：前端已接进扫描路径（2026-11-02 续）

**Kotlin 和 Scala 现在真的能被扫了。** 详见 §12，但结论先放这里：

| 环节 | 状态 |
|---|---|
| Kotlin / Scala 前端实现 | ✅ |
| 前端单元测试 | ✅ Kotlin 42 / Scala 28 |
| 前端进入**真实扫描路径**（`ScanRunner` 按扩展名分派） | ✅ §12.1 |
| callee signature 带 owner，sink family 能匹配上 | ✅ §12.2 缺陷二 |
| **扫描端到端出 finding**（`.kt` / `.scala` 的 SQL 注入报 CWE-89） | ✅ §12.1 |
| 风格检查器在 Kotlin/Scala 上 | ❌ 有意不做，见 §12.4 |
| Kotlin lambda 体 / Scala closure → 独立 `TacFunction` | ❌ Phase 3，见 §10.2 |

---

## 0.5 版本策略：以最新稳定版为目标

**原则**：**默认跟最新稳定版**；不追 nightly/EAP；每个语言**只锁一个小版本线**，跨小版本升级走显式的验证流程。

### 0.5.1 各语言的目标版本（截至 2026-11-02，实测核实）

| 语言 | 目标版本 | 理由 | 备注 |
|---|---|---|---|
| **Java** | **Java 25**（LTS） | 本机运行时已是 `openjdk 25.0.1 LTS`；Java 25 是当前 LTS | **前端目前只到 Java 21**——JavaParser 3.26.4 的上限（见 §0.5.6）。要真正支持 22–25 语法需升级 JavaParser |
| **Kotlin** | **2.5.0-Beta2**（Analysis API） | **Maven Central 上 `kotlin-analysis-api` 当前唯一版本就是 `2.5.0-Beta2`**——不是稳定版，但也没有别的可选 | Plumb 的 DSL 脚本引擎当前锁 **2.2.0**（为兼容 JDK 25），**不要动**——那是另一条依赖链（§0.5.4） |
| **Scala** | **3.10.0** | 最新稳定版；3.9 是 LTS 线 | 已实测 `scala3-compiler_3:3.10.0` 可从 Central 解析。注意 Maven metadata 的 `release` 字段仍写 `3.10.1-RC2`，但 `3.10.0` 作为稳定版已实际发布 |
| **JavaParser** | **3.26.4**（已在用） | 具名上限 = `JAVA_21` | **`BLEEDING_EDGE` 在此版本 == `JAVA_21`**（identity 验证），不是更新语法支持（§0.5.6） |

### 0.5.2 三种"版本跟随"策略（不同的风险模型）

**不同语言的前端，跟随策略不一样。** 这是本节最核心的判断：

| 语言 | 前端形态 | 跟随策略 | 理由 |
|---|---|---|---|
| **Java** | JavaParser（**稳定 API**，语义化版本） | **自由跟随**，随 Plumb 升级 | JavaParser 有 `LanguageLevel` 枚举，加新语言特性不需要改我们的代码 |
| **Kotlin** | Analysis API Standalone（**实验性 API**） | **锁死小版本 + 显式升级** | 官方明说 "subject to incompatible changes"；跨小版本要重跑交叉验证 |
| **Scala** | Compiler Plugin `PluginPhase`（**内部 API**） | **锁死小版本 + 显式升级** | 内部 API **跨 patch 版本都可能不兼容** |

**为什么 Java 可以自由跟随、Kotlin/Scala 必须锁死？**

| | JavaParser | Kotlin Analysis API | Scala dotc |
|---|---|---|---|
| API 性质 | 稳定的第三方库 | JetBrains 官方但标注 `@KaExperimentalApi` | 编译器内部实现 |
| 版本兼容承诺 | 语义化版本 | "可能不兼容变更" | 无（`CrossVersion.full` 才兼容） |
| 新增语言特性对我们影响 | 只是多一个 `LanguageLevel` 枚举值 | 可能改 API 形状 | 可能改 `tpd.Tree` 形状 |
| 升级成本 | 低（改 pom + 跑测试） | 中（重跑交叉验证 harness） | 高（重跑全部 Scala fixture） |

> **推论 —— "跟进最新"和"锁死版本"不是矛盾，是两件事。**
> - **语言特性层面**：要跟最新（Java 25 的 sealed/record pattern，Kotlin 2.4 的新语法）。
> - **前端 API 层面**：要锁死（Kotlin/Scala 的编译器内部 API）。
>
> 这两件事不冲突——因为**语言特性和前端 API 是解耦的**。JavaParser 加一个 `JAVA_25` 枚举值不需要我们改一行代码；Kotlin 加一个新语法特性也不需要我们改转换器。

### 0.5.3 Java 前端的现状缺口（**已修复**，见 0.5.6）

> **状态更新（2026-11-02）**：本节描述的是**核查当时**的现状。J1 与 J2 均已实现，
> 实际落地的内容见 [§0.5.6](#056-已落地java-language-level-链路与-java-14-翻译)。

**这是本轮核查发现的一个真实问题，与多语言无关，但影响"以最新版本为目标"这个要求。**

**现状**（`plumb-parser/src/main/java/org/plumb/parser/JavaSourceParser.java`）：

```java
public JavaSourceParser() {
  this(ParserConfiguration.LanguageLevel.JAVA_17, ...);   // ← hardcoded JAVA_17
}
public JavaSourceParser(List<JavaModule> modules) {
  this(ParserConfiguration.LanguageLevel.JAVA_17, ...);   // ← hardcoded JAVA_17
}
// Javadoc says: "if all modules report null, falls back to JAVA_17"
```

**问题 1：硬编码 `JAVA_17`，且 `JavaModule.languageLevel` 从未被使用。**

`JavaModule` 有一个 `String languageLevel` 字段，`ScanRunner.java:1193/1203/1221` 也确实把 build project 的 language level 传了进去——**但没有任何代码把它映射回 `ParserConfiguration.LanguageLevel`**。整个链路是断的：

```
BuildProject.languageLevel (String)
  → JavaModule.languageLevel (String)   ← passed in
  → ???                                 ← broken
ParserConfiguration.LanguageLevel        ← always JAVA_17
```

**问题 2：Java 21+ 语法直接解析失败（已实测）。**

用 JavaParser 3.26.4 实测 `sealed interface` + `record` + switch pattern：

```
JAVA_17       -> ok=false, problems=2
    "Switch patterns not supported. Pay attention that this feature is supported
     starting from 'JAVA_21' language level..."
JAVA_21       -> ok=true, problems=0
BLEEDING_EDGE -> ok=true, problems=0
```

**问题 3：TAC 翻译器对 Java 14+ 特性零覆盖。**

`TacTranslator.java:71-72` 自己写明了：

> "Out of scope: lambdas, anonymous inner classes (skipped), **record patterns, sealed-type patterns**, annotations on local decls (ignored for now)."

核查翻译器实际支持的节点：
- ✅ `SwitchStmt`（`StatementTranslator.java:141`）——**旧式** switch（Java 14 前）
- ❌ `SwitchExpr`（Java 14+ 的 `switch` 表达式）——**未见**
- ❌ `RecordDeclaration`（Java 16 record）——**未见**
- ❌ `PatternExpr`（Java 16+ instanceof pattern / Java 21 switch pattern）——**未见**
- ⚠️ `InstanceOfExpr`（`ExpressionTranslator.java:127`）——**旧式** instanceof（Java 14 前）

**这意味着**：即使把 language level 提到 21/25，Java 17+ 的代码能**解析**但**翻译不出正确的 TAC**。record 的 accessor、switch pattern 的分支绑定、sealed 类型的穷尽性——全是漏报来源。

**修法（分两步，可与 Phase 0 并行）**：

| 步骤 | 内容 | 风险 |
|---|---|---|
| **J1** | **打通 language level 链路**：`JavaModule.languageLevel` (String) → `ParserConfiguration.LanguageLevel` 解析器（支持 `"17"` / `"21"` / `"25"` / `"latest"`），并把 `JAVA_17` 硬编码换成"配置优先、缺省 JAVA_21" | 低。纯接线 |
| **J2** | **补 Java 14+ 语法翻译**：`SwitchExpr` / `RecordDeclaration` / `PatternExpr` / sealed 穷尽性 | 中。`record` 的 accessor 是合成方法（对应 Kotlin data class 的 `componentN`） |

> **J2 与多语言前端的关系**：Kotlin 的 `data class` 和 Scala 的 `case class` 都会生成合成 accessor。**如果 Java 的 `record` 先做出来，"合成方法识别"这套机制就有了 Java 侧的验证对象**，Kotlin/Scala 可以直接复用。这让 J2 从"Java 的独立工作"变成"多语言能力的第一个落地点"——**顺序上应该先做 J2**。

### 0.5.4 版本锁定与升级流程

**每个语言前端模块独立锁版本**，不共用：

```xml
<!-- plumb-frontend/pom.xml -->
<properties>
  <!-- Different from the main repository's pom.xml kotlin.version:
       The main repository pins 2.2.0 because the DSL script engine must be
       compatible with JDK 25 (see the main repository's comment);
       The frontend pins 2.5.0-Beta2 because on Maven Central the Analysis API
       currently has only one published version,
       and that version is 2.5.0-Beta2 (a Beta). -->
  <kotlin.analysis.api.version>2.5.0-Beta2</kotlin.analysis.api.version>
</properties>
```

**实际落地（2026-11-02）**：版本号放在**主仓** `pom.xml` 的 `<properties>` 里，
两个前端模块通过 `<dependencyManagement>` 引用。这样版本号只有一处定义，
但仍是"按 API 风险分层"的——`kotlin.version`（DSL 引擎）、`kotlin.analysis.api.version`（前端）、
`scala3.version`（Scala 前端）三个互相独立。

> **`kotlin-analysis-api` 的 artifact 结构（实测）**：`org.jetbrains.kotlin:kotlin-analysis-api`
> 是一个**聚合 POM**，本身 jar 只有 335 字节。真正的代码在四个 artifact 里：
>
> | artifact | 内容 | 作用域 |
> |---|---|---|
> | `kotlin-analysis-api-surface` | 核心 API 接口（2.3MB） | compile |
> | `kotlin-analysis-api-standalone-surface` | Standalone 会话接口（81KB） | compile |
> | `kotlin-analysis-api-standalone-implementation` | Standalone 实现（295KB） | runtime |
> | `kotlin-analysis-api-intellij-api-surface-components` | IntelliJ PSI 组件（8.7MB） | runtime |
>
> **API 的包是拆开的**：`KaSession` 在 `api`，`KaType` 在 `api.types`，`KaSymbol` 在 `api.symbols`，
> `StandaloneAnalysisAPISession` 在 `api.standalone`。**按 `api.components` 去找全部类型会找不到**——
> `KaFileSymbol` / `KaDeclarationSymbol` / `KaFunctionSymbol` 都在 `api.symbols` 里，不在 `components` 包里。

**升级流程（每次跨小版本）**：

1. 改版本号 → 编译 → 跑全部单元测试。
2. 跑 **§6.4 的双路径交叉验证 harness**（Kotlin）——这是发现 API 语义变化的主要手段。
3. 跑 **§6.1 的三语等价 fixture**（Java/Kotlin/Scala）——确认 finding 集未漂移。
4. 记录 changelog 到该模块的 `CHANGELOG.md`。

> **为什么 Kotlin 前端版本和主仓的 kotlin.version 要分开**：
> 主仓的 `kotlin.version=2.2.0` 有明确的注释约束——"2.2.0 is the first release whose script engine understands JDK 25; 2.1.0 fails with `IllegalArgumentException: 25.0.1`"。这是 **DSL 脚本引擎**的约束，和 **Analysis API 前端**的约束是两回事。混在一起会导致两个功能互相绑死。
>
> **推论 —— 同一个语言在一个仓里出现两次时,版本号必须解耦,否则一个功能的升级会绑死另一个。**

> **构建环境提示（实测）**：本机 Maven 默认走 Aliyun 镜像，**它能代理 Kotlin Analysis API 的
> POM 但不能代理 JAR**（`dependency:get` 报 "8 required artifacts are missing"，而同样的 URL
> 用 `Invoke-WebRequest` 直接下载是 200）。**解析这些依赖必须强制走 Maven Central**
>（`-s` 指向一个把 `central` 镜像到 `https://repo1.maven.org/maven2/` 的 settings 文件）。
> 这不是 Plumb 的问题，是环境配置问题——但**它会以"依赖不存在"的假象出现**，很容易被误判成
> "上游没发布"或"这条路走不通"。**结论：遇到"依赖拉不下来"，先分清是"上游没有"还是"镜像没有"。**

### 0.5.5 "以最新版本为目标"对 IR 设计的影响

**关键判断：语言版本升级不要求 IR 扩展。**

| 语言新特性 | IR 需要变吗 | 前端在哪降解 |
|---|---|---|
| Java 21 switch pattern | ❌ 不需要 | 降解成 BRANCH 链 + 模式绑定的 MOVE |
| Java 16 record | ❌ 不需要 | accessor 视为合成方法 |
| Java 17 sealed | ❌ 不需要 | 当普通类处理（穷尽性不是 IR 契约） |
| Kotlin 2.x 新语法 | ❌ 不需要 | 同上 |
| Scala 3.10 Java record patterns | ❌ 不需要 | 同上 |

**这正是"IR 只表达语义等价的东西"这条原则的兑现**——语言年年出新，IR 不动。

> **但有一个例外要盯**：如果某门语言的新特性引入了**新的污点语义**（不只是新的语法），那才可能需要 IR 表达。
> 举例：Kotlin 的 `suspend`（协程）——它不是语法，是**执行模型**变化（挂起点会切走执行流）。
> - 简单的挂起点（`delay()`、IO suspend）→ 污点流在 `Continuation` 里传递，可以当作普通调用处理（Phase 1）。
> - 但如果挂起点两侧的局部变量要建模（状态机字段）→ 需要 IR 表达"跨挂起点的存活变量"。
>
> **决策**：Phase 1 先不做状态机建模（把 suspend 当普通函数），Phase 3 再评估。**如果评估结论是需要状态机字段，那就要给 TAC 加一个"跨调用存活的变量"概念**——这是本设计里唯一预留给未来 IR 扩展的口子，且它与语言无关（任何语言的协程/async 都适用）。

### 0.5.6 已落地：Java language level 链路与 Java 14+ 翻译

**J1（language level 链路）已打通。**

新增 `plumb-parser/src/main/java/org/plumb/parser/JavaLanguageLevel.java`：

```java
public static ParserConfiguration.LanguageLevel resolve(String level);
```

支持 `"8"`–`"25"`、`"1.8"`、`"latest"`、`"current"`、`"bleeding-edge"`。
`JavaSourceParser(List<JavaModule>)` 现在从**第一个声明了 level 的模块**推导解析级别；
`ScanRunner.buildParser()` 改为 `new JavaSourceParser(modules)`（不再硬编码）。

**J2（Java 14+ 翻译）已实现。**

新增 `plumb-tac/src/main/java/org/plumb/tac/translate/JavaModernFeatures.java`：

| Java 语法 | 降解为 |
|---|---|
| `switch` 表达式（`SwitchExpr`） | 结果 temp + BRANCH/BINOP/MOVE 分支链；箭头 arm 的单表达式直接 MOVE 到结果 temp |
| `yield` | **不再丢弃值**——MOVE 到结果 temp（`ExpressionTranslator` 用 ThreadLocal `yieldTarget` 传递目标） |
| `instanceof` 模式（Java 16+） | INSTANCEOF + MOVE 绑定 |
| switch pattern label（Java 21） | INSTANCEOF + arm 内 MOVE 绑定 |
| record pattern（Java 21） | record 类型 INSTANCEOF + accessor CALL + component MOVE |
| `record` 的合成成员 | 自动合成 canonical constructor 与 component accessor；**显式成员优先**，避免重复 |

> **签名一致性陷阱（已踩过）**：Java record 的合成成员与显式成员必须使用**同一套签名约定**。
> 顶层方法用 `x():int`，构造器用 `Point(int,int)`。两套约定不一致时，调用点查找只能命中其中之一，
> 表现为"合成 accessor 存在但调用点解析不到"。

**关键实测事实：`BLEEDING_EDGE == JAVA_21`（identity 比较为 `true`）。**

JavaParser 3.26.4 的 `LanguageLevel` 枚举**没有** `JAVA_22`–`JAVA_25` 常量，且 `BLEEDING_EDGE`
经 identity 验证就是 `JAVA_21`：

```
BLEEDING_EDGE == JAVA_21 : true
BLEEDING_EDGE name       : JAVA_21
BLEEDING_EDGE toString   : JAVA_21
```

**因此 Java 22–25 的诚实映射是 `JAVA_21`**，而不是假装支持更新语法：

| 输入 | 映射结果 | 说明 |
|---|---|---|
| `"8"`–`"21"` | 对应具名常量 | 正常映射 |
| `"22"`–`"25"` | `JAVA_21` | **JavaParser 3.26.4 无更高具名级别** |
| `"latest"` / `"current"` / `"bleeding-edge"` | `JAVA_21` | 同上；`BLEEDING_EDGE` 不比 `JAVA_21` 新 |

**真正支持 Java 22–25 语法需要升级 JavaParser 依赖，不是配置变更。**
默认值已从 `JAVA_17` 改为 `JAVA_21`（3.26.4 具名的最高级别）。

> **这条教训值得单独记住**：`BLEEDING_EDGE` 这个名字听起来比 `JAVA_21` 新，
> 但在 3.26.4 里它们是同一个常量。**"看起来更新"不等于"确实是更新"**——
> 这类事只能靠 identity 比较实测，不能靠命名推断。

---

## 1. 路线论证：为什么是源码 AST → IR

### 1.1 三条路线的对比

| | 路线 A：字节码<br/>(.class → Jimple → TAC) | 路线 B：编译器前端<br/>(AST/IR → TAC) | **路线 C：源码 AST → IR<br/>（本设计采用）** |
|---|---|---|---|
| 依赖 | `sootup`（已有） | `kotlin-compiler` + `scala3-compiler`（~50MB each） | 同 B，但 Kotlin 可用 Analysis API Standalone |
| 注解（class/method/field） | `RuntimeVisibleAnnotations` 可见 | 完整 | 完整 |
| **注解（param 级）** | **不可见**（`CLASS` 保留） | 完整 | 完整 |
| **lambda 体** | **不可见**（`invokedynamic`） | 可见 | 可见 |
| **Kotlin 可空性** | 需解 `@Metadata` protobuf | 直接可得 | 直接可得（`KaType` 可空性） |
| **data class 合成方法** | 需靠命名启发式识别 | 直接可得（编译器标记） | 直接可得 |
| **行号精度** | 协程/inline 会错位 | 精确 | 精确 |
| 跨语言 call graph | 天然打通 | 需自己做符号解析 | 需自己做符号解析 |
| 增量编译 | 天然 | 需自己实现 | 需自己实现 |

### 1.2 字节码路径的三个不可接受缺陷

**缺陷 1：lambda 体完全不可见。**
Kotlin 1.5 起可用 `-Xlambdas=indy`，2.0 起成为**默认**。lambda 体在 `invokedynamic` 的 bootstrap 方法里，不在常规方法体中。后果是 **lambda 内的污点流断裂，finding 直接丢失**——这是漏报。在现代 Kotlin 代码库里，lambda（`let`/`also`/`map`/`filter`/回调）占比极高。

**缺陷 2：param 级注解不可见。**
Java 注解的 `@Retention(CLASS)` 在 `.class` 里是 `RuntimeInvisibleAnnotations`，SootUp 默认读不到。Spring 的 `@RequestParam` / `@PathVariable` / `@RequestHeader` 都是 `PARAMETER` 目标 + `RUNTIME` 保留（可见），但不少自定义框架注解是 `CLASS`。**更严重的是**：注解在 `.class` 里只能挂到方法上，**参数级注解的位置信息丢失**（因为方法参数没有独立的 attribute slot 承载 param 注解，只有 `MethodParameters` 这个可选属性）。

**缺陷 3：Kotlin 可空性需要解 protobuf。**
Kotlin 把类型信息编码在 `@Metadata` 注解的 protobuf 里。可空性、空安全、data class 标志全在里面。`kotlinx-metadata-jvm` 能解析，但 Kotlin metadata 格式**没有稳定性保证**，且我们只需要可空性这一个维度——为了一个维度引入一个长期脆弱的依赖，收益比很差。

> 源码路径上，Kotlin 的可空性是**类型系统的一等公民**（`KaType.isMarkedNullable`），不需要任何额外解析。

### 1.3 但字节码通道不删除

Plumb 现有的 `BytecodeAnalyzer`（SootUp Jimple → TAC）分析 `.jar` 的能力**与多语言无关**——它分析的是"用户提供的第三方库"，不是"用户的源码"。Kotlin/Scala 支持**不影响**这条通道。

重新定义两个 origin 的职责：

| origin | 职责 | 变更 |
|---|---|---|
| `SOURCE` | 用户源码（Java / Kotlin / Scala），走源码前端 | **扩展**：新增 Kotlin/Scala 源码 |
| `LIBRARY` | 第三方 `.jar`，走 Jimple | **不变** |
| `SYSTEM_LIBRARY` | JDK 代码 | **不变** |

---

## 2. Coverity 对标：JVM 语言支持边界与 checker 清单

### 2.1 Coverity 的 JVM 语言支持边界（Q1 的正式回答）

**Black Duck 官方语言清单**（`blackduck.com/static-analysis-tools-sast/languages-and-frameworks.html`）里的 JVM 语言**只有三个**：

| JVM 语言 | Coverity 支持 | 支持层级 |
|---|---|---|
| **Java** | ✅ | 原生深度数据流分析（最成熟） |
| **Kotlin** | ✅ | 原生数据流 checker（Sigma 引擎）+ Detekt 集成 |
| **Scala** | ✅ | 原生数据流 checker + 框架绑定（Play/http4s/Dropwizard） |
| Groovy | ❌ | 不在支持列表 |
| Clojure | ❌ | 不在支持列表 |
| JRuby | ❌ | 不在支持列表 |
| Gosu | ❌ | 不在支持列表 |
| Xtend | ❌ | 不在支持列表 |

> **注意区分两件事**：
> - **Coverity 支持 Kotlin/Scala 的"编译"**——`cov-build` 能捕获 Gradle/sbt 的 Kotlin/Scala 编译命令。
> - **Coverity 支持 Kotlin/Scala 的"数据流 checker"**——2026.3.0 起 Kotlin 的 dataflow checker 迁移到独立的 **Sigma** 引擎；2026.6.0 有约 16 个 Kotlin checker、多个 Scala checker。
>
> 也就是说：**Coverity 自己在 JVM 语言上也是"按语言分叉 checker 实现"，而不是做统一 IR。** 这不是我们要跟随的方向——我们的目标是**同一套 IR + 同一套规则 + 语言绑定**，这比 Coverity 的做法更统一。

**对我们的直接影响**：Groovy/Clojure 等动态语言**不在我们的支持计划里**（理由见 §2.1），因为 Coverity 这个业界标杆也没做——不是因为难，是因为**动态派发让数据流分析失去实用价值**。

### 2.2 Coverity 的 Kotlin checker 清单（Sigma 2026.3.0–2026.6.0）

| Coverity check 名 | 对应 CWE | 映射到 Plumb |
|---|---|---|
| `sqli_core_kotlin` | CWE-89 | 已有 `SqlInjectionChecker` |
| `os_cmd_injection_core_kotlin` | CWE-78 | 已有 `CommandInjectionChecker` |
| `script_code_injection_core_kotlin` | CWE-94 | 已有 `JavaCodeInjectionChecker` |
| `xpath_injection_core_kotlin` | CWE-643 | 已有 `XPathInjectionChecker` |
| `path_manipulation_core_kotlin` | CWE-22 | 已有 `PathTraversalChecker` |
| `url_manipulation_core_kotlin` | CWE-601 | 已有 `OpenRedirectChecker` |
| `regex_injection_core_kotlin` | CWE-1333 | **需新增** |
| `header_injection_core_kotlin` | CWE-113 | 已有 `HttpResponseSplittingChecker` |
| `sensitive_data_leak_core_kotlin` | CWE-532 | 已有 `SensitiveDataLeakChecker` |
| `unencrypted_sensitive_data_core_kotlin` | CWE-319 | 已有 `CleartextTxChecker` |
| `hardcoded_credentials_core_kotlin` | CWE-798 | 已有 `HardCodedPasswordSinkChecker` |
| `unsafe_deserialization_core_kotlin` | CWE-502 | 已有 `UnsafeDeserializationChecker` |
| `unsafe_jni_core_kotlin` | CWE-829 | 已有 `UnsafeJniChecker` |
| `unrestricted_access_to_file_core_kotlin` | CWE-732 | **需新增** |
| `implicit_intent_core_kotlin` | CWE-926 | Android 专用，暂不做 |
| `missing_permission_check_android` | CWE-862 | Android 专用，暂不做 |

**结论：Coverity 的 Kotlin checker 里，13/16 在 Plumb 已经有对应实现。** 这验证了 Plumb 的规则集选型是对的——**我们要补的不是规则，是语言通道**。

### 2.3 Coverity 的 Scala checker 清单

Scala 侧 Coverity 明显走**框架绑定**路线（Play / http4s / Dropwizard），而不是通用语言规则：

- `missing_tls_slick_play_database_config`、`missing_tls_result_play`
- `broad_domain_attribute_cookie_play_config`、`missing_httponly_attribute_play_config`、`missing_secure_attribute_play_config`、`root_path_attribute_cookie_play_config`
- `cors_with_credentials_null_origin_result_play`
- `broad_filesystem_access_dropwizard_setup`
- `missing_tls_apache_datasource`、`missing_tls_jackson_core`、`missing_tls_mongodb`、`missing_tls_vertx`（Java/Kotlin/Scala 三语言共享）

**重要发现**：大量 Scala check 是 **"Modified existing Java check ... to support Kotlin, Scala"**。也就是 Coverity 的做法是：**同一条规则，用语言无关的机制表达，靠 checker 的多语言绑定启用**。

> **这正是 Plumb 已有架构的做法**（`sink-families.yaml` + `fact-kinds.yaml` + `framework` 绑定）。所以 Scala/Kotlin 规则的落地方式 = **在现有 YAML 里加语言绑定，而不是写新 checker**。

### 2.4 我们的实现方向（对标 Coverity）

| 优先级 | 项 | 说明 |
|---|---|---|
| P0 | Kotlin 源码前端（Analysis API Standalone） | 14 个 checker 立即可用 |
| P0 | Scala 3 源码前端（Compiler Plugin） | 同上 |
| P0 | `missing_tls_*` 系列 | Coverity 已为 Java/Kotlin/Scala 三语言统一实现。我们只需加 sink family |
| P1 | `regex_injection` (CWE-1333) | 唯一需要新写的通用规则 |
| P1 | `unrestricted_access_to_file` (CWE-732) | 文件权限检查 |
| P2 | Play/http4s/Play-Slick 框架绑定 | Scala 生态高价值 |
| P2 | Kotlin/Android: `implicit_intent` (CWE-926) | Android 专项 |

---

## 3. 统一 IR 设计

### 3.1 设计原则

1. **IR 语义不变**。`plumb-tac/ir/` 是多语言契约（multi-language contract），任何语言前端都必须适配它，**不能为新语言改 IR 语义**。
2. **IR 只表达"语义等价"的东西**。语言特定的东西（Kotlin 的 `when`、Scala 的 `match`）在**前端**就降解到 TAC 语义，不在 IR 里开后门。
3. **精度分级，显式声明**。每个 `TacFunction` 携带"精度等级"，让 checker 知道自己该不该信某些信息。
4. **扩展面最小化**。每个新增字段/枚举都必须是"多语言必需"，且不能有更小的表达方式。
5. **序列化向前兼容**。`SourceOrigin.java:24-27` 已定契约：字段新增向后兼容，重命名/删除才需要 `serialVersionUID` bump。

### 3.2 IR 需要扩展的部分（严格压到 5 处）

现有 `TacFunction` 已有：`classAnnotations` / `methodAnnotations` / `fieldAnnotations` / `paramAnnotations` / `enclosingClass` / `origin` / `tryRegions`。

| # | 扩展 | 位置 | 破坏性 |
|---|---|---|---|
| 1 | `SourceLanguage` 枚举 | 新文件 | 无 |
| 2 | `IrPrecision` 枚举 | 新文件 | 无 |
| 3 | `sourceLanguage` + `irPrecision` 字段 | `TacFunction` | 无（委托构造器） |
| 4 | `NULL_CHECK` opcode | `TacInstruction.Kind` | 无（枚举追加） |
| 5 | `nullable` 维度 | `TacType` | **有条件**（`equals` 语义变化，见扩展 5） |

> **扩展 5 是唯一有条件破坏的一处**，必须配专门回归测试（见 Phase 0）。

#### 扩展 1：`SourceLanguage` 枚举（新文件）

```java
package org.plumb.tac.ir;

/**
 * The source language. It decides whether a checker sees the language's
 * native semantics or the degraded ones.
 *
 * <p>IR-serializable: part of the IrSerializer on-disk cache contract.
 */
public enum SourceLanguage {
  /** Java source. The default. */
  JAVA,
  /** Kotlin source. */
  KOTLIN,
  /** Scala 3 source. */
  SCALA,
  /** Cannot be determined (third-party code from a .jar). */
  UNKNOWN;

  /** Whether this language has a compile-time guaranteed non-null type system. */
  public boolean hasNonNullTypeSystem() {
    return this == KOTLIN;
  }
}
```

> `hasNonNullTypeSystem()` 是唯一需要"语言专属语义"的入口，且方向严格受限：**只能用于消除误报，不能用于证明安全**（见 §4.4）。

#### 扩展 2：`IrPrecision` 枚举（新文件）

```java
package org.plumb.tac.ir;

/**
 * The IR precision level. A checker can use it to decide whether to trust
 * certain information.
 */
public enum IrPrecision {
  /**
   * Source level: the complete annotations (including the param level),
   * nullability, exact line numbers and the lambda bodies are all preserved.
   * The default for the Java / Kotlin / Scala source frontends.
   */
  SOURCE_FULL,

  /**
   * Source level but degraded by the language: the Kotlin coroutines have
   * already been expanded into a state machine, and when/match have been
   * degraded into a BRANCH chain. The semantics are still complete, but the
   * structural information is lost.
   */
  SOURCE_LOWERED,

  /**
   * Bytecode level (used only for the analysis of third-party .jar libraries):
   * the annotations/line numbers may be incomplete, and the lambda bodies are
   * invisible (invokedynamic).
   */
  BYTECODE,

  /**
   * Bytecode level and passed through JVM synthesis (lambda body / accessor /
   * bridge methods).
   */
  BYTECODE_SYNTHETIC
}
```

**为什么需要 `IrPrecision`**：这是避免 Kotlin 支持引入大量误报的关键。

具体例子：Kotlin 的 `fun foo(s: String) = s.length` 在**字节码**下是：
```
checkNotNullParameter(s, "s")   // ← the null check inserted by Kotlin
ALOAD s
INVOKEVIRTUAL String.length
```
字节码路径下 `NullDerefChecker` 会误报——因为 Kotlin 编译器已经保证了非空。

**但在源码路径下这个问题根本不存在**：前端知道 `s: String` 的类型是可空的还是非空的，直接把非空事实写进 IR，不需要靠"识别编译器插入的 Intrinsics 调用"来反推。

> 所以 `IrPrecision` 的主要用途从"识别字节码降级"变成了"标记源码降级"（协程展开、when 降解）。
> `BYTECODE` / `BYTECODE_SYNTHETIC` 保留，因为 Plumb 现有的 `.jar` 分析路径仍需要它们。

#### 扩展 3：`TacFunction` 增加 2 个字段

```java
// In TacFunction:
private final SourceLanguage sourceLanguage;   // defaults to JAVA
private final IrPrecision irPrecision;         // defaults to SOURCE_FULL
```

**兼容性保障**（关键，因为 `TacFunction` 是 class 不是 record，有 7 个构造器重载）：

- **不改动任何现有构造器签名**。新增字段只在**最完整的那个构造器**里赋值，其余重载委托给它并传入默认值。
- 默认值 `JAVA` + `SOURCE_FULL` = **现有行为完全不变**。
- `serialVersionUID` **不需要 bump**（字段新增向后兼容，见 `SourceOrigin.java:24-27`）。

```java
// Added after the existing most complete constructor:
public TacFunction(
    List<TacOperand.TacVar> params,
    List<TacOperand.TacVar> locals,
    List<TacInstruction> instructions,
    List<String> classAnnotations,
    List<String> methodAnnotations,
    Map<String, List<String>> fieldAnnotations,
    Map<String, List<String>> paramAnnotations,
    List<TryRegion> tryRegions,
    SourceLanguage sourceLanguage,   // new
    IrPrecision irPrecision) {       // new
  this(params, locals, instructions, classAnnotations, methodAnnotations,
       fieldAnnotations, paramAnnotations, tryRegions);
  this.sourceLanguage = sourceLanguage == null ? SourceLanguage.JAVA : sourceLanguage;
  this.irPrecision = irPrecision == null ? IrPrecision.SOURCE_FULL : irPrecision;
}
```

> **注意**：`TacFunction` 的字段是 `final`，所以不能在已有构造器里"后赋值"。要么用上面的委托模式，要么把字段改为非 final。**委托模式更好**——它保持了 `final` 的不可变保证，且现有 7 个构造器一行都不用改。

#### 扩展 4：`TacInstruction.Kind` 增加 `NULL_CHECK`

```java
public enum Kind {
  ...,
  /**
   * The null check inserted by the compiler (Kotlin Intrinsics.checkNotNull*).
   *
   * <p>Semantics: if dest is null then an NPE is thrown; otherwise dest is
   * proven to be non-null.
   *
   * <p>Use [strictly limited to]: letting the null-deref checker know that
   * "this value has been proven non-null", and thereby **eliminating false
   * positives**. It must never be used to **prove safety** -- the compiler
   * switches (-Xno-param-assertions) and ProGuard/R8 stripping can all remove
   * these checks; no check in the bytecode does not mean it is safe at runtime.
   *
   * <p>Note: {@code Intrinsics.areEqual} and
   * {@code Intrinsics.throwUninitializedPropertyAccessException}
   * are neither null checks and must not be mapped onto this opcode.
   */
  NULL_CHECK
}
```

> **在源码路径下，`NULL_CHECK` 的使用频率大幅降低。**
> Kotlin 源码前端可以直接从类型系统拿到可空性——`KaType.isMarkedNullable` 或 IR 的 `IrType` 可空标记，**不需要**"识别编译器插入的 Intrinsics 调用"这种间接推断。
>
> 但 `NULL_CHECK` 仍然需要，因为：
> 1. **Java 互操作边界**：Java 方法返回平台类型（platform type），Kotlin 侧看到的是 `String!`。前端需要表达"这个值来自 Java，返回值可能是 null"。
> 2. **字节码路径**（`.jar` 分析）仍然需要。
> 3. **Scala**：没有非空类型系统，但有 `Option`/`null` 显式语义，可以复用同一机制表达"已知非空"。

#### 扩展 5：可空性表达（**关键设计决策**）

这是本设计里**最容易出错**的一处。`TacType` 的现状（已核查 `TacType.java:37-147`）：

```java
public final class TacType implements java.io.Serializable {
  private static final long serialVersionUID = 1L;
  public enum Kind { BOOLEAN, BYTE, ..., CLASS, ARRAY, NULL, VOID, UNKNOWN }
  public static final TacType STRING = new TacType(Kind.STRING, null);   // ← singleton constant
  public static final TacType INT    = new TacType(Kind.INT, null);      // ← singleton constant
  ...
  private final Kind kind;
  private final Object payload;      // CLASS→fqn, ARRAY→component
  private TacType(Kind kind, Object payload) { ... }   // ← private constructor

  @Override public boolean equals(Object o) {
    if (!(o instanceof TacType t)) return false;
    if (kind != t.kind) return false;
    return Objects.equals(payload, t.payload);        // ← compares kind + payload only
  }
  @Override public int hashCode() { return Objects.hash(kind, payload); }
}
```

**三个约束**：
1. **单例常量模式**：`STRING`/`INT`/`CLASS` 等是 `static final` 实例。
2. **`equals` 只比较 `kind` + `payload`**——加字段进 `equals` 会改变现有语义。
3. **`Kind` 已经有 `NULL` 哨兵**，但它表达的是"这个值就是 null 字面量"，**不是"这个引用可能为 null"**。这两个概念完全不同，不能混用。

#### 错误做法 A：给 `TacType` 加 `nullable` 字段

```java
// ❌ not viable
private final boolean nullable;
```

**为什么不行**：
- 加了字段就要进 `equals`/`hashCode` → `TacType.STRING.equals(TacType.NON_NULL_STRING)` 的语义变了。
- 单例常量（`public static final TacType STRING`）会与"非空 String"概念冲突——**同一个 `String` 类型既是可空又是非空？**
- `TacType` 是 `final` + 私有构造器，要表达"可空/非空"就得引入**类型级别的乘积**（CLASS × nullable = 2N 个状态），工厂方法全部要改。

#### 错误做法 B：把 `NULL` kind 当作"可空"

```java
// ❌ semantically wrong
// Kind.NULL expresses "this value IS null" (a constant), not "this reference MAY be null"
// (a type attribute)
```

`PointsToSolver.java:302,321,358,447` 和 `NullDerefChecker.java:106` 都是用 `Const.Kind.NULL` 判断**指令的字面量操作数**——那是**值层面**的概念。类型层面的"可空"是**另一个维度**。

#### 推荐做法：可空性走**独立的类型修饰维度**，不碰 `TacType`

**决策：可空性不进入 `TacType`，而是作为 `TacVar` 的属性。**

理由：
- **可空性是"某个变量的值的属性"，不是"某个类型的属性"。** 同一个 `String` 类型，`x: String`（非空）和 `y: String?`（可空）是两个不同的**变量**。
- 这与 Kotlin 本身的模型一致——可空性写在变量的类型上（`String` vs `String?`），而 `String` 这个**类**本身不知道自己的引用可不可空。
- `TacOperand.TacVar` 正是"带类型的变量"——可空性放这里语义最准。

```java
// TacOperand.java (the extension)
public record TacVar(int id, String name, TacType type) {
  // new: the nullability marker
}
```

> 但 `TacOperand.TacVar` 是一个 **record**——加字段会改变 record 的 canonical constructor 和 `equals`/`hashCode`。这同样是破坏性变更。

**因此，最终决策分两层**：

**第 1 层（IR 扩展，最小且非破坏）：`TacType` 增加一个 `nullable` 维度，但用"工厂 + 单例不变"的方式。**

```java
// TacType.java
public final class TacType implements java.io.Serializable {
  private static final long serialVersionUID = 1L;

  public enum Kind { BOOLEAN, ..., CLASS, ARRAY, NULL, VOID, UNKNOWN }

  // ... the existing singleton constants stay unchanged (all nullable = true, the Java semantics) ...

  private final Kind kind;
  private final Object payload;
  private final boolean nullable;     // ← new

  private TacType(Kind kind, Object payload) {
    this(kind, payload, true);        // ← defaults to true: a Java reference type is nullable by default
  }

  private TacType(Kind kind, Object payload, boolean nullable) { ... }

  /** Builds a non-null type. */
  public static TacType nonNull(TacType t) {
    if (t == null || !t.nullable) return t;
    return new TacType(t.kind, t.payload, false);
  }

  public boolean nullable() { return nullable; }

  @Override public boolean equals(Object o) {
    if (!(o instanceof TacType t)) return false;
    if (kind != t.kind) return false;
    if (nullable != t.nullable) return false;      // ← nullable goes into equals
    return Objects.equals(payload, t.payload);
  }
  @Override public int hashCode() { return Objects.hash(kind, payload, nullable); }
}
```

**这个做法为什么是安全的**：
- **现有单例常量的 `nullable` 全是 `true`** → 与改动前语义完全一致。
- **现有 `equals` 的实际使用场景几乎全是"同 kind 同 payload"**——加 `nullable` 只会让"可空 X" 与 "非空 X" 不再相等，这是**期望行为**。
- 现有 `TacType.ofClass(fqn)` / `arrayOf(c)` 返回 `nullable=true` → **现有代码零改动**。
- 序列化：`serialVersionUID` 不 bump（字段新增向后兼容，同 `SourceOrigin.java:24-27` 的契约）。

> ⚠️ **必须验证的一处**：`FieldOpClassifier.classNameOfType(TacType.STRING)` 这类**按类型查类名**的工具（`FieldOpClassifierTest.java:88` 已有测试）如果内部用 `equals` 做类型匹配，会因为 `nullable` 的加入而行为改变。**Phase 0 必须审计所有 `TacType.equals` 的调用点**，确认没有"拿一个非空类型去匹配一个可空单例常量"的场景。

**第 2 层（checker 层）：`NullDerefChecker` 用"类型可空性"作为第一判据。**

```java
// NullDerefChecker.java
// 1) The type of the target variable is non-null -> it cannot be a null deref
TacType targetType = typeOf(ins.lhs1);
if (targetType != null && !targetType.nullable()) return;

// 2) A NULL_CHECK instruction -> record "this variable has been proven non-null"
if (ins.kind == TacInstruction.Kind.NULL_CHECK) {
    markNonNull(dest);
    return;
}
// 3) An ordinary dereference
if (isNull(dest)) { report(); }
```

> **为什么第 2 层仍然需要 `NULL_CHECK`**：类型系统给的是"声明处的可空性"，而**控制流收窄**（smart cast、`requireNotNull`、显式 `if (x != null)`）是**运行时的、路径相关的**事实。类型可空性回答不了"在这个分支点上，x 已经被证明非空了吗"。这正是 `NULL_CHECK` 和 `dfa-nullness` 事实域的职责。

**第 3 层（跨语言）：Java 侧仍按现有逻辑走。**

Java 没有非空类型系统 → 所有引用类型 `nullable=true` → **现有 null deref 检测行为完全不变**。这就是"默认值取 `true`"的意义所在。

#### 落地后的两个修正（实现期发现）

**修正 1：`TacVar` 的身份不含类型。**

上面的讨论假设 `TacVar` 的 `equals` 会包含类型。实现时发现不能这样：

`TacOperand.TacVar` 的身份就是"**存储位置**"。DFA 引擎的每个 per-variable map
（`taintedVars`、`taintPaths`、field / array tracker）都以它为 key。
如果类型参与相等性，那么"细化后的变量"就是**不同的 key**：

```
at declaration:  TacVar("x", UNKNOWN)        -> the key of taintedVars
after refinement: TacVar("x", java.lang.String) -> a different key
-> the taint recorded earlier is not found -> the finding is silently lost
```

**没有异常，只是少一个 finding**——这是最难发现的一类 bug。

所以最终规则是：**身份 = `name + isTemp`，类型是可细化元数据，不参与**。
nullability 也因此自动安全：可空的 `String` 和非空的 `String` 是同一个存储位置，
null-deref checker 直接从变量上读类型，而不是从 key 上读。

**修正 2：`toString()` 不能用作身份。**

`TacType.toString()` 现在会追加 `" (non-null)"`（让 dump 能解释"为什么这个 finding 被抑制了"）。
但 `FieldOpClassifier` 之类的工具用**类名**做 map key——装饰过的 key 匹配不上未装饰的 key，
而且**失败是静默的**。

因此拆成两个方法：

| 方法 | 用途 | 可否做 key |
|---|---|---|
| `displayName()` | 身份形式（纯类名） | ✅ 可以 |
| `toString()` | 显示形式（带 `(non-null)` 装饰） | ❌ 不可以 |

> **推论 —— 给一个已有的值对象加"显示装饰"时，必须同时提供"身份形式"。**
> 否则装饰会顺着 `toString()` 渗进所有把它当 key 用的地方，而且渗进去之后是静默的。

### 3.3 前端契约（Frontend Contract）

所有前端实现同一个接口。这是**唯一的**多语言扩展点。

```java
package org.plumb.tac.frontend;

/**
 * The language frontend contract: source code -> TAC.
 * An implementation must be stateless and safe to call in parallel.
 */
public interface TacFrontend {

    /** The language this frontend handles. */
    SourceLanguage language();

    /** The source file extensions this frontend accepts, such as {"kt", "kts"}. */
    Set<String> fileExtensions();

    /**
     * Translates one source file.
     * @param source the content of the source file
     * @param path   the path of the source file (for the error messages and the line number mapping)
     * @param ctx    the compile context (the classpath, the dependent modules, the parse cache)
     * @return the list of functions this file produces (it may be empty, as for a declaration-only file)
     */
    List<TacFunction> translate(SourceFile source, CompileContext ctx);

    /** Whether this frontend needs the external compilation output (the bytecode/classpath). */
    default boolean requiresClasspath() { return false; }
}
```

**为什么是 `List<TacFunction>` 而不是 `TacProgram`**：一个源文件可能产生多个函数，且要能被合并到全局 program。现有 `MergedProgram` 已经处理这个。

**新增的编译上下文契约**（源码路径的核心难点）：

```java
package org.plumb.tac.frontend;

/**
 * The compile context: the shared state of one analysis session.
 *
 * <p>[Important] It is session-scoped, not file-scoped. The symbol resolution,
 * the type inference and the module dependency graph all have to be shared
 * across the whole session, otherwise the cross-file analysis degrades into
 * O(n^2).
 */
public interface CompileContext extends AutoCloseable {

    /** The mapping from a source root directory to a module. */
    List<Module> modules();

    /** The cache of the symbols already resolved. It must be reused across files. */
    SymbolResolver symbols();

    /** The cache of the parse results. */
    <T> T computeIfAbsent(String key, Function<String, T> factory);

    @Override
    void close();
}
```

> **推论 —— 编译上下文是 session 级，不是 file 级。**
> Kotlin 的 Analysis API Standalone 会话、Scala 的编译单元，都是整个构建共享的。
> 如果按文件建上下文，跨文件符号解析会退化成 N 次全量解析。

### 3.4 Kotlin 源码前端（Q2 的正式回答）

Kotlin 有**两条**可行的源码 → IR 路径。它们的精度、依赖、成本不同。

#### 路径 K1：Analysis API Standalone（**推荐作为主路径**）

**核实到的事实**（来自 Kotlin Analysis API 官方 `Writerside/topics/Index.md` 与 `StandaloneAnalysisAPISessionBuilder.kt`）：

- Analysis API 构建在 **Kotlin PSI** 之上，通过 `KaSession` / `KaSymbol` / `KaType` / `KaScope` 四大概念提供语义。
- **Standalone 模式** 是官方提供的 headless 运行方式：
  - 入口：`buildStandaloneAnalysisAPISession(projectDisposable) { buildKtModuleProvider { ... } }`
  - 内部用 `KotlinCoreProjectEnvironment`（来自 `kotlin.cli.jvm.compiler`）搭建 headless IntelliJ 平台核心环境
  - **不需要 IDE**，`java.awt.headless=true`
  - 支持**多模块**：`buildKtModuleProvider { addSourceModule(...); addLibraryModule(...) }`
  - 支持**库模块**（`.jar` 作为二进制依赖）
- **官方声明的限制**（很重要，必须写进设计）：
  > "Currently, the only officially supported use-case is IntelliJ IDEA plugin development. While the Analysis API provides the Standalone mode for command-line tool developers, **it is currently in development and subject to incompatible changes.**"
  >
  > 也就是说：**Standalone 模式是官方实验性 API，可能不兼容变更。**

**优点**：
- **Kotlin/Java 混合项目原生支持**——Analysis API 的 `KaSession` 天然能同时解析 Kotlin 和 Java 声明（Java 侧走 light classes）。
- **类型信息是一等公民**：`KaType.isMarkedNullable` 直接给出可空性，不需要任何间接推断。
- **lambda 体完全可见**（PSI 层面）。
- **不需要跑真正的编译**——只做分析（typer 级别），比全量编译快。
- **依赖可控**：`analysis-api-standalone` + `analysis-api-fir` + `low-level-api-fir` + `symbol-light-classes`。

**缺点**：
- API 标注 `@KaExperimentalApi`，**版本升级可能崩**。
- 需要理解 IntelliJ 平台的服务注册模型（`registerApplicationService` / `registerProjectService`）。
- 进程级副作用：`setupIdeaStandaloneExecution()` + headless 属性 + 全局线程池是 **JVM 进程级状态**，同一进程内并行跑多个 Standalone 会话时这些全局量共享。

#### 路径 K2：Compiler Plugin API 的 `IrGenerationExtension`（**推荐作为精度增强/备选**）

**核实到的事实**：
- Kotlin Backend IR 是**规整的树形 IR**：`IrModuleFragment` / `IrSimpleFunction` / `IrBlockBody` / `IrExpressionBody` / `IrCall` / `IrWhen` / `IrBranch` / `IrTry` / `IrForLoop` / `IrDoWhileLoop` / `IrConst` / `IrGetValue` / `IrSetValue` / `IrGetField` / `IrSetField` / `IrGetElement` / `IrSetElement` / `IrConstructorCall` / `IrReturn` / `IrThrow` / `IrTypeOperatorCall` / `IrIntrinsic` / `IrFunctionExpression`（lambda 体）。
- IR tree 节点由 `IrTree.kt` **规范生成**，字段/访问器都是机器可读的——**非常适合写自动转换器**。
- `IrGenerationExtension` 是 Kotlin 2.0+ 稳定的插件扩展点。

**优点**：
- **IR 树 = 编译器的最终内部表示**，与 TAC 几乎一一对应（§3.5 的映射表）。
- **协程展开后的状态机可见**——这是源码路径独有的能力（K1 的 PSI 层看到的是 `suspend fun` 声明）。
- 编译器插的空检查在 IR 里是 `IrIntrinsic`/`IrCall` 到 `Intrinsics.checkNotNull*`，**但类型系统本身也带可空性**，两者互为验证。

**缺点**：
- 需要**真正跑一遍编译**（Kotlin 编译是耗时的）——不能只做分析。
- 编译器内部 API **跨版本不兼容**（每次 Kotlin 升级都可能崩）。
- `IrGenerationExtension` 的执行时机在 lowering 之后，拿到的 IR 已经是**降级/降序后的**形式。

#### 决策：K1 为主，K2 为**交叉验证**通道

**推荐组合**：
- **主路径 = K1（Analysis API Standalone）**：覆盖 90% 的实际需求（符号解析、类型、可空性、注解、lambda），成本最低，依赖最轻。
- **K2（IR plugin）不是"第二期"，而是"验证通道"**——见 §6.4 的双路径交叉验证要求。两条路径用**同一套 TAC IR**，finding 集应该一致；不一致的地方要么是 K1 的精度损失，要么是 K2 的 lowering 差异，**这本身就是最有价值的 bug 探测器**。

> **不推荐"只做 K2"**：K2 要跑完整编译，Kotlin 编译本身很慢（分钟级），对"静态分析工具"是糟糕的体验。而 K1 只做分析，快得多。
>
> **也不推荐"只做 K1"**：K1 的 API 是 `@KaExperimentalApi`（官方明说"subject to incompatible changes"）。必须有 K2 作为**契约验证**，确保 K1 的输出没有系统性偏差。

### 3.5 Scala 3 源码前端

**核实到的事实**（来自 Scala 官方 compiler-plugins 文档）：

- Scala 3 的 `PluginPhase` 是 **mini-phase**，继承自 `dotty.tools.dotc.transform.MegaPhase.MiniPhase`。
- 覆写 `transformApply` / `transformDefDef` / `transformValDef` / `transformMatch` / `transformSelect` / `transformClosure` / `transformBlock` 等钩子，编译器在 **fused traversal** 中调用。
- 通过 `runsAfter` / `runsBefore` 指定插入位置：
  ```java
  override val runsAfter = Set("typer")     // after typer: symbols and types are resolved
  override val runsBefore = Set("pickler")  // before pickler: rewrites make it into TASTy
  ```
- 插件入口：`extends StandardPlugin`，实现 `initialize(options: List[String])(using Context): List[PluginPhase]`，jar 根目录放 `plugin.properties`（`pluginClass=xxx.Yyy`）。
- **TASTy 序列化在 `pickler` 阶段**，所以 `runsAfter=typer, runsBefore=pickler` 能拿到**完整的带类型树**，且改写会进 TASTy。
- **重要限制**：`PluginPhase` 只能看到**本次编译的文件**。Zinc 增量编译下，跨文件不变量看不到全貌。

**我们的方案**：
- **只用 `PluginPhase`，不用 `ResearchPlugin`。** Research plugin 能重排整个编译管线，但**只在 `-experimental` 或 nightly/snapshot 版本启用**——不能建在生产工具上。
- **位置选 `runsAfter = Set("typer")`, `runsBefore = Set("pickler")`**：typer 后拿得到符号和类型，pickler 前保证我们看到的是"高层语义"（`match` 还是 `Match`，没被 Maranget 决策树展开）。

**Scala 3 `tpd.Tree` → TAC 映射表：**

| Scala 3 `tpd.Tree` | TAC | 备注 |
|---|---|---|
| `DefDef` | `TacFunction` | `paramss` → params |
| `ValDef` | `MOVE`（局部）/ `FIELDSTORE`（成员） | `Flags.Mutable` 区分 var/val |
| `Block(stats, expr)` | 指令序列 | |
| `Apply(fun, args)` | `CALL` | `fun.symbol` → calleeSig |
| `TypeApply(fun, targs)` | `CALL` | 泛型调用，类型参数丢弃 |
| `Select(qual, name)` | `FIELDLOAD` | |
| `Assign(lhs, rhs)` | `MOVE` | |
| `New(tpt, args)` | `NEW` | |
| `If(cond, thenp, elsep)` | `BRANCH` | |
| `WhileDo(cond, body)` | `BRANCH` + `JUMP` | |
| `Match(selector, cases)` | `BRANCH` 链 | **降级点**：match → if/else 链 |
| `CaseDef` | 分支目标 + `MOVE`（模式绑定） | `Bind`/`Typed` → 参数 |
| `Closure(meth, params, body)` | 独立 `TacFunction` + `CALL` | **lambda 体可见** |
| `Try(body, catches, finalizer)` | `THROW` + `CATCH_HANDLER` + `FINALLY_ENTER` | |
| `Literal(Constant)` | `Const` | |
| `TypeTree` / `Typed` | 丢弃 / `MOVE` | |
| `Select` 到 `apply`/`unapply` | `CALL` | 伴生对象合成方法 |
| `Inlined` | 展开后的树 | **注意**：`shallowFold` 会作用在展开后的树上 |
| `Template` | 跳过 | 类成员在 `DefDef`/`ValDef` 里 |
| `Import` | 跳过 | 导入不影响语义 |

**Scala 2 明确不做**：
- Scala 2 编译器内部 API 与 Scala 3 完全不同（`Symbol` vs `Denotation`，`Tree` vs `TypedTree`）。
- Scala 2 已进入维护期（官方路线图不再有 2.14）。
- **成本**：需要第二套转换器 + 第二套版本锁 + 第二套测试 fixture，ROI 极差。
- **决策**：**Scala 2 不支持。** 若用户有 Scala 2 代码库，官方路径是迁移到 Scala 3。

### 3.6 其他 JVM 语言

**这里的关键洞察是：Coverity 也没做，我们也不需要做。**

| 语言 | Coverity | 我们的决策 | 理由 |
|---|---|---|---|
| Groovy | ❌ 不支持 | **不做** | 动态派发（`CallSite`），数据流分析准确率无实用价值 |
| Clojure | ❌ 不支持 | **不做** | `IFn.invoke` 动态派发，同上 |
| JRuby | ❌ 不支持 | **不做** | 动态派发 + Ruby 语义 |
| Gosu | ❌ 不支持 | **不做** | 静态类型但生态极小，无 checker 需求 |
| Xtend | ❌ 不支持 | **不做** | 编译到 Java 源码，绕路 |

**如果将来要支持 Groovy/Clojure，唯一有意义的形态是"配置检查"而非"数据流分析"**（比如 Gradle 构建脚本里的明文密码）。这属于 AST 规则范畴，本期不做。

---

## 4. 数据流分析：需要改什么

**答案是：几乎不改，但有三处必须改。**

### 4.1 规则层：加语言绑定，零新语法

现有 `checkers.yaml` / `sink-families.yaml` / `fact-kinds.yaml` 结构不变。加一个语言维度：

```yaml
# the sink-families.yaml extension
sink_families:
  sql.sinks:
    description: "SQL query construction/execution"
    patterns:
      - pattern: "^java\.sql\.Statement\.execute(/\d+)?"
      - pattern: "^jakarta\.persistence\.EntityManager\.createQuery"
    languages: [java, kotlin, scala]      # ← the new field, optional

  regex.sinks:                              # ← the new family (CWE-1333)
    description: "user-controlled regex pattern"
    patterns:
      - pattern: "^java\.util\.regex\.Pattern\.compile"
      - pattern: "^kotlin\.text\.Regex\.<init>"           # Kotlin specific
      - pattern: "^scala\.util\.matching\.Regex\.<init>"  # Scala specific
    languages: [java, kotlin, scala]
```

**设计要点**：
- `languages` 是**可选**字段。不写 = 所有语言都启用（保持向后兼容）。
- **跨语言 sink 天然共享**：`java.sql.Statement#execute*` 在 Kotlin/Scala 里也是同一个符号。**源码路径下这一点同样成立**——Kotlin 的 `stmt.executeQuery(...)` 解析到的就是 `java.sql.Statement.executeQuery`。所以"跨语言 sink 零成本共享"不是字节码路径的专利，**源码路径一样成立**。
- **Kotlin 特有 sink** 需要新的 pattern：`kotlin.text.Regex#<init>`、`kotlin.io.readLine`、`kotlin.system.getenv`。
- **Scala 特有 sink**：`scala.sys.process.ProcessBuilder#<init>`、`scala.util.matching.Regex#<init>`。

**注意 `SinkPattern` 的两个既有语义（`SinkFamily.java:71-104`），新 pattern 必须遵守**：
- **`argIndex` 语义**：`-1` = 用 family 默认值，`-2` (`RECEIVER_ARG_INDEX`) = 污染值在 receiver 上（如 `ObjectInputStream#readObject`），`>= 0` = 显式用户参数下标。Kotlin/Scala 的 sink 若污染值位置与 Java 版本不同，**必须显式指定 `argIndex`**。
- **relaxed form 自动派生**：`SinkPattern.relaxedForm()` 会把 `^fully\.qualified\.Class\.method` 里的**包前缀变可选**，但**类名和方法名必须保留**。这意味着 `^kotlin\.text\.Regex\.<init>` 的 relaxed 形式是 `^(?:[\w.]+\.)?Regex\.<init>`——它**不会**误匹配 `java.util.regex.Pattern.compile`（方法名不同），但**会**匹配任何包下的 `Regex.<init>`。这是可接受的（`Regex.<init>` 确实是 sink），但需要知道。

> 这两条语义对 Kotlin/Scala 尤其重要：Kotlin 的 `Regex`、`ProcessBuilder` 等类名在 relaxed 模式下可能与第三方库的同名类冲突。**加新 pattern 后必须跑一遍"该 pattern 是否会误匹配"的验证**。

### 4.2 `TaintRule`：加一个 `languages` 字段

```java
public record TaintRule(
    String ruleId,
    String sourceKindName,
    String sinkFamilyKey,
    String cwe,
    String severityLabel,
    TaintDomain domain,
    boolean enabled,
    Set<String> languages) {          // ← new, empty = all languages
  public boolean appliesTo(SourceLanguage lang) {
    return languages == null || languages.isEmpty() || languages.contains(lang.name());
  }
}
```

**兼容性**：`TaintRule` 已有 6 参和 7 参构造器。加第 8 个字段需要加一个兼容构造器（现有代码里 `TaintRule.of(...)` 有多个重载）。这是**唯一**的破坏性改动点，而且可控。

### 4.3 Checker：加语言门控

```java
public interface DfaChecker {
    /**
     * Whether this checker applies to the given function.
     * The default: it applies to every language.
     */
    default boolean supports(TacFunction fn) { return true; }
}
```

然后在 `DfaEngine` 的遍历里加一道门。**注意：这必须是"跳过"，不是"报错"**——检查 `fn.sourceLanguage()` 而不是"这个 checker 不认识这个语言"。

### 4.4 Kotlin 空安全：源码路径下的三层机制

这是 Kotlin 支持的**核心风险点**，必须系统解决。

**好消息**：源码路径下，空安全**不再是"识别编译器插入的 Intrinsics 调用"这种间接推断**，而是**直接读类型系统的可空性**。

**Kotlin 的空安全在源码侧的表达**：

| 概念 | Kotlin 源码 | Analysis API 表达 | IR 层表达 |
|---|---|---|---|
| 非空类型 | `s: String` | `KaType.isMarkedNullable == false` | `IrType` 非可空 |
| 可空类型 | `s: String?` | `KaType.isMarkedNullable == true` | `IrType` 可空 |
| 平台类型（Java 互操作） | `s: String!` | `KaFlexibleType`（`KaType` 的 flexible 变体） | — |
| 智能转换 | `if (s != null) { s.length }` | 控制流敏感的 smart cast | smart cast 后的 IR |
| 显式 null 检查 | `requireNotNull(s)` | 类型收窄 | — |

**第一层：前端直接产出非空事实。**

Kotlin 前端在处理每个变量声明时，直接把可空性写进 TAC 的类型信息。

> ⚠️ **这一层的实现细节不是显然的**，已在 §3.2 扩展 5 中完整推导。核心结论：
> - `TacType` 用**单例常量 + 私有构造器 + `equals` 只比较 `kind`+`payload`**，所以不能简单地加一个 `nullable` 字段了事。
> - 正确做法是加 `private final boolean nullable` + `nonNull(TacType)` 工厂 + `nullable` 进 `equals`/`hashCode`，**所有现有单例常量的 `nullable` 保持 `true`**。
> - **默认值必须是 `true`**（Java 语义：引用类型可为 null）——否则 Java 侧 null deref 检测会立刻漏报一大片。
> - `TacType.Kind.NULL` 是"值就是 null"的哨兵，**不能**当"可空"用。

Kotlin 前端产出非空类型的方式：

```java
// The Kotlin frontend: it resolves s: String (non-null)
TacType t = TacType.ofClass("java.lang.String");
TacType nonNull = TacType.nonNull(t);   // nullable = false
```

**第二层：`NullDerefChecker` 用类型可空性 + `NULL_CHECK` 双重判据。**

```java
// 1) The type system decides directly: the type of the target variable is non-null -> it cannot be a null deref
if (targetType != null && !targetType.nullable()) return;

// 2) A NULL_CHECK instruction: record "this variable has been proven non-null"
//    (the control-flow narrowing that the type system cannot answer)
if (ins.kind == TacInstruction.Kind.NULL_CHECK) {
    markNonNull(dest);
    return;
}
// 3) An ordinary dereference
if (isNull(dest)) { report(); }
```

> **为什么第 2 步不能省**：类型可空性回答的是"**声明处**这个引用允不允许 null"。而 **smart cast / `requireNotNull` / `if (x != null)` 的收窄是运行时的、路径相关的**——类型系统给不出这个信息。这正是 `NULL_CHECK` + `dfa-nullness` 事实域存在的理由。
>
> 换句话说：**类型可空性抑制"静态就不可能为 null"的误报；`NULL_CHECK` 抑制"控制流已证明非空"的误报。两者覆盖不同的误报来源，缺一不可。**

**第三层：taint checker 的"已知非空"集。**

如果 `s` 已被证明非空，后续 `s.length` 不该报 NPE。但 **taint 仍然要传播**（`s` 可能来自用户输入）。

**所以必须把"非空"和"无污点"分开**：这是两个正交的事实域。当前 `TaintDomain` 只处理污点，需要在 `FactBag` 里加一个独立的事实种类：

```yaml
# fact-kinds.yaml
fact_kinds:
  dfa-nullness:
    description: "value is known non-null (Kotlin non-null type / explicit null check)"
    traits: []
```

> ⚠️ **重要约束（`FactKind.java:57-78`）**：`FactKind.Trait` 是**声明性意图，不是引擎执行的行为**。注释里写得很明确——"No production code reads a trait; the propagation is implemented by the checker that owns the kind."
>
> 这意味着：加了 `dfa-nullness` 事实种类后，**它不会自动传播**。非空事实的传播必须由 `NullDerefChecker` 自己实现（它本来就是唯一关心非空的 checker）。所以第三层的实现位置是 checker 内部，不是引擎。
>
> 这反而更简单——不需要动引擎的传播框架。
>
> 这也回应了用户提到的 **"Cwe476 null deref"** 场景：Java 里 Kotlin 生成的 getter 会带 null check，混合代码库（Java + Kotlin）里如果不区分，误报会非常多。

**方向性铁律 —— 空安全信息只能用于消除误报，不能用于证明安全：**

Kotlin 的非空保证在 JVM 上不是类型系统实现的，而是编译器在"边界"插入的 `Intrinsics` 调用：

| Intrinsics 方法 | 插入位置 | 语义 |
|---|---|---|
| `checkNotNullParameter(value, name)` | 非空**参数**入口 | 参数非空保证 |
| `checkNotNullExpressionValue(value, expr)` | 平台类型的**返回值**（如 Java 方法返回） | 表达式非空保证 |
| `checkFieldIsNotNull(value, name)` | 非空**字段** | 字段非空保证 |
| `checkReturnedValueIsNotNull(value, expr)` | 非空**返回值** | 返回值非空保证 |
| `throwUninitializedPropertyAccessException(name)` | `lateinit` 属性未初始化访问 | **不是 null check**，是 lateinit 检查 |
| `areEqual(a, b)` | data class `equals()` | **不是 null check**，是 null 安全比较 |

**四个关键细节（决定实现正确性）**：

1. **本地变量不插检查**。`val x: String = "hello"` 编译后**没有** `Intrinsics` 调用——编译器数学上可证明它不可能为 null。检查只出现在**边界**（参数、返回值、字段、平台类型表达式）。
2. **`Intrinsics.areEqual(a, b)` 绝不能归类成 null check**。它是 data class `equals()` 的比较辅助函数。误判成 `NULL_CHECK` 会把"两值相等比较"当成"非空证明"，污染非空事实集。
3. **可空参数不插检查**。`fun f(s: String?)` 编译后**没有** `checkNotNullParameter`。所以字节码里出现 `checkNotNullParameter` ⟺ 该参数在源码里是非空类型。
4. **检查可以被关掉**。`-Xno-call-assertions` / `-Xno-param-assertions` / `-Xno-receiver-assertions` 会移除对应检查；ProGuard/R8 的 `-assumenosideeffects` 也会剥掉。**所以字节码路径下不能假设"有检查 ⟹ 运行时安全"**。

> **第 4 点决定了整个机制的用法方向**：
> `NULL_CHECK` 只能用于**消除误报**（false positive），**绝不能用于证明安全**（true positive）。
> 方向搞反了会把真漏洞藏起来——那比误报严重得多。
>
> **在源码路径下这个约束更清晰**：源码的可空性是**语言语义**（Kotlin 类型系统保证），不是"运行时可能被剥掉的检查"。所以源码路径的可空性信息**更可靠**——但即便如此，"非空类型 ⟹ 该具体值非空"仍然依赖智能转换的正确性，所以**推理方向依然只能是单向的**（非空 ⟹ 不报 NPE，绝不反过来）。

### 4.5 Kotlin 编译器合成代码：第二类误报源

data class 会被编译器生成 **6 个方法**：

| 生成方法 | 用途 | 对静态分析的干扰 |
|---|---|---|
| 构造函数 | 含 `checkNotNullParameter` | null check（已由 4.4 处理） |
| `equals()` | 结构相等，用 `Intrinsics.areEqual` | **不是 null check**（见 4.4 细节 2） |
| `hashCode()` | 结构哈希 | 死代码误报源 |
| `toString()` | 结构字符串 | 死代码误报源 |
| `componentN()` | 解构 | **死代码误报源**（源码里看不到调用点） |
| `copy()` / `copy$default()` | 带默认值的复制（bitmask 机制） | 死代码误报源 |

**问题**：`component1()`/`component2()`/`copy()` 在源码里**没有显式调用点**（解构 `val (a, b) = user` 编译后是 `component1()`/`component2()` 调用）。`UnusedVariableChecker` / `UnusedParameterChecker` 会把它们报成"未使用的私有方法"——**这正是 SpotBugs/FindBugs 在 Kotlin 上的经典误报**。

**源码路径下的解法（比字节码路径干净得多）**：

**Analysis API 路径**：`KaSymbol` 有 `isSynthetic` 标志（编译器合成的符号会被标记）。此外 `KaDeclarationSymbol` 对 data class 的 `componentN`/`copy` 有明确的合成标记。

**IR 路径**：`IrSimpleFunction` 对合成方法有 `isSynthetic`/`origin` 区分。

> **对比字节码路径**：字节码下要靠命名启发式识别（`component<N>` / `$default` / `access$` / `ACC_SYNTHETIC`），**容易漏也容易误判**。源码路径直接读编译器标记，**零启发式**。
>
> 这正是源码主线的核心收益之一。

**解法**：把编译器合成的方法标记为 `IrPrecision.SOURCE_LOWERED`（或加一个更精确的合成标记），让"未使用"类 checker 跳过它们。

> **这是 `IrPrecision` 存在的第二个理由**——第一个是 null safety（4.4），第二个是合成代码识别（本节）。

### 4.6 协程：源码路径独有的能力

Kotlin 协程（`suspend fun`）是**源码路径的独占收益**。

**IR 层的形态**：编译器把 `suspend fun` 编译成**状态机**（`SuspendLambda` + 状态机类，含 `Continuation` 实现）。IR 层能看到 `IrSuspendFunction` 和状态机的 `when` 分发。

**分析含义**：
- **挂起点的污点流**：源码里 `suspend fun a() { val x = readInput(); b(x) }`，`b` 是另一个 suspend 函数。IR 里 `a` 编译成状态机，`x` 存在状态机字段里。
- **跨 suspend 边界的污点传播**：如果 `b` 是外部函数（不在本文件），污点要在 `b` 的入口重新分析。

**决策**：
- **Phase 1（Kotlin 前端）**：把 `suspend fun` 当作**普通函数**处理（`IrSuspendFunction` → `TacFunction`，参数加一个隐式 `Continuation` 参数）。
  - **漏报风险**：状态机字段的污点跨 `Continuation` 传递会丢失。
  - **但这比字节码路径好**——字节码路径下根本看不到 `suspend fun` 的语义。
- **Phase 2（Kotlin 前端增强）**：显式建模状态机字段（`IrField` + 状态机 dispatch）。
- **验收要求**：Kotlin 协程 fixture 上，**源码路径的 finding 数必须 ≥ 字节码路径**。这是 §6.4 交叉验证的核心用例之一。

### 4.7 跨语言 call graph

**问题**：Java 调 Kotlin、Kotlin 调 Scala，在源码路径下需要**跨语言的符号解析**。

**Analysis API 路径（Kotlin）**：
- `KaSession` 天然跨语言——Kotlin session 能看到 Java 声明（通过 light classes）。
- Java → Kotlin 的调用：Kotlin 前端处理 Java 调用点时，需要能解析到 Kotlin 侧的定义。这需要**共享的符号表**。

**Scala 路径**：
- Scala 3 编译器**只能看到 classpath 上的 Java 二进制**（`.class`），看不到 Java 源码。
- 跨 Scala→Java 调用：直接读 classpath 的 `.class`，用 SootUp 解析符号（Plumb 已有能力）。
- 跨 Java→Scala 调用：Java 侧需要看到 Scala 的定义——要么读 Scala 编译产物的 `.class` + TASTy，要么走 TASTy inspector。

**跨语言 call graph 的设计决策**：

```
        ┌──────────────────────────────────────────┐
        │      the unified TacProgram / MergedProgram │
        └──────────────────────────────────────────┘
             ▲          ▲           ▲          ▲
             │          │           │          │
        ┌────┴───┐ ┌────┴────┐ ┌────┴────┐ ┌───┴──────┐
        │ Java   │ │ Kotlin  │ │ Scala 3 │ │ .jar     │
        │ source │ │ source  │ │ source  │ │ (third   │
        │ front  │ │ front   │ │ front   │ │ Jimple   │
        └────────┘ └─────────┘ └─────────┘ └──────────┘
             │          │           │          │
             └──────────┴─────┬─────┘          │
                              │                │
            cross-language symbol resolution:│
            a Kotlin session can see Java     │
            Scala reads the classpath .class  │
```

**关键设计**：**用一个共享的符号解析层**，而不是让每个前端各自解析。

```java
package org.plumb.tac.frontend;

/**
 * The cross-language symbol resolver: it abstracts the "call site -> the callee"
 * step away.
 *
 * <p>Each language frontend translates its call-site expression into a unified
 * CalleeRef, and this interface resolves it into the final calleeSig.
 */
public interface SymbolResolver {
  /**
   * Resolves a call site.
   * @param callee  the call description given by the frontend (owner FQN + method name + arity)
   * @param context the context of the function the call site is in (used to disambiguate the enclosing class)
   * @return the resolution result; null when it is ambiguous (conservative: make no cross-language assumption)
   */
  Optional<CalleeRef> resolve(CalleeRef callee, CallContext context);
}
```

**跨语言解析的具体策略**：

| 调用方向 | 解析方式 |
|---|---|
| Kotlin → Java | Analysis API 原生支持（Java light classes） |
| Java → Kotlin | Java 前端解析不到源码定义 → 走 Kotlin 前端产出的符号索引（Kotlin 前端先跑，把符号注册到共享表） |
| Scala → Java | Scala 读 classpath `.class` → SootUp 解析 |
| Java/Kotlin → Scala | Scala 前端产出的符号索引 |
| 任意 → `.jar` | SootUp 解析（Plumb 现有能力） |

> **关键工程点**：**前端执行顺序**。Java/Kotlin 前端必须先于 Scala 前端跑（或者反过来），因为跨语言解析需要对方先注册符号。
>
> **解法**：把"符号注册"从"前端翻译"里分离出来——前端跑两遍：
> 1. **Pass 1（符号收集）**：每个前端只解析并注册顶层符号（类/方法签名），不产出 TAC。
> 2. **Pass 2（翻译）**：在完整符号表就绪后，各前端产出 TAC。
>
> 这个两阶段设计让前端之间的依赖变成**显式的、可并行化的**（Pass 1 之间可并行）。

---

## 5. 分阶段路线图

> **进度快照（2026-11-02）**：Phase 0 **已完成**，Phase 1 / Phase 2 的前端模块已创建并开始实现。
> 下表中的"已完成"标记表示该项已落地并有回归测试锁定。

### Phase 0 — 地基（不产出新漏洞，但决定后面一切）—— **已完成**

| 任务 | 产出 | 验收标准 | 状态 |
|---|---|---|---|
| `SourceLanguage` 枚举（新文件） | IR 扩展 | 序列化往返测试通过 | ✅ 完成 |
| `IrPrecision` 枚举（新文件） | IR 扩展 | 序列化往返测试通过 | ✅ 完成 |
| `TacFunction` 增加 2 个字段 | IR 扩展 | 现有 DFA 测试全绿（**向后兼容**：默认 `JAVA` + `SOURCE_FULL`）。当前基线：`plumb-tac` + `plumb-dfa` 合计 **Tests run: 418, Failures: 0, Errors: 0, Skipped: 14** | ✅ 完成 |
| `TacType` 增加 `nullable` 标记 | IR 扩展 | **默认 `true`**（Java 语义），现有行为零变化 | ✅ 完成 |
| **审计所有 `TacType.equals` 调用点** | 兼容性审计 | `nullable` 进 `equals` 后，"可空 X" 与 "非空 X" 不再相等。必须确认没有"拿非空类型去匹配可空单例常量"的场景（重点：`FieldOpClassifier.classNameOfType` 等按类型查类名的工具） | ✅ 完成——`FieldOpClassifier` 已改用 `displayName()` |
| **回归测试锁死 `nullable` 默认值** | 防回归 | `assertThat(TacType.STRING.nullable()).isTrue()` + `assertThat(TacType.ofClass("x.Y").nullable()).isTrue()` + Java null deref 检测行为不变 | ✅ 完成——`MultiLanguageIrContractTest`（28 tests） |
| `TaintRule` 增加 `languages` 字段 + 兼容构造器 | 规则层扩展 | 现有规则加载零改动 | ✅ 完成 |
| `DfaChecker.supports(TacFunction)` 门控 | 引擎扩展 | 现有 checker 默认 `true`，行为不变 | ✅ 完成——分析循环与 summary 循环**双路径**门控 |
| `fact-kinds.yaml` 增加 `dfa-nullness` | 事实域扩展 | 新事实可被 `FactBag` 存取 | ✅ 完成 |
| `TacFrontend` / `CompileContext` 接口 | 前端契约 | 接口定稿，Java 前端实现它（**证明契约可实现**） | ✅ 完成——`JavaTacFrontend` 是参考实现 |
| **J1：打通 Java language level 链路** | Java 前端修复 | `JavaModule.languageLevel` (String) → `ParserConfiguration.LanguageLevel` 解析器；`JavaSourceParser` 的两处 `JAVA_17` 硬编码改为"配置优先、缺省 `JAVA_21`"。验收：Java 21 语法 fixture 能解析 | ✅ 完成——`JavaLanguageLevelTest`（9 tests） |
| **J2：补 Java 14+ 语法翻译** | Java 前端增强 | `SwitchExpr` / `RecordDeclaration` / `PatternExpr` / sealed 穷尽性。验收：Java 21 fixture 的 TAC 指令序列正确，record accessor 被识别为合成方法 | ✅ 完成——`JavaModernFeaturesTest`（14 tests） |

**Phase 0 额外落地（超出原计划）**：

| 任务 | 说明 |
|---|---|
| `TacOperand.TacVar` 身份规则 | 身份只由 `name + isTemp` 决定，**类型不参与**。类型是可细化元数据；纳入相等性会让"细化后的变量"变成不同的 map key，污点被静默丢弃 |
| `TacType.displayName()` | `toString()` 是**显示**形式（会追加 `" (non-null)"`），`displayName()` 是**身份**形式。凡是需要类型名做 key 的地方必须用后者 |
| `TacBuilder` 语言感知 | 新增 `setSourceLanguage` / `setIrPrecision`，让 Kotlin / Scala 前端复用同一个 builder |
| `plumb-dfa` 规则语言绑定 | `SinkFamily.languages`、`TaintRule.languages`、`SinkFamilyRegistry.lookupAll(sig, language)` |
| Coverity 缺口规则 | CWE-1333（正则注入）、CWE-732（不受限文件访问）落地并注册 |

**Phase 0 的核心价值：把"多语言"这件事在 IR/规则层完全准备好，但一行行为都不变。** 这样 Phase 1 出了问题可以立刻回滚，不影响现有 Java 能力。

> **Phase 0 的两个"必错不起"的点**：
> 1. **`TacType.nullable` 默认值必须是 `true`**。如果默认 `false`，Java 的 null deref 检测会立刻漏报一大片。**必须有专门的回归测试锁住这个默认值。**
> 2. **`TacType.equals` 的调用点审计**。`nullable` 进 `equals` 是唯一一处会改变现有相等语义的地方——它是有意为之（"可空 X" 与 "非空 X" 确实不该相等），但必须逐个确认现有调用点不依赖旧的相等语义。

> **J1/J2 为什么放在 Phase 0 而不是后面**：
> - **J1 是修 bug**，不是加功能。当前 `JavaModule.languageLevel` 传了但没人用，是一条断掉的链路。
> - **J2 是后续语言能力的第一块砖**：Java `record` 的 accessor 是**编译器合成方法**——和 Kotlin `data class` 的 `componentN`、Scala `case class` 的 `copy` 是同一类东西。先把 Java 侧的"合成方法识别"做出来，Kotlin/Scala 就有了一个已验证的实现范式，而不是三家各写一遍。
> - **J1/J2 不碰 IR**（都是前端降解，符合 §0.5.5 的原则）。

### Phase 1 — Kotlin 源码前端（Analysis API Standalone）—— **已落地**

> **状态（2026-11-02）**：模块 `plumb-frontend-kotlin` 已实现并通过 **34 个前端测试 + 4 个 DFA 端到端测试**。

| 任务 | 说明 | 状态 |
|---|---|---|
| 引入 `analysis-api-standalone` 依赖（锁 **2.5.0-Beta2**） | 与主仓 `kotlin.version=2.2.0` **解耦**（§0.5.4）；API 是 `@KaExperimentalApi` | ✅ |
| Standalone 会话构建 | `buildStandaloneAnalysisAPISession` + `buildKtModuleProvider` | ✅ |
| 符号解析层 | `KaResolver` → 共享 `SymbolResolver` | ✅ |
| 类型提取 | `KaType` → `TacType`（含 `nullable`） | ✅ |
| 表达式 → TAC 转换器 | Kotlin PSI 表达式树 → TAC 指令 | ✅ |
| **可空性** | `KaType.getNullability()` → `TacType` 的 nullable 维度。**三态**：非空 / 可空 / **平台类型** | ✅ |
| **合成代码** | `KaSymbol.getOrigin()` → `SOURCE_MEMBER_GENERATED` → `markSynthetic()` | ✅ |
| 行号映射 | PSI offset → 行号 | ✅ |
| 注解通道 | class/method/field 四通道 | ✅ |
| `sink-families.yaml` 增加 Kotlin sink | `kotlin.text.Regex`、`kotlin.system.getenv`、`kotlin.io.readLine` | ✅ |
| **lambda 体** | `KtLambdaExpression` → 独立 `TacFunction` | ❌ 未实现（降级为 problem + `SOURCE_LOWERED`） |
| **协程 / `suspend`** | 状态机建模 | ❌ 未实现（Phase 3） |
| **param 级注解** | — | ❌ 未实现 |
| 混合 Java+Kotlin 项目 | Analysis API 原生支持 | ✅（Java 源码需先编译成 class 才能被解析，见下） |

#### 平台类型：Kotlin 前端最容易搞错的一处

Kotlin 有**三种**可空性，不是两种：

| 类型 | 含义 | 映射到 `TacType` |
|---|---|---|
| `String` | 声明处不可为 null | `nonNull()` |
| `String?` | 可为 null | 默认（nullable） |
| **平台类型**（Java 类型） | **Java 声明不含 null 信息**，且 Kotlin 编译器插入的断言可被 `-Xno-call-assertions` / ProGuard 剥掉 | **nullable** |

**平台类型必须当可空处理**，否则会漏报真实的 NPE。

**怎么识别平台类型？** 不能靠 `getHasFlexibleNullability()`——对一个**显式命名**的 Java 类型
（`s: java.lang.StringBuilder`），它返回 `false`，API 把它报告成普通非空类型。
**可靠信号是分类器的符号 origin**：`KaSymbolOrigin.JAVA_LIBRARY` / `JAVA_SOURCE`。

> **这条是 Kotlin 前端真正的价值所在**：字节码路径下无法区分"平台类型"与"非空类型"，
> 只能靠猜；源码路径下**直接读类型系统，零启发式**。

#### 另一个真实的坑：内容作用域决定文件能不能解析

`KotlinStandaloneProjectStructureProvider.getModule` 用 `module.contentScope.contains(file)` 选模块。
**默认作用域是构建期冻结的文件列表**——构建之后才放进来的文件会落到
`KaNotUnderContentRootModuleImpl`（一个**没有任何依赖**的模块），于是 `StringBuilder` / `Regex`
全变成 error type，所有值都是 unknown。

**解法**：`filesScope(project, supplier)` 看起来能用，但 `LazyFilesScope` **会在第一次查询时冻结**
supplier 的结果。`KotlinDirectoryScope` 逐次回答"这个文件在根目录下吗"，所以每个后放入的文件都能解析。

> **推论 —— 任何"按文件列表冻结"的作用域，对流式/增量场景都是错的。**
> 文件是陆续进来的，作用域必须是**逐次判定**的。

**验收：**
- ✅ Kotlin fixture 上跑出与 Java 等价的 finding 集（DFA 端到端测试）
- ✅ **Kotlin data class 不报"未使用的方法"**（`componentN`/`copy` 是编译器生成的）
- ✅ **Kotlin `val x: String` 不报 NPE**（除非显式 `String?`）
- ✅ **Kotlin `val x: String?` 在解引用时报 NPE**（**不能过度抑制**——这是漏报侧的红线）
- ✅ **可能为 null 的 receiver 上的方法调用会报 NPE**（证明 receiver 进了 `args[0]`）
- ❌ lambda 体内的 finding（未实现）

### Phase 2 — Scala 3 源码前端 —— **已落地**

> **状态（2026-11-02）**：模块 `plumb-frontend-scala` 已实现并通过 **23 个前端测试 + 4 个 DFA 端到端测试**。

| 任务 | 说明 | 状态 |
|---|---|---|
| 引入 `scala3-compiler` 依赖（锁 **3.10.0**） | 内部 API 跨版本不兼容，需要 `CrossVersion.full` 级别的版本锁 | ✅ |
| `StandardPlugin` + `PluginPhase` | `runsAfter = Set("typer")` | ✅ |
| **`tpd.Tree` → TAC 转换器** | 按 §3.5 的映射表 | ✅ |
| `match` 降解 | BRANCH 链（**在 pickler 前**，Maranget 决策树未展开） | ✅ |
| case class 合成方法 | `copy` / `apply` / `unapply` / `copy$default$N` → 标记合成 | ✅ |
| **合成检测方式** | **`Flags.Synthetic` 精确标志**（不是名字启发式） | ✅ |
| Scala 特有 sink | `scala.sys.process.ProcessBuilder`、`scala.util.matching.Regex` | ✅ |
| **lambda / closure** | `tpd.Closure` → 独立 `TacFunction` | ❌ 未实现 |
| **模式匹配收窄** | `case s: String` 的类型收窄无 TAC 表示 | ❌ 降级为 `SOURCE_LOWERED` |
| **构造器的字段写入** | dotc 在 typer **之后**才合成 `this.x = x` | ❌ 构造器降级为 `SOURCE_LOWERED` |
| Scala → Java 符号解析 | 读 classpath `.class` → SootUp | ✅ |
| Java/Kotlin → Scala | 共享符号表（§4.7 的两阶段设计） | ⏳ Phase 3 |

#### Scala 插件相位的真实形状（实测）

`PluginPhase` **单独用没有变换钩子**——它的全部擦除后方法只有 `runsBefore()`。
变换钩子在 `MegaPhase.MiniPhase` / `MegaPhase` 上：

```java
// On the Scala side: class MyPhase extends MiniPhase with PluginPhase
// On the Java side: it must extend MiniPhase AND implement PluginPhase
final class ScalaTacPhase extends dotty.tools.dotc.transform.MegaPhase.MiniPhase
    implements PluginPhase {
  @Override public Trees.Tree<Type> transformUnit(Trees.Tree<Type> tree, Context ctx) { ... }
}
```

**为什么用 `transformUnit` 而不是 `transformBlock`**：`transformBlock` 只在 `Block` 节点上触发，
而一个编译单元是 `PackageDef` 不是 `Block`——`transformBlock` 对编译单元**永远不会触发**。

**`superPhase` 要手动设置**：一个不属于任何 `MegaPhase` 的 `MiniPhase` 的 `superPhase` 是 null，
`relaxedTyping` 机制会 NPE。所以这个相位自己创建一个只含自己的 `MegaPhase`。

**安装相位的方式**：仿照 dotc 自己安装 `-Xplugin` 相位的方式——继承 `Contexts.ContextBase`，
覆写 `addPluginPhases(List<List<Phase>>, Context)`，然后调用
`Plugins$.schedule(plan, plugin.initialize(List.empty(), ctx))`。
同时要设置 `_plugins` 与 `_roughPluginsList`，以跳过 `-Xplugin:` 的 jar 加载路径。

**Scala `Flags` 是 64 位，kind 字段占第 0–1 位**，所以 `flags & Flags.Synthetic()` **永远非零**。
正确的判断是 `((flags >>> 2) & (flag >>> 2)) != 0`。

> **推论 —— 位掩码测试前先确认掩码的位宽。**
> Scala 把 kind 编码在低位，直接 `&` 会把 kind 的位也算进去，
> 于是**任何**标志的测试都返回真。症状是"所有符号都看起来是合成的"。

**验收：**
- ✅ Scala fixture 上跑出与 Java 等价的 finding 集（DFA 端到端测试）
- ✅ **Scala `match` 的污点流在所有分支上都被追踪**（降解正确性）
- ✅ **Scala case class 不报"未使用的方法"**
- ✅ **Scala `String` 解引用**仍然**报** NPE——这是关键的**反向对照**：
      证明前端没有偷偷借用 Kotlin 的类型系统（Scala 没有非空类型系统）
- ⏳ **Scala → Java 的跨语言 taint 链不断**（Phase 3）

### Phase 3 — Kotlin 编译器 IR 交叉验证通道 + 协程增强

| 任务 | 说明 |
|---|---|
| 引入 `kotlin-compiler` 依赖（IR 后端） | 重量级，需要版本锁定策略 |
| `IrGenerationExtension` | 遍历 `IrModuleFragment` |
| `IrVisitor` → TAC 转换器 | 按 §3.5 的映射表（Kotlin IR） |
| **交叉验证 harness** | 两条路径同 fixture，finding 集 diff（§6.4） |
| `when` / `for` / `do-while` 降解 | BRANCH 链 |
| **协程状态机建模** | `IrSuspendFunction` + 状态机字段（§4.6） |

**验收：**
- **交叉验证 diff 为空**（或差异全部可解释）
- 协程 fixture 上 finding 数 ≥ Phase 1

### Phase 4 — 框架绑定与规则补齐

| 任务 | 对标 Coverity |
|---|---|
| `missing_tls_*` 系列 | Coverity 已为 Java/Kotlin/Scala 三语言统一实现 |
| `regex_injection` (CWE-1333) | 唯一需要新写的通用规则 |
| `unrestricted_access_to_file` (CWE-732) | 文件权限 |
| Play / http4s / Dropwizard 绑定 | Scala 生态 |
| Kotlin/Android: `implicit_intent` (CWE-926) | Android 专项 |

---

## 6. 验收标准（比 Phase 更重要）

### 6.1 等价性：Kotlin/Scala 与 Java 的 finding 集必须等价

**这是最核心的验收标准。** 同一个逻辑用三种语言写，finding 必须一致。

```
fixtures/equivalence/
├── sqli/           { Sql.java, Sql.kt, Sql.scala }   -> the expected finding counts are equal
├── cmdinj/         { Cmd.java, Cmd.kt, Cmd.scala }
├── path/           { Path.java, Path.kt, Path.scala }
├── xxe/            { Xxe.java, Xxe.kt, Xxe.scala }
├── nullness/       { Null.java, Null.kt, Null.scala }
└── lambda/         { Lam.java, Lam.kt, Lam.scala }
```

**判定**：三个文件的 finding 集（CWE + 位置 + 污点源）必须逐条对应。差异必须有明确解释。

### 6.2 误报红线（漏报侧的镜像）

| 用例 | 必须 | 禁止 |
|---|---|---|
| Kotlin `val x: String` 后 `x.length` | 不报 NPE | |
| Kotlin `val x: String?` 后 `x.length` | **报 NPE** | 不报 |
| Kotlin `val x: String?` + `if (x != null)` 后 `x.length` | 不报 NPE（smart cast） | |
| Kotlin data class 的 `componentN`/`copy` | 不报"未使用" | |
| Scala case class 的 `copy`/`apply` | 不报"未使用" | |
| Java 引用类型可能为 null | 报 NPE | 不报（**`nullable` 默认值回归测试**） |
| Kotlin `String?` 传给 Java `String` 参数 | 视平台类型规则定 | |

### 6.3 漏报红线

| 用例 | 必须 |
|---|---|
| lambda 体内的 taint finding | 必须报 |
| `when`/`match` 所有分支上的 taint | 必须报 |
| 跨 suspend 函数的 taint | 必须报（Phase 3 后完整） |
| 跨文件（Kotlin ↔ Java ↔ Scala）taint | 必须报 |
| 协程状态机字段的 taint | 必须报（Phase 3） |

### 6.4 双路径交叉验证

**Kotlin 有两条源码路径（Analysis API / Compiler IR）。它们必须互相印证。**

```
fixtures/crossvalidate/
├── lambda/         <- validates IrFunctionExpression on the IR path
├── when/           <- validates the degradation of when
├── coroutine/      <- validates the expansion of suspend
├── generic/        <- validates the taint propagation under type erasure
└── smartcast/      <- validates the smart cast
```

**判定**：
- 两条路径的 finding 集**必须一致**（允许行号差异）。
- 不一致的地方**要么是 bug，要么是已知差异**——必须逐条记录在案。
- **这个 harness 是最有价值的 bug 探测器**：Analysis API 有自己的精度损失，IR 路径的 lowering 也有差异，两者对不上就说明至少有一方错了。

---

## 7. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| **Kotlin Analysis API 是 `@KaExperimentalApi`** | 高（官方明说"subject to incompatible changes"） | **锁版本 + 隔离到独立模块 + 编译器 IR 路径作为交叉验证**（§6.4）。Kotlin 大版本升级时只需更新一个模块 |
| **Kotlin 编译器依赖体积**（~50MB+） | 中 | Analysis API Standalone 依赖比完整编译器小得多；用 Maven `provided` 作用域 + 独立前端模块隔离 |
| **Scala 编译器内部 API 不兼容** | 高 | `CrossVersion.full` 级别的版本锁；Scala 3 minor 版本升级即需重新发布 |
| **Kotlin 空安全误报** | 高（会淹没报告） | **类型系统直接判定**（`KaType.isMarkedNullable`）+ `TacType.nullable` + `dfa-nullness` 事实域（三层，见 §4.4） |
| **Kotlin 空安全漏报**（过度抑制） | 高 | §6.2 的红线用例：`String?` 必须报 NPE。**默认 `nullable=true`** 是关键防线 |
| **`TaintRule` record 破坏性变更** | 中 | 加兼容构造器；`TaintRule` 已有 6/7 参重载的先例 |
| **`TacType.nullable` 默认值搞反** | 高 | **专门回归测试锁死默认值**；Phase 0 验收项 |
| **跨语言符号解析复杂度** | 中 | 两阶段设计（Pass 1 符号收集 / Pass 2 翻译）；`SymbolResolver` 抽象 |
| **行号错位**（协程/inline） | 中 | `IrPrecision` 标记；不可信时降级到方法级报告 |
| **内存压力** | 中 | 已知：CWE-191 单进程 4GB RSS。**一个语言一个进程，串行跑** |
| **Scala 2 用户** | 中 | 明确不支持；官方路径是迁移到 Scala 3。文档必须写清楚 |
| **动态语言期望落差** | 低（不做） | 不宣称支持；§3.6 已给出依据 |
| **Java 前端 language level 链路断裂** | **高（已存在的缺陷）** | `JavaModule.languageLevel` 从未被映射到 `ParserConfiguration.LanguageLevel` → 永远 `JAVA_17` → **Java 21+ 语法解析失败**（已实测）。见 §0.5.3 步骤 J1 |
| **Java 14+ 语法零翻译覆盖** | **高（已存在的缺陷）** | `SwitchExpr` / `RecordDeclaration` / `PatternExpr` 翻译器均未实现 → 即使解析成功也翻译不出正确 TAC → **漏报**。见 §0.5.3 步骤 J2 |
| **Java 25 是当前 LTS 但我们只到 17** | 中 | J1+J2 完成后切到 `JAVA_21`（JavaParser 3.26.4 的上限）；`BLEEDING_EDGE` 留作实验开关，不默认 |

---

## 8. 与现有工作的衔接

用户已有痛点（来自项目历史）：

1. **"fscanf 写指针 SSA 看不到"** → **源码路径直接解决**。IR 层没有 out-param 这个概念（Java 的 `fscanf(ptr, ...)` 在 TAC 里就是普通参数）。这是源码主线的直接收益。
2. **"TAC 简化 if hMutex"** → 与语言无关。
3. **"Cwe252/253 unchecked return 漏检"** → 与语言无关，但 Kotlin 的 `Result<T>` 和 Scala 的 `Either` 是**语言原生的错误处理机制**，这值得作为独立 checker 目标（CWE-252 的语言特化版本）。
4. **"do-while + 多 syscall IR 适配"** → Kotlin 的 `do-while` 在 IR 里是 `IrDoWhileLoop`，降解成 BRANCH+JUMP 即可；**源码路径的天然收益**。
5. **"R62 for-loop induction 显式"** → Kotlin `for` 降解时，loop variable 的 induction 更新是显式的，天然比 Java 的 AST 翻译更容易处理。
6. **"Cwe191 整数下溢（Z3 约束求解）"** → 源码路径的类型信息更完整（Kotlin 有无符号类型 `ULong`/`UInt`），Z3 约束求解的精度更高。

**新增建议**：Kotlin `Result<T>` / Scala `Either` 的错误处理检查（CWE-252 特化）是一个**只有多语言支持才能做**的差异化能力，值得单列。

---

## 9. 待决问题

> **状态快照（2026-11-02）**：下列问题的 **1、2、3、4、6 已拍板并落地**，8 已由实现过程回答。

1. **Kotlin 前端走 Analysis API 还是 Compiler IR？** —— **已定：Analysis API Standalone 为主 + Compiler IR 为交叉验证通道**（§3.4）。
   - 理由：Analysis API 直接给出可空性（`KaType.getNullability()` → `KaTypeNullability.NULLABLE` / `NON_NULLABLE` / `UNKNOWN`）和合成标记（`KaSymbol.getOrigin()` → `KaSymbolOrigin.SOURCE_MEMBER_GENERATED`），**零启发式**。
   - 风险已接受：API 是实验性的，缓解手段是**锁版本 + 独立模块 + 双路径交叉验证**。

2. **Scala 3 的目标版本号？** —— **已定：3.10.0**，锁死小版本。
   - 备选 3.9.0 LTS——如果更看重稳定性而非新特性。
   - 注意：3.8 起要求 **JDK 17+**（本机 JDK 25 满足）；3.8.0/3.8.1 有回归，**不要用**。
   - ✅ **依赖已确认可解析**：`scala3-compiler_3:3.10.0` 已从 Central 拉取到本地仓库。

3. **Kotlin 目标版本号？** —— **已定为 `2.5.0-Beta2`**（Analysis API 的 Maven 版本），与主仓 `kotlin.version=2.2.0` 解耦（§0.5.4）。
   - ⚠️ **注意**：Maven Central 上 `kotlin-analysis-api` **当前唯一版本就是 `2.5.0-Beta2`**——是 Beta，不是稳定版。原先设想的 2.4.21 **不存在于该 artifact**。
   - 对比：Coverity 2024.9.x 只支持到 Kotlin 2.0.0——我们比它新，不必对齐。

4. **前端模块的依赖隔离方式？** —— **已定：独立 Maven 模块**。
   - 实际落地：`plumb-frontend-kotlin` / `plumb-frontend-scala`，与 `plumb-tac` 并列，**不进入** `plumb-tac` 的传递依赖。
   - **为什么要隔离（不只是整洁）**：Analysis API 是 `@KaExperimentalApi`（官方说 "subject to incompatible changes"），dotc 的 `PluginPhase` 是编译器内部 API。**任何一个上游 breaking change 都不应该能弄坏 Java 分析路径**——隔离模块就是那个边界。
   - ✅ 依赖已确认可解析（见 §0.5.4 的 artifact 结构表与镜像注意事项）。

5. **跨语言符号表怎么存？**
   - 两阶段设计需要一个共享的符号索引。
   - **选项 A**：内存 Map（单次分析会话内）—— **已实现**：`DefaultCompileContext` 内含并发内存符号表（`SymbolTable`）。
   - **选项 B**：落盘（复用现有的 `IrSerializer` 磁盘缓存机制）—— Phase 3 再评估。

6. **`TacType.nullable` 的默认值确认？** —— **已确认为 `true`**，并由回归测试锁死。
   - `MultiLanguageIrContractTest` 断言：所有单例常量、`ofClass`、`arrayOf` 产出的类型 `isNullable()` 均为 `true`。
   - ✅ `equals` 调用点审计已完成：`FieldOpClassifier.classNameOfType` 已改用 `displayName()`。
   - ⚠️ **但实现期发现了一个设计文档没预料到的问题**：原设计假定 `TacVar` 的 `equals` 会包含类型。**实际不能这样**——见 §3.2「落地后的两个修正」。

7. **Scala 的 `match` 降解到什么程度？** —— **完整降解成 BRANCH 链**（保留所有分支的污点追踪）。这本来就是 IR 契约的要求。
   - 因为结构信息丢失，受影响的函数标记 `IrPrecision.SOURCE_LOWERED`。

8. **`TacType` 的可空性维度 vs `TacVar` 的可空性维度，哪个更合适？** —— **已由实现回答：走 `TacType`，但 `TacVar` 的身份必须与类型无关**。
   - 原设计选了 `TacType`（§3.2 扩展 5），理由是 `TacVar` 是 record，加字段会改 canonical constructor + `equals`/`hashCode`，破坏性更大。
   - ✅ 落地时发现：`TacVar` 已经是 **class 而非 record**，且它的身份语义是"存储位置"。
   - **最终规则**：可空性存在 `TacType` 里；`TacVar.equals` 只看 `name + isTemp`。
   - **为什么必须这样**：DFA 的每个 per-variable map 都以 `TacVar` 为 key。类型参与相等性 → 细化后的变量变成不同的 key → **污点静默丢失**（无异常，只是少一个 finding）。
   - **如果后续发现"同一变量在不同路径上可空性不同"（smart cast）导致表达困难**，再考虑下沉到 `TacVar`——但那要同时解决身份分裂问题。

9. **Java language level 的缺省值该定成什么？**（§0.5.3 J1）
   - 选项 A：**`JAVA_21`**（JavaParser 3.26.4 的正式上限）。推荐。
   - 选项 B：`BLEEDING_EDGE`——能过更多语法，但**不适合作为默认值**（bleeding edge 可能引入回归，且未来版本会变）。
   - **建议**：默认 `JAVA_21`，提供 CLI/配置项覆盖，并允许显式选 `BLEEDING_EDGE` 作为实验开关。

10. **Java 14+ 语法翻译的优先级？**（§0.5.3 J2）
    - `SwitchExpr` / `RecordDeclaration` / `PatternExpr` 三项。
    - **建议**：`RecordDeclaration` 优先（它同时验证"合成方法识别"机制，Kotlin/Scala 会复用），其次 `SwitchExpr`，最后 `PatternExpr`。
    - **待确认**：是否要在 Phase 0 就做完，还是把 `RecordDeclaration` 提到 Phase 0、其余放 Phase 1 之前。

---

## 10. 落地状态总表（2026-11-02）

### 10.1 已完成并有回归测试锁定

| 能力 | 位置 | 测试 |
|---|---|---|
| `SourceLanguage` / `IrPrecision` 两个枚举 | `plumb-tac/.../ir/` | `MultiLanguageIrContractTest` |
| `TacFunction` 的语言 / 精度 / 合成标记 | `plumb-tac/.../ir/TacFunction.java` | 同上 |
| `TacType.nullable` 维度 + `displayName()` | `plumb-tac/.../ir/TacType.java` | 同上 |
| `TacOperand.TacVar` 身份规则（name + isTemp） | `plumb-tac/.../ir/TacOperand.java` | 同上 + `KotlinTacVarIdentityTest` |
| `NULL_CHECK` opcode | `plumb-tac/.../ir/TacInstruction.java` | 同上 |
| `TacFrontend` / `CompileContext` / `SymbolTable` SPI | `plumb-tac/.../frontend/` | Java 前端是参考实现 |
| Java 前端接入 SPI | `plumb-tac/.../frontend/java/JavaTacFrontend.java` | — |
| Java language level 链路（8–25 → 具名级别） | `plumb-parser/.../JavaLanguageLevel.java` | `JavaLanguageLevelTest`（9） |
| Java 14+ 语法翻译（switch expr / pattern / record） | `plumb-tac/.../translate/JavaModernFeatures.java` | `JavaModernFeaturesTest`（14） |
| 规则语言绑定（`SinkFamily.languages` / `TaintRule.languages`） | `plumb-dfa/.../config/` | `SinkFamilyLanguageTest`（9） |
| DFA 语言门控（分析循环 + summary 循环双路径） | `plumb-dfa/.../engine/DfaEngine.java` | `DfaLanguageGateTest`（8） |
| Kotlin 空安全（类型可空性 + `NULL_CHECK`） | `plumb-dfa/.../taint/NullabilityFacts.java` | `KotlinDfaIntegrationTest`（4） |
| CWE-1333 正则注入 + CWE-732 不受限文件访问 | `plumb-dfa/.../checkers/taint/` | `GapRuleCheckersTest`（9） |
| **Kotlin 源码前端** | `plumb-frontend/.../kotlin/` | **34** + owner 8 + 端到端 4 |
| **Scala 3 源码前端** | `plumb-frontend/.../scala/` | **23** + owner 5 + 端到端 4 |
| **前端接入扫描路径（`.kt` / `.scala` 真的被扫）** | `plumb-main/.../main/FrontendRegistry.java` | `MultiLanguageScanE2ETest`（5） |
| **callee signature 的 owner 统一为 `owner.method/arity`** | 两个 translator | `KotlinCalleeOwnerTest`（8）+ `ScalaCalleeOwnerTest`（5） |
| **模块分层（core / engine / app 聚合 POM）** | `plumb-core/` `plumb-engine/` `plumb-app/` | 见 §12.3 |
| Java 语法覆盖矩阵（清单闭合 + 实际降级） | `plumb-tac/.../translate/AstNodeInventoryTest.java` | 5 |
| 构造 → IR 形状契约 | `plumb-tac/.../translate/JavaConstructIrContractTest.java` | 19 |
| 注解值通道（`TacFunction.annotationValues()`） | `plumb-tac/.../ir/TacFunction.java` | 同上 |
| Problem 通道两种 kind（`UNHANDLED_NODE` / `PARSE_PROBLEM`） | `plumb-tac/.../translate/TacBuilder.java` | `UnhandledNodeReportingTest` + 同上 |
| `super` / `TypeExpr` / 表达式位置声明的静默降级修复 | `plumb-tac/.../translate/ExpressionTranslator.java` | 同上 |
| `VoidType` / `UnionType` / `IntersectionType` / `TypeParameter` 类型映射 | `plumb-tac/.../translate/TypeMapper.java` | 同上 |
| 紧凑构造器（`CompactConstructorDeclaration`）降级 | `plumb-tac/.../translate/TacTranslator.java` | 同上 |
| **前后端分离消费者入口** | `plumb-main/.../main/IrAnalysisRunner.java` | **6** |

### 10.2 尚未实现（明确列出，不含糊）

| 能力 | 为什么没做 | 什么时候做 |
|---|---|---|
| Kotlin lambda 体 → 独立 `TacFunction` | Analysis API 的 PSI 遍历未覆盖 | Phase 3 |
| Scala closure → 独立 `TacFunction` | 同上 | Phase 3 |
| Kotlin `when` 的 `is` / `in` 模式 | TAC 没有类型测试 opcode；**不加** | Phase 3 |
| Scala 模式匹配的类型收窄 | 同上 | Phase 3 |
| Scala 构造器的字段写入 | dotc 在 typer **之后**才合成 `this.x = x`——在 pickler 前拿不到 | 短期无解 |
| Kotlin 协程状态机建模 | 需要 IR 表达"跨挂起点的存活变量" | Phase 3 |
| param 级注解通道 | Analysis API 未取 | Phase 3 |
| 跨语言符号表（Java ↔ Kotlin ↔ Scala） | 内存符号表已就位，但前端之间还没接线 | Phase 3 |
| Kotlin Compiler IR 交叉验证通道 | 需要完整编译，慢 | Phase 3 |

> **已实现的前端在遇到无法降解的构造时，不会静默丢弃**——它报一个 problem，
> 并把该函数的精度降级为 `SOURCE_LOWERED`。这样 checker 知道不能信任该函数上的精度相关事实。

### 10.3 两个"反向对照"测试（重要）

多语言前端最危险的失败模式是**静默地借用别的语言的语义**。所以有两个测试专门盯着这件事：

| 测试 | 断言 | 防的是 |
|---|---|---|
| `KotlinDfaIntegrationTest` | Kotlin `String?` 解引用**仍然报** NPE | 过度抑制（漏报） |
| `ScalaDfaIntegrationTest` | Scala `String` 解引用**仍然报** NPE | 前端偷偷用了 Kotlin 的非空类型系统 |

**两个都通过 = 前端既没有过度抑制，也没有借用不该有的语义。**

### 10.4 全量回归基线

```
mvn test   →   BUILD SUCCESS
```

| 模块 | Tests | Failures | Errors |
|---|---|---|---|
| plumb-parser | 19 | 0 | 0 |
| plumb-tac | 218 | 0 | 0 |
| plumb-frontend | 70 | 0 | 0 |
| plumb-rules | 27 | 0 | 0 |
| plumb-reporter | 31 | 0 | 0 |
| plumb-style | 1266 | 0 | 0 |
| plumb-dfa | 475 | 0 | 0 |
| plumb-build-core | 77 | 0 | 0 |
| plumb-build-maven | 39 | 0 | 0 |
| plumb-build-capture | 114 | 0 | 0 |
| plumb-ai | 85 | 0 | 0 |
| plumb-dsl-kotlin | 69 | 0 | 0 |
| plumb-main | 129 | 0 | 0 |

**合计 2534 tests, 0 failures, 0 errors**（18 个模块全部 SUCCESS，全量构建 **5 分 17 秒**）。

比前端能力攻坚前的 2367 增加 **167 个测试**（+7.1%），全部是新增的：
语法覆盖矩阵、构造契约、消费者端到端、源码读取边界。

> **基线在 TAC 语义审计之后重新记录**（§13）。审计正好新增 32 个测试、
> 修掉 14 个数据流缺陷，用例总数从 2497 涨到 **2529**。跨语言测量（§14）又加了 5 个
> （`CrossLanguageInteropTest`），合计 **2534**。这个数字是逐行对过账的：
> `plumb-tac` +6（`StaticCallScopeTest`）、`plumb-main` +26（`TacTaintSurvivalTest`）
> +5（`CrossLanguageInteropTest`）。其他模块一个没动——如果有回归，表现应该是**掉**而不是涨。
>
> **数字口径上的一个说明**：`plumb-ai` 有 5 个测试（`LlmIntegrationTest`）是 **skipped**，
> 因为它们需要真实的 LLM 端点。skipped 计入"跑过"，但不算失败。

> **构建时长从 25:52 降到 5:17**（约 5 倍）。这**不是**优化出来的，
> 是**修掉了一个契约破坏**：消费者不再读源码（§12.6）。
> 之前那 19 分钟里，绝大部分是消费者把 `%TEMP%` 里的 4.8 万个
> `.java` 文件读了一遍。

> **基线记录纪律**：每次改动都要重新记录全量基线并写进文档。
> 只记"我测了我新写的测试"是不够的——**回归是全量的事**。
>
> **一个观察（已修正）**：这份基线曾记录"`plumb-main` 的消费者测试耗时 705 秒，
> 原因是 DFA 引擎本身慢，每个用例都要建一次全局 call graph"。
>
> **这个归因是错的。** 真正的原因是**消费者在读源码**——引擎顺手建了一个
> 跨文件常量解析器，它把目录树里的 `.java` 文件读了一遍；根目录是"往上走两级"，
> 于是 JUnit 的临时文件把整个 `%TEMP%` 拖了进来（本机 48,631 个 `.java`）。
>
> 修掉之后（§12.6）：**795 秒 → 2.3 秒**。DFA 分析本身一直只要 58 毫秒。
>
> > **推论 —— 归因错了，代价会被记成"引擎慢"然后被接受。**
> > 一个 13 分钟的测试套件很容易被解释成"这个引擎就是重"。
> > 真正的成本是**契约被破坏**：一个宣称"无源码"的消费者在读源码。
> > 这不只是慢，它让同一个 IR 在不同机器上可能给出不同答案。
> >
> > **所以性能异常要当作契约异常来查。** 先问"它在做什么本不该做的事"，
> > 再问"它能不能更快"。

### 10.5 一次"看不见的污染"的记录

收尾时 `git status` 报了 206 个改动文件，但 `git diff --stat` 只有 28 个。
差额是 **176 个"只在末尾多了一个换行"** 的文件。

**成因**：某些工具（格式化脚本 / 编辑器）在写文件时统一补了末尾换行，而仓库里
大部分文件原本没有。这与本次工作**完全无关**，但会污染 diff、淹没真正的改动。

**处理**：逐文件比对（去掉末尾换行后是否逐字节相同），只把**真正的改动**留下，
其余 `git checkout` 还原。

> **推论 —— "改动文件数"和"改动行数"是两个量，必须都看。**
> 只看 `git status` 会被噪声淹没；只看 `git diff --stat` 又会漏掉 stat 缓存问题。
> **逐文件做"归一化后逐字节比对"才是可靠做法。**

另外还发现一个 `SinkFamilyRegistry.java` 里有**裸 NUL 字节**（本该是 `"\0"` 转义）——
Java 允许字符串字面量里出现裸 NUL，所以它**能编译**，但 git 会因此把文件当**二进制**，
diff 完全失效。这类问题**编译器和测试都发现不了**，只有 git 会说。

---

## 11. 前端能力攻坚：让"全部语法结构 → IR"可度量（2026-11-02）

前面的章节确立了**架构**（源码 AST → IR 是主线，IR 是契约不是缓存）。
这一节记录的是**能力**：Java 前端到底能把多少语法变成 IR，怎么证明，以及怎么防止退化。

### 11.1 优先级重排

用户明确要求把**前端能力**放在最优先：

> "一定要将全部语法结构转换成 IR 支持相关能力，不然后端的分析一定是一个非常不准确的分析"

这个判断是对的，而且有具体的机理：**前端漏掉一个语法结构，产生的不是"漏报"，而是"静默的数据流污染"**。

翻译器的 fallback 是返回 `null` 常量。于是：

```
a tainted value -> an unsupported construct -> a null constant -> every downstream fact about it vanishes -> the finding disappears silently
```

这条链上**没有任何一环会抛异常**。没有异常、没有默认日志、唯一的症状是"finding 不见了"——
而"finding 不见了"和"代码是安全的"在下游看起来**完全一样**。

> **推论 —— 前端缺口的严重性是后端规则错误的平方。**
> 一条规则的实现写错，影响是那一条规则。
> 前端漏掉一个语法结构，影响的是**所有**依赖该构造的规则，而且无法被发现。

### 11.2 三个"静默降级"的实测缺口

这里修的都是**静默**的——它们产生指令，所以"有没有产生指令"的检查看不出来。

| 缺口 | 症状 | 根因 | 修法 |
|---|---|---|---|
| `SuperExpr` | `super.foo()` 的 receiver 是 `null` → **每一次 `super.method()` 都误报空指针** | 翻译器返回 `null` 常量 | 降级到 `this` local——`super` 和 `this` 是同一个对象，只是派发目标不同 |
| `TypeExpr` | 类型当值使用时变成 `null` | 翻译器返回 `null` 常量 | 降级为 `java.lang.Class` 引用（非空） |
| `VariableDeclarationExpr`（表达式位置） | **声明的局部变量根本没被声明**；初值的污点丢失 | 翻译器返回 `null` 且**不发任何指令** | 路由到 `StatementTranslator.translateVarDecl`——和语句路径共用同一份实现 |

`SuperExpr` 那一条最值得记：**它不是漏报，是误报**。每一个 `super.toString()` 都会触发一次空指针告警。

### 11.3 IR 的两个新通道

#### 11.3.1 注解**值**通道（`TacFunction.annotationValues()`）

之前 IR 只有注解的**名字**。但：

```java
@Value("${app.password}")   // the name is Value
@Value("${app.username}")   // the name is still Value -- but that is a completely different fact
```

名字通道只能回答"是不是标了 Value"，值通道才能回答"绑的是哪个配置项"。
后者才是消费方真正需要的问题，因为**配置绑定值是一个来自程序外部的值**。

新增 `Map<String, List<String>>`，键是注解简单名，值是它携带的值：

| 注解形态 | 贡献什么 |
|---|---|
| `@Marker` | 无值（键存在，值为空——"标了"本身是信息） |
| `@Single("v")` | `"v"` |
| `@Normal(a = "v", b = 1)` | `"v"`、`"1"` |
| `@Normal(a = {"x","y"})` | `"x"`、`"y"`（逐元素） |
| `@Outer(@Inner("z"))` | `"z"`（挂在外层注解名下，不丢） |

**方向性规则**：和注解名字一样，这是**元数据**。可以用来抑制误报、识别角色，
**绝不能用来证明运行时安全**——它来自源码文本，不是运行时观测。

#### 11.3.2 Problem 通道的两种 kind

已有的 problem 通道让缺口**可见**，但可见不等于缺口小。现在区分两种事实：

| kind | 含义 | 消费者该怎么反应 |
|---|---|---|
| `UNHANDLED_NODE` | **前端不支持**这个构造 | 该补功能 |
| `PARSE_PROBLEM` | **输入是坏的**（源码解析失败、解析器恢复过） | 该修代码或换解析器 |
| `TRANSLATION_ERROR` | 降级过程中**抛了异常** | 该查前端 bug |

把前两者混为一谈，消费者就没法区分"这个文件用了我不支持的特性"和"这个文件根本编译不过"。

新增的来源：
- `JavaParser` 的 `ParseProblem` → 记在 **program** 上（解析错误可能出现在两个声明之间，不属于某个函数）
- `UnparsableStmt` → 记在**函数**上，作为 `PARSE_PROBLEM`

#### 11.3.3 SPI 升级：从字符串问题到结构化问题

前端 SPI 原本的 `ParseResult.problems()` 是 `List<String>`。字符串只能说明"出了点问题"，
说不出**哪个构造、哪个函数、哪一行**。

对一个**无源码的消费者**来说这是致命的：它拿到 IR，只知道"某处有问题"，
既没法区分"前端缺功能"和"源码编译不过"，也没法指向要看的行。

新增 `TacFrontend.StructuredParseResult` 与 `parseStructured(...)`：

| 层 | 形状 |
|---|---|
| 旧 SPI（保留） | `ParseResult(functions, List<String> problems, precision)` |
| 新 SPI | `StructuredParseResult(functions, List<FunctionProblem> problems, precision)` |

`parseStructured` 有默认实现（把字符串包成结构化问题），
所以**已有前端不必改就能编译**；但只有覆盖它的前端才能给出构造名和位置。

三个前端都已覆盖：

| 前端 | 结构化问题的来源 |
|---|---|
| Java | `TacProgram.programProblems()`（翻译器已经记录的结构化问题） |
| Kotlin | 翻译器的 `report(...)`：构造名 + 行 + 列 |
| Scala | 编译器的诊断（从诊断文本里解析行号）+ 翻译器的失败 |

> **推论 —— SPI 的形状决定了 IR 的形状。**
> 如果 SPI 只能传字符串，IR 就只能传字符串，
> 于是"消费者能看见多少细节"这件事在 SPI 定义的那一刻就已经被决定了。
> **要改，就得改 SPI，不是改消费者。**

Scala 的诊断行号是从诊断**文本**里解析的（`path:line: message`），
而不是从 `SourcePosition` 对象读的——因为后者需要 compiler `Context`，
而编译结束后活下来的只有 reporter。
**能用公开文本拿到的信息，就不要去碰编译器内部 API**：内部 API 会随版本变，文本不会。

### 11.4 语法覆盖矩阵：可度量，而不是靠自觉

`AstNodeInventoryTest` 做两件事，**两个方向都要**：

**方向一 —— 清单闭合。**
从**编译后的 class 文件常量池**里读出前端引用了哪些 AST 节点类，
和 JavaParser 实际定义的**具体**节点类集合比对。

- 不是手写清单：手写清单会腐烂（加了构造忘了加清单，测试照样通过，却对新构造什么也没说）
- 不是扫源码文本：会扫到注释和 javadoc
- 常量池是**超集**（包含只是 import 的类），对"有没有提到"这个问题，超集是对的
- 抽象基类（`LiteralExpr`、`Expression`…）排除：它们**永远不会**出现在具体树里，
  前端匹配的是具体子类
- 名字节点（`Name`、`SimpleName`）排除：它们不是表达式，不出现在表达式位置

**方向二 —— 实际降级。**
一个用**每一种**受支持构造写成的源码，必须**零 problem** 地降级。
清单闭合只说明"提到了"，实际降级才说明"真的处理了"。

> **推论 —— 只做方向一，会得到"提到了但没处理"的引擎；
> 只做方向二，会得到"处理了但清单过期"的引擎。两者必须成对。**

### 11.5 清单测试立刻抓到的真实缺口

这个测试不是走过场——它**当场**抓到了一个真实的类型映射 bug：

```
Expecting empty but was: ["com/github/javaparser/ast/type/VoidType"]
```

**`void` 不是 `PrimitiveType`。** JavaParser 里 `VoidType extends Type`，
和 `PrimitiveType` **平级**，不是它的子类。所以 `TypeMapper.map()` 的
`instanceof PrimitiveType` 分支**接不住 `void`**，`void` 落到最后的
`return TacType.UNKNOWN`。

后果不是"类型名不好看"：`TacType.UNKNOWN` 意味着"一个类型未知的值"，
所以一个 `void` 方法看起来像**有返回值**。这是唯一一个前端**说不出名字**的语言类型。

修法是在 `PrimitiveType` 之后单独加 `VoidType` 分支。

> **推论 —— "语言里每个类型前端都能命名"是一个应该被测试的性质，
> 不是可以假设的性质。** 这个性质只差一个 `instanceof` 分支就会破。

### 11.6 前后端分离的消费者入口

用户的目标是明确的：

> "translate 能够将源码转换成 IR，然后 IR 可以本地保存，随后下一个阶段，
> 可以直接在没有源码的基础上，读取 IR 分析，扫描结果"

新增 `plumb-main/.../IrAnalysisRunner.java`：**IR 进，finding 出**。

| 方法 | 输入 |
|---|---|
| `analyzeMerged(Path)` | 合并 IR 文件（`merged.tac`） |
| `analyzeProgram(Path)` | 单文件 IR |
| `analyze(MergedProgram)` / `analyze(TacProgram)` | 内存中的 IR |

消费者**不需要**：
- 源码文件（可以已经不存在）
- classpath（分析只用 IR 自带的类型信息）
- Java 工具链（这里不发生任何编译）

消费者**需要**（全部由 IR 提供）：指令、源路径表、类继承、注解元数据、problem 通道。

结果对象 `IrAnalysisResult` 把 **finding** 和 **problem** 分开返回：

- finding 说"这段代码有 X 问题"
- problem 说"我没能完整翻译这段代码，所以我的 finding 是下界"

**两者混在一个列表里，接收方就无法区分"干净的程序"和"分析器读不懂的程序"。**

`IrAnalysisConsumerTest`（6 tests）的每个用例都在分析前**删掉源文件**——
这是唯一能证明分析没有偷偷回读源码的办法。断言"finding 一样"是不够的，
因为一个恰好能读到旁边源码的分析器也会通过那个断言。

### 11.7 新增测试清单

| 测试 | 位置 | 数量 | 守的是 |
|---|---|---|---|
| `AstNodeInventoryTest` | `plumb-tac/.../translate/` | 5 | 清单闭合 + 实际降级 + 引用真实性 |
| `JavaConstructIrContractTest` | `plumb-tac/.../translate/` | 19 | 构造 → IR 的**形状**契约（不只是"有指令"） |
| `IrAnalysisConsumerTest` | `plumb-main/.../main/` | 6 | 无源码、无 classpath 的端到端分析 |
| `JavaSyntaxCoverageTest`（已有，扩充） | `plumb-tac/.../translate/` | 60 | 每种语法结构都产生指令 |

`JavaConstructIrContractTest` 与 `JavaSyntaxCoverageTest` 的区别值得说清：

- `JavaSyntaxCoverageTest` 问"这个构造**产生指令了吗**"
- `JavaConstructIrContractTest` 问"这个构造产生的是**对的指令吗**"

必须分开，因为两种失败长得不一样：降级到 `null` 常量的构造**照样产生指令**，
照样摧毁数据流。只有断言指令的**形状**（种类、操作数、值）才能抓住它。

---

## 12. 把前端接进扫描路径（2026-11-02 续）

前面 §10 的能力表里写着"Kotlin 源码前端已实现"。这句话当时是真的，
也是**不完整的**：前端能跑、前端自己的测试全绿，但**没有任何真实扫描能读到
一个 Kotlin 文件**。这一节记录把它变成真的那一步，以及它暴露出来的三个问题。

### 12.1 三个"实现 ≠ 可达"的陷阱

一个前端通过了自己的单元测试，只能证明**前端是好的**，不能证明**前端被调用了**。

这两件事的失败长得一模一样：都是"零 finding"。

| 层 | 能证明什么 | 不能证明什么 |
|---|---|---|
| 前端单元测试 | 翻译逻辑正确 | 扫描路径会调用它 |
| DFA 集成测试 | 引擎能消费该 TAC | 扫描路径会产出该 TAC |
| **扫描端到端测试** | **整条链接通** | — |

所以新增了 `MultiLanguageScanE2ETest`（`plumb-main`），它**走真实的
`ScanRunner`**，用一个教科书式的 SQL 注入当探针：

```kotlin
import java.sql.Statement
class Vulnerable {
  fun query(stmt: Statement, userInput: String) {
    stmt.execute(userInput)     // it must report CWE-89
  }
}
```

这条测试一次覆盖六个环节：文件被**发现** → 按扩展名**选对前端** → 翻译成 TAC →
callee signature **带 owner**（否则 sink family 匹配不上）→ 污点**传播** →
checker **报出 CWE**。断任何一环都红。

> **推论 —— "零 finding"有两种完全不同的含义，必须用不同的测试区分。**
> "这个前端没被调用"和"这段代码是干净的"在下游看起来一模一样。
> 唯一能区分它们的办法，是走完整路径的测试。

### 12.2 接进扫描路径时暴露的三个真实缺陷

**缺陷一：Kotlin 的表达式体函数被降级成空函数。**

`KotlinTacFrontend` 传的是 `fn.getBodyBlockExpression()`，而 `fun f() = expr`
的函数体不是 `KtBlockExpression`，是 `KtExpression`——`getBodyBlockExpression()`
返回 **null**。于是每个表达式体函数都被降级为**空**：穿过它的数据流被切断，
经过它的污点**无声地丢失**。

Kotlin 里表达式体极其常见，这不是边角情况。修法是改用 `getBodyExpression()`，
它对块体和表达式体都返回函数体，由 translator 区分两者。

**缺陷二：Java 属性的 owner 读错了地方。**

`sb.length`（`sb: java.lang.StringBuilder`）在 Kotlin 里被 Analysis API 解析成
**INTERSECTION_OVERRIDE**（Kotlin 为 Java 成员合成的交叠覆盖），而不是
`JAVA_LIBRARY`。实测：

```
property -> origin=INTERSECTION_OVERRIDE, callableId=java/lang/StringBuilder.length
getter   -> origin=INTERSECTION_OVERRIDE, callableId=null
```

**声明类在 property 上，不在 getter 上。** 读 getter（最自然的写法）拿到
`null`，于是这个属性静默降级成 FIELDLOAD——一个不存在的字段，而且**永远匹配不到
任何 sink family**，因为 sink family 是锚在**方法名**上的。

更进一步，"这个成员是不是来自 Java"也不能问符号的 origin（origin 是
`INTERSECTION_OVERRIDE`），得问**它所属的类**是不是 Java 类。

**缺陷三：一个依赖版本冲突，让所有 Kotlin finding 静默归零。**

这是最隐蔽的一个，也是最值得记下来的一个。

```
NoClassDefFoundError: kotlin/jvm/internal/KotlinGenericDeclaration
```

Analysis API 2.5.0-Beta2 用到 `KotlinGenericDeclaration`，它**在 stdlib 2.3.x
才有**。而 `plumb-main` 同时依赖 DSL 脚本引擎（传递 stdlib **2.2.0**）和
Analysis API（传递 stdlib **2.3.21**）——Maven 的"最近者优先"让 **2.2.0 赢了**。

后果特别恶劣：前端**没有崩溃**。它把 session 启动失败当成一个 *problem* 上报，
然后扫描继续、产出 **0 个 finding**。一段有 SQL 注入的 Kotlin 代码看起来是干净的。

修法不是"升级脚本引擎"（那是另一件事，`kotlin-scripting-jsr223` 2.2.0 是本地
唯一可用版本），而是**把 stdlib 版本单独 pin 一次**，放在根 POM 的
`dependencyManagement` 里：

```xml
<kotlin.stdlib.version>2.3.21</kotlin.stdlib.version>
```

`dependencyManagement` 让这个版本**对每个模块生效，不管它从哪条路径进来**，
从而绕开"最近者优先"。

> **推论 —— 一个前端"报告了问题但没有崩溃"，比它崩溃更危险。**
> 崩溃会让测试红；报告问题会让扫描安静地跑完、产出零结果。
> 这正是"静默降级比漏报更危险"的又一个实例：**失败要响**。

### 12.3 模块分层：为什么是聚合 POM，不是子目录

11 个模块平铺在一个仓库根下，确实不好读。自然的做法是分层子目录：

```
plumb-core/    parser, rules, reporter
plumb-engine/  tac, frontend, dfa, style, dsl-kotlin, ai
plumb-app/     main, build
```

**但实测下来，这条路会静默破坏门禁**，所以没有走。

仓库里有 43 个门禁脚本用 `REPO / "plumb-<name>"` 硬编码模块路径。更要命的是
`scripts/check_test_coverage.py:188`：

```python
for module_dir in sorted(REPO.iterdir()):
    if not (module_dir / "pom.xml").is_file():
        continue
```

它**靠遍历仓库根目录来发现模块**。模块一旦移进子目录，这个门禁**一个模块都看不到**，
而且它**不会报错**——它会安静地变成一个空门禁。

> **推论 —— 门禁"停止检查"比门禁"报错"更危险。**
> 报错会让人去修；停止检查会让人相信检查还在跑。

所以分层用**聚合 POM**表达，目录保持平铺：

```xml
<!-- pom.xml -->
<modules>
  <module>plumb-core</module>    <!-- ../plumb-parser, ../plumb-rules, ../plumb-reporter -->
  <module>plumb-engine</module>  <!-- ../plumb-tac, ../plumb-frontend, ../plumb-dfa, ... -->
  <module>plumb-app</module>     <!-- ../plumb-main, ../plumb-build -->
</modules>
```

这跟仓库里既有的 `plumb-build` 聚合 POM 是同一个模式（先例已存在）。
**分层在 BUILD 里表达，源码树保持平铺可 grep**——读者问"谁依赖谁"，
从 POM 得到一个答案，而不是从目录嵌套得到第二个答案。

Maven 按依赖图排序 reactor，所以**层次边界在构建时已经被强制执行**，
和声明在哪一层无关。

### 12.4 风格检查器在 Kotlin/Scala 上不跑（明确的取舍）

`plumb-style` 的检查器是**按 JavaParser AST 构造**的。Kotlin/Scala 文件没有
JavaParser AST，所以风格检查在它们上面**不产生任何 finding**。

这是有意的取舍，理由是：让风格检查器理解三种 AST，等于要写第二套风格引擎，
而且**每一条没有 Kotlin/Scala 等价物的规则，它的覆盖率声明都是假的**。
数据流分析——承载安全语义的那部分——**照常跑**。

---


### 12.5 两个 Kotlin 编译器不能共处一个 classpath（最终方案）

这一节记录最难的一个问题，以及最终方案的边界在哪。

**问题**：仓库里跑着两套 Kotlin 栈——

| | 用途 | 版本 | IntelliJ 平台 |
|---|---|---|---|
| DSL 规则引擎 | `plumb-dsl-kotlin`，Kotlin 脚本引擎 | 2.2.0 | **shaded**：`org.jetbrains.kotlin.com.intellij.*` |
| Kotlin 源码前端 | `plumb-frontend`，Analysis API Standalone | 2.5.0-Beta2 | **unshaded**：`com.intellij.*` |

两者的 implementation jar **都内嵌一整套 Kotlin 编译器**（Analysis API 那份含
594 个 `org.jetbrains.kotlin.cli` 类、4771 个 `fir` 类、302 个
`org.jetbrains.kotlin.scripting` 类，以及整个 `kotlin/script/**` API）。

**关键事实：这不是版本偏差问题，换版本解决不了。** 编译器类在两边的
**类名相同**（`org.jetbrains.kotlin.cli.*`），但它们链接的 IntelliJ 平台
**shading 不同**——2.2.0 用 shaded，2.5.0-Beta2 用 unshaded。于是同一版本也一样冲突。

实测到的失败形态：

```
NoSuchMethodError: CompilationContextKt.createIsolatedCompilationContext$default(
    ..., org.jetbrains.kotlin.com.intellij.openapi.Disposable, ...)
```

同一个方法、同样的参数个数，**参数类型不同**——调用方的描述符对不上被调方的。

```
ClassCastException: org.jetbrains.kotlin.idea.KotlinFileType cannot be cast to
    org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType
```

同一个 shaded/unshaded 分裂，从强制转换的角度再看一遍。

**为什么它特别危险**：DSL 引擎把编译失败记成一条 **warning** 然后继续跑。
于是**每一条 DSL 规则都静默地不再匹配**，而每一条内建规则照常工作。
扫描看起来是健康的，用户的规则没了。

**走过的两条弯路**（都记录下来，因为它们看起来都很合理）：

1. **重打包 Analysis API 的 jar，删掉重复类。**
   想法很自然：删掉 `kotlin/script/**`、`org/jetbrains/kotlin/scripting/**`，
   让脚本引擎自己的 2.2.0 副本胜出。**但 shade 插件默认跳过 `provided` 作用域的
   依赖**，于是"净化后"的 jar 只有 **3571 字节**——一个空壳。Analysis API 因此
   丢掉了 `org.jetbrains.kotlin.cli.jvm.compiler.CompatKt`，session 根本建不起来。
   *教训*：`maven-shade-plugin` 的 `artifactSet` 过滤发生在**依赖解析之后**，
   而 `provided` 依赖根本不进 shade 的依赖集合。

2. **让脚本引擎独占 `org.jetbrains.kotlin.*` + `kotlin.*`。**
   结果是 **loader constraint violation**：
   `kotlin.coroutines.CoroutineContext` 被 app loader 和 script loader 各加载了一份。
   *教训*：**标准库必须是全 JVM 唯一**。它不是"谁用谁带"的东西。

**最终方案：边界画在 classloader 上，并且边界必须同时覆盖 CLASS 和 RESOURCE。**

`plumb-dsl-kotlin` 的 `ScriptEngineClassLoader`（子加载器，child-first）负责：

- **类**：独占 `org.jetbrains.kotlin.*`（含编译器、脚本编译器插件、IDE 类，
  以及 embeddable 编译器 shaded 进来的 `org.jetbrains.kotlin.com.intellij.*`）
  和 `kotlin.script.*`（脚本 API，即所有编译出来的规则脚本的基类所在）。
- **资源**：同样 child-first，服务 `META-INF/extensions/**` 和
  `org/jetbrains/kotlin/**`、`kotlin/script/**`。
- **共享**：`kotlin.*` 标准库（必须唯一）、`org.plumb.*`（DSL API）、
  `com.github.javaparser.*`（规则要检查的 AST）。

**为什么"资源"这一半同样重要**——这是最隐蔽的一环：

Kotlin 编译器的 IntelliJ 平台是这样找自己的扩展点的：

```kotlin
PathUtil.getResourcePathForClass(CompilerSystemProperties)
```

它把 `/org/jetbrains/kotlin/cli/common/CompilerSystemProperties.class` 当作
**资源**解析。而 `URLClassLoader.getResource` 是**父优先**的，跟 `loadClass` 一样。
于是这个资源从 **app classpath** 上解析，落在 **Analysis API 的 jar** 里——
那个 jar 恰好也有一份 `META-INF/extensions/compiler.xml`，而且**没有 `<id>`、
没有 `<name>`**。IntelliJ 的插件加载器于是抛：

```
RuntimeException: Neither id nor name are specified
```

**脚本引擎的 REPL 起不来**，所有 DSL 规则编译失败，报错信息里一个字都没提插件。

→ 所以 **`getResource` / `getResources` 必须覆盖成 child-first**，边界才算完整。
**类加载对了不等于资源加载对了**，这是同一类问题的两半。

**求值期的 context classloader 也必须切**：

编译出来的脚本类是在**第一次求值时**才被定义 classloader 的
（`KJvmCompiledScript.getOrCreateActualClassloader`），而它取的是
**当前线程的 context classloader**。只在编译期切，求值期没切，脚本类就挂到了
app loader 上，于是：

```
ClassCastException: Cannot cast org.jetbrains.kotlin.cli.common.repl.AggregatedReplStageState
to org.jetbrains.kotlin.cli.common.repl.AggregatedReplStageState
```

同名不同 loader。修法是求值时也切 context classloader。

**剩下的另一半：application classpath 的顺序。**

Analysis API 跑在 **app loader** 上，所以 app classpath 上必须是
**Analysis API 的编译器在前**。否则前端会绑到脚本引擎的 2.2.0 编译器上，
session 启动失败——而前端把失败当成 *problem* 上报，扫描继续、产出 0 个 finding。
一段有 SQL 注入的 Kotlin 代码看起来是干净的。

这个顺序是**构建事实**，不是运行时能修的，所以它被钉成了一个测试：
`plumb-main` 的 `KotlinStackClasspathOrderTest` 断言

- app classpath 上 `kotlin-analysis-api-implementation` **早于**
  `kotlin-compiler-embeddable`；
- **app loader 实际解析到的** `CompatKt` 来自 Analysis API 的 jar。

> **推论 —— classloader 隔离是一个"类 + 资源 + context classloader + classpath 顺序"
> 的四件套，只做其中一件，症状会漂移到另一个完全不相干的地方。**
>
> 每一件单独都能让测试看起来通过：
> 类加载对了，但资源没切 → 报"Neither id nor name"；
> 资源对了，但求值期 context 没切 → 报 AggregatedReplStageState 的 CCE；
> 隔离都对了，但 classpath 顺序错了 → 前端静默产出零 finding。
>
> **只有把两套栈放在同一个 JVM 里跑真实扫描，才能同时暴露它们。**
> 这也是为什么 `DslRulesE2ETest` 和 `MultiLanguageScanE2ETest` 必须**同时**通过：
> 前者证明 DSL 引擎活着，后者证明 Kotlin 前端活着，而它们共用一个 JVM。

**顺带删掉的一个模块**：`plumb-kotlin-analysis-api`。

它本来就是为了做"重打包 Analysis API"而存在的。既然边界正确地画在 classloader 上，
这个模块就**完全多余**——而且它的实现（shade + provided 作用域）本来就是坏的。
删掉它之后，Analysis API 的 jar 原样上 classpath，冲突由 classloader 解决，
而不是由 jar 的内容解决。

> **推论 —— 解决"两个 jar 撞车"时，先问"边界应该在哪"，再问"要不要改 jar"。
> 改 jar 是把问题挪到构建里；画边界是把它留在正确的层。**

### 12.6 "无源码消费者"其实在读源码（一个被自己的契约抓到的问题）

收尾回归时，`plumb-main` 的消费者测试跑不动了：单模块超过 30 分钟超时，
`IrAnalysisConsumerTest` 的 7 个用例耗时 **795 秒**——而它们分析的是
**两行代码**。

**先看结论**：这不是"DFA 引擎慢"，是消费者**在读源码**。

#### 症状

线程 dump 直接指到了位置：

```
at org.plumb.dfa.resolve.StaticFieldResolver.indexOne(StaticFieldResolver.java:141)
at org.plumb.dfa.resolve.StaticFieldResolver.index(StaticFieldResolver.java:88)
at org.plumb.dfa.engine.DfaEngine.withFunctionSourcePaths(DfaEngine.java:255)
at org.plumb.main.IrAnalysisRunner.analyze(IrAnalysisRunner.java:263)
```

`StaticFieldResolver` 的职责是解析**跨文件**的 `public static final` 常量
（`IO.STATIC_FIVE` 之类）。它的实现方式是**把目录树里的 `.java` 文件读一遍**。

#### 两个独立的缺陷

**缺陷一（正确性）：消费者读了源码。**

`DfaEngine.withFunctionSourcePaths(..)` 顺手就建了 `StaticFieldResolver`。
这个方法名说的是"注册源文件路径"，实际副作用却是"去读这些路径附近的源码"。

而 `IrAnalysisRunner` 是**无源码消费者**——它的全部意义就是
"在没有源码、没有 classpath、没有 Java 工具链的机器上，只凭 IR 出 finding"。
它一边宣称这个契约，一边去文件系统上抓 `.java` 文件。

后果不是 finding 变了，而是**同一个 IR 在不同机器上可能给出不同答案**：
取决于那台机器的目录树里恰好有什么。

**缺陷二（成本）：那个读是无界的。**

根目录的选取规则是"从样例文件往上走两级"。JUnit 的临时文件在
`%TEMP%\junit<random>\` 下，往上两级就是 `%TEMP%` 本身——
本机那里有 **48,631 个 `.java` 文件**。每一次分析都要把它们全部读一遍、
正则扫一遍。

于是：7 个用例 × 48,631 个文件 = 795 秒。而 DFA 分析本身只要 **58 毫秒**。

#### 修复：把"读源码"变成显式且有界的

不是删掉这个能力——它有用：`Level.WARNING`、`IO.STATIC_FIVE` 这类
FIELDLOAD 需要它才能挂上真实的父节点。是把它**挪到正确的层**：

| | 之前 | 之后 |
|---|---|---|
| `withFunctionSourcePaths(..)` | 注册路径 **+ 读源码** | 只注册路径（纯元数据） |
| 读源码 | 无声的副作用 | `withStaticFieldResolverRoot(Path)` 显式 opt-in |
| 根目录 | "往上两级" | 调用方指定；隐式回退也**有文件数预算** |

新增 `SourcePathResolver.MAX_INDEXED_FILES = 5000`：
索引在这个预算处停下，并且明确报告（`indexTruncated()`），而不是把整棵树读一遍。
超预算 = "不提供跨文件常量解析"，**不是**"提供一份空的"。

`ScanRunner`（**有源码**的路径）显式 opt-in，根目录取扫描输入的父目录；
`IrAnalysisRunner`（**无源码**的路径）**不** opt-in，并在代码里写明为什么。

#### 结果

| | 之前 | 之后 |
|---|---|---|
| `IrAnalysisConsumerTest` | 795 s | **2.3 s** |
| 单次消费者分析 | ~81 s | **< 1 s** |

#### 为什么这个 bug 之前没被发现

因为它**不影响结果**。装了 resolver 和没装 resolver，两边都能出 finding，
差别只体现在 FIELDLOAD 的形状上。**一个不影响结果的 bug 不会被结果断言发现。**

所以补了两条断言：

- `StaticFieldResolverBoundaryTest`：根目录超预算必须被拒；小根目录必须仍可用；
  注册路径**不得**装上 resolver；显式 opt-in 仍然装得上。
- `IrAnalysisConsumerTest.consumerReadsNoSource`：在 IR 旁边放 400 个 `.java` 文件，
  断言分析**不读它们**（耗时上界 + 无 resolver）。

> **推论 —— 契约边界如果不可观测，它就会侵蚀。**
>
> "消费者不读源码"这个契约，在修复之前**没有任何东西能证明它**。
> 删掉源文件只证明"不需要**那个**文件"，不证明"不读**别的**文件"。
>
> **边界要能被观测，才守得住。** 所以 `staticFieldResolverForTest()` 这个
> 访问器本身是修复的一部分：它把"引擎是否在读源码"从不可见变成可见。

> **推论 —— 无界是比"慢"更严重的问题。**
>
> 慢是可测量的；无界是**在某台机器上才发作**的。
> 本机 `%TEMP%` 恰好有 4.8 万个文件，测试就变成 13 分钟；
> 在干净的 CI 机器上可能 3 秒就过了——于是**问题不会在 CI 上复现**。
> 给"往上走"这种启发式配一个**硬预算**，比优化它更快。

## 13. TAC 语义审计："污点还到得到吗？"（2026-11-03）

### 13.1 之前的测试到底证明了什么

§12 之前的全部测试验证的都是 IR 的**形状**："这个构造产生了指令"。这是必要条件，不是充分条件。
一个 TAC 可以形状完美，同时**把污点丢在半路上**。

这个失效模式是隐形的：IR 看着对、CFG 看着对、检查器照跑——finding 就是不报，因为数据在源头和 sink
之间某处死掉了。

所以这次审计问的是另一个问题：

> **把一个带污点的值推进这个构造，检查它是否还到得了 sink。**

探针用的是**真实** sink（`Runtime.getRuntime().exec(...)`），不是 mock，所以整条路径必须真的完好——
TAC 翻译、DFA 传播、sink 匹配，三段都得成立。

**第一次测出来的结果是 32 个构造里 18 个保住污点。** 聚焦的 13 个构造子集起点是 **1 / 13**。

### 13.2 查出来的十四个缺陷

每一个都是"IR 结构正确但语义有损"的地方：

| # | 构造 | 丢掉了什么 | 根因 |
|---|---|---|---|
| 1 | 静态调用 | receiver | class scope 被当成**变量**发出（`FIELDLOAD this.C.String`），而不是**类型引用** |
| 2 | 静态调用 | 实参 | 静态 callee 的实参从 `args[1]` 开始，但污点传递一直看 `args[0]` |
| 3 | `String.format` / `join` / `formatted` | 实参 | 压根不在 passthrough 目录里 |
| 4 | 字段写后读 | 污点 | `recordFieldOpForStore` **无条件**清字段污点——包括刚刚记录过污点的那次写 |
| 5 | `StringBuilder.append` | 污点 | 累加器是**状态**，不是表达式边：mutator 污染 receiver，read-back 从 receiver 传播 |
| 6 | 数组初始化 | 元素 | 初始化列表没有发出任何 `ARRAYSTORE`；元素压根不存在 |
| 7 | `while` 循环 | 污点 | 分析顺序把**循环出口**排在**循环体**前面，于是循环体在出口已经触发之后才被分析 |
| 8 | 带标签的 `break` / `continue` | 污点 | 同一个顺序问题，深一层 |
| 9 | 方法引用 | 污点 | 方法引用是**延迟调用**；没有任何东西把应用点改写成真实调用 |
| 10 | record accessor | 污点 | 合成的 accessor 返回值写错了槽位（用了 `dest`，而不是 TAC 约定的 `lhs1`） |
| 11 | `throw` / `catch` | 污点 | `Throwable` 的 passthrough 按**固定的 owner 列表**匹配，任何子类都看不见 |
| 12 | `foreach` | 污点 | 迭代器两头都没建模：既没有 `iterator()`，也没有 `next()` |
| 13 | 静态字段 | 污点 | 静态字段属于**类**，不属于实例；receiver 必须是类身份 |
| 14 | lambda 捕获 | 污点 | 捕获的变量没有变成 lambda 的**真实参数**，于是调用点什么都没传 |

顺带还修了两个小的：数组本身带污点时把数组传给 sink 没被报出来；跨类 summary 查找用的是
**调用方**的类，而不是**被调用方**的类。

### 13.3 修复前后的实测

| | 保住污点的构造数 |
|---|---|
| 审计前 | **18 / 32** |
| 聚焦子集，修复前 | **1 / 13** |
| 聚焦子集，修复后 | **13 / 13** |
| 审计后 | **31 / 32** |

唯一仍然不报的 fixture 是 `string-switch`——它是**正确的真负例**：
discriminator 确实带污点，但被赋值的变量只可能拿到常量 `"safe"` 和 `"b"`。
带污点的 switch 判别式**不会**污染赋值目标。旧探针在这里期望有报告，那个期望本身是错的，已经改正。

> **推论 ——"每个构造都必须报"不是一条有效的验收标准。**
>
> 这个探针的第一版断言**每个** fixture 都报 finding。这条断言会让"什么都报"的分析通过——
> 包括报错的那些。探针只有在同时具备**正例**（污点必须到达）和**负例**（污点必须**不**到达）
> 之后才有意义。
>
> 这和覆盖矩阵为什么要成对是同一个道理（§11.4）：只断言"它报了"的测试，分不清
> 正常工作的分析和过于激进的分析。

### 13.4 落到 TAC 和 DFA 的修复

审计同时改了两层：

**`plumb-tac`（翻译层）**：
- `ExpressionTranslator.classScopeFqn(...)`——静态调用的 class scope 是**类型引用**，发出类型常量和
  FQN callee signature。那条幻影 `FIELDLOAD this.C.String` 消失了。
- `ExpressionTranslator.isStaticCallee(...)` 和 `passthroughArgIndices(...)`——共享的参数选择逻辑，
  **本地**传播和 **summary** 记录从此不可能再各走各的。
- `ExpressionTranslator.storeArrayInitializerElements(...)`——每个元素一条 `ARRAYSTORE`。
- `ExpressionTranslator.capturedVariables(...)`——lambda 捕获的外层局部变量/参数。
- `ExpressionTranslator.staticFieldDeclaringClass(...)` / `fieldReceiver(...)` / `classIdentityReceiver(...)`。
- `TacProgram.deferredTargets` + `ExpressionResult.ofDeferred(...)`——方法引用的延迟目标表。
- `TacTranslator.staticFieldsByClass` / `recordStaticField` / `isStaticFieldOf`——静态字段表。

**`plumb-dfa`（传播层）**：
- `AccumulatorCatalog`（新增）——累加器 mutator / 累加器读取的目录。
- `ArrayTaintTracker.alias(...)` / `elementTaintOf(...)`——数组元素的别名语义。
- `TaintedCheckerBase.propagateAccumulatorTaint(...)` / `propagateIteratorTaint(...)`——两种状态污点传播。
- `TaintedCheckerBase.recordFieldOpForStore(...)`——**干净**写清字段污点；**带污点**写记录污点。
  这是两种不同语义，旧代码把它们混为一谈。
- `TaintedCheckerBase.isThrowableMessageMethod(...)`——按**方法名**匹配，不再维护 owner 列表。
- `TaintedCheckerBase.calleeClassOf(...)`——跨类 summary 查找用被调用方的类。
- `DfaEngine.analysisOrder(...)`——循环感知的分析顺序：识别 back edge、算出 natural loop，
  并且**离开**循环的 successor 在循环体**之后**才探索。

### 13.5 守住这条线的测试

两个常驻回归，都已进 surefire allow list：

- `plumb-main/src/test/java/org/plumb/main/TacTaintSurvivalTest`——**26 个测试**。21 个正例构造、
  静态字段、record accessor，以及 3 个负例/完整性断言。
- `plumb-tac/src/test/java/org/plumb/tac/translate/StaticCallScopeTest`——**6 个测试**。静态 class scope
  不得产生幻影 `FIELDLOAD`，callee signature 必须是 FQN，静态 callee 的参数布局必须是
  `args[0]` = 类型常量、`args[1..]` = 真实实参。

临时探针已移入 `_trash/temp-probe-20261103`；留在树里的是回归。

> **这次审计的纪律——测语义，不测形状。**
>
> 已有的覆盖矩阵问的是"这个构造产生了对的指令吗？"。这个问题有答案，60 个构造全是 yes——
> **而其中 14 个在悄悄丢掉污点**。"它产生了指令"和"数据活下来了"是两个不同的断言，
> 而漏洞扫描器真正做出的，是第二个那个。

## 14. JVM 的几种语言真的能互相调用吗？（2026-11-03 实测）

### 14.1 问题背后的问题

Java、Kotlin、Scala 共处一个 JVM，所以它们**能**互相调用。但"语言能互相调用"是对**平台**的断言。
对扫描器来说，真正要回答的问题更窄、也更难：

> 污点从 Kotlin 调用方流进 Java sink，分析器会不会报出来——而且**归属到哪个文件**？

### 14.2 实测结果：当时不行

当时的答案是**不行**，有四个彼此独立的原因——每个边界各一个，另外还有一个跟边界无关的。
每一条都是跑真实扫描确认的，不是读代码推断的。

| 场景 | 调用图边数 | finding 的污点来源 |
|---|---|---|
| **Java → Java**（对照） | **2** ✅ | 调用方的值 |
| **Kotlin → Java** | **0** ❌ | sink **自己**的参数——链路没被跟过去 |
| **Scala → Java** | **0** ❌ | sink **自己**的参数——链路没被跟过去 |
| **Java → Kotlin** | **0** ❌ | sink **自己**的参数——链路没被跟过去 |

Java→Java 的对照能跑通，这正是那三个失败可诊断的原因：调用图本身没坏，坏的是**跨语言的那几条边**。

> **关于 finding 归到哪个文件。** Plumb 把 finding 归到 **sink 所在**的文件——这是约定，
> `testcase/01-taint-crossfile/EXPECTATIONS.json` 里写得很直白："Attribution is to the sink,
> not to the orchestrator method that started the chain, so the file must be the sink file for
> the case to be measurable." sink 才是危险操作发生的地方，也是开发者要去改的那一行。
> 所以"finding 有没有落在调用方文件上"从来不是正确的问题。正确的问题是**污点来源**：
> 链路被跟过去时，来源是调用方传进来的值；没跟过去时，来源是 sink 自己的入口边界——
> 而这两者在输出里长得一模一样，都跟"污点从来没跨过调用边界"没法区分。
> 现在修好的测试断言的就是这个区别。

#### 缺陷 1——两种签名形态算出不同的 key

Kotlin 前端解析 owner 是**正确的**：它发出 `demo.JavaSink.run/1`。而 Java 前端把同一个函数
登记成 `run(String):void`，enclosing class 是 `JavaSink`。

调用图靠比较*裸名 + 元数*把调用方连到被调用方，而算这个 key 的辅助函数对两种形态处理不同：

```
bareNameAndArity("run(String):void")     = "run/1"                (去掉类前缀)
bareNameAndArity("demo.JavaSink.run/1")  = "demo.JavaSink.run/1"  (保留类前缀)
```

字符串不同，裸名表就连不上，边永远建不起来。Java→Java 能通是因为**两边**都用括号形态。

> **可推广的教训**：一个签名格式"单独看是对的"，组合起来仍然可能是错的。Kotlin 那个格式对
> *sink 匹配*是对的（`owner.method/arity`——sink family 用的就是它），Java 那个格式对 *Java*
> 也是对的。调用图需要第三种概念——一个**两边完全一致**的 key——而没有人定义过它。

**已修**：`CalleeKeyNormalizer`——唯一的规范形态 `owner.method/arity`，owner 按**简单名**匹配，
所有前端和调用图本身都用它。

#### 缺陷 2——Scala 调 Java 静态方法时，名字是 module class

dotc 把 Java 类的**静态**成员建模成一个合成的 *module class* 的成员，名字是类名加一个 `$`。
所以 Scala 那边说的是 `demo.JavaSink$.readAll/1`，Java 那边说的是 `demo.JavaSink.readAll`。
同一个方法两种拼法，带 owner 的索引匹配不上。

**已修**：module class 后缀在**匹配时**归一化——但**绝不**从发出的 key 里去掉，因为 Scala 里
`Service$` 和 `Service` 确实是两个不同的类，合并它们会把两个真实存在的方法合并掉。
同一套归一化同时用在 `CalleeKeyNormalizer`、调用图的带 owner 索引，以及引擎的被调用方解析里。

#### 缺陷 3——Scala 根本看不见 Java 源码

Scala 前端把 dotc 跑在单个文件上，给了 `-classpath`，但那个 Java 类只以**源码**形式存在。
编译器解析一个源码类的方式是 `<root>/<package>/Class.java`，所以不包含这个形状的 source path
必然落空：

```
[E006] Not Found Error: S.scala:5:14
-- [E006] Not Found Error: S.scala:6:4
[PARSE_PROBLEM] L6: Not found: JavaSink
```

文件编译不过，前端报一个 parse problem，这个文件**一个函数都没**进 store。

**已修**：把工程里的 Java 源码暂存成**按包成形的目录树**，跟暂存的 Scala 文件并排，每个文件
放在它自己 `package` 声明所指的那个包目录下。这样不管原始目录是常规 Maven 布局，还是扫描器
经常拿到的扁平布局，查找都能成功——只加 `-sourcepath` 做不到后者。

> **注意两者的性质差别**。Kotlin 的失败是*解析*失败——符号在那儿，但叫不出名字。
> Scala 的失败是*编译*失败——文件在任何分析发生之前就被否掉了。它们需要不同的修法，
> 一个笼统的"把跨语言搞通"会把这件事掩盖掉。

#### 缺陷 4——Kotlin 前端把正在分析的文件**重建**了一遍

这一条最大，而且藏在一个听起来很合理的解释后面。

Kotlin 前端过去会用 PSI file factory 从文本重建文件，因为基于 stub 的文件可能丢掉声明的**函数体**：
stub 里的调用表达式没有参数表达式，于是每个参数都被丢掉，经过它们的污点在调用点就断了。

但 factory 建出来的文件是**内存文件**，而 Analysis API 把这种文件归类为 *dangling*：
它不是 physical 的，也没有 original file，于是**没有任何 module 认领它**。
dangling 文件是在一个**没有依赖**的 module 里分析的，在那里每个引用都解析成错误类型。

所以 Kotlin 那边根本看不见那个 Java 类，解析不出被调用方的 **owner**，callee 签名退化成裸名——
跨语言的那条边就永远建不起来。症状是"finding 没有落在调用方文件上"，
而对这个症状最自然的读法是"调用图少了那条边"。边确实少了，但不是因为调用图：
是调用方**看不见**被调用方。

**已修**：直接用文件本身。physical 文件在 source module 里，所以它能解析到该 module 的依赖；
它自己的文本就是它的文本，所以函数体在那儿。

> **这条教训写下来，免得犯两次**：一个会**重建**文件的前端，等于悄悄把文件挪出了项目的 module
> 结构。"文件文本是对的"和"文件在工程里"是两件事。

#### 缺陷 5——参数来源预分析只看错了 TAC 的操作数槽

这一条跟语言边界无关，而且它会**静悄悄地**给每一条跨语言污点流封顶。

每个参数在自己的函数入口边界都会被种成污点源，所以 sink 方法总是会报——来源就是它自己的参数。
引擎用一个预分析在调用图上向前传播来源标签来还原**真实**来源，能还原时 finding 就报那个标签。

这个预分析算"哪个操作数带着标签"时，扫的是指令的**参数列表**。但 TAC 里，调用类的指令把参数放在
`args`，而**其他所有** opcode 把操作数放在固定的 `lhs1` / `lhs2` 槽位。
`MOVE`——也就是赋值的表达方式——来源放在 `lhs1`，**参数列表是空的**。

于是局部赋值从来继承不到标签。经由局部变量到达 sink 的值，在预分析眼里根本不存在，
finding 只能退回那个诚实但没用的 `param:<name>` 标签——就是它被传进来的那个参数的名字。
污点其实跨过了调用边界，只是报告说不出来。

**已修**：按 TAC 实际使用的操作数槽扫，顺序也按 TAC 自己的读法。

### 14.3 这次测量差点掉进去的坑

只扫 Java sink **本身**就已经会报 CWE-78。sink 自己的参数被当作污点输入，所以没有任何调用方
在场时 finding 也会触发。

所以一个只断言*"混合语言工程会报 CWE-78"*的测试会**通过**，而跨语言能力完全不存在。
finding 会归到 sink 自己的文件，污点来源是 sink 自己的参数——真正传不可信值的那个调用方，
在任何地方都不会被牵连。

这正是回归测试断言**污点来源**、而不是断言 finding 存在、也不是断言文件的原因：

- `theTwoSignatureFormsProduceDifferentKeys`——钉住 key 不匹配这件事本身。
- `theModuleClassSpellingNamesTheSameMethod`——钉住 module class 的归一化，以及它**不会**把两个
  真正不同的类合并掉。
- `javaToJavaEdgeIsFormed`——对照，让测试能区分"跨语言坏了"和"调用图坏了"。
- 三个跨语言场景——每个都断言污点来源**不是** sink 自己的参数，这正是"链路被跟过去"和
  "没跟过去"的分界线。

> **推论——"finding 报了"是对输出的断言，"边形成了"是对能力的断言。** 扫描器可以为一个完全错误的
> 理由报出一个看起来正确的 finding。要钉住一个**能力**，只能去断言实现它的那个机制。

`CrossLanguageInteropTest`（6 个测试）现在钉的是**修好之后**的状态。

### 14.4 改了什么，改在哪

| # | 修法 | 位置 |
|---|---|---|
| 1 | 所有前端统一一个规范 callee key——`owner.method/arity` 形态，两边同样方式归一化 | `CalleeKeyNormalizer` + 调用图的带 owner 索引 |
| 2 | 归一化 Scala 对 Java 静态方法的 module class 拼法，只在匹配时做 | `CalleeKeyNormalizer`、`CallGraphBuilder`、`DfaEngine.resolveCallee` |
| 3 | 把 Java 源码暂存成按包成形的目录树，让 dotc 能解析只以源码存在的 Java 类 | `ScalaTacFrontend.stageJavaSources` |
| 4 | 直接分析 Kotlin 文件本身，而不是把它重建成内存文件 | `KotlinTacFrontend.findFile` |
| 5 | 在来源预分析里扫 TAC 的操作数槽，让赋值继承来源的标签 | `DfaEngine.labelOfAnyArg` |
| 6 | 给每个 Kotlin/Scala 函数一个源路径，让 finding 指得出文件 | 各前端 + `ScanRunner.mergedSourcePathMap` |

修法 1–4 是让**边**建起来；修法 5 是让 **finding** 说得出链路被跟过了；修法 6 让 finding 可操作。

### 14.5 规则输入，以及它们到底是什么

考察了两份外部规则输入。两份都能用，而且**都不是**表面看起来的那个东西。

#### 规则包 —— 一份机器可读的数据流模型

有一款商用静态分析工具，把它的污点规则以**机器可读的数据流模型**的形式发布，
而不是以代码或文字的形式。两个 JVM 语言包合计数千条规则，分成五类：

| 规则种类 | 表达什么 | Plumb 的对应物 |
|---|---|---|
| 数据流 **sink** 规则 | 污点数据落在哪里 | sink family + 污点规则 |
| 数据流 **passthrough** 规则 | 数据如何跨调用传递 | passthrough 条目 |
| 数据流 **source** 规则 | 不可信数据从哪里进入 | 污点 source family |
| 数据流 **cleanse** 规则 | 数据在哪里被净化 | sanitizer |
| **structural** / **semantic** 规则 | 一个模式，不是数据流 | 模式规则 |

这个形态几乎 1:1 对上 Plumb 的模型。一条 sink 规则本质上是：

> 一条规则 id、一个漏洞类别、**携带污点的那个参数**、一个可选的数据状态条件，
> 以及一个**标识符** —— namespace、class、function —— 外加一个标志，说明该规则
> 是否也适用于重写与实现。

namespace + class + function + 参数下标，正是 Plumb 的 sink family pattern
加上一个参数下标。有两个细节让这个映射是**好的**，而不只是貌似合理：

- namespace 与函数名是**正则择一**（`java\.util\.zip|org\.apache...`、
  `getJarEntry|getManifest`），可以直接对上 Plumb 基于正则的 family pattern。
- 那个说明规则适用于*重写*与*实现*的标志，恰好就是 Plumb 已经遵循的
  「owner 取**声明类**」规则（§12.2）。

差距是量出来的：两个包合计 **1722 条数据流规则**，标注 **47 个不同 CWE**；
Plumb 覆盖其中 **16** 个。

#### 模型库 —— 可执行的 Java

另一款商用工具发布的库模型不是一个数据文件，而是**可执行的 Java**：
一组 stub 源文件，其方法体调用一些**模型原语**，原语的名字直接点明缺陷。

```java
public static Connection getConnection(String url, String user, String password) throws SQLException {
    markSensitiveSource(password, SENSITIVE_PASSWORD);
    markHardcodedCredentialSink(password);
    markUnencryptedPasswordSink(password);
    return newOpenDBConnection();
}
```

原语词表不小 —— 几十个不同的 source/sink 原语，分布在若干个类里，
覆盖凭据、密码学、ORM 访问与资源处理。

这与规则包是**不同种类**的输入：规则包给的是*规则*，模型库给的是
*库方法是干什么的模型*。所以映射关系不是「规则 → 规则」，而是：

> 一个 `java.sql.DriverManager.getConnection` 的模型 stub，**就是**一个
> 有一个入口点和一个受污点参数的 sink family 的规格说明。

而且这些 stub **可以被 Plumb 自己的 Java 前端直接执行**：它就是 Java 源码，
它调用的方法名会标明 source 与 sink，而 callee signature 就是普通的
`owner.method/arity`。所以「读一个 stub、产出一个 Plumb sink family」的转换器
不需要新解析器 —— 它只需要认出原语调用的形态。

#### 决定工作内容的那个区分

the rule-pack vendor 有、Plumb 没有的那 31 个 CWE，**不是** 31 条待迁移的 taint 规则。
按 the rule-pack vendor 表达它们所用机制分类：

| 机制 | 例子 CWE | 需要什么 |
|---|---|---|
| **污点**（source → sink） | 79, 80, 89, 22, 73, 78, 99, 918, 566, 497, 15, 95, 94, 90, 91, 261, 256, 170, 730, 601, 117, 502, 134, 470, 639, 489, 215, 692, 87, 83, 82, 113, 643, 191, 190, 93 | 一个 sink family + 一个 source family + 一条规则 |
| **结构**（模式） | 259, 798, 754, 252, 311, 321, 327, 477, 614, 915, 352, 287, 285, 615, 759, 749, 772, 326, 328, 760, 398, 131, 129, 120, 787, 319, 501, 338 | 模式检查器——**另一套**机制，不是数据流那套 |

> **"迁移缺的 31 个 CWE"这个任务是错的。** 其中大约一半是模式规则，和数据流引擎毫无关系；
> 而污点那一批里，好几个是**同一个缺陷的不同编号**（CWE-79/80/83/87 是 XSS 的变体；
> CWE-82/83/87 属于同一族）。正确的工作单元是**一个缺陷类**，不是一个 CWE id——
> 这正是 Plumb 的 `TaintRule.cwes()` 已经支持的（一条规则，多个编号）。

### 14.6 这对计划意味着什么

1. **先修跨语言的边，再迁规则。** 从 the rule-pack vendor 的 Kotlin 包迁过来的一条 sink family，
   如果 Kotlin 调用根本形不成调用图边，就毫无价值——那条规则永远不会在它本来就是为
   那个边界写的场景里触发。
2. **the rule-pack vendor 那份是产出更高的输入。** 1722 条机器可读、带了 CWE 标签、形态已经是 Plumb 的
   规则；对照 the model-library vendor 的 36 个 model stub——那编码的是*库语义*，不是规则。
3. **the model-library vendor 的价值在库模型，不在规则集。** Plumb 现在的 sink family 手写点了几十个 API；
   the model-library vendor 的 library 把同样的知识覆盖到宽得多的 API 面，而且形态是 Plumb 的 Java 前端
   已经读得懂的。
4. **两份输入都需要转换，不是拷贝。** the rule-pack vendor 的 XML → Plumb 的 YAML；the model-library vendor 的 stub →
   Plumb 的 YAML。两份都不是文件复制，而且都不需要新 IR。

## 15. 分语言的规则层，以及规则包真正说了什么（2026-11-03）

本节记录三件**测出来**而不是设计出来的事情：

1. 规则层里语言维度的**形状**，以及最终让它变成一个想法的那次合并；
2. **the rule-pack vendor 和 the model-library vendor 的输入实际包含什么**，转换之后；
3. **下一个该加哪种语言**，以及每种语言要付出多少。

### 15.1 合并之后的规则层

规则层有三层，每层回答一个不同的问题：

| 层次 | 回答什么问题 | 在哪里 |
|---|---|---|
| **family（缺陷类）** | "这是什么缺陷？" | `SinkFamily` —— 一个名字、一个 CWE、一个 severity |
| **语言表** | "在哪种语言里，哪个符号能到达它？" | `LanguageRuleSet` —— `patterns:`（共享）+ `by_language:`（按语言） |
| **rule（规则）** | "流进这个 family 危不危险？" | `TaintRule` —— 盯一个 family，报一个 CWE |

**family** 是缺陷类，也是规则唯一需要写出来的名字。语言表是按语言的符号表。这本来就是设计意图，
但目录没有照着做：一个缺陷类被摊成了"每种语言一个 family"。

| 合并前 | 合并后 |
|---|---|
| `regex.sinks`、`kotlin-regex.sinks`、`scala-regex.sinks` | `regex.sinks`，带 `by_language: kotlin:` 和 `by_language: scala:` |
| `command.sinks`、`kotlin-process.sinks`、`scala-process.sinks` | `command.sinks`，两张表都在 |
| `taint.sources.console`、`taint.sources.kotlin.console`、`taint.sources.scala.console` | `taint.sources.console`，两张表都在 |
| `taint.sources.env`、`taint.sources.kotlin.env`、`taint.sources.scala.env` | `taint.sources.env`，两张表都在 |
| `el.sinks`、`ognl.sinks` | `expression-injection.sinks` |

最后一行是同一个思想用在另一个轴上：EL 引擎和 OGNL 引擎是同一个**缺陷**（CWE-94/95/917）的
不同**符号**。所以 family 是给引擎加一张符号表，而不是变成两个 family。

**合并带来了什么。** `RegexInjectionChecker` 需要写三个 family，每加一种语言就要改一次。
现在它只写一个：

```java
new TaintRule(
    "dfa-taint-regex-injection",
    "dfa-taint-untrusted-source",
    "regex.sinks",              // 一个 family；语言在它的表里
    "CWE-1333",
    "MEDIUM",
    TaintDomain.StringTaint.INSTANCE);
```

**方向性规则不变。** 语言表说的是"这个符号在这种语言里可达"，从不说"这种语言是安全的"。
没有条目的语言拿到共享 pattern —— 这是正确且保守的答案。

### 15.2 转换里的三个缺陷，靠测量输出才发现

转换器写来把 the rule-pack vendor 的 XML 和 the model-library vendor 的 stub 翻成 Plumb 的 YAML。翻译是机械的，而机械翻译
的失败模式是"已知 sink 还匹配吗"这种测试看不见的。所以转换结果是被测量过的。

**catch-all。** one extended rule pack 里有三条规则，整个 the identifier 都是 `.*`：

```xml
<Label>MyBatis Mapper SQL Injection Sink</Label>
<identifier>
  <namespace><Pattern>.*</Pattern></namespace>
  <class><Pattern>.*</Pattern></class>
  <function><Pattern>.*</Pattern></function>
  <FunctionLabel>MyBatisQueryFuncWithSQLi</FunctionLabel>
</identifier>
```

这些规则是靠 the label 定位的，不是靠符号：MyBatis 的 SQL 在 mapper 方法的注解里，符号本身
不携带任何信息。字面翻译之后，其中一条变成 `^.*\..*\..*(/\d+)?` —— 匹配被分析程序里**每一个**
全限定调用点。SQL family 会报出整个代码库的每一个调用。

现在转换器拒绝通配标识符，改为报告它。这样被拒的有 18 条。

**没转义的点。** 规则包的 namespace 字段 有时本身已经是正则（`java\.util\.zip`），有时是普通
包名（`javax.script`）。普通包名不带转义地进正则，每个点就变成"任意字符"：

```
^javax.script\.ScriptEngine\.eval     也会匹配   javaxXscriptXScriptEngine.eval
```

pattern 仍然匹配目标符号，所以这个 bug 在冒烟测试里看不见，只会表现为跨 family 污染。
受影响的 pattern 有 1400 条。

**没转义的 `$`。** the rule-pack vendor 一部分规则用字面的美元符号写嵌套类，另一部分写
`Response(\$|\.)ResponseBuilder`。前一种形式是**死的**：`$` 是行尾锚点，所以
`^javax\.ws\.rs\.core\.Response$ResponseBuilder\.cookie` 永远匹配不到任何东西。
永远不会触发的规则比没有规则更糟 —— 它看起来像覆盖，实际上什么都不报。

**对合并的影响。** 这三个缺陷之所以能被发现，是因为合并**不幂等**：第二次运行时从合并后的文件里
读回手写 family，而那里已经没有了（合并把它们挪进了托管区域），于是用转换器的版本重建。
这悄悄丢了 `default_arg_index` —— `ldap.sinks` 丢了 `default_arg_index: 1`，于是每条 LDAP
finding 指向 base DN 而不是 filter。

现在合并从一份**独立的 base 文件**（`sink-families-base.yaml`）读手写 family。
合并成了（base + fragments）的纯函数：跑两次产出同一个文件，转换器的修复立刻生效。

**但「独立」还不够 —— 这是同一个缺陷的后半段。** base 过去是*从合并后的文件重建*的，
于是它装着每个规则包 pattern 的一份副本。合并把两者取并集，所以在规则包里删掉一个
pattern 什么也不会发生：base 里那份副本把条目原样塞回了目录。合并是幂等的，删除是空操作
—— 这两条性质互相独立，而当时只被检查了其中一条。

base 现在是一份**显式手写清单**：只装人手写的条目，别的一概不装。一个条目可以与规则包条目
*文本相同*，但前提是它声称了规则包条目没有声称的东西 —— 比如另一个参数位置 —— 那才是手写层
在干活。只是重复规则包条目的那一类就是副本，副本现在算**错误**：`--check` 报出来，
`--prune-base` 把它删掉（且只删它 —— 注释、空行、其余每个条目的格式都逐字节保留）。
普通合并仍然不写 base；会写它的只有两个维护命令 `--prune-base` 与 `--rebuild-base`，
而 `--rebuild-base` 重建出来的文件逐字节相同。

把这个结论钉死的实测：从规则包里删掉一个 pattern，再合并，被跟踪的目录从一条变成零条。
在旧的 base 下它仍然是一条 —— 旧 bug 原样复现。把规则包的条目从 base 里清掉之后，
引擎自己的匹配器在六种语言上，对 9497 个探针符号看到的 first-match 与位置集合与之前完全
一致：这次清理没有改变任何行为，只是去掉了那个会让将来的删除失效的东西。

**现在测出来的结果。** 对一个和任何 sink 都无关的符号：

```
com.example.Whatever.someMethod/3   ->  0 个 family
```

修复之前是 1 个 —— SQL family 的 catch-all。

### 15.3 规则包里实际有什么

the rule-pack vendor 的 JVM 包，测量结果：

| 包 | sink 规则 | 缺陷类 | 不同符号 | 语言原生符号 | CWE |
|---|---|---|---|---|---|
| one extended rule pack | 3650 | 97 | 969 | 422 | 61 |
| `core_java.xml` | 597 | 47 | 112 | 8 | 46 |
| `core_android.xml` | 477 | 40 | 102 | 99 | 37 |
| `core_scala.xml` | 201 | 32 | 83 | 83 | 31 |
| `core_kotlin.xml` | 47 | 18 | 7 | 7 | 20 |

这张表里有两个事实决定了整个计划。

**第一，Java 包几乎全是共享的。** `core_java.xml` 的 112 个符号里有 104 个在 Java 标准库里 ——
`java.io`、`java.lang`、`java.sql`、`javax.servlet`。它们对 Kotlin 和 Scala 同样在 JVM 上，
所以共享 pattern 已经覆盖了。只有 **8** 个符号是 Java 特有的。

**第二，Kotlin 包几乎全是原生的，而且很小。** 它的 7 个符号全是 Kotlin 标准库：`kotlin.io`
（readLine）、`kotlin.text`（Regex）、`kotlin.io.path`、`kotlin.random`、`kotlin.text`、
`kotlin.String`、`kotlin`。里面**零**个共享的 Java 标准库符号，而且**没有引入任何一个 Java
没有的缺陷类**。换句话说：**Kotlin 包是给已经存在的 family 加七个符号。** 它是一张表，不是一套规则。

**第三，Scala 包也是原生的，但大多是第三方。** 它的 83 个符号全是 Scala 生态，但只有大约 30 个是
Scala 标准库（`scala.sys.process`、`scala.util.matching`、`scala.io`、`scala.xml`、
`scala.reflect.io`）。其余是**框架**：Akka HTTP（30 个符号）、Play（14 个）、Slick、
better-files、Twirl views。所以 Scala 包的价值主要是*框架*覆盖，而那是每个应用的问题，
不是语言的问题。

the model-library vendor 的 model library 按构造就是**只有 Java 生态**：`java/`、`android/`、`org/`、`javax/`、
`com/` 下 36 个 Java stub 文件，外加一个 `play/` stub。里面**没有** Kotlin、Groovy、Clojure、
Python、Ruby 或 JavaScript 的 stub。所以 the model-library vendor 贡献的是 JVM 的库语义，对别的语言没有贡献。

### 15.4 转换产出了什么

| | the rule-pack vendor | the model-library vendor |
|---|---|---|
| 输入 | 4 个 JVM 包（core+extended Java、core Kotlin、core Scala） | 36 个 Java model stub |
| sink 规则 / 调用点 | 4495 | 146 |
| source 规则 / 调用点 | 901 | 15 |
| 映射到的缺陷类 | 25 -> **37** | 13 |
| 合并进入的 family | 25 | 13 |

折进 extended 包并补上映射之后，the rule-pack vendor 转换从 25 个 family 变成 **37** 个。最大的几笔：

| Family | 之前 | 之后 |
|---|---|---|
| `ldap.sinks` | 5 | 69 |
| `xss.sinks` | 136 | 177 |
| `trust-boundary.sinks` | 0 | 39 |
| `expression-injection.sinks` | 0 | 30 |
| `resource-injection.sinks` | 156 | 173 |
| `header-injection.sinks` | 39 | 48 |
| `weak-crypto.sinks` | 88 | 93 |

有十二个新缺陷类需要的不只是 family，还需要**规则**：服务端模板注入（CWE-1336）、HTTP 参数污染
（CWE-235）、危险文件包含（CWE-98）、NoSQL 注入（CWE-943）、JSON 注入（CWE-91）、XQuery 注入
（CWE-943）、XSLT 注入（CWE-643）、XML 注入（CWE-91），再加上已有 family 的按语言表。没有规则的
family 是永远不会触发的 family。

### 15.5 下一种语言，以及每种语言要付出多少

用户问的问题是 —— "哪些语言值得接、哪些数据流改动最小" —— 在把规则包测量完之后，答案很锐利。

**一种语言的成本不是规则条数，而是原生符号的数量，以及缺陷类是不是新的。**

| 语言 | 原生符号 | 新缺陷类 | 需要的新 family | 需要的新规则 | DFA 改动 |
|---|---|---|---|---|---|
| **Kotlin** | 7 | **0** | 0 | 0 | 无 —— 7 条表项 |
| **Scala** | 83（30 个标准库） | 6 | 0（全部映射到已有 family） | 0 | 无 —— 83 条表项 |
| **Groovy** | 0（没有规则包） | 0 | 0 | 0 | 要一个前端，而 the model-library vendor 也不支持它 |
| **C / C++** | 596 | 很多 | 很多 | 很多 | 换一套引擎（内存模型、无 GC） |
| **Rust** | 0（没有规则包） | 0 | 0 | 0 | 换一套引擎（所有权、生命周期） |
| **Python** | 461 | 很多 | 很多 | 很多 | 动态派发、无静态类型 |
| **Go** | 507 | 很多 | 很多 | 很多 | goroutine、channel |

测量支持的结论：

1. **Kotlin 和 Scala 作为前两种语言是对的，而且很便宜** —— 恰恰因为 JVM 语言共享 Java 标准库。
   数据流引擎不需要任何语言特有的改动；改的是**表**。

2. **下一种 JVM 语言是 Groovy，而且不值得做。** 它根本没有 the rule-pack vendor 规则包，the model-library vendor 也不支持它，
   它唯一的原生符号是一个 console source（`groovy.lang.GroovyShell.evaluate`）。代价是为一
   条规则做一整个前端。

3. **对于规则大多是原生的语言（Go、Python、PHP、Swift、.NET），要改的是数据流引擎，而不只是表。**
   那些包点名的是它们自己的标准库（Go：90 个里 89 个原生；Python：132 个里 131 个；
   PHP：76 个里 75 个）。每一个符号都是带自己参数角色的**新 API**，而标准库不是 JVM 的语言
   没有共享 pattern 可以继承。这才是真正的成本：不是规则条数，而是**没有共享基线**。

4. **C、C++ 和 Rust 出局的理由和别的又不一样。** 它们的数据流不是堆上的污点走查：是内存安全、
   生命周期和别名分析。the rule-pack vendor 的 `core_cpp.xml` 有 596 条 sink 规则，但只有 **7** 个不同符号 ——
   那些语言的包并不是 JVM 包意义上的污点目录。

**这条测量给出的实用规则。** 对一种 JVM 上的语言，加它的边际成本是它**原生符号表**的大小，
不是它规则包的大小。前两种语言花了 7 条和 83 条表项，引擎零改动。一个标准库 500 个符号的第四种
JVM 语言要花 500 条表项，引擎依然零改动 —— 但它还要一个前端，真正的成本在那里。

### 15.6 在加新语言之前还要做什么

1. **跨语言调用图的边。** 第 14 节的测量显示 Kotlin -> Java 和 Scala -> Java 产生**零**条调用图
   边，所以为 Kotlin 包迁移的 sink 永远不可能跨过它被写的那条边界触发。三个根因都已定位：Java
   形式和 Kotlin/Scala 形式的 canonical callee key 不一致、Scala 前端没有拿到 `-sourcepath`、
   Kotlin 前端没有给顶层函数带 source path。
2. **每个原生符号一条语言表项**，以及一条测试断言该条目在该语言里可达、在别的语言里不可达。
3. **每个 family 一条规则**，包括转换新建的那些。

### 15.7 C#：评估的结论

C# 和其它语言用同一把尺子评估：它要付出多少，以及成本究竟落在哪里。
结论异常干净，而且不是这个问题通常预期的那种答案。

**路线。** C# 源码无法由一个既能解析又能解析出类型的 JVM 内解析器处理。
唯一能给出语义模型的开源选项是微软的编译器，而它跑在 .NET 上。
所以前端是一个 **sidecar 进程**：一个小的 .NET 程序，解析源码、解析符号、把 TAC 形状的指令写到标准输入输出上。
JVM 这一侧通过和其它前端相同的接口读它们。

另外两条路线是按事实排除的，不是按偏好排除的：

- **在 JVM 进程内跑 .NET 编译器**需要一个 Java 到 .NET 的翻译层。而现成的那个方向相反，用不上。
- **只做语法分析的解析器**（tree-sitter 家族的 C# 语法是有人维护的）能解析，但什么都解析不出来：
  没有类型、没有重载决议、没有可空引用信息。而污点引擎恰恰靠这三样活着，
  所以这条路会交付一个驱动不了规则的前端。

**IR 已经覆盖了它。** 这是 C# 比它的名声便宜的那部分。
通常会迫使新增操作码的三种构造 —— `async`/`await`、模式匹配、可空引用类型 —— 都有地方可去：

| C# 构造 | 在 IR 里的落点 | 为什么合适 |
|---|---|---|
| `async` / `await` | `IrPrecision.SOURCE_LOWERED`，加上任务自己的函数 | 一个挂起的方法，其函数体在别处恢复；先例是 Kotlin 的 `suspend`。 |
| 模式匹配 | 一条分支链 | 先例是 Scala 的 `match` 和 Kotlin 的 `when`。 |
| 可空引用类型 | `TacType` 的可空性 | IR 已经建模了类型的可空性，所以带注解的引用类型就是现有格能承载的一个值。 |
| 模型表达不了的表达式 | `HAVOC` | IR 已经有"一个我们描述不了的值"这个操作码，而且它要求前端报告这个缺口，而不是假装没有。 |

所以**第一个可用的 C# 版本不需要新的操作码，也不需要新的 IR 类**。
这和 JVM 那几种语言最后呈现的性质一样，也正是多语言设计值得做的原因。

**主要工作量是 sink 目录，不是解析器。** 目录靠被调用方的签名去匹配每个 family 的 pattern，
而它现在是一份 JVM API 的目录。C# 有自己的标准库，所以每个 family 都需要自己的 `by_language` 表：
`System.Data.SqlClient` 的命令、ASP.NET 的请求读取器、JSON 与 XML 读取器、进程与文件 API。
`by_language` 机制本身已经存在、不需要改动；缺的是内容。

**在做任何别的事之前必须先测的一件事。** C# 把大量 API 暴露成**属性**，
而源码里的属性赋值不是一次调用：

```csharp
cmd.CommandText = tainted;   // 经典的 C# SQL 注入
```

如果前端把属性赋值降解成字段写入，那么 sink 目录 —— 它匹配的是调用签名 —— 对它就是**结构性失明**，
无论目录多完整都报不出这个 finding。因此属性赋值必须降解成一次调用。
这是一个一天能问清、一天能有答案的问题，而且它应该在任何目录工作开始**之前**回答：
它决定这份目录是一张 pattern 列表，还是一张 pattern 列表加一张属性列表。

**和其它语言相比，C# 要付出什么。**

| | Kotlin | Scala | C# |
|---|---|---|---|
| 新增 IR 操作码 | 0 | 0 | 0（预期；承诺前先实测） |
| 引擎改动 | 无 | 无 | 无（预期） |
| 要编目的原生符号 | 7 | 83 | .NET 标准库：数百个 |
| 前端 | Analysis API，进程内 | 编译器插件，进程内 | 一个 .NET sidecar 进程 |
| 新的缺陷类 | 0 | 6 | 很多（.NET 标准库是另一套 API 面） |

最后一行决定了顺序。Kotlin 便宜是因为它共用 Java 的库；Scala 便宜是同样的原因；
**C# 不共用**，所以 C# 是这个计划里第一个成本由**目录**而不是由**表**主导的语言。

## 附录 A：实际落地的文件结构

**分层在 BUILD 里表达，源码树保持平铺。** 三个聚合 POM（`plumb-core` /
`plumb-engine` / `plumb-app`）声明层次，模块目录仍在仓库根下——理由见 §12.3
（43 个门禁脚本硬编码模块路径，`check_test_coverage.py` 靠遍历根目录发现模块）。

```
plumb-core/pom.xml            # aggregates: parser, rules, reporter
plumb-engine/pom.xml          # aggregates: tac, frontend, dfa, style, dsl-kotlin, ai
plumb-app/pom.xml             # aggregates: main, build

plumb-tac/
├── ir/                        # the unified IR contract (the extension surface: 2 fields + 2 enums + 1 opcode + 1 dimension)
│   ├── TacFunction.java       #   + sourceLanguage, irPrecision, synthetic, annotationValues
│   ├── TacInstruction.java    #   + NULL_CHECK
│   ├── TacType.java           #   + nullable (defaults to true) + displayName()
│   ├── TacOperand.java        #   the TacVar identity = name + isTemp
│   ├── SourceLanguage.java    # [new] JAVA / KOTLIN / SCALA / GROOVY / UNKNOWN
│   ├── IrPrecision.java       # [new] SOURCE_FULL / SOURCE_LOWERED / BYTECODE / ...
│   ├── TacProgram.java        #   + FunctionProblem (the structured problem channel)
│   └── MergedProgram.java
├── frontend/                  # the language frontend contract (language-agnostic)
│   ├── TacFrontend.java       #   + StructuredParseResult (the native shape of the structured problems)
│   ├── CompileContext.java    #   the session-scoped compile context
│   ├── SymbolTable.java       #   the cross-language symbol table
│   ├── DefaultCompileContext.java
│   └── java/
│       └── JavaTacFrontend.java   # the reference implementation
├── translate/                 # Java AST → TAC
│   ├── TacBuilder.java        #   the language-neutral TAC builder (shared by the three languages)
│   ├── TacTranslator.java
│   ├── StatementTranslator.java
│   ├── ExpressionTranslator.java
│   ├── TypeMapper.java
│   └── JavaModernFeatures.java  # switch expr / pattern / record / the compact constructor
└── bytecode/jimple/           # [existing] Jimple -> TAC (the .jar analysis channel, unchanged)

plumb-frontend/                # [new] the non-Java frontends (Kotlin + Scala, isolated from plumb-tac)
├── pom.xml                    #   isolates the kotlin / scala3 compiler dependencies
├── docs/KOTLIN-ANALYSIS-API-NOTES.md
└── src/main/java/org/plumb/tac/frontend/
    ├── kotlin/                 #   Analysis API Standalone
    │   ├── KotlinTacFrontend.java
    │   ├── KotlinTacTranslator.java    #   owner.method/arity
    │   ├── KotlinTypeMapper.java       #   non-null / nullable / platform type
    │   ├── KotlinModuleStructure.java
    │   └── KotlinDirectoryScope.java
    └── scala/                  #   dotc PluginPhase
        ├── ScalaTacFrontend.java
        ├── ScalaTacPhase.java
        ├── ScalaTacTranslator.java     #   owner.method/arity
        ├── ScalaTypeMapper.java
        ├── PlumbScalaPlugin.java
        └── PlumbScalaContext.java

plumb-parser/
├── JavaSourceParser.java      # [changed] the language level chain (J1): no longer hardcodes JAVA_17
├── JavaLanguageLevel.java     # [changed] "8".."25" / "1.8" / "latest" -> the named levels
└── JavaModule.java            # [changed] languageLevel (String)

plumb-dfa/
├── config/
│   ├── SinkFamily.java        #   + languages
│   ├── SinkFamilyRegistry.java#   + language-aware lookup
│   └── ConfigLoader.java      #   parses the YAML languages:
├── taint/
│   ├── TaintRule.java         #   + languages
│   ├── NullabilityFacts.java  # [new] the Kotlin non-null facts
│   └── FieldOpClassifier.java
├── engine/
│   ├── DfaChecker.java        #   + supports(TacFunction)
│   └── DfaEngine.java         #   the language gate on both the analysis loop and the summary loop
└── checkers/taint/
    ├── NullDerefChecker.java  #   + the nullable decision + NULL_CHECK
    ├── RegexInjectionChecker.java   # [new] CWE-1333
    └── FilePermissionChecker.java   # [new] CWE-732

plumb-main/
├── ScanRunner.java            # [changed] dispatches to the TacFrontend by extension
├── FrontendRegistry.java      # [new] the extension -> frontend mapping (lazily loaded)
└── IrAnalysisRunner.java      # [new] the source-code-free consumer entry point

plumb-dsl-kotlin/              # [changed] the classloader boundary of the script engine (§12.5)
└── src/main/kotlin/org/plumb/dsl/kotlin/
    ├── ScriptEngineClassLoader.kt   # the child loader: both the classes and the resources are child-first
    └── ScriptEngineHolder.kt        # switches the context classloader at both the compile time and the evaluation time
```

**被删掉的模块**：`plumb-kotlin-analysis-api`。它是为了"重打包 Analysis API 的 jar"
而存在的，但那条路走不通（shade 跳过 provided 依赖，产出 3.5KB 空壳），
而且**根本不需要**——冲突的正确解法是 classloader 边界，不是改 jar 的内容。
理由见 §12.5。

## 附录 B：参考资料

**the model-library vendor**
- Static Analysis Language and Framework Support（官方语言清单——JVM 语言只有 Java/Kotlin/Scala）
  `blackduck.com/static-analysis-tools-sast/languages-and-frameworks.html`
- the model-library vendor on Polaris 2026.6.0 Release Notes（Kotlin/Scala checker 完整清单）
  `docs.blackduck.com/r/cov_polaris/latest/coverity-on-polaris/coverity-on-polaris-2026.6.0-release-notes.html`
- the model-library vendor 2024.9.x: Supported Platforms, Languages, and Compilers（Kotlin 编译器版本支持）
  `documentation.blackduck.com/.../topics/r_coverity-compatible-platforms_2024.9.html`
- the model-library vendor 2026.3.0 Release Notes（SpotBugs/Detekt 支持移除、Kotlin 质量检查器默认禁用）

**Kotlin**
- Analysis API 官方文档（KaSession / KaSymbol / KaType / Standalone 模式的限制声明）
  `github.com/Kotlin/analysis-api/blob/main/Writerside/topics/Index.md`
- StandaloneAnalysisAPISessionBuilder（Standalone 模式的服务注册与模块结构）
  `github.com/JetBrains/kotlin/tree/master/analysis/analysis-api-standalone`
- Backend IR 总览（IrElement / IrModuleFragment / lowering / serialization）
  `github.com/JetBrains/kotlin/tree/master/compiler/ir`
- Kotlin 2.0+ invokedynamic for lambdas
- **Release process / 版本支持窗口**（2.4.21 = 当前最新，2.4 线支持到 2027-12-03）
  `kotlinlang.org/docs/releases.html`

**Scala**
- Changes in Compiler Plugins（`PluginPhase` / `StandardPlugin` / `runsAfter` / `runsBefore` / `plugin.properties`）
  `scala-lang.org/api/3.8.4/docs/changed-features/compiler-plugins.html`
- `MiniPhase` API（`transformXXX` 钩子全集 / fused traversal）
  `dotty.tools.dotc.transform.MegaPhase.MiniPhase`
- TASTy overview（`docs.scala-lang.org/scala3/guides/tasty-overview.html`）
- **All Available Versions**（3.10.0 = 最新；3.9.0 = LTS；3.8.0/3.8.1 有回归不可用）
  `scala-lang.org/download/all.html`
- **Scala 3.8 release notes**（JDK 17 起为硬性要求；标准库改用 Scala 3 编译）
  `scala-lang.org/news/3.8`
- **Scala 3.9 LTS release notes**（`into` 稳定；LTS 保证至少三年维护）
  `scala-lang.org/news/3.9`

**Java / JavaParser**
- **JavaParser 3.26.4** 的 `ParserConfiguration.LanguageLevel` 枚举——上限 `JAVA_21` + `BLEEDING_EDGE`
  `github.com/javaparser/javaparser`
- **JDK 25 是当前 LTS**（本机运行时 `openjdk 25.0.1`）

**其他**
- Detekt / ktlint / diktat（Kotlin PSI 生态）
- Scalafix / WartRemover / scala3-tasty-inspector
- Groovy AST Transformations（9 阶段编译期元编程）
- SemanticDB（语言无关元数据格式）
- SpotBugs/FindBugs 在 Kotlin 上的误报问题（data class `componentN` 被误判死代码）
