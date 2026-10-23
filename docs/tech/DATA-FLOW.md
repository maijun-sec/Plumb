# 数据流分析（plumb-dfa）

本文档描述 `plumb-dfa` 的实际实现。全部内容对照源码核实；文中每个类名都可在
`plumb-dfa/src/main/java/org/plumb/dfa/` 下找到，引用格式为 `文件:行号`。

> **更正记录**：本文档早期版本把引擎描述为「IFDS 风格的污点传播」，并引用了
> `TaintAnalysis` / `TaintPropagator` / `TaintCatalog` / `CallResolver` / `Interval` /
> `IrDispatcher` 等类。**这些类在代码库中都不存在。** 引擎既不是 IFDS，也不是
> tabulation solver；它是一个 per-checker 的正向数据流引擎，配一个独立的指向性求解器
> 和一个只在特定检查里启用的 Z3 误报过滤器。下面的内容是重写后的真实结构。

---

## 1. 引擎骨架

### 1.1 入口与两个阶段

入口是 `DfaEngine`（`engine/DfaEngine.java`），对外三个运行模式：

| 方法 | 用途 |
| --- | --- |
| `run(TacProgram)` (`:261`) | 单程序 |
| `runSequential(TacProgram)` (`:1022`) | 单线程 |
| `runParallel(TacProgram, int)` (`:1002`) | 顶层 worklist 并行 |
| `runWithGlobalStore(List<TacProgram>, int)` (`:340`) | 跨文件全局 store（CLI 默认策略 `global`） |

所有模式最终都走同一个骨架。真正的调度在 `runWorklist`（`:1081`）：

```
initEngine(ctx)              // 所有 checker 一次
warmUpMethodSummaries(...)   // 路径无关的语法级摘要预热
computeParamOrigins(...)     // 跨函数形参来源标签，传播到定点
solvePointsTo(...)           // 全程序指向性，一次求解
  ↓
每个函数（依赖计数归零才入队）:
  analyseOneFunction(ctx, fn)      // 正向指令遍历
  summariseOneFunction(ctx, fn)    // 收集 summary 进共享 cache
  ↓
finish(ctx)
```

### 1.2 「逆向非流敏感 + 正向流敏感」到底对应什么

这是本引擎最容易被文档写错的地方，所以按代码逐条对照：

| 说法 | 代码里的对应物 | 是否准确 |
| --- | --- | --- |
| 逆向、非流敏感的前置分析 | `warmUpMethodSummaries` (`:606`) → `MethodSummaryBuilder.build` | **部分准确**。它是一次线性 IR 扫描，分类 return 形态、收集副作用，**没有数据流求解**。它是路径无关的语法摘要，不是反向流分析。 |
| 正向、流敏感的指令遍历 | `analyseOneFunction` (`:1214`)，按 `bbRpoOrder(fn)` 遍历 | **准确**。每个 checker 维护自己的正向格（taint / nullable / interval），在指令上传播。 |
| 指向性是独立前置求解 | `solvePointsTo` (`:667`) | **准确**。全程序一次求解，结果挂在 `AnalysisContext` 上，所有 checker 共享同一个不动点。 |

**关键事实：引擎没有「先跑一遍逆向、再跑一遍正向」的两个独立数据流 pass。** 只有一次
正向遍历；summary 是这次遍历的产物，而不是另一遍分析的输入。

### 1.3 指令顺序：BB-RPO，块内正向

`bbRpoOrder(TacFunction)`（`:1317`）：

- 基本块按 **reverse post-order** 排列（DFS 后序再反转，`:1347-1350`）
- 块内指令保持**原始正向顺序**（`:1358-1363`）

块内为什么不反转：引擎里每个 checker 都是正向格，反转块会把 use 排在 def 之前，直接破坏
传播。（`DfaEngine` 类注释早期声称「BB-RPO + intra-block reverse」，与实现不符，已更正。）

### 1.4 Checker SPI

`DfaChecker`（`engine/DfaChecker.java`）：

```
initEngine(ctx)
  for fn in callgraph RPO:
    initFunction(ctx, fn)
    for ins in bbRpoOrder(fn):
        if ins is CALL:  SummaryApplier.apply(ins, ctx, ins.args)
                         sideEffectConsumer().recordCall(ins, ctx)
        checkInstruction(ins, fn, ctx)
    finishFunction(ctx, fn)
  finish(ctx)
```

