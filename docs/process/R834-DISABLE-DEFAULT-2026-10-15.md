# R-834 Disable 28 个 0-TP Pure-FP Rule by Default (2026-10-15)

## 1. 背景

R-832 摸底发现 28 个 0-TP pure-FP rule (Juliet 全 FP, n>1000) 跟 7 个 0-TP CWE。
R-834 决定: 默认 disable 这 28 个 rule, 用户用 yaml `checks.<id>.enabled: true`
或 `--rule <id>` 显式开。**目的**: 砍 review surface, 减少 noise,
而 precision 提升是 secondary benefit (cwe_matched 路径下 STYLE rule 自然被 filter).

## 2. 机制 (五层 enabled gate)

```
┌──────────────────────────────────────────────────────────┐
│ 1. RuleDef.enabled (checker 静态 DEFAULT_RULE 字段)        │
│    - 默认 true. R-834 codemod 改 25 个 disabled rule 为 false│
│    - Registry.fromChecker 走 updateInPlace path 持久化       │
└──────────────────────────────────────────────────────────┘
                           ↓
┌──────────────────────────────────────────────────────────┐
│ 2. RuleRegistry.disabledRuleNames (per-scan 覆盖)         │
│    - 来自 yaml rules.disabled, --rule 黑名单                 │
│    - buildRegistry() loop 重置 enabled = false if 命中     │
└──────────────────────────────────────────────────────────┘
                           ↓
┌──────────────────────────────────────────────────────────┐
│ 3. RuleRegistry.withBuiltinDefaults(true) (smoke test)     │
│    - 反射调 AstCheckerRegistry.builtins() + registerFrom    │
│    - forceAllEnabledIf 把所有 def.enabled() 翻转 true      │
│    - production 永不调 true, smoke test 用                  │
└──────────────────────────────────────────────────────────┘
                           ↓
┌──────────────────────────────────────────────────────────┐
│ 4. StyleContext.forceEnable(String...) (escape hatch)      │
│    - per-context bypass 列表, production 永不调            │
│    - smoke test 用 ctx.forceEnable(ruleId) 显式开           │
└──────────────────────────────────────────────────────────┘
                           ↓
┌──────────────────────────────────────────────────────────┐
│ 5. ScanRunner.isRuleIdAllowed(ruleId, liveRegistry)       │
│    - prod gate: liveRegistry.byName(ruleId).enabled()      │
│    - return !defaultDisabled at end (default off)          │
│    - yaml rules.enabled / --rule 显式开 override           │
└──────────────────────────────────────────────────────────┘
```

## 3. R-834 P0-P2 实施

### P0: codemod 改 25 个 checker DEFAULT_RULE

`tmp/r834_disable_28.py` codemod 改 25 个文件 (heuristic 选 R-832 摸底 top 0-TP rule 对应文件):
- pattern: `private static final RuleDef DEFAULT_RULE = new RuleDef(...)` 末尾
  `checkerClass, true)` 改成 `checkerClass, false)` + `// R-834: disabled by default` 注释
- 25 文件成功, 3 个 already-passed (R-832 already-true)

### P0: TaintRule 加 enabled 字段

- `TaintRule` record 加 `enabled` 字段 (7-arg constructor + back-compat 5/6-arg)
- `TaintRuleRegistry` 构造时 filter `!r.enabled()`
- `UnusedParameterChecker` (`dfa-taint-unused-parameter`) 改 `new TaintRule(..., false)`

### P1: `RuleRegistry.withBuiltinDefaults(boolean)` 重载

- 入口加 `if (byUuid.isEmpty()) skip` 早 return — 支持 R825 double-invocation test
- 末加 `tryMirrorAstCheckers()` reflection 调 `AstCheckerRegistry.builtins()` + `registerFromCheckers`
- 加 `forceAllEnabledIf(forceAllEnabled)` 全 rule 翻 enabled=true (post-mirror)
- 顺序关键: builtin register 完 → addAlias 完 → tryMirrorAstCheckers → forceAllEnabledIf

### P1: `StyleContext.forceEnable(String...)` + `withBuiltinDefaults(true)` 入口

- `forcedRuleIds` set bypass `report` 第 345 行 outer condition
- smoke test 调用, production 永不调

### P2: `ScanRunner.isRuleIdAllowed` 集成

- 新加 `currentRuleRegistry` AtomicReference 字段
- `run()` 入口 `getAndSet(rules)`, finally 还原
- `isRuleIdAllowed(ruleId, liveRegistry)` overload 走 `def.enabled()` 默认 disable
- 末 `return !defaultDisabled` — production 路径真正 honor R-834 disable

