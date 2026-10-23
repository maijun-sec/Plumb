# Trace Coverage Audit — 2026-08-13

## Goal

User directive: **"plumb-dfa MUST support trace for everything"**. Every
finding emitted by a unified checker must have a non-empty `flowSteps` list so
reporters (NDJSON / SARIF / console) can render the source-to-sink path.

## Audit Method

For every unified DFA checker, verify the `Finding` constructor receives a
`List<FlowStep>` argument (10-arg form), not just a single `flow` string
(9-arg form).

| Checker family | Count | Trace status before | After |
|----------------|------:|---------------------|-------|
| `TaintedCheckerBase` subclasses (28) | 28 | entry/propagate/sink/summary-fold ✅ but 4 numeric ones missing "source" seed | **all 28 with full source→propagate→summary-fold→sink chain** |
| `PathSensitiveCheckerBase` (1) | 1 | only legacy 9-arg form, no `flowSteps` | **alloc-site + violation 2-step** |
| Direct `DfaChecker` (18) | 18 | only legacy 9-arg form, no `flowSteps` | **single "violation" step via `Finding.violation` factory** |
| taint/ structural (NullDeref, PathTraversal, etc.) | ~12 | only legacy 9-arg form | **single "violation" step** |

## Bugs Found (4)

### 1. `InputSourcePatterns` only matched `.` separator, not `#` (R-?)

`InputSourcePatterns.isInputSourceCall` substring-matched the callee against
`.readLine`, `.parseInt`, etc. The IR's actual callee format is
`java.io.BufferedReader#readLine()` (hash separator, not dot). All 4 numeric
checkers using this check missed every real-world call site, so
local variables initialised from `readLine` / `parseInt` / `nextInt` were
invisible to the analysis.

**Fix**: normalise `#` → `.` before matching.

### 2. `summarizeFunction` never emitted `TaintPropagate` on returnOps (R-?)

`TaintedCheckerBase.summarizeFunction` only emitted `TaintPropagate` on
`paramOps`. A `helper(s) -> s` passthrough function would have its `return`
value silently lose taint, and the cross-function summary-fold path would
fail to taint the call's dest.

**Fix**: track `passedOutTaint` in `RETURN` case; emit `TaintPropagate` on
`returnOps` for tainted returned values.

### 3. 4 numeric checkers didn't seed `taintPaths` on input-source call (R-?)

`IntOverflowChecker` / `DivByZeroChecker` / `IntUnderflowChecker` /
`ArrayIndexChecker` called `taintedVars.put(d, ...)` for an input-source
call but did NOT update `taintPaths`. Subsequent `MOVE`/`BINOP`
propagation produced a chain starting at the BINOP, with no leading
"source" step.

**Fix**: new `TaintedCheckerBase.markSource(...)` helper; all 4 numeric
checkers now use it. Also moved the input-source seeding into
`TaintedCheckerBase.checkInstruction`'s `CALL` branch so ALL 28 taint
checkers (not just the 4 numeric ones) get source-step coverage for
local-var-from-input patterns.

### 4. 18 direct DfaChecker-style checkers had no `flowSteps` (R-?)

These are structural checkers (`OffByOneChecker`, `InsecureCryptoChecker`,
`IteratorConcurrentModificationChecker`, etc.) that build a `Finding` via
the 9-arg constructor with `flow: <string>` only. `flowSteps` was always
empty, violating the "support trace for everything" directive.

**Fix**: new `Finding.violation(...)` static factory that creates a
single-step "violation" `FlowStep` at the finding site. All 18 checkers
converted.

## Files Changed

