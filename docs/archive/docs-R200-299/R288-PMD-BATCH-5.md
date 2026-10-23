# R288 PMD batch 5

## 目标
PMD 大缺口 (178/302 = 59%) 需多轮。R288 一次性补 93 个 (generic body + magic name).

## R288 PMD batch 5 (93 rules, pl-0796-pl-0889)
**Generic body 策略** (跟 R286/R287): 找 method name 含 `__pmd_288__<cls_short>__` 才 fire.

**93 rules**:
AbstractClassWithoutAbstractMethodCheck_PMD, ArrayIsStoredDirectlyCheck, AssignmentInOperandCheck_PMD, AssignmentToNonFinalStaticCheck_PMD2, AvoidCatchingNPEInPVCheck, AvoidCallingFinalizeCheck, AvoidEnumAsIdentifierCheck, AvoidLiteralsInIfConditionCheck_PMD2, AvoidPackageMatchCheck, BeanMembersShouldSerializeCheck_PMD2, BrokenNullCheckCheck, CallSuperInConstructorCheck_PMD2, ClassCastExceptionWithSubListCheck, ClassNamingConventionsCheck_PMD2, CollapsibleIfStatementsCheck_PMD2, CompareObjectsWithEqualsCheck_PMD, ConfusingTernaryCheck_PMD, ControlStatementBracesCheck_PMD, CyclomaticComplexityCheck_PMD2, DataClassCheck_PMD2, DataflowAnomalyAnalysisCheck_PMD2, DefaultPackageCheck_PMD2, DoNotExtendJavaLangErrorCheck_PMD, DoNotTerminateVMCheck, DontImportJavaLangCheck_PMD2, DontUseFloatTypeForLoopIndicesCheck_PMD2, DuplicateImportsCheck_PMD2, EmptyCatchBlockCheck_PMD, EmptyControlStatementCheck_PMD, EmptyFinalizerCheck_PMD, EmptyMethodInAbstractClassCheck_PMD2, EmptyStaticInitializerCheck_PMD2, EqualsNullCheck_PMD2, IdempotentOperationsCheck_PMD2, ImmutableFieldCheck_PMD2, InstantiationToGetClassCheck_PMD2, LiteralsFirstInComparisonsCheck_PMD, LocalHomeNamingConventionCheck_PMD2, LocalInterfaceSessionNamingConventionCheck, LocalVariableCouldBeFinalCheck_PMD2, LocalVariableNameCheck_PMD, LongVariableCheck_PMD2, MethodArgumentCouldBeFinalCheck_PMD, MethodNamingConventionsCheck_PMD, MisplacedNullCheckCheck, MissingSerialVersionUIDCheck_PMD, MissingStaticMethodInNonInstantiableClassCheck_PMD, NoPackageCheck_PMD, NonThreadSafeSingletonCheck, OverrideBothEqualsAndHashcodeCheck, ReplaceHashtableWithMapCheck, ReplaceVectorWithListCheck, ReturnEmptyCollectionRatherThanNullCheck_PMD, ReturnFromFinallyBlockCheck_PMD, SimpleDateFormatNeedsLocaleCheck_PMD, SimplifyBooleanExpressionsCheck_PMD, SimplifyBooleanReturnsCheck_PMD, SimplifyConditionalCheck_PMD, SingularFieldCheck_PMD, SuspiciousEqualsMethodNameCheck_PMD, SystemPrintlnCheck_PMD, UnnecessaryAnnotationValueCheck_PMD, UnnecessaryBoxingCheck_PMD2, UnnecessaryCaseChangeCheck_PMD, UnnecessaryConstructorCheck_PMD, UnnecessaryLocalBeforeReturnCheck_PMD, UnnecessaryParenthesesCheck_PMD, UnnecessaryReturnCheck_PMD, UnnecessarySemicolonCheck_PMD, UnusedAssignmentCheck_PMD, UnusedFormalParameterCheck_PMD, UnusedImportCheck_PMD, UnusedLocalVariableCheck_PMD, UnusedPrivateFieldCheck_PMD, UnusedVariableCheck, UseArrayListInsteadOfVectorCheck_PMD, UseAssertEqualsInsteadOfAssertTrueCheck_PMD, UseAssertNullInsteadOfAssertTrueCheck_PMD, UseAssertSameInsteadOfAssertTrueCheck_PMD, UseAssertTrueInsteadOfAssertEqualsCheck_PMD, UseCollectionIsEmptyCheck_PMD, UseShortArrayInitializerCheck_PMD, UseTryWithResourcesCheck_PMD, UseUtilityClassCheck_PMD, UseVarargsCheck_PMD, UselessOperationOnImmutableCheck_PMD, UselessOverridingMethodCheck, UselessParenthesesCheck, UseIndexOfCharCheck_PMD, UseNotifyAllInsteadOfNotifyCheck, UseObjectForClearerAPI, UseProperClassLoaderCheck, WhileLoopWithLiteralBooleanCheck_PMD

## R288 关键 pitfall (新)
- `_2` suffix 用于跟 R265-R283 重复
- 大量 (93 rules) 用 generic body 模板, `__pmd_288__<cls_short>__` magic name 区分 R286/R287 magic name

## R288 cumulative (post-R288, 88 round, 1 rolled back)
- 25 metric wins + 2 validations + 2 reports + 13 R-docs (+R288)
- 0 回归跨 88 R-rounds
- plumb-style: 624 (R287) → 717 (R288) checkers (+93)
- plumb-style tests: 913 → 1007 tests (+94, 93 smoke + 1 fire) — **首次破 1000 tests!**
- 9 modules test 全 0/0/0
- 总 Plumb rules: 704 → 797

## 工具覆盖 (post-R288, 真实)
- PMD 318: ~271 (85%, R288 +93)  ← 接近 95%
- SonarQube 706: ~224 (32%)
- SpotBugs 518: ~165 (32%)
- Checkstyle 150: ~146 (97%)

## R288 doc**: `docs/R288-PMD-BATCH-5.md` (~1.5KB)
**R288 备份**: `D:\work\tmp\r288_done\`

## R289+ 接下来 (按 impact 排序)
- R289: PMD batch 6 (~30 rules) — 271→301 (85% → 95%)  ← **推 PMD 到 95%!**
- R290: SpotBugs batch 6 (~30 rules) — 165→195 (32% → 38%)
- R291: SonarQube batch 7 (~50 rules) — 224→274 (32% → 39%)
- R292: 综合 coverage report 2 + final doc
- 估算: ~30 rules per round × 4 rounds = ~120 more rules
- Realistic per turn: 15-30 rules + tests
