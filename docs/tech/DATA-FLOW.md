# 数据流分析算法说明

本文档描述 Plumb 中两类核心数据流分析的实现：**Taint Analysis（IFDS 风格的污点传播）** 与 **Generic DFA（区间抽象 + 符号约束求解）**。

## 1. Taint Analysis（IFDS 风格）

### 1.1 目标

找出所有"用户可控输入"（source）经过任意代码路径到达"危险函数"（sink）的事实链。源到汇之间的每条边都把"taint label"沿着控制流传播。

### 1.2 IFDS 简述

IFDS（Interprocedural, Finite, Distributive, Subset）由 Reps-Horwitz-Sagiv 1995 提出。核心思想：

- 把数据流分析转成**图可达性**问题
- 对每个程序点 p 和每个 fact d，构造图节点 `(p, d)`
- "distributive flow function"对应图上的边
- 求解"汇点可达性"=求解数据流问题

Plumb 的实现是简化的 IFDS（subset 是 boolean 集合）：
- fact = 某个 source 标签的整数 ID
- 一个 `(program-point, fact)` 对 = 节点
- edges = 沿控制流图传播

### 1.3 入口：`TaintAnalysis.run(ctx)`

```java
public class TaintAnalysis {
    public void run(AnalysisContext ctx) { ... }
}
```

执行流程：

1. **初始化 source facts** —— 在每个 source method call 处生成 `(callSite, sourceFactId)` 节点。
2. **过程内传播** —— 沿 TacCfg 基本块后继传播。
3. **call 边** —— 遇到 CALL 指令：把 caller 的 fact 通过 `CallResolver` 映射到 callee 的对应形参。
4. **return 边** —— 把 callee 返回值上的 fact 传回 caller 的 call site。
5. **sink 检测** —— 到达 sink method call 的 fact 触发 `Finding`。
6. **summary cache** —— 跨函数结果缓存（同一 callee 不重复求值）。

### 1.4 关键类

| 类 | 路径 | 作用 |
| --- | --- | --- |
| `TaintAnalysis` | `plumb-dfa/.../taint/TaintAnalysis.java` | 调度入口 |
| `TaintPropagator` | `plumb-dfa/.../taint/TaintPropagator.java` | 沿 CFG 传播单个 fact |
| `TaintCatalog` | `plumb-dfa/.../taint/TaintCatalog.java` | source / sink 方法签名表 |
| `CallResolver` | `plumb-dfa/.../cg/CallResolver.java` | CALL 指令 → callee TacFunction |
| `MethodSummary` | `plumb-dfa/.../taint/MethodSummary.java` | callee 摘要：哪些 fact 在 param 进入，哪些从 return 流出 |
| `PathCondition` | `plumb-dfa/.../solver/PathCondition.java` | 当前路径上的条件约束（guard 收集） |

### 1.5 Source / Sink 模式

`TaintCatalog` 维护三张表：
- **sources**：方法签名集合（任何调用 `request.getParameter(...)` 都被视为 source）
- **sinks**：方法签名集合
- **sanitizers**：方法签名集合（净化污点，如 `URLEncoder.encode`、`ESAPI.encoder().canonicalize`）

每个 source 自动分配一个整数 ID（fact 域）。Sink 检测时把累积的 fact 集合导出为 Finding。

### 1.6 跨函数与摘要缓存

```java
Map<MethodSignature, MethodSummary> summaryCache = new ConcurrentHashMap<>();
```

每次进入 callee 前查 cache。Cache miss 则求解（递归），结果回填。`MethodSummary` 记录：
- 输入 fact 集（哪些 source 进了哪个形参）
- 输出 fact 集（哪些 source 从 return 出来）
- 是否调用过 sink（决定是否需要 caller 报告）

为了打破递归死锁，TaintAnalysis 用 `analyzing: Set<MethodSignature>` 集合记录"正在分析"。遇到正在分析的方法时返回空 summary。

### 1.7 性能优化

- **summary 复用**：同一方法被多个 caller 调用时只算一次
- **return 链缓存**：`(callee, input-fact) -> output-fact-set` 也缓存
- **CFG 压缩**：用 `TacBasicBlock` 而不是逐条 instruction
- **field-sensitive**：`TacVar` 名字带 SSA version，字段访问走 `FIELDLOAD` / `FIELDSTORE`
- **平行 stage**：跨 TU 阶段 R148e 用 ForkJoin

## 2. Generic DFA（plumb-dfa 与 plumb-dfa）

### 2.1 目标

