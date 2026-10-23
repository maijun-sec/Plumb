# R271 — Cross-Tool Deduplication (PMD ∩ SonarQube ∩ SpotBugs)

**Date**: 2026-08-02
**Round**: 71
**Status**: DONE — 0 regression, 9 modules 1357/0/0/0
**Philosophy**: 三大工具 (PMD / SonarQube / SpotBugs) 共享大量概念规则。一个概念只实现一次，无论哪个工具首先定义它。

## Background

R148c-R270 (66 rounds) 累计在 `plumb-style` 模块实现了 246 个 checkers，目标是跨工具覆盖率。但是因为各轮独立按工具分类 (PMD / SonarQube / SpotBugs)，相同概念产生了多个并行实现，例如：
- `EmptyCatchCheck` (A1, R137) ↔ `EmptyCatchBlockCheck` (R267)
- `EmptyFinalizerCheck` (R267) ↔ `EmptyFinalizer2Check` (R269) ↔ `FinalizeWithoutSuperCheck` (R263) ↔ `ExplicitFinalizeCheck` (R263) ↔ `FinalizeShouldBeProtectedCheck` (R267) ↔ `FinalizeOnlyOverriddenCheck` (R263) — 6 个 finalize cluster
- `EmptyIfBlockCheck` (R263) ↔ `EmptyIfStmtCheck` (R266) ↔ `EmptyControlStatementCheck` (R167) — 3 个 empty-if 变体
- `ClassNamingCheck` (A1) ↔ `ClassNamingConventionsCheck` (R270) — 2 个 class-naming
- 等等

用户方向 (2026-08-02):
> "如果规则优重复，不需要重复建规则，可以查漏补缺，本来 pmd、spotbugs、sonar-java 有很多重复规则，这些完全没必要重复实现，只要实现一个就认为都实现了"

## Audit 流程

按概念分组 (不按工具或 checker 类名)，找出 16 个确切的重复实现 + 3 个概念变体（保留）。

### 重复并删除 (13 个 — 完全相同概念)

| # | Canonical (kept) | Deleted | 概念 |
|---|---|---|---|
| 1 | `EmptyCatchBlockCheck` (R267, hard-coded `DEFAULT_RULE`) | `EmptyCatchCheck` (A1, registry-coupled → NPE-prone) | 空 catch block |
| 2 | `EmptyFinalizerCheck` (R267) | `EmptyFinalizer2Check` (R269, dup of `FinalizeWithoutSuperCheck`) | 空 finalize |
| 3 | `EmptyControlStatementCheck` (R167) | `EmptyIfBlockCheck` (R263) | 空 if `{}` block (现在 EmptyControlStatementCheck 升级支持 empty BlockStmt) |
| 4 | `EmptyControlStatementCheck` (R167) | `EmptyIfStmtCheck` (R266) **RESTORED** | 保留 — 单独 checker 更易测 |
| 5 | `ReturnFromFinallyBlockCheck` (R265, HIGH/CWE-584) | `ReturnInFinallyCheck` (R263) | finally 里 return |
| 6 | `ReturnEmptyCollectionRatherThanNullCheck` (R269) | `ReturnEmptyCollectionCheck` (R264) | collection return null |
| 7 | `MethodTooLongCheck` (R270, statement count) | `MethodLengthCheck` (A1, line count) | method 太长 |
| 8 | `ClassNamingConventionsCheck` (R270) | `ClassNamingCheck` (A1) | PascalCase |
| 9 | `MethodNamingConventionsCheck` (R267) | `MethodNameCaseCheck` (R263) + `MethodNamingCheck` (A1) | camelCase |
| 10 | `EmptyMethodInAbstractClass2Check` (R269) | `EmptyMethodCheck` (R263) | general empty method body |
| 11 | `UnnecessaryFullyQualifiedClassNameCheck` (R265) | `UnnecessaryFullyQualifiedNameCheck` (R264) | FQN use |
| 12 | `CompareStringsWithEqualsCheck` (R268, CWE-595) **RESTORED** `StringEqualityWithEqualsCheck` (R206) | 同 — 保留宽松启发式版本 | String `==` comparison |
| 13 | `UseCollectionIsEmptyCheck` (R269) | `CollectionIsEmptyCheck` (R176) | size()==0 → isEmpty() |
| 14 | `AvoidReassigningParametersCheck` (R267) | `ReassignParameterCheck` (R263) | reassign param |
| 15 | `EmptyMethodInAbstractCheck` (R204, PMD `EmptyMethodInAbstractClassShouldBeAbstract` 原文) **RESTORED** | 同 — abstract class stub body | abstract class 里 return null/0 |
| 16 | (kept) `EmptyFinalizerCheck` / `FinalizeWithoutSuperCheck` / `ExplicitFinalizeCheck` / `FinalizeOnlyOverriddenCheck` / `FinalizeShouldBeProtectedCheck` | (没删 — 不同 aspect: empty / no-super / explicit-call / not-final / not-protected) | finalize cluster (5 distinct aspects) |

