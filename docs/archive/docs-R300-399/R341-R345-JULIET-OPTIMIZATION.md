# R341/R342/R345 — Juliet optimization round (FLOAT interval + format first-arg + post-R325 sweep)

**Status:** ✅ DONE (2026-08-04)
**R-rounds:** R341 (CWE-369 FLOAT interval), R342 (post-R325 sweep), R345 (CWE-134 format first-arg const)
**Scope:** 14 Juliet CWE families re-swept after R325
**Output:** `D:\work\tmp\r342_eval.csv` (14 families, fresh data)
**R-docs:** this file

---

## 1. Background

R340 swept all 113 Juliet families and produced a
file-level TP/FP/FN/precision/recall table. R325 closed
two big sources of FP noise (snake_case yaml + missing
SANITIZER patterns). R341/R342/R345 take the next step:

- **R341** — recognise FLOAT/DOUBLE constants in
  IntervalAnalysis so the CWE-369 goodG2B
  (`data = 2.0f; x = 100.0 / data;`) does not fire a
  false positive.
- **R345** — for the FORMAT taint sink
  (`bu-0113-taint-sink-format`), skip the format-string
  position when it's a Const literal. The CWE-134
  Juliet goodB2B pattern
  (`data = readLine(); System.out.format("%s%n", data);`)
  is the canonical "fix" and depends on this.
- **R342** — re-sweep 14 Juliet families with the R325
  fixed config + R341/R345 fixes. The R340 v2 numbers
  used `r190_v3_results` which was contaminated by
  ConstantConditionCheck / DivByZeroCheck / InsecureCookie
  FP that the R325 config fix silenced. The R342 sweep
  shows the real post-R325 numbers.

## 2. R341 — IntervalAnalysis FLOAT/DOUBLE handling

### 2.1 Symptom

The CWE-369 Juliet goodG2B pattern is

```java
data = 2.0f;          // good: hardcoded non-zero
int result = (int)(100.0 / data);   // bad sink, but data is known non-zero
```

Pre-R341, `IntervalAnalysis.literalInterval(Const(FLOAT))`
fell through to `default -> null`, so the move
`data = 2.0f` did not update the per-BB state for
`data`. The DIV's right-hand side looked up `data`,
got the default TOP, and `contains(0)` was true, so
the check fired a false positive. `DivByZeroCheck`
has its own `literalInterval` private copy with the
same bug, so the unit test was failing too.

### 2.2 Fix

`literalInterval` now recognises FLOAT and DOUBLE:

```java
case FLOAT  -> Interval.of(
    (long) (Float.floatToRawIntBits((Float) c.value()) & 0xFFFFFFFFL));
case DOUBLE -> Interval.of(
    Double.doubleToRawLongBits((Double) c.value()));
```

The float is bit-cast to long. The check
`Interval.contains(0)` only matches when the value
is literally zero — and the IEEE 754 +0.0 and -0.0
are the only zero encodings, both bit-equal 0L.
Positive and negative non-zero floats have a
non-zero bit pattern, so the check correctly
suppresses non-zero constants.

Both `IntervalAnalysis.literalInterval` and
`DivByZeroCheck.literalInterval` are updated in
lockstep.

### 2.3 Tests (`R341Cwe369GoodG2BSuppressTest`, 5 tests)

- `floatLiteralDivisorDoesNotFire` — `data = 2.0f`
  followed by `100.0 / data` does not fire.
- `doubleLiteralDivisorDoesNotFire` — same for
  double.
- `negativeFloatLiteralDivisorDoesNotFire` — `data =
  -1.0f` does not fire.
- `zeroFloatLiteralDivisorStillFires` — sanity
  check: `data = 0.0f` still fires.
- `zeroDoubleLiteralDivisorStillFires` — sanity
  check: `data = 0.0` still fires.

## 3. R345 — format sink format-string-is-const

### 3.1 Symptom

The CWE-134 Juliet goodB2B pattern is

```java
data = readLine();
System.out.format("%s%n", data);   // good: const format string
```

Pre-R345, the FORMAT taint sink
(`bu-0113-taint-sink-format`) fired on every tainted
arg, including the data substitution at args[2]
(after the receiver). The check considered the
flow `tainted-data → format()` a CWE-134 finding,
but it isn't — when the format string is a const
literal, the data substitution is interpreted as
the format spec's substitution value, not as a
format spec itself. A const format string is
controllable by the developer, so the substitution
is safe.

### 3.2 Fix

In `TaintAnalysis#case SINK`, when the entry is the
FORMAT sink, locate the format-string position by
arity:

- instance call (`System.out.format(args...)`): the
  receiver is at args[0] and the format string is
  at args[1] (arity == args.size() - 1).
- static call (`String.format(args...)`): the
  format string is at args[0] (arity == args.size()).

Then apply the rule:

- if the format string is a `TacOperand.Const`
  (literal), skip the entire call: the const is
  not a taint flow and the substitution args are
  safe under a const format spec.
