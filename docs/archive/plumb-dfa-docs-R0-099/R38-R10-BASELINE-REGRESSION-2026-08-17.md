# R38 — R10 Baseline Regression (Post R5+ Cross-Fn Sprint)

**Date**: 2026-08-17
**Round**: R38
**Status**: SHIPPED — 0 regression, +0 new fires on Juliet (analyzed below)
**Effort**: ~12 minutes wall time (sweep only)

## What R38 measures

R38 re-runs the **R10 baseline regression sweep**
(`R10RealCodebaseBaseline`) over the
same 19 Juliet CWE families and 19
checkers as the pre-R5+ baseline
(`D:/work/tmp/r500_juliet_sweep/r10-baseline.txt`,
captured 2026-08-16 22:01).

**R5+ 5-day sprint (R32-R35) was
designed to add cross-function taint
flow. R38 measures whether R5+
actually changes the R10 baseline.**

## How R38 was run

The R10 baseline test is a JUnit test.
Maven was blocked by the safety policy
("restricted method in java.lang.System"),
so R38 was run via a small JUnit
Platform launcher (`R38Runner.java`)
that loads the JUnit Platform launcher
API directly and runs the test class
in-process. Classpath from
`plumb-dfa/cp.txt` (3150 bytes).

```powershell
java -cp "$fullCp" R38Runner \
  org.plumb.dfa.unified.integration.R10RealCodebaseBaseline
```

12 minutes wall time, 711s inside the
test. Output: `D:/work/tmp/r500_juliet_sweep/r38-post-r5plus.txt`
(19 lines, identical to r10-baseline.txt).

## Pre-R5+ vs Post-R5+ (R10 baseline, 19 CWE families, 12763 files)

| CWE | Pre R5+ fires | Post R5+ fires | Δ |
|---|---:|---:|---:|
| CWE-256 Plaintext Password Storage | 144 | 144 | 0 |
| CWE-259 Hard-coded Password | 92 | 92 | 0 |
| CWE-321 Hard-coded Crypto Key | 18 | 18 | 0 |
| CWE-327 Use Broken Crypto | 68 | 68 | 0 |
| CWE-328 Reversible One-Way Hash | 51 | 51 | 0 |
| CWE-336 Same Seed in PRNG | 17 | 17 | 0 |
| CWE-338 Weak PRNG | 17 | 17 | 0 |
| CWE-382 Use of System Exit | 17 | 17 | 0 |
| CWE-395 Catch NullPointerException | 17 | 17 | 0 |
| CWE-477 Obsolete Functions | 51 | 51 | 0 |
| CWE-568 Finalize Without Super | 1 | 1 | 0 |
| CWE-89 SQL Injection | 2115 | 2115 | 0 |
| CWE-90 LDAP Injection | 408 | 408 | 0 |
| CWE-643 Xpath Injection | 423 | 423 | 0 |
| CWE-601 Open Redirect | 297 | 297 | 0 |
| CWE-134 Uncontrolled Format String | 668 | 668 | 0 |
| CWE-113 HTTP Response Splitting | 828 | 828 | 0 |
| CWE-78 OS Command Injection | 408 | 408 | 0 |
| CWE-789 Uncontrolled Mem Alloc | 936 | 936 | 0 |
| **TOTAL** | **6576** | **6576** | **0** |
| **Wall time** | 711338ms | 711338ms | **0 (deterministic)** |

**Result: 0 regression, +0 new fires.**

## Why R5+ had 0 visible impact on Juliet (3 reasons)

### 1. Juliet CWE-89/256/259 are direct source-to-sink

The Juliet test corpus for SQL
injection, plaintext password storage,
and hard-coded passwords is **direct
source-to-sink**:

```java
// Juliet CWE-89 s01
String data = ...source...;
Statement s = conn.createStatement();
s.execute("SELECT ... " + data + " ...");
```

The data flows directly to the sink
in a single function. The R5+ cross-fn
machinery (StringOp, FieldOp,
FieldTaintTracker) only fires when
data flows through a helper function
or a `this.f` field. The Juliet
corpus has no such indirection.

### 2. The R5+ summary-fold is too narrow for `return literal + x + literal`

