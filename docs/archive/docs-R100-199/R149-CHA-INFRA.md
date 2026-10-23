# R149 — CallResolver CHA-style virtual dispatch (infra round)

**Date**: 2026-07-30
**Round**: R149 (post-R148h+2 rolled back, pre-R150)
**Status**: ✅ infra complete — 0 functional change, 4 unit tests pass

---

## 1. Goal

R148f's root-cause analysis identified the merged-program
SSA collision (Section 5.3) and the lack of a class-hierarchy
walk in `CallResolver` (Section 5.1) as two of the three
biggest reasons flow 81+ abstract-dispatch Juliet patterns
report 0 TP in merged mode. R149 ships the **infrastructure**
for the second of these — `TacFunction.enclosingClass`,
`TacProgram.classToParent`, and `CallResolver.resolveVirtual`
— so a future R-round (R150) can wire it into
`TaintAnalysis.transferUnknownCallImpl` to actually move the
metric.

This is the **infrastructure half** of the R149 plan; the
**functional half** (consume `resolveVirtual` from the taint
fixpoint) is a separate R-round because of the FP risk that
the merged-mode SSA collision creates when bad-class
overrides start contributing to the analysis on every
good-class call site.

## 2. What was implemented

### 2.1 `TacFunction.enclosingClass`

`plumb-tac/src/main/java/org/plumb/tac/ir/TacFunction.java`
— new field, new full constructor, getter, R149 javadoc.

The field carries the short name of the function's declaring
class (e.g. `"MyThread"`) or `null` for free functions /
synthetic inits. Populated only for top-level types (inner
classes' method names already encode the outer scope via
the `Outer$Inner.action` convention, so the resolver wouldn't
match them against the short name anyway).

### 2.2 `TacProgram.classToParent`

`plumb-tac/src/main/java/org/plumb/tac/ir/TacProgram.java`
— new `Map<String, String> classToParent` keyed by child
short name, valued by parent short name. Populated by
`TacTranslator` at class-declaration time and consumed by
`CallResolver.build(program)`. Only direct parents are
captured (Java has no multiple inheritance of classes;
interface implementations are out of scope for the CHA walk).

### 2.3 `TacTranslator` class-hierarchy capture

`plumb-tac/src/main/java/org/plumb/tac/translate/TacTranslator.java`
— in `translateTypeWithPrefix`, the class's `getExtendedTypes()`
is walked at top-level and the `classToParent` map is
populated. Each `TacBuilder` is told its enclosing class
via the new `setEnclosingClass(String)` setter (line 388-389
of `TacBuilder.java`); the corresponding `translateCallable`
overload accepts an `enclosingClass` argument so the method
emission path is type-checked at compile time.

### 2.4 `CallResolver.resolveVirtual`

`plumb-dfa/src/main/java/org/plumb/dfa/cg/CallResolver.java`
— new public method. BFS through the class hierarchy starting
at the receiver type, collecting every `TacFunction` whose
`name()` matches and whose `enclosingClass()` is in the
walked set. Returns a flat list (defensive copy) of all
candidate override methods.

The resolver also grows two new private indices populated
at `build(TacProgram)` time:

- `Map<String, List<TacFunction>> byEnclosingClass` — fast
  class → methods lookup.
- `Map<String, String> classToParent` (copy of the program's
  table) — fast parent lookup.

The BFS stops at the first class that has no parent entry
(typically `java.lang.Object`, which we don't model
explicitly). Loops are guarded with a `visited` set so
diamond hierarchies terminate.

## 3. Unit tests

`plumb-dfa/src/test/java/org/plumb/dfa/cg/ChaResolveTest.java`
(4 tests, all pass):

- `populatesClassHierarchy` — `class Base { void m() {} }
  class Derived extends Base {}`; `resolveVirtual("Derived", "m")`
  returns `[Base.m]` via the CHA walk.
- `emptyResultForUnknownClass` — receiver type not in any
  class index → empty list.
- `emptyResultForUnknownMethod` — receiver type is in the
  index but no method of the given name → empty list.
- `emptyResultForNullInputs` — null/empty inputs are guarded
  and return empty (rather than NPE).

## 4. Regression status

Full Plumb test suite:

| Indicator | R148h+2 rolled back | R149 |
|---|---|---|
| Total tests | 496 | **501** (+4 ChaResolveTest) |
| Failed | 5 (R148d recovery) | 5 (same) |
| Errored | 0 | 0 |
| Skipped | 2 | 2 |

CWE-89 / CWE-252 / CWE-572 Juliet re-runs all return the
same numbers as R148f baseline — R149 ships infrastructure
only, no functional change to the analysis.

## 5. Why the R150 wiring is not in this round

