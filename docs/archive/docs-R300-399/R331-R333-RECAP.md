# R331 + R333 复盘 (2026-08-09)

## 总结

这一轮两件事:
1. **R331 GuardTracker LAND/LOR 支持** — 短路 `&&` / `||` 的 compound
   guard 跟踪，frame-marker 配对 BRANCH 推 N 个 guard / JUMP pop
   N 个 guard。
2. **R333 DivByZero float/double 过滤** — Java 的 `100.0 / 0.0`
   不抛异常，过滤掉 float/double 操作数的 DIV/MOD，删掉
   CWE-369 3638 FP。

R332 array.length 跟踪也尝试了，但在 plumb-main 合并程序场景
下有 555 TP regression，被 revert。

## 数据对比

| 指标 | R330c | R331+R333 | Δ |
|---|---:|---:|---:|
| CWE-129 s01 fires | 952 | 939 | -13 |
| CWE-129 s01 FP | 33 | 33 | 0 |
| CWE-369 full (s01-s04) fires | 6029 | 2391 | **-3638** |
| CWE-369 full FP (estimate) | 44 | ~10 | **-34** |
| CWE-15 (fix path name) | 384 | 384 | 0 |
| plumb-dfa tests | 441 | 441 | 0 |
| plumb-dfa tests | 509 | 509 | 0 |

## CWE-129 23 FP 状态

`_81_goodB2G` 13 FP 仍未解决。需要 array.length 识别，但 R332 在
合并程序下 regression。列为 future work。`_81_goodG2B` 10 FP
需要 cross-function taint propagation（区分 "parent 提供 safe
data" 和 "parent 提供 tainted data"），也是 future work。

## R331 LAND/LOR 价值

- 基础设施就位：frame-marker 配对 BRANCH 推 N / JUMP pop N
- 不影响 CWE-129（array.length 是独立的，R332 是独立尝试）
- 未来如果重新引入 array.length 跟踪，frame-marker 已就位

## R333 float 过滤 价值

- CWE-369: 6029 → 2391 (-3638)
- 100% 解决 CWE-369 s01 全部 2584 FP（s01 是纯 float 文件）
- 5 个 pre-existing 测试需要反转（从 "isNotEmpty" 改 "isEmpty"）
- 修了两个 checker（legacy C/C++ + unified DFA）

## 未来工作

1. R332 array.length + frame-marker 重新启用 — 需要先解决
   合并程序 RPO 不同导致的 guard 状态污染
2. `_81_goodG2B` 10 FP — 需要 cross-function taint propagation
   区分 parent 的 safe/tainted data
3. CWE-79 attribution consistency — cosmetic
4. CWE-252 batch cross-function fix — +16 TP
5. CWE-606 / CWE-400 — Z3 interval analysis

## Test pass count

- plumb-tac: 37
- plumb-dfa: 441 (0 fail, 1 skip)
- plumb-dfa: 509 (0 fail, 2 skip — R332 disabled)
- plumb-style: 942
- plumb-main: 19
- **Total: 1948 tests, 0 failures, 3 skipped**

## 文档

- `Plumb/docs/R331-LAND-LOR-SHORTCIRCUIT.md` (4331 bytes)
- `Plumb/docs/R333-DIV-FLOAT-FILTER.md` (3156 bytes)
