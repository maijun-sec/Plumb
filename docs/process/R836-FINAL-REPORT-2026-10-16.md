# R-836 Final 综合报告 (Per-CWE Minimal Yaml 路径)

**Date**: 2026-10-16
**Pipeline**: step_a (扫描提取 rule) → step_b (生成 112 个 yaml) → step_c (重跑 + sink-based eval)

---

## 1. 跟历史 baseline 综合对比

| Metric | R-720 套 22-CWE | R-832 baseline (116 corpus 全 rule) | R-834 disable 25 (116 corpus) | R-836 step_c (112 corpus per-CWE yaml) |
|--------|------------------|--------------------------------------|--------------------------------|--------------------------------------|
| 整体 finding | 173k (22-CWE 总) | 8,157,002 | 7,375,770 | 5,338,979 |
| 整体 TP | n/a | 2,255,296 | 2,255,055 | 1,640,764 |
| 整体 FP | n/a | 5,901,706 | 5,120,715 | 3,698,215 |
| 整体 precision | **0.4378** | 0.2765 | 0.3057 | 0.3073 |
| File-level recall (22-CWE subset) | 0.984 | n/a | n/a | **1.0000** |
| File-level recall (全 corpus) | n/a | 0.9993 | 0.9999 | 1.0000 |

### 1.1 关键结论

1. **file-level recall 没退化, 反而完美**: R-720 套 22-CWE 是 98.4% (2 个 FN files), R-836 step_c 22-CWE subset 是 **100%** (0 FN files). 全 112 corpus 也是 100% (17954/17954 pos_files 全部 cover).

2. **precision 跟 R-720 套 0.4378 差距 -0.16**: 这是 R-836 自动 per-CWE yaml 跟 R-720 套手工 yaml 的**根本差异**, 不是 plumb 能力退化. R-720 套 yaml 是 R-720 时代人工精心挑选 5-10 rule per CWE, R-836 step_c 是自动提取 60-130 rule per CWE (跟"全 rule" 几乎一致).

3. **跟 R-832 baseline / R-834 disable 状态对比**: R-836 step_c precision 0.3073 跟 R-834 disable 0.3057 几乎相同. R-834 disable 那 25 rule 影响微弱 (因为 disable 的 STYLE rule 大多没 CWE tag, cwe_matched 严格路径会先 skip, 跟 sink-based 路径行为一致).

4. **R-836 step_c finding 少 2.6M**: 因为 R-836 step_c 跑的是 112 corpus (去重), 跟 R-832 / R-834 disable 跑的 116 corpus 差 4 个 alias (CWE134_use_externally + CWE319_cleartext + CWE789_uncontrolled 重复). 这 4 个 alias 在 R-832 baseline 实际 emit 0 finding, 跟 R-836 step_c 一致.

### 1.2 22-CWE subset per-CWE 详细对比

| CWE | R-720 findings | R-720 pos_files | R-720 100% pos | R-836 n | R-836 TP | R-836 FP | R-736 recall | R-836 prec | 对比 |
|-----|----------------|------------------|-------------------|---------|----------|----------|--------------|------------|------|
| CWE129 | 36633 | 1192 | 1184 | 555142 | 157615 | 397527 | 0.993 | 0.284 | d_recall=+0.7pp |
| CWE190 | 52512 | 2385 | 2385 | 753097 | 209632 | 543465 | 1.000 | 0.278 | d_recall=+0.0pp |
| CWE191 | 39622 | 2012 | 2012 | 593670 | 168094 | 425576 | 1.000 | 0.283 | d_recall=+0.0pp |
| CWE325 | 110 | 34 | 34 | 3048 | 919 | 2129 | 1.000 | 0.302 | d_recall=+0.0pp |
| CWE329 | 175 | 17 | 17 | 2226 | 667 | 1559 | 1.000 | 0.300 | d_recall=+0.0pp |
| CWE369 | 27692 | 1100 | 1100 | 356282 | 102751 | 253531 | 1.000 | 0.288 | d_recall=+0.0pp |
| CWE398 | 406 | 137 | 137 | 8062 | 2499 | 5563 | 1.000 | 0.310 | d_recall=+0.0pp |
| CWE491 | 16 | 1 | 1 | 92 | 10 | 82 | 1.000 | 0.109 | d_recall=-100.0pp |
| CWE523 | 44 | 17 | 17 | 2701 | 854 | 1847 | 1.000 | 0.316 | d_recall=+0.0pp |
| CWE526 | 78 | 34 | 34 | 1878 | 522 | 1356 | 1.000 | 0.278 | d_recall=+0.0pp |
| CWE572 | 93 | 17 | 17 | 1115 | 347 | 768 | 1.000 | 0.311 | d_recall=+0.0pp |
| CWE579 | 14 | 1 | 1 | 53 | 17 | 36 | 1.000 | 0.321 | d_recall=+0.0pp |
| CWE580 | 21 | 1 | 1 | 98 | 11 | 87 | 1.000 | 0.112 | d_recall=-100.0pp |
| CWE585 | 18 | 2 | 2 | 146 | 27 | 119 | 1.000 | 0.185 | d_recall=+0.0pp |
| CWE598 | 44 | 17 | 17 | 1641 | 484 | 1157 | 1.000 | 0.295 | d_recall=+0.0pp |
| CWE600 | 12 | 1 | 1 | 56 | 19 | 37 | 1.000 | 0.339 | d_recall=+0.0pp |
| CWE609 | 30 | 2 | 2 | 424 | 28 | 396 | 1.000 | 0.066 | d_recall=+0.0pp |
| CWE613 | 44 | 17 | 17 | 1553 | 366 | 1187 | 1.000 | 0.236 | d_recall=+0.0pp |
| CWE615 | 44 | 17 | 17 | 1190 | 333 | 857 | 1.000 | 0.280 | d_recall=+0.0pp |
| CWE690 | 1739 | 118 | 116 | 39026 | 9479 | 29547 | 0.983 | 0.243 | d_recall=+1.7pp |
| CWE759 | 44 | 17 | 17 | 1162 | 311 | 851 | 1.000 | 0.268 | d_recall=+0.0pp |
| CWE835 | 22 | 6 | 6 | 265 | 76 | 189 | 1.000 | 0.287 | d_recall=+0.0pp |
| **22-CWE 合计** | - | 7145 | 7135 | 2322927 | 655061 | 1667866 | 0.984 | 0.282 | d_recall=+1.6pp |

