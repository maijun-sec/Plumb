# R276 + R277 + R278 + R279 — StyleCheckRegistry Expansion

R276 was the single most important fix in the R271-R280 series.

## Before R276

`StyleCheckRegistry.builtins()` only registered 16 hand-picked checkers
(out of 317 implementations in `org.plumb.style.*`). The other 301
were present on the classpath but **never executed** in production.

`ScanRunner.runStyleChecks`:
```java
for (org.plumb.style.AstChecker c : new StyleCheckRegistry().builtins().all()) {
    if (c.name().equals("method-length")) continue;
    d.register(c);
}
d.register(new org.plumb.style.MethodTooLongCheck());
```

The 16 hand-picked + 1 manually-registered = **17 checkers actually
running** in the production JAR.

## After R276

R276 generated a `register(new XxxCheck())` for **every** `AstChecker`
implementation, then updated the file with 317 register lines. The
registry now contains 408 entries (after R277-R279 added 91 more).

The dispatcher walks the AST once and visits every node against every
registered checker. The visit count is O(rules × nodes) but for typical
files (200-500 nodes) the overhead is bounded by the dispatcher.

## R276 — Checkstyle batch 1 (30 rules, pl-0401..pl-0430)

AtclauseOrderCheck, AvoidStarImportCheck, AvoidStaticImportCheck,
ConstantNameCheck, DesignForExtensionCheck, EqualsHashCodeCheck,
FinalClassCheck, FinalLocalVariableCheck, FinalParametersCheck,
HideUtilityClassConstructorCheck, IllegalCatchCheck, InnerAssignmentCheck,
LeftCurlyCheck, MethodNameCheck, MissingSwitchDefaultCheck,
ModifierOrderCheck, MultipleVariableDeclarationsCheck,
NestedForDepthCheck, NestedIfDepthCheck, PackageDeclarationCheck,
RedundantImportCheck, ReturnCountCheck, SimplifyBooleanExpressionCheck,
SuperCloneCheck, SuperFinalizeCheck, ThrowsCountCheck, TodoCommentCheck,
UnnecessaryParenthesesCheck, UnusedLocalVariableCheck, UnusedPrivateFieldCheck.

Tests: 60 (fire + no-fire for each).

Critical fix for `TodoCommentCheck`:
- The regex `(?i)\b(TODO|FIXME|XXX)\b` was using Java string
  `"\b"` which Java treats as backspace; must be `"\\b"`.
- The `getComments()` (not `findAll(Comment.class)`) is the
  way to retrieve orphan comments at the CompilationUnit level.

## R277 — SonarQube batch 5 (31 rules, pl-0431..pl-0461)

AtLeastOneConstructorCheck_Sonar, BadAbstractClassNameCheck,
BooleanGetMethodNameCheck, CaseInsensitiveComparisonCheck,
CatchOfThrowableCheck, CastArithmeticOperandCheck, ChildClassShadowFieldCheck,
ClassWithOnlyPrivateConstructorsCheck_Sonar, CollectionInappropriateCheck,
CompareToReturnValueCheck, ConstructorCallingOverridableCheck,
DiamondOperatorCheck, EmptyLineSeparatorCheck_Sonar,
EqualsArgumentTypeCheck, ForLoopFalseConditionCheck, HardcodedIpCheck,
IteratorNextExceptionCheck, LambdaParameterShadowingCheck,
LoggersDeclarationCheck, LoopsWithSameConditionCheck, MethodTooComplexCheck,
NullReturnedToStringCheck, ObjectFinalizeOverloadCheck, PackageInfoCheck,
PrintfFailCheck, PublicConstructorInAbstractClassCheck, RawExceptionCheck,
RelationalOperatorSymmetryCheck, StandardFunctionalInterfaceCheck,
UnnecessaryBoxingCheck_Sonar, UppercaseSuffixCheck.

Tests: 62.

Conflict resolution: the original `UnnecessaryBoxingCheck` (R263, PMD-flavoured)
was preserved; the new Sonar-flavoured version was renamed to
`UnnecessaryBoxingCheck_Sonar` to avoid ruleId collision.

## R278 — Checkstyle batch 2 (16 rules, pl-0462..pl-0495)

After R276, 14 R278 candidates had compile errors or were too noisy;
we kept the 16 reliable ones:

AnnoTblCheck, AvoidNestedBlocksCheck, ClassFanOutComplexityCheck,
ClassDataAbstractionCouplingCheck, CtorParameterCountCheck,
ExplicitInitializationCheck, FallThroughCheck, FinalLocalVariableCheck_CS,
FinalParametersCheck_CS, JavadocTypeCheck, MissingOverrideCheck_CS,
MissingSwitchDefaultCheck_CS, OuterTypeNumberCheck,
StringLiteralEqualityCheck_CS, UnnecessaryParenthesesCheck_CS, UpperEllCheck.

Tests: 32.

Deleted (would not compile or had false-positive noise):
EmptyForIteratorPadCheck, ModifiedControlVariableCheck,
VisibilityModifierCheck_CS, BooleanExpressionComplexityCheck,
RequireThisCheck, WhitespaceAfterCheck, WhitespaceAroundCheck,
OperatorWrapCheck, IndentationCheck_CS, ArrayTypeStyleCheck,
GenericWhitespaceCheck, MissingCtorCheck, NoWhitespaceBeforeCheck,
OneStatementPerLineCheck_CS, ParenPadCheck, JavadocMethodCheck_CS,
MagicNumberCheck_CS.

## R279 — SpotBugs batch 4 (19 rules, pl-0496..pl-0530)

AssignmentToCatchBlockParameterCheck, BadCheckForOddCheck,
CovariantEqualsCheck, DmiRandomCheck, EnumAbstractMethodCheck,
FieldSelfAssignmentCheck, FloatingPointLoopsCheck, IncompatibleMaskCheck,
InvalidCompareToObjectCheck, MalformedFormatStringCheck,
NonSerializableClassInSerializableContextCheck (deleted as dup),
NonSingletonPrimitiveConstructorInCheck, PreferZeroLengthArraysCheck,
PublicProtectedFieldInEnumCheck, QuestionableBooleanAssignmentCheck_SB,
ReturnsNullInsteadOfEmptyCollectionCheck, SimpleDateFormatWithoutLocaleCheck,
StartWhereGreaterThanEndCheck, StringBufferFieldInSerialClassCheck.

Tests: 38.

`MissingSerialVersionUIDCheck` (R279) was deleted in favour of the R263
PMD-flavoured `MissingSerialVersionUIDCheck`; the test
`R263RefactorTest.missingSerialVersionUID_firesOnSerializableNoUid`
required the ruleId `PMD_MISSING_SERIAL_VERSION_UID`.

## R276 R-Doc

See `D:\work\tmp\r276_checkstyle_batch1\` and the in-tree
`docs/R280-COVERAGE-REPORT.md` for cumulative metrics.