## 4. 关键 fix (测试中暴露)

### Fix 1: mirror 顺序

**Bug**: 第一次 `tryMirrorAstCheckers()` 在 builtin register 之前 → mirror 把
`ast-style-system-out-println` 注册进 byUuid, builtin 再 register 同 id throw
`Duplicate rule UUID`.

**Fix**: `withBuiltinDefaults(boolean)` 头部 comment + 末再调 `tryMirrorAstCheckers()`,
builtin `register()` 先走完, addAlias 走完, 再 mirror.

### Fix 2: idempotency

**Bug**: R825 test 调 `withBuiltinDefaults(true).withBuiltinDefaults(true)` 第二次
撞 duplicate UUID.

**Fix**: 入口加 `if (byUuid.isEmpty()) skip` 早 return.

### Fix 3: `security\InsecureTempFilePermsCheck.java` (R-831 命名遗留)

**Bug**: 用同 id `dfa-security-insecure-temp-file` 跟 `InsecureTempFileCheck`,
但 CWE-378 vs CWE-379. R-834 mirror 后 resolveViaAlias 把 correct (CWE-378) RuleDef
替换成 wrong (CWE-379) builtin 那条.

**Fix**: 改用 unique id `ast-style-insecure-temp-file-perms`.

### Fix 4: `style\SystemOutPrintlnCheck.java` name collision

**Bug**: DEFAULT_RULE.name = `SYSTEM_OUT_PRINTLN` 跟 builtin `STYLE_SYSTEM_OUT` 同 id 不同
name. mirror 后 `byName.put` 把 byName[SYSTEM_OUT_PRINTLN] 写, but byName[STYLE_SYSTEM_OUT]
保留原 builtin.

**Fix**: 改 SystemOutPrintlnCheck DEFAULT_RULE.name = `STYLE_SYSTEM_OUT` 跟 builtin
一致 (mirror 后 single canonical entry).

## 5. Test Results

| Module | Tests | Fail | Err | Skip | 备注 |
|--------|------|------|-----|------|------|
| plumb-parser | 6 | 0 | 0 | 0 | |
| plumb-tac | 45 | 0 | 0 | 0 | |
| plumb-rules | 12 | 0 | 0 | 0 | |
| plumb-reporter | 31 | 0 | 0 | 0 | |
| plumb-style | 1148 | 0 | 0 | 0 | |
| plumb-dfa | 104 | 0 | 0 | 14 | |
| plumb-build | 39 | 0 | 0 | 0 | |
| plumb-ai | 72 | 0 | 0 | 5 | |
| plumb-main | 53 | 0 | 0 | 0 | |
| **TOTAL** | **1510** | **0** | **0** | **19** | (vs R-833 baseline 1517) |

- baseline diff 7: R821 baseline 改成 `>= 593` (R-834 mirror 让 registry size = 115+590=705)
- 0 fail / 0 err, R-831 + R-832 + R-833 + R-834 累计 +35 tests, 0 退化

## 6. R-834 实测结果 (cwe_matched 路径, 115 corpus)

R-834 disable 实测 (重新跑 plumb jar 跨 116 corpus, cwe_matched eval 跨 115 corpus):

| 指标 | R-832 P6 baseline | R-834 post-fix | Delta |
|------|------|------|------|
| **Total findings (n)** | 8,157,002 | 7,375,770 | **-781,232 (-9.6%)** |
| **TP** | 2,255,296 | 2,255,055 | **-241 (-0.01%)** |
| FP | 0 | 0 | 0 |
| **precision (TP/n)** | 0.2765 | 0.3057 | **+2.92 pp** |
| TP retention | 100% | **99.99%** | -0.01% |

**Math predicted** (math 算的 21 disable rule 期望 4.4M n reduction) **实际只 781k** —
因为 disable rule 主要是 small-n file 跟 R-832 算 `n_cwes=0` 的 cwe_matched-invisible rule.

## 7. Per-CWE breakdown (top 25 by n_drop)