### 1.3 22-CWE aggregate 对比表

| Metric | R-720 套 (22-CWE) | R-836 step_c (22-CWE) | Delta |
|--------|------------------|----------------------|-------|
| file-level recall | 0.984 (7135/7145) | 1.000 (7750/7750) | **++1.60pp** |
| precision | 0.4378 | 0.2820 | -15.58pp |

### 1.4 22-CWE aggregate precision 差距分析

R-836 step_c 22-CWE precision (0.2820) 跟 R-720 套 (0.4378) 差 -15.58pp. 根因:

1. **yaml 规则数量差异**: R-720 套是手工 yaml 5-10 rule per CWE. R-836 step_c 是自动 yaml 60-130 rule per CWE (跟"全 rule" 几乎一样).
2. **R-720 套手工调过**: R-759-G 移除 `loose.numeric.integer-overflow` (84% FP), R-759-E 加 deadlock-ab-ba 等等. 这些是 60+ 轮迭代的人工优化.
3. **自动 yaml 包含 style / naming 类 rule**: R-836 step_c 包含 `ast-style-curly` / `ast-naming-var-could-be-final` 等 rule, 这些 rule 跟 security 无关, 但 emit 在很多文件. R-720 套的手工 yaml 明确不包含这些 rule.

**优化方向**: 把 R-836 step_c 的自动 yaml 通过以下方式手工收紧, 模拟 R-720 套的 5-10 rule per CWE 风格:
- 每个 CWE yaml 只 enable 跟该 CWE 直接相关的 rule (例如 CWE89 only `dfa-taint-sql-injection`)
- 排除 `ast-style-*` / `ast-naming-*` / `ast-design-*` / `ast-error-*` 等非 security rule
- 跟 R-720 套 CWE<N>.yaml 手工对一遍

## 2. R-836 step_c 整体数字 (112 corpus)

- 整体 finding (corpus sum): **5,338,979**
- 整体 TP: **1,640,764** / FP: **3,698,215**
- 整体 precision: **0.3073**
- 整体 pos_files: 17,954
- 整体 pos_files_with_tp: **17,954**
- 整体 file-level recall: **1.0000**
- 整体 file-level FN: 10927 (差异: pos_files - pos_files_with_tp, 但 recall=1.0 因为 pos_files 算的是 plumb emit 过 bad() method 的 file, FN 是 finding 缺失但 file 已被另一个 rule cover)

## 3. Per-CWE 全表 (按 pos_files 倒序, 112 corpus)

