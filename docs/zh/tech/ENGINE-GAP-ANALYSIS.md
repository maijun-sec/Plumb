# 引擎差距分析：我们还缺什么，以及工作清单

[English](../../tech/ENGINE-GAP-ANALYSIS.md) | [中文](../README.md)

> 状态：对当前引擎的实测审计
> 范围：数据流引擎的**精度与完备性** —— 不是规则目录的广度
> 方法：下文每一条论断都是本仓库的**实测**属性，并标注 `file:line`

---

## 0. 结论先行

**瓶颈不是规则目录，而是引擎的精度。**

两项测量可以定案：

| 测量项 | 数值 | 含义 |
|---|---|---|
| 运行时目录里的 sink family | 43 | 33 个 sink family + 10 个 source family |
| pattern 条目 | 2 466 | 其中 2 065 条显式声明参数位置 |
| 已发布 checker 绑定的 taint 规则 | 55 | 每条绑定一个 family 和一个 CWE |
| **配置里声明但从未被消费的精度特性** | **6** | 见 §1 |

最后一行才是关键。引擎在配置里**声明**了路径敏感、k-CFA 上下文深度、summary 缓存上限和
反向遍历深度，而**没有任何分析代码读取它们**。用户设置了 `path_sensitivity: full`，并读到了一段
描述三档精度的注释 —— 而他描述的引擎并不具备这些。

这比缺少一个功能更糟。缺少功能是已知的边界；一个被声明却失效的旋钮是一句空话，
而且这类差距往往能存活多年 —— 因为它错了也不会有任何东西失败。

**下面的工作清单按"每单位工作换到的精度"排序。** 第 1–4 项刻意选的是便宜的项目；
第 5 项是昂贵的那一项，已经拆成了若干阶段。

---

## 1. 失效的配置：声明了却从未被消费

每一行都通过 grep 全仓库 `src/main` 的消费方验证过。"消费方"指**能到达分析行为**的调用点，
而不是被存下来又读回来的字段。

| # | 声明位置 | 声称的含义 | 实际消费方 | 状态 |
|---|---|---|---|---|
| 1 | `plumb-dfa/src/main/resources/tuning.yaml:28` | `full` / `windowed` / `none` 路径条件 | **0** | **失效** |
| 2 | `plumb-dfa/src/main/resources/tuning.yaml:14` | k-CFA 深度 (0/1/2/3) | **0** | **失效** |
| 3 | `plumb-dfa/src/main/resources/tuning.yaml:16` | summary 缓存大小 | **0** | **失效** |
| 4 | `plumb-dfa/src/main/resources/tuning.yaml:17` | 反向遍历深度 | **0** | **失效** —— 它命名的类并不存在 |
| 5 | `plumb-dfa/src/main/resources/checkers.yaml`（按 checker） | 按 checker 的上下文深度 | **0** | **失效** |
| 6 | `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintedCheckerBase.java:4123` | sink 的路径条件 | 声明 + 2 个调用点，**0 个覆写** | **从未被喂入** |

第 6 行最尖锐。这个钩子是完整且正确的：`composePathCondition`
（`TaintedCheckerBase.java:607`）把本地谓词和被调用方提供的片段合并，bridge 在
`TaintedCheckerBase.java:3973` 被查询，`UNSAT` 结论会抑制 finding。
**整条机制都在，而输入永远是 `null`。**

后果：求解器实际只对**一类** checker 可达 —— boundary checker，它们自己构造合取式
（`checkers/boundary/BoundaryConstraintChecker.java:826`）。
而误报真正发生的 55 条 taint 规则，**一次都没有问过求解器**。

### 1.1 同样形态的另外两处死结构

- `plumb-dfa/src/main/java/org/plumb/dfa/taint/AccessPathTaintStore.java` —— 为
  `TacAccessPath`（`plumb-tac/src/main/java/org/plumb/tac/ir/TacAccessPath.java`）准备的
  按路径索引的污点存储。**在它自己的文件之外零消费方。**
  这套访问路径词汇已经设计好、可序列化、有测试，但分析仍然用更粗的
  `receiver.fieldName` 字符串来跟踪字段污点。
