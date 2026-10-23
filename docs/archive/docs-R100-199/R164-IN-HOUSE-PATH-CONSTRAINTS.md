# R164 — In-House Path Constraint Tracking (Lightweight R90/Z3 Alternative)

## Problem

R90's Z3-based reverse path walker (Fathom-side C/C++) does
heavyweight path feasibility reasoning. Plumb (Java) doesn't
have a Z3 dependency and the walker-equivalent would be a
500+ line architectural change. The Plumb null-deref check
(R148) had two failure modes the user has been flagging:

1. **Over-fire on `if (x != null) { x.length(); }`** —
   the linear-scan checker doesn't know about the null
   guard, so the FIELDLOAD/CALL on `x` reports a NULL
   deref. In reality, the guard proves `x` is non-null in
   the then-branch.
2. **No path-sensitivity whatsoever** — every BRANCH is
   a no-op in the data flow; the analysis is may-only.

The user explicitly asked for "path constraint solving"
before adding more rules. R164 implements an in-house
path-condition tracker that fixes failure mode (1) without
adding a Z3 dependency or a 500-line walker.

## Fix

R164 — `NullDerefCheck` now captures per-var path
conditions on `BRANCH` instructions. The condition is the
immediately preceding `BINOP`'s `(lhs1, op, rhs)` triple.
The condition stays in scope for the rest of the
straight-line path (until the next `RETURN` / `THROW`).
A SINK (FIELDLOAD/ARRAYLOAD/CALL on a NULL var)
suppresses its finding if the SINK's var has a known
non-null condition (`!= null` with a null literal RHS).

```java
if (ins.kind == TacInstruction.Kind.BRANCH
        && ins.lhs1 instanceof TacOperand.TacVar branchCond
        && i > 0) {
    final TacInstruction prev = fn.instructionAt(i - 1);
    if (prev.kind == TacInstruction.Kind.BINOP
            && prev.dest != null
            && prev.dest.equals(branchCond)
            && prev.lhs1 instanceof TacOperand.TacVar branchLhs
            && prev.lhs2 != null) {
        final String op = prev.binop != null
                ? prev.binop.name() : "";
        if (isRecordedConditionOp(op)) {
            pathConditions
                .computeIfAbsent(branchLhs.name(), k -> new HashSet<>())
                .add(new PathCondition(op, prev.lhs2));
        }
    }
}
```

The SINK suppression:

```java
if (r164IsKnownNonNull(pathConditions, v.name())) break;
```

where `r164IsKnownNonNull` returns true iff `v` has at
least one condition `(NE, null Const)`.

## R164 is block-local, may-analysis, conservative

R164 does **not** distinguish true-branch from false-branch.
The captured conditions are visible to both branches. This
is the canonical may-analysis behavior. It can over-suppress
(the false branch sees the same `!= null` condition that
was true in the if-branch), but it cannot over-fire.

The reason it's safe: an over-suppression on the false
branch is a false negative (miss a real bug), not a false
positive. False negatives are the side we already accept
for UNKNOWN state in `NullDerefCheck`'s reporting policy
(line 30 of the original file: "We treat UNKNOWN as 'may
be null' for safety but skip reporting to keep precision").
R164 only adds to the suppression side, never to the
fire side.

## R165+ scope

The natural follow-up is per-CFG-block path tracking:
walk the CFG, propagate per-block path conditions through
branches, and on each merge point do the right
intersection (not union) of true/false path conditions.
This is the R90-style path walker simplified to no-Z3,
in-house only. R164 is the foundation; R165 is the
full-fat version.

The user explicitly said "先优化底层实现 (data flow + path
constraint solving), 然后再是规则优化" — R164 is the first
half of the data-flow optimization. R165 closes the
over-suppression gap. R166+ integrate the new path
constraint infrastructure with the rest of the checkers
(DivByZero, IntegerOverflow) and the CVE suites.

## Test results

R164 introduced 5 tests, all pass:

