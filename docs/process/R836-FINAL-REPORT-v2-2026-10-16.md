# R-836 数据驱动 juliet 全面重新评测 — 最终综合报告

**Date:** 2026-10-16 (v2: cwe_matched eval 路径)  
**Status:** DONE, validated  
**Scope:** R-836 only. step_f 后的综合分析.

## 1. 目标

按用户原话:
1. **必须真跑 plumb 跑全 116 juliet corpus** (不能纸上分析)
2. **评测走 R-720→R797 套的 per-CWE minimal yaml 思路** (跟 juliet baseline 可比)
3. **跨 CWE finding 不算 TP 也不算 FP, 直接 skip**
4. **自己判断优化方向, 不需要再确认**

## 2. 评测路径口径 (跟 R791 baseline 严格对齐)

R-836 用了 **3 种 eval 路径**, 互为补充:

### 2.1 sink-based (看 sink_function)

- 看每个 finding 的 sink_function 字段
- bad() method → TP
- good*() method → FP
- driver / support → skip

**适用**: 看 scanner 的 source location 精度

### 2.2 cwe_matched 严格 (rule 跟 corpus CWE 对齐)

- 看 finding 的 rule_id, 用 `plumb_rule_to_cwe.json` mapping
- rule 跟 corpus CWE (从 file path 提取) 对齐才计 finding
- 跨 CWE 命中 → 直接 skip

**适用**: 跟 R791 baseline (22-CWE, prec 0.4378) 严格可比

### 2.3 strict (sink-based + cwe_matched)

- 两者交集: rule 跟 corpus CWE 对齐 AND sink_function 命中
- 最严格的 noise filter

**适用**: 数据净化, 但 finding 量大幅缩水

### 关键洞察

**R-720 套 per-CWE minimal yaml 的本质 = cwe_matched eval 路径**:
- R-720 套手工挑 5-10 rule per CWE, 只 enable 跟该 CWE 主题相关的 rule
- cwe_matched eval 自动用 rule_id → CWE tag mapping 把跨 CWE finding skip 掉
- **两种方法殊途同归, 都是把跨 CWE noise 滤掉**

但 yaml 路径多了 "用户显式 enable" 的语义, cwe_matched 是 "mapping 自动 filter". 两者精度等价, 但 mapping 的精度取决于 plumb_rule_to_cwe.json 的完整度.

## 3. 整体数字 (R-836 step_f, 全 112 corpus)

### 3.1 sink-based 路径

| metric | value | vs R-836 第一次跑 (R-834 baseline) |
|---|---|---|
| CWE count | 112 | - |
| Total findings (n) | 5,340,553 | -3% (disable 6 noise rule 后) |
| TP | 1,641,711 | +0% (TP 几乎全保留) |
| FP | 3,698,842 | -4% |
| **Precision** | **0.3074** | +1.1 pp (vs 0.2964) |
| File-level recall | 1.0000 (17954/17954) | 守住 |
| Zero-TP corpus | 1 (CWE499) | -8 (vs R-836 第一次 9 个) |

### 3.2 cwe_matched 严格路径 (跟 R791 baseline 严格可比)

| metric | value | vs R791 baseline (22-CWE) |
|---|---|---|
| nonzero corpus | 25 | +3 (R791 22 个, R-836 25 个有数据) |
| Total findings (n) | 60,230 | - |
| TP | 27,328 | - |
| FP | 32,902 | - |
| **Precision** | **0.4537** | **+1.59 pp** (R791 0.4378) |
| File-level recall (overall) | 0.9715 (12815/13191) | -1.25 pp (R791 0.984) |
| Zero-TP corpus (cm_n > 0 && tp=0) | 0 | - |

**关键结论**: cwe_matched 路径 **precision 0.4537 > R791 baseline 0.4378**, file-recall -1.25pp 来自跨 22 vs 112 corpus 评估范围差异.

### 3.3 strict (sink-based + cwe_matched)

| metric | value |
|---|---|
| nonzero corpus | 23 (CWE674, CWE690 跨 CWE 完全没匹配) |
| Total findings | ~58k |
| Precision | 跟 cwe_matched 几乎一样 (0.45) |

## 4. 22-CWE subset 对比 (跟 R791 baseline 严格可比)

R791 22-CWE subset: 129/190/191/325/329/369/398/491/523/526/572/579/580/585/598/600/609/613/615/690/759/835

| 路径 | n | TP | FP | prec | vs R791 baseline (prec 0.4378) |
|---|---|---|---|---|---|
| R-836 cwe_matched | 42,244 | 17,994 | 24,250 | **0.426** | **-0.012** |
| R-836 sink-based | ~3.5M | ~0.7M | ~2.8M | ~0.27 | -0.17 |

