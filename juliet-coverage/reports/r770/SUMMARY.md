# R770 — do-while + multi-syscall IR investigation

**Status**: Investigation only. **0 engine code changes**. Documented 3 CWE-835 FN as R771+ candidate.

**Date**: 2026-10-09
**Scope**: User's original list item "do-while + multi-syscall IR". Surveyed current TAC IR + DfaEngine support, evaluated CWE-835 status, no new engine code.

---

## 1. Background

User's R684 original to-do list included:
> "Future: do-while + multi-syscall IR adaptation"

This was queued for a future round. R770 picks it up as an investigation-only round to **survey the actual state** of do-while handling and multi-syscall analysis, and identify what's left vs what's already shipped.

---

## 2. TAC IR survey

`TacInstruction.Kind` enum in `plumb-tac/src/main/java/org/plumb/tac/ir/TacInstruction.java`:
- `MOVE`, `LOAD`, `STORE`, `BINOP`, `UNARYOP`, `CALL`, `INVOKE`, `RETURN`, `THROW`, `NOP`, **`BRANCH`**, **`JUMP`**, **`LABEL`**

**There is no DOWHILE / FOR / WHILE specific Kind.** All loops in the TAC IR are encoded as `BRANCH` + `JUMP` back-edge patterns. R759-E's `InfiniteLoopChecker` already handles `while (true) {`, `for (;;) {`, and `do {` (with `DO_WHILE_TRUE_TAIL` filter for `} while (true);`). The framework is **already loop-aware via the BRANCH/JUMP encoding**.

No new TAC IR kind is needed for do-while. The "do-while IR adaptation" in the user's list is **already done** by R759-E.

---

## 3. CWE-835 current status (do-while family)

CWE-835 has 6 Juliet test files in `D:\work\tmp\Juliet_Java\src\testcases\CWE835_Infinite_Loop\`:

| File | Pattern | R770 status | R759-E status |
|------|---------|-------------|---------------|
| `CWE835_Infinite_Loop__do_true_01.java` | `do { ... } while (true);` | ✅ **TP** (1.0 precision) | R759-E fix |
| `CWE835_Infinite_Loop__for_empty_01.java` | `for (;;) { ... }` | ✅ **TP** | R759-E fix |
| `CWE835_Infinite_Loop__while_true_01.java` | `while (true) { ... }` | ✅ **TP** | R759-E fix |
| `CWE835_Infinite_Loop__do_01.java` | `int i = 0; do { i = (i+1) % 256; } while (i >= 0);` | ❌ **FN** | R759-F candidate |
| `CWE835_Infinite_Loop__for_01.java` | `for (i = 0; i >= 0; i = (i+1) % 256) { ... }` | ❌ **FN** | R759-F candidate |
| `CWE835_Infinite_Loop__while_01.java` | `int i = 0; while (i >= 0) { i = (i+1) % 256; }` | ❌ **FN** | R759-F candidate |

The 3 FNs share a pattern: **the loop induction variable `i` is provably bounded by the Z3 range lattice** (here, `i ∈ [0, 255]` after the `% 256` operation), so the loop's exit condition `i >= 0` is always true, making it infinite. Detecting this requires **Z3 range proof on the loop induction variable + recognition of the always-true exit condition**.

This is **exactly** the 3-FN issue R759-E documented as deferred to R759-F:
> "CWE-835 remaining 3 FN (variable-bounded `do_01` / `for_01` / `while_01`) need value-range proof — out of regex scope."

### R770 E2E numbers

- **CWE-835 scan**: 1,044 findings, dominated by style rules + `plumb.resource.leak` (6) + `CatchGenericException` (10) + `plumb.dfa.cwe835-infinite-loop` (**3 TP, 0 FP, 1.0 precision**)
- **Rule↔CWE map augmentation**: added `plumb.dfa.cwe835-infinite-loop -> 835` to `plumb_rule_to_cwe.json` so the eval pipeline counts it
- **4 CWE eval 0 regression** vs R768/R769 (CWE-129/190/191/369 numbers unchanged)

---

## 4. Multi-syscall IR survey

**Current state**: Plumb's DFA engine is general-purpose — it doesn't have syscall-specific IR. The CALL instruction carries a `calleeSig` (e.g. `java.net.Socket#connect(SocketAddress):void`) and checkers use `InputSourcePatterns.isInputSourceCall(...)` to flag source calls. There's no "syscall family" abstraction.

**What's already shipped**:
- CWE-252/253 (R763): `IncorrectReturnComparisonCheck` + `UncheckedReturnChecker` handle `read()` / `readLine()` / `parseInt()` / `parseLong()` return-discard and return-comparison-against-0 patterns
- CWE-789 (memory allocation): `UncontrolledMemoryAllocationChecker` exists (in loose checkers before R760, may be in the deprecation chain)
- CWE-784 / 770 / 762 (C syscall families): not directly addressed by R759-R769

**The user's "multi-syscall IR" likely refers to** patterns where a Juliet test case chains multiple socket/file I/O calls across method boundaries (e.g. `bind`, `listen`, `accept`, `connect`, `read`/`write`) and the DFA needs to follow the data flow across all of them.

**Status**: The framework's RPO-based data flow already follows CALL chains. The `ClassThreadChecker.InfiniteLoopChecker.fix` from R759-E included a `readAllFiles(ctx)` call that handles cross-TU source reading, which is the "multi-syscall" infrastructure.

**What's missing**: a CWE-specific multi-syscall pattern detector (e.g. for the CWE-393/CWE-400/CWE-674 sequence-of-calls families). These are out of scope for R770 — they would be R772+ candidates requiring CWE-specific encoding.

---

## 5. Decisions