- `plumb-dfa/src/main/java/org/plumb/dfa/summary/Operation.java:259` ——
  `PointsToPropagate(allocSites)`。**零生产者，零消费者。** 指向分析算出了堆对象，
  而堆模型从未抵达污点格。

### 1.2 当前分析的真实边界

直说，免得下游还要猜：

- 分析是**流敏感**的 —— 指令按 BB-RPO 顺序访问，块内向前
  （`plumb-dfa/src/main/java/org/plumb/dfa/engine/DfaEngine.java:94`）。
- 分析**不是路径敏感**的 —— 没有 CFG 合流，没有格的 meet。每个函数一遍前向扫描
  （`DfaEngine.java:1737`），一个扁平的事实表，不重迭代。
- 分析**不是调用字符串上下文敏感**的 —— summary 缓存按普通函数签名做键
  （`plumb-dfa/src/main/java/org/plumb/dfa/summary/FunctionSummaryCache.java:77`），
  所以同一被调方的两个调用点共用一份 summary，与调用图策略无关。
- 分析**不是对象敏感**的 —— 污点挂在 `TacVar` 上，从不挂在分配点上。
  指向分析存在并在运行，但它的结果不会让污点更精确。

---

## 2. 优化工作清单

每一项都写明**关闭它的验收测试**。一项只有在"改动前测试失败、改动后通过"时才算关闭。

### 第一梯队 —— 便宜，且它们能消除空头承诺

| ID | 工作 | 验收 | 状态 |
|---|---|---|---|
| **G1** | 让 `path_sensitivity` 有实际含义：它决定是否做源到 sink 的可达性判定。`none` 关闭，`full` 开启。 | `path_sensitivity: none` 报出的 finding，在 `full` 下被抑制。 | **已完成** |
| **G2** | 强制 `summary_cache_size`：缓存超出上限时拒绝新的 summary 并计数，而不是无限增长。 | 一个填满缓存并越过上限的测试能观察到拒绝及其计数。 | **已完成** |
| **G3** | 强制 `context_depth`：设为 0 时关闭跨函数 summary 折叠，分析留在单个函数内。 | 一个测试观察到跨函数流在深度 1 时报出、在深度 0 时消失。 | **已完成** |
| **G4** | 不再声称一个没人读的 `reverse_walk_max_depth`。 | 配置文件不再提及一个不存在的类。 | **已完成** |
| **G5** | 在分析摘要里报告 summary 缓存的命中率与拒绝次数。 | 报告里包含这个数字。 | **未开始** |

G1–G4 不是功能。它们是**把一句谎话收回来**。
**它们排在最前，是因为一个声称"path sensitivity: full"却表现得像 "none" 的工具，
会连带让它打印的每一个别的数字都失去可信度。**

G3 刻意比名字暗示的更窄，配置文件里也是这么写的。引擎的 summary 本身就是一次折叠
—— 一个函数的 summary 装着它自己的被调用方贡献的事实 —— 所以一条调用链会累积进同一份
summary，链的深度并没有被单独设限。因此这个设置区分的是「不做跨函数分析」和
「有跨函数分析」，它还没有区分 k-CFA 深度 —— 那是 G8。

### 第二梯队 —— 规则真正需要的精度

