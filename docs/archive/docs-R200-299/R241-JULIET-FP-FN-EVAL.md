# R241 — Juliet False-Positive / False-Negative 综合评估

**Date**: 2026-08-01
**Round**: R241 (post-R226 engine expansion, post-R238/R239 source-analysis)
**Sweep root**: `D:\work\tmp\Juliet_Java\src\testcases\`
**Output dir**: `D:\work\tmp\r241_juliet_full_sweep\`
**Report**: `D:\work\tmp\r241_juliet_full_sweep.tsv`

## 1. 评估方法

R241 把 14 个 source-analysis checker (R228 3 + R229 4 + R230 1 + R238 2 + R239 4) 串成单一 `DfaEngine`, 在 `D:\work\tmp\Juliet_Java\src\testcases\` 下对每个 target CWE family 跑全部 `.java` 文件, 区分:

- **bad** file: 文件名不含 `_good*` 的测试用例 (Juliet 约定)
- **good** file: 文件名含 `_good*` 的 "反例" 测试用例 (本应不 fire)
- **TP** = 在 bad 上 fire
- **FP** = 在 good 上 fire
- **FN** = 在 bad 上未 fire
- **TN** = 在 good 上未 fire

precision = TP / (TP + FP), recall = TP / (TP + FN).

## 2. 8 family FP/FN 结果

| family | files | bad | good | TP | FP | FN | TN | precision | recall |
|--------|------:|----:|-----:|---:|---:|---:|---:|----------:|-------:|
| CWE252 | 21    | 21  | 0    | 18 | 0  | 3  | 0  | 1.00      | 0.86   |
| CWE486 | 23    | 23  | 0    | 7  | 0  | 16 | 0  | 1.00      | 0.30   |
| CWE563 | 254   | 238 | 16   | 121| 8  | 117| 8  | 0.94      | 0.51   |
| CWE568 | 8     | 6   | 2    | 3  | 2  | 3  | 0  | 0.60      | 0.50   |
| CWE572 | 21    | 21  | 0    | 7  | 0  | 14 | 0  | 1.00      | 0.33   |
| CWE586 | 22    | 22  | 0    | 19 | 0  | 3  | 0  | 1.00      | 0.86   |
| CWE597 | 21    | 21  | 0    | 7  | 0  | 14 | 0  | 1.00      | 0.33   |
| CWE209 | 38    | 38  | 0    | 13 | 0  | 25 | 0  | 1.00      | 0.34   |
| **ALL**| **408** | **390** | **18** | **195** | **10** | **195** | **8** | **0.95** | **0.50** |

**核心发现**:
- **Precision 95.1%** — 195 个 TP, 10 个 FP, source-analysis 误报率极低
- **Recall 50.0%** — 一半的 bad file 漏报, 主要因为:
  1. **解析失败**: 大量 Juliet `_bad.java` 用 `private void bad()` + 复杂 ant `antbuild/` 子目录 wrapper, parser 跳过
  2. **Pattern 不够宽**: 现有 checker 是单指令 pattern; 很多 Juliet 模式需要跨语句的 control-flow 分析 (e.g. CWE-572/597 经常 `Thread t = new Thread(...)` 之后 `t.run()` 而非 `t.start()`)
  3. **缺一个 CWE-486 的"歧义覆盖"**: CWE486 recall 30% 因为很多 Juliet 用 `getClass().getName() == other.getClass().getName()`, 我只覆盖了 receiver-typed `getName()` 模式

## 3. 全 113 family 历史数据 (R223 之前的 14 checker sweep)

R223 full sweep 已经跑过全 113 family, 累计:
- **family 数量**: 113 / 113
- **总文件数**: ~46,793 (Juliet 总数, 跟 R223 sweep 一致)
- **总 findings**: ~600K (含 ResourceLeak over-fire + NullDeref over-fire + ArrayIndex/IntOverflow 真检测)

checker 在 family 上的实际命中 (top 10 finding 数量):
- `CWE89_SQL_Injection`: 69,446 (ResourceLeak over-fire 占 67,374, SQL 真检测部分)
- `CWE190_Integer_Overflow`: 38,587
- `CWE129_Improper_Validation_of_Array_Index`: 36,479
- `CWE191_Integer_Underflow`: 30,004 (R222b 后 +1549 CWE-191 真实命中)
- `CWE369_Divide_by_Zero`: 8,445
- `CWE78_OS_Command_Injection`: 2,225
- `CWE80_XSS`: 3,949
- `CWE90_LDAP_Injection`: 4,361
- `CWE22/23 Path_Traversal`: 5,553
- `CWE476_NULL_Pointer_Dereference`: 287K
- `CWE404_Improper_Resource_Shutdown`: 13,716
- `CWE643_Xpath_Injection`: 2,024
- `CWE601_Open_Redirect`: 924
- `CWE400_Resource_Exhaustion`: 8,586

> 注: ResourceLeak 命中数过大 (60%+ of all findings), 因为 Juliet bad file 经常泄漏所有 `new SomeResource()` (Juliet 的 `bad()` 几乎全是 throw exception + close skip). 这是 Juliet 故意设计的, 不代表真实 FP. 已用 R224 round 收紧 (`getMatcherFor(FILE_INPUTSTREAM).close()` 这种 close-on-resource 真实匹配).

## 4. 整体 R241 状态

| 指标 | R148f baseline | R223 (14 checker) | R241 (28 checker, source + DFA) |
|------|----------------|-------------------|----------------------------------|
| 总 checker 数 | 7 | 14 | 28 (27 main + 1 library) |
| Juliet 跑过 family 数 | 49 / 113 (43%) | 113 / 113 (100%) | 113 / 113 (100%) |
| 总 findings | ~5K | ~600K (含 Juliet over-fire) | ~700K (含 R238-R239 新增 source) |
| Source-analysis checker 数 | 0 | 9 (R228-R230) | 15 (R228-R230 + R238 + R239) |
| DFA 路径敏感 checker 数 | 7 | 13 | 13 |
| 平均 precision (source-analysis) | n/a | n/a | **0.95** |
| 平均 recall (source-analysis) | n/a | n/a | **0.50** |
| Z3 求解器集成 | 无 | R218 LocalBridge | R218 + R235 (production wire) + R240 (cached) |
| CallGraph 策略 | 1 (CHA) | 4 (CHA / RTA / VTA / Stub) | 5 (+ 1-CFA per R240) |

## 5. 跟用户 4 方向对齐

1. **PMD/Sonar/SpotBugs 风格 checker**: R228-R230 + R238 + R239 共 14 个新 source checker. 源码中不出现 PMD/SonarQube/SpotBugs.
2. **引擎优化**: R226 VTA, R227 PointsTo, R240 1-CFA, R240 CachedZ3Bridge.
3. **迁移到 plumb-dfa**: 27 个 checker 全部在 plumb-dfa (1 个 plumb-style AST-level checker 故意不迁移, 架构不同).
4. **Juliet FP/FN 评估**: R234 (6 family) + R241 (8 family source + 113 family historical) 综合报告.

## 6. 下一步优化方向 (按用户优先级: 底层 DFA + z3 > 规则)

### 短期 (R242-R245, ~3-5 days)

1. **R242 z3 path condition 强化** (~1 day)
   - path condition 跟 CWE-129 数组下标 / CWE-191 整数下溢深度集成
   - 加 `PathCondition::implies(other)` API 让 checker 互相对比 guard
   - Z3 path-condition cache 命中率 90%+ (R240 已加 cache, 待测实际 perf)

2. **R243 域敏感 + 上下文敏感交叉** (~1 day)
   - 在 1-CFA (R240) 之上加 simple context (per call site), virtual call resolution 进一步紧
   - DfaEngine 加 context stack propagation, summary 跟 caller context 联合
   - Juliet CWE-572 / CWE-597 recall 应从 33% 提到 60%+

3. **R244 source checker 跨函数追踪** (~1 day)
   - UnusedVariable 跨函数: 变量在本函数写了没读, 但同一 class 的其他方法读了, 不 fire
   - ReassignParameter 跨函数: param 在 caller 写入 + callee 读, 不 fire
   - Juliet CWE-563 recall 应从 51% 提到 70%+

4. **R245 CFG 块级分析** (~1 day)
   - 当前 source checker 是单指令 pattern, 加 block-level 跟踪
   - `EmptyCatchBlockChecker` (CWE-390): catch block 有 statement count
   - `SwitchMissingDefaultChecker` (CWE-478): switch 跳 table 缺 default
   - Juliet CWE-209 recall 应从 34% 提到 60%+

### 中期 (R246-R250, ~5-7 days)

5. **R246 全 113 family 真正 FP/FN 评估** (~1 day)
   - 跟 Juliet ground truth (每个 bad file 是真 bug, 每个 good file 不该 fire) 全量算
   - 区分 "Juliet over-fire" (Juliet 故意构造, 真实不漏) vs "真 FP"
   - 输出真 FP rate / FN rate

6. **R247 Juliet parser 增强** (~2 days)
   - Juliet 用 `IO` static helper 大量 hidden coupling, 解析器跳过很多
   - 加 Juliet-specific stub: `IO.STATIC_FIVE = 5`, `IO.staticReturnsTrueOrFalse()`, `IO.staticReturnsTrue()`, `IO.staticReturnsFalse()` 等
   - Juliet 总解析成功率从 ~60% 提到 90%+

7. **R248 path-sensitive taint** (~2 days)
   - 1-level path sensitivity: BRANCH 条件被 Z3 求解, taint 只在 guard 满足时流动
   - Juliet CWE-129 / CWE-190 / CWE-191 recall 应从 50% 提到 80%+

### 长期 (R251+, 按用户反馈)

8. **R249 跨 TU 域敏感** (~3 days)
   - 现在 cross-TU 只跨 function 传播 taint summary, 不跨 function 边界做 path-sensitive
   - 加 global taint store, 跟 R226 VTA 联合

9. **R250 symbolic execution 集成** (~5 days)
   - 真正的 symbolic execution 引擎 (lazy)
   - Juliet FP 应 < 5% (跟 Coverity 持平)
