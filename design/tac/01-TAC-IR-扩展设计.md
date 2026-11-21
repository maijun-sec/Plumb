# TAC IR 扩展设计（IR v2）

> 本文是**设计**，不是实现。所有论断都带 `file:line` 证据；证据取自仓库
> `D:\work\workspace\idea\engine\Plumb`，HEAD = `2c15f0e`。
> 上游依据：`D:\work\tmp\audit\12-total-report.md`（全语言特性丢失审计总报告，505 条丢弃 / 279 条静默）。
> 配套：`02-覆盖矩阵与不可支持项.md`（逐构念族的表达力矩阵 + 确实无法支持的部分及理由）。

> ## 立场：TAC 应当**完整支持**全部语言的全部特性
>
> 本设计的默认姿态**不是**「保守降级」，而是「**把每个构念真正降级出来**」。
> `IrPrecision.SOURCE_DEGRADED` 与 `TacDrop` **不是兜底通道**，而是「**确实无法静态表达**」时的记账通道：
> 一个构念如果能被完整表达，它**不应该**产生 drop、也不应该降级精度。
>
> 哪些构念确实无法完整支持、理由是什么 ⇒ 见 `02-覆盖矩阵与不可支持项.md` §3。
> 结论预告：真正无法支持的只有 **4 类根因（运行时决定 / 求解器能力 / 并发调度 / 无运行时对应物）**，
> **没有一条是「IR 表达不了」**。

---

## 0. 先读这一节：四条硬约束

设计前必须先承认仓库里已经写死的四条约束。任何违反它们的「设计」都是错的。

### 0.1 不得新增语言专用 opcode（仓库明文禁止）

`plumb-tac/src/main/java/org/plumb/tac/frontend/TacFrontend.java:50-57`：

> It must not add language-specific TAC opcodes. The TAC is a multi-language contract; a frontend
> that needs a new shape (a branch chain, a call, a move) must express it with the existing
> instruction kinds. `TacInstruction.Kind#NULL_CHECK` is the one opcode added for multi-language
> work, and it is language-neutral by construction.

**推论**：任何新能力只能是两种形状——
- **(a) 语言中立的 opcode**（对所有语言含义一致，如 `NULL_CHECK`）；
- **(b) 挂在已有节点上的元数据字段**（消费方按需读取，不读就零影响）。

不存在第三种形状。「给 PHP 加一个 `PHP_TRY`」永远不是答案。

### 0.2 爆炸半径已经量过（本次实测，2026-11 复核）

在 288 个生产 Java 文件（`plumb-dfa` / `plumb-tac` / `plumb-frontend` 的 `src/main/java`）里实测。
**枚举常量各自算一遍**——三类枚举的分派面完全不同，混在一起数会得出错误结论：

| 枚举 | 既有常量数 | 分派形态 | 处数 | 加一个常量会发生什么 |
|---|---:|---|---:|---|
| `TacOperand.TacVar` / `TacOperand.Const` | — | `instanceof` | **343 行 / 345 处**（65 文件）+ **66 行**（31 文件） | 共 **411 处**静默失配 ⇒ 新 operand 变体**不可加** |
| `TacInstruction.Kind` | **22** | `switch (ins.kind)` | **28 处** | 27 处带 `default`（**静默跳过**）；1 处**穷尽无 `default`**（`TacInstruction.java:465`，21 个 case）⇒ **编译期报错** |
| `TacInstruction.Kind` | 22 | `ins.kind ==/!= TacInstruction.Kind.X` | **138 处**（62 文件） | 比较式**不受 `default` 保护**，每处都要人工判断 ⇒ 真实成本 **28 + 138 = 166 处** |
| `TacType.Kind` | **15** | `switch (t.kind())` | **9 处**（8 文件）+ `TypeSet.java:200` = **10 处** | 10 处**全部带 `default`** ⇒ 静默跳过 |
| `TacType.Kind` | 15 | IR 自身 `switch (kind)` | **3 处**（`TacType.java:184`/`:209`/`:216`） | `:184 displayName()` **穷尽 15 个 case、无 `default`** ⇒ **编译期报错**（好）；`:209`/`:216` 带 `default` |
| `TacType.Kind` | 15 | `.kind == TacType.Kind.X` | **0 处** | — |
| `TacOperand.Const.Kind` | 7 | `switch (c.kind())` | **2 处** | `TaintedCheckerBase.java:2126` **穷尽无 `default`** ⇒ **编译期报错**；`LiteralBooleanConditionChecker.java:97` 带 `default` |
| `TacAccessPath.Selector` | 6 | `instanceof` / `switch` | **0 处** | 只有 3 个方法以 `Selector` 为参数（`AccessPathBuilder.java:209`、`:251`）⇒ 加变体几乎零成本 |

**三个「响亮失败」点**（加常量会**编译不过**，不是静默走偏）：
`TacInstruction.java:465`（instruction Kind 穷尽 switch）、`TacType.java:184`（类型 Kind 穷尽 switch）、
`TaintedCheckerBase.java:2126`（常量 Kind 穷尽 switch）。
⇒ 这三处是**门禁**：任何新增枚举常量都会在编译期被抓住，**不需要靠人肉复查**。

**注意另有一个第三方枚举**：`TaintedCheckerBase.java:4073` 的 `switch (lit.kind)` 切的是
`StaticFieldResolver.LiteralValue.Kind`，**不是** `TacOperand.Const.Kind`——
按 `Const.Kind` 数会把它误算进去。

⇒ **形状选择结论**：

| 想要的形状 | 成本 | 判定 |
|---|---|---|
| 新增 operand 变体 | **411 处** `instanceof` | **否决** |
| 新增 instruction Kind | **166 处**（28 switch + 138 比较式） | 谨慎；仅当该形状是**语言中立**的 |
| 新增 `TacType.Kind` | **13 处**（10 switch + 3 IR 自身），其中 1 处编译期报错 | 谨慎；优先走 `domain` 等**可选字段**而不是新 Kind |
| **新增元数据字段**（新类 + 可选字段） | **0 处**（消费方按需读） | **首选** |

本文的 IR-A ~ IR-H 全部走「元数据字段」路线，唯一的例外是 IR-C 的守卫（它是 `BRANCH` 的可选字段，不是新 opcode）。

### 0.3 序列化兼容：只能加，不能改

`TacType.java:26-29`、`TacInstruction.java:32-35`、`TacFunction.java:29-32` 三处同一句约定：

> Field additions are backwards compatible; renaming or removing fields needs a serialVersionUID bump.

`IrPrecision.java:24-26` 额外约定：

> Enum constants are serialised by name, so ADDING a constant at the end is backwards compatible.
> Reordering or renaming a constant is NOT.

⇒ 新字段一律**追加在构造器参数表末尾**（`TacFunction.java:474-477` 明确写了这个理由：插在中间会静默改变重载绑定）；
新枚举常量一律**追加在末尾**（因此 `SOURCE_DEGRADED` 不能插在 `SOURCE_LOWERED` 后面，只能追加在末尾——枚举的**声明顺序从此不再是语义**，判断一律走查询方法）。

### 0.4 方向性规则：新增信息只能压制误报，不能证明安全

仓库里已有三处独立写下的同一条规则：

- `IrPrecision.java:42-56`：「Precision may be used to suppress a false positive. It must never be used to prove that code is safe.」
- `TacFunction.java:187-189`（synthetic 字段）：「This may SUPPRESS a finding about a synthetic method. It must never be used to PROVE that a call is safe.」
- `TacProgram.java:57-60`（FunctionProblem）：「It must never be reported as a finding, and it must never be used to suppress one either — it means the analysis is less complete, not more.」

⇒ 本设计里每一个新增字段都要回答一个问题：**它只可能让告警变少吗？**
如果一个新增字段能被用来「证明某个值安全」，那它就越界了，必须改成别的形状。

### 0.5 每个新增字段必须有生产消费方（否则等于没加）

审计 P0-4 的实测结论：`IrPrecision` 在生产代码里的**读取点只有一个**——
`plumb-dfa/src/main/java/org/plumb/dfa/engine/ModelCoverageComputer.java:133`（`fn.irPrecision() == IrPrecision.SOURCE_FULL`）；
`ScannedOrigin.java:81` 只是把它打印出来。
其余 120 处 `IrPrecision` 命中全是 import / javadoc / 定义。

⇒ **验收标准**：每个新增字段，必须在设计里点名**一个生产代码里的读取点**（类名 + 方法名 + 行号）。
点不出来的字段，不进本设计。

### 0.6 既有 IR 类型必须复用，不要重造

设计前先把 `plumb-tac/src/main/java/org/plumb/tac/ir/` 里**已经存在的类型**列一遍——
本设计已经踩过一次坑：`TacAccessPath`（398 行）早就在那里，而最初的 12 份文档里**一次都没提到它**。

| 既有类型 | 位置 | 它已经能表达什么 | 本设计对它的处置 |
|---|---|---|---|
| `TacAccessPath` | `ir/TacAccessPath.java`（398 行） | 「值住在哪里」的统一词汇：`Selector` = `Reference`/`Dereference`/`ArrayIndex`/`MapKey`/`MapKeySet`/`LazyMapKey`；`root` + 选择器链；`aliasBarrier`（别名屏障） | **复用**。见下方「两条路径不要混」 |
| `SourceLanguage` | `ir/SourceLanguage.java`（299 行） | 12 个语言常量（`JAVA`/`KOTLIN`/`SCALA`/`GROOVY`/`CSHARP`/`GO`/`JAVASCRIPT`/`TYPESCRIPT`/`ARKTS`/`PYTHON3`/`PHP`/`UNKNOWN`）+ 3 个能力查询（`hasNonNullTypeSystem()`/`supportsFullDataflow()`/`hasKeyedContainers()`） | **复用**：IR-H 的「引擎按 `sourceLanguage` 查规则」就锚在这里 |
| `TryRegion` | `ir/TacFunction.java`（嵌套类） | `finallyLabel`/`catchLabels` | IR-F 在**这个类上加字段**，不是新类 |
| `IrPrecision` | `ir/IrPrecision.java` | 精度档位 + 方向性规则 | IR-A 在**枚举末尾追加** |
| `TacAccessPath.Selector` | 同上 | 6 个变体 | **0 处** `instanceof`/穷尽 switch ⇒ 加变体几乎零成本 |

**两条「路径」不要混**（这是最容易搞错的一处）：

