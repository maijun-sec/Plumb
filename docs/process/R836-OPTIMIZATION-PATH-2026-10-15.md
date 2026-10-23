# R-836 数据驱动优化路径 (2026-10-15)

## 1. 整体背景

R-834 之后状态:
- baseline (R-832): TP 2,255,296 / n 8,157,002 / **precision 27.65%**
- R-834: TP 2,255,055 / n 7,375,770 / **precision 30.57%** (+2.92 pp, TP retention 99.99%)
- R-835 P2 (5 disable + threshold): rolled back — 5 disable 砍 TP 25%, 没赢 precision

R-836 任务: 给每个 Juliet corpus 列正/反例 + 正报/误报/漏报, 综合排序, 给明确优化方向.

## 2. 每个 corpus 的正/反例 + 正报/误报/漏报

数据来源:
- `tmp/r835_per_corpus_eval.csv` (115 corpus baseline vs R-834, sink-based 口径 — 真 plumb 命中)
- `tmp/r834_full_eval_results.json` (cwe_matched 严格口径: file CWE + rule CWE + bad method)

### 2.1 cwe_matched 严格路径 — 4 个永远 0 TP corpus (baseline + R834 都 miss)

| CWE | pos_examples | neg_examples | R834 TP | R834 FP |
|-----|--------------|--------------|---------|---------|
| **CWE789_Uncontrolled_Memory_Allocation** | 1571 | 941 | **0** | 0 |
| **CWE134_Use_of_Externally_Controlled_Format_String** | 666 | 432 | **0** | 0 |
| **CWE319_Cleartext_Transmission** | 370 | 240 | **0** | 0 |
| **CWE499_Sensitive_Data_Serializable** | 1 | 1 | **0** | 738 |

这 4 个 **R-836 NEW_RULE 候选**, plumb 一直缺. R-834 没切它们, 因为根本没命中.

### 2.2 综合 ranking (sink-based 口径) — Top 30 高潜 corpus

| Rank | CWE | pos | n | TP | FP | recall% | prec | score |
|------|-----|-----|---|----|----|---------|------|-------|
| 1 | CWE506_Embedded_Malicious_Code | 116 | 13656 | 7516 | 6140 | 64.8 | 0.550 | 0.995 |
| 2 | CWE383_Direct_Use_of_Threads | 16 | 1655 | 758 | 897 | 47.4 | 0.458 | 0.937 |
| 3 | CWE510_Trapdoor | 66 | 10498 | 3947 | 6551 | 59.8 | 0.376 | 0.931 |
| 4 | CWE327_Use_Broken_Crypto | 34 | 5972 | 1778 | 4194 | 52.3 | 0.298 | 0.886 |
| 5 | CWE597_Wrong_Operator_String_Comparison | 17 | 4730 | 1336 | 3394 | 78.6 | 0.282 | 0.876 |
| 6 | CWE23_Relative_Path_Traversal | 444 | 147890 | 31010 | 116880 | 69.8 | 0.210 | 0.874 |
| 7 | CWE90_LDAP_Injection | 444 | 128005 | 27120 | 100885 | 61.1 | 0.212 | 0.874 |
| 8 | CWE379_Temporary_File_Creation_in_Insecure_Dir | 17 | 7438 | 1980 | 5458 | 116.5 | 0.266 | 0.872 |
| 9 | CWE36_Absolute_Path_Traversal | 444 | 127836 | 26700 | 101136 | 60.1 | 0.209 | 0.872 |
| 10 | CWE681_Incorrect_Conversion_Between_Numeric_Types | 51 | 11207 | 2880 | 8327 | 56.5 | 0.257 | 0.872 |
| 11 | CWE390_Error_Without_Action | 34 | 7271 | 1894 | 5377 | 55.7 | 0.260 | 0.869 |
| 12 | CWE226_Sensitive_Information_Uncleared_Before_Release | 17 | 4859 | 1266 | 3593 | 74.5 | 0.261 | 0.865 |
| 13 | CWE535_Info_Exposure_Shell_Error | 17 | 3972 | 985 | 2987 | 57.9 | 0.248 | 0.857 |
| 14 | CWE89_SQL_Injection | 2220 | 941344 | 123546 | 817798 | 55.7 | 0.131 | 0.853 |
| 15 | CWE378_Temporary_File_Creation_With_Insecure_Perms | 17 | 5054 | 1185 | 3869 | 69.7 | 0.234 | 0.853 |
| ... | (继续看 r836_per_corpus_ranked.csv) |
| 30 | CWE789_Uncontrolled_Mem_Alloc (alias) | 1571 | 237123 | 52618 | 184505 | 33.5 | 0.222 | 0.786 |

