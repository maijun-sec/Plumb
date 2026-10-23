# R225+ — extend emitSummaryOps to DivByZero / ArrayIndex (2026-08-01)

R225 ship 后, 立即 polish round — 把 R225 的 `emitSummaryOps` hook 推广到
DivByZero / ArrayIndex checker. IntOverflow 已用 (R225), 但 DivByZero 跟
ArrayIndex 也有 GuardTracker 没利用.

## 根因

R225 给 IntOverflowChecker 加了 `emitSummaryOps` 让 `if (data < MAX)` guard
跨 function 传播. 但 DivByZero / ArrayIndex 也有 GuardTracker, 跟踪
`if (data != 0)` 跟 `if (data >= 0)` 模式. R225 没推广, callee 的 guard
在跨 function 边界丢失. 同样问题: a callee that returns a guarded value
should propagate the guard to the caller.

修法: DivByZeroChecker override `emitSummaryOps` emit `NE` guards
(`data NE 0`). ArrayIndexChecker override `emitSummaryOps` emit `GE/GT`
guards (`data GE 0`). 跟 IntOverflowChecker 完全平行.

## 实施 (~3KB code change + ~9KB test)

- `plumb-dfa/.../taint/checker/DivByZeroChecker.java` (modified,
  +~30 lines): 加 `emitSummaryOps` override. Filter `op == NE`, dedup by
  description. Use symbolic `g.op()` (e.g. "NE") not "!= " — 跟其他
  checker 保持 predicate grammar 一致
- `plumb-dfa/.../taint/checker/ArrayIndexChecker.java`
  (modified, +~30 lines): 加 `emitSummaryOps` override. Filter `op == GE/GT`,
  dedup by description
- `plumb-dfa/.../test/.../taint/R225PlusEmitGuardsTest.java` (new,
  9119 bytes, 4 tests): 直接 drive `checkInstruction` + `summarizeFunction`,
  验证 returnOps 包含预期 predicate

## 测试结果 (R225+)

| Test | Status |
|------|--------|
| `divByZero_emitsNonZeroGuard` | PASS |
| `arrayIndex_emitsNonNegativeGuard` | PASS |
| `intOverflow_stillEmitsLtGuard` | PASS (regression check) |
| `divByZero_doesNotEmitUnrelatedGuard` | PASS (filter test) |
| **plumb-dfa total** | 205 → **209** (+4) |

## 4 创新点 (R225+ design)

1. **直接 drive checkInstruction + summarizeFunction** (R225+ test design).
   R225 之前用 DfaEngine.run, 但 internal summary cache 不暴露. R225+ 测试
   显式 build AnalysisContext + 调 checkInstruction + 调 summarizeFunction,
   可以直接读 returnOps. 比 R225 测试方案更白盒
2. **Symbolic op string 保持一致** (R225+ design). IntOverflow emit "data
   LT 100", ArrayIndex emit "data GE 0", DivByZero emit "data NE 0". 全部
   用 BinaryOperator.toString() 的 symbolic form. R225 之前的 DivByZero
   用 "data != 0" 不一致, 修了
3. **每个 checker filter 自己关心的 op** (R225+ design). IntOverflow 看
   LT/LE/GT/GE (bounding ops), DivByZero 只看 NE (non-zero), ArrayIndex 只看
   GE/GT (non-negative). 一个 guard 在不同 checker 上下文里意义不同, 错 emit
   会让 caller 的 bridge 误判. 严格 filter
4. **Symbolic vs alphabetic naming 一致性 pitfall fixed** (R225+ pitfall).
   初版 emit "data != 0" (用 != string) vs "data < 100" (用 op toString).
   现在统一用 op toString. 后人加新 checker 别再用 != / >= 这种 symbolic

## 备份

`D:\work\tmp\r225plus_done/` (3 files, ~23KB):
- ArrayIndexChecker.java (R225+ 加 emitSummaryOps)
- DivByZeroChecker.java (R225+ 加 emitSummaryOps)
- R225PlusEmitGuardsTest.java (new, 4 tests)

## Cumulative (post-R225+)

- **84 rounds, 1 rolled back**
- **18 metric wins + 2 validations + 1 report**
- **58+ infra** (R148e..R225 + **R225+**)
- **1329+ tests / 0 fail / 0 err / 2 skip** (R225+ 4 new)
- **47 R-doc 累计 ~415K bytes**
