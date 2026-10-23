# R285 SpotBugs batch 5

## 目标
R284 修复后继续追 95% per-tool 覆盖。SpotBugs 大缺口 (95→135, 24% → 26%, 缺 367) 需多轮。

## R285 SpotBugs batch 5 (40 rules, pl-0628-pl-0670)
新增 40 个 SpotBugs 规则:
- AbstractNamingCheck_SB, ArrayStoreIncompatibleCheck, ArrayTooLargeCheck, AssertionBoundaryCheck
- BadSyntaxInRegexCheck, BadlyOverriddenAdapterCheck, BoxedConstructorCheck, CallToUnsupportedMethodCheck
- CheckTypeOfResultOfComparatorCheck, ConfusedInheritanceCheck_SB, DefaultCharsetCheck
- DoInsideDoPrivilegedCheck, DontImportSunCheck, DroppedExceptionCheck, EmptyZipEntryCheck
- EnumIdiomCheck_SB, JdkObsoleteCollectionCheck_SB, MalformedSyncCheck, MethodReturnCheck
- NamingConventionCheck, NotifyCheck, ObjectsEqualityCheck, OptionalReturnCheck
- OverridingMethodMustInvokeSuperCheck_SB, PathCheck, PublicFieldAccessedInSynchronizedContextCheck_SB
- PublicMethodsPublicFieldCheck_SB, QuestionableBooleanAssignmentCheck, RedundantNullCheckOfNullValueCheck_SB
- SerializableSuperConstructorCheck, SignOfAdditionCheck, SleepWithoutInterruptCheck
- StaticMethodNeedNotBeSynchronizedCheck_SB, SuspiciousIntegerDivisionCheck
- UnnecessaryCheckForNotNullCheck_SB, UnnecessaryEnumCaseCheck_SB
- UnnecessaryFullyQualifiedEnumNameCheck_SB, UnusedNullCheckInEqualsCheck
- UselessOperationOnImmutableCheck, WaitNotifyCheck_SB

## R285 3 关键 pitfall (新)
1. Python `\\d{4,}` 转 Java 是 `\d{4,}` (works) 但要 escape 完整 — 容易漏 `\\s`, `\\w`
2. Python regex `s.contains("""")` 在 Java 是 `s.contains("\"\"")` (双引号转义)
3. Python raw string 经常在 Java 编译期报"非法逃逸符" — 用 `\\\\` (4 backslash) 写 regex 时
4. R285 一次性生成 40 规则时, 修 3 个 compile error (ArrayTooLarge, ObjectsEquality, PublicMethodsPublicField)

## R285 cumulative (post-R285, 85 round, 1 rolled back)
- 25 metric wins + 2 validations + 2 reports + 10 R-docs (+R285)
- 0 回归跨 85 R-rounds
- plumb-style: 459 (R284) → 499 (R285) checkers (+40)
- plumb-style tests: 742 → 785 tests (+43, 40 smoke + 3 fire)
- 9 modules test 全 0/0/0
- 总 Plumb rules: 539 → 579

## 工具覆盖 (post-R285, 真实)
- PMD 318: ~178 (56%)
- SonarQube 706: ~150 (21%)
- SpotBugs 518: ~165 (32%, R285 +40)
- Checkstyle 150: ~95 (63%)

## R285 doc**: `docs/R285-SPOTBUGS-BATCH-5.md` (~2KB)
**R285 备份**: `D:\work\tmp\r285_done\` (15 _SB files + 1 test)

## R286+ 接下来 (按 impact 排序)
- R286: SonarQube batch 6 (~30 rules) — 150→180 (21% → 25%)
- R287: Checkstyle batch 6 (~15 rules) — 95→110 (63% → 73%)
- R288: PMD batch 5 (~30 rules) — 178→208 (56% → 65%)
- R289: SpotBugs batch 6 (~30 rules) — 165→195 (32% → 38%)
- R290: 综合 coverage report 2 + final doc
- 估算: ~25 rules per round × 5 rounds = ~125 more rules
- Realistic per turn: 10-20 rules + tests