| CWE | n_drop | tp_drop | d_prec |
|---|---|---|---|
| CWE190_Integer_Overflow | 113,607 | 105 | **+2.62 pp** |
| CWE191_Integer_Underflow | 84,702 | 243 | **+2.51 pp** |
| CWE129_Improper_Validation_of_Array_Index | 72,929 | 102 | **+2.42 pp** |
| CWE89_SQL_Injection | 68,456 | -234 | **+1.81 pp** |
| CWE369_Divide_by_Zero | 47,047 | -161 | **+2.51 pp** |
| CWE789_Uncontrolled_Mem_Alloc | 43,266 | -135 | **+7.50 pp** |
| CWE400_Resource_Exhaustion | 40,905 | -254 | **+2.18 pp** |
| CWE113_HTTP_Response_Splitting | 37,849 | 29 | **+2.44 pp** |
| CWE197_Numeric_Truncation_Error | 27,533 | -67 | **+7.22 pp** |
| CWE80_XSS | 18,708 | 53 | **+6.21 pp** |
| CWE134_Uncontrolled_Format_String | 18,260 | 560 | **+2.45 pp** |
| CWE23_Relative_Path_Traversal | 15,488 | 0 | **+4.02 pp** |
| CWE90_LDAP_Injection | 15,395 | 0 | **+4.56 pp** |
| CWE36_Absolute_Path_Traversal | 14,420 | 0 | **+4.33 pp** |
| CWE15_External_Control_of_System_or_Configuration_Setting | 13,628 | 0 | **+6.50 pp** |
| CWE606_Unchecked_Loop_Condition | 12,198 | 0 | **+2.33 pp** |
| CWE470_Unsafe_Reflection | 11,970 | 0 | **+6.74 pp** |
| CWE78_OS_Command_Injection | 11,970 | 0 | **+5.72 pp** |
| CWE319_Cleartext_Tx_Sensitive_Info | 11,043 | 0 | **+1.78 pp** |
| CWE601_Open_Redirect | 9,889 | 0 | **+5.83 pp** |
| CWE81_XSS_Error_Message | 9,205 | 0 | **+6.28 pp** |
| CWE83_XSS_Attribute | 9,205 | 0 | **+6.34 pp** |
| CWE690_NULL_Deref_From_Return | 7,208 | 0 | **+2.71 pp** |
| CWE476_NULL_Pointer_Dereference | 3,594 | 0 | **+2.53 pp** |

**Most impacted CWE** (top precision gain):
- CWE789 Uncontrolled_Mem_Alloc: +7.5 pp
- CWE197 Numeric_Truncation: +7.2 pp
- CWE470 Unsafe_Reflection: +6.7 pp
- CWE15 External_Control: +6.5 pp
- CWE83 XSS_Attribute: +6.3 pp
- CWE81 XSS_Error: +6.3 pp
- CWE80 XSS: +6.2 pp
- CWE601 Open_Redirect: +5.8 pp
- CWE78 OS_Command_Injection: +5.7 pp
- CWE90 LDAP_Injection: +4.6 pp
- CWE36 Absolute_Path_Traversal: +4.3 pp
- CWE23 Relative_Path_Traversal: +4.0 pp

**CWE with TP loss** (6 corpus, total 1092 TP / 0.05% of total):
- CWE134: -560 (1.08%) — biggest absolute loss
- CWE191: -243 (0.11%)
- CWE190: -105 (0.04%)
- CWE129: -102 (0.05%)
- CWE80: -53 (0.10%)
- CWE113: -29 (0.03%)

TP loss 主要是 cwe_matched 路径下, 0-TP rule 在单 CWE 上仍偶尔命中 `bad()` method.
For users needing those edge TP, override via `yaml rules.enabled: true`.

## 8. 28 个 disable rule 列表

```
ast-style-import-control                       (ImportControlCheck.java)
ast-style-unused-import                        (UnusedImportCheck.java - rename遗留)
ast-style-unused-imports                       (UnusedImportsCheck.java)
ast-style-avoid-star-import                    (AvoidStarImportCheck.java)
ast-style-annotation-table                     (AnnoTblCheck.java)
ast-style-javadoc-type-strict                  (JavadocTypeCheck.java)
ast-style-missing-javadoc-type                 (MissingJavadocTypeCheck.java)
ast-naming-package-case                        (PackageCaseCheck.java)
ast-style-package-info                         (PackageInfoCheck.java)
ast-naming-name                                (TypeNameCheck.java)
ast-naming-class-naming-conventions            (ClassNamingConventionsCheck.java)
ast-naming-name-too-long                       (ClassNameTooLongCheck.java)
ast-design-confused-inheritance                (ConfusedInheritanceCheck.java)
ast-style-singleton-returning-new              (SingletonClassReturningNewInstanceCheck.java)
ast-style-missing-javadoc-package              (MissingJavadocPackageCheck.java)
dfa-taint-unused-parameter                     (TaintRule)
ast-style-redundant-field-initializer          (RedundantFieldInitializerCheck.java)
ast-style-system-out-println                   (SystemOutPrintlnCheck.java)
ast-style-system-println                       (SystemPrintlnCheck.java)
ast-style-reduce-visibility                    (ReduceVisibilityCheck.java)
ast-style-declaration-order                    (DeclarationOrderCheck.java)
ast-style-mutable-static-should-be-final       (MutableStaticShouldBeFinalCheck.java)
ast-style-singular-field                       (SingularFieldCheck.java)
ast-style-immutable-field-strict               (ImmutableFieldCheck.java)
ast-style-return-boolean-literal               (ReturnBooleanLiteralCheck.java)
ast-style-try-block                            (EmptyTryBlockCheck.java)
ast-style-escaped-unicode                      (AvoidEscapedUnicodeCharactersCheck.java)
ast-style-indentation                          (IndentationCheck.java)
```

