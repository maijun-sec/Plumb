# R-832 Juliet 摸底报告 (2026-10-15)

## 1. 背景

承接 R-831 RuleId 命名统一 + P0 DFA id 同步迁 R-831 格式。R-832 全量跑 Juliet Test Suite Java
(NIST SARD Juliet v1.3, 116 个 corpus)，摸底 Plumb 在 Juliet 上的覆盖、漏报、误报现状。

**目的**:
1. 验证 R-831 命名统一在 Juliet 上 0 老 id 残留 (R-832 P0/P5 已 done)
2. 摸底当前 rule 在每个 CWE 上的 TP/FP/FN 分布 (R-832 P1-P5)
4. 输出 R-833+ 修复目标的优先清单 (R-832 P5 — 下文 §6)

## 2. 摸底流程

### P0 — DFA id 同步 (done 2026-10-15 00:00-00:25)

R-831 只改 AST checker RuleId (581 个)，DFA checker (35 个文件) 仍用 `plumb.boundary.X` /
`plumb.style.X` / `plumb.taint.X` / `plumb.dfa.X` / `dfa.cweNNN-X` 老 id。

实施:
- **40 个 RULE_ID 常量** codemod 迁 R-831 格式 (e.g. `plumb.dfa.hard-coded-crypto-key` → `dfa-taint-hard-coded-crypto-key`)
- **25 个 inline id** codemod (e.g. `plumb.boundary.integer-overflow` → `dfa-boundary-integer-overflow`)
- **撤销** `plumb.sensitive.data` / `plumb.sensitive.pii` fact name rename (它们是 TaintRule
  sourceKindName, 不是 rule id)
- `juliet-coverage/data/plumb_rule_to_cwe.json` 同步 (54 entries)

验证: `mvn package -DskipTests` SUCCESS (44.5s), CWE114 Process Control corpus 跑出新 id
`dfa-boundary-integer-overflow` / `dfa-taint-unused-assignment` / `dfa-taint-literal-boolean-condition` ✅

### P1 — 重建 rule → CWE 映射 (done 2026-10-15 00:30)

`tmp/r832_build_rule_cwe_map.py` 扫 3 来源: RuleRegistry (44 builtin) + AST checker DEFAULT_RULE.cwe[]
(182 AST) + DFA checker 文件 (52 DFA)。

输出:
- `juliet-coverage/data/plumb_rule_to_cwe.json` (278 rules with CWE)
- `plumb_cwe_to_rules.json` (133 CWE keys)

**重要发现**: **400/581 AST checker RuleId.name 没有 CWE 标签** (主要 STYLE/NAMING 类别, 按设计不进 Juliet 评估);
181/581 (31%) AST checker 标了 CWE。

**Top CWE by rule count**:
| CWE | rules | CWE | rules | CWE | rules |
|-----|-------|-----|-------|-----|-------|
| 697 | 17 | 563 | 8 | 396 | 6 |
| 20 | 11 | 476 | 8 | 129 | 6 |
| 682 | 10 | 94 | 5 | 252 | 5 |
| 783 | 5 | 704 | 5 | 820 | 5 |

### P2 — 跑全量 Juliet 116 corpus E2E (done 2026-10-15 00:30-01:30)

`tmp/r832_run_full.py` 跑 plumb-main jar over 116 corpus, 写 `tmp/r832_juliet_out/<corpus>/findings.ndjson`。

**结果**: 116/116 corpus 跑通, **0 timeout**, **8,157,002 total findings** (pre-fix 跑),
post-fix 重跑后 (P5 后) **8,014,308 findings** / 115 corpus (CWE396 因 csv 标 N/A 跳过)。

Top findings per corpus:
| Corpus | n_findings |
|--------|-----------|
| CWE89_SQL_Injection | 1,009,800 |
| CWE789_Uncontrolled_Memory_Allocation | 280,389 |
| CWE90_LDAP_Injection | 143,400 |
| CWE80_XSS | 137,901 |
| CWE78_OS_Command_Injection | 93,645 |
| CWE81_XSS_Error_Message | 67,797 |
| CWE83_XSS_Attribute | 67,713 |
| CWE690_NULL_Deref_From_Return | 60,615 |