| | `TacAccessPath` | IR-AA 的 `TacBinding.path` |
|---|---|---|
| 谁产出 | **引擎**从指令流推导（`AccessPathBuilder.mapPaths`/`keySelector`） | **前端**在模式匹配时给出 |
| 回答什么 | 「这个污点落在容器的哪个位置」 | 「这个绑定变量取的是被匹配值的哪个槽」 |
| 形态 | `root` + `Selector` 链（可嵌套、可通配） | 单个槽位（`_1`/`$1`/字段名/`""`=整体） |

⇒ **不要**把 IR-AA 的 `path` 做成 `TacAccessPath`：
`TacAccessPath` 的 `root` 是**引擎侧的污点对象标签**（`AbstractObject.Obj.label()`），
不是前端的变量名；把它塞进 IR 会让前端去构造引擎的内部词汇，方向反了。
IR-AA 的 `path` 保持**字符串槽位描述**即可，两者在 09 的引擎消费层汇合。

⇒ **新增规则**：任何提案在写「数据形状」之前，先确认
`plumb-tac/src/main/java/org/plumb/tac/ir/` 下**没有已经能表达它的类型**；
有 ⇒ 复用或**加字段**，不新建平行概念。

---

## 1. 审计的 505 条丢弃，落在 IR 上的哪几类

审计总表（`12-total-report.md` §2.1）：受检 1447、丢弃 505（34.9%）、其中**静默 279**（19.3%）。

把 505 条按「要修它，缺的是 IR 能力还是前端代码」重排：

| IR 缺口 | 对应审计条目 | 量级 | 只改前端够吗 |
|---|---|---:|---|
| **精度无法表达「语义有损」** | P0-4 | 全局 | ❌ 缺 IR 档位（IR-A） |
| **丢弃没有记录处** | P0-1 / P0-6 / 全部 279 条静默 | 279 条 | ❌ 缺 IR 通道（IR-B） |
| **路径条件无法从 CFG 推出** | P0-2 全部（控制流建错 ⇒ 守卫错） | 9 面 | ❌ 缺结构化守卫（IR-C） |
| **数值宽度/精度无处承载** | Cwe191 / Cwe190 系列、Z3 求解 | 跨语言 | ❌ 缺值域（IR-D） |
| **成员身份缺失（owner / 成员种类）** | P0-1（TS/ArkTS/C#/Kotlin 属性与访问器） | 6 面 | ❌ 缺声明元数据（IR-E） |
| **异常区域结构在线上为空** | P0-3（`tryRegions` 硬编码 `List.of()`） | 6 面 | ⚠️ IR 已有，**缺 wire 字段**（IR-F + §2 wire） |
| **跳转目标无语义** | P0-2（switch / break / continue / 循环 else） | 9 面 | ❌ 缺标签种类（IR-G） |
| **动态语言的运行时规则无处声明** | PHP/TS/Python 的真值性、强制转换 | 4 面 | ❌ 缺语义开关（IR-H） |

**一句话**：审计里 505 条丢弃，**没有一条能靠「前端照着写」单独修好**——它们缺的都是 IR 表达能力。
这就是为什么必须先设计 IR。

---

## 2. IR 提案

每个提案统一给出：① 动机（审计条目 + 代码证据）；② 数据形状；③ 为什么是这个形状（对照 §0.2 的爆炸半径）；④ 数据流友好性；⑤ **Z3 友好性**；⑥ 生产消费方（点名到行号）；⑦ 序列化影响；⑧ wire 影响；⑨ 风险。

---

### IR-A 精度等级：新增 `SOURCE_DEGRADED`

#### 动机

`IrPrecision` 现有四档（`IrPrecision.java:58-96`），其 javadoc 定义：

- `SOURCE_FULL`：完整源语义；
- `SOURCE_LOWERED`：「Source semantics, but the language construct itself was lowered away.」
  —— **语义保留，只是结构没了**（例：Kotlin `when` 退成分支链、Scala `match` 退成分支链，`IrPrecision.java:70-77`）；
- `BYTECODE` / `BYTECODE_SYNTHETIC`：字节码路径专用。

**问题**：审计里 279 条**静默丢弃**里，有一部分是**语义真的丢了**（例：Java `switch` 的多标签 arm 只有第一个标签被测、`finally` 在 try 内的 `return`/`throw` 路径上被跳过、`&&` 不短路）。
用现有枚举表达这种状态只有两个选择，都错：
- 标 `SOURCE_FULL` ⇒ 谎报；
- 标 `SOURCE_LOWERED` ⇒ **宣称「语义保留」**（`IrPrecision.java:75` 的原话），是更隐蔽的谎报。

⇒ 现有枚举**没有一档能表达「语义有损」**。这是 P0-4 的根：不是「Java 没调 `setIrPrecision`」，而是**调了也没地方调**。

> **但前提要先说清楚**（见本文开头的立场）：`SOURCE_DEGRADED` 只在**确实无法静态表达**时使用。
> 一个构念若能被完整表达，前端就必须**把它降级出来**，而不是降级精度。
> 换句话说：**精度降级是「表达不了」的记账，不是「懒得实现」的借口**——
> 「懒得实现」在前端侧必须体现为「门禁 A 的清单上没打勾」，而不是精度降级。

#### 数据形状

```java
public enum IrPrecision {
  SOURCE_FULL,      // 既有
  SOURCE_LOWERED,   // 既有：语义保留，结构丢失
  BYTECODE,         // 既有
  BYTECODE_SYNTHETIC, // 既有
  SOURCE_DEGRADED,  // 新增（追加在末尾，见 §0.3）
}
```

新增查询方法（语义判断一律走它们，不看声明顺序）：

```java
/** 源语义是否完整保留。SOURCE_DEGRADED 为 false —— 有构念的语义真的丢了。 */
public boolean preservesSemantics();

/** 是否有构念的语义确实丢失（区别于"结构丢失"）。 */
public boolean hasSemanticLoss();

/** 是否有构念的结构丢失但语义保留。 */
public boolean hasStructuralLoss();
```

**并且必须改一个既有方法**：`IrPrecision.java:126-128` 的

```java
public boolean maySuppressNullDeref() {
  return this == SOURCE_FULL || this == SOURCE_LOWERED;
}
```

⇒ 必须加上 `SOURCE_DEGRADED ⇒ false`。理由：`maySuppressNullDeref` 的作用是「用类型系统的事实压掉空指针告警」；
而 `SOURCE_DEGRADED` 的定义就是「有一块语义没读进来」，用它压误报是**拿不完整的事实当完整事实用**。
**这是 §0.4 方向性规则的直接推论，不是可选项。**

#### 生产消费方

- `ModelCoverageComputer.java:133`（现有唯一读点）→ 扩展为三档统计：`SOURCE_DEGRADED` 的函数单独计数，
  「语义有损」从此在覆盖率报告里**可见**。
- 新增：`plumb-dfa/.../checkers/` 下任何**结构敏感**的 checker（穷尽 switch 检查、死代码检查）
  在 `fn.irPrecision().hasStructuralLoss()` 为真时跳过 —— 语义敏感的 checker（污点、空指针、注入）**照跑**。

#### 序列化 / wire

枚举按名字序列化、只能末尾追加 ⇒ 兼容。
wire 侧：`SidecarTacMapper.java:133-135` 已经在读 `"SOURCE_LOWERED"`；
**只需让 sidecar 能发出 `"SOURCE_DEGRADED"`**，映射逻辑本身不用改（把 `equals` 换成 `startsWith("SOURCE_")` 判定即可）。

#### 风险

- 枚举顺序不再语义化 ⇒ 必须**全面禁止**对 `IrPrecision` 做 ordinal 比较（当前代码没有，可加门禁）。
- `SOURCE_DEGRADED` 与 `SOURCE_LOWERED` 的界线必须在各前端有**一致的判定规则** ⇒ 见 §2-IR-B（丢弃记录里带影响级别，精度由它推导，不允许前端各写各的）。

---

### IR-B 丢弃可见性：把「丢了什么」变成 IR 的一等公民

#### 动机

审计最贵的结论是 **279 条静默丢弃**（占全部丢弃的 55%）。「静默」意味着：
**扫描报告成功，而信息已经没了**，没有任何人、任何地方知道。

现状：`TacProgram.FunctionProblem`（`TacProgram.java:69-76`）已经存在，形状是
`(functionName, kind ∈ {PARSE_PROBLEM, TRANSLATION_ERROR, UNHANDLED_NODE}, message, nodeClass, line, column)`，
并且有 `TacFrontend.parseStructured` 这条 SPI（`TacFrontend.java:179-188`）。

**但它有四个缺口**：

| 缺口 | 证据 |
|---|---|
| (a) 它挂在**文件级**（`ParseResult`/`StructuredParseResult`），不挂在函数上 ⇒ 引擎问「这个函数完整吗」要自己关联 | `TacFrontend.java:144-147` |
| (b) `kind` 只有三档，**说不出「丢了什么语义」** | `TacProgram.java:71` |
| (c) **sidecar 路径完全没有它** —— wire 协议里没有 problems 字段（见 §3） | `SidecarTacMapper.java` 无对应读取 |
| (d) 它只被人读，**没有生产消费方**（`programProblems` 只被打印） | `TacProgram.java:113` |

⇒ 结果就是：**最需要被看见的 6 个 sidecar 语言，恰恰是最看不见的。**

#### 数据形状

**新增一个函数级记录**（追加在 `TacFunction` 字段表末尾）：

```java
/** 一次「没能降级」的记录。 */
public record TacDrop(
    String construct,     // 稳定 ID，如 "switch.fallthrough"、"try.finally"、"class.property"
    String detail,        // 人读描述（可含源文本片段）
    int line,             // 源行，-1 未知
    int column,           // 源列，-1 未知
    DropImpact impact,    // 见下
    String scope)         // 作用范围：函数名 / 类名 / 文件级
    implements java.io.Serializable {

  public enum DropImpact {
    /** 语义保留，只是形状没了（例：Scala match → 分支链）。 */
    STRUCTURE,
    /** 语义真的丢了（例：switch 的 fall-through 未建模、finally 未接到所有出口）。 */
    SEMANTICS,
    /** 单个值未知（例：某个表达式降级成 HAVOC）。函数整体仍可 SOURCE_FULL。 */
    VALUE,
    /** 只丢了类型层的信息（例：Go 的类型 switch 收窄）。运行期行为不受影响 ⇒ 精度不变。 */
    TYPE
  }
}
```

**`TacFunction` 追加字段**：`private final List<TacDrop> drops;`（默认空列表，序列化兼容）。

**`TacProgram` 追加字段**：`private final List<TacDrop> programDrops;` —— 承载**没有对应函数**的丢弃
（整类成员被跳过：一个类的属性、PHP 的魔术方法、TS 的 `declare module`）。

#### 为什么是这个形状

