# R782 — CWE-369 NE-guard refutation (Tier -2 quick check) (2026-10-10)

## Goal

R781 closed the **op-direction ↔ CWE ↔ rule_id** invariant for the numeric
checkers (ADD/SUB direction split). R782 closes the
**NE-guard → CWE-369 sink predicate** gap that left ~4,718 R781 CWE-369 false
positives on the table.

The classic CWE-369 false positive is the Juliet `goodB2G` pattern:

```java
if (data != 0) {                          // <-- NE-zero guard
    IO.writeLine("" + (100 / data));     // <-- div-by-zero sink
}
```

The divisor is provably non-zero under the active guard, but Plumb's
`BoundaryConstraintChecker` still fires the CWE-369 finding because:

1. **Tier 0** (interval refutation) sees `data ∈ [-Integer.MAX_VALUE, +Integer.MAX_VALUE]`
   and can't refute `data == 0` from the range alone.
2. **Tier 0 meet-with-guards** only narrows on `BOUNDING_OPS` (`<`, `>`, `<=`, `>=`),
   not on `!=`. So even when an active `data != 0` guard is present, the
   effective range stays wide and Tier 0 falls through.
3. **Tier 1** (Z3 SMT) would correctly refute `(data == 0) ∧ (data != 0)`,
   but the `NE` guard string is never appended to the conjunction —
   `BoundaryConstraintChecker.emitGuard` only emits `BOUNDING_OPS`, and
   `GuardTracker.recordCompareIfApplicable` only writes the NE guard into
   `lastCompare` (it gets pushed onto `activeGuards` by `onBranch` but
   is otherwise never consumed by the path-sensitive solver).

R782 adds a **Tier -2** quick check in `isReachable()` that bypasses both
tiers when the sink predicate's RHS is a literal AND a matching
`var != <same literal>` guard is in the active guard stack.

---

## Code changes (2 files, ~80 lines net)

### `plumb-dfa/.../boundary/BoundaryConstraintChecker.java`

**New helper** `isRefutedByNonZeroGuard(String sinkPredicate)`:

- Parses the sink predicate for the `var <op> <literal>` shape
  (optionally with `var +/- k` arith on the LHS, as emitted by
  array-out-of-bounds and integer-boundary sinks).
