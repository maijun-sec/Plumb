# Plumb 中的 C#：两个商业工具如何生成 IR，以及 TAC 必须做哪些调整

[English](../../tech/CSHARP-FRONTEND-ANALYSIS.md) | [中文](../README.md)

本文按顺序回答三个问题：

1. **Coverity 和 Fortify 究竟如何把 C# 变成 IR？** 不是"它们支持 C#"——而是
   基于证据的具体流水线，逐阶段展开。
2. **这对 Plumb 意味着什么？** 一个路线决策，替代方案是被衡量过的，不是被
   偏好的。
3. **TAC 必须做哪些改变**，才能让一个 C# 前端得以编写？

第三个问题是最关键的。前两个决定 C# 前端**住在哪里**；第三个决定它**被允许
产出什么**。针对错误的 TAC 契约编写的前端，会产出一次"报告成功却什么也没
找到"的扫描——这正是本项目反复栽过的坑。

标注 **[MEASURED]** 的内容来自本仓库的代码。标注 **[EXTERNAL]** 的内容来自
厂商文档或公开文章，并已注明出处。

---

## 0. 先给结论

| 问题 | 答案 |
| --- | --- |
| Coverity 如何把 C# 变成 IR？ | 对真实的 MSBuild/`dotnet build` 做构建捕获（`cov-build`），然后从**源码**生成**平台无关的 IR**，包含 CFG + 调用图 + 符号表 + 类型。C# 支持**依赖分析工具自带的一个 .NET 运行时**（[EXTERNAL]）。 |
| Fortify 如何把 C# 变成 IR？ | 历史上分两段：C# → debug DLL/EXE → `ildasm` → **MSIL** → Fortify 的 NST。自 16.20 起翻译器可以直接吃 **.NET 源码**。分析器本身跑在一个 **JVM** 里（[EXTERNAL]）。 |
| 对 Plumb 意味着什么？ | 两个商业工具处在这条轴的两端：**Coverity = 源码级 + 工具自带 .NET 运行时**；**Fortify = 二进制级 + 外部 JVM + 以 IL 为枢轴**。Plumb 已经拥有源码级这一端，也已经拥有一个 JVM。没有任何一家的路线可直接复用；可复用的是**混合体**。 |
| Plumb 的路线 | 一个 **.NET sidecar 进程**，跑 Roslyn，通过带长度前缀的 stdio 协议吐出 TAC，由 JVM 读取。 |
| TAC 需要新的 opcode 吗？ | **不需要新 opcode。但需要三处 IR 契约改动**，其中一处是承重的：**C# 的属性赋值必须降级为 `CALL`，而不是 `FIELDSTORE`。** |
| .NET sink 目录从哪来？ | **转换来的，不是手写的。**包里有 2519 条 .NET sink 规则，转换了 2491 条（98.9 %），72 个缺陷类映射了 66 个，**36 个 family 共 1284 条 C# 模式**。见 [§5.5](#55-目录是转换来的不是手写的代价是什么)。 |
| 另一个工具的 checker 清单对下来如何？ | 它 49 个 C# 安全 checker 里，**37 个**被覆盖。没覆盖的 7 个已补齐——5 个靠读厂商的非数据流规则，1 个（`RESOURCE_LEAK`）靠把一条**从未在 C# 上生效过**的 checker 扩展过去，剩 1 个（`TAINT_ASSERT`）是关于分析器的规则，在程序里没有对应物。见 [§5.6](#56-对照评测另一个工具的-checker-清单暴露了什么)。 |

最后一行决定了 C# 能否产出 finding，而它正是多语言设计文档留作"一个有答案的
一天问题"的那一项（[MULTI-LANGUAGE-IR-DESIGN.md §15.7](../../MULTI-LANGUAGE-IR-DESIGN.md)）。§4 对着代码给出了答案。