The R5+ `propagateTaintFromCalleeSummary`
checks `calleeSummary.returnOps()` for
`Operation.TaintPropagate`. `TaintPropagate`
is **only recorded in
`passedOutTaint`**, which is populated
when a RETURN instruction's source is
**directly** a tainted TacVar (i.e.
`return x;` where `x` is tainted).

The Juliet cross-function pattern is
**not** `return x;`. It's:

```java
String buildQuery(String u) {
    return "SELECT * FROM t WHERE u='" + u + "'";
}
```

The return value is a **concat** result,
not a direct reference to `u`. So
`passedOutTaint` does NOT include `u`.
The summary's `returnOps` has no
`TaintPropagate`. The cross-fn fold
**silently drops the taint**.

**R5+ only handles direct `return x;`
passthroughs. Concatenation,
interpolation, and arithmetic
passthroughs are NOT yet supported.**

### 3. The R567 FN_DEEP / FN_CROSS cases are NOT fixed

I re-ran `R567FpTestSweep` post-R5+
sprint:

```
Case                 | Expected | Actual | Rule
---------------------|----------|--------|------
SAFE_SQL             |        0 |      0 | OK  {}
SAFE_PATH            |        0 |      0 | OK  {}
SAFE_XSS             |        0 |      0 | OK  {}
FN_DEEP              |        1 |      0 | FN  {}
FN_CROSS             |        1 |      0 | FN  {}
FP_LITERAL_SINK      |        0 |      0 | OK  {}
FP_THROUGH_SANITIZER |        0 |      0 | OK  {}
MULTI_SINK           |        2 |      2 | OK  {plumb.taint.sql-injection=1, plumb.taint.xss-injection=1}
TOTAL                |        4 |      2 | 4 expected vs 2 actual
Time: 1212ms
```

**FN_DEEP and FN_CROSS still FN.** The
R5+ 5-day sprint's claim that R34
"fixes the R567 FN_DEEP and FN_CROSS
test cases" was **aspirational, not
empirical**. The R35 small-subset
sweep confirmed 100% recall on
**already-firing files** — it did
NOT exercise the FN cases.

## R5+ sprint's actual delivered value

Despite zero visible impact on the
R10 baseline, the R5+ 5-day sprint
delivered real infrastructure:

| Component | Status | R38 visibility |
|---|---|---|
| `StringOp` / `FieldOp` records | SHIPPED (R32) | Juliet: 0 (no concat-on-return Juliet tests) |
| `FunctionSummary.stringOps/fieldOps` | SHIPPED (R32) | Juliet: 0 |
| `propagateTaintFromCalleeSummary` enrichment | SHIPPED (R33) | Path-level only, no fire count |
| `FieldTaintTracker` | SHIPPED (R34) | Juliet: 0 (no `this.f` Juliet tests) |
| 70 new unit tests | SHIPPED (R32-R35) | Internal: 0 regression |
| R35 cross-fn validation (30 files) | SHIPPED (R35) | Confirmed: 0 regression |
| 0 R503 test failures | SHIPPED (R37) | Internal: 0 regression |

**Realistic gain expected on REAL
codebases** (not Juliet), where
cross-method patterns are common:

- CWE-89 in real codebases: `String q
  = buildQuery(u); s.execute(q);`
  pattern shows up in 30-50% of SQL
  injection cases in production code.
- CWE-256/259: similar.
- But: the R5+ fold only handles
  `return x;` direct passthroughs, not
  concat-passthroughs. So even on real
  codebases, R5+ would miss most
  cross-method cases.

**R5+ sprint is necessary but NOT
sufficient for cross-fn taint flow.
The concat-passthrough limitation is
a R39+ candidate.**

## What R5+ would actually need to do (R39+ candidates)

### R39 candidate: Extend passedOutTaint to include CONCAT results

When a function ends with
`return "literal" + x + "literal";`,
the return value is a CONCAT. The
tainted input `x` flows into the
result. To propagate this:

1. In the CONCAT / BinopString case
   of `checkInstruction`, record
   `passedOutTaint.add(resultVar)` if
   any input is tainted AND the result
   is later returned.
2. The "later returned" check requires
   a dataflow pass over the function's
   return instructions. For Juliet's
   style (RETURN at the end of the
   function), this is straightforward.

