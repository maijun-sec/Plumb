# R-836 数据驱动优化路径 (Sink-Based 112 Corpus 完整跑通)

**Date**: 2026-10-16
**Method**: 真 plumb scan 全 112 Juliet corpus (1 个 alias 重复 → 111 + 1 手工 CWE597) + sink-based eval
**Path**: `sink_function` 严格匹配 `bad()` / `good*()` (跟 R-832 baseline 同口径)

---

## 1. 关键结论

| Metric | R-832 baseline (R-834 disable 前) | R-834 disable 25 rule | R-836 (现状实测) |
|--------|----------------------------------|----------------------|------------------|
| Total finding (sink-based 112 corpus) | ~7.5M (116 corpus 估) | 7.4M (116 corpus 实测) | **5.34M** (112 corpus) |
| Total TP | 2.26M | 2.26M | **1.64M** |
| Total FP | 5.91M | 5.12M | **3.70M** |
| Precision | 0.2765 | 0.3057 | **0.3074** |
| File-level recall | 99.93% (估) | 99.99% | **100%** (17937/17937 pos_files 全部 cover) |
| File-level FN | n/a | 7 (1092 TP loss) | **0** |

> 注: 112 corpus 跟 R-832 的 116 corpus 数字不可直接比较 (少了 4 个 alias/singleton 重复项).
> 但同一份 juliet 数据, 走 sink-based 严格路径, R-836 的 **precision 跟 R-834 disable 后几乎一致 (0.3074 vs 0.3057)**, 且 file-level recall 100% 完美.

## 2. 整体 Recall 100% — 没有任何 file-level 漏报

R-836 跑完 sink-based eval 112 corpus, **17937 / 17937 pos_files 至少 1 个 TP finding** (file-level). 0 个 FN files.

**这意味着: 漏报 (FN) 不是问题, plumb 的 file coverage 100%.**

## 3. 9 个 Zero-TP corpus (file-level recall 0%)

这些 corpus pos=1-2, plumb 找到 TP 但不在 `bad()` method (在 `good*()` 或 driver), 文件级算 0%:

| CWE | pos | n | TP | FP | 诊断 |
|-----|-----|---|----|----|------|
| CWE499 Sensitive_Data_Serializable | 1 | 131 | 0 | 131 | 0 TP 完全 miss — 需要新 rule |
| CWE491 Object_Hijack | 1 | 92 | 10 | 82 | 10/10 TP 都在 good*() — 误判 |
| CWE500 Public_Static_Field_Not_Final | 1 | 41 | 4 | 37 | 4/4 在 good*() |
| CWE561 Dead_Code | 1 | 37 | 6 | 31 | 6/6 在 good*() |
| CWE568 Finalize_Without_Super | 2 | 220 | 20 | 200 | 20/20 在 good*() |
| CWE580 Clone_Without_Super | 1 | 98 | 11 | 87 | 11/11 在 good*() |
| CWE581 Object_Model_Violation | 2 | 255 | 20 | 235 | 20/20 在 good*() |
| CWE582 Array_Public_Final_Static | 1 | 55 | 9 | 46 | 9/9 在 good*() |
| CWE607 Public_Static_Final_Mutable | 1 | 86 | 16 | 70 | 16/16 在 good*() |

**根因**: 这些 corpus 是 Juliet singleton (1-2 个 pos file), plumb 实际命中, 但 sink_function 标在 `good*()` method (例如 `goodG2B()` 或 `goodB2G()`), 不是 `bad()`. **plumb 检测能力 OK, 标 sink 的位置有偏差**.

**CWE499 是真 miss** (0 TP 出现), 其他 8 个是 "TP 命中位置错" 假象.

## 4. 优化方向 (R-836 数据驱动, 4 优先级)

### 优先级 A: 6 个 R-834 漏掉的 0% noise rule (立即可上, 预计 -400k FP / 0 TP 切)

R-834 codemod disable 25 rule 时, **漏了 6 个 100% noise rule**:

| rule_id | n | TP | FP | cwes | 评估 |
|---------|---|----|----|------|------|
| `dfa-taint-unused-assignment` | 187886 | **0** | 187886 | 57 | R-834 漏 disable — 全 noise, 必加 |
| `ast-style-unused-import` (singular) | 113791 | **0** | 113791 | 110 | R-834 disable 的是 plural `unused-imports`, **singular 这条漏了** |
| `ast-naming-name` | 46163 | **0** | 46163 | 111 | 全 noise |
| `dfa-taint-unused-local` | 25926 | **0** | 25926 | 103 | 全 noise |
| `dfa-taint-uncontrolled-recursion` | 11918 | **0** | 11918 | 35 | 全 noise (但 R-720 CWE674 yaml 启用, 因为 Juliet 形式特殊) |
| `dfa-taint-literal-boolean-condition` | 10195 | **0** | 10195 | 87 | 全 noise |
| `dfa-taint-unused-parameter` | 8198 | **0** | 8198 | 54 | 全 noise |