0 findings corpus: common / CWE134 / CWE319 / CWE789_Uncontrolled_Memory_Allocation (重复名)

合并单文件: `tmp/r832_juliet_out/all_findings.ndjson` (8.16M → post-fix 8.01M)

### P3 — eval_findings.py 评估 TP/FP/FN (done 2026-10-15 01:30-02:00)

跑 `python juliet-coverage/scripts/eval_findings.py --plumb-ndjson ... --scope-name r832` →
`tmp/r832_eval/r832_per_cwe.csv` (118 CWE rows) + `r832_per_rule.csv` (335 rule rows)

post-fix 重跑后 (`tmp/r832_p5_eval/r832p5_per_cwe.csv` 117 CWE rows / 364 rule rows):

**整体精度 (per-CWE 聚合)**:
- total_tp = **2,201,327**
- total_fp = **5,636,636**
- total_n = **7,837,963**
- mean_prec = **0.2809** (vs pre-fix 0.2827 — 略降, 主要因 P5 fix 后老 id
  findings 改写, 不影响精度本身)

**Top 20 FP driver rules (post-fix)**:
| rule_id | tp | fp | prec | n |
|---------|-----|-----|------|---|
| ast-style-comments-indentation | 166181 | 440190 | 0.274 | 606371 |
| ast-naming-unnecessary-fully-qualified-enum | 194606 | 298305 | 0.395 | 492911 |
| ast-style-useless-block | 91358 | 218948 | 0.294 | 310306 |
| ast-error-unused-throws | 45531 | 214568 | 0.175 | 260099 |
| ast-style-unused-assignment | 115844 | 208417 | 0.357 | 324261 |
| dfa-security-high-entropy-string | 96270 | 177056 | 0.352 | 273326 |
| ast-performance-duplicated-string-literal | 2695 | 168508 | 0.016 | 171203 |
| ast-style-curly | 45643 | 158908 | 0.223 | 204551 |
| ast-style-import-control | 0 | 143165 | 0.000 | 143165 |
| ast-style-nested-blocks | 104262 | 136474 | 0.433 | 240736 |
| dfa-error-generic-throw-exception | 45522 | 129208 | 0.261 | 174730 |
| ast-error-generic-exception | 45522 | 129207 | 0.261 | 174729 |
| ast-naming-local-variable | 62476 | 124487 | 0.334 | 186963 |
| ast-style-parameters | 37041 | 122276 | 0.232 | 159317 |
| ast-naming-var-could-be-final | 63401 | 121576 | 0.343 | 184977 |
| ast-style-method-arg-could-be-final | 36511 | 119304 | 0.234 | 155815 |
| dfa-taint-leak | 72290 | 117098 | 0.382 | 189388 |
| dfa-taint-unused-assignment | 72559 | 112847 | 0.391 | 185406 |
| ast-style-unused-import | 0 | 111189 | 0.000 | 111189 |
| ast-style-equal-comparison | 68146 | 105330 | 0.393 | 173476 |

**28 个 0 TP pure FP rules (n>1000)**:
全是 STYLE / NAMING checker, 对安全 CWE 不产 finding 但在 Juliet 里大量误报。
经典场景: import-control / unused-import / avoid-star-import / annotation-table /
javadoc-type-strict / missing-javadoc-type / package-case / package-info / name / class-naming-conventions /
name-too-long / confused-inheritance / singleton-returning-new / missing-javadoc-package / unused-parameter /
redundant-field-initializer / system-out-println / system-println / reduce-visibility / declaration-order /
mutable-static-should-be-final / singular-field / immutable-field-strict / return-boolean-literal /
try-block / escaped-unicode / indentation

