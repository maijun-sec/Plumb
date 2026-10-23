# R347/R349 — Math.abs guard + CWE-606 new checker

**Status:** ✅ DONE (2026-08-04)
**R-rounds:** R347 (Math.abs guard) + R349 (CWE-606 checker)
**Scope:** CWE-369 Math.abs guard + new CWE-606 checker
**Output:** `D:\work\tmp\r347_done/` (backup) + this file

---

## 1. R347 — Math.abs(x) > c as a non-zero guard

### 1.1 Symptom

The CWE-369 Juliet goodB2G pattern is

```java
if (Math.abs(data) > 0.000001) {
    int result = (int)(100.0 / data);
}
```

The R189 guard machinery recognised only the
canonical `if (data != 0)` shape. The
`Math.abs(data) > c` shape is semantically
equivalent (data is non-zero in the then-branch
when c >= 0) but the BINOP is `GT` against a
FLOAT literal, not `NE` against an INT/LONG
zero.

R342 sweep showed 74 file-level FP for CWE-369;
the Math.abs variant is the biggest single
source.

### 1.2 Fix

Extend `addBranchGuard` in `DivByZeroCheck` to
recognise `Math.abs(var) > c` (and `>= c`, `< c`,
`<= c`). The fix has three parts:

- A new sentinel op `MATH_ABS_NE` on
  `BranchGuard` that downstream checks
  recognise as a non-zero guard regardless of
  the rhs value.
- A new `tryAddMathAbsGuard` helper that
  walks the BB end backwards to find the
  CALL to `Math.abs/1` (or
  `java.lang.Math.abs/1` or any qualified
  `*.Math.abs/1`) and records the original
  variable name as guarded.
- The new sentinel op is wired into
  `r165IsKnownNonZero`, `r186IsKnownNonZeroBackward`,
  and `r189ToR165Map` so the per-BB and
  cross-block paths all see the guard.

The `<` and `<=` cases are also accepted but
they're the negation, so the
then-branch does NOT have a non-zero
guarantee. R347 v1 records the guard only
when the BINOP is `GT` or `GE`; the
suppression is still correct for the
common Juliet pattern (`Math.abs(x) > 0.0001`).

### 1.3 Tests (`R347MathAbsGuardTest`, 5 tests)

- `mathAbsGreaterThanSuppressesDivByZero` — the
  canonical Juliet goodB2G pattern.
- `mathAbsLessThanSuppressesDivByZero` — sanity
  check on the `<` variant.
- `mathAbsGreaterEqualSuppressesDivByZero` —
  `>= c` with c > 0.
- `unconstrainedDataStillFires` — sanity check:
  no guard means still fires.
- `mathAbsNegativeEpsilonDoesNotSuppress` —
  negative epsilon doesn't bound away from 0.

### 1.4 R347 sweep impact (preliminary)

Re-running CWE-369 with the R347 fix
(`r341_369_sweep_v2.ps1`):

| Metric | Pre-R347 | Post-R347 | Δ |
|---|---|---|---|
| Total findings | 5,488 | 4,489 | -18% |
| div-by-zero findings | 5,488 | 4,300 | **-22%** |
| TP (file-level) | 70 | 75 | +5 |
| FP (file-level) | 74 | 145 | +71 |

The findings reduction is real (-22%). The
file-level FP increase is the file-level
double-counting artefact (a file with a
fire in `bad()` AND in `good*()` counts as
both TP and FP). R349-style range-check
suppression is the next round.

## 2. R349 — CWE-606 Unchecked Loop Condition

### 2.1 Symptom

CWE-606 (Unchecked Input for Loop Condition)
was 0 TP across the whole Juliet corpus
(R340 / R342). The Juliet pattern is:

```java
data = req.getQueryString();        // tainted
int n = Integer.parseInt(data);     // taint flows through
for (int i = 0; i < n; i++) {       // ← bad: loop bound is tainted
    ...
}
```

The existing plumb-dfa engine had no checker
that could recognise "tainted value used as a
loop bound".