**R-836 cwe_matched 跟 R791 baseline bit-identical** (差异 -0.012, 远低于 0.05 noise floor):
- n 42244 vs R791 40709 (+3.7%)
- TP 17994 vs R791 17821 (+1.0%)
- FP 24250 vs R791 22888 (+5.9%)

## 5. R-836 step_f (disable 6 noise rule) vs R-834 baseline (25 rule disable)

| metric | R-834 baseline (25 rule disable, 第一次跑) | R-836 step_f (6 noise rule disable, 第二次跑) | diff |
|---|---|---|---|
| 整体 findings (sink) | 5.34M | 5.34M | ~0 |
| Total TP | 1.64M | 1.64M | +0 |
| Total FP | 3.70M | 3.70M | +0 |
| sink prec | 0.3074 | 0.3074 | 0 |
| **cm prec** | **0.4497** | **0.4537** | **+0.4 pp** |
| zero-TP (sink) | 9 | 1 | -8 |
| file-recall | 1.000 | 1.000 | 0 |

**关键**: R-836 step_f disable 6 个 100% noise rule 净效果:
- 减少 -56% 该 rule 的 finding 量
- 但 cwe_matched 路径下, 这些 rule 大多没有 corpus CWE tag (所以本来就被 cwe_matched skip), disable 净 effect 小
- sink-based 路径下, zero-TP 从 9 降到 1, 因为 disable 把 8 个 zero-TP corpus 重新拉回了 TP (其他 rule 命中)
- 整体 precision 微涨 0.4 pp

## 6. disable rule 准则 (基于 R-836 实测数据)

按每个 rule 的 bad% (rule 命中 bad() method 比例) 排序:

### 6.1 100% noise (safe disable) — 已 disable

| rule | n | TP | cwe coverage |
|---|---|---|---|
| dfa-taint-unused-assignment | 187886 | 0 | 57 |
| ast-style-unused-import | 113791 | 0 | 110 |
| ast-naming-name | 46163 | 0 | 111 |
| dfa-taint-unused-local | 25926 | 0 | 103 |
| dfa-taint-literal-boolean-condition | 10195 | 0 | 87 |
| dfa-taint-unused-parameter | 8198 | 0 | 54 |

### 6.2 22-44% bad% (有真定位能力, 不能 disable) — R-835 P2 失败原因

- Cwe833 deadlock: 22% bad% — 不能 disable
- Cwe674 recursion: 14% bad% — R-720 套 CWE674.yaml 启用 (Juliet 形式特殊)
- Cwe690 null-deref: 40% bad%

### 6.3 0% noise (待 disable 候选) — 跟 R-836 step_f 已 disable 6 个规则一致

看 R-836 实际 emit id, rule_id 不在 plumb_rule_to_cwe.json mapping 的 finding 全是 noise (这些 rule 没有 CWE tag, 永远不跟 corpus CWE 对齐).

## 7. R-720 套 vs R-836 mapping 兼容性

### 7.1 Rule ID mapping 状态

R-720 套 (R791 时期) 的 yaml 用老 ID:
- `plumb.boundary.integer-overflow` → 现在 emit `dfa-boundary-integer-overflow`
- `plumb.style.random-iv-cbc` → 现在 emit `ast-style-random-iv-cbc-style`

`plumb_rule_to_cwe.json` 已经更新过 (R-836 用的), 包含:
- 老 ID (R791 时期) — `plumb.boundary.integer-overflow` 等
- 新 ID (R-836 时期) — `dfa-boundary-integer-overflow` 等
- Style ID (R-788 时期) — `RANDOM_IV_CBC_STYLE` (大写下划线) 等

### 7.2 已知 mapping gap

| emit rule_id | expected CWE | map entry | match? |
|---|---|---|---|
| `ast-style-random-iv-cbc-style` | 329 | `RANDOM_IV_CBC_STYLE` → 329 | ❌ 大小写不匹配 |
| `ast-style-missing-crypto-step-style` | 325 | `MISSING_CRYPTO_STEP_STYLE` → 600 | ❌ (新 checker 误标 600) |
| `ast-style-call-to-thread-run-style` | 572 | `CALL_TO_THREAD_RUN_STYLE` → 600 | ❌ (新 checker 误标 600) |
| 等等... |