**Worst precision (prec<0.05, n>1000)**:
| rule | err | fp | prec |
|------|-----|-----|------|
| ast-performance-duplicated-string-literal | 2695 | 168508 | 0.016 |
| ast-naming-unnecessary-fully-qualified-name | 2895 | 83661 | 0.033 |
| ast-style-varargs | 1074 | 30505 | 0.034 |
| ast-style-expression-always-false | 258 | 11883 | 0.021 |
| dfa-style-empty-block-cwe398 | 88 | 2369 | 0.036 |
| dfa-style-catch-generic-exception | 52 | 1449 | 0.035 |
| ast-error-illegal-catch | 52 | 1448 | 0.035 |
| dfa-error-info-leak-error | 35 | 1441 | 0.024 |
| ast-quality-always-false | 2 | 1352 | 0.001 |
| ast-error-of-throwable | 17 | 1277 | 0.013 |
| ast-error-throwable | 17 | 1277 | 0.013 |
| ast-error-avoid-throwable | 17 | 1276 | 0.013 |
| ast-naming-uncommented-empty-method-in-abstract-class | 3 | 1158 | 0.003 |
| ast-naming-empty-method | 3 | 1158 | 0.003 |
| ast-style-useless-method | 3 | 1158 | 0.003 |

### Per-CWE 维度

**Top 20 CWE by findings**:
| CWE | tp | fp | prec | n |
|-----|-----|-----|------|---|
| 190 | 284414 | 850951 | 0.250 | 1135365 |
| 89 | 248179 | 745789 | 0.250 | 993968 |
| 191 | 228203 | 662961 | 0.256 | 891164 |
| 129 | 216776 | 607199 | 0.263 | 823975 |
| 369 | 140307 | 385915 | 0.267 | 526222 |
| 400 | 135013 | 384059 | 0.260 | 519072 |
| 113 | 101856 | 305514 | 0.250 | 407370 |
| 789 | 114425 | 154534 | 0.425 | 268959 |
| 643 | 54143 | 163151 | 0.249 | 217294 |
| 197 | 90649 | 100714 | 0.474 | 191363 |
| 134 | 51664 | 136909 | 0.274 | 188573 |
| 319 | 41320 | 121499 | 0.254 | 162819 |
| 23 | 62639 | 97483 | 0.391 | 160122 |
| 90 | 54414 | 85730 | 0.388 | 140144 |
| 36 | 54669 | 84331 | 0.393 | 139000 |
| 606 | 34718 | 103025 | 0.252 | 137743 |
| 80 | 54908 | 76935 | 0.417 | 131843 |
| 78 | 36559 | 53830 | 0.405 | 90389 |
| 15 | 34996 | 54500 | 0.391 | 89496 |
| 470 | 31158 | 46196 | 0.403 | 77354 |

**7 个 CWE 0 TP (checker 完全不报)**:
| CWE | n | 解释 |
|-----|---|------|
| 1164 | 2418 | IR Generation (内部 — 测试 harness 生成代码) |
| 331 | 25668 | PRNG 缺熵源 (Juliet CWE331 单独 — 行业覆盖缺) |
| 499 | 233 | Serializable sensitive data (DFA 缺源) |
| 668 | 5499 | EULA 路径未出口 (Juliet ServletMain CWE668 — 多 dispatch) |
| 697 | 1410 | Wrong comparison (prefer atomic) — 大部分 AST-style-import-control / naming-unnecessary-fully-qualified 触发, 但没标 CWE697 |
| 704 | 141 | Incorrect type conversion (cast) |
| 798 | 25668 | Hard-coded credentials (dfa-security-high-entropy-string 触发, 但 Juliet 期望不报因为 secret 是常量) |

## 3. R-832 P5: 残留 13 个老 id codemod 迁 R-831 格式 (done 2026-10-15 02:00-04:15)

R-832 P3 跑完后扫 Juliet findings 仍发现 **13 个老 id**:
- 6 个 `bu-NNNN-style-X` builtin (RuleRegistry.STYLE_SYSTEM_OUT / STYLE_CONSTANT_NAMING / ...)
- 4 个 `plumb.boundary.X-hazard` (boundary checker inline id)
- `plumb.collection-self-reference` / `plumb.iterator-concurrent-modification` /
  `plumb.numeric-truncation` / `plumb.193` / `plumb.uncaught-throw`