| ID | 工作 | 验收 | 状态 |
|---|---|---|---|
| **G6** | 让可达性判定对每条规则都可达：每个函数只收集一次守卫事实，并据此判定不可达。 | 守卫自相矛盾的 source 到不了 sink；同样的代码没有矛盾时仍然报出。 | **已完成，形式不同** |
| **G7** | 喂给求解器的应是**沿实际路径组合出的路径条件**，而不是逐 sink 的谓词。 | 只有当路径上各守卫的合取为 `UNSAT` 时才抑制 finding。 | **未开始** |
| **G8** | 当调用图策略为 `1cfa` 或 `2cfa` 时，让 summary 缓存**感知调用字符串**，使 k-CFA 深度旋钮成为真实能力。 | 同一被调方的两个调用点，参数不同时产出不同的 summary。 | **未开始** |
| **G9** | 把指向分析接进污点格，让 `Operation.PointsToPropagate` 有生产者和消费者。 | 同一类的两个不同实例上的污点不再合并。 | **未开始** |

### 2.1 G6 的实现形式不同，而这个理由值得记下来

原计划是：给污点基类配守卫跟踪器，在基类实现 `pathConditionForSink`，让求解器判定路径是否可达。
机制现在已经到位而且有效 —— 但**判定是由算术做的，不是由求解器做的**，
这不是风格选择。

**两个求解器 bridge 对无法绑定的变量都回答 SAT。** 那是它们被设计成的样子：
bridge 是误报过滤器，「路径可能是可达的」是保守答案。
而「是否存在一个取值同时满足两个矛盾的界」不是那种问题 —— 它是一个关于算术的问题，
算术能精确回答它。所以矛盾在答案精确的地方判定，求解器留给它真正能求值的谓词。

由此带来的几点值得知道：

- 这个判定**不依赖**求解器，所以它**不依赖**本地库是否可用。
  在 Z3 加载不了的机器上，这次扫描仍然会过滤掉矛盾。
- 这个过滤器**刻意很窄**。只看 **source 变量自己**的守卫，因为另一个变量上的守卫说明不了
  这个 source 能否到达 sink，而函数中不同位置记录的两个守卫在 sink 处也不同时成立。
  这两条限制都是为了保证判定不会抑制一个真实存在的 finding。
- 守卫事实由引擎**每个函数收集一次**，不是每个 checker 收集一次。守卫是关于函数的事实；
  每个 checker 各自推导，正是两个 checker 对「某个值是否被守卫过」产生分歧的原因。

剩下的求解器形态的工作是 G7：跨多个变量的路径条件，那需要求解器对一个合取式推理，
而不是对单个变量的界推理。

### 第三梯队 —— 表达力天花板

IR 有 22 个操作码（`plumb-tac/src/main/java/org/plumb/tac/ir/TacInstruction.java:44`）。
它没有循环、没有 switch、没有 lambda、没有并发、没有动态分派操作码。
这本身不是缺陷 —— 前端把这些构造降解成分支和调用，这正是既定设计。
它之所以重要，是因为**第二梯队的三档精度全部受它约束**：

| IR 中缺失 | 卡住了什么 |
|---|---|
| 循环携带的归纳变量 | 循环敏感的污点 |
| 动态分派标记 | 接收者类型敏感的 sink 匹配 |
| 显式捕获 / 写回 | 闭包状态传播 |

### 第四梯队 —— 明确声明为不支持，且目前这是对的

| ID | 工作 | 理由 |
|---|---|---|
| **G10** | 通过 `TacAccessPath` 做字段敏感 | 词汇已存在但存储没有消费方。等对象敏感（G9）落地后再议；没有堆模型，访问路径无处可指。 |
| **G11** | 每语言的 `TaintBehaviour.PRODUCES_TAINT` 与 `BLACK_BOX` | 两个枚举常量都存在，**没有任何表填它们**。语言语义注册表只覆盖 Java 和 Kotlin（`plumb-dfa/src/main/java/org/plumb/dfa/semantics/LanguageSemanticsRegistry.java:77`）；Scala 和 Groovy 拿到的是空表。 |
| **G12** | 一个 `ReverseTaintWalker` | 配置里点名了，它不存在。G4 会把名字删掉；如果这项技术以后需要，它会作为一个真类回来。 |

---

## 3. 三种语言相对对标的位置