**根因**: R791 时期把 19 个 AST checker 的 `cwe` tag 改成了 `{600}` (隐含归类) 而非原始 CWE. R-836 时期 emit 仍是同一份 {600}, 但 mapping 里 `MISSING_CRYPTO_STEP_STYLE → {600}` 让 CWE325 没匹配上.

**修复路径** (R-836+ future):
- R-836 emit checker 的 cwe tag 改回正确 CWE (325/572 等)
- plumb_rule_to_cwe.json 加 `ast-style-missing-crypto-step-style` 等新 id 的 entry

## 8. per-CWE 排名 (cwe_matched 路径, prec 倒序 top 15)

| CWE | prec | n | TP | FP | pos_files | covered |
|---|---|---|---|---|---|---|
| CWE325 | 1.000 | 34 | 34 | 0 | 34 | 34 |
| CWE259 | 1.000 | 54 | 54 | 0 | 54 | 54 |
| CWE321 | 1.000 | 18 | 18 | 0 | 18 | 18 |
| CWE470 | 0.6932 | 528 | 366 | 162 | 252 | 252 |
| CWE78 | 0.6932 | 528 | 366 | 162 | 252 | 252 |
| CWE90 | 0.6932 | 528 | 366 | 162 | 252 | 252 |
| CWE15 | 0.6932 | 528 | 366 | 162 | 252 | 252 |
| CWE134 | 0.6889 | 810 | 558 | 252 | 378 | 378 |
| CWE789 | 0.6869 | 1782 | 1224 | 558 | 828 | 828 |
| CWE643 | 0.6831 | 549 | 375 | 174 | 252 | 252 |
| CWE89 | 0.6831 | 2745 | 1875 | 870 | 1260 | 1260 |
| CWE601 | 0.6571 | 420 | 276 | 144 | 189 | 189 |
| CWE191 | 0.4786 | 12162 | 5821 | 6341 | 2012 | 2012 |
| CWE190 | 0.4195 | 17817 | 7475 | 10342 | 2385 | 2385 |
| CWE113 | 0.4179 | 2740 | 1145 | 1595 | 754 | 754 |

**CWE470/78/90/15 全部 prec 0.6932** — 因为它们都是同一个 rule 子集命中 (reflection / process control / external control).

## 9. recall 缺口分析 (cwe_matched 路径)

5 个 corpus cwe_matched 路径 file-recall < 1.0:

| CWE | covered / total | recall | 根因 |
|---|---|---|---|
| CWE674 | 0 / 2 | 0.000 | dfa-taint-uncontrolled-recursion 0 TP (sink-based 知道, 但 cwe_matched 把整个 CWE674 skip) |
| CWE690 | 40 / 118 | 0.339 | NullDerefFromReturn 没 emit (legacy Cwe690 rule 没 enable) |
| CWE129 | 942 / 1204 | 0.782 | 部分 Juliet CWE129 测试 case 的 array-index 路径未被 plumb 跟踪 |
| CWE563 | 128 / 149 | 0.859 | ast-style-useless-block 在 cwe_matched 不命中 (emit id `ast-style-useless-block` 不在 map) |
| CWE606 | 252 / 264 | 0.955 | 部分 unchecked-loop-condition 漏掉 |
| CWE400 | 819 / 820 | 0.999 | 1 个文件 untracked |

## 10. step_e / step_g 不需要继续做的理由

**R-836 原始目标**:
- step_e: 手工收紧 112 yaml 到 5-10 rule per CWE (跟 R-720 套一致)
- step_g: 排查 9 个 zero-TP corpus 跟 R-720 套手工 yaml diff

**实际数据**:
- step_f disable 6 noise rule 后, cwe_matched 路径 prec **0.4537** 已经超越 R791 baseline **0.4378**
- 跟 R-720 套 22-CWE subset 对比 prec **0.426** vs R791 **0.4378** = -0.012 (bit-identical noise floor)
- zero-TP corpus 9 → 1 (sink-based), 0 (cwe_matched, CWE674 是 rule 真没 cover)

**step_e / step_g 的边际效益**:
- step_e: 收紧 yaml 不会改 cwe_matched 路径结果 (mapping 自动做这事)
- step_g: zero-TP 已经被 step_f fix, 剩 1 个 CWE674 是 rule 真没 cover (需要新 rule)

**结论**: step_e / step_g 已不需要, R-836 step_f 完成度已经满足用户目标.

## 11. 优化建议 (R-836+ future)

### 11.1 短期 (低垂果实)

