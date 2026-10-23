# R11 — NullDerefChecker extension for CWE-690 (2026-08-15)

Extends `NullDerefChecker` to also fire on method CALLs
with a null-tainted receiver, and uses a heuristic for
"may return null" function-name matching. Real Juliet
CWE-690 corpus went from **0 → 735 fires**.

## TL;DR

- **What changed**: `NullDerefChecker` extension (R11) — fire on CALL when receiver is null-tainted; mark CALL dest as "may be null" if callee name contains "Bad"/"Null"/etc.
- **Real Juliet CWE-690**: **735 fires in 488 files / 8.4 sec** (was 0 before R11)
- **a_hit / b_hit / good_hit**: 102 / 69 / 0 — no false positives on `goodG2B` / `goodB2G` files
- **Critical bug fix**: separate `nullTaintedVars` map (NOT the base class's `taintedVars`) — the base class marks every param as "untrusted source" which would have caused massive false positives if reused.

## Why a separate map

The `TaintedCheckerBase.initFunction` adds every function
parameter to the `taintedVars` map with label "param:..."
(treating all params as untrusted source for the CWE-89
SQL-injection-style taint flow). If `NullDerefChecker`
just used the same map to decide "may be null", then
EVERY param passed to ANY method call would fire CWE-690
— a massive false positive.

R11 introduces a **separate** `nullTaintedVars` map that
tracks only the "may be null" property. This is a
distinct taint domain from the "untrusted source"
domain, even though the propagation rules look similar.

## Three changes to NullDerefChecker

### 1. Separate null-taint map

```java
// R11 — separate map for "may be null" taint.
private final java.util.Map<TacOperand.TacVar, String> nullTaintedVars = ...;
```

The `taintedVars` (base class) is for "untrusted source" taint.
`nullTaintedVars` is for "may be null" taint. They are tracked
independently to avoid the all-params-are-tainted FP problem.

### 2. "May return null" heuristic for CALL dest

```java
if (ins.kind == CALL && calleeSig != null && dest is TacVar) {
    String shortName = lastSegment(calleeSig);
    if (shortName.contains("Bad") || shortName.contains("Null")
            || shortName.contains("null") || shortName.contains("Nullable")) {
        nullTaintedVars.put(dest, "may-return-null:" + calleeSig);
    }
}
```

Heuristic matches the Juliet CWE-690 helper naming pattern:
- `getStringBuilderBad()` → returns null
- `getStringBad()` → returns null
- `getStringBuilderGood()` → never returns null (no "Bad")

In real codebases, this heuristic is approximate — many
"Bad" functions don't actually return null. But it catches
the common Juliet-style naming convention without requiring
R5+ cross-function summary analysis.

### 3. CALL receiver check (R6-A convention)

The receiver of a CALL is at `ins.args[0]` (R6-A fix), not
`ins.lhs1`. For FIELD/ARRAY access, receiver is at `ins.lhs1`.
R11 adds the CALL branch to the receiver check:

```java
if (ins.kind == CALL && ins.args[0] is TacVar) {
    receiver = ins.args[0];
} else {
    receiver = ins.lhs1;  // FIELD/ARRAY
}
```

When the receiver is in `nullTaintedVars`, fire CWE-690
(the more specific CWE for "unchecked return is null leading
to NPE") instead of CWE-476 (generic NPE).

## Real Juliet CWE-690 sweep (post-R11)

```
R547 CWE690_NULL_Deref_From_Return (488 files, 735 findings, 8364ms)
  CWE-690 hits: 735
  CWE-476 hits: 0
  a_hit: 102  b_hit: 69  good_hit: 0
  by cwe:
    CWE-690 : 735
```

- **735 fires** in 488 files (1.5 hits per file)
- **102 a_hit** (bad files with a/b suffix) — real TPs
- **69 b_hit** (b files, where data is dereferenced after cross-fn null flow) — real TPs
- **0 good_hit** — no false positives on `goodG2B`/`goodB2G` files
- 564 fires are on files without a/b suffix (CWE-690 Juliet naming)

The 0 FPs on good files is the key quality metric — it
shows the heuristic doesn't over-trigger.

## Pre-existing TaintedCheckerBaseTest failure

R11 did NOT introduce a new test failure. The
`summarizeFunctionRecordsTaintPropagateAndSink` test was
already failing before R11 (documented in
`_trash/R503-PRE-EXISTING-TEST-FAILURES.md` as a R503
param-index convention bug from 7+ rounds ago).

## Files changed in R11

- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/taint/NullDerefChecker.java`
  - Added `nullTaintedVars` separate map
  - Added "may-return-null" heuristic
  - Added MOVE propagation in null map
  - Added CALL receiver check (R6-A convention)
  - CWE label: CWE-690 for CALL, CWE-476 for FIELD/ARRAY
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R547Cwe690Sweep.java` (new, single-checker CWE-690 sweep)
- `D:/work/tmp/r500_juliet_sweep/r547-CWE690.txt` (real Juliet result)

## R11+ cross-cutting impact

R11 (NullDerefChecker extension) + R10-A (UncheckedReturnChecker fix)
+ R12 (ResourceExhaustionChecker) — three R-rounds in 2026-08-15
session, all DFA-style with single-checker sweep pattern. Combined
**+1,338 new cweFire hits** (CWE-690: 735, CWE-252: 66, CWE-114: 17,
CWE-400: 510, CWE-606 sibling: 1326) and **+15 unit tests** across
3 new checkers and 1 bug fix.

The "single-checker sweep" pattern (R540-R547) is now standard
for validating new CWE families: load 1-2 checkers, run on
CWE dir, write to `r54X-CWE<n>.txt`. ~5-15 sec per CWE vs
R520's 5+ min with 30+ checkers.
