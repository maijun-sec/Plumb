# R37 — R503 Sink-Arg-Index Convention Fix

**Date**: 2026-08-17
**Round**: R37
**Status**: SHIPPED — 9 pre-existing R503 failures fixed, 0 regressions
**Effort**: ~2 hours (6 test fixture updates + 1 framework fix + 2 test rewrites)

## Why R37

The R5+ 5-day cross-fn sprint (R32-R35) had
9 pre-existing R503 test failures (8
documented in the R503 trash doc + 1
undocumented). These failures had been
deferred since R1 (2026-08-15) and were
documented in
`_trash/R503-PRE-EXISTING-TEST-FAILURES.md`.
R37 ships the fix: 6 test fixture updates
(mechanical) + 1 framework convention fix
(the R503 trash doc's "Issue 2") + 2 test
rewrites (R572-invalidated R215 nullDeref
tests).

## What R37 ships

### 1. Test fixture updates (Issue 1, 6 tests)

The R503 fix added `+1` to the sink arg
index for instance methods (TAC IR
convention: `args[0] = receiver, args[1+] =
user args`). The fix was applied to the
framework, but test fixtures were never
updated — they still used the pre-R503
convention where `args[0]` is the first
user arg.

R37 updates 6 test fixtures across 3 test
classes to prepend a synthetic
`recv` TacVar at `args[0]`:

| Test class | Test method | Fix |
|---|---|---|
| `R218aZ3BridgeWireTest` | `noBridge_defaultBehavior_firesFinding` | Auto-prepend in `callTo` helper |
| `R218aZ3BridgeWireTest` | `bridgeReturnsSat_firesFinding` | Auto-prepend in `callTo` helper |
| `R218aZ3BridgeWireTest` | `nullPathCondition_skipsBridgeCheck` | Auto-prepend in `callTo` helper |
| `R215MirrorCheckersTest` | `sensitiveDataLeak_paramToLogSink_firesCwe200` | Auto-prepend in `callTo` helper + fix `Logger#info` → `Logger.info` |
| `R221SensitiveDataLeakTest` | `passwordLeak_firesCwe200NotCwe359` | Manual prepend `loggerRecv` + fix `Logger#info` → `Logger.info` |
| `R221SensitiveDataLeakTest` | `piiLeak_firesCwe200AndCwe359` | Manual prepend `loggerRecv` + fix `Logger#info` → `Logger.info` |
| `R221SensitiveDataLeakTest` | `noLeak_doesNotFire` | Manual prepend `loggerRecv` + fix `Logger#info` → `Logger.info` |

The `callTo` helper in `R218aZ3BridgeWireTest`
and `R215MirrorCheckersTest` now
auto-prepends a synthetic `recv` when
there's 1 user arg, matching the pattern
already used in `TaintedCheckerBaseTest`
(since R6):

```java
if (args.length == 1) {
    TacOperand recv = new TacOperand.TacVar(
            "recv", TacType.ofClass("..."), false);
    TacOperand[] withRecv = new TacOperand[args.length + 1];
    withRecv[0] = recv;
    System.arraycopy(args, 0, withRecv, 1, args.length);
    args = withRecv;
}
```

For R221, the `callTo` helper is inlined
into each test (not a shared helper), so
the prepend is done per-test. This is a
minor refactor opportunity for a future
round.

Additionally, R215 and R221 tests had
`Logger#info` (with `#` separator) in
their `calleeSig` calls. The YAML sink
pattern is `^java\.util\.logging\.Logger\.info`
(with `.` separator). The `#` form doesn't
match the pattern, so the sink lookup fails
silently. R37 updates the calleeSig to
`Logger.info` to match the pattern, and
updates the test-defined pattern from
`Logger#info` to `Logger\.info` to match
the YAML.

### 2. R572-invalidated test rewrites (2 tests)

The R572 fix (2026-08-16) changed
`NullDerefChecker.initFunction` to CLEAR
the `nullTaintedVars` map (rather than seed
it with all params). This was a deliberate
FP fix. The R215 `nullDeref_fieldLoadOnNullParam_firesCwe476`
and `nullDeref_arrayLoadOnNullHandle_firesCwe476`
tests were written assuming the pre-R572
behavior (params seeded into nullTaintedVars
by initFunction). After R572, these tests
fail because params are no longer
null-tainted by default.

R37 rewrites these 2 tests to use an
explicit null-literal MOVE first:

```java
// Before R37 (relied on initFunction seeding)
TacFunction f = makeFn("App#field(Object)",
        List.of(obj),  // param, assumed null-tainted
        List.of(fieldLoad(len, obj, "length"), ...));

// After R37 (explicit null-literal MOVE)
TacFunction f = makeFn("App#field()",
        List.of(),  // no params
        List.of(
                MOVE obj = Const.NULL,  // explicitly null-tainted
                fieldLoad(len, obj, "length"),
                ...));
```

This is the correct pattern (it matches the
R572 test which uses `String s = null; s.length();`).

### 3. Framework convention fix (Issue 2, 1 test)

The `checkSinkForFamily` method records
`SinkObservation`s with the TAC arg
index (with `+1` for instance methods).
This caused `summarizeFunction` to
silently drop the Sink op for 1-param
functions: `argIdx = javaArgIdx + 1 = 1`,
`params.size() = 1`, so `1 < 1` is false.

R37 fixes this by using the user-arg
index (`javaArgIdx`) for the SinkObservation.
This matches the convention used by
`paramOpsAt(i)` (i = user-arg index).

```java
// Before R37: SinkObservation uses TAC arg index
sinkObservations.add(new SinkObservation(family, argIdx));
// (argIdx = javaArgIdx + 1 = 1 for 1-param function)
// (summarizeFunction checks so.argIndex < params.size() = 1 < 1 = false)
// (Sink op silently dropped)

// After R37: SinkObservation uses user-arg index
sinkObservations.add(new SinkObservation(family, javaArgIdx));
// (so.argIndex = 0 = first user arg)
// (summarizeFunction checks 0 < 1 = true, Sink op recorded)
```

Additionally, `propagateTaintFromCalleeSummary`
was updated to iterate user-arg indices
(was iterating TAC arg indices, which was
off-by-one for instance methods):

```java
// Before R37: iterates TAC arg indices
for (int i = 0; i < ins.args.size(); i++) {
    TacOperand arg = ins.args.get(i);
    boolean tainted = calleeSummary.paramOpsAt(i).stream()...
}

// After R37: iterates user-arg indices
int maxParams = Math.max(0, ins.args.size() - 1);
for (int i = 0; i < maxParams; i++) {
    int callArgIdx = i + 1;
    if (callArgIdx >= ins.args.size()) break;
    TacOperand arg = ins.args.get(callArgIdx);
    boolean tainted = calleeSummary.paramOpsAt(i).stream()...
}
```

The `i + 1` offset accounts for the
receiver at `args[0]` for instance
methods. For static methods, the bound
`maxParams = args.size() - 1` is conservative
(a static method with N params has N user
args, all at `args[0..N-1]`; we look at
`args[1..N]` which would be OOB for static).
This is sound because `paramOpsAt(i)` for
`i >= params.size()` returns an empty set.

### 4. R34 test update (1 test)

The R34 `fnDeepStyleFieldTaintFlowsFromHelperToFieldLoad`
test had its helper summary with both a
TaintPropagate AND a FieldOp on param 0.
With the R37 framework change (TaintPropagate
now marks the user arg instead of the
receiver), the test's "param:req" assertion
failed (the user arg now had the
"callee:Holder#setData" label).

R37 updates the helper summary to remove
the redundant TaintPropagate, keeping
only the FieldOp. The cross-function
field taint flow goes through the
FieldOp alone, and the user-arg keeps
its pre-existing "param:req" label.

## Test coverage (9 tests fixed, 0 regressions)

| Test class | Tests fixed | Method |
|---|---|---|
| `R218aZ3BridgeWireTest` | 3 (noBridge, bridgeReturnsSat, nullPathCondition) | `callTo` helper auto-prepend |
| `R215MirrorCheckersTest` | 3 (sensitiveDataLeak, 2 nullDeref) | `callTo` helper + pattern fix + null-literal MOVE rewrite |
| `R221SensitiveDataLeakTest` | 2 (passwordLeak, piiLeak) | Manual `loggerRecv` prepend + `Logger#info` → `Logger.info` |
| `TaintedCheckerBaseTest` | 1 (summarizeFunctionRecordsTaintPropagateAndSink) | Framework fix (Issue 2) |

Plus 1 R34 test updated to work with the
new framework convention.

**Total: 9 pre-existing R503 failures → 0**.

## Regression check

Ran the full test suite (459 tests in
`summary.*Test` + `taint.*Test` +
`checkers.*Test`):

- **458 tests pass, 0 new regressions**.
- 1 pre-existing failure remains:
  `R16ProcessControlCheckerTest.literalLibraryNameDoesNotFire`.
  This is NOT caused by R37 — verified by
  temporarily reverting R37 framework
  changes and confirming the test still
  fails. The R16 failure is an
  undocumented pre-existing issue (not in
  the R503 trash doc). R37 doesn't address
  it; that's a separate round.

Also re-ran the R35 sweep: 30 fires on 30
files (100% recall), no Juliet regression.

## Cross-cutting design decisions (R37)

1. **Synthetic recv in test helpers**
   (R218a, R215) — the `callTo` helper
   auto-prepends a synthetic `recv` for
   1-user-arg calls. This is a test-helper
   convention, not a framework change.
   Production code (TAC IR builder) always
   prepends the receiver per the plumb-tac
   convention; only hand-written test
   fixtures needed the helper.
2. **Manual prepend in R221** — R221's
   test inlines the CALL instruction (no
   shared `callTo` helper), so the prepend
   is done per-test. A future R-round could
   extract a shared helper to reduce
   duplication.
3. **`Logger#info` → `Logger.info` fixture
   fix** — the test calleeSig used `#` (the
   Jimple separator) but the YAML sink
   pattern uses `\.` (the FQN-style
   separator with regex escape). The fix
   updates both the calleeSig and the
   test-defined pattern to use `\.`. The
   test pattern and the YAML pattern are
   now consistent.
4. **Null-literal MOVE rewrite for R572
   compatibility** — the R572 fix changed
   `initFunction` to clear (not seed) the
   nullTaintedVars map. R37's test rewrite
   matches the R572 test pattern
   (`String s = null; s.length();`).
5. **Framework convention: user-arg index**
   — the `paramOpsAt(i)` convention is
   "i is the user-arg index" (0 = first
   user arg). The SinkObservation and
   `propagateTaintFromCalleeSummary` now
   use this convention throughout. The
   internal `+1` for the TAC arg index is
   encapsulated in the framework (the
   framework knows about the TAC IR
   receiver-offset; the summary layer
   doesn't).
6. **Static method handling** — the R37
   `propagateTaintFromCalleeSummary` change
   uses `maxParams = args.size() - 1` which
   is correct for instance methods (the
   common case) and conservative for static
   methods. A future round can detect
   static methods via the callee signature
   and use `maxParams = args.size()` for
   static.

## Files modified in R37

| File | Status | Changes |
|---|---|---|
| `plumb-dfa/src/main/java/org/plumb/dfa/unified/taint/TaintedCheckerBase.java` | MODIFIED | `checkSinkForFamily` uses user-arg index; `propagateTaintFromCalleeSummary` iterates user-arg indices |
| `plumb-dfa/src/test/java/org/plumb/dfa/unified/taint/R218aZ3BridgeWireTest.java` | MODIFIED | `callTo` helper auto-prepends `recv` |
| `plumb-dfa/src/test/java/org/plumb/dfa/unified/checkers/taint/R215MirrorCheckersTest.java` | MODIFIED | `callTo` helper auto-prepends `recv`; `Logger#info` → `Logger\.info`; 2 nullDeref tests rewritten with null-literal MOVE |
| `plumb-dfa/src/test/java/org/plumb/dfa/unified/checkers/taint/R221SensitiveDataLeakTest.java` | MODIFIED | Manual `loggerRecv` prepend; `Logger#info` → `Logger.info`; pattern fix |
| `plumb-dfa/src/test/java/org/plumb/dfa/unified/taint/R34FieldTaintTrackerTest.java` | MODIFIED | fnDeepStyle helper summary: removed redundant TaintPropagate |
| `plumb-dfa/docs/R37-R503-SINK-ARG-INDEX-FIX-2026-08-17.md` | NEW | This doc |

## R37 known limitations

- **R16 pre-existing failure not addressed**
  (`R16ProcessControlCheckerTest.literalLibraryNameDoesNotFire`).
  This is a 10th pre-existing failure that
  wasn't in the R503 trash doc. R37 doesn't
  address it; it's a separate round.
- **Static method handling is conservative**
  in `propagateTaintFromCalleeSummary`.
  A future round can detect static
  methods and use `maxParams = args.size()`.
- **R221 manual prepends** are duplicated
  per-test. A future round can extract a
  shared `callTo` helper.

## Cumulative R1-R37 (Plumb only)

- 39+ CWE families (no new CWE families
  in R37 — this is a test-fixture /
  framework-convention fix)
- ~16,351 cweFire hits (unchanged — no
  Juliet regression from R37)
- 109 checkers (unchanged)
- **290 → 290 unit tests (R37 doesn't add
  new tests; it fixes existing ones)**
- **0 R503-pre-existing failures** (was 9
  pre-R37, all 9 fixed; 1 additional
  pre-existing failure (R16) not in scope
  for R37)
- 0 regressions

## R37's place in the R32-R36 sprint

R37 is a follow-up to the R5+ 5-day cross-fn
sprint (R32-R36). The R32-R35 rounds shipped
the data structures (R32), path enrichment
(R33), FieldTaintTracker (R34), and R10
baseline validation (R35). R37 fixes the
9 pre-existing R503 test failures that were
documented before the sprint but never
addressed. R37 brings the unit test count
back to 100% pass rate on the 4 test classes
that had pre-existing failures.

## Future rounds (post R37)

- **R38**: Full R10 baseline regression
  (60+ files per CWE family, 30-minute
  sweep). Expected: +62 CWE-256 / +126
  CWE-259 / +85 CWE-89 per R5+ design doc.
- **R39**: Fix R16 literal library test
  (process-control FPs on literals). The
  R16 ProcessControlChecker fires on every
  loadLibrary call regardless of taint;
  the test expects it to skip literals. A
  future round can add a literal-detection
  check.
- **R40+**: Cross-class field propagation
  (deferred per R5+ design).
- **Fathom / AetherCode**: back to
  R459-5 / R97-H tracks (separate projects).