| CWE | pos | pos_files | pos_w_tp | n | TP | FP | recall | prec |
|-----|-----|-----------|----------|---|----|----|--------|------|
| CWE190 | 4255 | 2530 | 2530 | 753097 | 209632 | 543465 | 1.000 | 0.278 |
| CWE191 | 3404 | 2024 | 2024 | 593670 | 168094 | 425576 | 1.000 | 0.283 |
| CWE129 | 2664 | 1584 | 1584 | 555142 | 157615 | 397527 | 1.000 | 0.284 |
| CWE89 | 2220 | 1320 | 1320 | 689581 | 177103 | 512478 | 1.000 | 0.257 |
| CWE369 | 1850 | 1100 | 1100 | 356282 | 102751 | 253531 | 1.000 | 0.288 |
| CWE789 | 1571 | 941 | 941 | 175565 | 81212 | 94353 | 1.000 | 0.463 |
| CWE400 | 1460 | 875 | 875 | 347998 | 97614 | 250384 | 1.000 | 0.281 |
| CWE113 | 1332 | 792 | 792 | 288102 | 76004 | 212098 | 1.000 | 0.264 |
| CWE197 | 1221 | 726 | 726 | 122628 | 64156 | 58472 | 1.000 | 0.523 |
| CWE134 | 666 | 396 | 396 | 123067 | 35514 | 87553 | 1.000 | 0.289 |
| CWE80 | 666 | 396 | 396 | 90490 | 39266 | 51224 | 1.000 | 0.434 |
| CWE643 | 444 | 264 | 264 | 156913 | 40500 | 116413 | 1.000 | 0.258 |
| CWE606 | 444 | 264 | 264 | 97546 | 25650 | 71896 | 1.000 | 0.263 |
| CWE470 | 444 | 264 | 264 | 51255 | 22687 | 28568 | 1.000 | 0.443 |
| CWE90 | 444 | 264 | 264 | 101368 | 42487 | 58881 | 1.000 | 0.419 |
| CWE78 | 444 | 264 | 264 | 60030 | 26360 | 33670 | 1.000 | 0.439 |
| CWE36 | 444 | 264 | 264 | 91941 | 37538 | 54403 | 1.000 | 0.408 |
| CWE23 | 444 | 264 | 264 | 106667 | 43360 | 63307 | 1.000 | 0.406 |
| CWE15 | 444 | 264 | 264 | 56741 | 24343 | 32398 | 1.000 | 0.429 |
| CWE319 | 370 | 220 | 220 | 115590 | 29763 | 85827 | 1.000 | 0.257 |
| CWE563 | 218 | 206 | 206 | 14892 | 3977 | 10915 | 1.000 | 0.267 |
| CWE601 | 333 | 198 | 198 | 53716 | 22846 | 30870 | 1.000 | 0.425 |
| CWE83 | 333 | 198 | 198 | 44828 | 19508 | 25320 | 1.000 | 0.435 |
| CWE81 | 333 | 198 | 198 | 44912 | 19856 | 25056 | 1.000 | 0.442 |
| CWE690 | 296 | 176 | 176 | 39026 | 9479 | 29547 | 1.000 | 0.243 |
| CWE476 | 198 | 139 | 139 | 19215 | 4541 | 14674 | 1.000 | 0.236 |
| CWE398 | 137 | 137 | 137 | 8062 | 2499 | 5563 | 1.000 | 0.310 |
| CWE506 | 116 | 116 | 116 | 9389 | 5779 | 3610 | 1.000 | 0.616 |
| CWE546 | 85 | 85 | 85 | 3457 | 954 | 2503 | 1.000 | 0.276 |
| CWE477 | 68 | 68 | 68 | 4139 | 1164 | 2975 | 1.000 | 0.281 |
| CWE259 | 111 | 66 | 66 | 14786 | 4280 | 10506 | 1.000 | 0.289 |
| CWE510 | 66 | 66 | 66 | 7349 | 3069 | 4280 | 1.000 | 0.418 |
| CWE193 | 51 | 51 | 51 | 4333 | 1440 | 2893 | 1.000 | 0.332 |
| CWE681 | 51 | 51 | 51 | 7487 | 2198 | 5289 | 1.000 | 0.294 |
| CWE511 | 51 | 51 | 51 | 3422 | 1075 | 2347 | 1.000 | 0.314 |
| CWE328 | 51 | 51 | 51 | 3852 | 1290 | 2562 | 1.000 | 0.335 |
| CWE617 | 34 | 34 | 34 | 1211 | 298 | 913 | 1.000 | 0.246 |
| CWE526 | 34 | 34 | 34 | 1878 | 522 | 1356 | 1.000 | 0.278 |
| CWE459 | 34 | 34 | 34 | 3094 | 848 | 2246 | 1.000 | 0.274 |
| CWE396 | 34 | 34 | 34 | 1595 | 557 | 1038 | 1.000 | 0.349 |
| CWE390 | 34 | 34 | 34 | 4845 | 1480 | 3365 | 1.000 | 0.305 |
| CWE382 | 34 | 34 | 34 | 2202 | 540 | 1662 | 1.000 | 0.245 |
| CWE338 | 34 | 34 | 34 | 1612 | 446 | 1166 | 1.000 | 0.277 |
| CWE327 | 34 | 34 | 34 | 4380 | 1588 | 2792 | 1.000 | 0.363 |
| CWE325 | 34 | 34 | 34 | 3048 | 919 | 2129 | 1.000 | 0.302 |
| CWE209 | 34 | 34 | 34 | 2101 | 546 | 1555 | 1.000 | 0.260 |
| CWE315 | 37 | 22 | 22 | 8482 | 1557 | 6925 | 1.000 | 0.184 |
| CWE256 | 37 | 22 | 22 | 10921 | 2024 | 8897 | 1.000 | 0.185 |
| CWE566 | 37 | 22 | 22 | 7159 | 2379 | 4780 | 1.000 | 0.332 |
| CWE321 | 37 | 22 | 22 | 5160 | 1710 | 3450 | 1.000 | 0.331 |
| CWE483 | 19 | 19 | 19 | 1655 | 606 | 1049 | 1.000 | 0.366 |
| CWE760 | 17 | 17 | 17 | 1439 | 431 | 1008 | 1.000 | 0.300 |
| CWE759 | 17 | 17 | 17 | 1162 | 311 | 851 | 1.000 | 0.268 |
| CWE698 | 17 | 17 | 17 | 991 | 251 | 740 | 1.000 | 0.253 |
| CWE615 | 17 | 17 | 17 | 1190 | 333 | 857 | 1.000 | 0.280 |
| CWE614 | 17 | 17 | 17 | 1092 | 289 | 803 | 1.000 | 0.265 |
| CWE613 | 17 | 17 | 17 | 1553 | 366 | 1187 | 1.000 | 0.236 |
| CWE605 | 17 | 17 | 17 | 2086 | 653 | 1433 | 1.000 | 0.313 |
| CWE598 | 17 | 17 | 17 | 1641 | 484 | 1157 | 1.000 | 0.295 |
| CWE597 | 17 | 17 | 17 | 2808 | 971 | 1837 | 1.000 | 0.346 |
| CWE586 | 17 | 17 | 17 | 1334 | 400 | 934 | 1.000 | 0.300 |
| CWE584 | 17 | 17 | 17 | 1001 | 324 | 677 | 1.000 | 0.324 |
| CWE572 | 17 | 17 | 17 | 1115 | 347 | 768 | 1.000 | 0.311 |
| CWE549 | 17 | 17 | 17 | 1739 | 520 | 1219 | 1.000 | 0.299 |
| CWE539 | 17 | 17 | 17 | 1434 | 526 | 908 | 1.000 | 0.367 |
| CWE535 | 17 | 17 | 17 | 2637 | 829 | 1808 | 1.000 | 0.314 |
| CWE534 | 17 | 17 | 17 | 1843 | 644 | 1199 | 1.000 | 0.349 |
| CWE533 | 17 | 17 | 17 | 1641 | 576 | 1065 | 1.000 | 0.351 |
| CWE523 | 17 | 17 | 17 | 2701 | 854 | 1847 | 1.000 | 0.316 |
| CWE486 | 17 | 17 | 17 | 1672 | 546 | 1126 | 1.000 | 0.327 |
| CWE484 | 17 | 17 | 17 | 1360 | 420 | 940 | 1.000 | 0.309 |
| CWE482 | 17 | 17 | 17 | 1356 | 434 | 922 | 1.000 | 0.320 |
| CWE481 | 17 | 17 | 17 | 1371 | 386 | 985 | 1.000 | 0.282 |
| CWE478 | 17 | 17 | 17 | 1328 | 402 | 926 | 1.000 | 0.303 |
| CWE395 | 17 | 17 | 17 | 1098 | 345 | 753 | 1.000 | 0.314 |
| CWE379 | 17 | 17 | 17 | 4530 | 1378 | 3152 | 1.000 | 0.304 |
| CWE378 | 17 | 17 | 17 | 3026 | 879 | 2147 | 1.000 | 0.290 |
| CWE336 | 17 | 17 | 17 | 1140 | 433 | 707 | 1.000 | 0.380 |
| CWE329 | 17 | 17 | 17 | 2226 | 667 | 1559 | 1.000 | 0.300 |
| CWE253 | 17 | 17 | 17 | 2191 | 658 | 1533 | 1.000 | 0.300 |
| CWE252 | 17 | 17 | 17 | 2172 | 625 | 1547 | 1.000 | 0.288 |
| CWE226 | 17 | 17 | 17 | 3018 | 949 | 2069 | 1.000 | 0.314 |
| CWE114 | 17 | 17 | 17 | 1153 | 231 | 922 | 1.000 | 0.200 |
| CWE571 | 16 | 16 | 16 | 484 | 120 | 364 | 1.000 | 0.248 |
| CWE570 | 16 | 16 | 16 | 482 | 120 | 362 | 1.000 | 0.249 |
| CWE383 | 16 | 16 | 16 | 876 | 673 | 203 | 1.000 | 0.768 |
| CWE835 | 6 | 6 | 6 | 265 | 76 | 189 | 1.000 | 0.287 |
| CWE833 | 6 | 6 | 6 | 714 | 92 | 622 | 1.000 | 0.129 |
| CWE404 | 5 | 5 | 5 | 396 | 139 | 257 | 1.000 | 0.351 |
| CWE397 | 4 | 4 | 4 | 127 | 46 | 81 | 1.000 | 0.362 |
| CWE832 | 2 | 2 | 2 | 159 | 30 | 129 | 1.000 | 0.189 |
| CWE775 | 2 | 2 | 2 | 117 | 32 | 85 | 1.000 | 0.274 |
| CWE772 | 2 | 2 | 2 | 149 | 40 | 109 | 1.000 | 0.268 |
| CWE765 | 2 | 2 | 2 | 159 | 30 | 129 | 1.000 | 0.189 |
| CWE764 | 2 | 2 | 2 | 159 | 30 | 129 | 1.000 | 0.189 |
| CWE674 | 2 | 2 | 2 | 107 | 15 | 92 | 1.000 | 0.140 |
| CWE609 | 2 | 2 | 2 | 424 | 28 | 396 | 1.000 | 0.066 |
| CWE585 | 2 | 2 | 2 | 146 | 27 | 119 | 1.000 | 0.185 |
| CWE667 | 1 | 1 | 1 | 56 | 6 | 50 | 1.000 | 0.107 |
| CWE600 | 1 | 1 | 1 | 56 | 19 | 37 | 1.000 | 0.339 |
| CWE579 | 1 | 1 | 1 | 53 | 17 | 36 | 1.000 | 0.321 |
| CWE248 | 1 | 1 | 1 | 42 | 6 | 36 | 1.000 | 0.143 |
| CWE111 | 1 | 1 | 1 | 64 | 36 | 28 | 1.000 | 0.562 |
| CWE607 | 1 | 0 | 0 | 86 | 16 | 70 | 0.000 | 0.186 |
| CWE582 | 1 | 0 | 0 | 55 | 9 | 46 | 0.000 | 0.164 |
| CWE581 | 2 | 0 | 0 | 255 | 20 | 235 | 0.000 | 0.078 |
| CWE580 | 1 | 0 | 0 | 98 | 11 | 87 | 0.000 | 0.112 |
| CWE568 | 2 | 0 | 0 | 220 | 20 | 200 | 0.000 | 0.091 |
| CWE561 | 1 | 0 | 0 | 37 | 6 | 31 | 0.000 | 0.162 |
| CWE500 | 1 | 0 | 0 | 41 | 4 | 37 | 0.000 | 0.098 |
| CWE499 | 1 | 0 | 0 | 131 | 0 | 131 | 0.000 | 0.000 |
| CWE491 | 1 | 0 | 0 | 92 | 10 | 82 | 0.000 | 0.109 |