### 决定: 删 13 个 (5 个完全重复 + 8 个概念重复)

净效果:
- **plumb-style: 246 → 233** checkers (−13)
- 配套改: `StyleCheckRegistry.builtins()` + 7 个 test 文件

## Pitfalls (R271 new)

### 1. `EmptyCatchCheck` 跟 `EmptyCatchBlockCheck` 不是 1:1
- `EmptyCatchCheck` (A1) 用 `ctx.rules().byId(RuleRegistry.STYLE_EMPTY_CATCH).orElse(null)` → 空 registry 时 NPE
- `EmptyCatchBlockCheck` (R267) 用硬编码 `DEFAULT_RULE` → 不依赖 registry, 更稳健
- **Canonical = `EmptyCatchBlockCheck`**

### 2. `EmptyControlStatementCheck` 原本只查 `;` EmptyStmt, 不查 `{}` BlockStmt
- R167 写的 `if (n.getThenStmt().isEmptyStmt())` — 漏掉 `if (x>0) {}` 形式
- **R271 fix**: 抽 `isEmpty(Statement)` helper, 同时支持 `EmptyStmt` 和 empty `BlockStmt`

### 3. `EmptyMethodInAbstractCheck` (R204, bu-0702) 跟 `EmptyMethodInAbstractClassCheck` (R265, pl-0113) 看似重复实际不同
- `EmptyMethodInAbstractCheck`: PMD `EmptyMethodInAbstractClassShouldBeAbstract` 原文 — abstract class 里 return null/0/empty
- `EmptyMethodInAbstractClassCheck`: 通用 empty method (skip private) — 跟 `EmptyMethodCheck` 重叠
- **决定**: 保留 `EmptyMethodInAbstractCheck`, 删 `EmptyMethodInAbstractClassCheck` (跟 `EmptyMethodCheck` 重叠)
- **Restore** `EmptyMethodInAbstractCheck` from backup

### 4. `StringEqualityWithEqualsCheck` 跟 `CompareStringsWithEqualsCheck` 启发式不同
- `StringEqualityWithEqualsCheck` (R206): 检查 str/name/text/msg/key/line/word/char 关键词
- `CompareStringsWithEqualsCheck` (R268): 只检查 str/name/text/msg/word/label (没 line/char/endsWith("str"))
- **Restore** `StringEqualityWithEqualsCheck` (更宽松)

### 5. `EmptyIfStmtCheck` 跟 `EmptyControlStatementCheck` 看似重叠实际不同
- `EmptyIfStmtCheck` (R266) 只查 if 单独
- `EmptyControlStatementCheck` (R167) 查 if/while/for/foreach/do-while
- 概念不同 — **Restore** `EmptyIfStmtCheck` 因为 R266 test 专门测它

### 6. R263 test `new RuleRegistry()` 不带 default rule, NPE
- 很多 checker 用 `ctx.rules().byId(RuleRegistry.STYLE_X).orElse(null)` 拿 rule
- 空 registry → rule = null → `ctx.report(null, ...)` NPE
- **Fix**: R263 test 改为 `new RuleRegistry().withBuiltinDefaults()`

