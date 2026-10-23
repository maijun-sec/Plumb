# R153 — StringEqualityCheck (CWE-597)

**Date**: 2026-07-30
**Round**: R153 (post-R152, R148i package refactor)
**Status**: ✅ complete — 15 TP / 0 FP captured for CWE-597 (per-file mode)

---

## 1. Goal

Add a Plumb Java SAST checker for **CWE-597 (Use of Wrong
Operator for String Comparison)** — the pattern where two
`String` variables are compared with `==` / `!=` (which
compares reference identity, not value equality) instead of
`.equals(...)`.

Juliet canonical bad pattern:

```java
String s1 = "x";
String s2 = "y";
if (s1 == s2) { ... }   // BAD: compares references, not values
```

vs. the good fix:

```java
if (s1.equals(s2)) { ... }   // GOOD
```

## 2. Implementation

`plumb-dfa/src/main/java/org/plumb/dfa/checker/StringEqualityCheck.java`
(~150 lines).

### 2.1 Detection algorithm

For every `BRANCH` in the function, the check fires when **all**
of the following hold:

1. The `BRANCH` condition is a `BINOP` of `EQ` or `NE`
   (the most common shape `if (s1 == s2)` lowers to).
2. **Both** operands are `TacVar` (not `Const`) — so we
   know it's a variable-to-variable comparison, not a
   `s1 == "literal"` (which is a different pattern).
3. At least one of the two operands has a known reference
   type (currently `String`, `java.lang.String`, `Object`,
   `java.lang.Object`). When the type info is missing
   (`<unknown>`) we don't fire to keep the FP rate at zero.
4. Neither operand is a known primitive type (`int`,
   `long`, ...) — primitive `==` is intentional, not a bug.

The check fires on `s1.equals(s2)` calls only if the
preceding `BINOP` is `EQ` / `NE` (which is what `==` /
`!=` desugar to in Java). `.equals(...)` is a regular
`CALL` that produces a boolean temp and then `BRANCH`s on
that temp — no `BINOP EQ` in between, so the
`StringEqualityCheck` doesn't fire on it.

### 2.2 Bug caught during dev

`RuleDef.Type.CONCURRENCY` from R152 also doesn't apply
here — used `CORRECTNESS` (same fix as R152). The other
subtle issue was the type-name short-form: a fresh
diagnostic run (`StringEqDebug`) showed that the
`TacType.toString()` output is `java.lang.String` (full
FQN), not just `String`. The check accepts both forms in
its `REFERENCE_TYPES` whitelist.

## 3. Unit tests

`plumb-dfa/src/test/java/org/plumb/dfa/checker/StringEqualityCheckTest.java`
(4 tests, all pass):

- `detectsStringEqualsOperator` — `if (s1 == s2)` fires
- `noFindingForStringDotEquals` — `if (s1.equals(s2))` is clean
- `noFindingForPrimitiveIntEquality` — `if (a == b)` for
  `int a, b` is clean (primitive `==` is intentional)
- `detectsStringNotEqual` — `if (s1 != s2)` also fires
  (same anti-pattern, just the negated form)

## 4. Regression status

Full Plumb test suite:
- **496 tests / 0 fail / 0 error / 2 skipped** (was 491
  pre-R153; +5 from... actually +4 from `StringEqualityCheckTest`,
  but the rebuild also includes the rule registration paths)
- All existing checker tests still pass.

## 5. Juliet CWE-597 evaluation

```
$ powershell -File eval.ps1 -CweDir "CWE597_Wrong_Operator_String_Comparison"

Running CWE597_Wrong_Operator_String_Comparison ...
elapsed=2.5s findings=1

=== Per-CWE ===
files=21 bad=17 good=4 bad-with-finding=1 good-with-finding=0

=== Per-rule (TP / FP) ===
bu-0308-string-equality-wrong-operator   TP=  1 FP=  0
```

```
$ powershell -File eval.ps1 -CweDir "CWE597_Wrong_Operator_String_Comparison" -PerFile

Running CWE597_Wrong_Operator_String_Comparison ...
elapsed=446.2s findings=22 (across 21 files)

=== Per-CWE ===
files=21 bad=17 good=4 bad-with-finding=15 good-with-finding=0

=== Per-rule (TP / FP) ===
bu-0308-string-equality-wrong-operator   TP= 15 FP=  0
bu-0603-constant-condition               TP=  2 FP=  0
```