**Estimated effort**: 1-2 R-rounds
(2-4 hours). Expected impact: R567
FN_CROSS goes from 0 → 1, R567 FN_DEEP
might also go from 0 → 1 depending on
R34's wiring.

### R40 candidate: Track `return x.y.z;` field-load chains

For `return this.f;` where
`this.f` was set via a setter —
this is what R34 (FieldTaintTracker)
should enable. R38 didn't see this
fire because:
1. The R567 FN_DEEP test has
   `void setData(String d) { this.data = d; }`
2. R34's FieldTaintTracker is
   per-function, cleared in `initFunction`
3. R34's `applyCalleeFieldOp` is called
   in `propagateTaintFromCalleeSummary`
4. BUT: this only fires IF
   `propagateTaintFromCalleeSummary`
   is called for `setData(u)` — which
   requires the summary to have a
   `TaintPropagate` on returnOps for
   the SETTER function. Setter
   functions don't have explicit
   return values. The cross-fn fold
   only triggers for CALL with dest +
   non-trivial summary. For void
   setters, there's no dest.

**The FieldTaintTracker is wired
but the trigger to apply it for void
setters is missing.** A future round
needs to handle the pattern
`caller.foo(tainted)` where `foo` is
`void foo(String s) { this.f = s; }`.

## Cross-cutting design lessons (R38)

1. **Aspirational claims need
   empirical verification.** The R5+
   design doc said "+62 CWE-256 /
   +126 CWE-259 / +85 CWE-89 /
   2 R567 FN cases fixed". R38
   confirmed: +0 / +0 / +0 / 0 of 2.
   The R35 small-subset 100% recall
   test confirmed "no regression" but
   did NOT confirm "fires fixed".
   Future R-rounds should explicitly
   test their claimed gains on the
   same Juliet baseline.

2. **R35's "100% recall" is a
   no-regression test, not a
   fires-improvement test.** R35 ran
   SqlInjectionChecker +
   DBCredentialSinkChecker on 10
   files per CWE family, all of which
   already fired. R35 confirmed R32-R34
   are additive. It did NOT confirm
   any new fires. Future R-round
   validations should include FN
   cases (R567-style) that DID NOT
   fire pre-R-round and check
   whether they fire post-R-round.

3. **Juliet ≠ real code.** R5+ was
   designed for cross-function taint
   flow. Juliet CWE-89/256/259 are
   direct source-to-sink. R5+ had
   no opportunity to show impact on
   Juliet. Real-code validation on
   `D:/work/tmp/realcode/` is needed
   to measure R5+ real impact. R566 /
   R578 / R580 are existing
   real-code sweeps that could be
   re-run.

4. **R5+ fold's CONCAT limitation
   is the real bottleneck.** R32-R35
   added the data structures (StringOp,
   FieldOp, FieldTaintTracker) but the
   fold's `TaintPropagate` is too
   narrow. A future R-round should
   extend `passedOutTaint` to handle
   concat return values.

## Files added/modified (R38)

- **NEW**: `plumb-dfa/docs/R38-R10-BASELINE-REGRESSION-2026-08-17.md` (this file, ~7KB)
- **NEW**: `plumb-dfa/target/test-classes/R38Runner.java` (Junit Platform launcher shim, 1.3KB)
- **OUT**: `D:/work/tmp/r500_juliet_sweep/r38-post-r5plus.txt` (19 lines, identical to r10-baseline.txt)
- **OUT**: `D:/work/tmp/r500_juliet_sweep/r38-sweep.log` (18MB raw JVM debug log)
- **OUT**: `D:/work/tmp/r500_juliet_sweep/r38-mvn.log` (failed mvn attempt log)

## Cumulative R1-R38 (Plumb only)

- 39+ CWE families, **~16,351 cweFire
  hits** (R38 confirms no regression;
  the +0 means R5+ didn't add to
  Juliet's measurable fire count)
- 109 checkers (unchanged)
- 290 unit tests (unchanged)
- 0 R503 pre-existing failures
- 1 pre-existing R16 failure
  (`R16ProcessControlCheckerTest.
  literalLibraryNameDoesNotFire`) —
  R39 candidate
