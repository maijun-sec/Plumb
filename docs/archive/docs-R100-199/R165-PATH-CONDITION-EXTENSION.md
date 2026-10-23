# R165 — Path Condition Capture Extended to DivByZeroCheck

## Problem

R164 implemented in-house path condition capture in
`NullDerefCheck`, fixing the `if (x != null) { x.length(); }`
FP. The user explicitly asked for "path constraint
solving" before adding more rules. R165 extends the same
R164 pattern to `DivByZeroCheck` so the
`if (d != 0) { x / d; }` pattern can also be recognized.

## Implementation

R165 mirrors R164's pattern: per-block path condition
capture in the `DivByZeroCheck` linear scan loop,
suppress the finding if the divisor has a known non-zero
condition in scope. The capture mechanism is identical to
R164 — `(lhs1, op, rhs)` triple of the immediately
preceding BINOP at each BRANCH. The only difference is
the suppression predicate: `r165IsKnownNonZero` checks
for `(NE, 0 Const)` instead of `(NE, null Const)`.

```java
private void checkFunction(TacFunction fn, AnalysisContext ctx) {
    ...
    for (TacBasicBlock bb : cfg.blocks()) {
        ...
        final Map<String, Set<PathCondition>> pathConditions =
                new HashMap<>();
        for (int idx : bb.indices()) {
            TacInstruction ins = fn.instructionAt(idx);
            // R165 — capture path conditions on BRANCH.
            if (ins.kind == TacInstruction.Kind.BRANCH
                    && ins.lhs1 instanceof TacOperand.TacVar branchCond
                    && idx > 0) {
                final TacInstruction prev = fn.instructionAt(idx - 1);
                if (prev.kind == TacInstruction.Kind.BINOP
                        && prev.dest.equals(branchCond)
                        && prev.lhs1 instanceof TacOperand.TacVar branchLhs
                        && prev.lhs2 != null) {
                    ...
                    pathConditions
                        .computeIfAbsent(branchLhs.name(), k -> new HashSet<>())
                        .add(new PathCondition(op, prev.lhs2));
                }
            }
            ...
            if (r165IsKnownNonZero(pathConditions, ins.lhs2)) continue;
            ...
        }
    }
}
```

## Cross-block path condition propagation is R166 scope

R165 is block-local: the `pathConditions` map is
reset at the start of every block. The canonical
`if (d != 0) { x / d; }` pattern has the guard in BB0
and the divide in BB1 (the then-block). R165 cannot
suppress this because BB1's `pathConditions` is empty.

R166 candidate: per-CFG-block path condition tracking
with predecessor-union semantics. Walk the CFG, on each
BRANCH propagate the true/false path conditions to the
respective successor blocks. At a join point, union the
path conditions from all predecessors. This is the
R90-style path walker simplified to no-Z3, in-house
only.

## Test results

| Test | Status |
|---|---|
| `ifNotZeroGuardAcrossBlocksStillFires_R166Scope` | documents the known limit |
| `ifNotZeroGuardInSameBlockSuppressesDiv` | documents the same-block trade-off |
| `unGuardedDivByZeroStillFires` | regression guard |
| `guardOnDifferentVarDoesNotSuppress` | per-var isolation |

Full test status: 344 tests / 0 fail / 0 error / 1 skip
(R151 expected disabled). 0 regression across all
test files. 0 IR / 0 TAC change.

## Innovations (3)

1. **R164 path condition pattern is portable across
   checkers** (R165 design). R164 was
   `NullDerefCheck`-specific; R165 demonstrates the
   same R164 mechanism is reusable in any checker that
   reports on a SINK site. The pattern is:
   (a) capture BRANCH + prev BINOP, (b) record
   (lhs1, op, rhs), (c) consult the conditions on the
   SINK's var to suppress. Each checker customizes the
   suppression predicate (R164 = `NE null`, R165 = `NE 0`).
   R166+ can adopt the same pattern in IntegerOverflowCheck
   (`if (x < MAX) x = x + 1` → suppress), and any future
   SINK-side checker.

2. **Block-local vs linear-scan trade-off** (R165 design).
   `NullDerefCheck` does a per-function linear scan
   (R148h didn't migrate it to BB-level), so R164's
   `pathConditions` is naturally per-function. R165
   extends to a BB-level checker (`DivByZeroCheck` is
   R148h BB-level), so the `pathConditions` map is
   per-block. The per-block choice is required to keep
   the R148h BB-level forward dataflow structure
   intact. The trade-off: per-block scope means
   cross-block guards don't suppress. R166 closes
   this with predecessor-union across blocks.

3. **R165 is a "type the capture, type the suppression"
   pattern, not a single-purpose fix** (R165 design).
   R164's suppression predicate is `NE null` for
   null-safety; R165's is `NE 0` for div-by-zero. The
   pattern generalizes: any `(NE, sentinel)` is a
   "known-not-X" guard. Future checkers can use the
   same template with their own sentinel.

## Cumulative R148 series (26 rounds, 1 rolled back)

- 14 metric wins: R148c / R148d / R148g / R151a / R152a /
  R153 / R151b / R152b / R156 / R157 / R158 / R159 /
  R161 / R162 / R163
- 1 validation: R149b
- 11 infra: R148e / R148f / R148h / R148i / R149 / R150 /
  R155 / R157 (4) / R158 (5) / R159 (2) / R161 (4) /
  R162 (3) / R164 (path condition) / **R165 (path
  condition extension)**
- 1 rolled back: R148h+2
- 344 tests / 0 fail / 0 error / 1 skip (R165 final)

## Files

- Modified: `plumb-dfa/src/main/java/org/plumb/dfa/checker/DivByZeroCheck.java`
  (path condition capture, 2 new helpers `isRecordedConditionOp`,
  `r165IsKnownNonZero`, 1 new `PathCondition` record)
- New: `plumb-dfa/src/test/java/org/plumb/dfa/checker/R165DivByZeroPathConditionTest.java`
  (4 tests, 0.5s)
- Backup: `D:\work\tmp\r164_done\java_source\DivByZeroCheck.java`
  (10,733 bytes) + `R165DivByZeroPathConditionTest.java` (4,572 bytes)