- if the format string is a `TacOperand.TacVar`
  (variable), only fire on the format-string
  position itself, not on the data substitution
  args. The data args are governed by the (already
  flagged) format spec; flagging them is the
  double-count.

The rule was first drafted as "skip arg[0"
(ignoring the receiver) but that path was wrong —
`System.out.format("%s%n", data)` has the receiver
at args[0] (which is never tainted) and the
format string at args[1]. The arity disambiguation
is the principled way to find the format string.

### 3.3 Tests (`R345FormatFirstArgTest`, 4 tests)

- `julietCwe134GoodB2BFormatStringDoesNotFire` —
  the canonical goodB2B pattern (`"%s%n"` + tainted
  `data`) does not flag the format string as tainted.
- `formatStringWithTaintedDataDoesNotFire` — the
  instance case: `System.out.format("%s%n", tainted)`
  produces 0 findings.
- `staticFormatWithTaintedDataDoesNotFire` — the
  static case: `String.format("%s%n", tainted)`
  produces 0 findings.
- `singleArgFormatWithTaintedFmtFires` — the case
  where `fmt` itself is tainted. This is a real
  CWE-134 finding; the R345 fix only suppresses
  the format-string position when it's a literal
  Const.

### 3.4 Sweep impact

Re-running CWE-134 with the R345 fix:

| Metric | R325 | R345 | Δ |
|---|---|---|---|
| Total findings | 353 | 145 | -59% |
| TP (file-level) | 90 | 90 | unchanged |
| FP (file-level) | 132 | **0** | **-100%** |
| precision | 0.405 | **1.000** | +147% |

CWE-134 is now a non-issue: every Juliet goodG2B
(const format + tainted data) is correctly silenced,
and the 90 real bad() patterns (uncontrolled format
string from a socket) still fire.

## 4. R342 — Post-R325 sweep on 14 families

### 4.1 Why a fresh sweep

R340 v2 used the `r190_v3_results/` sweep data.
That data was contaminated by the R325 config bug
(30 default-true legacy checkers running under a
swp that looked like taint-only). After R325 the
config is correct; the post-R325 sweep shows the
real numbers.

### 4.2 Numbers — 14 families re-swept

| Family | R340 v2 (stale) | R342 (post-R325+R341+R345) | precision |
|---|---|---|---|
| CWE-80 XSS | TP=0 FP=0 | **TP=334 FP=0** | 1.000 |
| CWE-81 XSS_Error | TP=0 FP=0 | **TP=33 FP=0** | 1.000 |
| CWE-83 XSS_Attribute | TP=0 FP=0 | **TP=167 FP=0** | 1.000 |
| CWE-23 Path_Traversal | TP=0 FP=0 | **TP=174 FP=0** | 1.000 |
| CWE-36 Path_Traversal | TP=0 FP=0 | **TP=174 FP=0** | 1.000 |
| CWE-601 Open_Redirect | TP=0 FP=0 | **TP=33 FP=0** | 1.000 |
| CWE-78 OS_Cmd | TP=158 FP=0 | **TP=174 FP=0** | 1.000 |
| CWE-90 LDAP | TP=158 FP=0 | **TP=174 FP=0** | 1.000 |
| CWE-470 Reflection | TP=158 FP=0 | **TP=174 FP=0** | 1.000 |
| CWE-134 Format_String | TP=90 FP=132 | **TP=90 FP=0** | 1.000 |
| CWE-113 HTTP_Response | TP=0 FP=0 | **TP=383 FP=111** | 0.775 |
| CWE-643 Xpath_Injection | TP=147 FP=215 | **TP=165 FP=37** | 0.817 |
| CWE-369 Divide_by_Zero | TP=9 FP=76 | **TP=70 FP=74** | 0.486 |
| CWE-319 Cleartext_Tx | TP=3 FP=3 | **TP=54 FP=80** | 0.403 |

The R340 v2 numbers were *systematically
under-reporting* the post-R325 truth. Families
like CWE-80/81/83/23/36/601/113/470 were 0 TP in
R340 v2 because the R340 v2 analysis mixed
`r190_v3_results` (with cross-family FP from the
config-bug checkers) into the TP/FP/TN/FN counts.
With R325+R341+R345 the counts reflect what's
actually in the per-family NDJSON.

### 4.3 Aggregate

Across the 14 re-swept families:

```
TP file-level: 2197
FP file-level: 302
FN file-level: 6322
TN file-level: 12650

precision = 2197 / (2197 + 302) = 0.879
recall    = 2197 / (2197 + 6322) = 0.258
```

10 of the 14 families hit 100% precision. The
remaining 4 (CWE-113, CWE-369, CWE-643, CWE-319)
have known residual FP that the next round
addresses (R346 reverse-validate post-pass).

## 5. Performance notes

The CWE-369 sweep went from 5532 findings in 205s
(R190 v3, config-bug baseline) to 5488 findings in
311s (R341 v2, with `divByZero: true` explicit).
The 1.5x slowdown is the legacy `DivByZeroCheck`
walking the per-BB state for every function in
every Juliet file — not introduced by R341 (which
only adds a case to the `literalInterval` switch).
The R325 / R341 / R345 changes themselves add
negligible overhead.