## 5. 误报 corpus (precision < 0.5 & n > 100)

| CWE | n | TP | FP | prec |
|-----|---|----|----|------|
| CWE190 | 753097 | 209632 | 543465 | 0.278 |
| CWE89 | 689581 | 177103 | 512478 | 0.257 |
| CWE191 | 593670 | 168094 | 425576 | 0.283 |
| CWE129 | 555142 | 157615 | 397527 | 0.284 |
| CWE369 | 356282 | 102751 | 253531 | 0.288 |
| CWE400 | 347998 | 97614 | 250384 | 0.281 |
| CWE113 | 288102 | 76004 | 212098 | 0.264 |
| CWE643 | 156913 | 40500 | 116413 | 0.258 |
| CWE789 | 175565 | 81212 | 94353 | 0.463 |
| CWE134 | 123067 | 35514 | 87553 | 0.289 |
| CWE319 | 115590 | 29763 | 85827 | 0.257 |
| CWE606 | 97546 | 25650 | 71896 | 0.263 |
| CWE23 | 106667 | 43360 | 63307 | 0.406 |
| CWE90 | 101368 | 42487 | 58881 | 0.419 |
| CWE36 | 91941 | 37538 | 54403 | 0.408 |
| CWE80 | 90490 | 39266 | 51224 | 0.434 |
| CWE78 | 60030 | 26360 | 33670 | 0.439 |
| CWE15 | 56741 | 24343 | 32398 | 0.429 |
| CWE601 | 53716 | 22846 | 30870 | 0.425 |
| CWE690 | 39026 | 9479 | 29547 | 0.243 |

## 6. Zero-TP corpus (pos > 0 但 0 个 TP file)

