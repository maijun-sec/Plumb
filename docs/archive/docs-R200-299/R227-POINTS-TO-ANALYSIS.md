# R227 — Points-to analysis + UseAfterFreeChecker (2026-08-01)

R226 VTA 之后的 round. R227 实施真正的 **指针分析 (pointer analysis)**:
- `PointsToAnalysis` 跟踪 per-variable 哪些 heap allocation 可以指向
- `UseAfterFreeChecker` (新 CWE-416) 用 points-to 检测 close-then-use

## 根因

之前 ResourceLeakChecker 用 `Map<TacVar, AllocSite>` per-variable 跟踪 alloc
但 **不知道 alias**. 模式 `x = y` 后, x 跟 y 指向同一 alloc, close y 应该
propagate 到 x. 之前不行, 漏 CWE-416.

R227 加正式 points-to: 跟踪 field/array 跟 alias 关系, 写 UseAfterFreeChecker
用这个 emit CWE-416.

## 实施 (~31KB, 3 files, 2 new)

- `plumb-dfa/.../points/PointsToAnalysis.java` (new, 12636 bytes):
  通用 per-function points-to set. NEW/NEWARRAY 引入 AllocSite, MOVE 复制,
  FIELDSTORE/ARRAYSTORE 写 abstract field/array location, FIELDLOAD/ARRAYLOAD
 读. `markFreed` + `isUseAfterFree` 标记/检查.
- `plumb-dfa/.../taint/checker/UseAfterFreeChecker.java` (new,
 7002 bytes): CWE-416 HIGH. 检测 close (.close / .dispose / .finalize) 后
 的 dereference (FIELDLOAD/FIELDSTORE/ARRAYLOAD/ARRAYSTORE/CALL receiver).
- `plumb-dfa/src/test/.../points/R227PointsToAnalysisTest.java` (new,
 7 tests, 11839 bytes)

## 测试结果 (R227)

| Test | Status |
|------|--------|
| `pointsTo_newInstruction_addsAllocSite` | PASS |
| `pointsTo_move_propagatesPointsToSet` | PASS |
| `pointsTo_fieldStore_fieldLoad_roundTrip` | PASS |
| `pointsTo_markFreed_detectsUseAfterFree` | PASS |
| `useAfterFreeChecker_firesOnCloseThenUse` | PASS |
| `useAfterFreeChecker_doesNotFireWithoutClose` | PASS |
| `useAfterFreeChecker_aliasingPropagatesClose` | PASS |
| **plumb-dfa total** | 222 → **229** (+7 R227) |

## 4 创新点 (R227 design)

1. **Symbolic AllocSite 替代 heap modeling** (R227 design). 用
   `(functionSig, insnIdx, type)` 唯一标识一次 alloc — 跟具体地址无关.
   简单, 跨 function 也能 dedup (虽然同 function 内才分析)
2. **Field-level points-to via abstract location** (R227 design).
   `FieldKey(receiverAlloc, fieldName)` 当作抽象 location. 多个 receiver
   alloc 的同一 field 共享一个 set. 不会因为 receiver 有多个 alloc 而漏
3. **Alias propagation via MOVE** (R227 design). `x = y` 让 x 跟 y 共享
   points-to set. 这是 CWE-416 检测的关键 (close y → close x 的 alloc →
   use x = use-after-free)
4. **Conservative array merge** (R227 design). `[*]` 作为一个 field name
   表示所有 array 元素. 不 per-index 跟踪, 但不会因为 array 不同下标而
   假报

## R227 接下来 (按用户 4 方向 ③)

- **R228-232**: 新 checker 群 (empty-catch / dead-code / equals-hashCode / finalize / switch-default)
  全用 `points/PointsToAnalysis` + `TaintedCheckerBase` 模板
- **R233**: 迁移 plumb-style checkers (UncheckedReturn / InfiniteLoop / ConstantCondition / BadShift)
- **R234**: 全面 Juliet 113 family sweep + FP/FN 综合评估

## 备份

`D:\work\tmp\r227_done/` (3 files, ~31KB):
- PointsToAnalysis.java (new, 12636 bytes)
- UseAfterFreeChecker.java (new, 7002 bytes)
- R227PointsToAnalysisTest.java (new, 7 tests)