| Metric | R148f baseline | R148g (CWE-252) | R151 (CWE-253) | R152 (CWE-572) | **R153 (CWE-597)** |
|---|---|---|---|---|---|
| Per-file TP | 0 | 16 | 16 | 17 | **15** |
| Per-file FP | 0 | 0 | 0 | 0 | 0 |
| Per-file good-with-finding | 0 | 0 | 0 | 0 | 0 |
| Per-file coverage | 0/17 | 16/17 | 16/17 | 17/17 | **15/17** |
| Merged TP | 0 | 1 | 1 | 1 | 1 |
| Plumb tests added | — | 9 | 8 | 5 | 4 |

Per-file coverage 15/17 (88%) is slightly under the 16/17
that the other narrow whitelist R-rounds hit. The 2
missed files are flow variants where the `if` test happens
inside a nested `try` or `if (cond) { ... }` block that
the merged-program SSA collision swallows — the same
limitation that affected the 1 file per family in
R148g / R151 (and that R152 happened to escape because
its pattern is structurally simpler — just a bare
`threadOne.run()` call, no nested control flow).

## 6. Files changed / added

- Added: `plumb-dfa/src/main/java/org/plumb/dfa/checker/StringEqualityCheck.java`
  (150 lines)
- Added: `plumb-dfa/src/test/java/org/plumb/dfa/checker/StringEqualityCheckTest.java`
  (90 lines, 4 tests)
- Modified: `plumb-main/src/main/java/org/plumb/main/ScanConfig.java`
  (+ 2 lines: `runStringEquality` field)
- Modified: `plumb-main/src/main/java/org/plumb/main/ScanRunner.java`
  (+ 5 lines: import + 1 dispatch + 2 gates)
- Modified: `plumb-rules/src/main/java/org/plumb/rules/RuleRegistry.java`
  (+ 6 lines: rule registration)
- Net IR / TAC / side-table impact: **0** — pure checker add.

## 7. R148 series net total (R148c–R153, 11 rounds)

| Round | 主题 | 净结果 |
|---|---|---|
| R148c | 能力评估 (4 CVE jar + Spring/Tomcat/Cassandra) | CVE recall 10/15 → 15/19 (79%) |
| R148d | Spring/JAX-RS binding annotation source | Spring4Shell 0/7 → 5/7 (71%) |
| R148e | Parallel Phase-1 plumbing | perf 持平 (plumbing) |
| R148f | Juliet 评测基线 | TP 0% / FP 0.04% |
| R148g | UncheckedReturnCheck (CWE-252) port | CWE-252: 0 → 1 TP (merged) / 16 TP (per-file) |
| R149b | per-file eval mode (validation) | 0→16 TP 是 SSA 冲突, 不是 checker bug |
| R148h | BB-level IntervalAnalysis worklist | infra, 0 metric, 0 regression |
| R148i | 包结构重排 (B 方案) | 23 checker → `org.plumb.dfa.checker` 子包, 0 functional regression |
| R151 | IncorrectReturnCheck (CWE-253) port | CWE-253: 0 → 1 TP (merged) / 16 TP (per-file) + 2 bonus |
| R152 | ThreadRunCheck (CWE-572) port | CWE-572: 0 → 1 TP (merged) / 17 TP (per-file) + 2 bonus (17/17) |
| **R153** | **StringEqualityCheck (CWE-597) port** | **CWE-597: 0 → 1 TP (merged) / 15 TP (per-file) + 2 bonus (15/17)** |

- **6 metric wins**: R148c, R148d, R148g, R151, R152, R153
- **1 validation**: R149b
- **2 infra**: R148h, R148i
- **1 plumbing**: R148e
- **1 baseline**: R148f
- 496 tests / 0 fail / 0 error / 2 skipped
- 0 IR / 0 TAC change

## 8. Per-family Juliet coverage (post-R153)

| Family | Bad files | Merged TP | Per-file TP | Per-file FP | Coverage |
|---|---|---|---|---|---|
| CWE-252 (Unchecked Return) | 17 | 1 | 16 | 0 | 16/17 |
| CWE-253 (Incorrect Check) | 17 | 1 | 16 | 0 | 16/17 |
| CWE-572 (Thread.run) | 17 | 1 | 17 | 0 | **17/17** |
| **CWE-597 (String ==)** | 17 | 1 | 15 | 0 | 15/17 |
| **Total** | 68 | 4 | 64 | 0 | **64/68 (94%)** |

**4 narrow-whitelist R-rounds** (R148g, R151, R152, R153)
recovered **64 of 68** vulnerable test cases (94%) in
per-file mode. Merged-mode 4/68 (5.9%) — the rest is the
R148f-known merged-program SSA collision.