- 不新增 operand 变体（避开 411 处 `instanceof`）；
- 不新增 instruction Kind（避开 166 处 `switch` + `.kind ==` 比较式）；
- `TacDrop` 是**纯数据 record**，消费方按需读。

#### 精度与丢弃的关系（关键设计）

**精度等级由丢弃记录推导，不允许前端各写各的**：

```
函数内存在 impact == SEMANTICS 的 drop  ⇒  irPrecision = SOURCE_DEGRADED
函数内存在 impact == STRUCTURE 的 drop  ⇒  irPrecision = SOURCE_LOWERED
只有 impact == VALUE 的 drop            ⇒  irPrecision = SOURCE_FULL（局部降级，值本身是 HAVOC）
```

这与 `TacInstruction.HAVOC` 的既有设计一致 —— 它的 javadoc（`TacInstruction.java:110-117`）写着：

> The alternative was to degrade the whole function to `IrPrecision#SOURCE_LOWERED`, which throws away
> the parts of the function that *were* understood — including the taint that had already reached the
> function's parameters. One unreadable expression should cost one instruction's precision, not the
> whole function's.

⇒ `DropImpact.VALUE` 正是这句话的**记录处**：它说「这一个值降级了，别的没降级」。

#### 数据流友好性

- **taint 不再在「静默丢弃」处凭空消失**：一个被丢弃的表达式若是 taint 源，现在至少留下 `VALUE` 记录，
  引擎可据此把该值当**未知**（而非干净）处理 —— 注意这不是「抑制告警」，而是「不假装干净」，方向与 §0.4 一致。
- **函数级完整性可查**：引擎问 `fn.drops()` 就能知道这个函数是否可依赖。

#### Z3 友好性

- `DropImpact.VALUE` 的 drop 让引擎知道**某个变量是未知值**，因此不能给它赋一个具体的 Z3 常量绑定 ——
  否则会把「未知」误当成「就是这个数」。当前 `Z3Bridge` 把未绑定的标识符建成自由变量
  （`Z3Bridge.java:278` `ctx.mkIntConst(tok)`），这恰好是正确的行为；drop 记录让这个「自由变量」**可解释**。
- `DropImpact.SEMANTICS` 的 drop ⇒ 该函数内的守卫（IR-C）**不可信**，引擎不应把它送进 Z3 当作事实。

#### 生产消费方（点名）

| 消费方 | 现状 | 动作 |
|---|---|---|
| `ModelCoverageComputer.java:133` | 已读 `irPrecision()` | 扩展为读 `drops()`，产出「语义有损的函数」清单 |
| 报告器（`plumb-dfa/.../engine/FindingReporter.java`） | 不读 | 新增：报告尾部附「本次扫描丢弃清单」 |
| 精度推导（新增，建议放 `plumb-tac/.../ir/TacFunction.java` 静态方法） | 无 | 新增：`TacFunction.derivePrecision(drops)`，统一各前端口径 |

#### 序列化 / wire

- `TacDrop` 实现 `Serializable`；`TacFunction` / `TacProgram` 字段**追加在末尾** ⇒ 兼容。
- wire 侧需要新字段 `drops`（函数级）与 `programDrops`（文件级），见 §3。

#### 风险

- **新增 drop 记录会让「丢弃」变成常态输出**，可能让报告变得吵闹 ⇒ 报告里按 `construct` 聚合计数，不逐条铺开。
- `TacProgram` 已有 `programProblems`，两套通道语义重叠 ⇒ 建议 `FunctionProblem` 保留为「解析/翻译失败」，
  `TacDrop` 专职「构造没降级」，两者**不合并**（前者是错误，后者是覆盖缺口）。

---

### IR-C 结构化守卫：让路径条件能从 CFG 推出（**Z3 的关键前提**）

#### 动机（这是整份设计里对 Z3 影响最大的一条）

引擎今天怎么用 Z3？实测：

- `SolverZ3Bridge.java:67-76` 的接口是 **`check(String predicate, Map<String,Object> bindings)`** ——
  路径条件是**字符串 mini-DSL**，变量按**名字**绑定到具体值；
- `LocalSolverBridge` / `Z3Bridge` 解析这个 DSL（`Z3Bridge.java:125-280`），文法是
  `&&`、`||`、`!`、`==`、`!=`、`<`、`<=`、`>`、`>=`、`+`、`-`、`*`、`/`、数字、字符串、标识符；
- `GuardStore.java:54` 是 `Map<String, List<String>>` —— **key 是变量名**，value 是谓词字符串；
- `PathCondition.java:106-131` 的 `implies` = 问 `(前提 && !(结论))` 是否 UNSAT。

**关键发现：今天没有任何一条路径条件是从 CFG 推出来的。**
所有守卫都是 checker 自己拼的字符串 —— 例如 `DivByZeroChecker.java:216` 写入 `data != 0`、
`BoundaryConstraintChecker.java:692` 写入区间守卫。

⇒ **后果**：Z3 只能回答「某个 checker 已经想到的守卫之间是否蕴含」，
**回答不了「这条路径上的条件到底是什么」**。于是：
- 路径敏感分析（`PathSensitiveCheckerBase`）拿不到路径条件；
- 开关支（switch case）上的条件、循环条件的范围、`if (x > 0 && x < 10)` 的合取，全部丢失；
- 而审计 P0-2 认定**控制流建错了**（switch 只有第一个 case 的比较是活的、`&&` 不短路）
  ⇒ **守卫本身就是错的**，送进 Z3 只会更快地得到错答案。

⇒ 所以 IR 必须让**分支条件本身**成为结构化数据，而不是「一个布尔临时变量」。

#### 数据形状

```java
/** 一个分支上的条件。语言中立：每种语言的比较/真值测试都能映射到这里。 */
public sealed interface TacGuard extends java.io.Serializable {

  /** lhs op rhs。op 复用既有的 BinaryOperator（EQ/NE/LT/LE/GT/GE 已在 BinaryOperator.java:44-50）。 */
  record Compare(TacOperand lhs, BinaryOperator op, TacOperand rhs) implements TacGuard {}

  /** 真值测试（if (x)）。sense 区分「x 为真」与「x 为假」。 */
  record Truthiness(TacOperand operand, BooleanSense sense) implements TacGuard {}

  /** switch 的 case 等值测试：operand ∈ options。 */
  record Membership(TacOperand operand, List<TacOperand> options) implements TacGuard {}

  /** 类型测试（instanceof / is / as?）。 */
  record TypeTest(TacOperand operand, TacType type, boolean negated) implements TacGuard {}

  /** 常真 / 常假。 */
  record Always(boolean value) implements TacGuard {}
}
```

**挂载点**：`TacInstruction` 追加可选字段 `private final TacGuard guard;`
（**只在 `Kind.BRANCH` 上有意义**；其它 kind 恒为 null）。构造器参数追加在末尾。

**布尔极性**：`Compare` 的极性由 `trueLabel`/`falseLabel` 表达 —— 守卫描述「真分支成立的条件」，
假分支是它的否定。**不新增极性字段**（避免两种真值表示打架）。

#### 为什么是这个形状

- `Compare` 复用 `BinaryOperator` ⇒ **不新增算子枚举**（§0.3 的枚举约束）。
- 它是 `TacInstruction` 的**可选字段** ⇒ **0 处分派点**（§0.2）。
- `sealed interface` ⇒ 消费方可以穷尽匹配，编译器强制处理新变体。

#### 数据流友好性

- 引擎在 CFG 上走一条边时，可以**自动**把该边的守卫加入路径条件 —— 这是路径敏感分析的前提，
  今天完全没有。
- 守卫还能给**空指针**检查提供依据（`x != null` 是 `Compare`），
  给**未定义变量**检查提供依据（`x` 未赋值 ⇒ 真分支不可达）。

#### Z3 友好性（**核心**）

`TacGuard` → Z3 的映射是**直接的、无损的**：

| TacGuard | Z3 表达式 | 备注 |
|---|---|---|
| `Compare(a, EQ, b)` | `mkEq(a, b)` | a/b 是 `TacOperand` → Z3 项 |
| `Compare(a, NE, b)` | `mkNot(mkEq(a, b))` | |
| `Compare(a, LT/LE/GT/GE, b)` | `mkLt/mkLe/mkGt/mkGe` | |
| `Membership(a, [x,y])` | `mkOr(mkEq(a,x), mkEq(a,y))` | switch 的多 case 合并 |
| `Truthiness(a, TRUE)` | `mkNot(mkEq(a, 0))` | 与 `Z3Bridge.java:301-306` 的「非零即真」一致 |
| `TypeTest` | **不可表达** | 类型测试不是数值命题 ⇒ 渲染成 `true`（不贡献约束） |
| `Always(v)` | `mkTrue/mkFalse` | |

**变量绑定契约（必须写死，否则与 `GuardStore` 对不上）**：
守卫里的 `TacOperand.TacVar` 一律渲染成 **`TacVar.name()`** —— 这正是 `GuardStore` 的 key
（`GuardStore.java:35-37`：「Keys are the guarded variable's name (string, not `TacVar`)」）。
⇒ **现有 checker 一行都不用改**就能消费 CFG 推出的守卫：`GuardStore.impliedGuard(var, "x != 0", ...)`
的既有调用形态不变，只是守卫来源从「checker 手写」多了一个「IR 推出来」。

**Z3 排序映射（配合 IR-D）**：

| TacType.Kind | Z3 排序 | 依据 |
|---|---|---|
| BOOLEAN | Bool | |
| BYTE/SHORT/CHAR/INT/LONG | Int | 加 IR-D 的有界约束 |
| FLOAT/DOUBLE | Real | 注意 `Z3Bridge` 目前把它们也当 Int（`Z3Bridge.java:266-278` 一律 `mkInt`）⇒ **这是一个已存在的近似**，设计要显式写明 |
| DECIMAL | Real（带 scale 注释） | C# decimal 是 128 位定点，`Z3Bridge` 无法精确表达 ⇒ 标为「不精确」 |
| STRING | **长度算术**（`mkInt(str.length())`） | `Z3Bridge.java:258-260` 现状：字符串被折成长度 ⇒ 这是一个**已知简化**，不是精确建模 |
| CLASS/ARRAY/NULL/VOID/UNKNOWN | 不可数值化 | 不参与数值约束 |

#### 生产消费方