- 区间抽象：每个 `TacVar` 持一个 `[lo, hi]` 区间，传播时 union
- 算术安全：检测除零、整数溢出、数组越界
- 资源追踪：open 必须 close（`try-with-resources` 等价检查）

### 2.2 区间抽象

```java
public final class Interval implements Serializable {
    private final long lo;
    private final long hi;
    private final boolean isEmpty;       // 不可达
    private final boolean isFull;         // 全集（unknown）
    public static Interval of(long lo, long hi);
    public static Interval top();        // 未知
}
```

每个 `TacVar` 在每个程序点都有一份 `Interval`（Lattice 值）。操作：

- `x = a + b` → `interval(x) = interval(a) ⊕ interval(b)`
- `x = a / b` → 当 `interval(b)` 包含 0 时报警
- `x = new int[b]` → 检查 `b` 是否为负

### 2.3 Forward 求解

每个 `IrChecker` 实现 `run(AnalysisContext ctx)`，调度阶段把 checker 注册到 `IrDispatcher`：

```java
org.plumb.dfa.IrDispatcher d = new org.plumb.dfa.IrDispatcher();
if (config.runDivByZero)          d.register(new DivByZeroCheck());
if (config.runResourceLeak)       d.register(new ResourceLeakCheck());
...
d.run(ctx);
```

`IrDispatcher` 维护一个 worklist。每个基本块处理完毕后，把 successors 加进 worklist，直到稳定。

### 2.4 资源追踪（ResourceLeakCheck）

- `open` 站点（`FileInputStream(...)` 等）记录 `(var, line)` 到 `mustClose` 集合
- 路径上有 `close` 指令就把对应 var 从集合删
- 函数返回 / 异常抛出时，`mustClose` 不为空就报告

### 2.5 跨函数（plumb-dfa）

plumb-dfa 是新一代 DFA 引擎，特点：

- **统一的 fact 域**：所有 checker 用同一个 `Fact` 接口
- **call graph strategies**：CHA / RTA / 0-CFA / VTA 等可插拔
- **function summary**：每个 callee 算一次，caller 复用
- **path condition**：guard 沿路径累积，用 Z3 解 SMT

```java
// 关键调度入口
DfaEngine engine = new DfaEngine(program, rules, callGraph);
for (DfaChecker checker : checkers) engine.register(checker);
engine.run();
```

跨函数分析的具体设计（两阶段 + Operation sealed + SummaryApplier + R217
worklist + 与传统 IFDS 的对比）见 [`CROSS-FUNCTION-ANALYSIS.md`](./CROSS-FUNCTION-ANALYSIS.md)。

## 3. Z3 / 路径条件

部分 checker（INTEGER_OVERFLOW、DIV_BY_ZERO、ARRAY_BOUNDS）需要 Z3 路径条件。流程：

1. **收集 guard**：从 entry 到当前 sink，沿 CFG 收集所有 BRANCH 条件
2. **构造 path condition**：`x > 0 && y != 0 && ...`
3. **构造 sink 假设**：`a / b == 0` 或 `a + 1 < 0` 等
4. **SMT 求解**：`Z3.solver.check() == SAT` ⇒ 可达 ⇒ 报告

实现位于 `plumb-dfa/src/main/java/org/plumb/dfa/unified/solver/`。

Z3 集成通过 `tools.aqua:z3-turnkey`（自动下载对应平台的 native lib）。

## 4. 并发模型

| 阶段 | 是否并行 | 备注 |
| --- | --- | --- |
| 解析（JavaParser） | 是 | 文件级 ForkJoin，门槛 800 文件 |
| 翻译（AST → TAC） | 是 | 与解析同一轮 |
| 风格类（StyleCheck） | 是 | AST visitor，无跨文件状态 |
| Taint（IFDS） | 否 | 必须单线程，跨函数共享 summary cache |
| DFA unified | 部分 | 顶层 worklist 并行；callee summary 串行 |
| DFA legacy | 否 | 单线程 forward worklist |
| 报告输出 | 否 | 单文件写 |

并发上限由 `--max-parallelism` 控制（默认 `nCPU - 1`，floor 1）。

## 5. 与磁盘缓存的关系

```
文件 ─parse+translate─▶ TacProgram ───[可序列化]───▶ cache/programs/<sha1>.tac
                          │
                          ▼
                     MergedProgram ──[可序列化]───▶ cache/merged.tac
                          │
                          ▼
                       DFA ────  cache/callgraph.tac  ────  (可选)
```

详见 `doc/tech/CACHE.md`（未来版本）。
