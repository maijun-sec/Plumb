# 跨函数分析的 Operation 体系与 6 套子分析

[English](../../tech/CROSS-FUNCTION-OPERATION.md) | [中文](../README.md)

本文档讲跨函数分析的 `Operation` 体系与 6 套子分析。Phase 1+2 主干（动机、CallGraph、Phase 1 Summary、Phase 2 精确分析）见 `CROSS-FUNCTION-ANALYSIS.md`；周边（并行 worklist、与 tabulation 的对比、trace 审计、AI 增强层）见 `CROSS-FUNCTION-AI-TRACE.md`。

## 5. 介绍

Phase 2 在 caller 视角上做的分析并不是单一类型，而是**多套异构分析共用同一个 Summary 框架**。`Operation` 是这套框架的中间表示——callee 在 summary 里写 `Operation` 变体，caller 的 `SummaryApplier` 把 `Operation` 编码成 fact bag 里的具体事实，下游 checker 按事实内容决定后续分析。

### 5.1 6 套子分析（按 summary 端 → Phase 2 消费侧梳理）

下表按"分析 → summary 里用什么 → Phase 2 怎么 consume"梳理：

| # | 分析 | Summary 端 | Phase 2 消费侧 | 关键类 |
| --- | --- | --- | --- | --- |
| 1 | **常量传播** | `MethodSummary.returnShape` 的 `RETURN_CONST` / `RETURN_PARAM` / `RETURN_BINARY_OP` / `RETURN_CONDITIONAL` 形态 | `SummaryApplier.applyReturnValue` 编码成 `RETURN_VALUE_FOLDED(dest, "const:N")` / `"param:0:callerArg"` / `"binop:op:lhs:rhs"` / `"cond:..."` 写入 caller fact bag；下游 checker 按 value 字符串决定后续分析 | `MethodSummaryBuilder` + `SummaryApplier` + `LiteralBooleanConditionChecker` |
| 2 | **指向分析** | `Operation.PointsToPropagate(allocSites)` 收集 callee 内 `NEW / NEWARRAY` 的 `AllocSite` | `PointsToAnalysis.applyCalleeSummary(dest, summary)` 把 alloc sites 并入 caller 的 `pointsTo(dest)`；`mayAlias` / `isUseAfterFree` 在 caller 视角检查 | `PointsToAnalysis` + `PointsToSummary` |
| 3 | **null / 资源分析** | `TaintedCheckerBase` 在 callee 体内维护 `taintedVars`（PointerTaint 域），summary 时把每个 tainted 形参写出成 `Operation.TaintPropagate(sourceId, confidence)` | `propagateTaintFromCalleeSummary` 把 caller 实参标为 tainted；`NullDerefChecker` / `ResourceLeakChecker` 在 caller 视角检查 receiver 是否可能 null / 是否已被 close | `TaintedCheckerBase` + `TaintDomain.PointerTaint` + `Operation.TaintPropagate` |
| 4 | **数值范围 / 整数溢出** | `TaintedCheckerBase` 用 `TaintDomain.NumericTaint` 域；callee 算术结果若 `>= MAX_INT` 之类会触发 `TaintedCheckerBase.summarizeFunction` 写出的 `Operation.TaintPropagate` | `propagateTaintFromCalleeSummary` 把数值域的 taint 沿 caller 实参传播；`IntOverflowChecker` / `IntUnderflowChecker` / `ArrayIndexChecker` / `DivByZeroChecker` 在 caller 视角用 `PathCondition` + Z3 验证条件 | `TaintedCheckerBase` + `TaintDomain.NumericTaint` + `PathCondition` + `Z3Bridge` |
| 5 | **路径条件 / Z3 验证** | `Operation.SolverZ3Predicate(predicate, pathVars)` 把 callee 内 path-condition fragments 透出 | `TaintedCheckerBase.composePathCondition` 把 callee 的 fragments 串进 caller 的 `PathCondition`；Z3 用 caller 的 bindings 验证"antecedent && !sink-condition"是否 UNSAT（UNSAT ⇒ 路径不可达 ⇒ 抑制 finding） | `Operation.SolverZ3Predicate` + `PathCondition` + `Z3Bridge` / `LocalSolverBridge` + `GuardStore` |
| 6 | **副作用失效** | `MethodSummary.sideEffect` 的四个集合（writes-this / writes-param / writes-static / calls / may-throw） | `SummaryApplier.applySideEffect` 编码成 `SIDE_EFFECT_RECORDED(dest, "writes-this:f,...")` fact；`ctx.sideEffectConsumer().recordCall(ins, ctx)` 把它写进 per-function `SideEffectConsumer`；alias / reallocation 类 checker 在 caller 视角看 invalidation | `MethodSummary.sideEffect` + `SideEffect` + `SideEffectConsumer` + `SummaryApplier.applySideEffect` |