| 消费方 | 位置 | 动作 |
|---|---|---|
| `GuardStore` | `GuardStore.java:115-131` | 新增来源：引擎在 CFG 边上把 `TacGuard` 渲染成 DSL 字符串写入 store |
| `PathCondition` | `PathCondition.java:106-131` | 不变（它已经能吃字符串） |
| `PathSensitiveCheckerBase` | `plumb-dfa/.../taint/PathSensitiveCheckerBase.java` | 消费 CFG 推出的路径条件 |
| `DivByZeroChecker` / `IntUnderflowChecker` / `ArrayIndexChecker` | `DivByZeroChecker.java:145-147`、`IntUnderflowChecker.java:394-396`、`ArrayIndexChecker.java:220-222` | 保持既有 `impliedGuard` 调用不变（守卫来源变多，结论只会更准） |

#### 序列化 / wire

- `TacGuard` 需 `Serializable`；`sealed interface permits ...` 名单追加在末尾 ⇒ 兼容。
- wire：BRANCH 记录新增 `guard` 字段（§3）。

#### 风险

- **守卫写错 ⇒ Z3 更快地给出错答案**。这是本提案最大的风险。
  ⇒ **必须配套**：前端在发出 `guard` 的同时，在 `drops` 里记录「该分支的条件是**近似**的」
  （例如 Python 的 `if x:` 是真值测试，与 Java 的布尔不同）。
  ⇒ **而且 `guard` 只能是「更精确的信息」，不能是「猜测」**：
  语言无法静态判定条件真值时（例如 PHP 的 `if ($a == $b)` 其中 `$a`/`$b` 是字符串比较），
  发 `Truthiness` 而不是编一个 `Compare`。
- `TypeTest` 无法进 Z3 ⇒ 它在数值路径条件里**贡献为空**。这意味着「`if (x is String)` 之后的 `x.length()`」
  这类安全推断**不受本提案保护**，需要另设事实域（见 §3.3）。

---

### IR-D 值域：把「这个数有多大」写进类型（Z3 有界性的前提）

#### 动机

今天 Z3 里的数是**无界的**：任何数字都建成 `mkInt`（`Z3Bridge.java:266-278`），
字符串折成长度（`:258-260`）。也就是说：
**int32 的溢出不是 Z3 推出来的**，而是 checker 自己维护区间事实推出来的
（`BoundaryConstraintChecker` 里的 `Range`、`STATIC_FINAL_TABLE`（`:656-659` 等））。

跨语言时这件事更致命，因为**数值模型差别极大**：

| 语言 | 整数模型 | 浮点 | 备注 |
|---|---|---|---|
| Java | int 32 / long 64 | float/double | 有符号溢出是**回绕**（wrap），不是饱和 |
| C# | int 32 … | **decimal 128 位定点** | `TacType.Kind.DECIMAL` 已存在（`TacType.java:49-57`），且 `Const.ofDecimal` 用**文本形式**保存（`TacOperand.java:161-168`） |
| Go | int 平台相关（32/64） | float64 | |
| Python | **任意精度** | float | 溢出异常，不是回绕 |
| PHP | 平台相关 int | double | |
| TS/JS | **全部是 double**（无 int） | double | `Number.MAX_SAFE_INTEGER` 是 2^53-1 |

⇒ 同一个 `x + 1`，在 Java 里可能回绕、在 Python 里会抛异常、在 TS 里会丢精度。
**IR 现在完全表达不了这个差异** ⇒ 审计里 Cwe190/Cwe191 相关的判定天然不准。

#### 数据形状

**不新增 `TacType.Kind`**（那会波及所有 `switch (type.kind())` 的地方），而是**给 `TacType` 加一个可选字段**：

```java
/** 一个值的数值域。null = 未标注（由 Kind 推断默认域）。 */
public sealed interface TacValueDomain implements java.io.Serializable {
  /** 定宽整数。bits=32, signed=true ⇒ [-2^31, 2^31-1]。 */
  record Integral(int bits, boolean signed) implements TacValueDomain {}
  /** 任意精度整数（Python int）。 */
  record Unbounded() implements TacValueDomain {}
  /** 定点小数（C# decimal：128 位，scale 位小数）。 */
  record FixedPoint(int bits, int scale) implements TacValueDomain {}
  /** 浮点。mantissaBits/exponentBits ⇒ IEEE 754 形状。 */
  record Floating(int mantissaBits, int exponentBits) implements TacValueDomain {}
  /** 动态：脚本语言，运行时才定类型。 */
  record Dynamic() implements TacValueDomain {}
}
```

**`TacType` 追加字段** `private final TacValueDomain domain;`（默认 null）。
`TacType` 的 `equals`/`hashCode` **必须同步考虑这个字段**（`TacType.java:231-248` 现有的三字段相等性要扩成四字段）。

**默认推断**（`domain == null` 时按 Kind 推，避免所有前端都改）：
BYTE→Integral(8,true)、SHORT→Integral(16,true)、CHAR→Integral(16,false)、INT→Integral(32,true)、
LONG→Integral(64,true)、FLOAT→Floating(24,8)、DOUBLE→Floating(53,11)、DECIMAL→FixedPoint(128,?)、
STRING/CLASS/ARRAY/NULL/VOID/UNKNOWN→Dynamic。

#### Z3 友好性

- `Integral(bits)` ⇒ Z3 里仍是 Int，但**附加约束** `-2^(bits-1) <= x <= 2^(bits-1)-1`。
  这样「`x + 1` 在 int 里回绕」这件事，Z3 能**自己推**出来，而不是靠 checker 的区间表。
- `Unbounded` ⇒ 不加约束（Python int）。
- `Floating` ⇒ Real + 「误差不超过 ulp」的近似（Z3 的 FP 理论在 `Z3Bridge` 里没接）⇒ **先标为近似，不假装精确**。
- `FixedPoint` ⇒ Real + scale 注释；**标为不精确**。
- `Dynamic` ⇒ 不可判定 ⇒ 该值不参与数值约束。

⇒ 这是把「Cwe190/Cwe191 的判定」从「checker 自己的区间表」搬到「Z3 求解」的前提。

#### 数据流友好性

- 类型门控（sink 要求某个类型）能区分「int」与「double」；
  当前 `UNKNOWN` 会让门控失效（引擎里 `UNKNOWN` 是「什么都证明不了」）。
- 数值域让**字面量后缀**（`1L`、`1.0f`、`1m`）有地方落。

#### 生产消费方

| 消费方 | 位置 | 动作 |
|---|---|---|
| `Range.ofType`（整数区间表） | `checkers/boundary/Range.java:133-143`（`:135` 就是 `switch (t.kind())`；`:141` 把 `UNKNOWN` 兜底成 int32 区间） | 改为读 `domain` 取边界，删掉手写的 `Integer.MAX_VALUE` 之类推断 |
| `IntOverflowChecker` / `IntUnderflowChecker` | `IntOverflowChecker.java:394`（`isGuardBoundedOverflow`）、`IntUnderflowChecker.java:354`（`isGuardBoundedUnderflow`）—— 它们**不自建区间表**，而是从守卫里读边界 | 读 `domain` 得到溢出边界 |
| `NumericTruncationChecker` | `NumericTruncationChecker.java:149` | 区分「窄化」与「等宽」 |
| `Z3Bridge` | `Z3Bridge.java:266-278` | 新增：按 `domain` 生成有界约束 |

#### 序列化 / wire

- `TacValueDomain` `Serializable`；`TacType` 字段追加在末尾 ⇒ 兼容。
- wire：类型记录新增 `domain` 字段。

#### 风险

- **默认值必须与现有行为完全一致**：`TacType.INT` 等单例是 `domain == null`，
  引擎在读 `domain == null` 时按 Kind 推断 ⇒ **老 IR 的行为不变**（这是兼容性的关键）。
- `TacType.equals` 扩字段 ⇒ 会改变「相等」的语义（一个 `INT` 与一个显式标了 `Integral(32,true)` 的 `INT` 不再相等）
  ⇒ **建议 `domain` 不参与 equals**（像 `nullable` 参与一样要慎重），
  改用「等价性查询方法」`sameDomain(TacType other)`。

---

### IR-E 声明面元数据：成员的「身份」与「种类」

#### 动机

审计 P0-1 是本轮最高优先级：**声明面 dispatch 缺 default 分支 ⇒ 整类成员从未被访问**。
具体形态（审计总报告 §6 P0-1）：TS `lowerClass` 只有 `if (isMethodDeclaration)` 没有 `else`
（`ts-sidecar/src/translator.mjs:473-479`）；ArkTS 同款构造器 + 字段 + 访问器全丢；
C# `ProjectSession.cs:398-428` 4 个 case 无 default。

**这一类问题有两半**：
- **前端一半**：加 `else` 分支，把成员降级出来（这是 §4 各语言方案的事）；
- **IR 一半**：降级出来之后，**引擎分不清它们是什么**。

具体缺口：

| 缺口 | 证据 | 后果 |
|---|---|---|
| **成员没有「种类」** | `TacFunction` 只有 name/signature/enclosingClass，**没有「我是构造器/属性访问器/运算符重载」** | C#/Kotlin/Scala 的访问器、构造器、运算符重载无法被识别，checker 无法针对性处理 |
| **字段没有 owner** | `TacInstruction.fieldName` 只是个字符串（`TacInstruction.java:233-234`） | 同名字段（不同类）混在一起；`TacProgram` 虽有 `classFieldAnnotations`（`:173`）但**指令上不带 owner** |
| **类不是 IR 节点** | `TacProgram` 只有 `functions` map + `classToParent`/`classToChildren`（`:128`/`:140`） | 类的元数据（注解、修饰符、字段表）只能**每个方法重复挂一份**（`classAnnotations`） |

#### 数据形状

**(a) 成员种类** —— `TacFunction` 追加可选字段：

```java
public enum TacMemberKind {
  METHOD, CONSTRUCTOR, STATIC_INITIALIZER, GETTER, SETTER,
  PROPERTY_ACCESSOR, OPERATOR, EVENT_HANDLER, DELEGATE_INVOKE,
  FIELD_INITIALIZER, COMPANION_MEMBER, EXTENSION_FUNCTION
}
```

`TacFunction` 追加 `private final TacMemberKind memberKind;`（默认 `METHOD`）。

**(b) 字段 owner** —— `TacInstruction` 追加可选字段 `private final String ownerType;`
（`FIELDLOAD`/`FIELDSTORE` 用；`null` = 未知 owner，保持现状语义）。

**(c) 类级容器** —— `TacProgram` 追加：