**这个问题现在已经实测过了，答案是"能"。** C# 的属性赋值会降级成一条 `CALL`，
其 callee 签名能被现有的 sink 目录匹配到，finding 也能端到端报出。这次测量还
纠正了本文档里的一处错误：TAC 的调用约定把**接收者**放在 `args[0]`，所以被赋的
值是第一个*用户*实参。见 [§6, Phase 0](#phase-0--测量结果)。

---

## 1. 两个商业工具究竟做了什么

### 1.1 Coverity：源码级 IR，盒子里装着 .NET 运行时

Coverity 的流水线以构建捕获打头：

```
cov-build --dir cov-int <真实构建命令>      # 捕获构建
        │
        ▼
   中间目录 (cov-int)                        # 编译单元、编译器
        │                                     # 选项、依赖
        ▼
cov-analyze --dir cov-int                    # 在模型上做分析
```

对 Plumb 而言，厂商文档里有三个事实要紧。

**(a) IR 是平台无关的，且从源码构建。** 厂商对第二阶段的描述是："基于捕获到的
编译指令，Coverity 重新解析每个源文件，生成一个平台无关的中间表示（IR）。
这个 IR 不只包含语法树，还融合了：控制流图（CFG）、调用图、符号表与类型信息
，以及内存访问模式"（[EXTERNAL]，厂商技术说明）。所以 Coverity 的 IR **不是**
编译器自己的字节码——它是一个*重新推导出的*模型，由源码文本构建，而捕获的构建
提供上下文（哪些文件、哪些引用、哪些开关）。

**(b) C# 在那条流水线里是一等公民的源码语言，而且它需要一个 .NET 运行时。**
构建选项表把话说明白了：

> **C#, Visual Basic**——"C# 支持依赖 **Coverity Analysis 自带的 .NET 10 运行时**，
> 而运行 Coverity Analysis 构建捕获的任何 Linux 平台都必须支持 .NET 10。
> 参见 .NET build capture；另见 Coverity 2026.3.2 安装与升级指南中的
> 'C# compilers'。"（[EXTERNAL]，厂商捕获文档）

这是本文中最重要的一个外部事实。Coverity 分析 C# **不是**从 DLL 里读 IL。
它分析 C# 的方式是**在自己的发行版里驱动真实的 C# 编译器**，然后从源码重新
推导出一个 IR。

**(c) 构建捕获是强制的，而且它是 MSBuild 形状的。** C#、VB 和其他编译型语言
在同一张表的"使用构建捕获"一列里；只有解释型语言才有"不带构建命令的捕获"
这条捷径。于是 C# 项目的分析需要一个能跑起来的 `dotnet build` / MSBuild，而
"C# compilers" 那一节安装指南就是配置编译器身份的地方——扮演 `cov-configure`
为 GCC 和 Clang 扮演的角色。

**这对 Plumb 意味着什么。** Coverity 的形状是：*源码 + 捕获的构建上下文 →
自有的 IR*。这正是 Plumb 的 Java/Kotlin/Scala 前端的形状。唯一的差别在
**编译器在哪里跑**：Coverity 自带一个 .NET 运行时；Plumb 跑在 JVM 上。

### 1.2 Fortify：先是二进制级，16.20 起是源码级

Fortify 的流水线是经典的"先翻译、再扫描"两段式：

```
sourceanalyzer -b <build-id> msbuild /t:rebuild Sample.sln   # 翻译
sourceanalyzer -b <build-id> -scan -f results.fpr            # 分析
```

翻译步骤写出中间模型；分析步骤读它并套用规则包。Fortify 自己的用户指南精确
描述了历史的 .NET 路径：

> "SCA 在**通用中间语言（CIL）**上工作，因此支持所有编译到 CIL 的 .NET
> 语言，包括 C# 和 VB .NET。"……"Fortify SCA `sourceanalyzer` 找的是
> **DLL（以及对应的 PDB 文件）**，所以它不会去捡源文件来扫描。"
> （[EXTERNAL]，厂商用户指南）

而机制，同一时期的文档：

> "你的源代码被翻译成一个中间模型……某些类型的代码需要多个阶段的翻译。例如，
> **一个 C# 文件首先需要被编译成 debug 的 .DLL 或 .EXE，然后那个 .NET 二进制
> 文件被 .NET SDK 工具 `ildasm.exe` 反汇编成 Microsoft 中间语言（MSIL）。**
> 而其他文件，比如 Java 文件或 ASP 文件，是由该语言对应的 Fortify SCA 翻译器
> 一次翻译完成的。"（[EXTERNAL]，厂商社区帖，与用户指南相互印证）

两处增补改变了这一点：

- **自 16.20 起，翻译器可以直接接收 .NET 源码。**"自 Fortify 16.20 起，SCA
  已经支持直接扫描 .Net C#/ASP/VB 源码——不再需要预编译。"（[EXTERNAL]，同帖）
- **分析器仍然跑在 JVM 上**，哪怕针对 .NET。Visual Studio 扩展指南说："注意：
  Fortify SCA 在一个 **Java 虚拟机（JVM）**里运行扫描。"（[EXTERNAL]，厂商
  用户指南）

**这对 Plumb 意味着什么。** Fortify 的历史路线（C# → IL）正是 Plumb 为自己的
JVM 语言明确否决的路线，而多语言设计文档的 §1 已经说明了原因：IL 丢掉了规则
真正需要的源码结构（可空引用标注、属性语义、模式匹配的形状）。Fortify 的
*当下*路线（直接的 .NET 源码）与 Coverity 的路线汇合了——但 Fortify 是靠在一
个 JVM 宿主的产品里塞进一个具备 .NET 能力的翻译器做到的，而这恰恰是 Plumb
会觉得别扭的形状。

### 1.3 把这条轴说清楚

| | **Coverity** | **Fortify** |
| --- | --- | --- |
| 入口 | 对真实构建做捕获 | 构建捕获，或直接给源码清单 |
| C# 模型的来源 | **源码文本**，由工具重新解析 | **MSIL**（历史上），**源码**（16.20 起） |
| C# 编译器在哪里跑 | 工具内部（自带 .NET 运行时） | 工具外部（你的 `dotnet build`），或工具自带的翻译器 |
| 分析器运行时 | 原生分析引擎 | **JVM** |
| 跨语言叙事 | 多个语言专用 checker 共用一个引擎 | 一棵 NST，多个分析器（数据流 / 控制流 / 语义 / 结构 / 配置） |

由此得出两条观察。

**观察 1 —— 这两个工具在这条轴上不是竞争关系，而是相反的两端。** Coverity 证明了
*源码级* C# 模型可以在一个非 .NET 产品里实现，代价是自带一个 .NET 运行时。
Fortify 证明了 *JVM 宿主*产品可以分析 C#，代价要么是一次 IL 绕路，要么是一个
旁挂的 .NET 翻译器。

**观察 2 —— 对 Plumb 来说，有意思的格子是两家都没有天然占据的那个。** Plumb
*就是*一个想要*源码级* C# 语义的 JVM 产品。Fortify 那个"JVM 宿主 + .NET
sidecar"的形状，就是 Plumb 应当占据的格子；Coverity 的形状要求 Plumb 变成一个
.NET 产品，而它不该变成那样。

---

## 2. Plumb 的路线决策

考虑过四条路线。两条基于事实被否，两条基于成本被否。

### 2.1 否决：在 JVM 里进程内运行 .NET 编译器

不可能。可用的 Java→.NET 翻译层方向是**反的**，所以它帮不上忙
（[MULTI-LANGUAGE-IR-DESIGN.md §15.7](../../MULTI-LANGUAGE-IR-DESIGN.md)，独立
得出过同样的结论）。

### 2.2 否决：纯语法解析器（tree-sitter C# 之类）

被否，因为它什么也解析不出来。没有类型、没有重载决议、没有可空引用信息。
而污点引擎恰恰靠这些活着：sink 目录匹配的是**被调用者签名**和**接收者类型**，
空指针解引用检查器读的是类型上的**可空性**维度（[MEASURED]：
`SinkFamily.matches(String, SourceLanguage)` 匹配被调用者签名与接收者类型；
`TacType.nullable` 是可空性维度）。纯语法前端两者都产不出来，于是它会交付一个
把 C# 解析得完美、却驱动**零条**规则的前端。

### 2.3 否决：IL / 字节码通道

被否的理由与 JVM 语言相同：IL 丢掉的正是规则需要的事实，多语言设计文档的 §1
已经为 Java 字节码论证过这一点。

但有一个真实的好处值得点名：一条 IL 通道**不需要任何 .NET 工具链**——没有 SDK、
没有 Roslyn、没有源码路径。对手里只有二进制文件的用户，这是唯一可行的路。
那是一个**后续**阶段的真实选项，但不是首发：它产不出属性赋值 sink（第 4 节），也
产不出可空引用事实。

### 2.4 选定：走 stdio 协议的 .NET sidecar 进程

```
┌─ JVM (Plumb) ──────────────────┐        ┌─ .NET sidecar ──────────────┐
│  CSharpTacFrontend             │        │  Roslyn CSharpCompilation    │
│    ├ 启动 sidecar（长驻）       │◀──────▶│    ├ SyntaxTree（逐文件）      │
│    ├ 写：{文件, 引用, ...}      │  stdio │    ├ SemanticModel             │
│    ├ 读：TAC 指令               │        │    └ 吐出 TAC 行              │
│    └ 映射进 TacFunction         │        │                              │
└─────────────────────────────────┘        └──────────────────────────────┘
```

这个 sidecar：

- 跑在 .NET 上（.NET SDK 是硬依赖，正如 Coverity 把它变成对自己的硬依赖一样）；
- 使用 **Roslyn**——开源的编译器平台——做解析**以及**语义模型（类型、重载
  决议、可空标注、`partial`/生成代码、`#if` 区域）；
- **在整个扫描期间长驻**，而不是逐文件：覆盖整个项目的 Roslyn
  `CSharpCompilation` 的解析质量远高于逐文件编译，而逐文件前端会精确复现
  多语言设计文档 [§14](../../MULTI-LANGUAGE-IR-DESIGN.md) 记录的那次跨语言调用图失败；
- 吐出 **TAC 形状的指令**而不是另一套 IR，于是 TAC 依然是前端与引擎之间
  唯一的契约。

**为什么是 sidecar 而不是进程内。** Roslyn 是 .NET，Plumb 是 JVM。桥就是一个
进程边界，唯一的问题是**跨过边界的是什么**。跨过去的是 *TAC* 而不是 AST，
能把边界收窄：sidecar 拥有 C# 语义，而本来就按语言中立构造的 TAC 承载结果。

**为什么不走 Fortify 那条路（IL）。** 因为 IL 无法把属性赋值表达成一次调用，
而 sink 目录是基于调用的（第 4 节）。它也无法表达可空引用类型。Fortify 能承受，
是因为它的 .NET 规则本来就是围绕 .NET 二进制模型设计的；Plumb 的规则是与
Java、Kotlin、Scala 共享的，C# 规则必须加入**同一份**目录。

### 2.5 路线小结

| 路线 | 结论 | 理由 |
| --- | --- | --- |
| 在 JVM 里进程内跑 .NET 编译器 | 不可能 | 没有可用的 Java→.NET 翻译层 |
| 纯语法解析器 | 否决 | 解析不出类型 → 驱动不了规则 |
| IL / 字节码 | v1 否决 | 丢失属性语义与可空标注 |
| **.NET sidecar（Roslyn）吐出 TAC** | **选定** | 源码级语义，边界窄，TAC 仍是契约 |

---

## 3. C# 在 Plumb 自己的单位下要花多少

多语言设计文档 [§15.5](../../MULTI-LANGUAGE-IR-DESIGN.md) 建立了成本模型：**一个语言的成本是它原生物符号表的大小，
不是它规则包的大小。** Kotlin 花了 7 条表项、0 处引擎改动；Scala 花了 83 条、
0 处。

C# 打破了让 Kotlin 和 Scala 变便宜的那个模式，原因是结构性的：

| | Kotlin | Scala | **C#** |
| --- | --- | --- | --- |
| 新 IR opcode | 0 | 0 | **0** |
| 引擎改动 | 无 | 无 | **无** |
| 要编目的原生物符号 | 7 | 83 | **数百**（.NET 标准库） |
| 前端 | Analysis API，进程内 | 编译器插件，进程内 | **一个 .NET sidecar 进程** |
| 新缺陷类别 | 0 | 6 | **许多** |
| **共享 Java 库？** | 是 | 是 | **否** |

Kotlin 和 Scala 便宜，**是因为它们共享 Java 库**：它们标准库的符号能被同一批
模式识别出来，所以工作只是表项，仅此而已。**C# 不共享它。**.NET 标准库是一个
形状不同的 API 面，每个 family 都需要自己的 `by_language` 表。

这个机制已经存在且无需改动。`sink-families.yaml` 已经为每个 family 携带一个
`by_language:` 块，`LanguageFilter.narrowFamily` 也已经把逐语言模式表收窄到
选中的语言（[MEASURED]）。不存在的是**内容**：.NET 的符号。

那份内容的大小才是真正的预算。它同时也是**可机械推导**而非需要创造的那部分，
这对一个规划很重要：这张表是一份 .NET API 形状的清单，而 .NET 的 API 形状是
有文档的。

---

## 4. TAC 为 C# 要做的调整

这一节决定了 C# 前端能否产出 finding。

### 4.1 那个承重的发现：属性赋值必须是 `CALL`

多语言设计文档 [§15.7](../../MULTI-LANGUAGE-IR-DESIGN.md) 把这件事标记为首先要测的：

> "如果前端把属性赋值降级成字段写，那么 sink 目录——它匹配的是调用签名——
> 对它**在结构上是盲的**，无论目录多完整，那个 finding 都报不出来。"

**对着本仓库的代码量出来，答案是：它必须是 `CALL`。**

证据链，每一环都是从代码里读出来的：

1. **sink 目录匹配的是被调用者签名。** `SinkFamilyRegistry.lookup` 和
   `.lookupAll` 调用 `f.matches(calleeSignature)`，而
   `SinkFamily.matches(String, SourceLanguage)` 在签名不匹配该 family 的任何
   模式时返回 false（[MEASURED]：
   `plumb-dfa/src/main/java/org/plumb/dfa/config/SinkFamily.java:661`、
   `SinkFamilyRegistry.java:88`）。
2. **引擎只对 `CALL` 指令咨询目录。** `DfaEngine` 的指令遍历专门区分
   `ins.kind == TacInstruction.Kind.CALL` 以应用摘要与副作用消费者，污点检查器
   拿到指令后按它的 `calleeSig` 匹配（[MEASURED]：
   `plumb-dfa/src/main/java/org/plumb/dfa/engine/DfaEngine.java:1976`）。
3. **`FIELDSTORE` 携带的是字段名，不是被调用者签名。** `TacInstruction` 给
   `FIELDSTORE` 一个 `fieldName` 和一个 `dest`，没有 `calleeSig`（[MEASURED]：
   `plumb-tac/src/main/java/org/plumb/tac/ir/TacInstruction.java:173`）。

所以，一个把

```csharp
cmd.CommandText = tainted;   // 经典的 C# SQL 注入
```

降级成 `FIELDSTORE` 的 C# 前端，会产出一份 **sink 根本不存在**的 TAC。目录
看不见它，引擎从不问它，扫描报告成功却什么也没找到。这不是精度损失——这是
一个在输出里不可见的缺失能力。

**因此 C# 前端必须把属性赋值降级为 `CALL`。**

它应当采取的形状，以及原因：

```
// cmd.CommandText = tainted;
CALL  System.Data.SqlClient.SqlCommand::set_CommandText/1
      receiver = cmd
      args    = [tainted]
```

注意方法名：是 **`set_CommandText`**，不是 `CommandText`。这点很关键，因为它
决定了 .NET sink 目录的形状：

- 目录条目是 `^System\.Data\.SqlClient\.SqlCommand\.set_CommandText(/\d+)?`。
- `arg_index` 是 `0`——被赋的值——而现有的 `by_language` 机制已经支持逐模式的
  `arg_index`（[MEASURED]：`sink-families.yaml:63-73`，以及
  `SinkFamily.argIndexesFor(String, SourceLanguage)`）。

**把它一般化，作为 C# 前端必须遵守的规则表述出来：**

> C# 的**属性**赋值是对该属性 setter 的 `CALL`。
> C# 的**字段**赋值是 `FIELDSTORE`。

这不是取巧。这正是 C# 语言本身的语义：属性是*一对方法*（`get_X` /
`set_X`），而属性赋值*就是*对 `set_X` 的调用。编译器就是这么发出的。把它降级
成字段写，会是前端在**不同意这门语言**，而恰恰是这个不同意会悄无声息。

这还给了 C# 一样 JVM 语言没有的东西：唯一一个"对成员的赋值"变成"对方法的
调用"的统一位置，于是 .NET sink 目录不需要在现有的调用匹配器之外再配一个
理解属性的匹配机制。

**同一规则的另一个更小的实例。** `using` / `IDisposable` 和 `await` 有形状相同
的问题——它们在语言里就是调用，所以在 TAC 里也应当是调用。这不是新工作；是
同一条规则应用到不同构造上，列在 §4.5。

### 4.2 不需要新 opcode——四个构造都有归宿

| C# 构造 | 在 IR 里落到哪 | 为什么放得下 |
| --- | --- | --- |
| `async` / `await` | `IrPrecision.SOURCE_LOWERED` + 该任务自己的函数 | 一个被挂起的方法是一个在别处恢复执行体的函数；先例是 Kotlin 的 `suspend`。 |
| 模式匹配（`switch` 表达式、`is` 模式） | 一串分支 | 先例是 Scala 的 `match` 与 Kotlin 的 `when`。 |
| 可空引用类型（`string?` vs `string`） | `TacType` 的可空性维度 | `TacType` 已经有 `nullable` 标志、`nonNull()`/`nonNullArray()` 工厂，空指针解引用检查器已经在读它（[MEASURED]：`plumb-tac/.../ir/TacType.java:113`）。 |
| 模型无法表示的表达式 | `HAVOC` | 这个 opcode 已存在、语言中立，并且**要求**前端报告缺口而不是假装（[MEASURED]：`TacInstruction.Kind.HAVOC`）。 |
| 引擎看不进去的调用 | `UNINTERPRETED` | 这个 opcode 已存在、语言中立，并且带有明确的默认处理（[MEASURED]：`TacInstruction.Kind.UNINTERPRETED`）。 |

`NULL_CHECK` 覆盖 C# 的空值抑制运算符 `!` 与可空流分析收窄：`x!` 断言非空，
而这正是该 opcode 的含义（[MEASURED]：`TacInstruction.Kind.NULL_CHECK`）。

所以：**首个可用的 C# 版本不需要新 opcode，也不需要新的 IR 类。**

### 4.3 确实需要的三处 IR 契约改动

新 opcode：没有。但需要三处*契约*改动，其中一处是承重的。

#### (1) `SourceLanguage` 增加 `CSHARP`——而且必须追加

`SourceLanguage` 当前是 `JAVA, KOTLIN, SCALA, GROOVY, UNKNOWN`（[MEASURED]：
`plumb-tac/src/main/java/org/plumb/tac/ir/SourceLanguage.java:45`）。

**`CSHARP` 必须加在枚举末尾、`UNKNOWN` 之前……而 `UNKNOWN` 必须留在最后。**
理由是磁盘缓存：这个枚举是**按名字**序列化的，所以重命名或重排会让每个已缓存
的程序失效（[MEASURED]：`SourceLanguage.java:24-26` 的注释，以及契约测试
`MultiLanguageIrContractTest.SourceLanguageTest.enumConstantsAreStable` 断言的
确切顺序）。该测试必须更新以包含 `CSHARP`，并且必须断言*新的*顺序——一个只
检查"包含"的测试抓不住将来的重排。

`SourceLanguage.parse` 必须接受 `csharp`、`cs`，并（沿用现有的 `jvm-` 前缀
惯例）接受 `dotnet-csharp` / `dotnet-cs`。

`hasNonNullTypeSystem()`——当前只有 `KOTLIN` 为 true（[MEASURED]）——**对 C#
必须保持 `false`**，而这一点微妙得值得单独说明。

C# *确实*有可空引用类型系统（`#nullable enable`、`string?` vs `string`）。那为什么
答案仍然是 `false`？

因为这个标志的含义。它的 javadoc 写着："这门语言是否有编译期非空类型系统。
Kotlin 的 `String`（相对于 `String?`）**在声明处**保证非空。"以及："`true` 的
含义是'编译器拒绝为这个类型传入 null 字面量'，这是关于**声明**的事实。它对某个
特定运行时值是否为 null 什么都没说。"

C# 的可空引用类型是**警告，不是错误**。对一个非空引用赋 null，C# 编译器发出的是
*警告*（CS8600 系列），不是错误。`#nullable disable` 的项目——C# 8 之前项目的
默认值——则完全没有可空性信息。所以一个 C# 的 `string` **不像** Kotlin 的
`String` 那样被保证非空。

**因此：`hasNonNullTypeSystem()` 对 C# 保持 `false`，且 C# 前端绝不能为普通的
`string` 发出非空类型。** C# *确实*提供的可空性，要通过逐值的 `NULL_CHECK`
opcode 和前端自己的事实集来表达——但*声明处*的保证并不成立，所以它不能被用来
压制空指针解引用 finding。

这正是 IR 已经写明的那条"方向性规则"：语言维度可以**压制**一个 finding 或
**选择**一条规则，但绝不能**证明**代码是安全的。

`supportsFullDataflow()` 必须对 C# 返回 `true`——它是静态类型的。

#### (2) `TacType` 需要两个新的原始类型种类：`DECIMAL` 与值语义

**[MEASURED]** `TacType.Kind` 当前有：`BOOLEAN, BYTE, SHORT, CHAR, INT, LONG,
FLOAT, DOUBLE, STRING, CLASS, ARRAY, NULL, VOID, UNKNOWN`。

C# 有三件类型系统的事实，是当前 IR 无法表达的：

| C# 事实 | 当前 IR 为何无法表达 | 需要的改动 |
| --- | --- | --- |
| `decimal` 是一个**值类型**，有 28–29 位有效数字 | 没有 `DECIMAL` 种类，所以 `decimal` 会被映射成 `DOUBLE` 或 `UNKNOWN`——而数值区间分析（`checkSlt`）会推理一个错误的域 | 增加 `Kind.DECIMAL` |
| `int`/`long`/`short`/`byte` 是**值类型**（赋值即复制） | `TacType` 根本没有值/引用之分——`isReference()` 只对 `STRING, CLASS, ARRAY` 为 true，`isPrimitive()` 只覆盖八个数值/布尔种类 | 增加一个 `valueType` 标志，或一个 `Kind.VALUE` 大类 |
| `string` 是**引用类型**，但**不可变**，且 `==` 是**按值**比较 | `TacType.STRING` 存在且是引用；IR 没有"引用类型上的值相等"这一概念 | 等值运算符上的语言相关处理，不是 IR 改动 |

**值/引用之分是对数据流要紧的那一个**，而它恰恰是 IR 现在没有的。在 Java 里，
`int` 与 `Integer` 不同；在 C# 里，`struct` 的赋值是复制。对污点引擎来说后果
是具体的：**别名**。一个 C# `struct` 字段写不会与调用方的副本别名，而一个 Java
对象字段写会。如果 TAC 对两者都给 `FIELDSTORE`，字段污点跟踪器就会把污点带过
一次 C# 语义说根本没发生的复制。

**推荐的最小改动：** 给 `TacType` 加一个 `boolean valueType` 标志（八个原始类型
默认为 `true`，`STRING`、`CLASS`、`ARRAY` 默认为 `false`），再加一个
`Kind.DECIMAL`。两者都是**增量式**的——字段增加对序列化契约是向后兼容的，枚举
常量加在末尾也是安全的。

`valueType` 标志必须**被排除在 `TacVar` 身份之外**，与类型本身完全一样
（[MEASURED]：`TacOperand.TacVar.equals` 只比较 `name` 与 `isTemp`）。一个被
精化的类型不得改变变量的身份，否则记在旧键上的污点会被悄无声息地丢掉。

#### (3) `TacOperand.Const` 需要 `DECIMAL` 与一种 `BYTE[]` 式的字面量形式

**[MEASURED]** `TacOperand.Const.Kind` 有：`INT, LONG, FLOAT, DOUBLE, BOOL,
CHAR, STRING, NULL`。

C# 字面量需要 `decimal`（`1.5m`），而更重要的是——C# 的字符串插值与逐字字符串
都没问题，但 C# 也有带转义序列的 `char` 字面量（没问题）以及
`$@"..."` 插值逐字字符串（也没问题，仍是字符串）。

真正的缺口是 `decimal`。一个 `decimal` 字面量绝不能被无声地加宽成 `double`
——那样数值区间分析就会推理一个**不可能存在**的值（`decimal` 没有
`NaN`/`Infinity`）。

`Const.Kind` 必须在枚举末尾增加 `DECIMAL`（同一条向后兼容规则适用）。`value`
字段是 `Object`，所以它可以持有 `BigDecimal` 而不需要改 schema。

### 4.4 唯一一处"引擎而非 TAC"的变化

**DFA 引擎里什么都没有变。** 这正是多语言设计的目的，而且在这里成立。但有一处
*行为*值得说明，因为它是 C# 的 finding 会与 Java 的 finding 产生差异的地方：

**C# 没有受检异常。** 在 Java 里，`TaintedCheckerBase` 的异常传播
（`exceptionPropagator`）会推理一个调用声明了哪些受检异常，而 `catch` 处理器的
`ctorType` 决定它捕获什么（[MEASURED]：`TacInstruction.Kind.CATCH_HANDLER` 携带
`ctorType`；多语言设计文档 [§4.4](../../MULTI-LANGUAGE-IR-DESIGN.md) 描述了建立在其上的 Kotlin 空安全机制）。

对 C# 来说，**每一个**异常都是不受检的，一个 `catch` 可以捕获任何东西。所以 C#
前端应当发出带**声明的** catch 类型的 `CATCH_HANDLER`（裸 `catch` 则是
`System.Exception`），而引擎现有的"被抛类型是否为捕获类型的子类型"逻辑在 C#
规则下成立：`catch (Exception)` 捕获一切，`catch (IOException)` 不捕获
`System.InvalidOperationException`。

这**不需要引擎改动**——子类型关系已经通过 `TacType` 表达了。它需要的是**前端
纪律**：忠实地发出声明的类型，不要凭空造一个更窄的。

### 4.5 TAC/IR 改动的完整清单

| # | 改动 | 类型 | 阻塞？ |
| --- | --- | --- | --- |
| 1 | `SourceLanguage.CSHARP`，追加在 `UNKNOWN` 之前；`parse` 接受 `csharp`/`cs`/`dotnet-csharp` | 枚举 + 方法 | 是 |
| 2 | `hasNonNullTypeSystem()` 对 C# 保持 **false** | 行为 | 是（错误的 `true` 会静默压制 finding） |
| 3 | `supportsFullDataflow()` 对 C# 返回 **true** | 行为 | 是 |
| 4 | **属性赋值 → 对 `set_X` 的 `CALL`** | 前端规则 | 是 |
| 5 | `TacType.Kind.DECIMAL` | 枚举 | 是 |
| 6 | `TacType.valueType` 标志（排除在 `TacVar` 身份之外） | 字段 | 是 |
| 7 | `TacOperand.Const.Kind.DECIMAL` | 枚举 | 是 |
| 8 | `CalleeKeyNormalizer`——C# 的 `owner.method/arity` 形式已经适配 | 无 | 否 |
| 9 | `async`/`await` 函数体用 `IrPrecision.SOURCE_LOWERED` | 已有 | 否 |
| 10 | `x!` 与可空流收窄用 `NULL_CHECK` | 已有 | 否 |
| 11 | 无法表示的表达式用 `HAVOC` | 已有 | 否 |
| 12 | 引擎无法建模的调用用 `UNINTERPRETED` | 已有 | 否 |
| 13 | `CATCH_HANDLER` 携带**声明的** C# catch 类型 | 已有 | 否 |
| 14 | `SourceLanguage` 顺序测试更新以包含 `CSHARP` | 测试 | 是 |
| 15 | 每个 family 的 `by_language` 表，含 .NET 符号 | 数据 | 是（对 finding 而言） |

### 4.6 为什么被调用者键契约已经适配 C#

**[MEASURED]** `CalleeKeyNormalizer.canonicalCalleeKey` 接受：

- `run(String):void`（Java 的括号形式）、
- `run/1`（裸斜杠形式）、
- `demo.JavaSink.run/1`（限定斜杠形式）、
- `demo.JavaSink.run(String):void`（限定括号形式）、
- 以及一种 Jimple 风格的 `G#use` 形式。

规范形式是 `owner.method/arity`，owner 按**简单名**比较，并带一条特例：在比较
两个 owner 时剥掉末尾的 `$`（Scala 的模块类后缀）。

C# 的自然形状——`System.Data.SqlClient.SqlCommand.set_CommandText/1`——**已经
就是**限定斜杠形式，**完全不需要改动**。

有两个细节值得说明，因为否则会踩坑：

- **重载靠元数区分，不靠参数类型。** C# 的重载可以在*相同元数*下按类型区分
  （`Foo(int)` 与 `Foo(string)`）。规范键不区分它们，所以两个重载共享一个键。
  这与 JVM 语言是同一个限制，首发版本可以接受：它影响的是*匹配到哪个* sink，
  而 sink 目录本来就是类型驱动的（一个 `SqlCommand.set_CommandText` 模式在
  任何现实的 .NET sink family 里都不会与同元数的另一个同名重载相撞）。
- **`$` 后缀规则已经在那儿，而 C# 不需要它。** C# 没有模块类等价物，所以不
  需要新的后缀规则。这是一个"**没有**改动"的发现。

### 4.7 线格式不是 IR 契约——而且它没有版本

**sidecar 说 JSON；IR 不说。** .NET 前端跑在另一个进程里，所以 TAC 必须跨一次进程边界。
边界格式是长度前缀的 JSON，形状与 TAC 一一对应（`kind`、`dest`、`lhs1`、`lhs2`、`binop`、
`calleeSig`、`line`、`column`……），Java 侧再把它映射回 IR 类（`CSharpTacFrontend.toInstruction`
/ `toVar` / `toType`）。因此下游的一切——CFG、DFA 引擎、磁盘缓存——看到的都是与 Java /
Kotlin 前端产出的同一个 `TacProgram`，磁盘契约也没有变：`IrSerializer` 照样用 Java 序列化
写 IR，与前端是谁无关。

**JSON 是瞬态的，而且并不大。** 在一个 2.3 KB 的 C# 文件上实测（8 个函数、76 条指令），
同一份 TAC 的三种度量：

| 表示 | 字节 | 说明 |
| --- | --- | --- |
| C# 源文件 | 2,330 | |
| sidecar 的线格式 JSON | 19,400 | 在管道里流过，从不落盘 |
| Java 序列化的 IR（`.tac`） | 12,788 | 缓存契约真正存的东西 |
| 同一个 `.tac`，gzip 后 | 3,265 | |

线格式 JSON 大约是二进制形式的 1.5 倍，它只存在于两个进程之间，而它占用的峰值内存由
**单个最大文件的帧**决定，不由项目规模决定。

**真正缺的是版本握手。** IR 自己的存储契约是有的：`IrSerializer` 写魔数和版本号，不匹配
会被响亮地拒绝（`SqliteTacStore` 抛出一个点名版本的 `IOException`）。而线格式协议**没有**——
请求里没有版本字段，响应里也没有。于是比前端新的 sidecar 不会失败，它会被**解释**：

- 本构建不认识的指令种类，变成 `HAVOC`——恰好是"分析不知道"的那一个 opcode。
- 本构建不认识的运算符，变成 `ADD`。

两种降级都是静默的，而且都在最要命的方向上有损：引擎只在 `CALL`（以及 `UNINTERPRETED`）
指令上查 sink 目录，所以一条被降级成 `HAVOC` 的调用**永远不会**与任何 sink family 匹配。
finding 消失了，而扫描照常报成功。两种降级都不产生 `FunctionProblem`，所以这份不完整
同样不可见。

三条改动可以补上这个缺口，而且都很便宜：

1. 每个响应都带上协议版本（以及 IR 自己的版本），并像缓存拒绝不兼容 IR 那样，拒绝不兼容
   的 sidecar。
2. 保留降级，但让降级**出声**：未知的种类或运算符必须产生一条 `FunctionProblem`，写明
   是什么没被理解、在哪里。
3. 加一条往返测试：sidecar 发一帧，Java 侧解析，断言每一条指令、每一个字段都还在。

第三条最要紧，因为前两条只有在两半**真的**保持同步时才有用——而"保持同步"这件事只有测试
能保证。

### 4.8 前端只送了一份文件清单，而一次 C# 编译远不止一份文件清单

Roslyn 编译的是**一个工程**，而一个 Roslyn `CSharpCompilation` 是五个输入之和：源文件、
引用程序集、预处理器符号、语言版本、可空上下文。前端只提供了其中一个，另外四个写死
（[MEASURED]）：

| 一次 C# 编译需要 | 前端今天送什么 | |
| --- | --- | --- |
| 编译里的全部源文件 | `ensureProject` 遍历每个模块的源根，收下其下的每一个 `.cs`（`CSharpTacFrontend.java:263-284`） | 有 |
| 引用程序集 | `references` 来自 `ctx.classpath()`（`CSharpTacFrontend.java:310-317`）——而那个 classpath 是 JVM 的：`ScanRunner.java:1881` 把 `config.classpathJars` 交给 `FrontendRegistry` | 错 |
| 预处理器符号 | 什么都没有——请求里根本没有 `preprocessorSymbols` 字段（`CSharpTacFrontend.java:319-327`） | 缺 |
| 语言版本 | 字面量 `"latest"`（`CSharpTacFrontend.java:325`） | 错 |
| 可空上下文 | 字面量 `"disable"`（`CSharpTacFrontend.java:326`） | 错 |

**这几个缺口并不等价，其中恰好有一个是静默的。** 直接驱动 sidecar 实测：

| 缺口 | 实际发生什么 |
| --- | --- |
| 引用程序集 | **响亮。** 来自未被引用程序集的类型绑不上，Roslyn 报 `CS0234`，前端把每一条编译错误变成代码为 `PARSE_PROBLEM` 的 `FunctionProblem`（`CSharpTacFrontend.java:355-385`）。扫描会明说这个工程编译不过。 |
| 预处理器符号 | **静默。** 函数体落在 `#if DEBUG` 里面的函数**根本不在 TAC 里**，而 Roslyn 报**零**条诊断：未激活的 `#if` 区域不是错误。 |
| 语言版本 | **过度近似。** 编译按最新语言版本进行，于是工程自己会拒绝的代码也被编译了。 |
| 可空上下文 | 只影响可空性分析；它不改变哪些函数存在。 |

第二行是唯一会真正少掉 finding 的那一行。协议本身已经带着这个字段——`Protocol.cs:286-289`
声明了 `preprocessorSymbols`，`ProjectSession.cs:60` 把它喂给 `CSharpParseOptions`——所以
sidecar 已经就位，只是 Java 侧没发。实测：一个文件里一个函数在 `#if DEBUG` 内、一个在外面：

| `preprocessorSymbols` | TAC 里的函数数 | 诊断数 |
| --- | --- | --- |
| `[]`（前端今天送的） | 1 | 0 |
| `["DEBUG"]` | 2 | 0 |

**缺失的引用程序集没有看上去那么严重。** sidecar 会把运行时框架自带的程序集当作引用加载
（`ProjectSession.cs:127-145`，`TRUSTED_PLATFORM_ASSEMBLIES`），所以基础类库永远都在。真正
缺的是**框架之外**的东西：解决方案里的其他工程，以及 NuGet 包。

**语言版本只认一种拼写。** `ResolveLanguageVersion`（`ProjectSession.cs:178-197`）把请求
里的字符串当作 Roslyn 枚举成员名来解析，解析失败就回退到 `Latest`。实测：

| 送入的 `langVersion` | 编译实际按什么版本进行 |
| --- | --- |
| `CSharp8`、`CSharp9`、`CSharp10` | 就是那个版本——C# 12 的特性被拒绝 |
| `"8.0"`、`"9"`、`"10"`、`"12"` | 最新版本——**被静默忽略** |
| `"banana"` | 最新版本——**被静默忽略** |

所以前端写死的 `"latest"` 目前是惰性的，而想靠送 `"10.0"` 把它变准，同样是惰性的。拼写
必须是枚举成员名，或者 sidecar 需要一张版本号到成员名的映射表。

**模块依赖图是空的，但模块归属不是。** `CompileContext.Module` 带着一个 `dependsOn` 字段
（`CompileContext.java:107`），而 `FrontendRegistry.newContext` 给每个模块都填 `List.of()`
（`FrontendRegistry.java:146`、`FrontendRegistry.java:150`）。模块的**身份**仍然有效——
`DefaultCompileContext.moduleOf` 取包含该文件的最深源根（`DefaultCompileContext.java:101-119`）——
所以 finding 会被归到正确的模块上。缺的是**边**：前端无从知道工程 A 引用了工程 B。

### 4.9 编译捕获必须供出什么

上面那五个输入不是前端该自己编出来的。它们是构建系统早就定好的东西，而编译捕获正是这个
决定可见的地方。在捕获仓里，C# 的命令解析器只认出了五个中的两个：

| 编译输入 | 捕获仓的状态 |
| --- | --- |
| 引用程序集 | **已捕获。** `/reference:` 与 `/r:` 被解析进命令的 classpath（`dotnet_backend.cpp:605-616`），适配器再把它们以 `--sys_include` 发出（`csharp_adapter.cpp:65-72`）。 |
| 预处理器符号 | **有词汇，没有解析。** 选项类存在（`ccap_compdb_map.h:387`），适配器的输出词汇里也有一个 `-D` 槽位（`csharp_adapter.cpp:26`），但命令解析器把 `/define:` 丢在了地上：解析循环对任何既不是引用也不是 analyzer 的参数一律 `continue`（`dotnet_backend.cpp:620-622`）。 |
| 语言版本 | **有词汇，没有解析。** 选项类存在（`ccap_compdb_map.h:382`）；命令行上带着 `/langversion:`；没有东西解析它。 |
| 可空上下文 | **有词汇，没有解析。** 选项类存在（`ccap_compdb_map.h:381`）；没有东西解析它。 |
| 工程引用图 | **完全没捕获。** 没有任何地方收集解决方案里各工程之间的 `<ProjectReference>` 边。 |

这张表就是这项工作的形状：数据在命令行上，选项词汇已经给它起了名字，缺的是解析器里的那个
分支。五个里的三个，是往 `dotnet_backend.cpp` 的一个循环里加几行；第四个（工程图）是新增一项
收集；第五个（前端侧）是把一个捕获到的输入接到 sidecar 请求字段上的接线。

**而接线有一个现成的形状可以照抄。** C# 请求已经为这五个输入各留了一个字段
（`Protocol.cs:284-301`），所以前端的活是从捕获里**填**它们，而不是写死。守住两半一致
的交叉校验，Maven 那条路已经有了：把捕获到的编译与前端实际发起的编译作比较，把差异报出来。
一个捕获看到了、而前端没有送出的引用程序集，正是那种必须响亮的差异。

---

## 5. .NET sink 目录：真正的工作项

### 5.1 形状

机制已经存在。一个带 .NET 专用符号的 family 长这样（[MEASURED]，
`sink-families.yaml:59-85`）：

```yaml
  taint.sources.console:
    patterns:
      - '^java\.io\.Console\.readLine(/\d+)?'
      - '^java\.lang\.System\.console(/\d+)?'
    by_language:
      kotlin:
        - pattern: '^kotlin\.io\.readLine(/\d+)?'
          arg_index: 0
      scala:
        - pattern: '^scala\.io\.StdIn\$\$anon\$1\.readLine(/\d+)?'
          arg_index: 0
```

一个 C# 条目落进同一块：

```yaml
      csharp:
        - pattern: '^System\.Console\.ReadLine(/\d+)?'
          arg_index: 0
```

**`arg_index` 正是 C# 属性规则见效的地方。** 对 `cmd.CommandText = tainted`，
sink 是那次 *setter 调用*，被污染的值是 `set_CommandText/1` 的第 0 个参数——
所以 `arg_index: 0`。

### 5.2 需要 .NET 内容的 family

**已测量。** 带 C# 模式的 family 及各自模式条数：

```
privacy.sinks              261    CWE-215,CWE-226,CWE-359,CWE-489,CWE-497
database-access.sinks      144    CWE-566
xss.sinks                  133    CWE-79,CWE-80,CWE-82,CWE-83,CWE-87,CWE-692
sql.sinks                   87    CWE-89,CWE-564
file-path.sinks             77    CWE-22,CWE-73
weak-crypto.sinks           72    CWE-261,CWE-326
credential.sinks            66    CWE-256,CWE-522,CWE-798
setting-manipulation.sinks  58    CWE-15
resource-injection.sinks    53    CWE-94,CWE-95,CWE-99,CWE-502
header-injection.sinks      45    CWE-93,CWE-113
trust-boundary.sinks        31    CWE-501,CWE-264,CWE-287,CWE-359,CWE-732
deserialization.sinks       27    CWE-502
formula-injection.sinks     19    CWE-1236
log-injection.sinks         16    CWE-117
open-redirect.sinks         16    CWE-601
ssrf.sinks                  16    CWE-918
ldap.sinks                  14    CWE-90,CWE-20,CWE-639
cookie.sinks                13    CWE-113
dos.sinks                   13    CWE-730,CWE-776
xml-injection.sinks         13    CWE-91
code-injection.sinks         9    CWE-94,CWE-95,CWE-494
nosql.sinks                  8    CWE-943,CWE-89
regex.sinks                  8    CWE-1333,CWE-185,CWE-730
command.sinks                7    CWE-78,CWE-77,CWE-88,CWE-93,CWE-114,CWE-147,CWE-494
reflection.sinks             6    CWE-470,CWE-494
xpath.sinks                  5    CWE-643
xxe.sinks                    5    CWE-611
xslt-injection.sinks         3    CWE-494
json-injection.sinks         2    CWE-91
file-inclusion.sinks         1    CWE-94,CWE-98,CWE-494
http-parameter-pollution.sinks 1   CWE-235
xquery.sinks                 1    CWE-643,CWE-652
```

各 family 命中的符号举例：

```
sql.sinks                System.Data.Common.DbCommand.set_CommandText
                         System.Data.IDbCommand.set_CommandText
                         System.Data.SqlClient.SqlCommand.set_CommandText
                         System.Data.Entity.DbSet.SqlQuery
                         System.Data.Linq.DataContext.ExecuteQuery
command.sinks            System.Diagnostics.Process.Start
                         System.Diagnostics.ProcessStartInfo.set_FileName
                         System.Diagnostics.ProcessStartInfo.set_Arguments
                         System.Reflection.Assembly.Load
deserialization.sinks    System.Text.Json.JsonSerializer.Deserialize
                         System.Runtime.Serialization.Formatters.BinaryFormatter
ldap.sinks               System.DirectoryServices.AccountManagement.PrincipalContext
credential.sinks         System.Net.NetworkCredential.<init>
                         System.Security.Cryptography.X509Certificates.X509Certificate
weak-crypto.sinks        System.Security.Cryptography.*
xss.sinks                System.Web.HttpUtility.HtmlEncode
                         System.Web.UI.WebControls.TextBox.set_Text
ssrf.sinks               System.Net.HttpWebRequest
                         System.Net.WebClient
```

.NET 的 API 面是有文档的，所以这个枚举是机械的——这正是它值得**转换**而不是手写的
原因（§5.5）。

### 5.3 属性 setter 带来的后果，展开说

每一个通过属性抵达的 .NET sink 都需要一条 `set_X` 模式。这让 .NET sink 表相对
一个只算方法的朴素计数**大约翻倍**，而它是关于 C# 目录最重要的结构性事实。

**已测量。** 属性访问器不是转换结果里的小众形态，而是**多数**。在已发布的目录里，
仅属性 setter 就占了几百条（总共 1284 条 C# 模式）。单符号条目最大的两类是
`set_CommandText`（三个 owner 类型：抽象的 `System.Data.Common.DbCommand`、
接口 `System.Data.IDbCommand`、具体的 `System.Data.SqlClient.SqlCommand`），
以及 ASP.NET 的 `set_(SelectCommand|DeleteCommand|InsertCommand|UpdateCommand)`。

受影响最大的 family：`sql.sinks`（CommandText、CommandType、ConnectionString）、
`command.sinks`（FileName、Arguments）、`file-path.sinks`（部分重载是属性）、
`xss.sinks`（Text、Value）。

### 5.4 必须先做的那个测量

在写目录内容之前，有一个测量决定了工作的形状：

> **C# 前端是否把 `set_CommandText/1` 作为被调用者签名发出，sink 目录能否匹配
> 到它？**

这是多语言设计文档 [§15.7](../../MULTI-LANGUAGE-IR-DESIGN.md) 里那个"一天的问题"，重述为一个测量。它有两种可能的
答案，而它们导向不同的计划：

- **若是**（属性规则成立）：目录就是"方法模式 + setter 模式"的清单。一套匹配
  机制。干净。
- **若否**：目录需要第二套理解属性的匹配机制，按 `(接收者类型, 属性名)` 而不是
  被调用者签名来匹配。那是 `SinkFamily` 与 `SinkFamilyRegistry` 的改动，而且
  **昂贵得多**。

**这个测量应当最先做**，因为答案会改变第二件事的大小。

### 5.5 目录是转换来的，不是手写的——代价是什么

**已测量。** .NET sink 目录**不是**手写的。它由一个转换脚本
从外部规则包转换而来：脚本读取该包解码后的规则文件，产出一个片段，再由
另一个合并脚本折进 `sink-families.yaml`。手写的基线文件只保留
人写的模式；转换来的模式落在每个 family 的 `by_language: csharp:` 表里。

对着两个 .NET 包量出来的数字：

| 量 | 值 |
| --- | --- |
| 包里的 .NET sink 规则 | 2519 |
| 成功转换的 .NET sink 规则 | 2491（**98.9 %**）|
| .NET sink 缺陷类 | 72 |
| 映射到 Plumb family 的 .NET 缺陷类 | 66 |
| 带 C# 模式的 Plumb family | 36 |
| 已发布目录里的 C# sink 模式 | **1284** |

未映射的 6 个缺陷类共 28 条规则，而且它们在 Plumb 里都没有对应的 family 可落：
三个是 Java 专属概念，两个是代码质量而非安全缺陷，还有一个需要新建 family。

**映射按缺陷类走，不按厂商规则走。** ORM 变体仍然是 `sql.sinks`：NHibernate、
LINQ、Castle ActiveRecord、PartiQL、SubSonic、Hibernate 和 JDO 都落在同一个 family，
因为它们表达的是同一个缺陷。family 是规则作者要输入的东西，所以 family 名必须可
检索；`sql.nhibernate.sinks` 不是。

#### 5.5.1 四种不是"照抄"的转换

转换大体上是机械的，但 .NET 包里有四样东西照抄会出事。每一样都曾产出一条
**匹配不到任何东西**或**匹配到错东西**的规则，现在每一样在转换器里都是一个
写明了规则的具名用例。

**泛型 arity。** 包把泛型类型连同 arity 一起写出来——`DbSet@1`、`ObjectQuery@1`——
而 Plumb 的 TAC 抹掉泛型：被调用者签名是 `owner.method/arity`，owner 不带类型实参。
留着 `@1` 的模式**什么也匹配不到**。这个标记在其他一切之前被剥掉，因为它可以
待在一个本身又位于交替组里的组中（`(DropDownList|ListBox)(For(@1)?)?`），事后再剥
会留下一个空组——一个匹配空串的正则，会悄悄**放宽**规则。

**真名两边的通配。** 包用 `.*Operation` 和 `(HqlBased|Simple|Scalar)Query.*` 表示
"名字以 `Operation` 结尾的每个类"和"名字以 `Query` 开头的每个类"。`.*` 是正则通配，
但它里面的点是**裸**点，而 owner 的规范化会把裸点转义。照抄得到的是
`Model\..*Operation`——一个字面点后跟一个星号——它要求 `Model` 和通配之间有一个
空命名空间段，于是匹配不到任何东西。现在通配先被剥下来再做 owner 规范化，所以它
仍然是通配。

**重复的组。** `set_(Legend)*Url` 和 `lookup(Link)?` 是两种形状，而它们对"组缺失"
那个变体要的答案**正好相反**：

- `lookup(Link)?`——`?` 是零或一，所以名字要么是 `lookup`，要么是 `lookupLink`。
  **两个都是真方法。** 缺失变体必须保留；丢掉它就丢了整个 JNDI lookup sink，
  resource-injection family 不再认 `javax.naming.Context.lookup`。
- `set_(Legend)*Url`——组在**中间**。缺失变体是 `set_Url`，它不是任何 charting
  类的属性。保留它就多出一个没有任何符号拥有的名字：一条看起来像覆盖、实际
  匹配不到东西的规则。

所以规则是**位置相关**的：后缀组保留缺失变体（此时名字是它自己变体的前缀，两个
都是真的），中间组丢弃它（此时缺失改变的是这个名字**是什么**）。

**转义的竖线。** Scala 的运算符方法是用竖线拼的，包里写作 `#(##|&&|\||\|\|)`。
转换器的"丢掉空交替项"那一遍不认识转义，于是把字面 `||` 里的 `|` 当成了交替符
并删掉。结果 `(\#\#|&&|\||\|\)` 根本不是合法正则，于是**整个目录加载失败**：
"Unclosed group near index 63"。每一个 sink family 都停了，对**所有**语言都停。
这不是精度取舍；这是一条规则造成的全面停摆，而且除非有人把每条模式都编译一遍，
否则看不见。

#### 5.5.2 配置门控，以及它为什么不是取值声明

一个转换来的 sink 可能被一个 *taint flag* 门控。这有两种，彼此不等价：

- **缓解**门控（`VALIDATED_*`）说的是"这个值**没有**被校验过"。Plumb 建模了
  sanitizer，所以忽略它会让 Plumb 更窄——安全。
- **取值或配置**门控说的是关于**值**或关于**对象怎么配的**：`SHELL_PROCESS`
  （"这个被污染的值**就是**命令"）、`INSECURE_TYPENAMEHANDLING_SETTING`
  （"这个 mapper 不安全地解析类型名"）。

Plumb 的接收者位置意思是"这个对象带着污点"，这跟上面两种都**不是同一个命题**。
Jackson 那条规则是最锋利的例子：它的门控是 `INSECURE_TYPENAMEHANDLING_SETTING`，
它断言的是关于 mapper **配置**的事。翻译成接收者位置后，Plumb 会对每一个收到
不可信数据的 Jackson 调用报反序列化——不管 `TypeNameHandling` 有没有被打开。
更糟的是，模式 `read(Tree|Value|Values)` 也匹配 `readValue`，于是这条规则**遮蔽**
了那条更窄的规则，并把报告的参数从数据（0）挪到了接收者。

所以在取值或配置门控下的接收者位置被丢弃，而同一门控下的参数位置被保留：位置
声明（"被污染的值落在参数 N 上"）在这次扁平化里存活下来了。

#### 5.5.3 靠什么证明这次转换是对的

三件事，每一件抓一类不同的失败：

1. **目录里每一条模式都能编译。** 编不过的模式会把整个目录一起带下去。这正是
   本该抓住 Scala 那根竖线的测试。
2. **没有 C# 模式带泛型 arity 标记，也没有 C# 模式含转义通配。** 两者都是永不
   触发的规则，而两者在 family 列表里都**看起来像覆盖**。
3. **一条转换来的模式被端到端抵达。** `ProcessStartInfo.set_Arguments` 是一条
   **转换来的** sink（不是手写的），测试把 `startInfo.Arguments = tainted` 推过
   前端、目录和引擎，并要求报出 finding。否则转换 bug 是看不见的：规则在 family
   列表里，扫描报告成功，而什么都没找到。

测试是 `plumb-dfa` 里的 `SinkCatalogueCsharpTest` 与 `CSharpDfaIntegrationTest`。

#### 5.5.4 另一个来源贡献了什么：没有——而这是一个被测量过的事实

另一个外部来源有一份 Plumb 已经在转换的**模型库**
（一个转换脚本读它并产出片段，由同一个合并脚本折进目录）。
**已测量：那份库根本没有 .NET 模型库。**它的 `library/` 树里有 `generic/`（C 与
C++）、`go/`、`java/` 和 `security/`——没有 C#。转换器的默认路径是 `library/java/`，
而已发布的那份片段里 C# 模式**为零**。

所以 C# 目录完全来自规则包，第二个来源不贡献任何 C# 内容。这一点值得明说，而不是
留作一个看起来像缺口的东西：这两个来源不是可以互换的 .NET sink 知识来源，一个
假定"两边都会贡献"的计划，对其中一边就是错的。

### 5.6 对照评测：另一个工具的 checker 清单暴露了什么

另一个工具附带一份 **checker 参考文档**（按 checker 组织的 HTML，[EXTERNAL]），
描述它检测的每一个弱点、针对每种语言。那份文档不是规则包，也不带 API 清单，但它
**是一份弱点分类表**——而分类表正是覆盖评测需要的：它告诉你 C# 上存在哪些缺陷类，
于是"某个 family 没有 C# 模式"就成了一眼看得见的洞，而不是没人注意到的缺失。

#### 5.6.1 测量

参考文档里有 **299 个 checker 段落**；**80** 个适用于 C#，其中 **49** 个是安全
checker。把 49 个逐一映射到承载同一缺陷类的 Plumb family，得到第一轮评测结果：

| | 数量 |
| --- | --- |
| 适用于 C# 的安全 checker | 49 |
| 已被"有 C# 模式的 family"覆盖 | 31 |
| family 存在但**没有** C# 模式 | 5 |
| family 根本不存在 | 3 |
| 不是 taint 规则（配置 / 取值 / 控制流） | 11 |

最后一行和第一行一样重要。像 `CONFIG.ENABLED_DEBUG_MODE` 这样的 checker 是
**配置**规则——"调试模式必须关掉"；`DIVIDE_BY_ZERO` 是取值规则。两者都不是 taint
sink，也无法表达成 taint sink。把它们映射到 sink family 上，会对一段根本没有污点
的代码报出污点发现。

#### 5.6.2 这次评测改动了什么

评测不是一份报告，它改动了目录。那 7 个未覆盖的 checker 逐条对着厂商自己的数据
重新审视，得到的是**三种不同的答案**。

**其中 5 个是真实缺口，现在补上了。**family 存在却没有 C# 模式，是因为外部的
.NET 规则包**根本没有用数据流规则表达这些缺陷类**——它们是*语义*规则、
*结构*规则或*控制流*规则。数据流解析器跳过它们，于是它们从未进入
目录。API 清单可以从这些规则自己的 `<FunctionIdentifier>` 里取回，转换器现在读它了。

读出来之后必须按"这条规则断言的是什么"过滤：

| 规则的断言 | 是 taint sink 吗 | 例子 |
| --- | --- | --- |
| 污点值落在一个参数上 | **是** | "该方法用来自不可信源的输入拼接并执行 SQL 查询" |
| 对象的**配置**不对 | 不是 | "应用被配置为以明文与数据库通信" |
| **算法**本身弱 | 不是 | "该随机数生成器无法抵御密码学攻击" |

第二、三行就是陷阱。*Insecure Transport* 与 *Insecure Randomness* 看上去像污点
弱点——它们有"安全 checker"标签、有 CWE——但厂商自己的描述说，缺陷在于配置，
而不在于流入其中的某个值。把它们转成 taint sink，会对一个字面量连接串报出明文
传输发现，对一段没有污点的代码报出弱随机数发现。

**已测量：571 条没有数据流规则的 .NET 规则里，104 条是取值声明并被转换；467 条
是配置或控制流声明，只计数不转换。**那 104 条补上了 EF Core 的
`FromSqlRaw` / `ExecuteSqlRaw` 一族、`DbBatchCommand.set_CommandText`、
`XmlReaderSettings.<init>` 的构造函数和 Dapper 的查询方法——都是数据流规则漏掉的 API。

**剩下 2 个仍然没覆盖，而且原因是写明的。**`TAINT_ASSERT` 根本不是 taint 规则：它
拿用户断言的"非污点"和算出来的污点做比对——它是一条关于**分析器**的规则，不是
关于程序的，程序里没有东西可找。

`RESOURCE_LEAK` 则是另一回事，而且是被这次评测翻出来的。见
[§5.6.3](#563-评测翻出来的那条-checker--以及-sink-family-看不到的东西)。

#### 5.6.3 评测翻出来的那条 checker —— 以及 sink family 看不到的东西

`RESOURCE_LEAK` **不是 taint 规则**。它在"分配了却没关闭"时触发（CWE-404），
所以它不属于任何 sink family——它是一条 **checker**，而它的"被跟踪类型列表"才是
机制。

而那个列表是**只有 Java 的**。里面每个名字都是 Java 类型，于是：

- C# 的 `new FileStream(...)` **根本不算分配点**，这条规则在 C# 上从不触发；
- 就算触发了，.NET 把关闭叫作 **`Dispose`** 而不是 `close`——一个正确 dispose
  掉的 .NET 资源会被报成泄漏。这是最糟的那种误报：它发生在**正确**的代码上，
  而且发生在程序里的**每一个**资源上。

**这正是一次对照评测的意义所在。**只比 sink **family** 的评测永远找不到它：
这条规则没有 sink family，所以它根本不出现在逐 family 的对比里。它能被找到，
只因为另一个工具的 checker 清单里为 C# 列了一条 `RESOURCE_LEAK` checker，
于是"Plumb 有这条 rule id 的规则，但它对 C# 有效吗？"就成了一个值得问的问题。

两处改动补上了它：

1. 被跟踪类型列表加进了 .NET 资源（`FileStream`、`StreamReader`、
   `SqlConnection`、`NetworkStream`、`XmlReader` 等）——和 Java 列表同一个纪律：
   类型有 `Dispose` 才算资源；
2. `Dispose` 被认作关闭，与 `close` 并列。

已测量：从不 dispose 的 C# `FileStream` 会被报出；同一个资源加上
`Dispose()` 则不会。

#### 5.6.4 手写模式是测出来的，不是凭记忆写的

那 5 个需要**手写**模式（而非转换得来）的缺口，带出了一个问题：API 名字从哪来？
一个指向不存在的 API 的 sink 模式是**死规则**：它在目录里看起来像覆盖，却永远
不会触发。

于是 .NET API 面是**测**出来的，而不是回忆出来的，用的就是 C# 前端自己那份
Roslyn：探针编译一个引用了各 API 的 C# 文件，编译器拒绝的 API，就是前端也解析
不到的 API。测量工具在 `.aethercode/rule-config/apiprobe/`（Roslyn 4.12，即锁定
的版本）。

已测量，而下面每一条都是测量逼出来的修正：

| 模式写的是 | 框架实际是 | 修正 |
| --- | --- | --- |
| `DataContractSerializer.Deserialize` | 方法叫 `ReadObject` | 改名 |
| `Random.NextInt32` / `NextInt64` | `System.Random` 上没有这两个 | 删除 |
| `HttpWebRequest.<init>` | 构造函数不是 public | 删除——protected 构造函数是永远触发不了的规则 |
| `XmlReaderSettings.set_XmlResolver` | 该属性没有 getter | 保留 setter，不保留读取 |
| `System.Net.Http.HttpResponseHeaders` | 类型是 `System.Net.Http.Headers.HttpResponseHeaders` | 改名——owner 必须是**声明**该成员的类型 |

**已测量：37 条手写 C# 模式，37 条被确认。**剩下 6 条指向的 API 不在共享框架里
（`System.Data.SqlClient`、`Microsoft.Data.SqlClient`、`System.DirectoryServices`
——NuGet 包或仅 Windows 的程序集）。它们不是死规则——厂商规则命名的是同一批
API——但探针看不见它们，所以检查会**如实说出来**，而不是声称一个它没有做过的测量。

#### 5.6.5 构造函数这个缺口，以及为什么它不是一个 TAC 层面的问题

对照评测暴露了一个真实的 TAC 缺陷，值得单独说，因为它在其他任何方式下都是
不可见的。

C# 的 `new` 表达式降级成 `NEW` 指令。而 DFA 引擎只有在指令**带 callee 签名**
时才会去查 sink 目录（`TaintedCheckerBase.checkSinkAtNew` 在签名为 null 时直接
返回）。C# 前端原本只发出构造出来的**类型**——于是**每一个构造函数 sink 都是
结构性不可达的**：目录里带着模式，代码是对的，而发现就是缺席，扫描却报告成功。

这和当初为了避开而选择属性 setter 规则的那个失败模式一模一样，只不过发生在一个没人
会看的地方。修法沿用 Kotlin 前端已经在用的契约：C# 的 `NEW` 现在**同时**带
`ctorType` 和 `owner.<init>/arity` callee 签名。

已测量：.NET 规则包里的 321 条构造函数规则中，**314 条命名的是具体类型**——
前端恰好能解析出这些，于是它们现在会触发。**7 条命名的是接口**
（`System.Data.IDbCommand.<init>` 之类），而接口没有构造函数：Roslyn 把
`new SqlCommand(sql)` 解析成 `System.Data.SqlClient.SqlCommand.<init>/1`，所以
接口形式是死规则。转换器把这 7 条展开成它们的具体实现，接口形式同时保留。

#### 5.6.6 结果

| | 之前 | 之后 |
| --- | --- | --- |
| 被 sink family 覆盖的 C# 安全 checker | 31 | **36** |
| 被 checker 覆盖的 C# 安全 checker | 0 | **37**（资源泄漏那条规则） |
| 带 C# 模式的 family | 32 | **36** |
| C# sink 模式 | 1230 | **1284** |
| 仍未覆盖的 checker | 7 | **1**（`TAINT_ASSERT`，一条关于分析器的规则） |

**已测量：49 个 C# 安全 checker 里覆盖了 37 个。**唯一没覆盖的是 `TAINT_ASSERT`，
它拿用户断言的"非污点"和算出来的污点做比对——它是一条关于**分析器**的规则，
不是关于程序的，程序里没有东西可让它找。

---

### 5.7 对照评测翻出来的四个 TAC 缺陷

checker 清单说的是**哪些缺陷类存在**。它没说前端产出的 TAC 能不能表达它们。
一个前端可以覆盖全部 checker，同时产出**陈述错了程序**的 TAC——而这种失败在
"扫描报告成功"的界面下是看不见的。

所以评测没有停在目录这一层。它把 C# 的降低过程与 Java、Kotlin 的降低过程
**逐个构造**对照了一遍，发现四处差异是**缺陷**，而不是简化。

四者的形状是同一个，值得先说一遍：

> **一个降低成"引擎会照字面读"的形状的 C# 构造，就是错的——而目录纠正不了它。**
> 目录说的是**去哪里**找 sink；它对 sink 周围的控制流是不是程序的控制流，一言不发。

| # | 构造 | 降低出来的形状 | 程序实际的样子 |
| --- | --- | --- | --- |
| 1 | `switch` | case 标签被放在**选中它的那次比较之前** | 分支进入 case **体** |
| 2 | 字面量 | 每个字面量都是 `STRING` | `int` 就是 `int` |
| 3 | `break` / `continue` | **被静默丢弃** | 一次跳转 |
| 4 | `for` | 条件的真边指向**增量段** | 指向**循环体** |

#### 5.7.1 switch：标签定义在指向它的分支之前

Java 参考实现把 switch 降成两个阶段：先是整条比较链，然后是各个标签下的 case 体。
C# 的实现把两者**交错**了——标签、比较、体、标签、比较、体。

后果是一条**反向边**。选中第一个 case 的分支指向一个在它**上方**的标签，
于是"真"这条边落回比较本身，而不是落到 case 体：

```
LABEL  case_1        <- 下面的分支指向的是这个
BINOP  t = (mode == 1)
BRANCH t ? case_1 : case_2
```

结果仍然是一个**良构**的 CFG：每个标签都能解析，CFG 构造器照收不误。
它只是语义上错了，而且错在扫描看不见的地方——扫描报告成功。

修法就是 Java 参考实现的两阶段形状。`default:` 分支需要单独处理：它没有比较，
所以它由比较链最后一条假边到达，也由比较链之后的那个跳转到达。少任何一条，
default 体就是不可达的。

**已测量：**比较分支指向一个定义在它**之后**的标签，且 default 体可达。

#### 5.7.2 字面量：switch 在了错误的 kind 上

C# 文件里的每个字面量都变成了 `STRING`——每个数字、每个布尔、每个字符。
原因只有一个词：

```csharp
return token.Kind() switch        // NumericLiteralToken、TrueKeyword……
{
    SyntaxKind.NumericLiteralExpression => ...,   // 永远匹配不上
```

这个 switch 写的是**语法节点**的 kind，却问 **token** 要 kind。两者是不同的词汇：
token 的 kind 是 `NumericLiteralToken`，节点的 kind 是 `NumericLiteralExpression`。
一个 case 都匹配不上，于是每个字面量都走了 `_ =>` 分支，也就是 `STRING`。

这是这组缺陷里最坏的形态，因为常量仍然带着正确的**值**。TAC 看着完全合理，
所有检查值的测试都通过，而丢掉的是常量的 **kind**——而整数范围检查、布尔测试
读的就是它。

**已测量：**`int` 是 `INT`，`long` 是 `LONG`，`double` 是 `DOUBLE`，`float` 是
`FLOAT`，`decimal` 是 `DECIMAL`，`bool` 是 `BOOL`，`char` 是 `CHAR`，`string` 是
`STRING`，`null` 是 `NULL`，而且除非本来就是字符串，没有任何字面量是 `STRING`。

#### 5.7.3 `break` 与 `continue`：被丢弃，不是被降低

`break` 和 `continue` 是跳转，而跳转需要目标。C# 的实现把两者都丢了。

丢弃它们不会让 TAC 变小；它让 TAC 陈述了一段程序**没有**的控制流：

- switch 里被丢掉的 `break` 让 case 体**落穿**进下一个 case，于是数据流引擎
  走了一条程序从不走的路径；
- 循环里被丢掉的 `continue` 跑完循环体**剩下的部分**而不是重启这次迭代，
  于是下一次迭代开头的污点状态是错的。

修法需要一个上下文，因为目标取决于语句所在的位置：

| 语句 | 目标 |
| --- | --- |
| `switch` 里的 `break` | switch 的结束标签 |
| 循环里的 `break` | 循环的结束标签 |
| `while` 里的 `continue` | 条件标签 |
| `for` 里的 `continue` | 增量标签 |
| `foreach` 里的 `continue` | 增量标签 |
| `switch` 里的 `continue` | 外层**循环**的增量——C# 的 switch 自己没有 continue 目标 |

最内层的上下文优先，而且每个循环和 switch 都要保存并恢复上下文，
免得嵌套的那个把标签泄漏到外面。

#### 5.7.4 `for` 循环：条件的边指向了增量段

`for` 的降低把条件的真边接到了**增量**标签上。有了 `continue` 也指向增量，
增量器在那条路径上会跑两次——而且第一次迭代也跑，因为条件的真边把循环体整个跳过了。

Java 参考实现为此准备了四个标签（`cond`、`body`、`update`、`end`），
C# 的实现现在也一样：条件的真边指向**循环体**，只有 `continue` 指向增量段。

#### 5.7.5 这四个缺陷的共同点

每一个都是同一个错误的实例：**假定 TAC 的形状就是程序的形状**。

- switch 假定标签在指令流里的位置是个细节；
- 字面量假定 token kind 和节点 kind 是同一个东西；
- `break` 假定没有体的语句就是没有效果的语句；
- `for` 假定"条件之后的下一件事"就是增量段。

对策不是一张检查清单，而是**对照**。Java 和 Kotlin 的降低里已经写着答案，
所以问题不是"这个对不对？"，而是"C# 的做法和 Java 的做法一样吗？"——
这个问题把两个文件并排读一遍就能回答。

**已测量：**C# 的降低测试从 11 条变成 **32** 条——属性降低的 11 条，加上 switch 的
6 条、字面量 kind 的 10 条、`break`/`continue` 的 5 条——四个缺陷各有一个对着旧实现
会失败的测试。

---

## 6. 计划

### Phase 0 —— 那个测量（先做这个）

搭起最小可能的 C# 通路：一个 sidecar 解析**一个**文件、吐出 TAC，并有一个测试
断言：

1. `cmd.CommandText = tainted;` 产生一条 `CALL`，其 `calleeSig` 为
   `...SqlCommand.set_CommandText/1`；
2. 该 `calleeSig` 能匹配一条 `arg_index: 0` 的 `by_language` 模式；
3. `await` 产生 `SOURCE_LOWERED` 和一个任务函数；
4. `x!` 产生一条 `NULL_CHECK`；
5. `catch (IOException)` 产生一条 `ctorType = System.IO.IOException` 的
   `CATCH_HANDLER`。

**门禁：** 若 (1) 或 (2) 失败，停下，先修属性规则，再写任何目录内容。

#### Phase 0 —— 测量结果

**测量已经做了，答案是"是"。** 属性规则成立：C# 的属性赋值会变成一条
`CALL`，其 callee 签名能被现有目录匹配到，因此**一套匹配机制就够了**，
那条昂贵的第二套机制不需要。

实测事实：

| 问题 | 答案 |
|---|---|
| `cmd.CommandText = tainted` → `CALL`？ | **是。** `calleeSig` 为 `System.Data.Common.DbCommand.set_CommandText/1`。 |
| 目录能匹配到它吗？ | **能。** `sql.sinks` 的 `by_language: csharp:` 模式匹配，`arg_index: 0`。 |
| 端到端会报出 finding 吗？ | **会。** `taint: param:input -> CWE-89,CWE-564`。 |
| 真正的字段赋值 | 仍然是 `FIELDSTORE`。属性/字段的区分来自解析出的符号，绝不来自拼写。 |
| `await` | `SOURCE_LOWERED`，且被 await 的值保留污点。 |
| `x!` | **不是** `NULL_CHECK` —— 见下。 |
| `catch (IOException)` | `ctorType = System.IO.IOException` 的 `CATCH_HANDLER`。 |

实现在 `plumb-frontend/csharp-sidecar/`（.NET 侧）与
`plumb-frontend/src/main/java/org/plumb/tac/frontend/csharp/`（Java 侧）。
协议见 `plumb-frontend/csharp-sidecar/PROTOCOL.md`。

**这次测量改了计划里的一件事。** 计划里的假设——以及第一版实现——把被赋的值
放在了 `args[0]`。这是错的，TAC 自己的调用约定说的正是相反的话：

```
args[0] = 接收者（当调用有接收者时）
args[1..] = 真正的实参
```

引擎是按这个约定读目录里的 `arg_index` 的：写着 `arg_index: 0` 的目录项指的是
**第一个用户实参**，接收者的偏移由引擎自己加。

于是第一版实现产出的 TAC 看起来完全正确、也能被目录匹配上 —— 然后引擎读
`args[0]`，拿到的是*接收者*，那里没有污点，于是**什么都不报**。而扫描照样报告
成功。这恰恰是整套设计要避免的失败模式，而且**没有端到端测试就看不见**：前端
自己的测试全都通过了，因为 TAC 里确实有那条正确的 `CALL`。

**这才是值得留下的结论。** 单做一次"属性 → `CALL`"的测量是不够的。真正有意义的
测量是**属性 → `CALL` → 目录匹配 → finding 报出**，而最后一环正是参数约定的
所在。

测量还逼出了另外两处修正：

- **`x!` 不是 `NULL_CHECK`。** 空值宽恕运算符（null-forgiving operator）告诉的是
  *编译器*"这个值不为空"，它**不插入任何运行时检查**。既然检查在运行时并不存在，
  就没有 `NullReferenceException` 可捕获。发出 `NULL_CHECK` 等于声称一个不存在的
  检查，空指针检查器随后就会抑制掉一个完全真实的 finding。
- **sink 的接收者类型是 `System.Data.Common.DbCommand`**，也就是共享框架里的抽象
  基类。具体 provider（`System.Data.SqlClient`）是 NuGet 包，没还原过这些包的机器
  解析不了具体类型 —— 而需要具体类型的测试会因为与降级无关的原因失败。基类声明了
  同一个 `CommandText` 属性，所以 setter 的签名（也就是目录要匹配的东西）完全相同。

### Phase 1 —— IR 契约改动

§4.5 的第 1–7 项与第 14 项。这些改动都很小、是增量的，且每一项都配一个契约
测试：

- `SourceLanguage.CSHARP` 与更新后的顺序测试；
- `TacType.Kind.DECIMAL` 与 `valueType`，并有一个测试断言该标志被排除在
  `TacVar` 身份之外；
- `TacOperand.Const.Kind.DECIMAL`。

**门禁：** `MultiLanguageIrContractTest` 在新顺序下带着新常量通过，且现有的
Java/Kotlin/Scala finding **保持不变**（每个既有工厂的可空性默认值仍是 `true`）。

### Phase 2 —— sidecar

- Roslyn sidecar：解析 → 语义模型 → TAC 发出。
- 整个扫描期间长驻（项目级 `CSharpCompilation`），这样跨文件引用才能解析。
- 协议：stdio 上的带长度前缀帧，每文件一个请求、每文件一个响应，另加一条
  `FunctionProblem` 通道用于结构化问题（[MEASURED]：`TacFrontend.StructuredParseResult`
  已经定义了这个契约）。
- **全函数**：前端不得对畸形输入抛异常。一个编译不过的 C# 文件产出编译器的诊断
  作为问题，以及能解析出来的部分。

### Phase 3 —— 目录

为每个有 .NET 对应物的 family 补上 .NET 的 `by_language` 表（§5.2），带上
`set_X` setter 模式（§5.3）。

**已测量——这一阶段做完了，做法是转换而不是手写。**目录由 .NET 规则包生成并合进
`sink-families.yaml`：**36 个 family 共 1284 条 C# 模式**，覆盖 72 个 .NET 缺陷类
中的 66 个（§5.5）。四种不是照抄的转换——arity、通配、重复组、配置门控——见
§5.5.1 与 §5.5.2。对照评测补上的那 7 个缺口见 §5.6。

**门禁：** 每一个有 .NET 符号的 family 都有一份 .NET sink 表，且有一个测试断言
每个条目在 C# 中可达、在其他语言中不可达——与多语言设计文档 [§15.6](../../MULTI-LANGUAGE-IR-DESIGN.md) 规定的门禁
相同。**已满足：**语言过滤在 `SinkCatalogueCsharpTest`（I3）里断言，而全目录
编译检查（I1）是"转换 bug 不可能通过"的那条断言。

### Phase 4 —— 规则与验收标准

多语言设计文档 [§6](../../MULTI-LANGUAGE-IR-DESIGN.md) 的验收标准原样适用，而对 C# 最要紧的是**漏报**那一侧：每一
个 Java finding 类别都必须有一个可检测的 C# 对应物。

**C# 专属的验收测试**是值得最先写的那一个，它是 Java 侧 Juliet 扫描的对应物：

| C# 构造 | 期望的 finding |
| --- | --- |
| `cmd.CommandText = request.QueryString["id"]` | SQL 注入（CWE-89） |
| `Process.Start("cmd.exe", userInput)` | 命令注入（CWE-78） |
| `File.ReadAllText(Path.Combine(root, userInput))` | 路径遍历（CWE-22） |
| `Response.Write(userInput)` | 反射型 XSS（CWE-79） |
| `new SqlCommand(sql)` 里是拼接的字符串 | SQL 注入 |

第一行就是证明属性规则的那一行。

---

## 6.5 C# 的数据流缺口：.NET 的每一种拼写都被漏掉了

§5.7 讲的是 **TAC**。这一节讲的是 TAC **之后**的事：引擎的数据流。而这里的发现和 §5.7 那些是同
一个形状——前端产出了正确的 TAC，引擎仍然给出了错误的答案。

### 6.5.1 测量结果

`SourceLanguage.CSHARP` 在 `plumb-dfa/src/main/java` 里**一处都没出现过**。每一个 C# callee 到
达引擎时都是 `TaintBehaviour.UNKNOWN`，而引擎对未知 callee 的保守默认是**保留污点**。

对没人建模过的 API，这个默认是对的。但它同时在两个方向上都是错的：

| .NET 调用 | 它实际做什么 | 引擎实际做的 |
|---|---|---|
| `s.Trim()`、`s.Substring()`、`s.Split()`、`s.Replace()` | 返回输入的一部分 | **把污点丢了**——一个漏报 |
| `int.Parse(s)`、`double.Parse(s)` | 返回一个数字，不是那段文本 | **把污点留着**——一个误报 |

两个方向都是测出来的，不是推出来的。

### 6.5.2 漏报：方法名的比较是大小写敏感的

引擎靠**方法名**识别污点透传调用，用的是一族帮助函数（`isTrimLike`、`isSubstringLike`、
`isCaseLike`、`isSplitLike`、`isValueOfLike`、`isAppendLike`、`isFormatterLike`、
`isCharByteLike`）。它们每一个都拿 Java 的拼法做**大小写敏感**的比较：

```java
private static boolean isTrimLike(String calleeSig) {
  String m = methodNameOf(calleeSig);
  return "trim".equals(m) || "strip".equals(m) || ... ;
}
```

Java 写 `s.trim()`；.NET 写 `s.Trim()`。`methodNameOf` 原样返回方法名，所以 `"trim".equals("Trim")`
是 `false`，于是**每一个** .NET 字符串操作都没有被识别。

受影响的调用，以及各自付出的代价：

| 帮助函数 | Java 拼法 | 漏掉的 .NET 拼法 | 代价 |
|---|---|---|---|
| `isTrimLike` | `trim` | `Trim` | 漏报 |
| `isSubstringLike` | `substring` | `Substring` | 漏报 |
| `isSplitLike` | `split` | `Split` | 漏报 |
| `isCaseLike` | `replace` | `Replace` | 漏报 |
| `isCaseLike` | `toUpperCase` | `ToUpper` | 漏报 |
| `isValueOfLike` | `toString` | `ToString` | 漏报 |
| `isAppendLike` | `append` | `Append` | 漏报 |
| `isFormatterLike` | `format`、`join` | `Format`、`Join` | 漏报 |
| `isCharByteLike` | `toCharArray`、`getBytes` | `ToCharArray`、`GetBytes` | 漏报 |

**漏报**是代价更高的那一种：本来该报的 finding 从来没被报出来，而扫描对一个把用户输入流进命令
的程序报告了「干净」。

修法是一次比较：`equalsIgnoreCase`。这些帮助函数是按**这个调用做了什么**来识别的，而 `TRIM`、
`Trim`、`trim` 在任何拥有这个操作的语言里都是同一个操作——所以大小写不敏感的比较不可能让一个
无关的方法误匹配。

### 6.5.3 误报：.NET 的解析器没有被当成 sanitizer

镜像的一面。`isSanitizer` 按 Java 的 **owner 前缀**列出数值解析器（`java.lang.Integer.parseInt/1`），
所以 .NET 的 `System.Int32.Parse/1` 从来没匹配上。于是，一段把不可信输入解析成 `int` 的 C# 代码，
会把解析出来的值报成仍然带污点。

owner 前缀没法靠「大小写不敏感」解决——`java.lang.Integer` 和 `System.Int32` 是同一个操作的两种
拼法。所以 .NET 的解析器按**它们自己的 owner** 列出，这也正是语义表的做法。

### 6.5.4 语义表

两处修法需要的是 Java 表和 Scala 表早就有的同一件东西：**一份关于标准库做了什么的、按语言分开
的陈述**。C# 一份都没有。

`LanguageSemanticsRegistry.csharpSemantics()` 现在有 **约 80 条实测条目**，其中每一个前缀都是
**测出来的**：把 C# 前端跑在一份 .NET 字符串与 IO 操作的探针上。测量很重要，因为 .NET 有几个
操作的拼法和源码字面给出的不一样：

| 源码 | 实测的 callee key |
|---|---|
| `s.Length` | `System.String.get_Length/0`——**属性是一次方法调用** |
| `s + "x"` | `System.String.Concat/2`——`+` 运算符是一次调用 |
| `string.Format("{0}", x)` | `System.String.Format/2`——元数要算上格式串 |
| `s.Trim()` | `System.String.Trim/0` |

一张只按源码字面写的表会写上 `System.String.Length`——一个前端永远不产出的 key——每一条都会是死
的。

### 6.5.5 这一节换来的规则

> **命名约定不是行为。** 当引擎靠名字识别一个调用时，这个比较必须能活过它所分析的每一种语言的
> 命名约定——否则识别会静默失败，而这种失败的方向恰好是代价最高的那个。

测量的形状也可以推广：

> **前端自己的测试不够。** C# 前端为 `s.Trim()` 产出了完美的 `CALL`，前端自己的测试也全过了。缺陷
> 在那份 TAC 的**消费方**，在两个模块之外。一个断言「IR 里含有正确指令」的测试，对「finding 有没有
> 被报出来」什么也没说。

---

## 7. 风险与各自的缓解措施

| 风险 | 后果 | 缓解 |
| --- | --- | --- |
| 属性赋值被降级成 `FIELDSTORE` | C# SQL 注入这一类 sink **在结构上不可见** | `CALL` 规则（§4.1）+ Phase 0 门禁 |
| `hasNonNullTypeSystem()` 被设成 C# 的 `true` | 空指针解引用 finding 被静默压制——一个**漏报** | C# 的可空标注是警告不是错误；保持 `false`（§4.3.1） |
| 逐文件的 sidecar | 跨文件引用解析不了 → 跨语言调用图产出零条边（[§14](../../MULTI-LANGUAGE-IR-DESIGN.md) 的失败） | 每个扫描一个长驻 sidecar，项目级编译 |
| `SourceLanguage` 枚举被重排 | 每个已缓存的 IR 程序失效 | 只追加；契约测试钉住顺序 |
| `valueType` 被算进 `TacVar` 身份 | 污点在被精化的变量上被静默丢弃 | 沿用既有规则：身份只有 `name` + `isTemp` |
| C# 的 `decimal` 被映射成 `double` | 数值区间分析推理一个不可能存在的域 | `Kind.DECIMAL` 作为独立种类 |
| .NET 目录写了，但 sink family 不触发 | 扫描报告成功却什么也没找到 | Phase 4 的验收测试，逐构造 |
| .NET SDK 是硬依赖 | 没有它的机器上扫不了 C# | 与 Coverity 同样的立场（[EXTERNAL]）；把缺失的工具链报成**问题**，而不是"没有 finding" |

最后一行值得强调。**缺失的工具链必须是响亮的失败。** 一次什么都没分析、什么
都没报告的扫描，与一个干净项目无法区分——这正是 `LanguageFilter.parse` 的
javadoc 已经为一个未知语言名点出的失败模式：*"`--language kotlin` 里的一个拼写
错误否则会产出一场什么都不分析、什么都不报告的扫描，与一个干净项目无法
区分"*（[MEASURED]：
`plumb-dfa/.../config/LanguageFilter.java:91-94`）。

C# 前端必须遵守同样的纪律：如果 sidecar 起不来，扫描要报告"未能分析 C#"，
并以非零码退出。

---

## 8. 本文不主张什么

- **不主张仅凭成本理由就值得加 C#。** §3 显示目录是数百条，而不是 7 条或
  83 条。那是一笔真实的预算。
- **不主张引擎改动是零。** *引擎*改动是零；*IR 契约*改动是三处，其中一处
  （属性规则）决定整件事成不成。
- **不主张属性规则已经实现。** §4.1 对着代码回答了多语言设计文档留下的那个
  问题；实现是 Phase 0。
- **不主张 .NET 目录是完整的。** §5.2 是需要内容的 family 的一份草图，不是
  内容本身。
- **不主张外部事实在其来源之外仍然当前。** 厂商陈述已就地注明；读者应当核对
  自己实际安装的那个发行版的厂商文档。

---

## 附录 A：证据汇总

**[MEASURED]** —— 从本仓库读出：

| 事实 | 位置 |
| --- | --- |
| `SourceLanguage` 为 `JAVA, KOTLIN, SCALA, GROOVY, UNKNOWN`，按名字序列化 | `plumb-tac/src/main/java/org/plumb/tac/ir/SourceLanguage.java:45` |
| `hasNonNullTypeSystem()` 仅 Kotlin | `SourceLanguage.java:81` |
| `supportsFullDataflow()` 为 Java/Kotlin/Scala | `SourceLanguage.java:86` |
| IR 契约测试钉住枚举顺序 | `plumb-tac/src/test/java/org/plumb/tac/ir/MultiLanguageIrContractTest.java:99` |
| `TacType` 有 `nullable` 标志；每个既有工厂都可空 | `plumb-tac/src/main/java/org/plumb/tac/ir/TacType.java:113` |
| `TacType.Kind` 有 14 种，没有 `DECIMAL` | `plumb-tac/src/main/java/org/plumb/tac/ir/TacType.java:40` |
| `TacType` 没有值/引用之分 | `TacType.java:197-209` |
| `TacVar` 身份是 `name` + `isTemp`，类型被排除 | `plumb-tac/src/main/java/org/plumb/tac/ir/TacOperand.java:95` |
| `Const.Kind` 没有 `DECIMAL` | `plumb-tac/src/main/java/org/plumb/tac/ir/TacOperand.java:109` |
| `TacInstruction.Kind` 含 `NULL_CHECK`、`HAVOC`、`UNINTERPRETED`、`CATCH_HANDLER` | `plumb-tac/src/main/java/org/plumb/tac/ir/TacInstruction.java:44-153` |
| `FIELDSTORE` 携带 `fieldName`，不是 `calleeSig` | `plumb-tac/src/main/java/org/plumb/tac/ir/TacInstruction.java:173` |
| 规范被调用者键是 `owner.method/arity`；`$` 后缀规则已存在 | `plumb-tac/src/main/java/org/plumb/tac/ir/CalleeKeyNormalizer.java:45` |
| sink 目录按被调用者签名匹配 | `plumb-dfa/src/main/java/org/plumb/dfa/config/SinkFamily.java:661` |
| 注册表按被调用者签名查找 family | `plumb-dfa/src/main/java/org/plumb/dfa/config/SinkFamilyRegistry.java:88` |
| `by_language` 携带带 `arg_index` 的逐语言模式 | `plumb-dfa/src/main/resources/sink-families.yaml:59-85` |
| `LanguageFilter` 收窄逐语言表 | `plumb-dfa/src/main/java/org/plumb/dfa/config/LanguageFilter.java:190` |
| 未知语言是错误，不是静默跳过 | `plumb-dfa/src/main/java/org/plumb/dfa/config/LanguageFilter.java:100` |
| 引擎的指令遍历专门区分 `CALL` | `plumb-dfa/src/main/java/org/plumb/dfa/engine/DfaEngine.java:1976` |
| 跨函数边来自 `CALL` 指令 | `plumb-dfa/src/main/java/org/plumb/dfa/engine/DfaEngine.java:1215` |
| `TacFrontend` 是唯一扩展点；不允许语言专用 opcode | `plumb-tac/src/main/java/org/plumb/tac/frontend/TacFrontend.java` |
| 结构化问题通道已存在 | `plumb-tac/src/main/java/org/plumb/tac/frontend/TacFrontend.java`（`StructuredParseResult`） |
| Scala 前端通过编译器插件 phase 在进程内运行 | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacFrontend.java` |

**[EXTERNAL]** —— 厂商文档与公开文章：

| 事实 | 来源 |
| --- | --- |
| Coverity 的 IR 平台无关、从源码构建，含 CFG + 调用图 + 符号表 + 类型 | 厂商对四个分析阶段的技术说明 |
| "C# 支持依赖 Coverity Analysis 自带的 .NET 10 运行时" | 厂商捕获文档，"Build options by language" |
| C#、VB 在"使用构建捕获"一列；解释型语言有无构建的快捷方式 | 同一张表 |
| Coverity 的构建捕获包裹一次真实构建（`cov-build`），分析读中间目录 | 厂商用户文档 |
| "SCA 在通用中间语言（CIL）上工作……包括 C# 和 VB .NET" | 厂商用户指南，"Translating .NET Source Code" |
| "一个 C# 文件首先需要被编译成 debug 的 .DLL 或 .EXE，然后那个 .NET 二进制文件被 .NET SDK 工具 `ildasm.exe` 反汇编成 MSIL" | 厂商社区帖，与用户指南相互印证 |
| "自 Fortify 16.20 起，SCA 已支持直接扫描 .Net C#/ASP/VB 源码" | 同帖 |
| "Fortify SCA 在一个 Java 虚拟机（JVM）里运行扫描" | 厂商用户指南，Visual Studio 扩展 |
| `sourceanalyzer -b <id> msbuild /t:rebuild Sample.sln` 是 .NET 翻译命令 | 厂商用户指南，"Visual Studio and MSBuild Project Translation Command-Line Syntax" |
| 没有对应 `.pdb` 的 `.dll` 会被忽略 | 厂商用户指南 |
| JVM→.NET 翻译层的方向是反的 | [MULTI-LANGUAGE-IR-DESIGN.md](../../MULTI-LANGUAGE-IR-DESIGN.md) §15.7 |