### 5.2 常量传播 (Constant Propagation)

最常用的"Phase 2 精确分析"是常量折叠：

- **Summary 端**：`MethodSummaryBuilder` 对 `return const` / `return p0` /
  `return a + b` / `if (b) return true; else return false;` 4 种 pattern 做形
  状分类，结果落到 `ReturnShape.Kind`。`RETURN_BINARY_OP` 编码 op + lhs + rhs，
  `RETURN_CONDITIONAL` 编码 true-arm + false-arm。
- **Phase 2 端**：`SummaryApplier.encodeShape` 把形状转成稳定字符串写到
  `RETURN_VALUE_FOLDED` fact。`LiteralBooleanConditionChecker` 这类消费者按
  字符串前缀判断：看到 `const:true` / `const:42` 就报 dead branch / constant
  operand。
- **上下文敏感的体现**：同一个 `staticReturnsTrue()` 被 100 个 caller 调用，
  每个 caller 的 `FactBag` 里都有一条 `RETURN_VALUE_FOLDED(dest_i, "const:true")`，
  `dest_i` 是 caller 自己的局部变量。Phase 2 的"精确"指的就是这个**绑
  定到 caller 具体变量**的过程——而不是全局的"callee 返回 true"。

### 5.3 指向分析 (Points-to Analysis)

唯一**严重依赖 summary 传递 alloc-site** 的子分析：

- **Summary 端**：`PointsToAnalysis.buildSummary` 收集 callee 内所有
  `AllocSite`，编码成 `functionSig\0insnIdx\0type` 字符串集合（`\0` 分隔避免
  跟其它字符冲突），写进 `FunctionSummary.paramOps` 或 `returnOps` 的
  `Operation.PointsToPropagate(allocSites)`。
- **Phase 2 端**：`applyCalleeSummary(dest, summary)` 解析字符串、回填
  `pointsTo(dest)`，让 `isUseAfterFree(dest)` 能在 caller 视角正确检查
  "dest 是不是 alias 到 callee 内已经 `close()` 过的 alloc"。
- **上下文敏感的体现**：`dest` 是 caller 的具体变量，alloc site 是 callee
  视角的 symbol——`applyCalleeSummary` 把两者用 `pointsTo[dest] ⊇
  callee.returnAllocSites` 接起来。`dest` 在不同 caller 里指向不同的 caller
  变量，但接到的 alloc sites 集合相同（callee 决定的）。

### 5.4 null / 资源分析 (Nullness / Resource)

走 `TaintedCheckerBase` 的 taint 框架：

- **Summary 端**：`TaintedCheckerBase` 在 callee 体内维护
  `taintedVars: Map<TacVar, String>`（PointerTaint 域）。callee 的
  `summarizeFunction` 遍历当前 `taintedVars`，对每个形参写一条
  `Operation.TaintPropagate(sourceId="may-be-null:reason", confidence=1.0)`。
- **Phase 2 端**：`propagateTaintFromCalleeSummary` 看到 `TaintPropagate` →
  把 caller 实参加进 `taintedVars`。`NullDerefChecker` 的
  `checkInstruction` 在 `FIELDLOAD` / `FIELDSTORE` / `ARRAYLOAD` /
  `ARRAYSTORE` 时看 receiver 是否在 `taintedVars`。
- **上下文敏感的体现**：caller 的实参 `a` 和 `b` 都来自同一个 callee 的
  `RETURN_THIS_FIELD` 形态，summary 是同一个 `RETURN_THIS_FIELD` fact，但
  caller 的 `a` 走完 `propagateTaintFromCalleeSummary` 后被标 may-be-null
  / may-be-aliased，`b` 不走这条路径就不标——这种**只有 caller 视角才能区分**
  的事实，是 Phase 2 精确分析的核心。