**summary 契约**：函数体内部的缺陷直接 `ctx.reporter().report(...)` 发出，**不进 summary**；
只有「调用方需要知道的事」才进 `FunctionSummary`，由 `FunctionSummary.merge` 合并进
`ctx.summaryCache()`，按函数签名索引。caller 侧遇到 CALL 时用 `SummaryApplier.apply`
消费它。

---

## 2. Fact 域

`fact/Fact.java` 的 `Fact` 是四元组 `(FactKind, operand, value, 来源指令)`。
`FactKind.Trait` 位标记引擎自动传播方式（`BuiltinFactKinds.java:19-37`）。

`BuiltinFactKinds.registryWithBuiltins()` 注册 **15 个**内置 kind（`:223-314`）：

| kind 常量 | 位置 | trait |
| --- | --- | --- |
| `dfa-taint-untrusted-source` | `:50` | SOURCE, PROPAGATED_BY_DECL, THROUGH_CALL |
| `dfa-taint-untrusted-sink` | `:60` | SINK, IS_PATH_SINK |
| `plumb.nullable.may-be-null` | `:71` | PROPAGATED_BY_DECL |
| `plumb.numeric.may-be-zero` | `:79` | PROPAGATED_BY_DECL |
| `plumb.resource.alloc-source` | `:90` | PROPAGATED_BY_DECL, THROUGH_CALL |
| `plumb.resource.free-sink` | `:100` | IS_PATH_SINK |
| `plumb.pointer.deref-sink` | `:111` | IS_PATH_SINK |
| `plumb.array.index-sink` | `:119` | IS_PATH_SINK |
| `plumb.numeric.overflow-hazard` | `:129` | NOT_PROPAGATED_THROUGH_LOOP, IS_PATH_SINK |
| `plumb.sensitive.data` | `:140` | SOURCE, BY_DECL, THROUGH_CALL |
| `plumb.sensitive.pii` | `:150` | 同上 |
| `plumb.loop.induction` | `:161` | NOT_PROPAGATED_THROUGH_LOOP |
| `plumb.call.unmodeled-callee-ret` | `:172` | BY_DECL, THROUGH_CALL |
| `plumb.call.return-value-folded` | `:198` | BY_DECL |
| `plumb.call.side-effect-recorded` | `:210` | BY_DECL |

**重要区分**：fact 域不等于「四类分析各有一个 fact kind」。实际只有 taint / sensitive
有 source 类 kind；boundary、points-to、control-flow **都没有对应的 fact kind**，它们由各自
独立的机制计算（见下）。

---

## 3. 四类分析

### 3.1 污点分析（taint）

**驱动类**：`taint/TaintedCheckerBase.java`。它**同时是抽象基类和传播引擎**——这与文档里
「子类只需几行」的印象不同：`checkers/taint/` 下约 46 个具体 checker 大量覆写
`checkInstruction`。

- 状态：`taintedVars: Map<TacVar, String>`（`:140`）与 `taintPaths`（`:149`）
- 传播：`propagateMOVE`（`:1158`）等按指令种类分派
- sink 判定：按 `sinkFamilyKey` 查 `SinkFamilyRegistry`（`config/SinkFamilyRegistry.java:22`，在
  `org.plumb.dfa.config` 包），**不是** IFDS 框架
- 跨函数：`FunctionSummary` + `Operation.TaintPropagate`

**source / sink / sanitizer 表在哪里**：没有 `TaintCatalog`。实际是两个 registry：
`TaintRuleRegistry`（`taint/TaintRuleRegistry.java:27`，按 rule id 与 sink family 建索引）与
`SinkFamilyRegistry`（`:22`，带 `lookupAll` 缓存）。

### 3.2 边界分析（boundary）

`checkers/boundary/`，全部继承 `BoundaryConstraintChecker`（`:64`）。每个子类把算术谓词
写成字符串交给 Z3：

| checker | 检测 | 谓词位置 |
| --- | --- | --- |
| `ArrayBoundsConstraintChecker` | 数组越界 | `idx < 0`（`:85`） |
| `DivZeroConstraintChecker` | 除零 | `rhs == 0`（`:71`） |
| `OverflowConstraintChecker` | 溢出 | `:46` |
| `UnderflowConstraintChecker` | 下溢 | `result < typeR.lo`（`:103`） |

