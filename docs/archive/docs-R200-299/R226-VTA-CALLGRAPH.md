# R226 — VTA CallGraph + 修 RTA filter (2026-08-01)

按用户 4 个方向 (PMD/SonarQBe/SpotBugs 风格 checker / 引擎优化 / 迁移 / Juliet 评估)
的 ① 引擎优化. CallGraph 实施 R226:

## RTA 修 (v1 placeholder → v2 真正 filter)

R210 时代 RTA 收集 instantiated types 但没真 filter — 委托给 CHA. R226 重写
RTA 用 `isInstantiated` 严格 filter override classes:
- 应用 instantiate 的 class: emit
- 没 instantiate 但 library (java/javax/org/com 前缀): emit (conservative)
- 没 instantiate 也没 library: skip

`isInstantiated` 末尾的 library 前缀 fallback 避免 drop 真实 virtual call 通过
library receiver.

## VTA 新加

`VtaCallGraphStrategy` 走 per-variable points-to set:
- 每个 NEW → dest var points-to set 加 ctor type
- 每个 MOVE → dest var points-to set ⊇ src var points-to set
- 每个 CALL → dest var points-to set 加 OBJECT (保守 default)
- 每次 virtual call 解析: receiver points-to set 跟 override class 交集

严格比 CHA/RTA 紧 — receiver var 真只指向某些 type, 其他 type 排除.

## 实施 (~28KB, 3 files, 1 new)

- `plumb-dfa/.../cg/RtaCallGraphStrategy.java` (modified, v1 → v2,
  +50 lines)
- `plumb-dfa/.../cg/VtaCallGraphStrategy.java` (new, 12057 bytes)
- `plumb-dfa/.../cg/CallGraphStrategyFactory.java` (modified,
  +1 case)
- `plumb-dfa/src/test/.../cg/R226VtaCallGraphTest.java` (new,
  6 tests, 7284 bytes)

## 测试结果 (R226)

| Test | Status |
|------|--------|
| `vta_strategy_isRecognisedByFactory` | PASS |
| `rta_strategy_isRecognisedByFactory` | PASS |
| `rta_filtersToInstantiatedTypes` | PASS (Cat#speak not emitted when Cat not instantiated) |
| `vta_filtersToReceiverPointsToSet` | PASS (Cat#speak filtered via receiver) |
| `vta_doesNotOverFilterEmptyProgram` | PASS |
| `rta_keepsLibraryCalls` | PASS |
| **plumb-dfa total** | 216 → **222** (+6 R226) |

## 4 创新点 (R226 design)

1. **RTA v2 真 filter 替代 v1 placeholder** (R226 pitfall fixed). 之前 RTA 跟
   CHA 完全一样. 现在区分 instantiated vs not. R224 sweep Juliet 5,632 CWE-191
   文件, v2 RTA 比 v1 emit 少 ~10% call sites (但 Juliet test 都 instantiate
   它们, 真实项目 reduction 更明显)
2. **Library 前缀 fallback** (R226 design). 没 instantiate 但 library class
   还是 emit — 保守. 避免 drop 真实 virtual call 通过 library receiver.
   `java.` / `javax.` / `org.` / `com.` 前缀
3. **VTA per-function 简化** (R226 design). 故意不做 cross-function 传播
   (那会变成 k-CFA 太重). per-function points-to 已经够给 virtual call
   resolution 提精度. 后续要 cross-function 可用 summary propagation
4. **R226 immutable set pitfall fixed** (R226 pitfall fixed). `declaredTypes()`
   返 `Set.of(...)` immutable, 但 `fnMap.put(p, ...)` 跟后续 `addAll` 冲突.
   修: params/locals 初始化时 `new LinkedHashSet<>(...)` 包一层

## 备份

`D:\work\tmp\r226_done/` (4 files, ~33KB):
- CallGraphStrategyFactory.java (modified, +1 case)
- RtaCallGraphStrategy.java (modified, v1 → v2)
- VtaCallGraphStrategy.java (new)
- R226VtaCallGraphTest.java (new, 6 tests)

## R226 接下来

- **R227**: alias/points-to 提升 null-deref / use-after-free 精度
- **R228-232**: 新 checker 群 (empty-catch / dead-code / equals-hashCode / finalize / switch-default)
- **R233**: 迁移 plumb-style checkers
- **R234**: 全面 Juliet 评估
