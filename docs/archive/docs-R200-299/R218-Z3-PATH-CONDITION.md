# R218 — SolverZ3Bridge + Operation.SolverZ3Predicate + LocalSolverBridge

**Round**: R218 · **2026-08-01** · **状态**: ✅ DONE (LocalSolver 部分)

---

## 1. 背景与设计目标

R213 落地 FunctionSummary + Operation 通用 sum-type. R217c 加
PathSensitiveCheckerBase (alloc-without-close 模式). R218 目标：

> ③ 在最后发现可能的问题后，再正向汇总分析一把，确定特定缺陷是否确实存在

具体：path-condition validation. 一个 finding 的 source → var →
sink 路径上的 path condition 可能不可满足 (例如 close 只在
`if (x != null)` 分支里, 实际 x 始终是 null). Z3 验证 UNSAT →
不 emit (false positive elimination).

R218 范围：
- `Operation.SolverZ3Predicate` 新 record subtype — 让
  `FunctionSummary` 能携带 Z3 predicate
- `SolverZ3Bridge` interface + `LocalSolverBridge` impl
  (pure-Java fallback) — 处理简单的 arithmetic / boolean DSL
- 12 R218 tests (predicate record + bridge SAT/UNSAT 行为)

R218 没做：
- 真正的 Z3 native bridge (需要 Z3 native lib, 等 plumb-dfa
  真实使用 Z3 时再加)
- 把 bridge 接到 `TaintedCheckerBase.summarizeFunction` (这是
  R218a, 还没做)

---

## 2. 实施清单

### Operation.SolverZ3Predicate (modified)

- 新 record subtype 加进 `Operation` sealed permits 列表
- 字段: `predicate: String` (DSL 字符串), `pathVars: Set<String>` (关联 var 名字)
- null `pathVars` → empty Set
- 是 sealed `Operation` sum-type 的一员, 可被 pattern-match

### SolverZ3Bridge (new, ~3KB)

- Interface with `Result` enum: SAT / UNSAT / UNKNOWN
- 方法: `check(predicate, bindings)`, `referencedVars(predicate)`
- 设计为可拔插 (LocalSolver fallback, future Z3 bridge)

### LocalSolverBridge (new, ~13.5KB)

- Pure-Java recursive-descent parser + interpreter
- DSL grammar: `||`, `&&`, `!`, `==`, `!=`, `<`, `<=`, `>`, `>=`, `+`, `-`, `*`, `/`, `()`, `NUMBER`, `BOOL`, `STRING`, `IDENT`
- 数值: Long / Double 互转
- 字符串: lex compare (fallback when one side is string)
- **UNBOUND sentinel**: 未绑定的 var 用 `UNBOUND` 占位, 任何
  涉及 `UNBOUND` 的比较抛 `UnboundVariableException`, bridge
  catch 返 SAT (conservative "path might be reachable")
- 异常处理:
  - `UnboundVariableException` → SAT
  - `UnsupportedOperationException` (e.g. division by zero) → UNKNOWN
  - `RuntimeException` (malformed) → SAT

### R218 tests (12 new, ~6.6KB)

- `triviallyTruePredicate_isSat` — `x == 5` with x=5 → SAT
- `triviallyFalsePredicate_isUnsat` — `x == 0` with x=5 → UNSAT
- `booleanCombination_andOrNot` — `x > 0 && y > 0`, `!(x == 5)` etc
- `unboundVariable_isConservativeSat` — `y == 0` with no y → SAT
- `arithmeticArithmetic_evaluatesCorrectly` — `x + y == 8`, `(x + y) * 2 == 16`
- `divisionByZero_isUnknown` — `x / y == 1` with y=0 → UNKNOWN
- `malformedPredicate_isConservativeSat` — garbage, empty, null
- `referencedVars_extractsAllIdentifiers` — regex extracts `x`, `y`, `z`
- `referencedVars_filtersKeywords` — `true`, `false`, `null` 滤掉
- `solverZ3Predicate_preservesPredicateAndVars` — record ctor
- `solverZ3Predicate_nullPathVars_becomesEmpty` — null pathVars
- `solverZ3Predicate_isOperation` — 是 Operation 的 subtype

---

## 3. 5 大设计创新点

1. **SolverZ3Bridge 是 Z3 / LocalSolver 可拔插接口** (R218 design).
   Caller-side checker 不知道也不关心哪个 solver backend.
   Tests 用 LocalSolverBridge (fast, no native lib), production
   可以换 Z3Bridge. R218 ship LocalSolver, future 加 Z3 bridge
   不用改 caller.