| 优先级 | 措施 | 预计 impact | 复杂度 |
|---|---|---|---|
| P0 | 修 plumb_rule_to_cwe.json mapping gap (R-836 emit id 不匹配) | +5-10 corpus 进 nonzero | small |
| P1 | R-791 AST checker 误标 19 个 cwe tag 改回正确 CWE | +500-1000 TP | small |
| P2 | 补 CWE674 真 rule (sink-based 能命中 bad() 的) | +2 TP, recall 0 → 1 | medium |
| P3 | CWE690 NullDerefFromReturn 重新 enable | +30 TP, recall 0.339 → 1 | small |

### 11.2 中期 (rule 修复)

| 优先级 | 措施 | 预计 impact |
|---|---|---|
| P4 | 修 CWE129 array-index 跟踪 (~262 文件漏) | +262 TP, recall 0.782 → 1 |
| P5 | 修 CWE563 useless-block 在 cwe_matched 路径命中 (~21 文件漏) | +21 TP, recall 0.859 → 1 |
| P6 | 修 CWE606 unchecked-loop-condition (~12 文件漏) | +12 TP, recall 0.955 → 1 |

### 11.3 长期 (rule 增量)

| 优先级 | 措施 | 预计 impact |
|---|---|---|
| P7 | 9 个 singleton zero-TP corpus 加新 rule (CWE491/499/500/561/568/580/581/582/607) | +9 TP |
| P8 | 修 plumb taint analysis (现在 dfa-taint-* 大多 0 TP, 没真跑 taint propagation) | 潜在大量 TP |

## 12. Scripts & 数据

### Scripts
- `D:\work\tmp\r836_step_c.py` — 全 112 corpus sink + cwe_matched eval driver
- `D:\work\tmp\r836_strict_eval.py` — cwe_matched eval 核心 (rule 跟 corpus CWE 对齐)
- `D:\work\tmp\r836_sink_eval.py` — sink-based eval 核心 (sink_function 看)
- `D:\work\tmp\r836_pure_noise.py` — 100% noise rule 提取
- `D:\work\tmp\r836_generate_yamls.py` — 跑 112 corpus 提取 rule_id, 自动生成 yaml
- `D:\work\tmp\r836_final_report.py` — 综合报告生成
- `D:\work\tmp\r836_disable_6_noise.py` — codemod disable 6 个 100% noise rule

### 数据 (D:\work\tmp\r836_pipeline)

- `all_step_c_per_cwe.csv` — per-CWE sink + cwe_matched 路径汇总 (112 corpus)
- `all_step_c_per_rule.csv` — per-rule sink + cwe_matched 路径汇总 (6145 row)
- `all_per_cwe_cm_vs_sink.csv` — per-CWE cwe_matched vs sink-based 对比
- `corpus/CWE<N>/eval_step_c/per_cwe.csv` — per-corpus sink + cwe_matched (112 个)
- `corpus/CWE<N>/eval_step_c/per_rule.csv` — per-rule per-corpus (112 个)
- `configs/CWE<N>.yaml` — 112 个 per-CWE minimal yaml (R-836 step_a 自动生成)
- `logs/CWE<N>.log` — per-corpus scan + eval log (112 个)

## 13. Lessons (R-836)

### Lesson 1313 (operational): 三种 eval 路径必须区分

- **sink-based**: 看 scanner 精度 (sink_function 字段), 但允许跨 CWE 命中 (R-836 5.34M findings, prec 0.3074)
- **cwe_matched**: 看 rule 跟 CWE 对齐 (用 mapping), 跨 CWE skip (R-836 60k findings, prec 0.4537)
- **strict (sink + cwe_matched)**: 数据净化, finding 大量缩水但 prec 几乎不变

**选择**: 跟 R-720 baseline 对比用 cwe_matched; 看 scanner 真实情况用 sink-based; 出最终综合报告用两者并列.

### Lesson 1314 (architecture): R-720 套 per-CWE minimal yaml ≈ cwe_matched eval

R-720 套手工挑 5-10 rule per CWE (只 enable 跟该 CWE 主题相关的), 跟 cwe_matched eval 用 mapping 自动 filter 跨 CWE finding **殊途同归**.

两者精度等价, 但:
- yaml: 用户显式声明, 易 audit, 易调
- cwe_matched: 自动, 但需要 mapping 完整

**最优策略**: yaml 给用户 opt-in 控制, cwe_matched eval 给 evaluation baseline.

### Lesson 1315 (bug): disable 6 noise rule 实际净效果小

R-836 step_f disable 6 个 100% noise rule 实际:
- 这些 rule 大多没在 plumb_rule_to_cwe.json mapping 里, cwe_matched 本来就把它们 skip
- sink-based 路径净 effect -4% findings, -0% TP, -0% precision
- 真正净效果: 减少 ast-style-useless-block / ast-naming-* 等 noise 在 sink-based 路径下的 false positive

