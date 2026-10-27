# The Operation System and the Six Sub-Analyses of Cross-Function Analysis

[English](../README.md) | [中文](../zh/tech/CROSS-FUNCTION-OPERATION.md)

This document covers the `Operation` system and the six sub-analyses of the
cross-function analysis. The Phase 1+2 backbone (motivation, CallGraph,
Phase 1 Summary, Phase 2 precise analysis) is in
`CROSS-FUNCTION-ANALYSIS.md`; the periphery (parallel worklist, comparison
with tabulation, trace auditing, the AI enhancement layer) is in
`CROSS-FUNCTION-AI-TRACE.md`.

## 5. Introduction

The analysis that Phase 2 performs from the caller's point of view is not a single kind of analysis, but rather **several heterogeneous analyses sharing the same Summary framework**. `Operation` is the intermediate representation of that framework — the callee writes `Operation` variants into the summary, the caller's `SummaryApplier` encodes the `Operation` into concrete facts in the fact bag, and the downstream checker decides what to do next based on the content of the fact.

### 5.1 The six sub-analyses (ordered from the summary side to the Phase 2 consumer side)

The table below is organised as "analysis → what is used in the summary → how Phase 2 consumes it":

| # | Analysis | Summary side | Phase 2 consumer side | Key classes |
| --- | --- | --- | --- | --- |
| 1 | **Constant propagation** | The `RETURN_CONST` / `RETURN_PARAM` / `RETURN_BINARY_OP` / `RETURN_CONDITIONAL` shapes of `MethodSummary.returnShape` | `SummaryApplier.applyReturnValue` encodes it as `RETURN_VALUE_FOLDED(dest, "const:N")` / `"param:0:callerArg"` / `"binop:op:lhs:rhs"` / `"cond:..."` written into the caller's fact bag; the downstream checker decides what to do next based on the value string | `MethodSummaryBuilder` + `SummaryApplier` + `LiteralBooleanConditionChecker` |
| 2 | **Points-to analysis** | `Operation.PointsToPropagate(allocSites)` collects the `AllocSite` of `NEW / NEWARRAY` inside the callee | `PointsToAnalysis.applyCalleeSummary(dest, summary)` merges the alloc sites into the caller's `pointsTo(dest)`; `mayAlias` / `isUseAfterFree` are checked from the caller's point of view | `PointsToAnalysis` + `PointsToSummary` |
| 3 | **Null / resource analysis** | `TaintedCheckerBase` maintains `taintedVars` inside the callee body (PointerTaint domain); when summarising, it writes each tainted formal parameter out as `Operation.TaintPropagate(sourceId, confidence)` | `propagateTaintFromCalleeSummary` marks the caller's actual arguments as tainted; `NullDerefChecker` / `ResourceLeakChecker` check from the caller's point of view whether the receiver may be null / has already been closed | `TaintedCheckerBase` + `TaintDomain.PointerTaint` + `Operation.TaintPropagate` |
| 4 | **Numeric range / integer overflow** | `TaintedCheckerBase` uses the `TaintDomain.NumericTaint` domain; if the callee's arithmetic result is `>= MAX_INT` or similar, it triggers the `Operation.TaintPropagate` written by `TaintedCheckerBase.summarizeFunction` | `propagateTaintFromCalleeSummary` propagates the numeric-domain taint along the caller's actual arguments; `IntOverflowChecker` / `IntUnderflowChecker` / `ArrayIndexChecker` / `DivByZeroChecker` verify the conditions from the caller's point of view using `PathCondition` + Z3 | `TaintedCheckerBase` + `TaintDomain.NumericTaint` + `PathCondition` + `Z3Bridge` |
| 5 | **Path condition / Z3 verification** | `Operation.SolverZ3Predicate(predicate, pathVars)` exposes the path-condition fragments inside the callee | `TaintedCheckerBase.composePathCondition` strings the callee's fragments into the caller's `PathCondition`; Z3 uses the caller's bindings to verify whether "antecedent && !sink-condition" is UNSAT (UNSAT ⇒ the path is unreachable ⇒ the finding is suppressed) | `Operation.SolverZ3Predicate` + `PathCondition` + `Z3Bridge` / `LocalSolverBridge` + `GuardStore` |
| 6 | **Side-effect invalidation** | The four sets of `MethodSummary.sideEffect` (writes-this / writes-param / writes-static / calls / may-throw) | `SummaryApplier.applySideEffect` encodes it as the `SIDE_EFFECT_RECORDED(dest, "writes-this:f,...")` fact; `ctx.sideEffectConsumer().recordCall(ins, ctx)` writes it into the per-function `SideEffectConsumer`; alias / reallocation checkers look at the invalidation from the caller's point of view | `MethodSummary.sideEffect` + `SideEffect` + `SideEffectConsumer` + `SummaryApplier.applySideEffect` |