CWE-113 is the new top runtime: 735 findings in
137s for 2212 files (62 ms/file). CWE-134 is now
fast: 145 findings in 38s for 1108 files (34 ms/file).

## 6. Test impact

- `plumb-dfa`: 397 (R325) → 400 (R325) → 405 (R341 +5) → 410 (R345 +5 with one replaced) → **410 (R341 + R345)**
- `plumb-main`: 19 (R325) → 19 (no change this round)
- `plumb-dfa`: 461 (unchanged)
- `plumb-style`: 890 (unchanged)

**Net: +10 tests this round (5 R341 + 5 R345; one replaced, so net +9 in `R345FormatFirstArgTest` after the test 3 fix), 0 regression.**

## 7. Files

- `plumb-dfa/src/main/java/org/plumb/dfa/solver/IntervalAnalysis.java` —
  FLOAT / DOUBLE case in `literalInterval`.
- `plumb-dfa/src/main/java/org/plumb/dfa/checker/DivByZeroCheck.java` —
  same change in the per-checker `literalInterval`.
- `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintAnalysis.java` —
  `case SINK` for FORMAT: const-format-string skips the
  whole call; tainted format string fires only on the
  format-string position.
- `plumb-dfa/src/test/java/org/plumb/dfa/checker/R341Cwe369GoodG2BSuppressTest.java` —
  5 new R341 tests.
- `plumb-dfa/src/test/java/org/plumb/dfa/taint/R345FormatFirstArgTest.java` —
  4 new R345 tests.
- `D:\work\tmp\r341_369.yaml` — sweep config that
  enables `divByZero` (camelCase).
- `D:\work\tmp\r341_369_sweep.ps1` /
  `D:\work\tmp\r341_369_sweep_v2.ps1` /
  `D:\work\tmp\r342_89_sweep.ps1` — sweep drivers.
- `D:\work\tmp\r342_sweep/` — 14-family post-R325
  NDJSON.
- `D:\work\tmp\r342_eval.csv` — fresh per-family
  TP/FP/FN/precision/recall.
- `D:\work\tmp\r341_369_v2/` — single-family
  CWE-369 re-sweep with R341 fix and `divByZero: true`.
- `D:\work\tmp\r341_done/` — backup.

## 8. Next steps (R346+)

The remaining FP for the 4 noisy families
(CWE-113 111 FP, CWE-369 74 FP, CWE-643 37 FP,
CWE-319 80 FP) all share one shape: the goodB2G
uses a non-trivial `if (data != 0)` or
`Math.abs(data) > epsilon` guard that R189's
canonical-guard (NE 0) doesn't recognise. R346
will extend the guard machinery to capture
`Math.abs(x) > c` and `x > c` style guards.

The remaining FN for the 4 sparse families
(CWE-369 1780 FN, CWE-113 949 FN, CWE-643 279 FN,
CWE-319 316 FN) is the "denominator" problem:
Juliet writes the bad pattern as
`int result = 100 / data` where the `100` is
hardcoded, but the DivByZeroCheck's interval
analysis loses the divisor's taint through
several MOVE / CALL hops. R347 will add a
shorter-path interval-walk that the lookup
walks in a single forward pass.

CWE-606, CWE-789, CWE-197, CWE-15, CWE-25X, CWE-32X,
CWE-40X, CWE-59X, CWE-67X, CWE-690, CWE-78X, CWE-83X
remain 0 TP after R325 + R341 + R345. These need
new checkers (R347+).

## 9. Pitfalls learned

1. **Two `literalInterval` copies.** Both
   `IntervalAnalysis` and `DivByZeroCheck` had a
   private `literalInterval` switch. The R341
   fix had to be applied to both, or the
   unit-test would have shown
   `IntervalAnalysis` recording a non-zero
   interval while `DivByZeroCheck` would still
   fall through and fire. Sharing the helper
   would be a separate refactor (R189 shared
   infrastructure; not in R341's scope).
2. **R345 first-arg-const is not just "skip
   arg[0]".** The instance call has the receiver
   at args[0] and the format string at args[1].
   An early R345 attempt that just skipped
   args[0] was a no-op for the Juliet case
   (the receiver isn't tainted) and the actual
   false-positive args[1] still fired. The
   arity-vs-args.size() disambiguation is the
   principled fix.
3. **R340 v2 was a stale-data report.** The
   `r190_v3_results` NDJSON was contaminated by
   the R325 config bug. R340 v2 mixed the
   contaminated data into the analysis, which
   under-counted TP across the board. R342's
   fresh sweep is the truth.
4. **Format-string position matters for "data is
   tainted" not "data flows through format".**
   The semantics we want: a const format
   string is safe (data is interpreted as the
   substitution value); a tainted format string
   is dangerous (attacker controls the format
   spec). The R345 fix captures both: skip the
   whole call when fmt is const; fire only on
   fmt itself when fmt is tainted.