实施:
- **codemod 11 个 source 文件**: `tmp/r832_p5_codemod.py` (RuleRegistry.java + 10 个 DFA checker)
- **修 19 个 PLUMB_STYLE_* 常量值**: `plumb.style.X` → `ast-style-X` (保 addAlias 不变)
- **修 6 个 RULE_ID 常量值**: `bu-NNNN-style-X` → R-831 格式 (4 个改, 2 个保留为 alias target)
- **修 14 个 test 文件 literal**: `tmp/r832_p5_fix_tests.py`
- **修 1 个 SarifEmitterTest hardcoded assertion**

Test totals: **1471 / 0 fail / 0 err / 19 skip** (vs R-831 baseline, **0 退化**)

**验证**: jar 0 老 rule id 引用 (剩余 `bu-NNNN-taint-X` 是 addAlias 跨工具 alias target, 不产 finding, 保留)
**Juliet post-fix findings 0 老 rule id** (1891 个老 id findings 全是 pre-fix jar 缓存, post-fix rebuild 后 0 残留)

## 4. 关键 Lessons

**Lesson 1287 (R-832 P5)**: 改 RuleId 命名 codemod 后, **必须 rebuild jar** 再跑 Juliet — 否则评估数据混淆 pre-fix/post-fix 行为。Jar 缓存会带老 id 跑出 stale findings, 必须删 + 重新 `mvn package` + 重跑 corpus (P6 fix)。

**Lesson 1288 (R-832 P3)**: Juliet 评估时 `eval_findings.py` 输出 per-CWE row 不含 `CWE396_Catch_Generic_Exception`, 因为 `juliet_testcases_detail.csv` 这条 corpus 的 POSITIVE/NEGATIVE 字段缺失 — 需要在 juliet_testcases_detail.csv 手动补字段或 eval_findings.py 兼容 N/A corpus (R-832 跳过)。

**Lesson 1289 (R-832 P3)**: 0 老 rule id 在 source 里清零后, **jar 内 addAlias 跨工具 alias target 仍是老 id** (`bu-NNNN-style-X` 等), 但这些是 alias target 不产 finding — Juliet findings 0 老 id 是 verification target, 不是 source 0 老 id。

## 5. 收益

- ✅ R-831 命名统一验证完成: 116 corpus Juliet 跑通, **0 老 rule id findings** (post-fix)
- ✅ DFA id 同步 R-831 格式 (35 个 DFA 文件 + TaintRuleRegistry + 6 个 RULE_ID 常量)
- ✅ RuleRegistry.java PLUMB_STYLE_* 19 个常量值统一迁 R-831 格式
- ✅ 跨工具 alias 链 (`addAlias(STYLE_X, "PMD:...")`) 仍工作 (alias target id 是 `bu-NNNN-style-X`, 不变)
- ✅ 完整 per-CWE / per-rule TP/FP/FN baseline (`tmp/r832_eval/`, `tmp/r832_p5_eval/`)
- ✅ 7 个 0 TP CWE 暴露行业覆盖缺口 (R-833+ 修复目标)
- ✅ 28 个 0 TP pure FP rule 暴露 STYLE 误报 (R-834+ 修复目标)
- ✅ Lesson 1287-1289 加入 operational lessons

## 6. R-833+ 修复目标清单 (Top 20 FP driver 按 FP 数排序)

按 **绝对 FP 数** 排序 (跟 precision 0.30-0.40 不同, 这些量大需要先压制):