> 注: CWE789 有两条 alias, "Uncontrolled_Mem_Alloc" 在 sink-based 路径下 52618 TP, 但 cwe_matched 严格路径下 0 TP — 这是 Juliet 测试集 ID 命名分歧.

### 2.3 Recall < 30% 的高潜力 corpus (有大量 pos 但 recall 低, 17 个)

| CWE | pos | TP | FP | recall% | prec |
|-----|-----|----|----|---------|------|
| CWE690_NULL_Deref_From_Return | 296 | 5552 | 47855 | 18.8 | 0.104 |
| CWE563_Unused_Variable | 218 | 5167 | 17362 | 23.7 | 0.229 |
| CWE476_NULL_Pointer_Dereference | 198 | 3325 | 24219 | 16.8 | 0.121 |
| CWE398_Poor_Code_Quality | 137 | 2864 | 8903 | 20.9 | 0.243 |
| CWE259_Hard_Coded_Password | 111 | 2617 | 17855 | 23.6 | 0.128 |
| CWE546_Suspicious_Comment | 85 | 1319 | 4850 | 15.5 | 0.214 |
| CWE477_Obsolete_Functions | 68 | 1501 | 5115 | 22.1 | 0.227 |
| CWE328_Reversible_One_Way_Hash | 51 | 1470 | 4298 | 28.8 | 0.255 |
| CWE511_Logic_Time_Bomb | 51 | 1282 | 3973 | 25.1 | 0.244 |
| CWE315_Plaintext_Storage_in_Cookie | 37 | 871 | 9900 | 23.5 | 0.081 |
| CWE321_Hard_Coded_Cryptographic_Key | 37 | 1011 | 6386 | 27.3 | 0.137 |
| CWE209_Information_Leak_Error | 34 | 656 | 2755 | 19.3 | 0.192 |
| CWE338_Weak_PRNG | 34 | 540 | 2332 | 15.9 | 0.188 |
| CWE382_Use_of_System_Exit | 34 | 650 | 2851 | 19.1 | 0.186 |
| CWE396_Catch_Generic_Exception | 34 | 740 | 2412 | 21.8 | 0.235 |
| CWE526_Info_Exposure_Environment_Variables | 34 | 632 | 2556 | 18.6 | 0.198 |
| CWE617_Reachable_Assertion | 34 | 426 | 2166 | 12.5 | 0.164 |

### 2.4 Precision < 0.15 大 corpus (噪音过多, 23 corpus, 总 n=5.77M)

top by n:

| CWE | n | TP | FP | prec |
|-----|---|----|----|------|
| CWE190_Integer_Overflow | 1052039 | 140491 | 911548 | 0.134 |
| CWE89_SQL_Injection | 941344 | 123546 | 817798 | 0.131 |
| CWE191_Integer_Underflow | 830502 | 112923 | 717579 | 0.136 |
| CWE129_Improper_Validation_of_Array_Index | 770001 | 106825 | 663176 | 0.139 |
| CWE369_Divide_by_Zero | 492699 | 70249 | 422450 | 0.143 |
| CWE400_Resource_Exhaustion | 488892 | 69102 | 419790 | 0.141 |
| CWE113_HTTP_Response_Splitting | 378300 | 48281 | 330019 | 0.128 |

这些 corpus 的现有 rule 大量 FP. 走 disable-by-default 不行 (R-835 P2 实证切 TP). 应该走 per-CWE rule tuning — 加 whitelist (framework code / Juliet 的 utility method / console I/O).

### 2.5 TP-loss 分析 (R-834 disable 切的 6 个 CWE, 累计 -1092 TP)

| CWE | TP-loss | 主要 rule | bad_count delta |
|-----|---------|-----------|------------------|
| CWE134 | -560 | ast-style-expression-always-true | -8 |
| CWE191 | -243 | ast-naming-local-variable | -266 |
| CWE190 | -105 | ast-naming-local-variable | -64 |
| CWE129 | -102 | ast-naming-local-variable | -100 |
| CWE80 | -53 | ast-style-equal-comparison | -21 |
| CWE113 | -29 | ast-naming-local-variable | -74 |