### 5.5 数值范围 / 整数溢出 (Numeric Range / Overflow)

跟 null/资源共用 `TaintedCheckerBase` 框架但走 `TaintDomain.NumericTaint`：

- **Summary 端**：`TaintedCheckerBase.summarizeFunction` 把算术上溢
  (BINOP 结果 `> MAX_INT`) 的形参/返回值写成 `Operation.TaintPropagate`，
  domain 是 `NumericTaint`。
- **Phase 2 端**：`propagateTaintFromCalleeSummary` 在 caller 实参上设
  `taintedVars[arg] = "numeric-overflow"`，caller 视角的 `ArrayIndexChecker` /
  `DivByZeroChecker` 在做下标/除数检查时用 `PathCondition` 配合
  `impliedGuard` 查 caller 自己的 guard 是否已经覆盖。
- **上下文敏感的体现**：`x + 1` 在 callee 内结果可能上溢（`x` 已是
  `MAX_INT`），但 caller 传 `x = 5` 进去时，caller 自己的 `GuardStore` 已经记
  `x < 100`（来自 `caller` 路径上的 BRANCH），`intOverflow.check("x + 1
  overflows")` → `PathCondition(x < 100)` ⊢ `x + 1 < 101 << MAX_INT` → UNSAT
  → finding 抑制。**这种基于 caller 路径的 Z3 验证是 Phase 2 路径敏感的核心**。

### 5.6 路径条件 / Z3 验证 (Path Condition)

唯一**主动拉 Z3** 的子分析：

- **Summary 端**：`Operation.SolverZ3Predicate(predicate, pathVars)` 让
  callee 把"自己内部的 guard 条件"显式透出（典型例子：callee 在
  `if (x > 0)` 之后才用 `x`，把 `"x > 0"` 作为 fragment 写出）。
- **Phase 2 端**：`TaintedCheckerBase.composePathCondition(local)` 把
  callee 的 fragments 串进 caller 的 `PathCondition`；当 caller 的某个
  finding 触发时（如"整数除零"），`composePathCondition("x != 0")` →
  `Z3Bridge.check(antecedent && !(x != 0), bindings)` → UNSAT → 抑制。
- **上下文敏感的体现**：`bindings` 用 caller 的 `FactBag` 里的具体值填充
  （"x = 5"），同一个 `x > 0` predicate 在不同 caller 里有不同的 binding，Z3
  验证结果可能不同。
- **两阶段 bridge**：`TaintedCheckerBase.solverBridge` 默认装的是
  `CachedZ3Bridge(new Z3Bridge())`，Z3 不可用时 Z3Bridge 自动 fall back 到
  `LocalSolverBridge`（纯 Java 解析器，局限在算术子集）。`CachedZ3Bridge`
  把 `(predicate, bindings) → Result` 缓存，避免重复 SMT query。

### 5.7 副作用失效 (Side-Effect Invalidation)

唯一**让 caller 后续分析必须重做**的子分析：

- **Summary 端**：`MethodSummary.sideEffect` 的四个集合 + `mayThrow` flag。
- **Phase 2 端**：`SummaryApplier.applySideEffect` 在 caller 的 fact bag
  写 `SIDE_EFFECT_RECORDED(dest, "writes-this:f,writes-param:0,...")`；
  `ctx.sideEffectConsumer().recordCall(ins, ctx)` 把这条 fact 同步写进
  per-function 的 `SideEffectConsumer`，alias / reallocation 类 checker 在 caller
  视角的 FIELDLOAD / FIELDSTORE / ARRAYLOAD / ARRAYSTORE 时查
  `wasInvalidated(receiver, field)`。
- **上下文敏感的体现**：caller 传的 `this.field` 在 callee 内部被修改
  （`MethodSummary.sideEffect.writesThisFields()`），caller 视角下**所有**
  对 `this.field` 的后续读都看不到 callee 之前的值，但其他字段不受影响——
  这是 caller 视角的字段级精度。