区间抽象的实现类是 `checkers/boundary/Range.java`（**不是** `Interval`——该类不存在），
带方法长度阈值的 `Range` 定义在 `:56`。

### 3.3 指向分析（points-to）

`points/PointsToSolver.java`。源码明确声明这是**约束传播求解器，不是 tabulation 求解器**
（`:38-40`），结构参考 Qilin（`:36`）。

- **堆抽象**：field-sensitive `(receiver, name)`（`:80`）、数组单元（`:83`）、以及独立的
  Map/List `containerCells`（`:92`）
- **上下文敏感**：`AbstractObject.Policy` 三档，默认 **`CALL_SITE`（1-CFA）**
  （`points/AbstractObject.java:304-306`），可经 `TuningConfig.effectivePointsToPolicy()`
  覆盖
- **不使用 Z3**：`points/` 下没有任何 solver 引用；Z3 只出现在 `solver/` 的路径条件里
- 求解一次（`DfaEngine.solvePointsTo`，`:667`），失败降级为「无 alias 信息」而非崩溃

### 3.4 控制流检查（controlflow）

`checkers/controlflow/` 下 16 个类，**全部直接 `implements DfaChecker`，没有共同基类**：
`DeadlockChecker`、`CollectionSelfReferenceChecker`、`IteratorConcurrentModificationChecker`、
`ServletAtomicityDfaChecker`、`XxeChecker`、`EqualsCalledOnNullChecker`、`OffByOneChecker`、
`MissingCryptoInitChecker`、`TryCatchFinallyChecker`、`UnlockNotLockedChecker`、
`OrmUnnecessaryGetDfaChecker`、`SingletonRaceDfaChecker`、`OrmLostUpdateDfaChecker`、
`CompareWithSelfChecker`、`OrmLoadNullCheckDfaChecker`、`UncaughtExceptionPropagator`。

它们是 16 个独立的 TAC 模式检查器，只是共用一个包名，**不是**某种统一的数据流分析。

---

## 4. 可空性：三值事实域

`AnalysisContext.mayBeNull(...)`（`engine/AnalysisContext.java:391-395`）是**对指向性
求解器的转发**，本身不存事实。五个通道是 `PointsToSolver` 的私有字段：

- `nullFields` `:124`
- `nullArrayCells` `:127`
- `nullContainerCells` `:130`
- `nullReturns` `:133`
- `nullVars` `:136`

**三值语义**（`PointsToSolver.java:104-121` 解释了为什么 null 不能做成 `Obj` 哨兵：那样所有
null 会互相 alias，污染 `mayAlias`）：

| objects | null 事实 | 结论 |
| --- | --- | --- |
| 非空 | 无 | 确定是对象 |
| 空 | 有 | 确定为 null |
| 空 | 无 | **未知**（绝不等于 null） |

⚠️ `AnalysisContext.java:383-390` 明确警告：`mayBeNull == false` **不是**非 null 的证明
（未被建模的写入不留痕迹）。这与「空集合不能证明安全」是同一条不变量。

---

## 5. Z3：只做误报过滤

Z3 在整个引擎里只有**一个**调用点，`TaintedCheckerBase.java:3139`，位于 sink 发出路径：

```java
String composed = composePathCondition(pathConditionForSink(...));
if (!composed.isEmpty()) {
    SolverZ3Bridge.Result r = solverBridge.check(composed, Map.of());
    if (r == SolverZ3Bridge.Result.UNSAT) return;   // 抑制
}
```

- `pathConditionForSink` **默认返回 null**（`:3239-3242`），所以未覆写它的 checker
  组合出的条件为空，**根本不会调用 Z3**
- 语义明确：Z3 **只用于抑制误报，绝不用于发现缺陷**（`:3237`）
- 调用链：`CachedZ3Bridge`（LRU 4096 项，`solver/CachedZ3Bridge.java:41-66`）→
  `Z3Bridge`（native Z3，失败回退 `LocalSolverBridge`）
- `StructuralImply`（`solver/StructuralImply.java`）是先于 SMT 的廉价一级判定
  （`x >= N` 蕴含 `x != 0` 之类，`PathCondition.java:136-144`）

### 5.1 PathCondition 与 GuardTracker 是两套东西