**关键发现**: TP-loss 都是 STYLE/NAMING rule 在 bad() method 命中 — 这些 rule 的检测能力跟 security 无关, 是命名规范碰巧在 bad() 里被触发. 不能简单回滚 (1092 TP / 2.25M TP = 0.05%).

## 3. Per-rule "bad ratio" 分析 (R-836 关键)

每个 rule 的 finding 落在 Juliet `bad()` method 的比率 — 这个值高, 表示这 rule 实际能产生真 TP (不是 pure noise).

| rule_id | n | bad | good | other | **bad%** | good% | 评级 |
|---------|---|-----|------|-------|---------|-------|------|
| ast-style-unused-formal-parameter | 18639 | **0** | 15079 | 3560 | **0.0%** | 80.9% | **纯 noise** |
| ast-style-expression-always-false | 12318 | 43 | 12042 | 233 | **0.3%** | 97.8% | **纯 noise** |
| ast-performance-duplicated-string-literal | 173019 | 2619 | 168030 | 2370 | **1.5%** | 97.1% | 接近纯 noise |
| ast-design-collapsible-if-statements | 6624 | 1464 | 5039 | 121 | 22.1% | 76.1% | 真 TP 价值 |
| ast-style-curly | 207155 | 28930 | 128135 | 50090 | 14.0% | 61.9% | 真 TP 价值 |
| ast-style-comments-indentation | 612369 | 142529 | 299911 | 169929 | 23.3% | 49.0% | 真 TP 价值 |
| dfa-security-unchecked-return | 117637 | 42056 | 66418 | 9163 | **35.8%** | 56.5% | **真定位能力** |
| ast-style-unused-assignment | 328296 | 103889 | 209135 | 15272 | 31.6% | 63.7% | 真定位能力 |

**判定准则**:
- bad% < 5% + n>1000: **disable 收益高**, 切真 TP 风险低 (R-835 P2 没选对候选的教训)
- bad% 5-20%: 有 TP 价值, 不能单独 disable
- bad% > 20%: 真定位能力, 必有保留

## 4. R-836 优化路径决策

### A. **NEW_RULE** (3 个 CWE, plumb 完全 0 TP) — R-837 候选

| 优先级 | CWE | pos | 现有 rule | 缺失能力 |
|--------|-----|-----|-----------|---------|
| ★★★ | CWE134_Use_of_Externally_Controlled_Format_String | 666 | 无 | 跟踪 format() / printf() 的第一个参数是否源自外部输入 (network / file / argv) |
| ★★ | CWE789_Uncontrolled_Memory_Allocation | 1571 | UnboundedMemAllocCheck | 加 source: console.readLine() / System.in / Integer.parseInt 传播链 + 加 integer-driven loop allocation 检测 |
| ★ | CWE319_CleartextTransmission | 370 | CleartextSocketUsageCheck | 加 HttpURLConnection (plain http) + URL.openStream() 调用链 + SSL handshake 缺失检测 |

**预期效益**: +2000-3000 TP, +0.5pp precision (因为 n 也增, 比例不均).

### B. **PER_RULE_THRESHOLD_TUNING** (bad% < 5% 的 rule) — R-838 候选

候选 (基于 cwe_matched-aware simulation 而不是 raw n):
1. **ast-style-unused-formal-parameter** (n=18639, bad=0, good=15079, bad%=0.0%) — disable 零切 TP, FP -15079
2. **ast-style-expression-always-false** (n=12318, bad=43, good=12042) — disable 切 43 TP, FP -12042
3. **ast-performance-duplicated-string-literal** (n=173019, bad=2619, good=168030) — 已有 R-835 P2 threshold, 加 bad%>0.5% 也不 disable — 跟 #1, #2 一起 disable 看是否仍切 TP

**预期效益**: 如果 disable #1, #2, FP -27000+, TP 切最多 ~50. **对比 R-835 P2 失败的 5 disable**: 那 5 条 bad_ratio 是 14-37%, 切真 TP; 这 2 条 bad_ratio 是 0-0.3%, 几乎不切.

### C. **PER_CWE_TUNING** (prec < 0.15 的 7 个大 corpus) — R-839 候选

加 per-CWE rule whitelist (例如 `plumb-dfa/src/main/resources/checks/cwe-specific/`):
- `cwe-190-rules.yaml`: 关掉 ast-style-curly / ast-style-line-length / ast-style-magic-number (这些在 CWE190 测试里大量 FP)
- `cwe-89-rules.yaml`: 同理
- ...