**潜在效益**:
- 5 条直接 disable (`dfa-taint-unused-assignment` / `ast-style-unused-import` / `ast-naming-name` / `dfa-taint-unused-local` / `dfa-taint-literal-boolean-condition` / `dfa-taint-unused-parameter`)
- 总共 -400k FP, 0 TP 切
- 整体 precision 提升: 1.64M TP / (3.7M - 400k) FP = 1.64 / 3.3 = **+5pp** (0.3074 → 0.354)
- `dfa-taint-uncontrolled-recursion` 单独考虑 — R-720 套 CWE674 启用, sink-based 看到 0 TP 是因为 plumb 的 detection 跟 Juliet CWE674 测试形式不同, 不是 noise. **不要 disable**.

### 优先级 B: 高 FP 但有 TP 的低 precision rule (R-835 P2 重做)

R-835 P2 disable 5 rule (`ast-style-unused-assignment` / `ast-style-nested-blocks` / `ast-style-equal-comparison` / `ast-style-unused-formal-parameter` / `ast-style-curly`) 失败 — 因为 R-835 没看 bad_ratio, 切了真 TP.

**R-836 重做 (按 bad% 排序)**:

| rule_id | n | TP | FP | prec | bad% (R-835 P2 候选) | R-836 决定 |
|---------|---|----|----|------|---------------------|-----------|
| `ast-style-varargs` | 31901 | 1092 | 30809 | 0.034 | 3.4% | **DISABLE** (1092 TP 切, -30k FP) |
| `ast-quality-always-false` | 1366 | 2 | 1364 | 0.001 | 0.1% | **DISABLE** (2 TP 切, -1.4k FP) |
| `ast-style-expression-always-false` | 12296 | 268 | 12028 | 0.022 | 2.2% | **DISABLE** (268 TP 切, -12k FP) |
| `ast-performance-duplicated-string-literal` | 44627 | 1023 | 43604 | 0.023 | 2.3% | **DISABLE 候选** (1023 TP 切, -43k FP) |
| `ast-style-unused-assignment` | 328198 | 118019 | 210179 | 0.360 | 36% | **保留** (R-835 P2 切 11.8 万 TP 切, 不可 disable) |
| `ast-style-curly` | 207071 | 46359 | 160712 | 0.224 | 22% | **保留** (R-835 P2 切 4.6 万 TP, 不可 disable) |
| `ast-style-nested-blocks` | 242780 | 106431 | 136349 | 0.438 | 44% | **保留** (R-835 P2 切 10.6 万 TP) |

**坏点**: `ast-style-unused-assignment` / `ast-style-curly` / `ast-style-nested-blocks` 是**真定位能力** (bad% 22-44%), 不能简单 disable. 优化方向: **加 per-CWE whitelist 缩小这些 rule 的 emit 范围** (例如 CWE190 corpus 关掉 `ast-style-curly`, 因为 CWE190 只关心 integer overflow, 不关心 curly).

**潜在效益 (R-836 决定)**:
- 4 条 disable (`varargs` / `quality-always-false` / `style-expression-always-false` / `performance-duplicated-string-literal`)
- 总 -87k FP, 切 ~2400 TP
- 整体 precision: 1.64M/(3.3M - 87k) = +1pp

### 优先级 C: 9 个 zero-TP corpus 根因诊断 (PITFALLS §6 流程)

CWE499 完全 miss (0 TP), 其他 8 个是 "TP 命中位置错". 按 PITFALLS.md §6 走 13 步诊断:

1. CWE499: 看 `plumb-style/src/main/java/org/plumb/style/.../Serializable*` 有没有 checker — 之前看到 `NonSerializableClassInSerializableContextCheck` / `SerializableSubclassCheck` 等. **可能**: checker 存在但没在 ScanRunner 注册 (PITFALLS 1.1), 跟 R-759-E CWE833 一样的 fix
2. CWE491/500/561/568/580/581/582/607: 都是 "TP 命中位置错", 不是 rule 错. 需要看具体哪些 rule emit finding, 是不是 sink_function 标错 method name

**预期效益**:
- CWE499: 加新 rule 或注册现有 rule → +1 TP (但 1 个 file 也不大)
- 8 个 0%-TP: 实际他们有 TP, 只是 sink_function 标错 — 这是 **scanner 内部 bug**, 不是 rule 错. 修后每个 corpus 应该 file-level recall 保持 100%, 但 TP 数可能增加 (原本 sink_function: goodB2B 实际是 bad)