### 5.2 Constant Propagation

The most common "precise Phase 2 analysis" is constant folding:

- **Summary side**: `MethodSummaryBuilder` classifies the shape of 4 patterns — `return const` / `return p0` / `return a + b` / `if (b) return true; else return false;` — and the result lands in `ReturnShape.Kind`. `RETURN_BINARY_OP` encodes op + lhs + rhs, `RETURN_CONDITIONAL` encodes true-arm + false-arm.
- **Phase 2 side**: `SummaryApplier.encodeShape` turns the shape into a stable string written to the `RETURN_VALUE_FOLDED` fact. Consumers such as `LiteralBooleanConditionChecker` decide by the string prefix: seeing `const:true` / `const:42` it reports a dead branch / constant operand.
- **How context sensitivity shows up**: the same `staticReturnsTrue()` is called by 100 callers, and each caller's `FactBag` contains a `RETURN_VALUE_FOLDED(dest_i, "const:true")` where `dest_i` is the caller's own local variable. What Phase 2 means by "precise" is exactly this **binding to the caller's concrete variable** — not a global "the callee returns true".

### 5.3 Points-to Analysis

The only sub-analysis that **depends heavily on the summary carrying alloc sites**:

- **Summary side**: `PointsToAnalysis.buildSummary` collects every `AllocSite` inside the callee, encodes them as a set of `functionSig\0insnIdx\0type` strings (`\0` as the separator, to avoid clashing with other characters), and writes them into `FunctionSummary.paramOps` or `returnOps` as `Operation.PointsToPropagate(allocSites)`.
- **Phase 2 side**: `applyCalleeSummary(dest, summary)` parses the strings and fills `pointsTo(dest)` back in, so that `isUseAfterFree(dest)` can correctly check from the caller's point of view whether "dest aliases an alloc inside the callee that has already been `close()`d".
- **How context sensitivity shows up**: `dest` is the caller's concrete variable, while the alloc site is a symbol from the callee's point of view — `applyCalleeSummary` connects the two with `pointsTo[dest] ⊇ callee.returnAllocSites`. `dest` points at different caller variables in different callers, but the set of alloc sites it receives is the same (determined by the callee).

### 5.4 Nullness / Resource

These go through the taint framework of `TaintedCheckerBase`:

- **Summary side**: `TaintedCheckerBase` maintains `taintedVars: Map<TacVar, String>` inside the callee body (PointerTaint domain). The callee's `summarizeFunction` walks the current `taintedVars` and writes one `Operation.TaintPropagate(sourceId="may-be-null:reason", confidence=1.0)` per formal parameter.
- **Phase 2 side**: `propagateTaintFromCalleeSummary` sees `TaintPropagate` → adds the caller's actual arguments to `taintedVars`. The `checkInstruction` of `NullDerefChecker` looks at whether the receiver is in `taintedVars` on `FIELDLOAD` / `FIELDSTORE` / `ARRAYLOAD` / `ARRAYSTORE`.
- **How context sensitivity shows up**: the caller's actual arguments `a` and `b` both come from the same callee's `RETURN_THIS_FIELD` shape, and the summary is the same `RETURN_THIS_FIELD` fact, but the caller's `a` is marked may-be-null / may-be-aliased after going through `propagateTaintFromCalleeSummary`, while `b` does not take that path and is not marked — this kind of fact, **distinguishable only from the caller's point of view**, is the core of Phase 2 precise analysis.

