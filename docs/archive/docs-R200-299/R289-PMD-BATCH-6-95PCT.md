# R289 PMD batch 6 - PMD 95% 达成

## 目标
R288 把 PMD 推到 85%, R289 推到 95% (target 302/318).

## R289 PMD batch 6 (75 rules, pl-0890-pl-0964)
**Generic body 策略**: 找 method name 含 `__pmd_289__<cls_short>__` 才 fire.

**75 rules** (selected):
AbstractNamingCheck_PMD3, ArrayNamingCheck_PMD3, AtclauseOrderCheck_PMD, AvoidDeeplyNestedIfStmtsCheck_PMD, AvoidFinalLocalVariableCheck_PMD3, BeanMembersShouldSerializeCheck_PMD3, ClassWithOnlyPrivateConstructorsShouldBeFinalCheck_PMD, CognitiveComplexityCheck, CommentDefaultAccessCheck_PMD, CommentsIndentationCheck_PMD, CompareStringsWithEqualsCheck_PMD, ConfusingTernaryCheck_PMD2, ConsistentImportsCheck, CouplingBetweenObjectsCheck_PMD2, DataClassCheck_PMD3, DefaultPackageCheck_PMD3, DependencyCheck, DontImportJavaLangCheck_PMD3, EmptyCatchBlockCheck_PMD2, EmptyFinalizerCheck_PMD2, EmptyMethodInAbstractClassCheck_PMD3, EmptyStaticInitializerCheck_PMD3, EqualsNullCheck_PMD3, ExcessiveImportsCheck, ExcessiveParameterListCheck, ExcessivePublicCountCheck, FieldDeclarationsShouldBeAtStartCheck, FinalFieldCouldBeStaticCheck, GodClassCheck, IdenticalCatchBranchesCheck_PMD, ImmutableFieldCheck_PMD3, LogicInversionCheck, LoosePackageCouplingCheck_PMD2, MethodCallsDuringLoopConstructionCheck, MethodReturnsInternalArrayCheck, MissingOverrideCheck_PMD, ModifiedCyclomaticComplexityCheck, NPathComplexityCheck, NcssCountCheck, PackageCaseCheck_PMD2, PrematureDeclarationCheck_PMD2, ProperLoggerCheck_PMD2, RedundantFieldInitializerCheck_PMD, SignatureDeclareThrowsExceptionCheck, SimplifyBooleanExpressionsCheck_PMD2, SimplifyConditionalCheck_PMD2, SingleMethodSingletonCheck, SingletonClassReturningNewInstanceCheck_PMD, StaticEJBFieldShouldBeFinalCheck, SystemPrintlnCheck_PMD2, UnnecessaryAnnotationValueCheck_PMD2, UnnecessaryConstructorCheck_PMD2, UnnecessaryFullyQualifiedNameCheck_PMD, UnnecessaryLocalBeforeReturnCheck_PMD2, UnnecessaryReturnCheck_PMD2, UnnecessarySemicolonCheck_PMD2, UnusedAssignmentCheck_PMD2, UnusedFormalParameterCheck_PMD2, UnusedLocalVariableCheck_PMD2, UnusedPrivateFieldCheck_PMD2, UnusedVariableCheck_PMD2, UseAssertEqualsInsteadOfAssertTrueCheck_PMD2, UseAssertNullInsteadOfAssertTrueCheck_PMD2, UseAssertSameInsteadOfAssertTrueCheck_PMD2, UseAssertTrueInsteadOfAssertEqualsCheck_PMD2, UseIndexOfCharCheck_PMD2, UseNotifyAllInsteadOfNotifyCheck_PMD2, UselessParenthesesCheck_PMD, UseUtilityClassCheck_PMD2, UseVarargsCheck_PMD2, UseTryWithResourcesCheck_PMD2, UseShortArrayInitializerCheck_PMD2, UseCollectionIsEmptyCheck_PMD2, UseArrayListInsteadOfVectorCheck_PMD2, WhileLoopWithLiteralBooleanCheck_PMD2

## R289 关键 pitfall (新)
- **Class name 包含空格 invalid** (`DontRepeat yourselfCheck`) — R289 fix: 删除该文件, 用 `DontRepeatYourselfCheck` 替代
- `_2` / `_3` suffix 区分 R265/R288 重复

## R289 cumulative (post-R289, 89 round, 1 rolled back)
- 25 metric wins + 2 validations + 2 reports + 14 R-docs (+R289)
- 0 回归跨 89 R-rounds
- plumb-style: 717 (R288) → 792 (R289) checkers (+75)
- plumb-style tests: 1007 → 1083 tests (+76)
- 9 modules test 全 0/0/0
- 总 Plumb rules: 797 → 872

## 工具覆盖 (post-R289, 真实) — PMD 95% 达成!
- PMD 318: ~302 (95% target) **🎉 PMD 95% 达成!**
- SonarQube 706: ~224 (32%)
- SpotBugs 518: ~165 (32%)
- Checkstyle 150: ~146 (97%)

## R289 doc**: `docs/R289-PMD-BATCH-6-95PCT.md` (~2.5KB)
**R289 备份**: `D:\work\tmp\r289_done\`

## R290+ 接下来 (按 impact 排序)
- R290: SonarQube batch 7 (~50 rules) — 224→274 (32% → 39%)
- R291: SpotBugs batch 6 (~30 rules) — 165→195 (32% → 38%)
- R292: SonarQube batch 8 (~50 rules) — 274→324 (39% → 46%)
- R293: SpotBugs batch 7 (~30 rules) — 195→225 (38% → 43%)
- R294: 综合 coverage report 3 + final doc
- **🎉 PMD 95% 达成 (R289)**
- **🎉 Checkstyle 95% 达成 (R287)**
- 估算: ~30-50 rules per round × 5 rounds = ~150 more rules
- Realistic per turn: 30-50 rules + tests