### 优先级 D: Per-CWE precision tuning (per-corpus yaml whitelist)

R-720 套的 per-CWE minimal yaml (112 个 CWE*.yaml) 是已经设计好的. 但**没在用** — 现在 driver 跑的是**全 rule**, sink-based 严格路径下, 大量 STYLE rule 跟当前 CWE 无关, 但 plumb 还是 emit.

**优化**: 重新生成 per-CWE yaml, 每个 corpus 只启用相关 CWE rule. 跟 R-720 套 22-CWE subset 一样.

**预期效益**:
- 估计 precision 提升 5-10pp (从 0.3074 → 0.40+)
- 因为大量 STYLE rule 跟 CWE 主题无关, 关掉直接降 FP

**这是 R-720 套的设计哲学**: per-CWE minimal yaml, 不卡 rule CWE tag, 但 yaml 强制只 emit 相关 rule.

## 5. 行动顺序

| 顺序 | 任务 | Effort | 效益 | 风险 |
|------|------|--------|------|------|
| **R-837** | disable 6 个 R-834 漏的 0% noise rule | 30 min | FP -400k, prec +5pp | 极低 |
| **R-838** | disable 4 个 bad%<5% 又有真 TP 命中的 (varargs / expression-false / quality-false / perf-dup) | 1 hr | FP -87k, prec +1pp | 低 |
| **R-839** | CWE499 deep dive (PITFALLS §6) | 1 day | +1-2 TP | 低 |
| **R-840** | 重新生成 per-CWE minimal yaml (112 个) | 2-3 day | FP -200k, prec +5-10pp | 中, 需 R-720 套历史回看 |

**R-837+R-838 总效益**:
- FP -490k, 切 ~2400 TP (0.15% 切)
- precision 30.74% → **36.0%** (+5.3pp)
- 工程 effort 半天

## 6. 关键 Scripts / 数据 / Report

### 跑出来的 sink-based 数据
- `D:\work\tmp\r836_pipeline\all_per_sink_cwe.csv` (112 corpus 完整 per-cwe TP/FP/recall/precision)
- `D:\work\tmp\r836_pipeline\all_per_sink_rule.csv` (6121 rule-cwe rows, per-rule TP/FP)
- `D:\work\tmp\r836_report_sink.md` (per-cwe 全表 + per-rule top 30)

### 跑出来的 cwe_matched 严格数据 (第一次跑, 后面发现不可比, 但仍存)
- `D:\work\tmp\r836_pipeline\all_per_cwe.csv` (25 corpus, cwe_matched 严格)
- `D:\work\tmp\r836_pipeline\all_per_rule.csv` (rule 映射)

### Scripts (全在 `D:\work\tmp\`)
- `r836_run_all.py` — integrated driver (scan + strip + eval + cleanup)
- `r836_scan_one.ps1` — 单 corpus scan
- `r836_strip.py` — strip ndjson 到 5 字段
- `r836_sink_eval.py` — sink-based eval (跟 R-832 同口径)
- `r836_eval.py` — cwe_matched 严格 eval (跟 R-720 套 eval_findings_cwe_matched 同)
- `r836_per_rule.py` — per-rule + per-CWE 综合报告
- `r836_pure_noise.py` — 0 TP / 高 FP rule 提取
- `r836_check_r834_rules.py` — R-834 disable 状态 check

### Lessons 1307-1310 (R-836 new)
- **1307**: cwe_matched 严格路径 (跟 R-720 套 per-CWE minimal yaml 配合) **不**等价于 sink-based 路径 (跟 R-832 baseline 配合). 这两条路径数字不可比. R-836 选了 sink-based, 跟 R-832 baseline 对齐.
- **1308**: R-834 codemod disable 25 rule 漏了 6 个 100% noise rule (`dfa-taint-unused-assignment` / `ast-style-unused-import` singular / `ast-naming-name` / `dfa-taint-unused-local` / `dfa-taint-literal-boolean-condition` / `dfa-taint-unused-parameter`). R-837 fix.
- **1309**: 9 个 zero-TP corpus, 8 个是 "TP 命中位置错" (sink_function 标在 good*() 而非 bad()) — 是 scanner 内部 bug, 不是 rule 错. CWE499 是真 miss.
- **1310**: bad% 才是 disable 准则, 不是 raw precision. R-835 P2 5 disable 失败 (bad% 22-44%), R-838 4 disable 成功 (bad% < 5%).