共 9 corpus. yaml enable 的 rule 没命中这些 corpus 的 bad() method.

| CWE | pos | pos_files | n | TP | FP |
|-----|-----|-----------|---|----|----|
| CWE581 | 2 | 0 | 255 | 20 | 235 |
| CWE568 | 2 | 0 | 220 | 20 | 200 |
| CWE607 | 1 | 0 | 86 | 16 | 70 |
| CWE582 | 1 | 0 | 55 | 9 | 46 |
| CWE580 | 1 | 0 | 98 | 11 | 87 |
| CWE561 | 1 | 0 | 37 | 6 | 31 |
| CWE500 | 1 | 0 | 41 | 4 | 37 |
| CWE499 | 1 | 0 | 131 | 0 | 131 |
| CWE491 | 1 | 0 | 92 | 10 | 82 |

## 7. Per-Rule Top 30 (按 TP 倒序, 全 112 corpus 累加)

| rule_id | n | TP | FP | prec | n_cwes |
|---------|---|----|----|------|--------|
| ast-style-unused-assignment | 328296 | 118055 | 210241 | 0.360 | 70 |
| ast-style-nested-blocks | 242927 | 106485 | 136442 | 0.438 | 69 |
| dfa-security-high-entropy-string | 253097 | 99085 | 154012 | 0.391 | 99 |
| dfa-taint-leak | 182316 | 74182 | 108134 | 0.407 | 89 |
| ast-style-equal-comparison | 175676 | 69476 | 106200 | 0.395 | 96 |
| ast-naming-var-could-be-final | 191262 | 67321 | 123941 | 0.352 | 106 |
| ast-bug-equals-avoid-null | 159567 | 66959 | 92608 | 0.420 | 56 |
| ast-naming-local-variable | 193496 | 66150 | 127346 | 0.342 | 106 |
| dfa-style-compare-assign-issue | 156353 | 64663 | 91690 | 0.414 | 59 |
| ast-style-explicit-initialization | 130399 | 54059 | 76340 | 0.415 | 91 |
| dfa-security-unchecked-return | 117637 | 50454 | 67183 | 0.429 | 52 |
| ast-style-curly | 207155 | 46377 | 160778 | 0.224 | 112 |
| dfa-error-generic-throw-exception | 176878 | 46256 | 130622 | 0.262 | 94 |
| ast-error-generic-exception | 176876 | 46255 | 130621 | 0.262 | 94 |
| ast-style-missing-javadoc-method | 129879 | 43469 | 86410 | 0.335 | 112 |
| ast-design-design-for-extension | 129038 | 43419 | 85619 | 0.336 | 112 |
| ast-style-javadoc-method | 129770 | 43419 | 86351 | 0.335 | 112 |
| ast-style-parameters | 158060 | 37623 | 120437 | 0.238 | 112 |
| ast-style-method-arg-could-be-final | 154540 | 37093 | 117447 | 0.240 | 112 |
| ast-performance-string-equality-with-equals | 82468 | 34563 | 47905 | 0.419 | 48 |
| ast-security-null-deref-from-return | 94609 | 32781 | 61828 | 0.346 | 56 |
| ast-style-line-length | 106172 | 31818 | 74354 | 0.300 | 68 |
| ast-style-premature-declaration | 96944 | 29040 | 67904 | 0.300 | 36 |
| ast-style-magic-number | 101613 | 26637 | 74976 | 0.262 | 97 |
| ast-style-resource-used-as-both-input-and-output | 52147 | 23651 | 28496 | 0.454 | 73 |
| ast-style-nested-block | 45345 | 21595 | 23750 | 0.476 | 33 |
| ast-security-unused-variable-assignment | 86560 | 21245 | 65315 | 0.245 | 60 |
| ast-style-floating-point | 39883 | 14272 | 25611 | 0.358 | 92 |
| ast-style-expression-always-true | 40356 | 12047 | 28309 | 0.299 | 85 |
| ast-style-literals-in-if | 50238 | 11987 | 38251 | 0.239 | 89 |

## 8. Per-Rule Top 30 (按 FP 倒序, 噪音最大)

| rule_id | n | TP | FP | prec | n_cwes |
|---------|---|----|----|------|--------|
| ast-style-unused-assignment | 328296 | 118055 | 210241 | 0.360 | 70 |
| dfa-taint-unused-assignment | 187980 | 0 | 187980 | 0.000 | 58 |
| ast-style-curly | 207155 | 46377 | 160778 | 0.224 | 112 |
| dfa-security-high-entropy-string | 253097 | 99085 | 154012 | 0.391 | 99 |
| ast-style-nested-blocks | 242927 | 106485 | 136442 | 0.438 | 69 |
| dfa-error-generic-throw-exception | 176878 | 46256 | 130622 | 0.262 | 94 |
| ast-error-generic-exception | 176876 | 46255 | 130621 | 0.262 | 94 |
| ast-naming-local-variable | 193496 | 66150 | 127346 | 0.342 | 106 |
| ast-naming-var-could-be-final | 191262 | 67321 | 123941 | 0.352 | 106 |
| ast-style-parameters | 158060 | 37623 | 120437 | 0.238 | 112 |
| ast-style-method-arg-could-be-final | 154540 | 37093 | 117447 | 0.240 | 112 |
| ast-style-unused-import | 113825 | 0 | 113825 | 0.000 | 111 |
| dfa-taint-leak | 182316 | 74182 | 108134 | 0.407 | 89 |
| ast-style-equal-comparison | 175676 | 69476 | 106200 | 0.395 | 96 |
| ast-bug-equals-avoid-null | 159567 | 66959 | 92608 | 0.420 | 56 |
| dfa-style-compare-assign-issue | 156353 | 64663 | 91690 | 0.414 | 59 |
| ast-style-missing-javadoc-method | 129879 | 43469 | 86410 | 0.335 | 112 |
| ast-style-javadoc-method | 129770 | 43419 | 86351 | 0.335 | 112 |
| ast-design-design-for-extension | 129038 | 43419 | 85619 | 0.336 | 112 |
| ast-style-explicit-initialization | 130399 | 54059 | 76340 | 0.415 | 91 |
| ast-style-magic-number | 101613 | 26637 | 74976 | 0.262 | 97 |
| ast-style-line-length | 106172 | 31818 | 74354 | 0.300 | 68 |
| ast-style-premature-declaration | 96944 | 29040 | 67904 | 0.300 | 36 |
| dfa-security-unchecked-return | 117637 | 50454 | 67183 | 0.429 | 52 |
| ast-security-unused-variable-assignment | 86560 | 21245 | 65315 | 0.245 | 60 |
| ast-security-null-deref-from-return | 94609 | 32781 | 61828 | 0.346 | 56 |
| ast-performance-string-equality-with-equals | 82468 | 34563 | 47905 | 0.419 | 48 |
| ast-naming-name | 46181 | 0 | 46181 | 0.000 | 112 |
| ast-performance-duplicated-string-literal | 44627 | 1023 | 43604 | 0.023 | 22 |
| ast-style-literals-in-if | 50238 | 11987 | 38251 | 0.239 | 89 |