```java
/** 一个类型声明的元数据。IR 里没有「类」节点，这是最小的一等表示。 */
public record TacTypeDecl(
    String fqn,                    // 全名
    String simpleName,
    String superType,             // 父类 FQN，可空
    List<String> interfaces,       // 实现的接口 FQN
    List<String> annotations,      // 类级注解（简单名）
    Map<String, TacType> fields,   // 字段名 → 类型
    Map<String, List<String>> fieldAnnotations,
    List<TacMemberRef> members,    // 成员清单（见下）
    boolean isInterface,
    boolean isAbstract,
    boolean isEnum,
    boolean isRecord,
    boolean isSynthetic)
    implements java.io.Serializable {}

public record TacMemberRef(
    String name, String signature, TacMemberKind kind, List<String> annotations,
    boolean isStatic, TacVisibility visibility, boolean isSynthetic) {}
```

**(d) 可见性分级**（IR-E 的补充细化，由 Kotlin 线的审计带出来）：

初稿这里写的是 `boolean isPublic`，但**布尔不够用**：Kotlin 的 `internal`（模块作用域）
既不是 `public` 也不是 `private`，布尔只能把它和 `private` 混在一起 ⇒
「跨模块分析能不能用这个成员的可见性」这个问题仍然答不了
（审计台账：Kotlin 的 `internal` 与 `public` 不可区分 ⇒ 跨模块分析无法利用它）。

⇒ 改为**分级枚举**，**枚举末尾追加**（按 `01` §0.3 的序列化约束）：

```java
public enum TacVisibility {
  PUBLIC, PROTECTED, INTERNAL, PRIVATE, PACKAGE
}
```

- `PACKAGE` = Java/Scala 的包级可见；`INTERNAL` = Kotlin 的模块级可见；
  语言特有的可见级别（如 Rust 的 `pub(crate)`）⇒ 用 IR-H 的语言规则开关映射到这五档之一，
  **不在 IR 里新增语言专用枚举值**。
- `null`（可选字段缺省）= **未知可见性** ⇒ 消费方按「不设限」处理（保守方向：不误杀可达性）。

**为什么是 `TacProgram` 而不是新 IR 节点**：IR 的顶层容器已经是 `TacProgram`（`TacProgram.java:44`），
`classToParent`/`classToChildren`/`classFieldAnnotations` 都在那儿 ⇒ **加一个同类字段成本最低**。

#### 数据流友好性

- `ownerType` 让「读 `this.x`」和「读 `other.x`」可区分 ⇒ 字段污点追踪（`TaintedCheckerBase` 的 per-class map）更准。
- `members` 清单让「这个方法从未被调用」这类 checker 能区分「真的死代码」与「被反射/特性调用」。

#### 生产消费方

| 消费方 | 位置 | 动作 |
|---|---|---|
| 字段污点追踪 | `TaintedCheckerBase`（per-class 字段 map） | 读 `ownerType` 分桶 |
| 死代码 checker | `plumb-dfa/.../checkers/` | 读 `TacTypeDecl.members()` 排除反射可达成员 |
| 入口点分类 | 已读 `classAnnotations` | 扩展读 `TacTypeDecl` |

#### 序列化 / wire

- `TacFunction` / `TacInstruction` / `TacProgram` 均**追加末尾字段** ⇒ 兼容。
- wire：函数记录新增 `memberKind`；指令记录新增 `ownerType`；文件记录新增 `types`（`TacTypeDecl` 数组）。

#### 风险

- `TacTypeDecl` 是**新的一等类型**，会让「类」在 IR 里第一次有形状 ⇒ 引擎侧所有「按函数枚举类成员」的代码
  需要逐步迁移。**迁移期必须保证新旧两套给出同样的结果**（老路径仍从 `TacFunction.classAnnotations` 读）。
- `ownerType` 参与 `TacInstruction.equals` 吗？`TacInstruction` 没有覆写 equals（用身份相等）⇒ **不影响**，安全。

---

### IR-F 异常区域：`TryRegion` v2 + wire 字段

#### 动机

审计 P0-3：**`tryRegions` 被硬编码成 `List.of()`**（`plumb-frontend/src/main/java/org/plumb/tac/frontend/sidecar/SidecarTacMapper.java:150`）
⇒ 6 个 sidecar 语言**零异常流边**：PHP 的 catch 体不可达、Python 的 except handler 不可达、C# 的 catch 体在正常路径上。

**IR 侧其实已经有能力**：`TacFunction.TryRegion`（`TacFunction.java:749-835`）带 try/catch/finally 的索引区间 + 标签，
`TacInstruction.Kind.CATCH_HANDLER` / `FINALLY_ENTER` 也在（`TacInstruction.java:73`/`:81`）。

⇒ 这一条**主要是 wire 缺字段**，不是 IR 缺能力。但 IR 侧仍有三个真实缺口：

| 缺口 | 证据 | 后果 |
|---|---|---|
| **catch 不知道匹配哪个异常类型** | `TryRegion.catchLabels` 只是标签列表（`:774`） | `TryCatchFinallyChecker` 只能靠 `CATCH_HANDLER.ctorType` 单点判断，multi-catch 的伪类型问题由此而来 |
| **没有资源语义** | `TryRegion` 无资源字段 | try-with-resources / `using` / `with` 的「异常时也要关闭」完全没建模 |
| **finally 的入口只靠标签** | `finallyLabel`（`:781`） | 「finally 在哪几个出口被执行」需要 CFG builder 自己数出口，语义分散 |

#### 数据形状

`TryRegion` 追加三个字段（构造器参数追加在末尾）：

```java
/** 每个 catch 的异常类型（与 catchLabels 同序）。 */
public final List<String> catchTypes;
/** 需要在 finally/离开前关闭的资源变量（try-with-resources / using / with）。 */
public final List<TacOperand> resourceVars;
/** 该 finally 是由语言机制合成的（Python with、C# using → 编译器生成的 try/finally）。 */
public final boolean syntheticFinally;
```

**并新增一个「finally 出口」清单**：

```java
/** finally 必须被执行到的出口（正常完成 / 每个 catch 完成 / rethrow）。 */
public final List<String> finallyExits;
```

#### Z3 友好性

异常区域本身不进 Z3，但**它决定 CFG 的边** ⇒ 边决定路径条件（IR-C）。
「catch 不可达」会让 catch 里的守卫永远不被求解 ⇒ **修好异常边 = 多一批可求解的路径**。

#### 生产消费方

- `TryCatchFinallyChecker`（`plumb-dfa/.../checkers/controlflow/TryCatchFinallyChecker.java`）—— 读 `catchTypes`。
- `TacCfgBuilder`（`plumb-tac/.../ir/TacCfgBuilder.java`）—— 读 `finallyExits` 建边。

#### 序列化 / wire

- `TryRegion` 是 `TacFunction` 的静态嵌套类，追加字段 ⇒ 兼容。
- **wire 必须新增 `tryRegions`**（这是 P0-3 的正解）⇒ 见 §3。

---

### IR-G 标签：给跳转目标一个语义

#### 动机

审计 P0-2 的一整类：**控制流被建模成错答案**（不是缺信息）：

- Java `switch` 只有第一个 case 的比较是活的（`StatementTranslator.java:891`）；
- `finally` 在四种出口被跳过（`:807-823`）；try 体落入 catch（`:730-799`）；`&&`/`||` 不短路；
- ArkTS switch 按顺序执行 case；
- Python 循环 `else` 语义被反转（`else` 体永远执行）。

这些错误的共同点是：**跳转目标是裸字符串**（`TacInstruction.label` / `trueLabel` / `falseLabel`），
**没有任何信息说明这个标签是「循环尾」还是「switch 的下一个 case」**。
前端写错了，IR 无法发现，引擎也无从校验。

#### 数据形状

```java
/** 标签的种类与作用域。 */
public record TacLabelRef(String name, TacLabelKind kind, int scopeId) implements java.io.Serializable {}

public enum TacLabelKind {
  FUNCTION_ENTRY, FUNCTION_EXIT, BLOCK,
  LOOP_HEAD, LOOP_BODY_END, LOOP_END,      // 循环 end 与 body end 必须分开：Python 的 else 挂在 body end 上
  SWITCH_HEAD, SWITCH_CASE, SWITCH_END,
  CATCH_ENTRY, FINALLY_ENTRY
}
```

`TacInstruction` 追加可选字段 `private final TacLabelRef labelRef;`（`LABEL`/`JUMP`/`BRANCH` 用）。
**既有的 `label`/`trueLabel`/`falseLabel` 字符串字段保留**（兼容旧 IR 与旧 checker），
新前端**两个都写**，引擎优先读 `labelRef`。

#### 为什么 `LOOP_BODY_END` 与 `LOOP_END` 必须分开

Python 的 `for ... else:` 里的 `else` 体：
- 在**没有 break** 时执行 ⇒ 挂在**循环正常结束**之后；
- 有 `break` 时不执行 ⇒ `break` 的目标必须**跳过**它。

⇒ 这两条是不同的边。审计认定 Python 现在「`else` 体与 break 目标同块 ⇒ 永远执行」
（总报告 §7.1.5 复核表 P0-2 行）。把两种「结束」分开建模，Python 前端才能把 `break` 指向
`LOOP_BODY_END`（跳过 else），把正常流指向 `LOOP_END`（进入 else）。

#### 生产消费方

- `TacCfgBuilder` —— 建边时可**校验**目标合法性（跳到别的作用域 ⇒ 报 drop）。
- 前端 —— 发出 `labelRef` 后，可在 sidecar 内部做**断言**（例如「switch 的每个 case 必须由一个
  `SWITCH_CASE` 标签进入」），断言失败即发一条 `TacDrop`。

#### 风险

- 双表示（`label` + `labelRef`）带来「两者不一致」的可能 ⇒ 约定：**`labelRef` 为 null 时 `label` 权威；
  `labelRef` 非 null 时必须与 `label` 相等**，不一致由前端自检（发 drop）。

---

### IR-H 语言的运行时规则开关（真值性 / 数值语义）

#### 动机

动态语言（PHP / TS / JS / Python）的很多「语义」不在类型里，而在**语言的求值规则**里。
审计发现的典型丢失：

- **真值性规则各语言不同**：
  JS/TS：`""`、`0`、`-0`、`0n`、`NaN`、`null`、`undefined`、`[]`（空数组！）都为假；
  Python：`""`、`0`、`0.0`、`[]`、`{}`、`set()`、`None` 为假；
  PHP：`""`、`"0"`、`0`、`0.0`、`[]`、`null` 为假 —— **注意 `"0"` 在 PHP 是假，在 JS 是真**；
  Java/C#/Kotlin/Scala/Go：只有布尔为条件（Go 允许非布尔但要求可比较）。
- **字符串↔数字的强制转换**：PHP `"1abc" + 1` = 2（前导数字），TS `"1abc" * 1` = `NaN`。

⇒ 引擎要正确判定「`if ($x)` 分支可达吗」，**必须有语言的真值规则**。
现在引擎不知道 ⇒ 只能保守（都可达）⇒ 大量误报。

