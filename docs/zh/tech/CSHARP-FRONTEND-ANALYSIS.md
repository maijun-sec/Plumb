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

最后一行决定了 C# 能否产出 finding，而它正是多语言设计文档留作"一个有答案的
一天问题"的那一项（[MULTI-LANGUAGE-IR-DESIGN.md §15.7](../../MULTI-LANGUAGE-IR-DESIGN.md)）。§4 对着代码给出了答案。

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

Java 已有、且有 .NET 对应物的 family：

```
taint.sources.console    System.Console.ReadLine
                         System.Console.In.ReadLine
taint.sources.env        System.Environment.GetEnvironmentVariable
taint.sources.network    System.Net.WebClient.DownloadString
                         System.Net.Http.HttpClient (response)
taint.sources.request    HttpRequest.QueryString        (ASP.NET)
                         HttpRequest.Form
                         HttpRequest.Params
                         HttpRequest.Cookies
sql.sinks                System.Data.SqlClient.SqlCommand.set_CommandText
                         System.Data.SqlClient.SqlCommand.ExecuteNonQuery
                         System.Data.SqlClient.SqlCommand.ExecuteReader
                         System.Data.SqlClient.SqlCommand.ExecuteScalar
cmd.sinks                System.Diagnostics.Process.Start
                         System.Diagnostics.ProcessStartInfo.set_FileName
                         System.Diagnostics.ProcessStartInfo.set_Arguments
path.sinks               System.IO.File.*
                         System.IO.Directory.*
                         System.IO.Path.*
xss.sinks                System.Web.UI.HtmlTextWriter
                         Response.Write                (ASP.NET)
                         System.Web.UI.Literal.Text
deserialization.sinks    System.Text.Json.JsonSerializer.Deserialize
                         BinaryFormatter.Deserialize
ldap.sinks               System.DirectoryServices.*
crypto.sinks             System.Security.Cryptography.*
weak-crypto.sinks        System.Security.Cryptography.*
xxe.sinks                System.Xml.XmlDocument.Load
                         System.Xml.XmlReaderSettings
log.sinks                System.Diagnostics.Trace.WriteLine
                         ILogger.Log
```

**完整**清单比这更长；要点是它是 .NET 标准库的**机械枚举**，不是一个研究项目。
.NET 的 API 面是有文档的，而 family 已经定义好了。

### 5.3 属性 setter 带来的后果，展开说

每一个通过属性抵达的 .NET sink 都需要一条 `set_X` 模式。这让 .NET sink 表相对
一个只算方法的朴素计数**大约翻倍**，而它是关于 C# 目录最重要的结构性事实。

受影响最大的 family：`sql.sinks`（CommandText、CommandType、ConnectionString）、
`cmd.sinks`（FileName、Arguments）、`path.sinks`（部分重载是属性）、
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

**门禁：** 每一个有 .NET 符号的 family 都有一份 .NET sink 表，且有一个测试断言
每个条目在 C# 中可达、在其他语言中不可达——与多语言设计文档 [§15.6](../../MULTI-LANGUAGE-IR-DESIGN.md) 规定的门禁
相同。

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