- **0 engine code changes for R770**: The TAC IR loop encoding (BRANCH+JUMP) is already correct. R759-E fixed 3 of 6 CWE-835 patterns. The remaining 3 FNs (value-bounded) require Z3 range proof on the loop induction variable, which is **R771+ candidate scope**.
- **Multi-syscall IR not pursued**: Plumb's DFA engine already follows CALL chains via RPO. CWE-specific multi-syscall families (CWE-393 / CWE-400 / CWE-674) are not in the R-Round scope.
- **CWE835.yaml added**: needed to whitelist `plumb.dfa.cwe835-infinite-loop` since R760+ yaml-default mode doesn't enable it. R770 measurement shows it works at **1.0 precision** on its 3 fire-able Juliet variants.
- **`plumb_rule_to_cwe.json` augmented**: `plumb.dfa.cwe835-infinite-loop -> 835` was missing; added so `eval_findings_cwe_matched.py` counts the 3 TP correctly.

---

## 6. R771+ candidate scope

The CWE-835 3-FN fix (value-bounded loop detection) requires:

1. **Loop induction variable identification**: detect `for (i = X; cond; update)` and `while (cond)` / `do {} while (cond)` patterns where the var is provably bounded
2. **Z3 range proof on the induction variable**: prove the var's range after `update` doesn't satisfy `!cond` ⇒ loop is infinite
3. **Integration with `BoundaryConstraintChecker`**: the same Range lattice used for CWE-190/191/369 can answer "is the var in a range that satisfies `cond`?" — the same R769 3-blocker chain (Range lattice, conservative-SAT, `*` regex) applies

The natural order:
- **R771a**: extend `atomicRefutes` to handle `*` arithmetic (R769 blocker 3) — unlocks 1 CWE-190 TP gain AND unblocks value-bounded CWE-835 work
- **R771b**: value-bounded loop detection in `InfiniteLoopChecker` using Z3 range lattice
- **R772**: cross-syscall pattern detection (if a concrete CWE emerges)

---

## 7. Verification

### E2E: CWE-835 re-scan + eval

| Rule | TP | FP | Total | Precision |
|------|---:|---:|------:|----------:|
| `plumb.dfa.cwe835-infinite-loop` | 3 | 0 | 3 | **1.000** |

4 CWE eval (R768/R769 baseline): 0 regression, 35,716 findings unchanged.

### Files touched

- `D:\tmp\plumb_test\configs_v2_r760\CWE835.yaml` (new, 0.8 KB)
- `D:\tmp\plumb_test\plumb_rule_to_cwe.json` (added `plumb.dfa.cwe835-infinite-loop -> 835`)
- `D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\plumb_rule_to_cwe.json` (synced)

### No engine code

- 0 `plumb-dfa` / `plumb-main` / `plumb-tac` source files modified
- 0 new unit tests
- 0 new ScanConfig / yaml schema changes

---

## Lessons learned

- **R770 L1109 ("Future" 列表里的 items 应该 investigate-first, code-second)**: User 的 "do-while + multi-syscall IR" 在 R684 列的时候是模糊的。R770 调查发现 do-while 已经被 R759-E 处理, multi-syscall 走 RPO call chain 已经支持。**经验**: 对长 backlog item, 先写 1 轮 investigation-only 调查现状, 再决定 code change scope。
- **R770 L1110 (CWE-835 1.0 precision 3/3 是 rule-id map 不存在导致的隐形未跑)**: `plumb.dfa.cwe835-infinite-loop` rule 注册在 R759-E, 但 `plumb_rule_to_cwe.json` 没 map 它到 835, 加上 R760 yaml mode whitelist 默认不 enable, 所以 **rule 实际上 0 跑**. 加 yaml + 加 map 之后立刻 3 TP. **经验**: rule 注册了不代表跑起来, 必须 verify yaml + map 链路 (跟 R765 L1096 同型)。
- **R770 L1111 (3-FN value-bounded loop 是真 Z3 range 推理题)**: `i = (i+1) % 256` 这个 range narrowing 加 `i >= 0` 的 condition 推出 infinite, 是个完整 Z3 induction proof. 跟 R769 嵌套 guard refutation 是 **同型问题** (Range lattice + atomic refutation). **经验**: 多个 "CWE 漏检" 实际是同一类 Z3 range 推理能力缺位的不同表现. 修一个 blocker 多个 CWE 同时收益 (R770a = R769 blocker 3 = CWE-190 + CWE-835 + 任何 `*` 算术 refutation)。

---

## Files

### New
- `D:\work\workspace\idea\engine\Plumb\juliet-coverage\reports\r770\SUMMARY.md` (this file)
- `D:\tmp\plumb_test\configs_v2_r760\CWE835.yaml` (0.8 KB)
- `D:\tmp\plumb_test\scan\baseline\CWE835.ndjson` (12 KB, 1,044 findings)
- `D:\tmp\plumb_test\scan\whitelist_v2\CWE835.ndjson` (eval copy)

### Modified
- `D:\tmp\plumb_test\plumb_rule_to_cwe.json` (added `plumb.dfa.cwe835-infinite-loop -> 835`)
- `D:\work\workspace\idea\engine\Plumb\juliet-coverage\data\plumb_rule_to_cwe.json` (synced)

### No engine source changes
- 0 files in `plumb-tac`, `plumb-dfa`, `plumb-main`, `plumb-style`, `plumb-reporter` modified
- 0 unit tests added
- 0 new ScanConfig flags

### Logs
- `D:\tmp\plumb_test\logs\r770_scan_cwe835_v3.log` (E2E scan, 1,044 findings, 0.0s after java startup)
- `D:\tmp\plumb_test\logs\r770_eval.log` (4 CWE eval, 0 regression vs R768/R769)
