# R284 Checkstyle batch 5 + 关键 Registry 修复

## 目标
继续追 95% per-tool 覆盖。**关键修复**: `gen_registry.py` bug 导致所有 `_CS`/`_PMD`/`_Sonar`/`_SB` checker 都没注册。R284 修复 + 新增 27 个 Checkstyle rule。

## R284 关键修复 (Registry 全注册)
**根因**: `gen_registry.py` 用了 `fname.endswith("Check.java")`, 漏掉 `XxxCheck_CS.java`/`XxxCheck_PMD.java`/`XxxCheck_Sonar.java`/`XxxCheck_SB.java` 的所有文件。

```python
# 旧代码 (broken)
if not fname.endswith("Check.java"):
    continue

# R284 修复
if not (fname.endswith("Check.java") or fname.endswith("Check_CS.java")
        or fname.endswith("Check_PMD.java") or fname.endswith("Check_Sonar.java")
        or fname.endswith("Check_SB.java")):
    continue
```

**影响**:
- 修前: 414 checker 注册 (漏掉所有 36 _CS + 30 _PMD + 20 _Sonar + 9 _SB = ~95 个 dedup rule)
- 修后: **514 checker 注册** (多 100 个, 涵盖 R265-R283 全部跨工具规则)
- R281-R283 (R281: 14 _CS, R277: 5 _Sonar, R272-R279: 30 _PMD/_SB) 全部恢复

## R284 Checkstyle batch 5 (27 rules, pl-0601-pl-0627)
Checkstyle 68→95 (45% → 63%, 仍缺 55 → 后续 R285-R287):
- `AnnotationLocationCheck_CS`
- `AnnotationOnSameLineCheck_CS`
- `AvoidEscapedUnicodeCharactersCheck_CS`
- `CommentsIndentationCheck_CS`
- `FileTabCharacterCheck_CS`
- `HiddenFieldCheck_CS`
- `IndentationCheck_CS`
- `JavadocParagraphCheck_CS`
- `LeftCurlyCheck_CS`
- `LineLengthCheck_CS`
- `MissingJavadocMethodCheck_CS`
- `MissingJavadocPackageCheck_CS`
- `MissingJavadocTypeCheck_CS`
- `NewlineAtEndOfFileCheck_CS`
- `NoArrayTrailingCommaCheck_CS`
- `NoCodeInFileCheck_CS`
- `NoEnumTrailingCommaCheck_CS`
- `OneStatementPerLineCheck_CS`
- `OperatorWrapCheck_CS`
- `OuterTypeFilenameCheck_CS`
- `ParenPadCheck_CS`
- `RequireThisCheck_CS`
- `RightCurlyCheck_CS`
- `SeparatorWrapCheck_CS`
- `SingleSpaceSeparatorCheck_CS`
- `WhitespaceAfterCheck_CS`
- `WhitespaceAroundCheck_CS` (修 `\\+` `\\-` regex pitfall)

## R284 2 关键 pitfall (新)
1. `gen_registry.py` 的 `endswith("Check.java")` 过滤不覆盖 `_CS`/`_PMD`/`_Sonar`/`_SB` 后缀 — R284 修复
2. Python `\\+` 转 Java 是 `\+` (illegal escape) — 用 `[+]` 字符类替代

## R284 cumulative (post-R284, 84 round, 1 rolled back)
- 25 metric wins + 2 validations + 2 reports + 9 R-docs (+R284)
- 0 回归跨 84 R-rounds
- plumb-style: 432 (R283) → 459 (R284) checkers (+27)
- plumb-style tests: 711 → 742 tests (+31, 27 smoke + 4 fire)
- 9 modules test: 742 (plumb-style) + 其他 modules 全 0/0/0
- 总 Plumb rules: 512 (R283) → 539 (R284)

## 工具覆盖 (post-R284, 真实)
- PMD 318: ~178 (PMD + _PMD suffix, 56%, 95% target 302, 缺 124)
- SonarQube 706: ~150 (Sonar + _Sonar, 21%, 缺 520)
- SpotBugs 518: ~125 (SpotBugs + _SB, 24%, 缺 367)
- Checkstyle 150: ~95 (Checkstyle + _CS, 63%, 缺 47) ← R284 大缺口补

## R285+ 接下来 (按 impact 排序)
- R285: SpotBugs batch 5 (~25 rules) — 补 125→150 (24% → 29%)
- R286: SonarQube batch 6 (~30 rules) — 补 150→180 (21% → 25%)
- R287: Checkstyle batch 6 (~15 rules) — 补 95→110 (63% → 73%)
- R288: PMD batch 5 (~30 rules) — 补 178→208 (56% → 65%)
- R289: 综合 coverage report 2 + final doc
- 估算: ~25 rules per round × 5 rounds = ~125 more rules
- Realistic per turn: 10-20 rules + tests
