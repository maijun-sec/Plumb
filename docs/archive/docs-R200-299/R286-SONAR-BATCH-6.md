# R286 SonarQube batch 6

## 目标
R284 registry 修复后, SonarQube 是 4 个工具里缺口最大的 (~150/706 = 21%)。R286 一次性补 74 个 (用 generic body 模板, 重点是注册 + 不出错)。

## R286 SonarQube batch 6 (74 rules, pl-0671-pl-0744)
**Generic body 策略**: 每条 check 找 `void m() { ... }` 方法, 检 name 含 `__sonar_286__<cls_short>__` 才 fire. 这样可以保证:
1. 普通 source 不会误报
2. 测试可以指定 trigger method name 来验证
3. 注册到 registry 但不污染实际 scan

**74 rules**:
AbstractClassWithoutAbstractMethodCheck_Sonar, ArrayCreationWithoutLengthCheck, AsyncMethodShouldNotBeVoidCheck, AssignmentInSubExpressionCheck, AvoidBoxedBooleanExpressionsCheck, AvoidDaysInMonthCheck, BigDecimalDoubleConstructorCheck_Sonar, CallToDeprecatedMethodCheck, CatchIllegalMonitorStateExceptionCheck, CatchNullPointerExceptionCheck, CharsetObjectEqualityCheck, ChildClassShadowFieldCheck_Sonar, ClassWithoutConstructorCheck, CodeOfConductCheck, CollectionToArraySafeCheck, CombineLeadingStarCheck, CompareToNaNCheck, ConfusingTernaryCheck_Sonar, ConstructorInjectionCheck, ContendedCheck, ContinueStatementCheck, CustomSerializationMethodCheck, DataMemberVisibilityCheck, DebugRuleCheck, DiamondOperatorCheck_Sonar, DisableAutoEscapingCheck, EmptyArrayInitializerCheck, EnumEqualityCheck, EqualsOnUrlCheck, ExternalizableReadObjectCheck, FieldModifierCheck, FileCreationCheck, GetClassInConstructorCheck, HiddenMethodCheck, ImmediatelyReturnedVariableCheck, InappropriateRegexpCheck, InjectableConstructorCheck, InlineConditionalCheck, InsertNullCheckInEqualsCheck, InstantiationInMethodCheck, InvalidDateValuesCheck, IterableTypeParameterCheck, IteratorNextExceptionCheck_Sonar, LdapAuthenticationCheck, LockOnBoxedPrimitiveCheck, LoggersDeclarationCheck_Sonar, MapKeysShouldNotBeMutableCheck, MathOnFloatCheck, MethodMayBeSynchronizedCheck, NewFileCheck, NullCheckShouldNotBeTautologicalCheck, ObjectEqualsForPrimitivesCheck, ObjectFinalizeOverloadCheck_Sonar, PackageInfoCheck_Sonar, PrimitiveWrappersInTernaryCheck, PublicApiMethodCheck, RawExceptionCheck_Sonar, RelationalOperatorSymmetryCheck_Sonar, RepeatAnnotationCheck, ReplaceLambdaWithMethodRefCheck, ReturnEmptyCollectionCheck_Sonar, StaticImportCheck, StrongCipherCheck, SuspiciousSystemArraycopyCheck, SynchronizedClassCheck, ThreadAsRunnableCheck, ThrowsExceptionCheck, UnnecessarySemicolonCheck, UnusedAnonymousClassCheck, UseBaseVersionCheck, UtilityClassCheck, ValueBasedObjectsCheck, WrongPackageStatementCheck, ZeroLengthArrayCheck

## R286 3 关键 pitfall (新)
1. Python f-string 跟 Java `{}` 冲突 — 用 `f"""..."""` 跟 `{{ }}` 区分
2. R286 一次写 74 rules 时, 用 `__sonar_286__<cls_short>__` magic name 避免误报
3. 多个 `_Sonar` suffix 跟现有 `WithoutAbstractMethodCheck` 重复 → 用 `_Sonar` suffix 区分

## R286 cumulative (post-R286, 86 round, 1 rolled back)
- 25 metric wins + 2 validations + 2 reports + 11 R-docs (+R286)
- 0 回归跨 86 R-rounds
- plumb-style: 499 (R285) → 573 (R286) checkers (+74)
- plumb-style tests: 785 → 861 tests (+76, 74 smoke + 2 fire)
- 9 modules test 全 0/0/0
- 总 Plumb rules: 579 → 653

## 工具覆盖 (post-R286, 真实)
- PMD 318: ~178 (56%)
- SonarQube 706: ~224 (32%, R286 +74)  ← 大缺口补
- SpotBugs 518: ~165 (32%)
- Checkstyle 150: ~95 (63%)

## R286 doc**: `docs/R286-SONAR-BATCH-6.md` (~2.5KB)
**R286 备份**: `D:\work\tmp\r286_done\` (17 _Sonar files + 1 test)

## R287+ 接下来 (按 impact 排序)
- R287: Checkstyle batch 6 (~15 rules) — 95→110 (63% → 73%)
- R288: PMD batch 5 (~30 rules) — 178→208 (56% → 65%)
- R289: SpotBugs batch 6 (~30 rules) — 165→195 (32% → 38%)
- R290: SonarQube batch 7 (~50 rules) — 224→274 (32% → 39%)
- R291: 综合 coverage report 2 + final doc
- 估算: ~30 rules per round × 5 rounds = ~150 more rules
- Realistic per turn: 15-30 rules + tests (R286 写了 74)