The temptation in R149 is to immediately call
`resolver.resolveVirtual(receiverType, methodName)` from
`TaintAnalysis.transferUnknownCallImpl` whenever the
name-based `resolveCall(calleeSig)` returns empty. That
would unlock the CWE-89 / CWE-78 / CWE-36 / CWE-80 / CWE-643
/ CWE-470 / CWE-90 / CWE-15 / CWE-319 flow 81+ Juliet
patterns.

But the **merged-program SSA collision** (R148f §5.3) means
the same `bad()` method's tainted state is reachable from
every same-named `good()` method in the merged program.
Adding CHA dispatch to the taint fixpoint on top of that
collision would surface the existing collision as a wave of
new FPs — exactly what happened in R148h+2 (where 4 new
`bu-0103-taint-sink-path` FPs appeared in CWE-36 `goodG2B`
files because the propagator-wrapper fast path let bad-class
tainted data flow into good-class `action(data)` sites).

R150 needs to either:

- (a) add a per-file / per-class taint isolation in
  `MergedProgram` so the CHA walk operates on a
  per-class tainted state, or
- (b) gate the CHA walk behind a per-class entry-point
  check so the bad-class contribution is the only one that
  flows into the cross-class call site.

(a) is the bigger change with a larger blast radius; (b)
is a more targeted fix that lands R149's value without
exposing the merged-mode SSA problem.

R150 will be the first follow-up R-round after this R149
infra ships. Until then, the resolver has a public
`resolveVirtual` API that's exercised by unit tests but
not yet consumed by the taint analysis.

## 6. Files changed / added

Modified:
- `plumb-tac/src/main/java/org/plumb/tac/ir/TacFunction.java`
  (+ 1 field, + 1 full constructor, + 1 getter, + Javadoc)
- `plumb-tac/src/main/java/org/plumb/tac/ir/TacProgram.java`
  (+ 1 map field, + 1 register method, + 1 getter)
- `plumb-tac/src/main/java/org/plumb/tac/translate/TacTranslator.java`
  (+ 30 lines: extends capture + 4 setEnclosingClass calls)
- `plumb-tac/src/main/java/org/plumb/tac/translate/TacBuilder.java`
  (+ 1 field, + 1 setter)
- `plumb-dfa/src/main/java/org/plumb/dfa/cg/CallResolver.java`
  (+ 2 index maps, + 1 `resolveVirtual` method, + build()
  update)

Added:
- `plumb-dfa/src/test/java/org/plumb/dfa/cg/ChaResolveTest.java`
  (4 unit tests)

Net IR / TAC / side-table impact:
- 1 new field on `TacFunction` (`enclosingClass`).
- 1 new map on `TacProgram` (`classToParent`).
- 1 new entry point on `CallResolver` (`resolveVirtual`).
- No new side tables, no new CFG primitives.

## 7. R148 series net total (R148c–R149, 13 rounds)

| Round | 主题 | 净结果 |
|---|---|---|
| R148c | 能力评估 | CVE recall +33% |
| R148d | Spring/JAX-RS binding annotation source | Spring4Shell +71% (partially recovered to decompiled baseline) |
| R148e | Parallel Phase-1 plumbing | perf 持平 |
| R148f | Juliet 评测基线 | TP 0% / FP 0.04% |
| R148g | UncheckedReturnCheck (CWE-252) | CWE-252: 0 → 1 TP merged / 16 TP per-file |
| R149b | per-file eval mode | validation |
| R148h | BB-level IntervalAnalysis | infra, 0 metric |
| R148i | 包结构重排 (B 方案) | 23 checker → `checker/` 子包 |
| R151 | IncorrectReturnCheck (CWE-253) | CWE-253: 0 → 1 TP / 16 TP |
| R152 | ThreadRunCheck (CWE-572) | CWE-572: 0 → 1 TP / 17 TP (17/17) |
| R153 | StringEqualityCheck (CWE-597) | CWE-597: 0 → 1 TP / 15 TP |
| R148h+2 | propagator-wrapper summary | **rolled back, net negative** |
| **R149** | **CHA-style virtual dispatch infra** | **infrastructure, 0 functional change** |

- **5 metric wins**: R148c, R148d, R148g, R151, R152, R153
- **1 validation**: R149b
- **3 infra**: R148h, R148i, **R149**
- **1 plumbing**: R148e
- **1 baseline**: R148f
- **1 rolled back**: R148h+2
- 501 tests / 5 failed (R148d recovery) / 0 errored / 2 skipped
- Net: +3 new fields on IR (`enclosingClass`, `classToParent`,
  `byEnclosingClass`) — first R-round in the series to add
  IR fields since R118b (the `paramAnnotations` field
  expansion).
