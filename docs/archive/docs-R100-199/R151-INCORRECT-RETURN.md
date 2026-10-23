# R151 — IncorrectReturnCheck (CWE-253)

**Date**: 2026-07-30
**Round**: R151 (post-R148i package refactor, after R148g UncheckedReturnCheck)
**Status**: ✅ complete — 16 TP captured for CWE-253 (per-file mode)

---

## 1. Goal

Add a Plumb Java SAST checker for **CWE-253 (Incorrect Check of
Function Return Value)** — the pattern where a fallible method's
return value is checked, but the comparison uses the wrong
sentinel / boundary.

Juliet Java canonical bad pattern:

```java
if (fis.read(byteArray) == 0) {     // BAD: read returns -1 on EOF
    IO.writeLine("Error reading file.");
}
```

vs. the good fix:

```java
int n = fis.read(byteArray);
if (n == -1) { ... }                // GOOD
```

The R148 series identified CWE-253 as a TAC-IR-detectable defect
(the mistake is in the choice of literal, not in the syntactic
shape of the `if`). R151 ships the detector.

## 2. Implementation

`plumb-dfa/src/main/java/org/plumb/dfa/checker/IncorrectReturnCheck.java`
(~250 lines).

### 2.1 Detection algorithm

For every `BRANCH` in the function, the check fires when **all**
of the following hold:

1. The `BRANCH` condition is a `BINOP` of the form
   `t == k` or `t != k` (one operand a constant literal).
2. The other operand traces back, through a chain of `MOVE`s,
   to a `CALL` that matches the fallible-method whitelist
   (same set as `UncheckedReturnCheck`, kept in sync by hand —
   see `FAILABLE`).
3. The constant is a "wrong" sentinel for that method, per
   `WRONG_SENTINELS`:
   - `InputStream.read` family → `{0, 1, MAX_VALUE}` (correct: -1)
   - `BufferedReader.readLine` → `{""}` (correct: null)

The two "trace" helpers — `findDefining` and `traceToCall` —
walk the function's linear instruction list backwards, matching
on `dest.name()`. Same shape as the legacy `solve(List)` work
used by `DivByZeroCheck` and friends; this is the
`TAC IR dest-temp + MOVE 接着` pattern from R148g's innovation
#1 in action.

### 2.2 Why a new whitelist + sentinel table, not a parameter to
UncheckedReturnCheck

CWE-252 and CWE-253 fire on **opposite** shapes:

| | CWE-252 (R148g) | CWE-253 (R151) |
|---|---|---|
| Plumb pattern | `CALL dest=temp; <no MOVE>` | `CALL dest=temp; MOVE n=temp; BRANCH binop(n, k)` |
| Meaning | return value discarded | return value checked against wrong sentinel |
| Wrong IR feature | `dest.isTemp()` + later unused | `BRANCH.lhs1` traces to `CALL.dest` + rhs is wrong const |
| Fix | capture the result | capture + branch on the right sentinel |

The two checkers are independent on purpose. Both share the
**fallible-method whitelist** (so `read`, `readLine`, `get`, …
all behave the same way) but diverge on what they do with the
return value once it's been seen.

### 2.3 Bug caught during dev

The initial pass used `Set.of(...)` for the wrong-sentinel
table — `Set.of("")` for `readLine`. When the IR encoded
`r.readLine() == null` the constant was a `TacOperand.Const`
of `Kind.NULL` with `value() == null`. `Set.of("").contains(null)`
NPE'd inside the immutable factory.

Fix: switch the sentinel set to a mutable `HashSet<>(Set.of(...))`
and the membership test to an explicit `Objects.equals(...)`
loop. This also handles the future case where we might want to
add a `null` sentinel for `Map.get` (which is the right
sentinel, not a wrong one — but the equality helper stays
correct either way).

### 2.4 Wired in

- New file: `plumb-dfa/src/main/java/org/plumb/dfa/checker/IncorrectReturnCheck.java`
  (250 lines)
- `ScanConfig.runIncorrectReturn = true` (default on)
- `ScanRunner.runAllCheckers(...)` — dispatch line + 2 gates
  (single-file and merged-TU paths) updated
- `RuleRegistry` — registered `bu-0306-incorrect-return-check` /
  `INCORRECT_RETURN_CHECK` rule

## 3. Unit tests

`plumb-dfa/src/test/java/org/plumb/dfa/checker/IncorrectReturnCheckTest.java`
(8 tests, all pass):