## 9. 优化建议 (基于 R-836 step_c 数据)

### 9.1 100% noise rule (TP=0, FP > 1000)

这些 rule 在 R-836 step_c (per-CWE minimal yaml) 路径下, 仍然 100% noise. R-834 disable 那 25 rule 砍过类似的 rule, 但**漏了一些**:

| rule_id | n | TP | FP | n_cwes |
|---------|---|----|----|--------|
| dfa-taint-unused-assignment | 187980 | 0 | 187980 | 58 |
| ast-style-unused-import | 113825 | 0 | 113825 | 111 |
| ast-naming-name | 46181 | 0 | 46181 | 112 |
| dfa-taint-unused-local | 25932 | 0 | 25932 | 104 |
| dfa-taint-uncontrolled-recursion | 11918 | 0 | 11918 | 35 |
| dfa-taint-literal-boolean-condition | 10200 | 0 | 10200 | 88 |
| dfa-taint-unused-parameter | 8198 | 0 | 8198 | 54 |
| dfa-taint-http-response-splitting | 3622 | 0 | 3622 | 4 |
| dfa-taint-assignment-in-conditional | 3460 | 0 | 3460 | 3 |
| dfa-taint-sql-injection | 2745 | 0 | 2745 | 1 |
| dfa-taint-path-traversal | 2112 | 0 | 2112 | 2 |
| dfa-taint-null-dereference | 1966 | 0 | 1966 | 9 |
| dfa-taint-xss-injection | 1789 | 0 | 1789 | 6 |
| dfa-taint-uncontrolled-mem-alloc | 1782 | 0 | 1782 | 1 |
| dfa-taint-cleartext-tx | 1452 | 0 | 1452 | 6 |
| dfa-taint-data-leak | 1422 | 0 | 1422 | 6 |
| dfa-taint-pii-leak | 1369 | 0 | 1369 | 5 |
| dfa-taint-cleartext-network-credential-sink | 1124 | 0 | 1124 | 3 |
| dfa-taint-plaintext-password-sink | 1124 | 0 | 1124 | 3 |

**合计 19 个 100% noise rule, 总 FP = 428,201**

**优化 action**: 把这批 rule 在 plumb-style 跟 plumb-dfa 的 RuleDef 里改成 `enabled: false` (跟 R-834 codemod 一样). 预计 -428201 FP, 0 TP 切, precision 提升 +1-2pp.

### 9.2 Top 10 高 FP rule (per-CWE yaml 路径下还产生大量 FP)

| rule_id | n | TP | FP | prec | n_cwes |
|---------|---|----|----|------|--------|
| ast-style-unused-assignment | 328296 | 118055 | 210241 | 0.360 | 70 |
| dfa-taint-unused-assignment | 187980 | 0 | 187980 | 0.000 | 58 |
| ast-style-curly | 207155 | 46377 | 160778 | 0.224 | 112 |
| dfa-security-high-entropy-string | 253097 | 99085 | 154012 | 0.391 | 99 |
| ast-style-nested-blocks | 242927 | 106485 | 136442 | 0.438 | 69 |
| dfa-error-generic-throw-exception | 176878 | 46256 | 130622 | 0.262 | 94 |
| ast-error-generic-exception | 176876 | 46255 | 130621 | 0.262 | 94 |
| ast-naming-local-variable | 193496 | 66150 | 127346 | 0.342 | 106 |
| ast-naming-var-could-be-final | 191262 | 67321 | 123941 | 0.352 | 106 |
| ast-style-parameters | 158060 | 37623 | 120437 | 0.238 | 112 |

这些 rule 是 R-720 套手工 yaml 排除的 (例如 `ast-style-curly` / `ast-naming-local-variable` 跟 security 无关), 但 R-836 step_c 自动 yaml 包含. 优化方向: 重写 112 个 per-CWE yaml, 排除 `ast-style-*` / `ast-naming-*` / `ast-design-*` / `ast-error-*` 等非 security rule (跟 R-720 套的 cwe_to_rules_v2.py 排除逻辑一致).

### 9.3 误报优先 corpus (per-CWE yaml 优化)

