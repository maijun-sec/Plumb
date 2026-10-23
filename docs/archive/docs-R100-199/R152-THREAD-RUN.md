# R152 — ThreadRunCheck (CWE-572)

**Date**: 2026-07-30
**Round**: R152 (post-R151, R148i package refactor)
**Status**: ✅ complete — 17 TP / 0 FP captured for CWE-572 (per-file mode, 17/17 perfect coverage)

---

## 1. Goal

Add a Plumb Java SAST checker for **CWE-572 (Call to Thread run
Instead of start)** — the pattern where the developer wants to
launch work on a new thread but invokes `Thread#run` directly,
which executes synchronously in the current thread instead of
dispatching to a new one.

Juliet canonical bad pattern:

```java
Thread t = new Thread() {
    public void run() { ... }
};
t.run();   // BAD: runs synchronously, no new thread spawned
```

vs. the good fix:

```java
t.start(); // GOOD: spawns a new thread that calls run()
```

## 2. Implementation

`plumb-dfa/src/main/java/org/plumb/dfa/checker/ThreadRunCheck.java`
(~120 lines, smaller than R148g/R151 because the pattern is a
direct method-name × receiver-type match — no MOVE trace, no
sentinel lookup, no BRANCH-inspection).

### 2.1 Detection algorithm

For every `CALL` in the function, the check fires when **all**
of the following hold:

1. The callee signature's method name is exactly `run` (arity
   0).
2. The first argument is a `TacVar` whose declared type is
   `Thread` (after short-name resolution).

A `Runnable.run()` (where the receiver is a `Runnable`) does
**not** fire — `Runnable.run` is the documented entry point
for a runnable, and calling it directly is the standard
"execute a runnable synchronously" idiom. Same for any custom
class with a `run` method that doesn't extend `Thread`.

The check does not try to model the call-site semantics
(whether the caller meant to spawn a new thread, or just wanted
to run something synchronously) — that's an intent question
that static analysis can't answer. The narrow whitelist keeps
the false-positive rate at zero.

### 2.2 Bug caught during dev

`RuleDef.Type.CONCURRENCY` doesn't exist — the enum is
`TAINT, STYLE, NAMING, PERFORMANCE, CORRECTNESS, RESOURCE,
SECURITY, OTHER`. Used `CORRECTNESS` instead. One-line fix.

## 3. Unit tests

`plumb-dfa/src/test/java/org/plumb/dfa/checker/ThreadRunCheckTest.java`
(5 tests, all pass):