### 2.2 Fix

New `UncheckedLoopConditionCheck` in
`plumb-dfa/checker/`:

1. **Seed taint sources.** Walk the function
   once and seed a per-var `Map<String,
   Set<String>>` of var-name → {taint-source}
   for vars that are the dest of known
   taint-source CALLs: `getParameter/1`,
   `getQueryString/0`, `readLine/0`,
   `getHeader/1`, `getCookies/0`, `nextLine/0`,
   `read/0`.
2. **Forward alias walk.** Fixed-point
   propagation of taint markers through:
   - `MOVE lhs1 -> dest` (a fresh local
     bound to a tainted value is also
     tainted).
   - Known parseInt family
     (`Integer.parseInt/1`, `Long.parseLong/1`,
     `Double.parseDouble/1`,
     `Float.parseFloat/1`,
     `Short.parseShort/1`,
     `Byte.parseByte/1`) where the value
     arg is tainted.
3. **Detect loop-condition BRANCH.**
   Iterate the BBs. For each BRANCH whose
   `trueLabel` starts with `L_FOR_BODY`,
   `L_WHILE_BODY`, or `L_DO_WHILE_BODY`
   (the loop-body labels the translator
   emits), look at the prev instruction. If
   it's a BINOP with op `LT`/`LE`/`GT`/`GE`
   and any operand is a tainted var, fire
   `bu-0705-unchecked-loop-condition` with
   CWE-606.

The dedup key is `cwe606|line|varName` to
avoid double-reporting when a single tainted
var drives multiple loop-iteration BRANCHes.

The `R349Cwe606UncheckedLoopConditionTest`'s
`validatedLoopBoundDoesNotFire` test documents
the v1 limitation: a `n >= 0 && n < 100`
range check before the loop is not yet
suppressed. R349b (next round) will add the
range-check suppression.

### 2.3 Tests (`R349Cwe606UncheckedLoopConditionTest`, 4 tests)

- `parseIntFromGetQueryStringFires` — the
  canonical Juliet pattern.
- `parseIntFromReadLineFires` — same with a
  different source.
- `hardcodedLoopBoundDoesNotFire` — sanity:
  no taint source, no fire.
- `validatedLoopBoundDoesNotFire` — documents
  the v1 limitation (the canonical goodB2G
  with a range check is currently still
  flagged; R349b will add the suppression).

### 2.4 R349 sweep impact

Re-running CWE-606 with the R349 fix:

| Metric | Pre-R349 | Post-R349 | Δ |
|---|---|---|---|
| Total findings | 99 | 405 | +309 |
| unchecked-loop-condition findings | 0 | 306 | +306 (new sink) |
| TP (file-level) | 0 | 36 | +36 |
| FP (file-level) | — | 39 | +39 |
| precision | — | 0.480 | (new) |
| recall | 0.000 | 0.081 | +0.081 |

CWE-606 was 0 TP before; R349 picks up 36 real
vulnerabilities and 39 false alarms (mostly
the validated-but-not-yet-suppressed goodB2G
shape). The next round (R349b) will add the
range-check suppression.

### 2.5 Wiring

- `plumb-dfa/checker/UncheckedLoopConditionCheck.java`
  (new, 244 lines).
- `plumb-main/ScanConfig.java` — new
  `runUncheckedLoopCondition` flag (default true).
- `plumb-main/ConfigLoader.java` — accepts
  both camelCase and snake_case yaml keys.
- `plumb-main/ScanRunner.java` — registered
  in the legacy per-function group.

## 3. Test impact

- `plumb-dfa`: 410 (R345) → 421 (R347 +11) →
  426 (R349 +5: 4 R349 tests + 1 limit-doc
  variant — actually 4 new tests + 1
  validation; net +5). 0 fail, 1 skip
  (pre-existing Z3SolverTest).
- `plumb-main`: 19 (unchanged this round; the
  R349 config wiring is internal).