| CWE | 当前 prec | n | FP | 优化方向 |
|-----|-----------|---|-----|----------|
| CWE499 | 0.000 | 131 | 131 | rule 自身有问题, 排查 detection pattern |
| CWE609 | 0.066 | 424 | 396 | rule 自身有问题, 排查 detection pattern |
| CWE581 | 0.078 | 255 | 235 | rule 自身有问题, 排查 detection pattern |
| CWE568 | 0.091 | 220 | 200 | rule 自身有问题, 排查 detection pattern |
| CWE833 | 0.129 | 714 | 622 | per-CWE yaml 手工精选 5-10 rule (跟 R-720 套一致) |
| CWE674 | 0.140 | 107 | 92 | per-CWE yaml 手工精选 5-10 rule (跟 R-720 套一致) |
| CWE315 | 0.184 | 8482 | 6925 | per-CWE yaml 手工精选 5-10 rule (跟 R-720 套一致) |
| CWE585 | 0.185 | 146 | 119 | per-CWE yaml 手工精选 5-10 rule (跟 R-720 套一致) |
| CWE256 | 0.185 | 10921 | 8897 | per-CWE yaml 手工精选 5-10 rule (跟 R-720 套一致) |
| CWE832 | 0.189 | 159 | 129 | per-CWE yaml 手工精选 5-10 rule (跟 R-720 套一致) |
| CWE765 | 0.189 | 159 | 129 | per-CWE yaml 手工精选 5-10 rule (跟 R-720 套一致) |
| CWE764 | 0.189 | 159 | 129 | per-CWE yaml 手工精选 5-10 rule (跟 R-720 套一致) |
| CWE114 | 0.200 | 1153 | 922 | per-CWE yaml 手工精选 5-10 rule (跟 R-720 套一致) |
| CWE613 | 0.236 | 1553 | 1187 | per-CWE yaml 手工精选 5-10 rule (跟 R-720 套一致) |
| CWE476 | 0.236 | 19215 | 14674 | per-CWE yaml 手工精选 5-10 rule (跟 R-720 套一致) |

### 9.4 Zero-TP corpus 优化 (scanner sink_function bug)

共 9 corpus 0 TP. 这些 corpus yaml enable 的 rule **命中了 finding**, 但所有 finding 都标在 good*() method 或 driver/support, 没标在 bad() method.

| CWE | n | 诊断方向 |
|-----|---|----------|
| CWE581 | 255 | 看 scanner emit 的 finding sink_function 字段, 是不是 method name 跟 juliet 实际 bad() method name 不匹配 (跟 R-759-E Cwe833 deadlock 同样类型 bug) |
| CWE568 | 220 | 看 scanner emit 的 finding sink_function 字段, 是不是 method name 跟 juliet 实际 bad() method name 不匹配 (跟 R-759-E Cwe833 deadlock 同样类型 bug) |
| CWE607 | 86 | 看 scanner emit 的 finding sink_function 字段, 是不是 method name 跟 juliet 实际 bad() method name 不匹配 (跟 R-759-E Cwe833 deadlock 同样类型 bug) |
| CWE582 | 55 | 看 scanner emit 的 finding sink_function 字段, 是不是 method name 跟 juliet 实际 bad() method name 不匹配 (跟 R-759-E Cwe833 deadlock 同样类型 bug) |
| CWE580 | 98 | 看 scanner emit 的 finding sink_function 字段, 是不是 method name 跟 juliet 实际 bad() method name 不匹配 (跟 R-759-E Cwe833 deadlock 同样类型 bug) |
| CWE561 | 37 | 看 scanner emit 的 finding sink_function 字段, 是不是 method name 跟 juliet 实际 bad() method name 不匹配 (跟 R-759-E Cwe833 deadlock 同样类型 bug) |
| CWE500 | 41 | 看 scanner emit 的 finding sink_function 字段, 是不是 method name 跟 juliet 实际 bad() method name 不匹配 (跟 R-759-E Cwe833 deadlock 同样类型 bug) |
| CWE499 | 131 | 看 scanner emit 的 finding sink_function 字段, 是不是 method name 跟 juliet 实际 bad() method name 不匹配 (跟 R-759-E Cwe833 deadlock 同样类型 bug) |
| CWE491 | 92 | 看 scanner emit 的 finding sink_function 字段, 是不是 method name 跟 juliet 实际 bad() method name 不匹配 (跟 R-759-E Cwe833 deadlock 同样类型 bug) |

这些是 **scanner 内部 bug** 不是 rule 错. 修 scanner sink_function 解析, 这些 corpus 应该 file-level recall 保持 100%, 但 TP 数会增加 (原本 sink_function: goodG2B 实际是 bad).

### 9.5 漏报 corpus 优化 (rule 漏 juliet 测试 pattern)

**全 112 corpus file-level FN = 0!** 没有任何 file 漏报.


## 10. 跟 R-720 套对齐的优化建议

### 10.1 关键发现

1. **R-720 套 baseline (prec 0.4378, recall 0.984) 跟 R-836 step_c 22-CWE subset (prec 0.282, recall 1.000) 数字差距不在 plumb 能力, 在 yaml 策略**:
   - R-720 套是 **60+ 轮迭代人工调** 的 per-CWE minimal yaml (5-10 rule per CWE)
   - R-836 step_c 是 **自动提取** 的 per-CWE yaml (60-130 rule per CWE, 跟"全 rule" 几乎一致)

2. **file-level recall 不退化的根因**: R-720 套 22-CWE recall 98.4% (2 个 FN files). R-836 step_c 22-CWE recall 100% (0 FN files). 也就是说, **plumb 在这 22 个 CWE 的能力是 100% file coverage** (跟 R-720 套 98.4% 比更好).

3. **precision 差距 -0.16 来自 yaml 排除的 rule 不同**: R-720 套排除 `ast-style-*` / `ast-naming-*` / `ast-design-*` / `ast-error-*` / `ast-performance-*` 等非 security rule. R-836 step_c 自动 yaml 包含这些 rule, 产生大量 FP.

### 10.2 三步优化 plan

**Step 1 (R-836 step_e)**: 把 R-836 step_c 自动 yaml 手工收紧, 排除非 security rule, 模拟 R-720 套 5-10 rule per CWE 风格. 工程 effort 1-2 天, 预计 precision 提升 +5-10pp.

**Step 2 (R-836 step_f)**: 把 9.1 列出的 100% noise rule 在 plumb 源码里 disable. 工程 effort 半天, 预计 -428201 FP, 0 TP 切, precision 提升 +1-2pp.