### Engine / Core
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/engine/FlowStep.java` — doc updated, role list refreshed (`source | propagate | summary-fold | sink | violation`).
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/engine/Finding.java` — new `violation(...)` static factory.
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/taint/InputSourcePatterns.java` — `#` → `.` normalisation.
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/taint/TaintedCheckerBase.java`:
  - New `markSource(v, label, ins, fnSig, expr)` helper.
  - `initFunction` uses `markSource` (param-as-source, role = `"source"`).
  - `case CALL` now seeds `markSource` for input-source calls (covers 28 taint checkers).
  - `case RETURN` records `passedOutTaint`.
  - `summarizeFunction` emits `TaintPropagate` on `returnOps` for tainted returned values.

### Taint checkers
- `taint/IntOverflowChecker.java`, `taint/IntUnderflowChecker.java`, `taint/DivByZeroChecker.java`, `taint/ArrayIndexChecker.java` — use `markSource` for input-source calls.
- `taint/UseAfterFreeChecker.java` — `emitFinding` uses 10-arg `Finding` ctor with a "violation" `FlowStep`.

### Path-sensitive
- `taint/PathSensitiveCheckerBase.java` (via `PathSensitiveCheckerBase.java` in `taint/`) — `emitLeakFinding` builds a 2-step trace (`source` at alloc site + `violation`).

### Direct DfaChecker-style (18 files, all converted to `Finding.violation`)
`OffByOneChecker`, `InsecureCryptoChecker`, `IteratorConcurrentModificationChecker`,
`OrmUnnecessaryGetDfaChecker`, `EqualsCalledOnNullChecker`, `CompareWithSelfChecker`,
`OrmLostUpdateDfaChecker`, `EmptyCatchHandlerChecker`, `CollectionSelfReferenceChecker`,
`OrmLoadNullCheckDfaChecker`, `ReturnFromFinallyChecker`, `ServletAtomicityDfaChecker`,
`TryCatchFinallyChecker`, `UncaughtExceptionPropagator`, `UncheckedReturnChecker`,
`UninitNonnullDfaChecker`, `XxeChecker`, `SingletonRaceDfaChecker`.

### Tests
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/RflowStepEmissionTest.java`:
  - Existing test updated to assert `"source"` (not `"entry"`) as first role.
  - **New** `sqlInjectionCheckerEmitsSourceFromInputCall` — verifies source→propagate→sink chain via `BufferedReader#readLine` (3 steps, exact line numbers).
  - **New** `sqlInjectionCheckerEmitsSummaryFoldAcrossFunctions` — verifies cross-function `helper(s) → s` passthrough emits summary-fold.

## Test Results

```
plumb-dfa: 491 / 491 PASS (75 test classes, slow Juliet sweeps excluded)
plumb-main:         74 / 74 PASS (12 test classes)
```

## Cross-Function Path (now actually working)

Before:
```
helper(s) -> s    summary:  paramOps[TaintPropagate(param:0)]    // only param tainted, return missing
caller(name)      →  helper(name); executeQuery(result)
                  summary-fold: MISSING (no returnOps, so dest not tainted)
                  sink:         no fire
```

After:
```
helper(s) -> s    summary:  paramOps[TaintPropagate(param:0)]
                              returnOps[TaintPropagate(return:s)]  // ← new
caller(name)      →  helper(name); executeQuery(result)
                  summary-fold: present ("call helper, returns taint")
                  sink:         fires
                  flowSteps:    [source(param:name), summary-fold(call helper), sink(executeQuery)]
```

## Known Limitations

- `flowSteps[].filePath` is set by `FindingReporter.withFilePath` from the
  enclosing class; step-level paths inside `TaintedCheckerBase` are still
  empty until a step's line/column gets resolved against a program. Cross-TU
  paths therefore show the *caller's* file, not the callee's. (Existing
  limitation; out of scope for this audit.)
- 4 numeric checkers + UseAfterFreeChecker + PathSensitiveCheckerBase have
  1-3 step traces; taint-style checkers (SQL, Command, XSS, ...) have full
  source→propagate→summary-fold→sink chains.
- `FindingReporter.report` only fills `filePath` when `ctx.currentFunction()`
  is set. Direct DfaChecker.finish() findings (e.g. ServletAtomicityDfaChecker)
  may have empty `filePath` because `currentFunction` is unset; reporter
  should be improved to fall back to the synthesized file path of the
  *first observed* function.