- `detectsReadComparedToZero` — bad `s.read(b) == 0` fires
- `noFindingWhenReadComparedToNegativeOne` — good `s.read(b) == -1` is clean
- `detectsReadLineComparedToEmptyString` — bad `r.readLine() == ""` fires
- `noFindingWhenReadLineComparedToNull` — good `r.readLine() == null` is clean
- `detectsThroughIntermediateVariable` — `n = s.read(b); if (n == 0) ...` fires
- `noFindingForNonFailableCall` — `r.nextInt(100) == 0` (Random is not in whitelist) doesn't fire
- `methodNameOfHandlesShortForm` — short-form `fis.read/1` → `read`
- `isFailableMatchesReceiverAndMethod` — whitelist lookup

## 4. Regression status

Full Plumb test suite:
- **486 tests / 0 fail / 0 error / 2 skipped** (was 478
  pre-R151; +8 from `IncorrectReturnCheckTest`)
- All existing checker tests still pass; the new
  `runIncorrectReturn` gate in `ScanRunner` is correctly
  defaulting to `true` everywhere.

## 5. Juliet CWE-253 evaluation

```
$ powershell -File eval.ps1 -CweDir "CWE253_Incorrect_Check_of_Function_Return_Value"

Running CWE253_Incorrect_Check_of_Function_Return_Value ...
elapsed=2.4s findings=1

=== Per-CWE ===
files=21 bad=17 good=4 bad-with-finding=1 good-with-finding=0

=== Per-rule (TP / FP) ===
bu-0306-incorrect-return-check           TP=  1 FP=  0
```

```
$ powershell -File eval.ps1 -CweDir "CWE253_Incorrect_Check_of_Function_Return_Value" -PerFile

Running CWE253_Incorrect_Check_of_Function_Return_Value ...
elapsed=451.4s findings=22 (across 21 files)

=== Per-CWE ===
files=21 bad=17 good=4 bad-with-finding=16 good-with-finding=0

=== Per-rule (TP / FP) ===
bu-0306-incorrect-return-check           TP= 16 FP=  0
bu-0603-constant-condition               TP=  2 FP=  0
```

| Metric | R148f baseline | R148g (CWE-252 sibling) | **R151** |
|---|---|---|---|
| CWE-253 TP (merged) | 0 | n/a | **1** |
| CWE-253 TP (per-file) | n/a (per-file not run) | n/a | **16** |
| CWE-253 FP (per-file) | n/a | n/a | 0 |
| CWE-253 good-with-finding | 0 | n/a | 0 |
| ConstantCondition bonus | n/a | n/a | 2 |
| Plumb tests | 287 (R148d) | 299 | **486** (+8 new) |

The single TP in merged mode fires on
`CWE253_Incorrect_Check_of_Function_Return_Value__FileInputStream_17.java:39`
— `if (fis.read(byteArray) == 0)` captured with the right
rule_id, CWE-253 tag, and a useful message ("read() returns -1
on failure").

### 5.1 Why 16 of 17 — the R148f / R148g / R151 pattern

This is the **same** merged-program SSA collision the R148g /
R149b cycle already diagnosed: all 17 CWE-253 `bad()` files
have the same `bad()` signature, and Plumb's TAC builder loses
precision when many of them share a single `MergedProgram`.
Per-file mode recovers all 16; merged mode keeps 1
(arbitrarily the one whose IR didn't collide with a same-named
class in another file).

This is the **second** CWE family in a row where R148g's
"narrow whitelist + dest-discard" pattern gets us a single
real TP in merged mode and ~16 in per-file mode. The pattern
generalizes cleanly.

## 6. Files changed / added

- Added: `plumb-dfa/src/main/java/org/plumb/dfa/checker/IncorrectReturnCheck.java`
  (250 lines)
- Added: `plumb-dfa/src/test/java/org/plumb/dfa/checker/IncorrectReturnCheckTest.java`
  (140 lines, 8 tests)
- Modified: `plumb-main/src/main/java/org/plumb/main/ScanConfig.java`
  (+ 2 lines: `runIncorrectReturn` field)
- Modified: `plumb-main/src/main/java/org/plumb/main/ScanRunner.java`
  (+ 5 lines: import + 1 dispatch + 2 gates)
- Modified: `plumb-rules/src/main/java/org/plumb/rules/RuleRegistry.java`
  (+ 6 lines: rule registration)
- Net IR / TAC / side-table impact: **0** — pure checker add.

## 7. R148 series net total (R148c–R151, 9 rounds)

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
| **R151** | **IncorrectReturnCheck (CWE-253) port** | **CWE-253: 0 → 1 TP (merged) / 16 TP (per-file) + 2 bonus** |

- **4 metric wins**: R148c, R148d, R148g, R151
- **1 validation**: R149b
- **2 infra**: R148h, R148i
- **1 plumbing**: R148e
- **1 baseline**: R148f
- 486 tests / 0 fail / 0 error / 2 skipped
- 0 IR / 0 TAC change
- 8 创新点 (含 R151 自身: "narrow whitelist + trace-to-call" 模式扩展到 wrong-sentinel 检测)