**预期效益**: 直接降 FP, precision 个位数 pp 提升; 不会切 TP 因为关掉的都是 CWE 这个 corpus 里没用的 rule.

### D. **RULE_RECALL_TUNING** (recall < 30% 的 17 个 corpus) — R-840 候选

加 sink identification / 加 integer-overflow Z3 约束求解:
- CWE690 (rec 18.8%) — 现有 NullDerefFromReturn 没 catch 通过调用链返回 null 的模式
- CWE476 (rec 16.8%) — NullPointerDereference 需加 `Optional.empty().get()` 模式
- CWE563 (rec 23.7%) — UnusedVariable 需加 catch (Exception e) 形式
- CWE259 (rec 23.6%) — HardcodedPassword 需加 `String pass = getProperty("pass")` 这种 secret 在 Java property 的形式

**预期效益**: recall 从 18-30% 到 40-60%, +5000-15000 TP (跟 disable-by-default 收益对比, 这才是真增量).

### E. **不做的方向**

❌ **继续 disable-by-default**: R-835 P2 实证证明会切 TP. R-834 那 28 个已是边界.
❌ **改 CWE tag**: 改 rule CWE tag 可能把 bad() finding 转成 cwe_matched TP, 但会引入"假阳性" (rule 报 X CWE 但 issue 实际是 Y). ROI 不高.

## 5. 推荐顺序 (按 R-836 综合 score + 工程 effort)

| Round | 任务 | Effort | 预期效益 | 风险 |
|-------|------|--------|----------|------|
| **R-837** | disable 2 个 100% noise rule (ast-style-unused-formal-parameter + ast-style-expression-always-false) | 30 min | TP -50 / FP -27000 / prec +0.3pp | 极低, bad%<0.5% 实证 |
| **R-838** | 加 3 个 NEW_RULE (CWE134 ext format string + CWE789 source 增强 + CWE319 URL detection) | 2-3 天 | TP +2000-3000 / FP ~0 / prec +0.5pp | 中, 需 Juliet unit test |
| **R-839** | per-CWE rule whitelist (7 个 prec<0.15 corpus) | 1 天 | TP 0 / FP -200000 / prec +1.0pp | 低, per-corpus yaml |
| **R-840** | 加 6 个高潜 corpus 的 sink/source 增强 (CWE690/476/563/259/315/135) | 2-3 周 | TP +5000-15000 / FP ~0 / prec +0pp | 高, 需 DFA Z3 工作 |

R-837 立即可上, R-838 紧接着. **R-837+R-838 总效益: prec +0.8pp, TP retention 100%, FP -27000, 工程 effort 2-3 天**.

## 6. Scripts 跟中间数据

- `tmp/r836_analyze.py` — 主分析入口, 输出 `tmp/r836_per_corpus_ranked.csv` (115 corpus 全 sorted by composite score)
- `tmp/r836_optimization_plan.py` — 4 维度分类输出
- `tmp/r836_tp_loss_analysis.py` — 6 个 TP-loss CWE 的具体 rule 来源
- `tmp/r836_rule_analysis.py` — per-rule bad ratio 分析
- `tmp/r836_per_corpus_ranked.csv` — 综合 ranking 完整数据
- `tmp/r835_per_corpus_eval.csv` — per-corpus baseline vs R-834 原始数据
- `tmp/r834_full_eval_results.json` — cwe_matched 严格 eval

## 7. Lesson 1307 (R-836 new)

- **bad% 才是 disable 的真正判据, 不是 n/precision**: R-835 P2 失败因为用 n+precision 选 disable 候选, 但没看 bad%. R-837 候选必须 bad% < 1% 才安全. 实际 `ast-style-unused-formal-parameter` / `ast-style-expression-always-false` 这两条才是 disable 安全候选, 因为 bad%<0.5%.
- **per-corpus precision < 0.15 跟 per-rule precision < 0.15 是不同问题**: corpus 级低 precision 通常是 rule whitelist 没设, 不是 rule 本身差; rule 级低 precision 是 rule 自身 logic 问題. R-839 跟 R-837-840 是不同 workflow.
- **NEW_RULE 加在 cwe_matched 严格 miss 的 CWE 才有新 TP 价值**: 加 rule 帮已经能捕到的 CWE (例如 CWE78) 是没意义的; 加 rule 给 cwe_matched 0 TP 的 CWE (CWE134_ext/CWE789_mem/CWE319_url) 才有 incremental.