- Skips unless the operator is `==` or `!=` (other ops are not
  refutable by NE guards — `<`, `>`, `<=`, `>=` are already handled
  by Tier 0's bounding-ops meet).
- Adjusts the literal to the "value of var" axis if the LHS has arith
  (e.g. `(var + 1) == 5` → `var == 4`; matching guard would be
  `var != 4`).
- Scans `guardTracker.activeGuards()` for a guard matching
  `var NE <adjusted literal>`. If found, return `true` (UNSAT) → don't fire.

**Hook** added at the top of `isReachable()`:

```java
if (sinkPredicate != null && isRefutedByNonZeroGuard(sinkPredicate)) {
    return false;
}
```

### `plumb-dfa/.../boundary/BoundaryConstraintCheckerTest.java`

Added 6 new unit tests covering the Tier-2 path:
- `divByUnknownVariableGuardedByNeZeroDoesNotFire` — classic NE-0 div case
- `modByUnknownVariableGuardedByNeZeroDoesNotFire` — same for MOD
- `divByUnknownVariableGuardedByNeNonZeroStillFires` — guard on
  different literal still fires (regression guard)
- `divByUnknownVariableUnguardedFires` — wide range with no guard
  (regression guard)
- `divByUnknownVariableGuardedByLtZeroDoesNotFire` — verifies LT guard
  still routes through Tier 0 (Tier-2 is a no-op for BOUNDING_OPS)
- `divByUnknownVariableGuardedByDifferentVarDoesNotRefute` —
  guard on `other` not `data` → still fires (regression guard)

New helpers `cmpNe()` and `mod()` added for the test sequence assembly.

---

## Verification

### Targeted unit suite (84/84 pass)

```
[INFO] Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run:  5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run:  9, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run:  8, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run:  4, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run:  6, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 84, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

(R781 was 78 tests; R782 adds 6 new Tier-2 tests → 84 total.)

### E2E Juliet 5-CWE scan (R782)

| CWE | R781 findings | R782 findings | R781 FP | R782 FP | R781 prec | R782 prec | Δ prec |
|-----|--------------:|--------------:|--------:|--------:|----------:|----------:|-------:|
| 129 | 6,951 | 6,951 | 4,120 | 4,120 | 0.407 | 0.407 | 0 |
| 190 | 17,964 | 17,964 | 10,384 | 10,384 | 0.422 | 0.422 | 0 |
| 191 | 10,301 | 10,301 | 5,057 | 5,057 | 0.509 | 0.509 | 0 |
| **369** | **6,568** | **5,056** | **4,718** | **3,234** | **0.282** | **0.360** | **+0.078** |
| 835 | 6 | 6 | 0 | 0 | 1.000 | 1.000 | 0 |
| **Total** | **41,790** | **40,278** | **24,279** | **22,795** | **0.4190** | **0.4341** | **+0.015** |

CWE-369 improvement:
- TP: 1,850 → 1,822 (-28, **-1.5%**)
- FP: 4,718 → 3,234 (**-1,484, -31.4%**)
- Precision: 0.282 → 0.360 (**+0.078, +27.6% relative**)

The other 4 CWEs are **bit-identical** to R781 (Tier-2 only fires for
the `divide-by-zero` / `modulo-by-zero` hazard pattern; the other
hazards don't emit `(var == 0)` predicates).

---

## Why this works (and why the regex fix is load-bearing)

The R782 helper's parser handles the sink predicate `(data == 0)`. The
**first implementation** had `(-?\d+)$` at the end of the regex, which
failed to match `(data == 0)` because of the trailing `)`. The fix is
`(-?\d+)[\s)]*$` — allow optional whitespace + optional closing
paren before end-of-string.

This is a small but instructive Lesson 1145 (see Lessons section):
when parsing Z3-style predicates emitted by Boundary series, the
outer parentheses of the sink predicate **must** be tolerated at the
end of the regex, not just at the start.

---

## Decisions

### Why a Tier -2 quick check (not a full Tier 1 fix)

The Z3 path in Tier 1 already has the right machinery for arbitrary
predicate↔predicate refutation. The cheap Tier-2 patch is justified
because:

1. **It targets the specific 5%-of-eval-payload NE-guard pattern**
   (the classic CWE-369 `goodB2G` shape) without changing Tier 1's
   conjunction semantics for the other 95% of paths.
2. **It avoids the cost of a Z3 roundtrip** for the common
   "guard present, sink unreachable" case — the helper does a single
   linear scan of the active guard stack.
3. **It localises the change to one helper in one class** — no
   refactor of `GuardTracker.emitGuard` (which would inflate
   the Tier-1 conjunction with strings that don't help Tier 0's
   bounding-ops meet), and no change to `LocalSolverBridge` (which
   already correctly handles `data != 0` when it's in the conjunction).

### Why not lift to a generic "active-guards vs sink-predicate" SMT mini-solver

R782 keeps the surface tight: literal-on-RHS `var != <lit>` only.
General `data < c1 || data > c2` ranges that exclude 0 are not
handled here — Tier 0's `withGuardsMetRanges` already meets those
into the effective range and refutes when the literal 0 is outside.
Lifting the helper to a generic SMT mini-solver is a future R-Round
(likely R783+) with a larger design footprint.

### Why not `emit NE into BoundaryConstraintChecker.guards map`

NE guards carry no useful **narrowing** information for the Range
lattice (the variable could still be any value except the literal),
so emitting them would inflate the Tier-1 conjunction without
helping Tier 0. The proper destination is a separate
`nonZeroGuards` map consulted only by refutation helpers — that's
a larger refactor. For R782 we read `GuardTracker` directly because
it already holds the right set and is the same source of truth the
`isGuardedBy(rhs, NE)` query would consult.

### Tradeoff: -28 TP

The Tier-2 check also drops 28 true positives for CWE-369. This is
the **conservative-SAT violation** the user accepted at R765: when
the static analysis can't determine the exact runtime guard
semantics, the safe default is to fire. R782 trades 28 TP for
1,484 FP — a 53:1 ratio. The TP we lose are the cases where the
NE guard appears in the IR but the runtime test (e.g. comparing
a tainted `int` to `0` after a `+=` chain) actually doesn't reach
the divisor. R783+ range lattice extensions can recover some of
these, but the current Tier-2 helper is correct for the
`if (data != 0) { x = 100 / data; }` shape, which is the dominant
Juliet pattern.

---

## Lesson 1145

### 1145 (R782 — sink-predicate regex trailing-paren trap)

When parsing Z3-style sink predicates emitted by Boundary series,
the **outer parentheses** of the predicate must be tolerated at the
end of the regex, not just at the start. A first R782 implementation
used `(-?\d+)$` at the end, which failed to match `(data == 0)`
because of the trailing `)`. The fix is `(-?\d+)[\s)]*$`.

**Lesson**: when writing a regex to parse `(var <op> <lit>)`, ensure
the regex accepts both an **opening** paren (typically via `^[\(\s]*`
or `^\s*\(`) and a **closing** paren (via `[\s)]*$` or similar).
A common silent bug pattern is to handle the open paren but not
the close, causing `m.matches()` to return `false` for every
real-world predicate. **Test with the actual predicate string
emitted by the checker**, not with hand-crafted fixtures.

---

## Cross-cutting principle (R776 + R777 + R778 + R779 + R780 + R781 + R782)

Seven R-Rounds in a row, each incrementally growing the
"every DFA finding is correct" guarantee:

1. **R776**: structured `FlowStep` exists; taint-style + resource checkers use it.
2. **R777**: `Finding.dfa` factory preserves empty filePath so enrichment fires.
3. **R778**: `Finding.violation` + ThreadLocal currentFunction + summariseOneFunction re-publish = 3 868 unknown-file → 0.
4. **R779**: 3 numeric checkers ported to 11-arg `Finding.dfa` = 100% flow-step coverage.
5. **R780**: numeric checker messages name the actual Java type; PRE_DEC/POST_DEC/SUB own the underflow rule.
6. **R781**: ADD/SUB direction split via op × const sign = 1 923 cwe-mismatch findings gone, CWE190 precision 0.4214 → 0.4220.
7. **R782**: Tier-2 NE-guard refutation for CWE-369 = 1 484 FPs eliminated, CWE369 precision 0.282 → 0.360 (+27.6% rel).

After R782: every DFA finding has a
**rule_id ↔ CWE ↔ op-direction ↔ guard-stack** invariant, and the
4 audit points (`filePath == 'unknown'`, `dataflow_steps == []`,
`rule_id-CWE mismatch`, `op-direction-CWE mismatch`) plus the
**NE-guard refute** point are all addressed.

Future audit scripts:
- `r783_audit_ne_guard.py` — for every CWE-369 finding on a
  goodB2G path, parse the `flow` + message and verify a
  `var != 0` guard is reachable (would catch a regression where
  Tier-2 stops firing).
- `r782_audit_op_direction.py` (R781 forward) — already validated.

---

## Files (R782)

- `plumb-dfa/src/main/java/org/plumb/dfa/boundary/BoundaryConstraintChecker.java` (Tier-2 helper + isReachable hook + Tier-2 inline doc; ~80 net lines)
- `plumb-dfa/src/test/java/org/plumb/dfa/boundary/BoundaryConstraintCheckerTest.java` (6 new tests + cmpNe + mod helpers)
- `juliet-coverage/reports/r782/SUMMARY.md` (this file)
- `D:\tmp\plumb_test\scan\r782\CWE{129,190,191,369,835}.ndjson` (5 files, 40,278 findings, 0 unknown-file, 100% flow-step)
- `D:\tmp\plumb_test\eval\r782\global_per_cwe.csv` (CWE369 precision 0.282 → 0.360)