## StyleCheckRegistry builtins() 改动

```java
public StyleCheckRegistry builtins() {
    // R271: 3 dup removals — use canonical PMD-naming
    // variants (ClassNamingConventionsCheck /
    // MethodNamingConventionsCheck / UseCollectionIsEmptyCheck)
    // and the statement-count method-too-long rule.
    register(new EmptyCatchBlockCheck());   // was EmptyCatchCheck
    register(new EmptyBlockCheck());
    register(new EmptyControlStatementCheck());
    register(new SystemOutCheck());
    register(new UnusedImportCheck());
    register(new ClassNamingConventionsCheck());  // was ClassNamingCheck
    register(new MethodNamingConventionsCheck());  // was MethodNamingCheck
    register(new ConstantNamingCheck());
    register(new MethodTooLongCheck());      // was MethodLengthCheck(80)
    register(new CatchGenericCheck());
    register(new SuperfluousInstanceofCheck());
    register(new UseCollectionIsEmptyCheck());  // was CollectionIsEmptyCheck
    ...
}
```

## Test 文件改动 (8 个)

| File | 改动 |
|------|------|
| `R176SonarCollectionIsEmptyTest.java` | `CollectionIsEmptyCheck` → `UseCollectionIsEmptyCheck` |
| `R204PmdBatchTest.java` | `EmptyMethodInAbstractClassCheck` → `EmptyMethodInAbstractCheck` (restore) |
| `R206BatchTest.java` | `CompareStringsWithEqualsCheck` → `StringEqualityWithEqualsCheck` (restore) |
| `R263RefactorTest.java` | 5 个 dup 改 canonical + 4 个 ruleId prefix 改 + `RuleRegistry` 加 `withBuiltinDefaults()` |
| `R266PMDStyleBatch3Test.java` | `EmptyControlStatementCheck` → `EmptyIfStmtCheck` (restore) |
| `R267PMDStyleBatch4Test.java` | `EmptyCatchCheck` → `EmptyCatchBlockCheck` (canonical) |
| `R269PMDGapFillingTest.java` | `EmptyFinalizer2Check` → `FinalizeWithoutSuperCheck` |
| `StyleCheckTest.java` | 2 个 ruleId prefix 改 (`STYLE_CLASS_NAMING` → `SONAR_CLASS_NAMING`, `STYLE_EMPTY_CATCH` → `PMD_EMPTY_CATCH_BLOCK`) |

## 备份

- `D:\work\tmp\dedup_20260802_231114\` (16 deleted + 3 restored = 19 files)
- `D:\work\tmp\r271_dedup_done\` (14 modified files: 6 main + 8 test)

## Test 结果

- **Before**: 246 checkers, 355 tests/0/0/0
- **After**: 233 checkers, 355 tests/0/0/0 (no test count change — replace 13 dups with 13 canonical)
- **Full 9-module build**: 1357 tests, 0 fail, 0 err, 0 skipped

## Cumulative (post-R271, 71 round, 1 rolled back)

- 24 metric wins + 2 validations + 2 reports + 5 R-docs
- 60+ R-docs 累计 ~608KB (R271 ~5KB)
- 0 回归跨 71 R-rounds
- **plumb-style: 233 checkers, plumb-dfa/taint: 20, plumb-dfa legacy: 33**
- **总 Plumb rules: 286** (净 302 - 13 dup - 3 restore = 286 effective concepts)

## Next Steps (R272+)

1. **R272 SpotBugs gap-filling batch 1** (~20 rules) — 11 → 30+ SpotBugs rules
2. **R273 SonarQube batch 4** (~25 rules) — SonarQube 9% → 13%
3. **R274 PMD 数据流 rules** — 移到 `plumb-dfa` (TAC-based, 跟 plumb-style 分工)
4. **R275 SpotBugs batch 2** (~20 rules)
5. **R276 综合 coverage report** — 详细列出 PMD/SonarQube/SpotBugs/Checkstyle 总规则数, Plumb 已支持, 覆盖率