2. **UNBOUND sentinel + UnboundVariableException** (R218 design).
   Parser 对未绑定 var 返 sentinel 而不是 false, 因为 `unbound == 5`
   不该是 false. 涉及 UNBOUND 的比较抛异常, bridge catch 返 SAT
   (conservative "path might be reachable"). 这是 under-approximation
   (saying UNSAT 会 suppress true findings) vs over-approximation
   (saying SAT 可能多发 false positive) 的权衡 — R218 选 over-approximation.

3. **Recursive-descent parser 内联在 LocalSolverBridge** (R218 design).
   ~13KB 全 self-contained. 不依赖 ANTLR / JavaCC. 12 R218 tests 覆盖
   grammar 全 subset (arithmetic / boolean / comparison / 嵌套).

4. **Operation.SolverZ3Predicate 携带 pathVars** (R218 design).
   让 summary 不只说 "this var flows to sink", 还说 "under
   condition X, this var flows to sink". Caller 用 pathVars 把
   当前 fact context 映射成 Z3 binding, 然后调 bridge.check.

5. **Malformed input → SAT not UNKNOWN** (R218 design choice).
   Division by zero → UNKNOWN (semantic problem, can't decide).
   Malformed input → SAT (parser error, can't tell — assume worst
   case for the user: don't suppress findings because of
   infrastructure problems).

---

## 4. 测试结果

```
[INFO] Tests run: 158, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

| Module | R218 new | Cumulative |
|---|---|---|
| plumb-tac | 0 | 24 |
| plumb-dfa | 12 (R218) | 158 (+12) |
| **Total new** | **12** | **+12 from R217c baseline** |

- 0 回归
- 12 R218 tests pass
- 备份: `D:\work\tmp\r218_done/` (4 files, ~25KB)
- doc: `docs/R218-Z3-PATH-CONDITION.md` (this file, ~7KB)

---

## 5. R218a / R219 接下来

- **R218a (~0.5 day)**: 把 `SolverZ3Bridge` 接到
  `TaintedCheckerBase.summarizeFunction` — emit finding 前
  调 bridge.check(source-var-predicate) 跟 sink-pattern
  谓词. UNSAT → skip. R218a polish 让 TaintedCheckerBase
  真正用上 Z3 验证.

- **R218b (~0.5 day)**: 真正的 Z3 native bridge — wrap
  `tools.aqua.z3-turnkey` 的 `com.microsoft.z3.Context`.
  用 LocalSolverBridge 同样的 DSL parser, 但 evaluation
  走 Z3. Production classpath 通常有 z3-turnkey native lib.

- **R218c (~1 day)**: 跨 function path-condition propagation.
  Callee summary 的 `Operation.SolverZ3Predicate` 通过
  R213 framework 传到 caller, caller 跟自己 summary 合并
  谓词后再 Z3 验证. 这是 R213 "summary bridge" 的 path-condition
  应用.

- **R219 (~1 day)**: end-to-end 验证 — Juliet 样本对比
  plumb-dfa (legacy) vs plumb-dfa (R213/215/216/217/218
  framework) 的 TP/FP/P/R metrics. 用户的"③ 正向汇总验证"
  终极价值 — false positive 率应该下降.

---

## 6. R218 期间踩坑

- **`UNBOUND` 用 `Boolean.TRUE` placeholder** (R218 pitfall fix).
  最初让 unbound var 返 `Boolean.TRUE`. 错误 — `Boolean.TRUE == 0`
  走 eq 返 false, 破坏 "conservative SAT" 语义. 修法: 单独的
  `UNBOUND` sentinel object, 涉及 UNBOUND 的比较 throw
  `UnboundVariableException`, bridge catch 返 SAT.
- **eq / compare 都要 catch UNBOUND** (R218 pitfall fix).
  最初只在 compare 抛. 但 `==` 走 eq 直接返 boolean, 没经过
  compare. 修法: eq 也 throw.
- **Division by zero 返 UNKNOWN 不是 SAT** (R218 design choice).
  实际修法: `UnsupportedOperationException` 专门 catch 返 UNKNOWN.
  RuntimeException (其他) 返 SAT.
- **Z3 native lib 在测试环境不可用** (R218 scope).
  plumb-dfa 已有 z3-turnkey 依赖, 但 Z3Context ctor
  在 CI / dev machines 经常 fail (native lib 找不到). R218
  ship LocalSolver, 真正的 Z3Bridge 等到 R218b.