#### 数据形状

**这一项落在引擎侧，不在 TAC IR 上**：`plumb-dfa/.../semantics/LanguageSemantics.java`（已存在，
`LanguageSemanticsRegistry.java:43-90` 已经为 10 个语言注册了 taint 行为表）。

追加两个正交维度：

```java
/** 一个语言把什么当作假。 */
public enum TruthinessPolicy {
  JAVA_LIKE,     // 只有 false 是假
  JS_LIKE,       // "" 0 -0 0n NaN null undefined [] 都是假
  PYTHON_LIKE,   // "" 0 0.0 [] {} set() None 都是假
  PHP_LIKE,      // "" "0" 0 0.0 [] null 都是假
  STRICT_BOOLEAN // 条件必须是布尔
}

/** 一个语言的数值模型。 */
public enum NumericPolicy {
  INT32_WRAPPING, INT64_WRAPPING, ARBITRARY_PRECISION,
  DOUBLE_ONLY, FIXED_POINT_DECIMAL, PLATFORM_INT
}
```

**IR 侧只需一件事**：`IR-C` 的 `Truthiness` 守卫标明「这是一个真值测试」，
引擎按 `sourceLanguage` 查 `TruthinessPolicy` 求值。

#### 生产消费方

- `LanguageSemanticsRegistry.withDefaults()`（`:77-90`）—— 追加两张表。
- 所有「条件可达性」判定 —— 用 `fn.sourceLanguage()` 选策略。

#### 风险

- `LanguageSemantics` 是引擎侧既有类型，追加枚举常量要**追加在末尾**（同 §0.3）。

---

## 3. wire 协议 v2（6 个 sidecar 共用）

> 独立成篇：`08-wire-协议-v2.md`。此处只列 IR 提案对 wire 的要求。

| IR 提案 | wire 需要的新字段 | 影响的 sidecar |
|---|---|---|
| IR-A 精度档位 | `precision` 已有（`SidecarTacMapper.java:133`），需能发 `"SOURCE_DEGRADED"` | 全部 6 个 |
| IR-B 丢弃记录 | 函数级 `drops`、文件级 `programDrops` | 全部 6 个 |
| IR-C 结构化守卫 | BRANCH 记录新增 `guard` | 全部 6 个 |
| IR-D 值域 | 类型记录新增 `domain` | 全部 6 个 |
| IR-E 声明面 | 函数级 `memberKind`、指令级 `ownerType`、文件级 `types` | 全部 6 个 |
| IR-F 异常区域 | 函数级 `tryRegions`（**当前硬编码 `List.of()`**，`SidecarTacMapper.java:150`） | 全部 6 个 |
| IR-G 标签 | LABEL/JUMP/BRANCH 记录新增 `labelRef` | 全部 6 个 |

**共同点**：**没有一个提案要求改引擎的指令语义**——全部是**加字段**。
这是 §0.2 形状选择的直接回报：**改 wire 不改引擎，改引擎不改 wire**。

**C# 与 Go 不走 `SidecarTacMapper`**（C# 有自己的 `toInstruction`/`toFunction`）⇒
它们的 wire 形状要**单独同步**，不能假设共享。

---

## 4. Z3 与数据流的落点总表

| IR 提案 | 对数据流的作用 | 对 Z3 的作用 | 生产消费方（点名） |
|---|---|---|---|
| IR-A 精度 | 让「语义有损」的函数可被识别、可被区别对待 | 该函数的守卫不可信 ⇒ 不送 Z3 | `ModelCoverageComputer.java:133` |
| IR-B 丢弃 | 丢弃从静默变可见；`VALUE` 落点不当成干净值 | 未绑定变量保持自由变量（`Z3Bridge.java:278`） | `ModelCoverageComputer.java:133`、报告器 |
| **IR-C 守卫** | **路径敏感分析的前提**：守卫从 CFG 自动来 | **核心**：Compare → mkEq/mkLt…；变量名 = `TacVar.name()` = `GuardStore` 的 key | `GuardStore.java:115`、`PathCondition.java:106` |
| IR-D 值域 | 类型门控能区分 int/double | Int 加有界约束 ⇒ 溢出由 Z3 推 | `Range.java:133-143`、`Z3Bridge.java:266` |
| IR-E 声明面 | 字段污点按 owner 分桶；死代码判定更准 | 不直接相关 | `TaintedCheckerBase` 字段表、`TacProgram.classFieldAnnotations:173` |
| IR-F 异常 | 修好异常边 ⇒ 多一批可达路径 | 异常边带来的守卫可求解 | `TryCatchFinallyChecker`、`TacCfgBuilder` |
| IR-G 标签 | 让「循环尾 vs 循环体尾」可区分 ⇒ Python else 正确 | switch 的 case 条件成为独立路径 | `TacCfgBuilder`、前端自检 |
| IR-H 语言规则 | 真值性正确 ⇒ 少一批误报 | 真值测试的求值规则确定 | `LanguageSemanticsRegistry.java:77` |

---

## 5. 本设计**不做**的事（明确排除，避免范围蔓延）

1. **不新增任何语言专用 opcode**（`TacFrontend.java:50-57` 明文禁止）。
2. **不新增 operand 变体**（411 处 `instanceof` 风险）。
3. **不新增 instruction Kind**（本设计 8 个提案**一个都没有**新增 Kind —— 这是刻意的：
   instruction Kind 的真实成本是 **28 处 `switch` + 138 处 `.kind ==` 比较式 = 166 处**，
   而 `TacInstruction.java:465` 是**穷尽 switch、无 `default`** ⇒ 加常量会**编译失败**；
   其余 27 处 switch 都带 `default` ⇒ **静默跳过**，正是 P0-1「忘了 `default`」那类形态的温床）。
4. **不改 `TacType.Kind` 的既有 15 个值**（只加可选字段 `domain`）。
5. **不把 `TacProgram.FunctionProblem` 与 `TacDrop` 合并**（前者是错误，后者是覆盖缺口，语义不同）。
6. **不改 `SourceLanguage` / `SourceOrigin` 的语义**（只读它们的值）。
7. **不动 `IrSerializer` 的格式**（靠 Java 序列化 + 末尾追加字段兼容）。
8. **不把 drop 当成兜底通道**（这一条是本文开头立场的直接推论）：
   `TacDrop` 只在「**确实无法静态表达**」时产生。**能完整表达的构念不许降级、不许记 drop**。
   「前端还没实现某个构念」属于**工程债**，它必须体现在门禁 A 的清单缺项上，
   而不是变成 IR 里的一条「已降级」——否则精度会变成一个可以随便花掉的预算。

---

## 6. 实施顺序（IR 部分）

| 阶段 | 做什么 | 为什么是这个顺序 | 验收 |
|---|---|---|---|
| **S1** | IR-B（`TacDrop` + `TacFunction.drops`）+ IR-A（`SOURCE_DEGRADED`） | **必须最先**：没有丢弃记录，任何「修好了」的断言都无法证明 | 门禁：每个前端在遇到未识别节点时必须发一条 drop；老 IR 行为不变 |
| **S2** | IR-C（`TacGuard`） | Z3 的前提；且它不改任何 checker 的调用形态 | 同一个语料，引擎自动推出的守卫数 > 0；`GuardStore` 里出现 CFG 来源的守卫 |
| **S3** | IR-F（`TryRegion` v2 + wire `tryRegions`） | P0-3：零异常流边是 6 个语言的最大单点损失 | PHP catch / Python except 变得可达；语料上异常路径的 finding 出现 |
| **S4** | IR-D（`TacValueDomain`） | 让 Cwe190/191 的边界由 Z3 推，而不是区间表 | 同一语料，溢出/下溢 finding 数变化可解释 |
| **S5** | IR-E（声明面）+ IR-G（标签） | 让「成员身份」与「跳转目标」可表达；P0-1 的 IR 侧配套 | C#/Kotlin/Scala 的访问器、构造器在 TAC 里可区分 |
| **S6** | IR-H（语言规则开关） | 纯引擎侧，不依赖 wire | PHP/TS/Python 的真值分支判定与解释器一致 |

**每个阶段的验收口径必须包含「老 IR 的行为不变」**——
用同一份语料跑新旧两版，要求**在新增能力生效处 finding 增加、在其它处 finding 不变**。

---

## 7. 与审计 505 条丢弃的对应（收口）

| 审计条目 | 由哪些 IR 提案解决 | 还剩什么前端工作 |
|---|---|---|
| P0-1 声明面 dispatch 缺 default | IR-E（成员种类、owner、类型声明） | 各前端加 `else` 兜底（§4 各语言方案） |
| P0-2 控制流建错 | IR-C（守卫）、IR-G（标签）、IR-F（异常边） | 各前端按 §4 的控制流模板重写 |
| P0-3 `tryRegions` 硬编码 | IR-F | wire 加字段（§3） |
| P0-4 精度缺失/谎报 | IR-A + IR-B | 各前端按统一口径上报 drop |
| P0-5 Kotlin 赋值与属性读写 | IR-E（成员种类） | Kotlin 前端 |
| P0-6 注解/特性/装饰器无线上字段 | 既有 `TacFunction` 四个注解通道 + `annotationValues` | **只需 wire 加字段**（IR 不需新增） |
| P1 9 条 | 分散 | 见总报告 §6 P1 表逐条 |
| P2 6 条 | IR-D（类型泛型/可空性/标注） | 见总报告 §6 P2 表逐条 |

⇒ **IR 侧的提案到此为止**：剩余的 505 条里，绝大多数的**前端工作**在 §4 的各语言方案里逐条列出。

---

## 8. 待定项（**已拍板**，2026-11）

> 本节原先是「待定项」，现已逐条裁定并给出**实测依据**。
> 裁定结论同时写进 `10` 的风险表与各语言文档。

### 8.1 `SOURCE_DEGRADED` 的枚举位置 —— **采纳「追加在末尾」**

**裁定**：接受 `SOURCE_DEGRADED` 追加在 `IrPrecision` 枚举末尾（`SOURCE_FULL` / `SOURCE_LOWERED` / `BYTECODE` / `BYTECODE_SYNTHETIC` 之后），
**不**升级 `IrSerializer` 的枚举编码。