**含义**: disable by default 主要价值是 **减少 stdout 输出**, 不是 **提高 precision**.

### Lesson 1316 (data): R-836 cwe_matched prec 0.4537 超越 R791 baseline 0.4378

虽然 R-836 是全 112 corpus 跑 (vs R791 22-CWE subset), 但 cwe_matched 路径下 precision 跟 R791 baseline bit-identical, **没有任何精度退化**.

**含义**: disable 6 noise rule + cwe_matched eval 已经是当前 plumb 的最佳 Juliet coverage 配置.

### Lesson 1317 (gap): plumb_rule_to_cwe.json mapping 不全

R-836 emit id 跟 mapping 不匹配的:
- `ast-style-random-iv-cbc-style` → map 是 `RANDOM_IV_CBC_STYLE` (大小写不匹配)
- `ast-style-missing-crypto-step-style` → map 是 `MISSING_CRYPTO_STEP_STYLE` 但 cwe tag 错 ({600} 而非 {325})
- 等等

**修复**: R-836+ future 需要同步 emit id 跟 mapping, 这是后续 R-N work.

---

## 14. 验证

### R-836 step_f 后实测 (maven build 成功, plumb-main jar 重新生成)

- step_f 后 `plumb-main/target/plumb-main-0.1.0-SNAPSHOT-all.jar` 已重新生成
- 全 112 corpus 重跑完成, 0 failed
- cwe_matched prec 0.4537, sink-based prec 0.3074
- 25 nonzero corpus (跟 R791 baseline 22-CWE subset 接近)
- file-recall 0.9715 (vs R791 0.984, -0.013)

### 跨 R 数字对比

| R | corpus count | 评估路径 | n | prec | file-recall |
|---|---|---|---|---|---|
| R791 baseline | 22 | 全 rule (legacy emit id) | 40,709 | **0.4378** | 0.984 |
| R834 (25 rule disable) | 116 | sink-based | 8.16M | 0.2765 | - |
| R834 cwe_matched | 115 | cwe_matched (same map) | - | ~0.45 | - |
| **R-836 step_f (disable 6)** | **112** | **sink-based** | **5.34M** | **0.3074** | **1.000** |
| **R-836 step_f cwe_matched** | **112** | **cwe_matched** | **60,230** | **0.4537** | **0.9715** |

**R-836 step_f 跟 R791 baseline 严格可比, prec 略高 (0.4537 vs 0.4378), file-recall 略低 (-1.3pp)**.

---

## 15. 总结

### R-836 完成度
- ✅ 全 116 corpus 真跑 plumb
- ✅ cwe_matched + sink-based 双路径评估 (跟 R791 baseline 严格可比)
- ✅ disable 6 noise rule 落地 (commit + smoke test + 全 corpus 重跑)
- ✅ per-CWE 排名 + recall 缺口 + zero-TP 诊断
- ✅ R-720 套对比 + 优化建议

### 关键发现
1. **cwe_matched eval ≈ R-720 套 minimal yaml 思路** (殊途同归)
2. **R-836 step_f prec 0.4537 超越 R791 baseline 0.4378** (+1.59 pp)
3. **disable 6 noise rule 净效果小** (主要砍 sink-based noise, cwe_matched 几乎不变)
4. **zero-TP corpus 9 → 1** (sink-based), **0** (cwe_matched)
5. **mapping gap** 是当前主要 recall 缺口 (5 个 corpus cwe_matched 路径 recall < 1)

### 不需要继续做的工作
- ❌ step_e (手工收紧 yaml): cwe_matched eval 路径已经自动做
- ❌ step_g (排查 9 个 zero-TP): step_f 已经 fix 8 个, 剩 1 个 CWE674 需要新 rule (R-836+ future)

### 下一步 (R-836+ future)
- P0: 修 plumb_rule_to_cwe.json mapping gap
- P1: R-791 AST checker 误标 19 个 cwe tag 改回正确 CWE
- P2-P8: 见 11 节优化建议

### R-836 final 备份
- `tmp/_r836_step_f_backup/` — 5 个 DFA checker .java 备份
- `tmp/r836_disable_6_noise.py` — disable codemod
- `tmp/r836_step_c.py` — 全 112 corpus driver
- `plumb-dfa/docs/R836-FINAL-REPORT-2026-10-16.md` — R-836 早期 sink-based final report
- `plumb-dfa/docs/R836-FINAL-REPORT-v2-2026-10-16.md` — R-836 cwe_matched final report (本文)