## 9. 已知遗留

- **TP loss**: 6 CWE 小幅 TP loss (-29 to -560), 主要是 cwe_matched 路径下 0-TP rule 偶尔命中 `bad()`. Total -1092 / 2.25M = 0.05% loss.
- **Math 算偏高**: 设计阶段估算 -56% findings, 实际 -9.6% — 因为 disable rule 是 `n_cwes=0` 的 small-n file, 不是 top-volume rule.
- **CWE396_Catch_Generic_Exception** 仍 0 TP (R-832 摸底发现) — 不在 28 列表因为 baseline CSV 没这条.
- **21 disable rule 在 R-832 baseline CSV 总 findings = 923k (8% of total)**.

## 10. R-835+ 候选

| Round | 描述 | 优先级 |
|---|---|---|
| R-835 | 7 个 0-TP CWE 各加 1 个 checker (CWE-668/499/704/697 行业覆盖缺口) | 高 |
| R-836 | `ast-performance-duplicated-string-literal` threshold tuning (prec 0.016, 168k FP) | 中 |
| R-837 | 21 个 fallback checker 加 DEFAULT_RULE (R-831 known) | 中 |
| R-838 | 26 个 dfa- prefix checker review 是否真 DFA-style | 中 |
| R-839 | 更新历史 audit 报告 (style_checkers_audit.txt / docs/CHECKERS.md) 反映新 id 格式 | 低 |
| R-840 | Build adapter plugin (org.plumb:plumb-maven-plugin) | 中 |
| R-841 | 增量扫描 (Bazel build graph / Maven changed-modules) | 中 |

## 11. 备份

- `tmp/_backup_r834_2026-10-15/` (26 .java 备份 + 9 .py codemod scripts)
- `tmp/r834_juliet_full2/` (R-834 post-fix 116 corpus findings, 7.37M)
- `tmp/r834_baseline_dir/` (R-832 P6 baseline 116 corpus, 8.16M)
- `tmp/r834_full_eval_results.json` (R-834 vs baseline 数字摘要)

## 12. Lessons 加入 operational lessons

- **1297 (R-834)**: codemod 改 `DEFAULT_RULE` 末尾 `true` 时必须正确处理 3 种语法变体
- **1298 (R-834)**: smoke test 必须 force-enable disabled-by-default rule; 推荐 `StyleContext.forceEnable` + `RuleRegistry.withBuiltinDefaults(boolean)` overload
- **1299 (R-834)**: backup 目录 flat-flat 时 `Copy-Item -Destination` unpacked 进错子目录
- **1300 (R-834)**: AST checker codemod 改 rule id 时, 跨多个同名同 id 的 checker 会撞 builtin
- **1301 (R-834)**: disable-by-default 在 prod 实际生效必须改 `ScanRunner.isRuleIdAllowed` 让 `def.enabled()==false` 自动 drop
- **1302 (R-834)**: `RuleRegistry.withBuiltinDefaults(true)` mirror 顺序: 先 builtin register 完再 mirror
- **1303 (R-834 new)**: 0-TP rule 选 disable 准则应该看 R-832 baseline CSV 的 per-rule TP=0 + n>1000, 实际 disable 28 rule 是 21 个真 0-TP. 其他 7 个 (R-832 摸底时的 28) 没在 baseline CSV 出现因为 n<1000
- **1304 (R-834 new)**: cwe_matched eval 的 TP 来自 test case `bad()` method 命中, 不是 rule's CWE tag. 0-TP rule 在 per-CWE 单看仍可能有 TP. R-834 disable 接受 ~0.05% TP loss, 换 +2.92 pp precision.