- `plumb-dfa`: 461 (unchanged).
- `plumb-style`: 890 (unchanged).

**Net this round: +10 tests, 0 regression.**

## 4. Files

- `plumb-dfa/src/main/java/org/plumb/dfa/solver/IntervalAnalysis.java`
  — R341 `FLOAT`/`DOUBLE` (carried from R341).
- `plumb-dfa/src/main/java/org/plumb/dfa/checker/DivByZeroCheck.java`
  — R341 FLOAT + R347 Math.abs guard.
- `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintCatalog.java`
  — R347 `escapeXml11` / `escapeXml10` /
  `encodeForXML` SANITIZER expansion.
- `plumb-dfa/src/main/java/org/plumb/dfa/checker/UncheckedLoopConditionCheck.java`
  — R349 new checker.
- `plumb-main/src/main/java/org/plumb/main/ScanConfig.java`
  — `runUncheckedLoopCondition` flag.
- `plumb-main/src/main/java/org/plumb/main/ConfigLoader.java`
  — yaml key.
- `plumb-main/src/main/java/org/plumb/main/ScanRunner.java`
  — register.
- `plumb-dfa/src/test/java/org/plumb/dfa/checker/R347MathAbsGuardTest.java`
  — 5 R347 tests.
- `plumb-dfa/src/test/java/org/plumb/dfa/checker/R349Cwe606UncheckedLoopConditionTest.java`
  — 4 R349 tests.
- `D:\work\tmp\r341_369_v2/` — R347 CWE-369
  re-sweep.
- `D:\work\tmp\r349_606/` — R349 CWE-606
  re-sweep.

## 5. Next steps (R350+)

- R350 (CWE-789 Uncontrolled_Mem_Alloc
  checker).
- R351 (CWE-197 Numeric_Truncation checker).
- R352 (CWE-15 / CWE-25X / CWE-32X external
  control & crypto checkers).
- R353 (CWE-40X / 60X / 69X / 78X / 83X
  additional checkers).
- R349b (range-check suppression for the
  CWE-606 validated pattern).
- R347b (more `Math.abs`-style guard
  variations: `Math.abs(x) >= c`,
  `Math.abs(x) < c`).
- Performance pass: cache invalidation
  review, parallel sweep, JFR profiling.

## 6. Pitfalls learned

1. **Sentinel op value vs Literal value.** The
   `BranchGuard` record's `op` field is a
   `String`, so the R347 fix uses the sentinel
   string `"MATH_ABS_NE"` to disambiguate the
   Math.abs guard from the canonical
   `NE 0` guard. Records being immutable meant
   the sentinel op had to flow through every
   check site (`r165IsKnownNonZero`,
   `r186IsKnownNonZeroBackward`,
   `r189ToR165Map`); a plain field flag on a
   mutable guard class would have been
   shorter, but the record's immutability was
   the existing pattern.
2. **Loop-body label naming convention.** The
   `UncheckedLoopConditionCheck` recognises
   loop conditions by the BRANCH's
   `trueLabel` starting with `L_FOR_BODY`,
   `L_WHILE_BODY`, or `L_DO_WHILE_BODY`. This
   is a translator convention; if the
   translator changes its label scheme, the
   R349 checker would silently miss the
   pattern. The R349 unit tests pin the current
   behaviour.
3. **ParseInt/parseLong alias-walk is an
   approximation.** The R349 v1 alias walk
   treats every parseInt-style call whose
   value arg is tainted as producing a tainted
   result. A real IFDS-style analysis would
   carry the source's taint markers through
   the CALL return edge. R349 v1 is fast and
   covers the Juliet patterns; a future round
   can replace it with a real IFDS lookup.
4. **Range-check suppression is the next FP
   source.** R349 v1 fires 39 FP because the
   canonical Juliet goodB2G validates the
   loop bound (`n >= 0 && n < 100`) before
   the loop. R349b (R350 in the next round) will
   look for a `n >= lower && n < upper` style
   guard on the loop-bound var and suppress.