**实测依据（全仓 288 个生产 Java 文件）**：`IrPrecision` 的**全部**使用点都是
`==` 跟**具名常量**比较（`ModelCoverageComputer.java:133`、`JavaTacFrontend.java:190`、
`KotlinTacFrontend.java:432`、`TsTacFrontend.java:282`、`PhpTacFrontend.java:233`、
`PythonTacFrontend.java:259`、`CSharpTacFrontend.java:247`、`GoTacFrontend.java:207`、
`ArktsTacFrontend.java:272`、`ScalaTacFrontend.java:238`、`SidecarTacMapper.java:134`、
`TacFunction.java:596`、`TacBuilder.java:191`），
**零处**按序比较（`<`/`>`/`compareTo`/`ordinal()`）——全仓仅有两处 `compareTo`，
分别是 `CopyEquivalence.java:218` 与 `LocalSolverBridge.java:360` 的**字符串**比较，与精度无关。
⇒ **枚举顺序从来不是语义**，追加末尾是安全的。

**配套硬规则（新增）**：任何新增精度档位**不得**引入序数比较；
判断一律走查询方法 `preservesSemantics()` / `hasSemanticLoss()`（IR-A §2 已定义）。
⇒ **门禁**：新增枚举常量时，用 grep 确认没有新增 `<`/`>`/`ordinal()` 用法。

### 8.2 `TacType.domain` 是否参与 `equals` —— **采纳「不参与」**

**裁定**：`domain` **不参与** `TacType.equals`/`hashCode`；改用等价性查询方法
`sameDomain(TacType other)`（IR-D §2 已定义）。

**实测依据（三处「类型相等 ⇒ 数值行为相同」的隐含假设，全部会因此破裂）**：

| 位置 | 现有用法 | 若 `domain` 参与 `equals` 会怎样 |
|---|---|---|
| `translate/ExpressionTranslator.java:1430` | `TacType.INT.equals(folded.type()) ? TacType.INT : TacType.LONG` | 常量折叠会**静默选错宽度**（`domain=Integral(32)` 的 INT 与普通 INT 不相等 ⇒ 折成 `LONG`）⇒ **溢出/下溢按 64 位判定** ⇒ 误报 |
| `points/AbstractObject.java:97` | `type.equals(other.type)` 是**指向对象的身份**的一部分 | 同一个运行期对象会因「推断出的 domain 不同」被**拆成多个 `AbstractObject`** ⇒ 污点跟踪断裂 |
| `ir/TacCfgBuilder.java:158` | `!t.equals(f)` 决定两条指令的类型是否「同一个」 | 数值域差异会被误当成类型差异 ⇒ CFG 合并/分裂判断出错 |

⇒ **先例只支持 `nullable` 参与**：`TacType.equals`（`:232-243`）的注释明确写了
「Nullability participates in equality」——因为**可空性是类型级事实**，
而 `domain` 是**值域事实** ⇒ 二者不同层，不该进同一个 `equals`。

### 8.3 wire 协议是否带版本号 —— **采纳「带」**

6 个 sidecar 与 Java 引擎是**分别部署**的独立进程，加字段必须能新旧互通。
⇒ 采纳 `wireVersion` + `capabilities` 数组（`08` §3 已给出形状）。
审计已证明现在的协议**没有**协商机制（TS 发 `returnType: undefined`，Java 侧直接当缺失处理）——
**先接线再扩协议**是硬顺序（`10` 的 S0）。

### 8.4 `TacTypeDecl` 的迁移期 —— **采纳「以消费方迁移完为准」**

新旧两套类元数据并存到**引擎消费方全部迁移完**为止，
并加**门禁禁止新代码读旧路径**（旧路径 = `TacProgram.types` 缺席时的 `classFieldAnnotations` 兜底）。

### 8.5 形参元数据的两套形状（`06` 的 Q11） —— **采纳 `IR-AO`**

`IR-AO` 的 `List<TacParameter>` 是**唯一存储形状**；`04` 的 `IR-AC` 降级为它的**退化视图**。
理由：`TacParameter` 里已有 `optional`/`vararg`/`defaultFunction` 三个槽位，
一个存储就够；`Map<String,ParamOption>` 会逼出**第二套存储**（两处都要同步、`equals`/`hashCode` 各改一次）。
⇒ `06` §13.2 的 Q11 与 `04` §10 的 `IR-AC` 已同步改写。

### 8.6 文档长度 —— **不压缩**

`07` 的逐条覆盖粒度是本轮的核心价值（每条都有「现状证据 → TAC 形态 → IR 需求 → 改动落点」四件套），
压缩成家族级会丢掉可施工性。⇒ **保留现状粒度**。

---

## 9. 补遗：IR-I..IR-Q（「完整支持」所需的第二批能力）

> 本节是 `02-覆盖矩阵与不可支持项.md` §2 的摘要。**完整定义以 02 号文档为准**，本节只给索引与一句理由。

| 提案 | 名称 | 为什么需要（一句） | 详细位置 |
|---|---|---|---|
| **IR-I** | `TacBranchRegion` 分支区域 | 分支的正确性不只是「有几条 BRANCH」，还包括「哪些 case、绑定什么、守卫吗、穷尽吗、default 在哪、fall-through 到哪」——这些是**结构**，指令流装不下 | 02 §2 IR-I |
| **IR-J** | `THROW` 的抛出类型（复用 `ctorType`） | 现在抛出点在 TAC 里**没有类型**，catch 只能靠 CFG 侧比对自己也拿不到的类型 | 02 §2 IR-J |
| **IR-K** | 类型的三个维度：类型实参 / 联合 / 类型变量 | 「`A\|B` 拼成假类名」是**错的**（不是未知）；且泛型实参、联合、类型变量是**正交维度**，加 `Kind` 表达不了组合 | 02 §2 IR-K |
| **IR-N** | 类型声明完整化（类型参数 / 允许的子类型） | sealed / permits 完全没处理；穷尽性检查需要知道子类型集合 | 02 §2 IR-N |
| **IR-L** | 挂起 / 恢复（`Effect.SUSPEND` + `resumePoints`） | `suspend` / 协程需要能标出「这里可能挂起」与「恢复点在哪」；`Effect` 只有 DEFERRED/CONCURRENT 两个值且 Kotlin/Scala 从未用过 | 02 §2 IR-L |
| **IR-M** | 临界区（`criticalSections`） | 原子性 checker 存在，但**临界区范围**在 TAC 里没有 ⇒ 无法判断两条写是否同锁 | 02 §2 IR-M |
| **IR-P** | **全语言统一的 `calleeSig` / arity / 接收者规范** | 这是**跨函数污点能否跨过方法边界**的总开关（Python 在每个方法边界断掉；Java for-each 用的是合成短名 `iterator/0`） | 02 §2 IR-P |
| **IR-Q** | 强制转换与相等语义的引擎侧规则表 | PHP `"1abc"+1 == 2` 与 TS `"1abc"*1 == NaN` 不同；PHP `"0"` 是假而 JS 是真 | 02 §2 IR-Q |

⇒ **IR v2 合计 17 个提案（IR-A..IR-Q），全部是「加字段」或「加元数据类」**，
**没有一个新增 instruction Kind、没有一个新增 operand 变体**——
这正是 §0.2 形状选择的回报，也是「TAC 能完整支持全部语言全部特性」在 IR 侧的根据。

---

## 10. 补遗：各语言文档的补充 IR 提案（全局编号索引）

### 10.1 为什么要单开一个编号空间

§2 与 §9 的 17 个提案是**跨语言共享**的主命名空间（`IR-A`..`IR-Q`；`IR-O` 刻意跳过，
不在中间插号，避免已有引用漂移）。

各语言文档在落地时还会发现**主命名空间覆盖不到的、该语言特有的形状需求**
（例如 Kotlin 的模式绑定要记「第几个槽」、C# 的 `ref`/`out` 实参要记「输出到哪个变量」）。
这些提案**只在对应语言文档里定义**，但**编号必须全局唯一** ——
否则「`R3`」在 TS 文档里指联合类型、在 Go 文档里指 `TacGuard` 组合子，
跨文档引用就变成了噪音。

⇒ 约定：**补充提案一律用 `IR-xx`（两个大写字母）**，从 `IR-R` 起顺序分配，
由本文档统一登记。**主命名空间 `IR-A`..`IR-Q` 不再扩充** ——
补充提案若能靠主命名空间的能力表达，就**不应该**成为新提案。

### 10.2 全局分配表