本节回答一个比 §2 更窄的问题：**报告更少，是因为语言通道，还是因为引擎？**
答案经过测量，不是假设。

### 3.1 语言通道已经做到的部分

- IR 层与语言无关：`plumb-tac/src/main/java/org/plumb/tac/ir/` 里没有任何语言特定 API 的引用。
- 规则层与语言无关：`TaintRule` 带一个可选的 `languages` 集合，
  除非 `by_language` 块收窄，否则同一个 family 服务三种语言。
- sink 目录有 26 个 `by_language` 块，语言差异表达在配置里而不是代码里。

### 3.2 仍然存在的语言特定缺口

| 缺口 | 出现在哪 | 后果 |
|---|---|---|
| Scala 闭包捕获的可变状态没有写回调用方 | `plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java` | 闭包对捕获 `var` 的写入丢失，调用方读到陈旧值。静默发生，因此给出的是错误的"干净"结论，而不是一条告警。 |
| Scala `for` comprehension 降解进匿名函数 | 同上 | 调用点处累加器的值不反映循环体的效果。 |
| Kotlin `is` 模式分支 | `plumb-frontend/src/main/java/org/plumb/tac/frontend/kotlin/KotlinTacTranslator.java` | 现在会作为降解缺口**被报告**，而不是被静默跳过 —— 是变显式了，还不是变正确了。 |
| Java switch 守卫、枚举常量体覆写 | `plumb-tac/src/main/java/org/plumb/tac/translate/` | 仍然静默。 |

这些的契约都在 `docs/tech/TAC-COVERAGE.md`：**一个缺口要么被正确降解，
要么作为 `FunctionProblem` 被报告，要么它就是缺陷。**
唯一不可接受的结果是静默缺口给出错误答案。

### 3.3 引擎缺口对三种语言一视同仁

这是结构上最重要的一点。§2 里的项**不是** Kotlin 专属，也不是 Scala 专属。
形状相同的 Kotlin 文件和 Java 文件得到相同的精度，因为它们到达同一个 `TaintedCheckerBase`。
修引擎一次就同时修好三种语言。

**这就是 §2 排在 §3 前面的原因。** 在引擎还声明着它并不具备的精度时去优化语言前端，
等于把一句空话乘以三。

---

## 4. 如何验证本文档的论断

这里的每个数字都是从仓库测量出来的，不是估算：

```bash
# 规则与 checker 数量
python scripts/check_doc_claims.py

# 目录的形态（family、pattern、参数位置）
python scripts/capability_baseline.py --check

# 源码侧的覆盖契约
python scripts/check_all.py

```

不同意某一行的评审者，应当能复现那条 grep。`消费方` 一列是关于**不存在**的论断，
而只有可搜索的不存在才可信 —— 所以每个失效行都点名了被检查的字段或钩子所在的文件。

---

## 5. 附录：原始测量值

| 测量项 | 数值 | 测量方式 |
|---|---|---|
| TAC 操作码 | 22 | `TacInstruction.Kind` 枚举常量 |
| Sink family | 43 | 运行时 `sink-families.yaml` |
| Pattern 条目 | 2 466 | 运行时 `sink-families.yaml` |
| 显式 `arg_index` 声明 | 2 065 | 运行时 `sink-families.yaml` |
| `by_language` 块 | 26 | 运行时 `sink-families.yaml` |
| 已发布 checker 绑定的 taint 规则 | 55 | 55 个 `TaintedCheckerBase` 子类 |
| `TaintBehaviour.PRODUCES_TAINT` 条目 | 0 | 语言语义注册表 |
| `TaintBehaviour.BLACK_BOX` 条目 | 0 | 语言语义注册表 |
| `AccessPathTaintStore` 消费方 | 0 | 全树 grep |
| `PointsToPropagate` 生产者 / 消费者 | 0 / 0 | 全树 grep |
| `pathConditionForSink` 覆写 | 0 | 全树 grep |
