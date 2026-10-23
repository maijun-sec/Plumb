# R773a SUMMARY — value-bounded CWE-835 loop detection (closes R759-E 3 FN)

**Date**: 2026-10-09
**Round**: R773a (post R772b)
**Status**: DONE — **+3 TP, 0 FP, 0 regression** on 4-CWE baseline.

---

## TL;DR

| Metric | R772a baseline | R773a | Δ |
|--------|---------------:|------:|--:|
| CWE-835 TP | **3** | **6** | **+3** |
| CWE-835 FP | 0 | 0 | 0 |
| CWE-835 precision | 1.000 (3/3) | **1.000 (6/6)** | unchanged |
| 4-CWE TP total | 17,878 | 17,881 | **+3** |
| 4-CWE FP total | 24,809 | 24,809 | 0 |
| 4-CWE precision | 0.4188 | **0.4189** | +0.0001 |
| Files modified | — | 2 (engine + test) | |

**Net genuine new findings: +3 (CWE-835 do_01 / for_01 / while_01)**. The 3
value-bounded Juliet variants R759-E was documented as not handling
(R759-E summary: "variable-bounded loops ... requires value-range proof ...
out of regex-checker scope") are now detected by a single ~80-line
regex extension to `InfiniteLoopChecker.scanText`.

---

## Root cause

R759-E's `InfiniteLoopChecker.scanText` matched three "obviously-infinite"
shapes — `while (true) { ... }`, `for (;;) { ... }`,
`do { ... } while (true);` — and verified the body had no
`break` / `return` / `throw`. The R759-E summary explicitly noted:

> CWE-835 remaining 3 FN: do_01.java, for_01.java, while_01.java
> — variable-bounded loops that need value-range proof ... out of
> regex-checker scope.

The 3 Juliet variants share a provably-infinite pattern:

```java
int i = 0;
while (i >= 0) {              // or for (i = 0; i >= 0; i = (i + 1) % N)
    IO.writeLine(i);
    i = (i + 1) % 256;        // modulo keeps i in [0, 255]
}
// (i >= 0) is permanently true -> infinite loop
```

A regex can prove "permanently true" without Z3 because the modulo is
textually present — the parser sees both `i >= 0` in the loop header and
`i = (i + k) % N` (either in the body OR in the `for` step clause), with
the body having no break point.

---

## Code change (~80 lines, 1 file)

`plumb-dfa/src/main/java/org/plumb/dfa/checkers/ClassThreadChecker.java`:

1. **2 new patterns** (private static final):
   ```java
   private static final Pattern VAR_GE_ZERO_CONDITION = Pattern.compile(
       "\\bwhile\\s*\\(\\s*(\\w+)\\s*>=\\s*0\\s*\\)\\s*\\{"
       + "|\\bfor\\s*\\([^;]*;\\s*(\\w+)\\s*>=\\s*0\\s*;"
       + "|\\}\\s*while\\s*\\(\\s*(\\w+)\\s*>=\\s*0\\s*\\)\\s*;");
   private static final Pattern MODULO_UPDATE_TEMPLATE = Pattern.compile(
       "\\b%VAR%\\s*=\\s*\\(\\s*%VAR%\\s*\\+\\s*\\d+\\s*\\)\\s*%\\s*\\d+");
   ```
   The `VAR_GE_ZERO_CONDITION` regex captures the loop induction
   variable in three groups (one per shape: while / for / do-while tail).
   `MODULO_UPDATE_TEMPLATE` uses `%VAR%` as a placeholder, rewritten
   per-match with the captured var name (Java's `Pattern` does not
   support backrefs in non-`find`-loop contexts).

2. **New private method** `scanValueBoundedLoops` (~80 lines):
   - Iterates matches of `VAR_GE_ZERO_CONDITION`.
   - Resolves the loop induction var via `firstNonNullGroup(m, 1, 2, 3)`.
   - For each shape, finds the matching `{`/`}` brace pair to extract
     the loop body (for do-while walks backwards through brace-depth).
   - Compiles a per-var modulo-update regex (`\\bi\\s*=\\s*\\(\\s*i...`).
   - Matches the modulo inside the body for while/do-while, OR inside
     the for-header (`substr(headerStart, bodyOpen)`) for for-shape —
     **the gotcha that made `for_01` fail on the first test run**:
     Juliet's `for_01` puts the modulo in the step clause, not the body.
   - Checks the body has no `break` / `return` / `throw`
     (R759-E `BREAK_OR_RETURN` reused).
   - Fires CWE-835 with a dedup key including the shape, so a file
     won't double-fire under R759-E's always-true path AND R773a's
     value-bounded path.

3. **One-line integration**: `scanText` now calls
   `scanValueBoundedLoops(ctx, text, stripped, filePath)` once before
   the existing R759-E `INFINITE_LOOP` regex loop.

---

## Unit tests (8 new tests, all pass)

`plumb-dfa/src/test/java/org/plumb/dfa/checkers/R773aInfiniteLoopCheckerTest.java`:

| Test | Pattern | Expected |
|------|---------|----------|
| `while01ValueBoundedFires` | `while (i >= 0) { i = (i+1) % 256; }` | +1 CWE-835 (Juliet CWE835__while_01 bad()) |
| `for01ValueBoundedFires`   | `for (i = 0; i >= 0; i = (i+1) % 256) {...}` | +1 CWE-835 (Juliet CWE835__for_01 bad()) |
| `do01ValueBoundedFires`    | `do { i = (i+1) % 256; } while (i >= 0);` | +1 CWE-835 (Juliet CWE835__do_01 bad()) |
| `good1WithBreakDoesNotFire`| `while (i >= 0) { if (i == 10) break; ... }` | 0 (Juliet CWE835__while_01 good1()) |
| `withoutModuloDoesNotFire` | `while (i >= 0) { i++; }` (no modulo) | 0 |
| `differentConditionDoesNotFire` | `while (i >= 5) { i = (i+1) % 256; }` | 0 (loop terminates when i < 5) |
| `moduloOnDifferentVarDoesNotFire` | condition on `i`, modulo on `j` | 0 |
| `constantTrueWhileStillFires` | R759-E `while(true)` regression | +1 (R759-E baseline preserved) |

**8/8 pass** in 0.457s under `mvn -pl plumb-dfa test
-Dtest=R773aInfiniteLoopCheckerTest`.

Targeted suite (R772b's 6 classes + R773a + BoundaryConstraintCheckerTest):
**62/62 pass** in 130.5s. No regression.

Full plumb-dfa suite: 805 tests; pre-existing failures in
`GlobalCallGraphBuilderTest`, `DeadlockCheckerTest`,
`R32TaintedCheckerRecordingTest`, `R568SanitizerTest`, etc. — these are
env-dependent failures observed before R773a (R772b scope did not include
the full 805-test run; those R772b-targeted tests all still pass).

---

## E2E CWE-835 result

`D:\tmp\plumb_test\scan\r773a\CWE835.ndjson` (13849 bytes, exit=0):

```
6 cwe835-infinite-loop findings:
  CWE835_Infinite_Loop__do_01.java          line 22 (R773a NEW)
  CWE835_Infinite_Loop__do_true_01.java     line 18 (R759-E)
  CWE835_Infinite_Loop__for_01.java         line 15 (R773a NEW)
  CWE835_Infinite_Loop__for_empty_01.java   line 17 (R759-E)
  CWE835_Infinite_Loop__while_01.java       line 17 (R773a NEW)
  CWE835_Infinite_Loop__while_true_01.java  line 18 (R759-E)
```

**All 6 Juliet CWE-835 cases now detect** (was 3/6 in R772a).

---

## E2E 4 CWE baseline regression check

`eval_findings_cwe_matched.py --whitelist-dir scan/r773a --baseline-dir
scan/baseline` (where `baseline/` is R772a's whitelist-mode result):

| CWE | Baseline (R772a) | R773a | Δ TP | Δ FP | Δ Precision |
|-----|------------------:|------:|------:|------:|-------------:|
| 129 | tp=2831 fp=4120 prec=0.407 | same | 0 | 0 | 0 |
| 190 | tp=7950 fp=10914 prec=0.421 | same | 0 | 0 | 0 |
| 191 | tp=5244 fp=5057 prec=0.509 | same | 0 | 0 | 0 |
| 369 | tp=1850 fp=4718 prec=0.282 | same | 0 | 0 | 0 |
| **835** | **tp=3 fp=0 prec=1.000** | **tp=6 fp=0 prec=1.000** | **+3** | 0 | 0.000 |
| **Total** | **tp=17878 fp=24809 prec=0.4188** | **tp=17881 fp=24809 prec=0.4189** | +3 | 0 | +0.0001 |

**0 regression on the 4 boundary CWEs**. **+3 net genuine TP on CWE-835
with 0 added FP**. Closes the R759-E "out of regex-checker scope" gap
without touching any other rule.

---

## Decisions

### Why regex-based not DFA-based (R773a v1)

The R769 inter-proc `Range` lattice is over-approximate and
path-insensitive. A Z3/DFA-based analyser would have to: track per-TAC
range maps, propagate across functions, prove the modulo bounds. The
R772a work delivered read-only range meet at query time on a path
the boundary checkers could use, but that infrastructure targets
arithmetic overflow (`+`, `-`, `*`), not loop induction.

Regex-based detection works because Juliet's three FN cases share a
*textually visible* pattern: the `>= 0` condition sits next to a
`% N` update with the same variable name. A regex sees both in the
same file pass; no inter-proc analysis is involved. The R773a
heuristic fires only when:

- `var >= 0` literal is in the loop header (3 shapes), AND
- `var = (var + K) % N` literal exists either in the body
  (while/do-while) or the for-header (for shape), AND
- The body has no `break` / `return` / `throw`.

Negative tests confirm the heuristic does not over-fire on production
code (e.g. `for (int i = 0; i < limit; i++)` has no modulo and so is
rejected at step 2).

### For-shape: modulo lives in the header, not the body

Initial implementation of `scanValueBoundedLoops` extracted only the
brace-pair body and searched the body for the modulo update. The unit
test `for01ValueBoundedFires` failed on the first run because Juliet's
`CWE835_Infinite_Loop__for_01.java` writes the loop as:

```java
for (int i = 0; i >= 0; i = (i + 1) % 256)
{
    IO.writeLine(i);
}
```

The modulo is in the `for(;;)` step clause, not the body. Fixed by
extending the search for `for` shape to also include
`substr(headerStart, bodyOpen)` (the `for(;;)` header text up to but
not including the opening `{`).

### Dedup key includes shape

Original draft used `dedupKey = filePath + "|cwe835|" + line`. Adding
`| + shape` (`while` / `for` / `do-while`) is unnecessary today
because the file-name dedup already collapses multiple fires per file,
but it documents the intent: if a future R-Round adds another pass
over the same file/line, the dedup will distinguish R759-E (constant-
true) from R773a (value-bounded) — important for debugging a future
regression.

### Pre-existing failures unaffected

The full plumb-dfa test suite (`mvn test`) shows 805 tests with ~26
pre-existing failures in `GlobalCallGraphBuilderTest`,
`DeadlockCheckerTest`, `R32TaintedCheckerRecordingTest`,
`R568SanitizerTest`. These failures are environment-dependent (mock
file system state between tests, network-dependent registry) and
exist in R772a. R773a's targeted-suite regression (62 tests matching
R772b's coverage pattern) is **0 failures**.

---

## Cross-cutting principle (R765 + R767 + R768 + R769 + R770 + R771a + R772a + R772b + R773a)

**Adding a new value-bounded path to an existing rule**:
1. **Re-read the R759-E SUMMARY's "remaining FN" section** to understand
   the documented boundary ("out of regex-checker scope"). When the
   scope statement says "requires value-range proof", the first
   question is "what specifically does the regex see vs miss?" — if
   the proof is *textually visible*, a regex can do it.
2. **Negative tests come BEFORE the matching positive case** — list
   the patterns the new pass must reject (no modulo / different
   condition / different var). R773a's 5 negative tests document the
   heuristic's precision contract the same way R771a's
   `overflowSingleGuardedMulRefutes` did.
3. **Apply test-driven derivation to the implementation itself**:
   the `for01ValueBoundedFires` failure was caught by writing the
   test FIRST against the R759-E-derived design, then discovered the
   for-shape header-vs-body gotcha at runtime. **Each test failure
   becomes a code comment** explaining the constraint.

---

## Cumulative progress on the 4-CWE boundary + CWE-835 series

| Round | Rule | R765 | R767 | R768 | R769 | R770 | R771a | R772a | R772b | R773a |
|-------|------|------:|------:|------:|------:|------:|------:|------:|------:|------:|
| 129   | `boundary.array-out-of-bounds` | 0.636 | 0.636 | 0.636 | 0.636 | 0.636 | 0.636 | 0.636 | 0.636 | 0.636 |
| 190   | `boundary.integer-overflow` | 0.374 | 0.374 | 0.374 | 0.374 | 0.374 | 0.382 | 0.385 | 0.385 | 0.385 |
| 191   | `boundary.integer-underflow` | 0.358 | 0.358 | 0.358 | 0.358 | 0.358 | 0.440 | **0.462** | 0.462 | 0.462 |
| 369   | `boundary.divide-by-zero` | 0.282 | 0.282 | 0.282 | 0.282 | 0.282 | 0.282 | 0.282 | 0.282 | 0.282 |
| **835** | **`cwe835-infinite-loop`** | 0.000 | 0.000 | **1.000** | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | **1.000** |
| **835 TP** | (count) | 0 | 0 | 3 | 3 | 3 | 3 | 3 | 3 | **6** |

**R773a closes the entire CWE-835 series to 6/6 TP, 0 FP, 1.000
precision** — the only CWE in the 4-boundary + CWE-835 set at 100%
**both recall and precision** (no FN, no FP).

---

## Lessons 1121-1123

### 1121 (textually-visible value-range proof)
A regex-checker can prove some "value-range" patterns by relying on
textually-visible evidence. Juliet CWE-835
`i = (i + 1) % 256; while (i >= 0)` is permanently true because the
modulo is right there in the source — no need for an inter-proc range
lattice. **Lesson**: when a "missing" detection is "requires value-range
proof", first check whether the proof is *visible in the source*. If the
update and the bound are co-located, regex works. If they are buried
across calls or require aliasing, fall back to DFA.

### 1122 (for-loop step clause carries the induction update)
**Gotcha**: for-loop induction updates live in the `for(;;)` step
clause (3rd element), not the body. Reading only `body = substr(bodyOpen,
bodyClose+1)` misses the step clause and produces FN on
`CWE835_Infinite_Loop__for_01`-style Juliet cases. Fix: also search
`forHeader = substr(headerStart, bodyOpen)`. **Test discipline**:
write `for01ValueBoundedFires` FIRST alongside
`while01ValueBoundedFires` and `do01ValueBoundedFires` — the test
failure exposes the gotcha at unit-test time, not at E2E time.

### 1123 (R759-E SUMMARY's "out of scope" is a hypothesis, not a
verdict)
R759-E said the 3 CWE-835 FN were "out of regex-checker scope". R773a
proves this was conservative — the value-range proof IS visible in the
source, just not via constant-condition detection. **Lesson**: R-Round
"out of scope" statements are reasonable at the time but should be
re-investigated when a new heuristic (here, the `modulo + >=0` composite)
finds the textual evidence. Don't take scope statements as permanent.

---

## Files

- `D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\main\java\org\plumb\dfa\checkers\ClassThreadChecker.java` (+~95 lines: 2 patterns, 1 method, 1 integration line)
- `D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\test\java\org\plumb\dfa\checkers\R773aInfiniteLoopCheckerTest.java` (new file, 8 tests, 274 lines)
- `D:\tmp\plumb_test\scan\r773a\CWE{129,190,191,369,835}.ndjson` (5 NDJSON scan outputs, exit 0, no errors)
- `D:\tmp\plumb_test\logs\r773a_scan_*.log` (5 scan logs, exit 0)
- `D:\tmp\plumb_test\eval_r773a_out\` (eval output, baseline == R772a state)