### 5.5 Numeric Range / Overflow

It shares the `TaintedCheckerBase` framework with null/resource, but goes through `TaintDomain.NumericTaint`:

- **Summary side**: `TaintedCheckerBase.summarizeFunction` writes the formal parameters / return values whose arithmetic overflows (a BINOP result `> MAX_INT`) as `Operation.TaintPropagate`, with the domain being `NumericTaint`.
- **Phase 2 side**: `propagateTaintFromCalleeSummary` sets `taintedVars[arg] = "numeric-overflow"` on the caller's actual arguments; the caller's `ArrayIndexChecker` / `DivByZeroChecker` use `PathCondition` together with `impliedGuard` when doing index / divisor checks, to see whether the caller's own guard already covers it.
- **How context sensitivity shows up**: `x + 1` may overflow inside the callee (`x` is already `MAX_INT`), but when the caller passes in `x = 5`, the caller's own `GuardStore` has already recorded `x < 100` (from a BRANCH on the `caller` path), and `intOverflow.check("x + 1 overflows")` → `PathCondition(x < 100)` ⊢ `x + 1 < 101 << MAX_INT` → UNSAT → the finding is suppressed. **This kind of Z3 verification based on the caller's path is the core of Phase 2 path sensitivity.**

### 5.6 Path Condition

The only sub-analysis that **actively pulls in Z3**:

- **Summary side**: `Operation.SolverZ3Predicate(predicate, pathVars)` lets the callee explicitly expose "its own internal guard conditions" (a typical example: the callee only uses `x` after `if (x > 0)`, and writes `"x > 0"` out as a fragment).
- **Phase 2 side**: `TaintedCheckerBase.composePathCondition(local)` strings the callee's fragments into the caller's `PathCondition`; when one of the caller's findings fires (for example "integer division by zero"), `composePathCondition("x != 0")` → `Z3Bridge.check(antecedent && !(x != 0), bindings)` → UNSAT → suppressed.
- **How context sensitivity shows up**: `bindings` are filled with the concrete values from the caller's `FactBag` ("x = 5"), so the same `x > 0` predicate has different bindings in different callers, and the Z3 verification result may differ.
- **Two-stage bridge**: `TaintedCheckerBase.solverBridge` is by default equipped with `CachedZ3Bridge(new Z3Bridge())`; when Z3 is unavailable, `Z3Bridge` automatically falls back to `LocalSolverBridge` (a pure Java parser, limited to the arithmetic subset). `CachedZ3Bridge` caches `(predicate, bindings) → Result` so that repeated SMT queries are avoided.

### 5.7 Side-Effect Invalidation

The only sub-analysis that **forces the caller's subsequent analysis to be redone**:

- **Summary side**: the four sets of `MethodSummary.sideEffect` + the `mayThrow` flag.
- **Phase 2 side**: `SummaryApplier.applySideEffect` writes `SIDE_EFFECT_RECORDED(dest, "writes-this:f,writes-param:0,...")` into the caller's fact bag; `ctx.sideEffectConsumer().recordCall(ins, ctx)` synchronously writes this fact into the per-function `SideEffectConsumer`, and alias / reallocation checkers look up `wasInvalidated(receiver, field)` from the caller's point of view on FIELDLOAD / FIELDSTORE / ARRAYLOAD / ARRAYSTORE.
- **How context sensitivity shows up**: the `this.field` passed in by the caller is modified inside the callee (`MethodSummary.sideEffect.writesThisFields()`), so from the caller's point of view **all** subsequent reads of `this.field` no longer see the value from before the callee, while other fields are unaffected — this is field-level precision from the caller's point of view.