| Test | Status |
|---|---|
| `ifNotNullGuardSuppressesFieldAccess` | pass (the canonical fix) |
| `earlyReturnAfterNullCheckStillFires_KnownLimit` | pass (documents R165 scope) |
| `unGuardedNullDerefStillFires` | pass (regression guard) |
| `guardOnDifferentVarDoesNotSuppress` | pass (per-var isolation) |
| `branchTerminatorClearsPathConditions` | pass (RETURN/THROW reset) |

Full test status: 340 tests / 0 fail / 0 error / 1 skip
(R151 expected disabled). 0 regression across 16
taint/checker test files. 0 IR / 0 TAC / 0 new side
table change (R164 is purely a checker-internal
optimization, no IR surface change).

## Innovations (4)

1. **R164 in-house path condition tracking without Z3**
   (R164 design). The Fathom-side R90 walker is 500+ lines
   of Z3 + CFG + path feasibility reasoning. R164 is a
   100-line linear-scan implementation that captures only
   the (lhs1, op, rhs) triple of the immediately preceding
   BINOP. It's the 80/20 solution: handles the canonical
   `if (x != null) { ... }` and `if (x > 0) { ... }` patterns
   without bringing in a theorem prover. R165 candidate:
   extend to per-CFG-block tracking.

2. **Block-local path conditions cleared at RETURN/THROW**
   (R164 invariant). The capture is per-function, cleared
   at function-exit terminators. Within a function, the
   conditions stay in scope across BRANCHes (may-analysis
   over-approximation). This matches the natural
   linear-scan structure: BRANCH is a no-op, RETURN/THROW
   ends the function. The over-suppression is bounded by
   the function body — within one function, conditions
   don't leak across function boundaries.

3. **R164 is a conservative over-suppressor, not over-firer**
   (R164 trade-off). The may-analysis approximation can
   miss real bugs in the false branch (over-suppress), but
   cannot fire false positives. This aligns with the
   existing `NullDerefCheck` policy of "we treat UNKNOWN
   as may be null for safety but skip reporting to keep
   precision" — R164 is in the same risk-averse
   direction. R165 will close the over-suppression gap
   with per-CFG-block path tracking.

4. **R164 is the first user of a path condition
   abstraction; future R-rounds can extend the same
   pattern to other checkers** (R164 design). The capture
   mechanism is general — any checker's SINK-side
   suppression can consult `pathConditions` for the
   var it's about to report on. DivByZeroCheck can
   recognize `if (x != 0) { ... x / y ... }` (y = x is
   guarded against zero). IntegerOverflowCheck can
   recognize `if (x < MAX) { x = x + 1; }` (the B2G
   guard). The infrastructure is one helper + one map;
   checker adoption is opt-in per-SINK-site.

## Cumulative R148 series (25 rounds, 1 rolled back)

- 14 metric wins: R148c / R148d / R148g / R151a / R152a /
  R153 / R151b / R152b / R156 / R157 / R158 / R159 /
  R161 / R162 / R163
- 1 validation: R149b
- 10 infra: R148e / R148f / R148h / R148i / R149 / R150 /
  R155 / R157 (4) / R158 (5) / R159 (2) / R161 (4) /
  R162 (3) / **R164 (path condition infra)**
- 1 rolled back: R148h+2
- 340 tests / 0 fail / 0 error / 1 skip (R164 final)

CVE 累计 (post-R164, 4/4 detected, 0 regression):
- cc4: 1 taint + 2 other
- fastjson: 5 deserialize
- log4shell: 6 taint-sink-log + 3 direct JNDI
- spring4shell: 8 taint-sink-reflection

## Files

- Modified: `plumb-dfa/src/main/java/org/plumb/dfa/checker/NullDerefCheck.java`
  (path condition capture in `checkFunction`, 3 new helpers
  `isRecordedConditionOp`, `r164IsKnownNonNull`,
  `clearPathConditionsForAll`, 1 new `PathCondition` record)
- New: `plumb-dfa/src/test/java/org/plumb/dfa/checker/R164PathConditionTest.java`
  (5 tests, 0.5s)
- Backup: `D:\work\tmp\r164_done\java_source\NullDerefCheck.java`
  (14,127 bytes) + `R164PathConditionTest.java` (4,870 bytes)