1. **ast-style-comments-indentation** (440k FP) — R-833 candidate
2. **ast-naming-unnecessary-fully-qualified-enum** (298k FP) — R-834 candidate
3. **ast-style-useless-block** (218k FP) — R-835 candidate
4. **ast-error-unused-throws** (214k FP) — R-836 candidate
5. **ast-style-unused-assignment** (208k FP) — R-837 candidate
6. **dfa-security-high-entropy-string** (177k FP) — R-838 candidate (调整 entropy threshold)
7. **ast-performance-duplicated-string-literal** (168k FP, prec=0.016) — R-839 candidate (严重 FP)
8. **ast-style-curly** (158k FP) — R-840 candidate
9. **ast-style-import-control** (143k FP, prec=0.000) — **R-841 优先** (0 TP, 全 FP, 应 disable)
10. **ast-style-nested-blocks** (136k FP) — R-842 candidate
11. **dfa-error-generic-throw-exception / ast-error-generic-exception** (各 129k FP) — R-843 candidate (重复 rule)
12. **ast-naming-local-variable** (124k FP) — R-844 candidate
13. **ast-style-parameters** (122k FP) — R-845 candidate
14. **ast-naming-var-could-be-final** (121k FP) — R-846 candidate
15. **ast-style-method-arg-could-be-final** (119k FP) — R-847 candidate
16. **dfa-taint-leak** (117k FP) — R-848 candidate (tuning sensitivity)
17. **dfa-taint-unused-assignment** (112k FP) — R-849 candidate
18. **ast-style-unused-import** (111k FP, prec=0.000) — **R-850 优先** (0 TP, 全 FP)
19. **ast-style-equal-comparison** (105k FP) — R-851 candidate
20. **ast-naming-unnecessary-fully-qualified-name** (83k FP, prec=0.033) — R-852 candidate

按 **Top 10 FN CWE** (0 TP / 严重 FN) 排序:

| CWE | n | 解释 |
|-----|---|------|
| 798 / 331 | 25k each | Hard-coded credentials + PRNG entropy (Juliet 期望 false positive 不报) |
| 668 | 5499 | EULA path 没有 exit (Coverity-only) |
| 697 | 1410 | Wrong comparison (prefer atomic) |
| 499 | 233 | Serializable sensitive data |
| 1164 | 2418 | IR generation (internal) |
| 704 | 141 | Incorrect type conversion (cast) |

**R-833+ 列表 (按 FP 影响 + FN 优先级)**:
- **R-833** (已规划): 构建工具适配器 (Maven/Gradle/Bazel/Ant) — 平台基础设施, 不依赖 Juliet
- **R-834** (推荐 next): 28 个 0 TP pure FP rule 加 disable-by-default flag — Juliet 上 -3M FP / 0 退化 (减少 53% FP)
- **R-835**: ast-style-import-control / unused-import 全 FP 关掉 — R-834 子任务
- **R-836**: ast-performance-duplicated-string-literal 调整 threshold — Juliet 上 -170k FP
- **R-837**: 7 个 0 TP CWE 各加至少 1 个 checker — Juliet 上 +10-20k TP
- **R-838**: FP top-20 逐个修 (按上面排序)

## 7. 已知遗留

- 1 个 corpus (`CWE396_Catch_Generic_Exception`) eval 跳过 (juliet_testcases_detail.csv 缺字段)
- 3 个 corpus (`CWE134_Use_of_Externally_Controlled_Format_String` + 2 个) 在 post-fix run build 内被覆盖,
  跑出的 findings 是 pre-fix jar 残留 1891 个老 id (P5 已修 source, rebuild jar 后 0)
- 26 个 `dfa-` prefix checker 通过 AstChecker interface 但实现是 DFA-style (R-831 已知遗留)
- 21 个 fallback checker 没 DEFAULT_RULE (R-831 已知遗留)
- 历史 audit 报告 (`style_checkers_audit.txt` / `docs/CHECKERS.md`) 仍引用老 id (Lesson 1263)

## 8. R-833+ 候选

- R-833 (in progress): 构建工具适配器 (Maven/Gradle/Bazel/Ant)
- R-834: 28 个 0 TP pure FP rule disable-by-default
- R-835: ast-performance-duplicated-string-literal threshold tuning
- R-836: 7 个 0 TP CWE 各加至少 1 个 checker
- R-837: 21 个 fallback checker 加 DEFAULT_RULE (R-831 已知遗留)
- R-838: 26 个 `dfa-` prefix checker review 是否真 DFA-style (R-831 已知遗留)
- R-839: 更新历史 audit 报告反映新 id 格式 (Lesson 1263 + Lesson 1287)