- `detectsRunOnThread` — `t.run()` on a `Thread` instance fires
- `noFindingForStart` — `t.start()` is clean
- `noFindingForRunnableRun` — `r.run()` where `r` is a
  `Runnable` is clean (it's the entry point, not a bug)
- `noFindingForCustomClassRun` — `h.run()` where `h` is a
  custom `Helper` class is clean (no `Thread` receiver)
- `methodNameOfHandlesShortForm` — short-form `t.run/0` → `run`

## 4. Regression status

Full Plumb test suite:
- **491 tests / 0 fail / 0 error / 2 skipped** (was 486
  pre-R152; +5 from `ThreadRunCheckTest`)
- All existing checker tests still pass.

## 5. Juliet CWE-572 evaluation

```
$ powershell -File eval.ps1 -CweDir "CWE572_Call_to_Thread_run_Instead_of_start"

Running CWE572_Call_to_Thread_run_Instead_of_start ...
elapsed=2.4s findings=1

=== Per-CWE ===
files=21 bad=17 good=4 bad-with-finding=1 good-with-finding=0

=== Per-rule (TP / FP) ===
bu-0307-thread-run-instead-of-start      TP=  1 FP=  0
```

```
$ powershell -File eval.ps1 -CweDir "CWE572_Call_to_Thread_run_Instead_of_start" -PerFile

Running CWE572_Call_to_Thread_run_Instead_of_start ...
elapsed=429.2s findings=22 (across 21 files)

=== Per-CWE ===
files=21 bad=17 good=4 bad-with-finding=17 good-with-finding=0

=== Per-rule (TP / FP) ===
bu-0307-thread-run-instead-of-start      TP= 17 FP=  0
bu-0603-constant-condition               TP=  2 FP=  0
```

| Metric | R148f baseline | R148g (CWE-252) | R151 (CWE-253) | **R152 (CWE-572)** |
|---|---|---|---|---|
| Per-file TP | 0 | 16 | 16 | **17** |
| Per-file FP | 0 | 0 | 0 | 0 |
| Per-file good-with-finding | 0 | 0 | 0 | 0 |
| Per-file coverage | 0/17 | 16/17 | 16/17 | **17/17** |
| Merged TP | 0 | 1 | 1 | 1 |
| Merged FP | 0 | 0 | 0 | 0 |
| Plumb tests added | — | 9 | 8 | 5 |

R152 is the **first** narrow-whitelist R-round to hit
**17/17 perfect coverage** in per-file mode. The
`CWE572_Call_to_Thread_run_Instead_of_start` family is
structurally simpler than CWE-252 / CWE-253 (the bad pattern
is just `t.run()` on a `Thread` instance, with no per-flow
variant in the `bad()` method — the bad line is always
`threadOne.run()` and the good line is always
`threadTwo.start()`), so the merged-program SSA collision
that bit R148g / R151 doesn't apply here.

The merged-mode TP fires on
`CWE572_Call_to_Thread_run_Instead_of_start__basic_17.java`
— same per-file-mode pattern that consistently fires
across R148g / R151 / R152 (the "17" file is the only one
whose `Thread` class doesn't collide with a same-named class
in another file in the merged program).

## 6. Files changed / added

- Added: `plumb-dfa/src/main/java/org/plumb/dfa/checker/ThreadRunCheck.java`
  (120 lines)
- Added: `plumb-dfa/src/test/java/org/plumb/dfa/checker/ThreadRunCheckTest.java`
  (100 lines, 5 tests)
- Modified: `plumb-main/src/main/java/org/plumb/main/ScanConfig.java`
  (+ 2 lines: `runThreadRun` field)
- Modified: `plumb-main/src/main/java/org/plumb/main/ScanRunner.java`
  (+ 5 lines: import + 1 dispatch + 2 gates)
- Modified: `plumb-rules/src/main/java/org/plumb/rules/RuleRegistry.java`
  (+ 6 lines: rule registration)
- Net IR / TAC / side-table impact: **0** — pure checker add.

## 7. R148 series net total (R148c–R152, 10 rounds)

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
| **R152** | **ThreadRunCheck (CWE-572) port** | **CWE-572: 0 → 1 TP (merged) / 17 TP (per-file) + 2 bonus (17/17 perfect)** |

- **5 metric wins**: R148c, R148d, R148g, R151, R152
- **1 validation**: R149b
- **2 infra**: R148h, R148i
- **1 plumbing**: R148e
- **1 baseline**: R148f
- 491 tests / 0 fail / 0 error / 2 skipped
- 0 IR / 0 TAC change

## 8. R148 series 创新点累计 (9 条)

1. Plumb TAC IR dest-temp 总是 + MOVE 接着模式 (R148g)
2. Forward-dataflow "terminator-only block seed" bug (R148h)
3. per-file vs merged mode 验证方法学 (R149b)
4. Narrow whitelist checker 模式 (R148g)
5. BB-level IntervalAnalysis infra 0 cost migration (R148h)
6. "Single-pass solver 不是 CWE-369 根因" 反发现 (R148h)
7. 包结构子包约定 (R148i)
8. trace-to-call 模式 (R151 对偶 R148g)
9. **method-name-only narrow whitelist (R152)** — R148g/R151 都需要 trace context, R152 是 pure method×type match. 模式: 看到具体 method name (`run`) + 具体 receiver type (`Thread`) 就 fire. 比 R148g 更窄, 适用更有限的 pattern (CWE-572 是单 method single family).

## 9. Per-family Juliet coverage summary (post-R152)

| Family | Bad files | Merged TP | Per-file TP | Per-file FP | Coverage |
|---|---|---|---|---|---|
| CWE-252 (Unchecked Return) | 17 | 1 | 16 | 0 | 16/17 |
| CWE-253 (Incorrect Check) | 17 | 1 | 16 | 0 | 16/17 |
| CWE-572 (Thread.run) | 17 | 1 | **17** | 0 | **17/17** |
| **Total** | 51 | 3 | 49 | 0 | 49/51 (96%) |

**3 narrow-whitelist R-rounds** (R148g, R151, R152) recovered
**49 of 51** vulnerable test cases (96%) in per-file mode.
Merged-mode is 3/51 (5.9%) — the rest is the R148f-known
merged-program SSA collision (1 file per family escapes).