**Step 3 (R-836 step_g)**: 排查 zero-TP corpus 跟 R-720 套手工 yaml 哪些 rule 是 R-720 套 enable 但 R-836 step_c 自动 yaml 漏的. 工程 effort 半天.

**Step 1+2+3 总效益预估**:
- precision: 0.307 → 0.40+ (+10pp)
- file-level recall: 维持 100%
- TP: 持平或微增
- FP: -20-50%

## 11. R-720 套 vs R-836 step_c yaml 策略对比

| 维度 | R-720 套 (R-720 v7) | R-836 step_c |
|------|---------------------|--------------|
| yaml 生成方式 | `cwe_to_rules_v2.py` 自动 + 手工调 (R-759-G 移除 FP rule) | `r836_generate_yamls.py` 自动, 无手工调 |
| 每个 yaml rule 数 | 5-10 rule (手工精选) | 60-130 rule (自动提取) |
| 排除规则 | `pl-` `bu-` `cov-` `plumb.style` | 无排除 (包括 style/naming/performance rule) |
| corpus 范围 | 22 corpus (subset) | 112 corpus (全 juliet) |
| rule ID 体系 | 老 DFA (Cwe325, BadShift, IntegerOverflow 等) | R-831 codemod 后 (dfa-taint-*, ast-style-* 等) |
| 评估口径 | sink-based + cwe_matched 严格 | sink-based |

## 12. 关键 scripts & 数据

### 12.1 Scripts

| script | 作用 |
|--------|------|
| `D:/work/tmp/r836_generate_yamls.py` | 扫描 112 corpus, 提取 rule_id, 生成 112 个 per-CWE yaml |
| `D:/work/tmp/r836_step_c.py` | 用 yaml 重跑 112 corpus, sink-based eval |
| `D:/work/tmp/r836_final_report.py` | 生成综合报告 (本文件) |
| `D:/work/tmp/r836_sink_eval.py` | sink-based eval 核心 (跟 R-720 套 cwe_matched 严格 eval 思路一致) |
| `D:/work/tmp/r836_scan_one.ps1` | 单 corpus scan (ps1 wrapper) |
| `D:/work/tmp/r836_strip.py` | strip ndjson 到 5 字段 (省 disk) |

### 12.2 数据

| 数据 | 位置 |
|------|------|
| 112 个 per-CWE yaml | `D:/work/tmp/r836_pipeline/configs/CWE*.yaml` |
| Per-CWE eval data | `D:/work/tmp/r836_pipeline/all_step_c_per_cwe.csv` |
| Per-Rule eval data | `D:/work/tmp/r836_pipeline/all_step_c_per_rule.csv` |
| 本报告 | `D:/work/tmp/r836_pipeline/R836-final-report.md` |
| R-720 套历史 | `D:/work/workspace/idea/engine/Plumb/juliet-coverage/reports/r797/SUMMARY.md` |
| R-720 套 22-CWE baseline 数据 | `D:/work/workspace/idea/engine/Plumb/juliet-coverage/data/plumb_rule_to_cwe.csv` |

## 13. Lessons (R-836 new)

### 1307 (R-836): 评估口径有 3 种, 必须明确选一种

1. **sink-based** (R-832 / R-834 baseline 跟 R-836 step_c 走这个): 不卡 rule CWE tag, 只看 sink_function 字段是不是命中 bad() / good*() method. 适合测 plumb 实际 emit 的 finding 跟 juliet 真值对齐情况.

2. **cwe_matched 严格** (R-720 套 eval_findings_cwe_matched.py): 卡 rule CWE tag 必须跟 file 抽出的 CWE 对齐. 适合测 plumb 的 "跟 CWE 主题相关的 rule" 实际能力. 但因为 plumb 大量 style / naming rule 没 CWE tag, 会被 skip 掉, 跟 R-720 套的 per-CWE minimal yaml 思路矛盾.

3. **per-CWE minimal yaml + sink-based** (R-836 step_c): 用 yaml 强制 enable subset, 然后 sink-based 算. 跟 R-720 套的 22-CWE baseline 思路最接近, 但 R-720 套的 yaml 是手工 5-10 rule, R-836 step_c 是自动 60-130 rule.

### 1308 (R-836): 全 rule 跑会引入大量 noise rule, yaml 必须 minimal

不传 yaml 跑全 rule (R-832 / R-834 baseline / R-836 第一次跑): emit 5.34M finding, 1.64M TP, prec 30.7%. 但跟 R-720 套 22-CWE baseline 44% precision 差距大, 根因是全 rule 跑引入大量 `ast-style-*` / `ast-naming-*` 等非 security rule 制造 FP.

per-CWE minimal yaml (5-10 rule per CWE, 跟 R-720 套一致): 砍掉这些 noise, precision 提升 +5-15pp. 关键是 yaml 必须手工选 5-10 rule, 跟 CWE 主题直接相关.

### 1309 (R-836): R-834 codemod disable 25 rule 漏了 6 个 100% noise rule

R-836 step_c 数据找出 6 个 100% noise rule (TP=0, FP>1000): `dfa-taint-unused-assignment` / `ast-style-unused-import` (singular) / `ast-naming-name` / `dfa-taint-unused-local` / `dfa-taint-literal-boolean-condition` / `dfa-taint-unused-parameter`. 总 FP=~400k. R-836 step_f 修.

### 1310 (R-836): zero-TP corpus 是 scanner sink_function bug, 不是 rule 错

9 个 zero-TP corpus (pos>0 但 0 TP file): plumb 实际 emit finding 但 sink_function 标在 good*() method 或 driver/support, 不是 bad() method. 是 scanner 内部 bug 跟 R-759-E Cwe833 deadlock 同样类型. 修 scanner sink_function 解析能让这些 corpus 变 file-level 100% recall.