| 编号 | 归属文档 | 名称 | 为什么需要（一句） |
|---|---|---|---|
| `IR-A`..`IR-Q` | 本文 §2 / §9 | 主命名空间 17 条 | 跨语言共享，见 §2 与 §9 |
| **IR-R** | `05` TS/ArkTS | `BinaryOperator` 增 `POW` | `**` 现在近似成 `MUL`，语义不同（`2**3` ≠ `2*3`） |
| **IR-S** | `05` TS/ArkTS | `TacGuard` 增 `PropertyPresence` 变体 | `in` 判定「键是否存在」，与 `Membership`（值∈集合）语义不同 |
| **IR-T** | `05` TS/ArkTS | `TacType` 表达联合成员集合 | `string \| null` 现在被塌成 `STRING`（`TypeFlags` 首位匹配） |
| **IR-U** | `05` TS/ArkTS | 任意精度整数（`bigint`） | 现在映成 `LONG`（64 位），而 `bigint` 是任意精度 |
| **IR-V** | `05` TS/ArkTS | `ARRAYSTORE` 的「删除键」标志 | `delete obj.k` 现在无法与「键值为 `undefined`」区分 |
| **IR-W** | `05` TS/ArkTS | `CALL`/`NEW` 的「剩余参数数组」 | `f(...args)` 的参数个数运行期才定，`args` 是定长列表 |
| **IR-X** | `05` TS/ArkTS | 常量层区分 `undefined` / `null` | 现在 `NULL` 同时承担两者 ⇒ 可选链结果被当成「可能为 null」 |
| **IR-Y** | `05` TS/ArkTS | `TacTypeDecl` 增索引签名 / 类型参数 | §IR-E 的 `TacTypeDecl` 没有这两个位置 |
| **IR-Z** | `05` TS/ArkTS | 分支内的类型收窄 | `TypeTest` 的结果没有回写到变量类型 ⇒ `x instanceof Y` 之后看不到 `Y` 的字段 |
| **IR-AA** | `04` Kotlin/Scala | 模式绑定的访问路径与模式种类 | `TacBinding` 只有名字与类型 ⇒ 无法表达「绑定是被匹配值的第几个槽」 |
| **IR-AB** | `04` Kotlin/Scala | `TacMemberKind` 增 `ENUM_ENTRY` | 枚举条目既不是方法也不是字段 ⇒ 引擎无法与静态字段区分 |
| **IR-AC** | `04` Kotlin/Scala | 形参的可选性 / 可变参声明 | 命名实参重排与缺省补参都需要它；现状 `calleeSig` 与签名对不上 ⇒ 调用图边直接断 |
| **IR-AD** | `04` Kotlin/Scala | `as?` 的「失败即 null」转换形态 | `CAST` 的 `castType` 表达的是「一定会成功」 |
| **IR-AE** | `04` Kotlin/Scala | `calleeSig`（IR-P）的 Kotlin/Scala 细则 | IR-P 是规范而非字段，这一节给出该语言的落法 |
| **IR-AF** | `07` Go/C# | `TacGuard` 的合取 / 析取组合子 | 多条件的守卫需要能组合，现只有原子守卫 |
| **IR-AG** | `07` Go/C# | `TacMemberKind` 追加 4 个成员种类 | 闭包对象 / 事件访问器 / 局部函数 / 终结器 |
| **IR-AH** | `07` Go/C# | `TacInstruction` 表达「多输出」与「按引用实参」 | Go 的 `(v, err) := f()` 与 C# 的 `ref`/`out`/`in` |
| **IR-AI** | `07` Go/C# | 方法集归属：值接收者 vs 指针接收者 | 同一方法两套方法集 ⇒ 调用解析漏 |
| **IR-AJ** | `07` Go/C# | `UnaryOperator` 增 `CLEAR` | Go 的 `clear(m)` 无对应算子 |
| **IR-AK** | `07` Go/C# | `TacValueDomain` 的「宽度未定的整数」 | Go 的 `int` 宽度取决于构建目标 |
| **IR-AL** | `03` Java | 声明侧的两个补充通道：`TacFunction.throwsTypes` + `TacVar.annotations` | `throws` 子句与局部变量声明注解今天**完全消失**（`getThrownExceptions` 全仓零命中）⇒ catch 匹配无依据、空值分析看不到声明注解 |
| **IR-AM** | `03` Java | 注解类型的零值成员 | `translateAnnotationMember` 对无默认值成员发 `NOP` ⇒ 该成员**没有返回值** |
| **IR-AN** | `03` Java | 类型使用注解（type-use） | IR 只有 class/method/field/param 四个注解通道，类型注解无处可放 |
| **IR-AO** | `06` PHP/Python | 形参声明元数据（`TacFunction.parameters`）+ `CALL/NEW.argCount` | 形参的名字/类型/实参个数今天无处承载 ⇒ `calleeSig` 的 arity 与签名对不上 |
| **IR-AP** | `06` PHP/Python | 类型交集 / 晚绑定 / trait 链 / 属性标注 | Python 的 `Union`/`Protocol`/属性注解在 IR 里无处放 |
| **IR-AQ** | `06` PHP/Python | 键存在性与容器删除（守卫 + 数组存删标志） | 与 `05` 的 `IR-S`/`IR-V` **是同一形状** ⇒ 实现时合并，不要两套 |
| **IR-AR** | `06` PHP/Python | 实参按名绑定 + 剩余实参（展开） | 与 `05` 的 `IR-W` **是同一形状** ⇒ 实现时合并 |
| **IR-AS** | `06` PHP/Python | 作用域绑定与别名 | 闭包/全局/内置名的绑定关系无处放 |
| **IR-AT** | `05` TS/ArkTS | `BinaryOperator` 末尾追加 `LOOSE_EQ` / `LOOSE_NE` | TS/ArkTS 的松散相等 `a == b` 现在被当成严格相等 ⇒ `1 == "1"` 这类事实丢失 |
| **IR-AU** | `05` TS/ArkTS | 类型层计算的结果约束（`TacTypeLayer`） | `keyof` / 条件类型 / 映射类型 / 索引访问类型 / 模板字面量类型 / `infer` / `const` 类型参数**都不产生运行期值**，但**产生可静态求出的形状**（键名集合、成员表、字符串模式）⇒ 形状必须有落点，否则「按类型推断的规则」在引擎侧无从判定 |
| **IR-AV** | `05` TS/ArkTS | 装饰器的运行期应用（`TacDecorator`） | 装饰器**运行期就是一个被调用的函数**（legacy：`dec(C)`；standard：成员阶段 `dec(target, ctx)` → 类阶段 `dec(C)`）⇒ 装饰器实参的污点必须能流进被装饰实体；「返回值是否替换被装饰实体」也是运行期事实 |

> 上表**按编号顺序排列**；提案的**归属文档**与编号顺序不必一致（`03` 与 `07` 交错），
> 唯一硬要求是**全局唯一**。

> ⚠ **两处需要合并考虑**（不是冲突，是同一 IR 类的两个扩展）：
> - `IR-S`（`05`，`PropertyPresence` 守卫变体）与 `IR-AF`（`07`，合取/析取组合子）
>   都扩展 §IR-C 的 `TacGuard` ⇒ 实现时应**在同一个 `TacGuard` 层次里一起做**，
>   否则会出现两套互不兼容的守卫代数。
> - `IR-AE`（`04`）是 `IR-P` 的语言细则，**不是新字段** ⇒ 实现时归到 `IR-P` 下。

⇒ **合计：主命名空间 17 条 + 补充提案 32 条**，全部仍然是
→「加字段」或「加元数据类」或「枚举末尾追加常量」——
**没有一个新增 instruction Kind、没有一个新增 operand 变体、没有一个新增 `TacType.Kind`**。

> **`IR-AU` / `IR-AV` 是本轮（2026-11-17）为「继续覆盖各语言未覆盖语法」新增的两条**：
> 它们把 `05` 文档里 **12 条**纯类型层构念（`keyof` / 条件类型 / 映射类型 / 索引访问类型 /
> 模板字面量类型 / `infer` / `const` 类型参数 / `import type`）与 **3 条**装饰器构念
> 从「永久例外」改成「有精确落点」⇒ 详见 `05` §7.4 与 §4.1。
> **两条都不产生新指令**：`IR-AU` 是**类型层约束**（挂在 `TacType` / `TacTypeDecl` 上的零指令元数据），
> `IR-AV` 的运行期部分**复用现有 `CALL`**，只有「装饰器链 + 返回值替换」需要新元数据类。

### 10.3 字段级细化登记表（**不占新编号**，但必须登记一次）

各语言文档在写方案时反复提出**同一个 IR 类上的字段细化**（不是新的 IR 提案）。
按 `01` §0.4 的纪律，它们**不占新编号**，但**必须在本文登记一次** ——
否则三个文档各写一遍，读者无从判断「这是同一处还是三处」，实现方也会漏改。

| 细化项 | 落在哪个既有 IR 上 | 归属文档 | 承载的事实 | 为什么不能新开编号 |
|---|---|---|---|---|
| `TacMemberRef.modifiers`（成员修饰符列表） | **IR-E**（声明面元数据）的细化 | `03` Java、`04` Kotlin/Scala、`07` C# | Java 的 `strictfp`/`native`/`abstract`/`synchronized`/`default`、字段的 `static`/`final`/`volatile`/`transient`；Kotlin 的 `override`/`open`/`final`/`abstract`/`data`/`sealed`/`inner`/`companion`；C# 的 `sealed`/`new`/`required`/`readonly`/`volatile`/`ref struct` | 修饰符是**成员身份**的一部分，与 `visibility`/`isStatic` 同属 `TacMemberRef` ⇒ 开新编号会造出第二套「成员修饰符」容器 |
| `TacTypeDecl.isSealed` / `isFinal`（`isAbstract` 已存在） | **IR-E** / **IR-N** 的细化 | `04` Kotlin/Scala | Scala 的 `final`/`sealed`/`abstract`/`case` 修饰符；`sealed` 的 `permittedSubtypes` 供 §6.3 的 `exhaustive` 判定 | 同样落在「类型声明的身份」上；`permittedSubtypes` 本来就是 IR-N 的既有字段 |
| `TacVar.isFinal`（局部量的 `final`） | **IR-AL**（`03` 的两个补充通道）的细化 | `03` Java | Java `final` 局部量（是**编译期常量**的前提） | 局部量元数据已有 IR-AL 的通道；`final` 只是它的一个布尔位 |
| `TacTypeDecl.typeParameters[].variance` | **IR-N**（类型声明完整化）的**字段扩展** | `04` Kotlin/Scala、`07` C# | Kotlin 的 `in`/`out`/`*`、C# 的 `in`/`out`（含 `+`） | 变型是**类型参数**的属性，与 `bound`/`typeArguments` 同属一条记录 ⇒ 加一个枚举槽位即可 |
| `TacTypeDecl.typeParameters[].bound`（泛型约束） | **IR-N** 的既有字段（**已登记**，此处只做索引） | `07` C# | C# 的 `where T : IDisposable` 等约束 | 复用 `bound`，**不新增** |
| `TacVisibility`（可见性分级枚举） | **IR-E** 的既有细化（本文 §IR-E(d) 已定义） | `04` Kotlin/Scala、`03` Java | Kotlin 的 `internal`（模块级）不是布尔能表达的 | 本文 §IR-E(d) 已写死枚举，五档足够 |
| `TacMemberKind` 的新成员种类（`EVENT_ACCESSOR` 等） | **IR-AG**（`07` 提出的 4 个成员种类） | `07` C# | 闭包对象 / 事件访问器 / 局部函数 / 终结器 | 枚举末尾追加常量，**IR-AG 已占号** |
| `TacType.typeLayer` + `TacTypeDecl.typeLayers` + `TacTypeDecl.typeOnlyImports` | **IR-AU**（类型层约束）的字段扩展 | `05` TS/ArkTS | `keyof` 求出的键名集合、条件类型的两个分支、映射类型的键/值形状、模板字面量类型的字符串模式、`const` 类型参数的字面量类型、以及类型级导入导出的模块边 | 类型层约束是**新元数据类**，但它**挂在既有的 `TacType` / `TacTypeDecl` 上** ⇒ 字段登记在这里，类的定义在 `05` §8 |
| `TacTypeDecl.decorators` + `TacMemberRef.decorators` | **IR-AV**（装饰器应用）的字段扩展 | `05` TS/ArkTS | 装饰器链（按运行期调用顺序）、每个装饰器的 callee 签名、以及「返回值是否替换被装饰实体」 | 装饰器链是**声明面的事实**（谁被装饰、装饰器是谁），与 `annotations` 同属 `TacTypeDecl` / `TacMemberRef` ⇒ 不另开容器 |

> **纪律**：上表任何一行**要改实现**，都必须回到它登记的 IR 上改；**不许**在语言文档里
> 私自再加一份同义的字段（那会让「同一处细化」变成「三处各自实现」）。
> 本表**没有新增 IR 编号** ⇒ `01` §10.2 的全局分配表是 **48 条**（本轮新增 `IR-AU` / `IR-AV` 两条；本表登记的是它们**落在既有 IR 类上的字段**）。