| | PathCondition | GuardTracker |
| --- | --- | --- |
| 位置 | `solver/PathCondition.java:35` | `taint/GuardTracker.java:51` |
| 形态 | mini-DSL 的**谓词字符串列表** | 每函数一份的**结构化 guard 栈** |
| 生命周期 | 累积于路径 | 按函数重置（`:48-49`） |
| 用 Z3 | 经 `GuardStore` 两级判定 | **完全不引用** Z3 |

二者唯一的交汇是单向的：checker 从 `GuardTracker` 读结构化 guard，再**字符串化**写入
`GuardStore`（例：`DivByZeroChecker.java:203-207`），并在 summary 里记
`Operation.SolverZ3Predicate`。

Z3 由 `tools.aqua:z3-turnkey` 提供，自动下载对应平台 native lib。

---

## 6. Call graph

`cg/CallGraphStrategyFactory.create`（`:36-70`）实际支持：

| 名字 | 实现 |
| --- | --- |
| `cha` | `ChaCallGraphStrategy` |
| `rta` | `RtaCallGraphStrategy` |
| `vta` | `VtaCallGraphStrategy` |
| `1cfa` | `OneCfaCallGraphStrategy` |
| `2cfa` | `TwoCfaCallGraphStrategy` |
| `stub` | `StubCallGraphStrategy` |
| `cached:<name>:<path>` | `CachedCallGraphStrategy` |

跨 TU 的 `createCrossTu`（`:89-112`）只支持 `global` → `GlobalCallGraphStrategy`（**CLI
默认**）与 cached 两种。

⚠️ **文档早期声称存在 `0-CFA`，代码中不存在**，且未知名字会直接抛异常
（`:64-68`）——不是「没写进文档」，是「选不了」。

另注：这里的 `1cfa` 是**调用图**策略，与 §3.3 指向分析的 `CALL_SITE` policy 是两套独立
开关。

---

## 7. 规则数量

「多少条规则」有两个不同的口径，文档早期混用了：

| 口径 | 数量 | 出处 |
| --- | --- | --- |
| **引擎实际配置的 checker** | **16** | `plumb-dfa/src/main/resources/checkers.yaml:19-179` |
| `plumb-rules` 的 `RuleRegistry` | 85 | `plumb-rules/.../RuleRegistry.java` |

`checkers.yaml` 的 16 项：`DivideByZero`、`IntegerOverflow`、`NullDereference`、
`ResourceLeak`、`SensitiveDataLeak`、`UncheckedReturn`、`ConstantCondition`、
`OrmLoadNullCheckDfa`、`OrmUnnecessaryGetDfa`、`OrmLostUpdateDfa`、`SingletonRaceDfa`、
`ServletAtomicityDfa`、`BoundaryOverflow`、`BoundaryUnderflow`、`BoundaryDivZero`、
`BoundaryArrayBounds`。

⚠️ `RuleRegistry.java` 里登记的许多 `org.plumb.dfa.checker.*` / `org.plumb.dfa.taint.*`
类名在 `plumb-dfa` 下**不存在**（例如 `:1010` 指向 `org.plumb.dfa.taint.TaintAnalysis`），
该 registry 明显陈旧，**不能**用它推断规则总数。

---

## 8. 并发模型

| 阶段 | 并行 | 说明 |
| --- | --- | --- |
| 解析（JavaParser） | 是 | 文件级，门槛 800 文件 |
| 翻译（AST → TAC） | 是 | 与解析同一轮 |
| 风格检查 | 是 | AST visitor，无跨文件状态 |
| **DFA（`runParallel`）** | **是** | 顶层 worklist：依赖计数归零的函数入 `readyQ`（`:1123-1130`） |
| 报告输出 | 否 | 单文件写 |

并发上限由 `--max-parallelism` 控制（默认 `nCPU - 1`，下限 1）。

⚠️ 文档早期称「Taint 必须单线程，跨函数共享 summary cache」——**已过时**。引擎有并行
worklist；单线程约束只存在于 `GuardStore`（`solver/GuardStore.java:20` 有明确说明）。

---

## 9. 与磁盘缓存的关系

```
文件 ─parse+translate─▶ TacProgram ───[可序列化]───▶ cache/programs/<sha1>.tac
                          │
                          ▼
                     MergedProgram ──[可序列化]───▶ cache/merged.tac
                          │
                          ▼
                       DFA ────  cache/callgraph.tac  ────  (可选)
```

详见 [`CACHE.md`](./CACHE.md)。