# plumb-style — AST 规则目录

本文档列出 `plumb-style` 模块下的所有 AST 风格/结构检查器。每个规则都对应一个 JavaParser AST 访问器（见 `AstChecker` 接口），由 `AstDispatcher` 单次遍历 CompilationUnit 触发。

## 目录

- [架构与运行机制](#架构与运行机制)
- [统计概览](#统计概览)
- [按分类索引](#按分类索引)
- [规则详细列表](#规则详细列表)
- [新增 / 修改规则](#新增--修改规则)

## 架构与运行机制

**plumb-style** 是 plumb 项目的纯静态分析模块：

```
  Java 源文件
       │
       ▼
  JavaSourceParser ──► CompilationUnit (AST)
       │
       ▼
  AstDispatcher.run(ctx)  ◄── 一次完整 AST 遍历
       │                    ◄── 对每个节点
       │                        调用每个 checker 的 visit(...)
       ▼
  StyleContext.findings() ──► List<Finding>
```

**关键设计**：

- `AstChecker` 是单次遍历的访问器接口。每个 checker 实现自己关心的`visit(MethodDeclaration, ctx)` / `visit(IfStmt, ctx)` / `visit(MethodCallExpr, ctx)` 等方法，未实现的方法默认 no-op。
- `AstDispatcher` 是单次遍历的派发器。它对每个 AST 节点，按注册顺序调用每个 checker 对应的 visit 方法。
- 这意味着 *N* 个 checker 遍历一份 *M* 节点的 AST，只需 *O(M·N)* 次方法分派，而不是每个 checker 独立遍历一次 (那样是 *O(M·N²)* 节点访问)。
- 每个 checker 通过 `ctx.report(line, col, rule, message, severity, dataflowHead)` 上报违规。`RuleDef` 提供规则 ID、严重度、类型 (BUG / STYLE / BAD_PRACTICE) 等元数据。
- 注册方式有三种：
  - `AstCheckerRegistry.builtins()` — 全部 583 条
  - `AstCheckerRegistry.load("Foo.*")` — 按简单名 glob 过滤
  - `AstCheckerRegistry.byCategory("security")` — 按子包分类过滤

- 下列 1 个规则 ID 被多个 checker 共享 —— 其中一个 checker 直接内联声明 `RuleId`，另一个则通过 `ctx.rules().byId(RuleRegistry.<CONST>)` 查同一规则：

  - `ast-style-system-out-println` — `SystemOutCheck`, `SystemOutPrintlnCheck`

## 统计概览

- 总规则数: **583**

| 分类 | 数量 | 说明 |
| ---- | ---- | ---- |
| `bug` | 20 | Bug 模式 (容易导致运行时错误的写法) |
| `design` | 18 | 设计 (复杂度、耦合度) |
| `error` | 35 | 错误处理 (异常、try/catch、finalize) |
| `naming` | 51 | 命名 (标识符、命名风格) |
| `performance` | 63 | 性能 (低效实现、可优化写法) |
| `security` | 42 | 安全 (注入、硬编码、加密) |
| `style` | 354 | 风格 (代码风格、格式) |

## 按分类索引

### bug  (20 条)

| 规则 ID | 短名 | 严重度 | 类型 | 规则类 |
| ------- | ---- | ------ | ---- | ------ |
| `ast-bug-bc-equals-no-null` | `BC_EQUALS_NO_NULL` | LOW | STYLE | `BcEqualsNoNullCheckCheck` |
| `ast-bug-comparator-compare` | `COMPARATOR_COMPARE` | LOW | STYLE | `ComparatorCompareCheck` |
| `ast-bug-compare-objects-with-equals` | `COMPARE_OBJECTS_WITH_EQUALS` | HIGH | BUG | `CompareObjectsWithEqualsCheck` |
| `ast-bug-compareto-return-value` | `COMPARETO_RETURN_VALUE` | LOW | STYLE | `CompareToReturnValueCheck` |
| `ast-bug-covariant-equals` | `COVARIANT_EQUALS` | MEDIUM | BUG | `CovariantEqualsCheck` |
| `ast-bug-equals-avoid-null` | `EQUALS_AVOID_NULL` | LOW | BAD_PRACTICE | `EqualsAvoidNullCheck` |
| `ast-bug-equals-null` | `EQUALS_NULL` | LOW | STYLE | `EqualsNullCheck` |
| `ast-bug-incorrect-return-check` | `INCORRECT_RETURN_CHECK` | LOW | STYLE | `IncorrectReturnValueCheckChecker` |
| `ast-bug-incorrect-return-comparison` | `INCORRECT_RETURN_COMPARISON` | HIGH | BUG | `IncorrectReturnComparisonCheck` |
| `ast-bug-invalidcomparetoobject` | `INVALIDCOMPARETOOBJECT` | LOW | BUG | `InvalidCompareToObjectCheck` |
| `ast-bug-jumbled-incrementer` | `JUMBLED_INCREMENTER` | LOW | STYLE | `JumbledIncrementerCheck` |
| `ast-bug-method-signature` | `METHOD_SIGNATURE` | LOW | STYLE | `MethodSignatureCheck` |
| `ast-bug-np-always-null` | `NP_ALWAYS_NULL` | MEDIUM | BUG | `NpAlwaysNullCheck` |
| `ast-bug-np-load-null` | `NP_LOAD_NULL` | MEDIUM | BUG | `NpLoadOfKnownNullCheck` |
| `ast-bug-redundant-nullcheck` | `REDUNDANT_NULLCHECK` | LOW | STYLE | `RedundantNullCheckCheck` |
| `ast-bug-redundantnullofnullvalue-sb` | `REDUNDANTNULLOFNULLVALUE_SB` | LOW | STYLE | `RedundantNullCheckOfNullValueCheck` |
| `ast-bug-return-count` | `RETURN_COUNT` | LOW | STYLE | `ReturnCountCheck` |
| `ast-bug-rv-negating-compareto` | `RV_NEGATING_COMPARETO` | MEDIUM | BUG | `RvNegatingCompareToCheck` |
| `ast-bug-unnecessaryfornotnull-sb` | `UNNECESSARYFORNOTNULL_SB` | LOW | STYLE | `UnnecessaryCheckForNotNullCheck` |
| `ast-bug-unusednullinequals` | `UNUSEDNULLINEQUALS` | LOW | STYLE | `UnusedNullCheckInEqualsCheck` |

### design  (18 条)

| 规则 ID | 短名 | 严重度 | 类型 | 规则类 |
| ------- | ---- | ------ | ---- | ------ |
| `ast-design-abstract-coupling` | `ABSTRACT_COUPLING` | LOW | STYLE | `AbstractClassCouplingCheck` |
| `ast-design-abstract-no-method` | `ABSTRACT_NO_METHOD` | LOW | STYLE | `AbstractClassWithoutAbstractMethodCheck` |
| `ast-design-boolean-expression-complexity` | `BOOLEAN_EXPRESSION_COMPLEXITY` | LOW | STYLE | `BooleanExpressionComplexityCheck` |
| `ast-design-classfanoutcomplexity` | `CLASSFANOUTCOMPLEXITY` | LOW | STYLE | `ClassFanOutComplexityCheck` |
| `ast-design-confused-inheritance` | `CONFUSED_INHERITANCE` | LOW | STYLE | `ConfusedInheritanceCheck` |
| `ast-design-coupling` | `COUPLING` | LOW | STYLE | `CouplingBetweenObjectsCheck` |
| `ast-design-ctor-calling-overridable` | `CTOR_CALLING_OVERRIDABLE` | MEDIUM | BUG | `ConstructorCallingOverridableCheck` |
| `ast-design-cyclomatic-complexity` | `CYCLOMATIC_COMPLEXITY` | LOW | STYLE | `CyclomaticComplexityCheck` |
| `ast-design-data-class` | `DATA_CLASS` | LOW | STYLE | `DataClassCheck` |
| `ast-design-design-for-extension` | `DESIGN_FOR_EXTENSION` | LOW | STYLE | `DesignForExtensionCheck` |
| `ast-design-inheritance-depth` | `INHERITANCE_DEPTH` | LOW | STYLE | `ClassInheritanceDepthCheck` |
| `ast-design-java-ncss` | `JAVA_NCSS` | LOW | STYLE | `JavaNCSSCheck` |
| `ast-design-law-of-demeter` | `LAW_OF_DEMETER` | LOW | STYLE | `LawOfDemeterCheck` |
| `ast-design-loose-coupling` | `LOOSE_COUPLING` | LOW | STYLE | `LooseCouplingCheck` |
| `ast-design-loose-coupling-2` | `LOOSE_COUPLING_2` | LOW | STYLE | `LoosePackageCouplingCheck` |
| `ast-design-method-count` | `METHOD_COUNT` | LOW | STYLE | `MethodCountCheck` |
| `ast-design-public-ctor-abstract` | `PUBLIC_CTOR_ABSTRACT` | LOW | STYLE | `PublicConstructorInAbstractClassCheck` |
| `ast-design-uncommented-empty` | `UNCOMMENTED_EMPTY` | LOW | STYLE | `EmptyMethodInAbstractClass2Check` |

### error  (35 条)

| 规则 ID | 短名 | 严重度 | 类型 | 规则类 |
| ------- | ---- | ------ | ---- | ------ |
| `ast-error-assignmenttocatchblockparameter` | `ASSIGNMENTTOCATCHBLOCKPARAMETER` | LOW | BUG | `AssignmentToCatchBlockParameterCheck` |
| `ast-error-avoid-throwable` | `AVOID_THROWABLE` | LOW | STYLE | `AvoidCatchingThrowableCheck` |
| `ast-error-catch-generic-exception` | `STYLE_CATCH_GENERIC` | LOW | STYLE | `CatchGenericCheck` |
| `ast-error-catch-log` | `CATCH_LOG` | MEDIUM | BAD_PRACTICE | `CatchExceptionForLoggingCheck` |
| `ast-error-catch-nullpointer-exception` | `CATCH_NULLPOINTER_EXCEPTION` | MEDIUM | BAD_PRACTICE | `CatchNpeCheck` |
| `ast-error-catch-of-throwable` | `CATCH_OF_THROWABLE` | MEDIUM | BUG | `CatchOfThrowableCheck` |
| `ast-error-catch-throwable` | `CATCH_THROWABLE` | MEDIUM | BAD_PRACTICE | `CatchThrowableCheck` |
| `ast-error-do-not-extend-error` | `DO_NOT_EXTEND_ERROR` | MEDIUM | BAD_PRACTICE | `DoNotExtendJavaLangErrorCheck` |
| `ast-error-dropped-exception` | `DROPPED_EXCEPTION` | LOW | STYLE | `DroppedExceptionCheck` |
| `ast-error-empty-catch-block` | `EMPTY_CATCH_BLOCK` | LOW | STYLE | `EmptyCatchBlockCheck` |
| `ast-error-empty-finalizer` | `EMPTY_FINALIZER` | MEDIUM | BAD_PRACTICE | `EmptyFinalizerCheck` |
| `ast-error-error-message-info-leak` | `ERROR_MESSAGE_INFO_LEAK` | MEDIUM | SECURITY | `ErrorMessageInfoLeakCheck` |
| `ast-error-explicit-finalize` | `EXPLICIT_FINALIZE` | MEDIUM | BAD_PRACTICE | `ExplicitFinalizeCheck` |
| `ast-error-finalize-only-overridden` | `FINALIZE_ONLY_OVERRIDDEN` | LOW | BAD_PRACTICE | `FinalizeOnlyOverriddenCheck` |
| `ast-error-finalize-protected` | `FINALIZE_PROTECTED` | LOW | BAD_PRACTICE | `FinalizeShouldBeProtectedCheck` |
| `ast-error-finalize-without-super` | `FINALIZE_WITHOUT_SUPER` | MEDIUM | BAD_PRACTICE | `FinalizeWithoutSuperCheck` |
| `ast-error-generic-exception` | `GENERIC_EXCEPTION` | LOW | STYLE | `GenericExceptionCheck` |
| `ast-error-identical-catch` | `IDENTICAL_CATCH` | MEDIUM | BAD_PRACTICE | `IdenticalCatchBranchesCheck` |
| `ast-error-illegal-catch` | `ILLEGAL_CATCH` | LOW | STYLE | `IllegalCatchCheck` |
| `ast-error-instanceof-in-catch` | `INSTANCEOF_IN_CATCH` | MEDIUM | BAD_PRACTICE | `AvoidInstanceofChecksInCatchClauseCheck` |
| `ast-error-iterator-next-exception` | `ITERATOR_NEXT_EXCEPTION` | LOW | BUG | `IteratorNextExceptionCheck` |
| `ast-error-lose-exception-info` | `LOSE_EXCEPTION_INFO` | MEDIUM | BAD_PRACTICE | `AvoidLosingExceptionInformationCheck` |
| `ast-error-nested-try` | `NESTED_TRY` | MEDIUM | BAD_PRACTICE | `NestedTryCatchCheck` |
| `ast-error-no-finalizer` | `NO_FINALIZER` | LOW | STYLE | `NoFinalizerCheck` |
| `ast-error-object-finalize-overload` | `OBJECT_FINALIZE_OVERLOAD` | LOW | STYLE | `ObjectFinalizeOverloadCheck` |
| `ast-error-parse-error-logging` | `PARSE_ERROR_LOGGING` | MEDIUM | BAD_PRACTICE | `ParseErrorLoggingCheck` |
| `ast-error-raw-exception` | `RAW_EXCEPTION` | LOW | STYLE | `RawExceptionCheck` |
| `ast-error-super-finalize` | `SUPER_FINALIZE` | MEDIUM | BUG | `SuperFinalizeCheck` |
| `ast-error-suppression-marker-comment` | `SUPPRESSION_MARKER_COMMENT` | LOW | STYLE | `NosonarCommentCheck` |
| `ast-error-throw-generic-exception` | `THROW_GENERIC_EXCEPTION` | LOW | BAD_PRACTICE | `ThrowGenericExceptionCheck` |
| `ast-error-throw-in-finally` | `THROW_IN_FINALLY` | HIGH | BUG | `ThrowInFinallyCheck` |
| `ast-error-throwable-not-thrown` | `THROWABLE_NOT_THROWN` | MEDIUM | BAD_PRACTICE | `ThrowableNotThrownCheck` |
| `ast-error-throws-count` | `THROWS_COUNT` | LOW | STYLE | `ThrowsCountCheck` |
| `ast-error-unchecked-throws` | `UNCHECKED_THROWS` | LOW | STYLE | `PublicMethodNoUncheckedExceptionCheck` |
| `ast-error-unused-throws` | `UNUSED_THROWS` | LOW | STYLE | `UnusedThrowsCheck` |

### naming  (51 条)

| 规则 ID | 短名 | 严重度 | 类型 | 规则类 |
| ------- | ---- | ------ | ---- | ------ |
| `ast-naming-abstract` | `ABSTRACT` | LOW | STYLE | `AbstractCheck` |
| `ast-naming-abstract-naming` | `ABSTRACT_NAMING` | LOW | STYLE | `AbstractNamingCheck` |
| `ast-naming-arraynaming` | `ARRAYNAMING` | LOW | STYLE | `ArrayNamingCheck` |
| `ast-naming-at-least-one-constructor` | `AT_LEAST_ONE_CONSTRUCTOR` | LOW | STYLE | `AtLeastOneConstructorCheck` |
| `ast-naming-avoid-final-local-variable` | `AVOID_FINAL_LOCAL_VARIABLE` | LOW | STYLE | `AvoidFinalLocalVariableCheck` |
| `ast-naming-bad-abstract-class-name` | `BAD_ABSTRACT_CLASS_NAME` | LOW | NAMING | `BadAbstractClassNameCheck` |
| `ast-naming-bean-members-serialize` | `BEAN_MEMBERS_SERIALIZE` | MEDIUM | BAD_PRACTICE | `BeanMembersShouldSerializeCheck` |
| `ast-naming-boolean-get-method-name` | `BOOLEAN_GET_METHOD_NAME` | LOW | NAMING | `BooleanGetMethodNameCheck` |
| `ast-naming-class-data-abstraction-coupling` | `CLASS_DATA_ABSTRACTION_COUPLING` | LOW | STYLE | `ClassDataAbstractionCouplingCheck` |
| `ast-naming-class-name-long` | `CLASS_NAME_LONG` | LOW | NAMING | `ClassNameTooLongCheck` |
| `ast-naming-class-naming` | `CLASS_NAMING` | LOW | NAMING | `ClassNamingConventionsCheck` |
| `ast-naming-class-only-private-ctors` | `CLASS_ONLY_PRIVATE_CTORS` | LOW | STYLE | `ClassWithOnlyPrivateConstructorsCheck` |
| `ast-naming-class-type-parameter-name` | `CLASS_TYPE_PARAMETER_NAME` | LOW | STYLE | `ClassTypeParameterNameCheck` |
| `ast-naming-compare-class-names` | `COMPARE_CLASS_NAMES` | MEDIUM | BUG | `CompareClassNamesCheck` |
| `ast-naming-constant-name` | `CONSTANT_NAME` | LOW | STYLE | `ConstantNameCheck` |
| `ast-naming-constants` | `STYLE_CONSTANT_NAMING` | LOW | NAMING | `ConstantNamingCheck` |
| `ast-naming-empty-method` | `EMPTY_METHOD` | LOW | STYLE | `EmptyMethodCheck` |
| `ast-naming-equals-hash` | `EQUALS_HASH` | MEDIUM | BUG | `OverrideEqualsAndHashCodeCheck` |
| `ast-naming-field-naming` | `FIELD_NAMING` | LOW | NAMING | `FieldNamingConventionCheck` |
| `ast-naming-final-local-variable` | `FINAL_LOCAL_VARIABLE` | LOW | STYLE | `FinalLocalVariableCheck` |
| `ast-naming-generic-naming` | `GENERIC_NAMING` | LOW | NAMING | `GenericNamingCheck` |
| `ast-naming-interface-type-parameter-name` | `INTERFACE_TYPE_PARAMETER_NAME` | LOW | STYLE | `InterfaceTypeParameterNameCheck` |
| `ast-naming-invalid-log` | `INVALID_LOG` | LOW | BAD_PRACTICE | `InvalidLogMessageFormatCheck` |
| `ast-naming-local-final-variable-name` | `LOCAL_FINAL_VARIABLE_NAME` | LOW | STYLE | `LocalFinalVariableNameCheck` |
| `ast-naming-local-home-naming-NOT-IMPLEMENTED` | `LOCAL_HOME_NAMING_NOT_IMPLEMENTED` | LOW | STYLE | `LocalHomeNamingConventionCheck` |
| `ast-naming-local-var-could-be-final` | `LOCAL_VAR_COULD_BE_FINAL` | LOW | STYLE | `LocalVariableCouldBeFinalCheck` |
| `ast-naming-local-var-naming` | `LOCAL_VAR_NAMING` | LOW | NAMING | `LocalVariableNamingConventionsCheck` |
| `ast-naming-local-var-return` | `LOCAL_VAR_RETURN` | LOW | STYLE | `LocalVariableThenReturnCheck` |
| `ast-naming-local-variable-name` | `LOCAL_VARIABLE_NAME` | LOW | STYLE | `LocalVariableNameCheck` |
| `ast-naming-long-variable` | `LONG_VARIABLE` | LOW | STYLE | `LongVariableCheck` |
| `ast-naming-membername` | `MEMBERNAME` | LOW | STYLE | `MemberNameCheck` |
| `ast-naming-method-name` | `METHOD_NAME` | LOW | NAMING | `MethodNameCheck` |
| `ast-naming-method-naming` | `METHOD_NAMING` | LOW | NAMING | `MethodNamingConventionsCheck` |
| `ast-naming-missing-override` | `MISSING_OVERRIDE` | LOW | STYLE | `MissingOverrideCheck` |
| `ast-naming-naming-convention` | `NAMING_CONVENTION` | LOW | STYLE | `NamingConventionCheck` |
| `ast-naming-outer-type-filename` | `OUTER_TYPE_FILENAME` | LOW | STYLE | `OuterTypeFilenameCheck` |
| `ast-naming-package-case` | `PACKAGE_CASE` | LOW | NAMING | `PackageCaseCheck` |
| `ast-naming-public-constant` | `PUBLIC_CONSTANT` | LOW | NAMING | `PublicConstantFieldNamingCheck` |
| `ast-naming-shadow-field` | `SHADOW_FIELD` | MEDIUM | BAD_PRACTICE | `LocalVariableShadowingFieldCheck` |
| `ast-naming-short-class` | `SHORT_CLASS` | LOW | NAMING | `ShortClassNameCheck` |
| `ast-naming-short-method` | `SHORT_METHOD` | LOW | NAMING | `ShortMethodNameCheck` |
| `ast-naming-short-variable` | `SHORT_VARIABLE` | LOW | NAMING | `ShortVariableCheck` |
| `ast-naming-singleton-class` | `SINGLETON_CLASS` | LOW | STYLE | `SingletonClassCheck` |
| `ast-naming-suspicious-equals` | `SUSPICIOUS_EQUALS` | MEDIUM | BAD_PRACTICE | `SuspiciousEqualsMethodNameCheck` |
| `ast-naming-type-name` | `TYPE_NAME` | LOW | STYLE | `TypeNameCheck` |
| `ast-naming-uncommented-empty-method` | `UNCOMMENTED_EMPTY_METHOD` | LOW | STYLE | `EmptyMethodInAbstractClassCheck` |
| `ast-naming-unnecessary-fqn` | `UNNECESSARY_FQN` | LOW | STYLE | `UnnecessaryFullyQualifiedClassNameCheck` |
| `ast-naming-unnecessary-fqn-enum` | `UNNECESSARY_FQN_ENUM` | LOW | STYLE | `UnnecessaryFullyQualifiedEnumNameCheck` |
| `ast-naming-unused-local-var` | `UNUSED_LOCAL_VAR` | LOW | STYLE | `UnusedLocalVariableCheck` |
| `ast-naming-use-underscores-in-numeric-literals` | `USE_UNDERSCORES_IN_NUMERIC_LITERALS` | LOW | STYLE | `UseUnderscoresInNumericLiteralsCheck` |
| `ast-naming-use-utility-class` | `USE_UTILITY_CLASS` | LOW | STYLE | `UseUtilityClassCheck` |

### performance  (63 条)

| 规则 ID | 短名 | 严重度 | 类型 | 规则类 |
| ------- | ---- | ------ | ---- | ------ |
| `ast-performance-array-equality` | `ARRAY_EQUALITY` | MEDIUM | CORRECTNESS | `ArrayEqualityCheck` |
| `ast-performance-array-hashcode-tostring` | `ARRAY_HASHCODE_TOSTRING` | MEDIUM | CORRECTNESS | `ArrayHashCodeAndToStringCheck` |
| `ast-performance-array-key` | `ARRAY_KEY` | MEDIUM | BUG | `ArrayAsKeyOfSetOrMapCheck` |
| `ast-performance-array-store-incompatible` | `ARRAY_STORE_INCOMPATIBLE` | LOW | STYLE | `ArrayStoreIncompatibleCheck` |
| `ast-performance-array-too-large` | `ARRAY_TOO_LARGE` | LOW | STYLE | `ArrayTooLargeCheck` |
| `ast-performance-array-type` | `ARRAY_TYPE` | LOW | STYLE | `ArrayTypeCheck` |
| `ast-performance-avoid-array-loops` | `AVOID_ARRAY_LOOPS` | LOW | STYLE | `AvoidArrayLoopsCheck` |
| `ast-performance-avoid-calendar-date-instance` | `AVOID_CALENDAR_DATE_INSTANCE` | LOW | BAD_PRACTICE | `AvoidCalendarDateInstanceCheck` |
| `ast-performance-avoid-stringbuffer-field` | `AVOID_STRINGBUFFER_FIELD` | LOW | BAD_PRACTICE | `AvoidStringBufferFieldCheck` |
| `ast-performance-bc-bad-cast-abstract` | `BC_BAD_CAST_ABSTRACT` | MEDIUM | BUG | `BcBadCastToAbstractCollectionCheck` |
| `ast-performance-boxed-constructor` | `BOXED_CONSTRUCTOR` | LOW | STYLE | `BoxedConstructorCheck` |
| `ast-performance-collection-inappropriate` | `COLLECTION_INAPPROPRIATE` | LOW | STYLE | `CollectionInappropriateCheck` |
| `ast-performance-dm-boxed-primitive` | `DM_BOXED_PRIMITIVE` | MEDIUM | BUG | `DmBoxedPrimitiveForParsingCheck` |
| `ast-performance-dmi-collection-urls` | `DMI_COLLECTION_URLS` | MEDIUM | BUG | `DmiCollectionOfUrlsCheck` |
| `ast-performance-do-not-call-gc` | `DO_NOT_CALL_GC` | MEDIUM | BAD_PRACTICE | `DoNotCallGarbageCollectionCheck` |
| `ast-performance-dup-string` | `DUP_STRING` | LOW | STYLE | `DuplicatedStringLiteralCheck` |
| `ast-performance-ec-bad-array-compare` | `EC_BAD_ARRAY_COMPARE` | MEDIUM | BUG | `EcBadArrayCompareCheck` |
| `ast-performance-ec-bad-array-store` | `EC_BAD_ARRAY_STORE` | MEDIUM | BUG | `EcBadArrayStoreCheck` |
| `ast-performance-floating-point-loops` | `FLOATING_POINT_LOOPS` | LOW | BUG | `FloatingPointLoopsCheck` |
| `ast-performance-for-loop-variable-type` | `FOR_LOOP_VARIABLE_TYPE` | MEDIUM | BUG | `ForLoopVariableTypeCheck` |
| `ast-performance-for-size` | `FOR_SIZE` | LOW | PERFORMANCE | `ForLoopWithSizeMethodCheck` |
| `ast-performance-jdk-obsolete-collection` | `JDK_OBSOLETE_COLLECTION` | LOW | STYLE | `JdkObsoleteCollectionCheck` |
| `ast-performance-jumbled-incrementer` | `JUMBLED_INCREMENTER_2` | MEDIUM | BUG | `JumbledIncrementerLoopCheck` |
| `ast-performance-loops-same-condition` | `LOOPS_SAME_CONDITION` | LOW | BUG | `LoopsWithSameConditionCheck` |
| `ast-performance-malformed-format-string` | `MALFORMED_FORMAT_STRING` | LOW | BUG | `MalformedFormatStringCheck` |
| `ast-performance-method-return` | `METHOD_RETURN` | LOW | STYLE | `MethodReturnCheck` |
| `ast-performance-no-array-trailing-comma` | `NO_ARRAY_TRAILING_COMMA` | LOW | STYLE | `NoArrayTrailingCommaCheck` |
| `ast-performance-no-float-loop-index` | `NO_FLOAT_LOOP_INDEX` | LOW | STYLE | `DontUseFloatTypeForLoopIndicesCheck` |
| `ast-performance-non-singleton-primitive-constructor` | `NON_SINGLETON_PRIMITIVE_CONSTRUCTOR` | LOW | BUG | `NonSingletonPrimitiveConstructorInCheck` |
| `ast-performance-null-returned-tostring` | `NULL_RETURNED_TOSTRING` | LOW | STYLE | `NullReturnedToStringCheck` |
| `ast-performance-prefer-zero-length-arrays` | `PREFER_ZERO_LENGTH_ARRAYS` | LOW | BUG | `PreferZeroLengthArraysCheck` |
| `ast-performance-primitives-boxed` | `PRIMITIVES_BOXED` | MEDIUM | BAD_PRACTICE | `PrimitivesBoxedCheck` |
| `ast-performance-range-array-index` | `RANGE_ARRAY_INDEX` | MEDIUM | BUG | `RangeArrayIndexCheck` |
| `ast-performance-range-array-length` | `RANGE_ARRAY_LENGTH` | MEDIUM | BUG | `RangeArrayLengthCheck` |
| `ast-performance-range-array-offset` | `RANGE_ARRAY_OFFSET` | MEDIUM | BUG | `RangeArrayOffsetCheck` |
| `ast-performance-range-string-index` | `RANGE_STRING_INDEX` | LOW | STYLE | `RangeStringIndexCheck` |
| `ast-performance-range-string-length` | `RANGE_STRING_LENGTH` | MEDIUM | BUG | `RangeStringLengthCheck` |
| `ast-performance-return-empty-not-null` | `RETURN_EMPTY_NOT_NULL` | MEDIUM | BAD_PRACTICE | `ReturnEmptyCollectionNotNullCheck` |
| `ast-performance-return-null-collection` | `RETURN_NULL_COLLECTION` | MEDIUM | BAD_PRACTICE | `ReturnEmptyCollectionRatherThanNullCheck` |
| `ast-performance-returns-null-collection` | `RETURNS_NULL_COLLECTION` | LOW | BUG | `ReturnsNullInsteadOfEmptyCollectionCheck` |
| `ast-performance-simplify-bool` | `SIMPLIFY_BOOL` | LOW | STYLE | `SimplifyBooleanExpressionsCheck` |
| `ast-performance-simplify-conditional` | `SIMPLIFY_CONDITIONAL` | LOW | STYLE | `SimplifyConditionalCheck` |
| `ast-performance-string-buffer-constructor` | `STRING_BUFFER_CONSTRUCTOR` | LOW | STYLE | `StringBufferConstructorCheck` |
| `ast-performance-string-buffer-field-serial` | `STRING_BUFFER_FIELD_SERIAL` | LOW | BUG | `StringBufferFieldInSerialClassCheck` |
| `ast-performance-string-builder-concatenation-in-loop` | `STRING_BUILDER_CONCATENATION_IN_LOOP` | MEDIUM | PERFORMANCE | `StringBuilderConcatenationCheck` |
| `ast-performance-string-eq` | `STRING_EQ` | HIGH | BUG | `CompareStringsWithEqualsCheck` |
| `ast-performance-string-equality-with-equals` | `STRING_EQUALITY_WITH_EQUALS` | MEDIUM | CORRECTNESS | `StringEqualityWithEqualsCheck` |
| `ast-performance-string-instantiation` | `STRING_INSTANTIATION` | LOW | STYLE | `StringInstantiationCheck` |
| `ast-performance-string-literal-equality` | `STRING_LITERAL_EQUALITY` | LOW | STYLE | `StringLiteralEqualityCheck` |
| `ast-performance-synchronization-on-string-or-boxed` | `SYNCHRONIZATION_ON_STRING_OR_BOXED` | HIGH | MULTITHREADING | `SynchronizationOnStringOrBoxedCheck` |
| `ast-performance-unnecessary-boxing` | `UNNECESSARY_BOXING` | LOW | STYLE | `UnnecessaryBoxingCheck` |
| `ast-performance-unnecessary-case-change` | `UNNECESSARY_CASE_CHANGE` | LOW | STYLE | `UnnecessaryCaseChangeCheck` |
| `ast-performance-unnecessary-cast` | `UNNECESSARY_CAST` | LOW | STYLE | `UnnecessaryCastCheck` |
| `ast-performance-unnecessary-constructor` | `UNNECESSARY_CONSTRUCTOR` | LOW | STYLE | `UnnecessaryConstructorCheck` |
| `ast-performance-use-arraylist-instead-of-vector` | `USE_ARRAYLIST_INSTEAD_OF_VECTOR` | LOW | PERFORMANCE | `UseArrayListInsteadOfVectorCheck` |
| `ast-performance-use-assertequals` | `USE_ASSERTEQUALS` | LOW | STYLE | `UseAssertEqualsInsteadOfAssertTrueCheck` |
| `ast-performance-use-asserttrue` | `USE_ASSERTTRUE` | LOW | STYLE | `UseAssertTrueInsteadOfAssertEqualsCheck` |
| `ast-performance-use-collection-is-empty` | `USE_COLLECTION_IS_EMPTY` | LOW | PERFORMANCE | `UseCollectionIsEmptyCheck` |
| `ast-performance-use-indexof-char` | `USE_INDEXOF_CHAR` | LOW | PERFORMANCE | `UseIndexOfCharCheck` |
| `ast-performance-use-short-array-init` | `USE_SHORT_ARRAY_INIT` | LOW | STYLE | `UseShortArrayInitializerCheck` |
| `ast-performance-use-stringbuffer` | `USE_STRINGBUFFER` | MEDIUM | PERFORMANCE | `UseStringBufferForStringAppendsCheck` |
| `ast-performance-useless-string-valueof` | `USELESS_STRING_VALUEOF` | LOW | PERFORMANCE | `UselessStringValueOfCheck` |
| `bu-0607-big-decimal-equals` | `BIG_DECIMAL_EQUALS` | MEDIUM | CORRECTNESS | `BigDecimalEqualsCheck` |

### security  (42 条)

| 规则 ID | 短名 | 严重度 | 类型 | 规则类 |
| ------- | ---- | ------ | ---- | ------ |
| `ast-security-avoid-coded-ip` | `AVOID_CODED_IP` | LOW | STYLE | `AvoidUsingHardCodedIPCheck` |
| `ast-security-case-insensitive-comparison` | `CASE_INSENSITIVE_COMPARISON` | LOW | STYLE | `CaseInsensitiveComparisonCheck` |
| `ast-security-cleartext-socket-usage` | `CLEARTEXT_SOCKET_USAGE` | MEDIUM | SECURITY | `CleartextSocketUsageCheck` |
| `ast-security-compare-classes-by-name` | `COMPARE_CLASSES_BY_NAME` | LOW | SECURITY | `CompareClassesByNameCheck` |
| `ast-security-cors-misconfig` | `CORS_MISCONFIG` | MEDIUM | SECURITY | `CorsMisconfigurationCheck` |
| `ast-security-dc-dangerous` | `DC_DANGEROUS` | HIGH | SECURITY | `DcDangerousCheck` |
| `ast-security-dc-deadlock` | `DC_DEADLOCK` | HIGH | SECURITY | `DcDeadlockCheck` |
| `ast-security-env-var-info-exposure-ast` | `ENV_VAR_INFO_EXPOSURE_AST` | MEDIUM | SECURITY | `EnvVarInfoExposureAstCheck` |
| `ast-security-hardcoded-absolute-path` | `HARDCODED_ABSOLUTE_PATH` | MEDIUM | BUG | `DmiHardcodedAbsolutePathCheck` |
| `ast-security-hardcoded-credentials` | `HARDCODED_CREDENTIALS` | CRITICAL | SECURITY | `HardcodedCredentialsCheck` |
| `ast-security-hardcoded-ip` | `HARDCODED_IP` | MEDIUM | SECURITY | `HardcodedIpCheck` |
| `ast-security-hardcoded-password-sink-ast` | `HARDCODED_PASSWORD_SINK_AST` | HIGH | SECURITY | `HardcodedPasswordSinkAstCheck` |
| `ast-security-hardcoded-uri` | `HARDCODED_URI` | MEDIUM | STYLE | `HardcodedURICheck` |
| `ast-security-high-entropy-string` | `HIGH_ENTROPY_STRING` | LOW | SECURITY | `HighEntropyStringCheck` |
| `ast-security-information-exposure` | `INFORMATION_EXPOSURE` | MEDIUM | SECURITY | `InformationExposureCheck` |
| `ast-security-insecure-communication` | `INSECURE_COMMUNICATION` | MEDIUM | SECURITY | `InsecureCommunicationCheck` |
| `ast-security-insecure-cookie` | `INSECURE_COOKIE` | MEDIUM | SECURITY | `InsecureCookieCheck` |
| `ast-security-insecure-crypto` | `INSECURE_CRYPTO` | HIGH | SECURITY | `InsecureCryptoCheck` |
| `ast-security-insecure-temp-file-perms` | `INSECURE_TEMP_FILE_PERMS` | MEDIUM | SECURITY | `InsecureTempFilePermsCheck` |
| `ast-security-literal-sourced-hint` | `LITERAL_SOURCED_HINT` | LOW | SECURITY | `LiteralSourcedHintCheck` |
| `ast-security-loop-once` | `LOOP_ONCE` | LOW | STYLE | `LoopExecutingAtMostOnceCheck` |
| `ast-security-missing-authorization` | `MISSING_AUTHORIZATION` | HIGH | SECURITY | `MissingAuthorizationCheck` |
| `ast-security-missing-input-validation` | `MISSING_INPUT_VALIDATION` | MEDIUM | SECURITY | `MissingInputValidationCheck` |
| `ast-security-null-deref-from-return-ast` | `NULL_DEREF_FROM_RETURN_AST` | MEDIUM | SECURITY | `NullDerefFromReturnAstCheck` |
| `ast-security-obsolete-api` | `OBSOLETE_API` | LOW | BAD_PRACTICE | `ObsoleteApiCheck` |
| `ast-security-orm-sql-interpolation` | `ORM_SQL_INTERPOLATION` | HIGH | SECURITY | `OrmSqlInterpolationCheck` |
| `ast-security-path` | `PATH` | LOW | STYLE | `PathCheck` |
| `ast-security-predictable-key-password` | `PREDICTABLE_KEY_PASSWORD` | HIGH | SECURITY | `PredictableKeyPasswordCheck` |
| `ast-security-predictable-random-seed` | `PREDICTABLE_RANDOM_SEED` | MEDIUM | SECURITY | `PredictableRandomSeedCheck` |
| `ast-security-process-control` | `PROCESS_CONTROL` | HIGH | SECURITY | `ProcessControlCheck` |
| `ast-security-static-api-key` | `STATIC_API_KEY` | HIGH | SECURITY | `StaticApiKeyCheck` |
| `ast-security-unbounded-mem-alloc` | `UNBOUNDED_MEM_ALLOC` | HIGH | SECURITY | `UnboundedMemAllocCheck` |
| `ast-security-unchecked-return` | `UNCHECKED_RETURN` | MEDIUM | SECURITY | `UncheckedReturnCheck` |
| `ast-security-unlogged-security-exception` | `UNLOGGED_SECURITY_EXCEPTION` | MEDIUM | SECURITY | `UnloggedSecurityExceptionCheck` |
| `ast-security-unused-variable-assignment` | `UNUSED_VARIABLE_ASSIGNMENT` | LOW | STYLE | `UnusedVariableAstCheck` |
| `ast-security-weak-hash` | `WEAK_HASH` | MEDIUM | SECURITY | `WeakHashChecker` |
| `ast-security-weak-prng` | `WEAK_PRNG` | MEDIUM | SECURITY | `WeakPrngCheck` |
| `ast-security-weak-url-sanitization` | `WEAK_URL_SANITIZATION` | MEDIUM | SECURITY | `WeakUrlSanitizationCheck` |
| `dfa-security-insecure-temp-file` | `INSECURE_TEMP_FILE` | MEDIUM | SECURITY | `InsecureTempFileCheck` |
| `dfa-security-persistent-cookie` | `PERSISTENT_COOKIE` | LOW | SECURITY | `PersistentCookieCheck` |
| `dfa-security-runtime-exec` | `RUNTIME_EXEC` | MEDIUM | SECURITY | `RuntimeExecCheck` |
| `dfa-security-shell-error-exposure` | `SHELL_ERROR_EXPOSURE` | MEDIUM | SECURITY | `ShellErrorExposureCheck` |

### style  (354 条)

| 规则 ID | 短名 | 严重度 | 类型 | 规则类 |
| ------- | ---- | ------ | ---- | ------ |
| `ast-style-absent-codeblock-in-case` | `ABSENT_CODEBLOCK_IN_CASE` | MEDIUM | BUG | `AbsentCodeblockInCaseCheck` |
| `ast-style-ambiguous-precedence` | `AMBIGUOUS_PRECEDENCE` | MEDIUM | CORRECTNESS | `AmbiguousOperatorPrecedenceCheck` |
| `ast-style-annotation-location` | `ANNOTATION_LOCATION` | LOW | STYLE | `AnnotationLocationCheck` |
| `ast-style-annotation-on-same-line` | `ANNOTATION_ON_SAME_LINE` | LOW | STYLE | `AnnotationOnSameLineCheck` |
| `ast-style-annotation-table` | `ANNOTATION_TABLE` | LOW | STYLE | `AnnoTblCheck` |
| `ast-style-anon-inner-length` | `ANON_INNER_LENGTH` | LOW | STYLE | `AnonInnerLengthCheck` |
| `ast-style-assertion-boundary` | `ASSERTION_BOUNDARY` | LOW | STYLE | `AssertionBoundaryCheck` |
| `ast-style-assign-in-operand` | `ASSIGN_IN_OPERAND` | MEDIUM | BAD_PRACTICE | `AssignmentInOperandCheck` |
| `ast-style-assignment-in-condition` | `ASSIGNMENT_IN_CONDITION` | MEDIUM | BUG | `AssignmentInConditionCheck` |
| `ast-style-assignment-to-non-final-static` | `ASSIGNMENT_TO_NON_FINAL_STATIC` | MEDIUM | BAD_PRACTICE | `AssignmentToNonFinalStaticCheck` |
| `ast-style-atclause-order` | `ATCLAUSE_ORDER` | LOW | STYLE | `AtclauseOrderCheck` |
| `ast-style-avoid-enums` | `AVOID_ENUMS` | LOW | STYLE | `AvoidEnumsCheck` |
| `ast-style-avoid-literals-in-if` | `AVOID_LITERALS_IN_IF` | LOW | STYLE | `AvoidLiteralsInIfConditionCheck` |
| `ast-style-avoid-messagedigest-field` | `AVOID_MESSAGEDIGEST_FIELD` | MEDIUM | BAD_PRACTICE | `AvoidMessageDigestFieldCheck` |
| `ast-style-avoid-native-methods` | `AVOID_NATIVE_METHODS` | LOW | BAD_PRACTICE | `AvoidNativeMethodsCheck` |
| `ast-style-avoid-nested-blocks` | `AVOID_NESTED_BLOCKS` | LOW | STYLE | `AvoidNestedBlocksCheck` |
| `ast-style-avoid-octal-values` | `AVOID_OCTAL_VALUES` | MEDIUM | BAD_PRACTICE | `AvoidUsingOctalValuesCheck` |
| `ast-style-avoid-print-stack-trace` | `AVOID_PRINT_STACK_TRACE` | MEDIUM | BAD_PRACTICE | `AvoidPrintStackTraceCheck` |
| `ast-style-avoid-protected-field-in-final-class` | `AVOID_PROTECTED_FIELD_IN_FINAL_CLASS` | LOW | STYLE | `AvoidProtectedFieldInFinalClassCheck` |
| `ast-style-avoid-protected-method-in-final-class` | `AVOID_PROTECTED_METHOD_IN_FINAL_CLASS` | LOW | STYLE | `AvoidProtectedMethodInFinalClassCheck` |
| `ast-style-avoid-star-import` | `AVOID_STAR_IMPORT` | LOW | STYLE | `AvoidStarImportCheck` |
| `ast-style-avoid-static-import` | `AVOID_STATIC_IMPORT` | LOW | STYLE | `AvoidStaticImportCheck` |
| `ast-style-avoid-threadgroup` | `AVOID_THREADGROUP` | MEDIUM | BAD_PRACTICE | `AvoidThreadGroupCheck` |
| `ast-style-avoid-volatile` | `AVOID_VOLATILE` | LOW | BAD_PRACTICE | `AvoidUsingVolatileCheck` |
| `ast-style-avoidescapedunicode` | `AVOIDESCAPEDUNICODE` | LOW | STYLE | `AvoidEscapedUnicodeCharactersCheck` |
| `ast-style-bad-check-for-odd` | `BAD_CHECK_FOR_ODD` | LOW | BUG | `BadCheckForOddCheck` |
| `ast-style-bad-shift` | `BAD_SHIFT` | MEDIUM | BUG | `BadShiftCheck` |
| `ast-style-badly-overridden-adapter` | `BADLY_OVERRIDDEN_ADAPTER` | LOW | STYLE | `BadlyOverriddenAdapterCheck` |
| `ast-style-badsyntaxinregex` | `BADSYNTAXINREGEX` | LOW | STYLE | `BadSyntaxInRegexCheck` |
| `ast-style-bc-equals-no-hash` | `BC_EQUALS_NO_HASH` | MEDIUM | BUG | `BcEqualsNoHashcodeCallCheck` |
| `ast-style-bigdec-double` | `BIGDEC_DOUBLE` | HIGH | BUG | `BigDecimalDoubleConstructorCheck` |
| `ast-style-bit-signed-check` | `BIT_SIGNED_CHECK` | MEDIUM | BUG | `SignedBitCheckCheck` |
| `ast-style-bitwise-and` | `BITWISE_AND` | MEDIUM | BUG | `BitwiseAndCheck` |
| `ast-style-bitwise-or` | `BITWISE_OR` | MEDIUM | BUG | `BitwiseOrCheck` |
| `ast-style-bool-if` | `BOOL_IF` | MEDIUM | BUG | `BooleanLiteralInIfCheck` |
| `ast-style-bool-negation` | `BOOL_NEGATION` | MEDIUM | BUG | `BooleanLiteralNegationCheck` |
| `ast-style-boolean-method-returns-null` | `BOOLEAN_METHOD_RETURNS_NULL` | HIGH | BUG | `BooleanMethodReturnCheck` |
| `ast-style-call-super-in-constructor` | `CALL_SUPER_IN_CONSTRUCTOR` | MEDIUM | BAD_PRACTICE | `CallSuperInConstructorCheck` |
| `ast-style-call-to-thread-run` | `CALL_TO_THREAD_RUN` | HIGH | BUG | `CallToThreadRunCheck` |
| `ast-style-call-to-unsupported-method` | `CALL_TO_UNSUPPORTED_METHOD` | LOW | STYLE | `CallToUnsupportedMethodCheck` |
| `ast-style-cast-arithmetic-operand` | `CAST_ARITHMETIC_OPERAND` | LOW | STYLE | `CastArithmeticOperandCheck` |
| `ast-style-catch-nullpointer-exception` | `CATCH_NULLPOINTER_EXCEPTION_2` | MEDIUM | BUG | `CatchNullPointerExceptionCheck` |
| `ast-style-child-class-shadow-field` | `CHILD_CLASS_SHADOW_FIELD` | MEDIUM | BUG | `ChildClassShadowFieldCheck` |
| `ast-style-class-based-issue-491` | `CLASS_BASED_ISSUE_491` | LOW | BAD_PRACTICE | `ClassBasedIssueCheck` |
| `ast-style-class-field-shadow` | `CLASS_FIELD_SHADOW` | MEDIUM | BAD_PRACTICE | `ClassFieldShadowingCheck` |
| `ast-style-class-variable-visibility` | `CLASS_VAR_VISIBILITY` | LOW | STYLE | `ClassVariableVisibilityCheck` |
| `ast-style-clone-without-super` | `CLONE_WITHOUT_SUPER` | HIGH | BUG | `CloneWithoutSuperCheck` |
| `ast-style-collapsible-if` | `COLLAPSIBLE_IF` | LOW | STYLE | `CollapsibleIfCheck` |
| `ast-style-collapsible-if-statements` | `COLLAPSIBLE_IF_STATEMENTS` | LOW | STYLE | `CollapsibleIfStatementsCheck` |
| `ast-style-comment-default-access` | `COMMENT_DEFAULT_ACCESS` | LOW | STYLE | `CommentDefaultAccessCheck` |
| `ast-style-comment-info-leak` | `COMMENT_INFO_LEAK` | LOW | BAD_PRACTICE | `CommentInfoLeakCheck` |
| `ast-style-commentsindentation` | `COMMENTSINDENTATION` | LOW | STYLE | `CommentsIndentationCheck` |
| `ast-style-comparator-result` | `COMPARATOR_RESULT` | LOW | STYLE | `CheckTypeOfResultOfComparatorCheck` |
| `ast-style-compare-instead-of-assigning` | `COMPARE_INSTEAD_OF_ASSIGNING` | MEDIUM | BUG | `CompareInsteadOfAssigningCheck` |
| `ast-style-conditional-constant` | `CONDITIONAL_CONSTANT` | HIGH | BUG | `ConditionalOnConstantCheck` |
| `ast-style-confusing-ternary` | `CONFUSING_TERNARY` | MEDIUM | BAD_PRACTICE | `ConfusingTernaryCheck` |
| `ast-style-control-statement-braces` | `CONTROL_STATEMENT_BRACES` | LOW | STYLE | `ControlStatementBracesCheck` |
| `ast-style-ctor-method` | `CTOR_METHOD` | MEDIUM | BAD_PRACTICE | `MethodCallsFromConstructorCheck` |
| `ast-style-ctor-parameter-count` | `CTOR_PARAMETER_COUNT` | LOW | STYLE | `CtorParameterCountCheck` |
| `ast-style-cwe580-clone-without-super` | `CWE580_CLONE_WITHOUT_SUPER` | MEDIUM | BAD_PRACTICE | `ObjectModelCheck` |
| `ast-style-cwe600-uncaught-exception` | `CWE600_UNCAUGHT_EXCEPTION` | MEDIUM | BAD_PRACTICE | `UncaughtExceptionCheck` |
| `ast-style-dataflow-anomaly-NOT-IMPLEMENTED` | `DATAFLOW_ANOMALY_NOT_IMPLEMENTED` | LOW | STYLE | `DataflowAnomalyAnalysisCheck` |
| `ast-style-deadcode` | `DEADCODE` | LOW | CORRECTNESS | `DeadcodeCheck` |
| `ast-style-declaration-order` | `DECLARATION_ORDER` | LOW | STYLE | `DeclarationOrderCheck` |
| `ast-style-deeply-nested-if` | `DEEPLY_NESTED_IF` | MEDIUM | BAD_PRACTICE | `AvoidDeeplyNestedIfStmtsCheck` |
| `ast-style-default-charset` | `DEFAULT_CHARSET` | LOW | STYLE | `DefaultCharsetCheck` |
| `ast-style-default-package` | `DEFAULT_PACKAGE` | LOW | STYLE | `DefaultPackageCheck` |
| `ast-style-default-package-2` | `DEFAULT_PACKAGE_2` | LOW | BAD_PRACTICE | `PublicStaticFieldAccessedInSubclassCheck` |
| `ast-style-deprecated-annotation` | `DEPRECATED_ANNOTATION` | LOW | STYLE | `DeprecatedAnnotationCheck` |
| `ast-style-diamond-operator` | `DIAMOND_OPERATOR` | LOW | STYLE | `DiamondOperatorCheck` |
| `ast-style-dmi-bad-month` | `DMI_BAD_MONTH` | LOW | STYLE | `DmiBadMonthCheck` |
| `ast-style-dmi-done` | `DMI_DONE` | LOW | STYLE | `DmiDoneCheck` |
| `ast-style-dmi-random` | `DMI_RANDOM` | LOW | BUG | `DmiRandomCheck` |
| `ast-style-dmi-useless-method` | `DMI_USELESS_METHOD` | LOW | STYLE | `DmiUselessMethodCheck` |
| `ast-style-do-inside-do-privileged` | `DO_INSIDE_DO_PRIVILEGED` | LOW | STYLE | `DoInsideDoPrivilegedCheck` |
| `ast-style-dont-import-javalang` | `DONT_IMPORT_JAVALANG` | LOW | STYLE | `DontImportJavaLangCheck` |
| `ast-style-dontimportsun` | `DONTIMPORTSUN` | LOW | STYLE | `DontImportSunCheck` |
| `ast-style-double-checked-locking` | `DOUBLE_CHECKED_LOCKING` | MEDIUM | BUG | `DoubleCheckedLockingCheck` |
| `ast-style-duplicate-imports` | `DUPLICATE_IMPORTS` | LOW | STYLE | `DuplicateImportsCheck` |
| `ast-style-ec-incompatible` | `EC_INCOMPATIBLE` | MEDIUM | BUG | `EcIncompatibleTypesCheck` |
| `ast-style-ec-no-read` | `EC_NO_READ` | MEDIUM | BUG | `EcNoReadIfEqualToWriteCheck` |
| `ast-style-ec-null-vararg` | `EC_NULL_VARARG` | MEDIUM | BUG | `EcNullArgToVarargCheck` |
| `ast-style-ec-unrelated` | `EC_UNRELATED` | LOW | STYLE | `EcUnrelatedTypesCheck` |
| `ast-style-ec-unrelated-iface` | `EC_UNRELATED_IFACE` | LOW | STYLE | `EcUnrelatedInterfacesCheck` |
| `ast-style-empty-block` | `EMPTY_BLOCK` | LOW | STYLE | `EmptyBlockCheck` |
| `ast-style-empty-constructor` | `EMPTY_CONSTRUCTOR` | LOW | STYLE | `EmptyConstructorCheck` |
| `ast-style-empty-control-statement` | `STYLE_EMPTY_CONTROL_STATEMENT` | LOW | STYLE | `EmptyControlStatementCheck` |
| `ast-style-empty-finally-block` | `EMPTY_FINALLY_BLOCK` | LOW | STYLE | `EmptyFinallyBlockCheck` |
| `ast-style-empty-if-stmt` | `EMPTY_IF_STMT` | MEDIUM | BUG | `EmptyIfStmtCheck` |
| `ast-style-empty-method-in-abstract` | `EMPTY_METHOD_IN_ABSTRACT` | LOW | STYLE | `EmptyMethodInAbstractCheck` |
| `ast-style-empty-statement` | `EMPTY_STATEMENT` | LOW | STYLE | `EmptyStatementCheck` |
| `ast-style-empty-statement-2` | `EMPTY_STATEMENT_2` | LOW | STYLE | `EmptyStatementUsageCheck` |
| `ast-style-empty-statement-block` | `EMPTY_STATEMENT_BLOCK` | LOW | BAD_PRACTICE | `EmptyStatementBlockCheck` |
| `ast-style-empty-switch` | `EMPTY_SWITCH` | MEDIUM | BUG | `EmptySwitchStatementsCheck` |
| `ast-style-empty-sync-block` | `EMPTY_SYNC_BLOCK` | LOW | BUG | `EmptySyncBlockCheck` |
| `ast-style-empty-try-block` | `EMPTY_TRY_BLOCK` | LOW | STYLE | `EmptyTryBlockCheck` |
| `ast-style-empty-while` | `EMPTY_WHILE` | MEDIUM | BUG | `EmptyWhileStmtCheck` |
| `ast-style-empty-zip-entry` | `EMPTY_ZIP_ENTRY` | LOW | STYLE | `EmptyZipEntryCheck` |
| `ast-style-emptylineseparatorcheck-cs` | `EMPTYLINESEPARATORCHECK_CS` | LOW | STYLE | `EmptyLineSeparatorCheck` |
| `ast-style-emptystaticinitializer` | `EMPTYSTATICINITIALIZER` | LOW | STYLE | `EmptyStaticInitializerCheck` |
| `ast-style-enumabstractmethod` | `ENUMABSTRACTMETHOD` | LOW | BUG | `EnumAbstractMethodCheck` |
| `ast-style-enumidiom-sb` | `ENUMIDIOM_SB` | LOW | STYLE | `EnumIdiomCheck` |
| `ast-style-env-var-info-exposure` | `ENV_VAR_INFO_EXPOSURE` | MEDIUM | BAD_PRACTICE | `EnvVarInfoExposureCheck` |
| `ast-style-equal-comparison` | `EQUAL_COMPARISON` | HIGH | BUG | `EqualComparisonCheck` |
| `ast-style-equals-arg-type` | `EQUALS_ARG_TYPE` | MEDIUM | BUG | `EqualsArgumentTypeCheck` |
| `ast-style-equals-hashcode` | `EQUALS_HASHCODE` | MEDIUM | BUG | `EqualsHashCodeCheck` |
| `ast-style-equals-no-object` | `EQUALS_NO_OBJECT` | HIGH | BUG | `EqualsNoObjectCheck` |
| `ast-style-esync-empty` | `ESYNC_EMPTY` | LOW | STYLE | `EsyncEmptySyncCheck` |
| `ast-style-explicit-finalize` | `EXPLICIT_FINALIZE_2` | MEDIUM | BUG | `ExplicitFinalizeCallCheck` |
| `ast-style-explicitinitialization` | `EXPLICITINITIALIZATION` | LOW | STYLE | `ExplicitInitializationCheck` |
| `ast-style-expose-internal-representation` | `EXPOSE_INTERNAL_REPRESENTATION` | MEDIUM | CORRECTNESS | `ExposeInternalRepresentationCheck` |
| `ast-style-expose-mutable-rep2` | `EXPOSE_MUTABLE_REP2` | MEDIUM | BUG | `ExposeMutableRepStorageCheck` |
| `ast-style-extends-object` | `EXTENDS_OBJECT` | LOW | STYLE | `ExtendsObjectCheck` |
| `ast-style-fallthrough` | `FALLTHROUGH` | LOW | STYLE | `FallThroughCheck` |
| `ast-style-fe-floating-point` | `FE_FLOATING_POINT` | MEDIUM | BUG | `FeFloatingPointCheck` |
| `ast-style-fi-useless-block` | `FI_USELESS_BLOCK` | LOW | STYLE | `FiUselessBlockCheck` |
| `ast-style-fieldselfassignment` | `FIELDSELFASSIGNMENT` | LOW | BUG | `FieldSelfAssignmentCheck` |
| `ast-style-filetabcharacter` | `FILETABCHARACTER` | LOW | STYLE | `FileTabCharacterCheck` |
| `ast-style-final-class` | `FINAL_CLASS` | LOW | STYLE | `FinalClassCheck` |
| `ast-style-final-parameters` | `FINAL_PARAMETERS` | LOW | STYLE | `FinalParametersCheck` |
| `ast-style-for-foreach` | `FOR_FOREACH` | LOW | STYLE | `ForLoopCanBeForeachCheck` |
| `ast-style-for-loop-false-condition` | `FOR_LOOP_FALSE_CONDITION` | LOW | BUG | `ForLoopFalseConditionCheck` |
| `ast-style-for-var-count` | `FOR_VAR_COUNT` | LOW | STYLE | `ForLoopVariableCountCheck` |
| `ast-style-form-get-password` | `FORM_GET_PASSWORD` | MEDIUM | SECURITY | `FormGetPasswordCheck` |
| `ast-style-he-hashcode-signature` | `HE_HASHCODE_SIGNATURE` | HIGH | BUG | `HeSignatureDeclaresHashcodeOfObjectCheck` |
| `ast-style-hiddenfield` | `HIDDENFIELD` | LOW | STYLE | `HiddenFieldCheck` |
| `ast-style-hide-utility-class-ctor` | `HIDE_UTILITY_CLASS_CTOR` | LOW | STYLE | `HideUtilityClassConstructorCheck` |
| `ast-style-ic-superclass-generic` | `IC_SUPERCLASS_GENERIC` | MEDIUM | BUG | `IcSuperclassGenericCheck` |
| `ast-style-icast-int-mult-long` | `ICAST_INT_MULT_LONG` | MEDIUM | BUG | `IcastIntegerMultiplyCastToLongCheck` |
| `ast-style-idempotentoperations` | `IDEMPOTENTOPERATIONS` | LOW | STYLE | `IdempotentOperationsCheck` |
| `ast-style-if-else-stmt` | `IF_ELSE_STMT` | LOW | STYLE | `IfElseStmtCheck` |
| `ast-style-ignored-result` | `IGNORED_RESULT` | MEDIUM | CORRECTNESS | `ResultOfMethodCallIgnoredCheck` |
| `ast-style-iil-deref-readline` | `IIL_DEREF_READLINE` | MEDIUM | BUG | `IilDereOfReadlineCheck` |
| `ast-style-il-container-sequencing` | `IL_CONTAINER_SEQUENCING` | MEDIUM | BUG | `IlContainerSequencingCheck` |
| `ast-style-im-average` | `IM_AVERAGE` | MEDIUM | BUG | `ImAverageComputationCheck` |
| `ast-style-im-bad-check-odd` | `IM_BAD_CHECK_ODD` | MEDIUM | BUG | `ImBadCheckForOddCheck` |
| `ast-style-immutablefield` | `IMMUTABLEFIELD` | LOW | STYLE | `ImmutableFieldCheck` |
| `ast-style-import-control` | `IMPORT_CONTROL` | LOW | STYLE | `ImportControlCheck` |
| `ast-style-import-same-package` | `IMPORT_SAME_PACKAGE` | LOW | STYLE | `ImportFromSamePackageCheck` |
| `ast-style-incompatiblemask` | `INCOMPATIBLEMASK` | LOW | BUG | `IncompatibleMaskCheck` |
| `ast-style-incomplete-class-declaration` | `INCOMPLETE_CLASS_DECLARATION` | LOW | STYLE | `IncompleteClassDeclarationCheck` |
| `ast-style-incomplete-cleanup` | `INCOMPLETE_CLEANUP` | MEDIUM | RESOURCE | `IncompleteCleanupCheck` |
| `ast-style-indentation` | `INDENTATION` | LOW | STYLE | `IndentationCheck` |
| `ast-style-infinite-loop` | `INFINITE_LOOP` | MEDIUM | BUG | `InfiniteLoopCheck` |
| `ast-style-info-exposure-in-comment` | `INFO_EXPOSURE_IN_COMMENT` | MEDIUM | SECURITY | `InfoExposureInCommentCheck` |
| `ast-style-info-exposure-querystring` | `INFO_EXPOSURE_QUERYSTRING` | MEDIUM | SECURITY | `InfoExposureQueryStringCheck` |
| `ast-style-inner-assignment` | `INNER_ASSIGNMENT` | LOW | STYLE | `InnerAssignmentCheck` |
| `ast-style-insecure-temp-file-perms-DEDUPED` | `INSECURE_TEMP_FILE_PERMS_DEDUPED` | LOW | SECURITY | `InsecureTempFilePermsCheck` |
| `ast-style-instantiationtogetclass` | `INSTANTIATIONTOGETCLASS` | LOW | STYLE | `InstantiationToGetClassCheck` |
| `ast-style-insufficient-session-expiration` | `INSUFFICIENT_SESSION_EXPIRATION` | MEDIUM | BAD_PRACTICE | `InsufficientSessionExpirationCheck` |
| `ast-style-int-division-cast-to-double` | `INT_DIVISION_CAST_TO_DOUBLE` | LOW | STYLE | `IntDivisionCastToDoubleCheck` |
| `ast-style-interface-constants` | `INTERFACE_CONSTANTS` | LOW | STYLE | `ConstantsInInterfaceCheck` |
| `ast-style-io-resource-both` | `IO_RESOURCE_BOTH` | MEDIUM | BUG | `IoResourceBothCheck` |
| `ast-style-iterator-idiomatic` | `ITERATOR_IDIOMATIC` | LOW | STYLE | `IteratorIdiomaticCheck` |
| `ast-style-javadoc-method` | `JAVADOC_METHOD` | LOW | STYLE | `JavadocMethodCheck` |
| `ast-style-javadocparagraph` | `JAVADOCPARAGRAPH` | LOW | STYLE | `JavadocParagraphCheck` |
| `ast-style-javadoctype` | `JAVADOCTYPE` | LOW | STYLE | `JavadocTypeCheck` |
| `ast-style-junit-message` | `JUNIT_MESSAGE` | LOW | STYLE | `JUnitAssertionsShouldIncludeMessageCheck` |
| `ast-style-junit-no-assert` | `JUNIT_NO_ASSERT` | MEDIUM | BUG | `JUnitTestsShouldIncludeAssertCheck` |
| `ast-style-lambda-body-length` | `LAMBDA_BODY_LENGTH` | LOW | STYLE | `LambdaBodyLengthCheck` |
| `ast-style-lambda-param-shadowing` | `LAMBDA_PARAM_SHADOWING` | LOW | STYLE | `LambdaParameterShadowingCheck` |
| `ast-style-left-curly` | `LEFT_CURLY` | LOW | STYLE | `LeftCurlyCheck` |
| `ast-style-line-length` | `LINE_LENGTH` | LOW | STYLE | `LineLengthCheck` |
| `ast-style-literal-boolean-condition` | `LITERAL_BOOLEAN_CONDITION` | MEDIUM | BUG | `LiteralBooleanConditionCheck` |
| `ast-style-literals-first` | `LITERALS_FIRST` | MEDIUM | BUG | `LiteralsFirstInComparisonsCheck` |
| `ast-style-locale-dependent-case-conversion` | `LOCALE_DEPENDENT_CASE_CONVERSION` | LOW | I18N | `LocaleDependentCaseConversionCheck` |
| `ast-style-locale-root` | `LOCALE_ROOT` | LOW | STYLE | `LocaleRootCheck` |
| `ast-style-log-info-leak` | `LOG_INFO_LEAK` | MEDIUM | SECURITY | `LogInfoLeakCheck` |
| `ast-style-logger-final` | `LOGGER_FINAL` | MEDIUM | BAD_PRACTICE | `LoggerShouldBeFinalCheck` |
| `ast-style-loggers-declaration` | `LOGGERS_DECLARATION` | LOW | STYLE | `LoggersDeclarationCheck` |
| `ast-style-loop-multi-breaks` | `LOOP_MULTI_BREAKS` | LOW | STYLE | `LoopsWithMultipleBreaksCheck` |
| `ast-style-magic-number` | `MAGIC_NUMBER` | LOW | STYLE | `MagicNumberCheck` |
| `ast-style-malformedsync` | `MALFORMEDSYNC` | LOW | STYLE | `MalformedSyncCheck` |
| `ast-style-math-op-literal` | `MATH_OP_LITERAL` | LOW | STYLE | `MathematicalOperationCheck` |
| `ast-style-mc-returns-void` | `MC_RETURNS_VOID` | HIGH | BUG | `MCReturnsVoidCheck` |
| `ast-style-method-arg-could-be-final` | `METHOD_ARG_COULD_BE_FINAL` | LOW | STYLE | `MethodArgumentCouldBeFinalCheck` |
| `ast-style-method-too-complex` | `METHOD_TOO_COMPLEX` | MEDIUM | BUG | `MethodTooComplexCheck` |
| `ast-style-method-too-long` | `METHOD_TOO_LONG` | LOW | STYLE | `MethodTooLongCheck` |
| `ast-style-mf-class-masks-field` | `MF_CLASS_MASKS_FIELD` | LOW | STYLE | `MfClassMasksFieldCheck` |
| `ast-style-mf-method-masks-field` | `MF_METHOD_MASKS_FIELD` | LOW | STYLE | `MfMethodMasksFieldCheck` |
| `ast-style-missing-break` | `MISSING_BREAK` | LOW | CORRECTNESS | `MissingBreakCheck` |
| `ast-style-missing-crypto-step` | `MISSING_CRYPTO_STEP` | MEDIUM | BAD_PRACTICE | `MissingCryptoStepCheck` |
| `ast-style-missing-deprecated` | `MISSING_DEPRECATED` | MEDIUM | BUG | `MissingDeprecatedCheck` |
| `ast-style-missing-password-masking` | `MISSING_PASSWORD_MASKING` | MEDIUM | SECURITY | `MissingPasswordMaskingCheck` |
| `ast-style-missing-serial-version-uid` | `MISSING_SERIAL_VERSION_UID` | MEDIUM | BUG | `MissingSerialVersionUIDCheck` |
| `ast-style-missing-static-in-non-instantiable` | `MISSING_STATIC_IN_NON_INSTANTIABLE` | MEDIUM | BAD_PRACTICE | `MissingStaticMethodInNonInstantiableClassCheck` |
| `ast-style-missing-switch-default` | `MISSING_SWITCH_DEFAULT` | LOW | STYLE | `MissingSwitchDefaultCheck` |
| `ast-style-missingjavadocmethod` | `MISSINGJAVADOCMETHOD` | LOW | STYLE | `MissingJavadocMethodCheck` |
| `ast-style-missingjavadocpackage` | `MISSINGJAVADOCPACKAGE` | LOW | STYLE | `MissingJavadocPackageCheck` |
| `ast-style-missingjavadoctype` | `MISSINGJAVADOCTYPE` | LOW | STYLE | `MissingJavadocTypeCheck` |
| `ast-style-modifier-order` | `MODIFIER_ORDER` | LOW | STYLE | `ModifierOrderCheck` |
| `ast-style-more-than-one-logger` | `MORE_THAN_ONE_LOGGER` | MEDIUM | BAD_PRACTICE | `MoreThanOneLoggerCheck` |
| `ast-style-multiple-binds-same-port` | `MULTIPLE_BINDS_SAME_PORT` | HIGH | BUG | `MultipleBindsSamePortCheck` |
| `ast-style-multiple-var-decls` | `MULTIPLE_VAR_DECLS` | LOW | STYLE | `MultipleVariableDeclarationsCheck` |
| `ast-style-mutable-enum-field` | `MUTABLE_ENUM_FIELD` | LOW | BAD_PRACTICE | `MutableEnumFieldCheck` |
| `ast-style-mutable-static-should-be-final` | `MUTABLE_STATIC_SHOULD_BE_FINAL` | MEDIUM | MALICIOUS_CODE | `MutableStaticShouldBeFinalCheck` |
| `ast-style-nested-block` | `NESTED_BLOCK` | LOW | STYLE | `NestedBlockCheck` |
| `ast-style-nested-for-depth` | `NESTED_FOR_DEPTH` | LOW | STYLE | `NestedForDepthCheck` |
| `ast-style-nested-if-depth` | `NESTED_IF_DEPTH` | LOW | STYLE | `NestedIfDepthCheck` |
| `ast-style-nested-switch` | `NESTED_SWITCH` | LOW | STYLE | `NestedSwitchCheck` |
| `ast-style-newline-at-eof` | `NEWLINE_AT_EOF` | LOW | STYLE | `NewlineAtEndOfFileCheck` |
| `ast-style-nm-confusing` | `NM_CONFUSING` | LOW | NAMING | `NmConfusingInspectionCheck` |
| `ast-style-nm-same-name-super` | `NM_SAME_NAME_SUPER` | LOW | NAMING | `NmSameSimpleNameAsSuperCheck` |
| `ast-style-no-effect-statement` | `NO_EFFECT_STATEMENT` | LOW | BAD_PRACTICE | `NoEffectStatementCheck` |
| `ast-style-no-log-statement` | `NO_LOG_STATEMENT` | LOW | PERFORMANCE | `NoLogStatementCheck` |
| `ast-style-no-long-literal` | `NO_LONG_LITERAL` | LOW | STYLE | `NoLongLiteralCheck` |
| `ast-style-no-package` | `NO_PACKAGE` | LOW | STYLE | `NoPackageCheck` |
| `ast-style-nocodeinfile` | `NOCODEINFILE` | LOW | STYLE | `NoCodeInFileCheck` |
| `ast-style-noenumtrailingcomma` | `NOENUMTRAILINGCOMMA` | LOW | STYLE | `NoEnumTrailingCommaCheck` |
| `ast-style-non-case-label` | `NON_CASE_LABEL` | MEDIUM | BUG | `NonCaseLabelInSwitchStatementCheck` |
| `ast-style-non-serializable-class` | `NON_SERIALIZABLE_CLASS` | MEDIUM | BUG | `NonSerializableClassInSerializableContextCheck` |
| `ast-style-non-serializable-in-session` | `NON_SERIALIZABLE_IN_SESSION` | MEDIUM | BUG | `NonSerializableInSessionCheck` |
| `ast-style-non-static-initializer` | `NON_STATIC_INITIALIZER` | LOW | STYLE | `NonStaticInitializerCheck` |
| `ast-style-notify` | `NOTIFY` | LOW | STYLE | `NotifyCheck` |
| `ast-style-np-sync-unshared` | `NP_SYNC_UNSHARED` | MEDIUM | BUG | `NpSyncOnUnsharedVarCheck` |
| `ast-style-null-assignment` | `NULL_ASSIGNMENT` | LOW | STYLE | `NullAssignmentCheck` |
| `ast-style-null-deref-from-return` | `NULL_DEREF_FROM_RETURN` | MEDIUM | BUG | `NullDerefFromReturnCheck` |
| `ast-style-objectsequality` | `OBJECTSEQUALITY` | LOW | STYLE | `ObjectsEqualityCheck` |
| `ast-style-one-statement-per-line` | `ONE_STATEMENT_PER_LINE` | LOW | STYLE | `OneStatementPerLineCheck` |
| `ast-style-only-one-return` | `ONLY_ONE_RETURN` | LOW | STYLE | `OnlyOneReturnCheck` |
| `ast-style-only-one-return-strict` | `ONLY_ONE_RETURN_STRICT` | LOW | STYLE | `OnlyOneReturnPerMethodCheck` |
| `ast-style-operatorwrap` | `OPERATORWRAP` | LOW | STYLE | `OperatorWrapCheck` |
| `ast-style-optional-get` | `OPTIONAL_GET` | MEDIUM | BUG | `OptionalGetCheck` |
| `ast-style-optionalreturn` | `OPTIONALRETURN` | LOW | STYLE | `OptionalReturnCheck` |
| `ast-style-outertypenumber` | `OUTERTYPENUMBER` | LOW | STYLE | `OuterTypeNumberCheck` |
| `ast-style-overload-methods-order` | `OVERLOAD_METHODS_ORDER` | LOW | STYLE | `OverloadMethodsDeclarationOrderCheck` |
| `ast-style-overly-broad-catch` | `OVERLY_BROAD_CATCH` | LOW | BAD_PRACTICE | `OverlyBroadCatchCheck` |
| `ast-style-overridingmethodmustinvokesuper-sb` | `OVERRIDINGMETHODMUSTINVOKESUPER_SB` | LOW | STYLE | `OverridingMethodMustInvokeSuperCheck` |
| `ast-style-package-annotation` | `PACKAGE_ANNOTATION` | LOW | STYLE | `PackageAnnotationCheck` |
| `ast-style-package-declaration` | `PACKAGE_DECLARATION` | LOW | STYLE | `PackageDeclarationCheck` |
| `ast-style-package-info` | `PACKAGE_INFO` | LOW | STYLE | `PackageInfoCheck` |
| `ast-style-param-hides-field` | `PARAM_HIDES_FIELD` | MEDIUM | CORRECTNESS | `ParameterHidesFieldCheck` |
| `ast-style-paren-method` | `PAREN_METHOD` | LOW | STYLE | `UnnecessaryParenthesesMethodCallsCheck` |
| `ast-style-parenpad` | `PARENPAD` | LOW | STYLE | `ParenPadCheck` |
| `ast-style-poor-code-quality` | `POOR_CODE_QUALITY` | LOW | BAD_PRACTICE | `PoorCodeQualityCheck` |
| `ast-style-predictable-salt` | `PREDICTABLE_SALT` | HIGH | SECURITY | `PredictableSaltCheck` |
| `ast-style-premature-declaration` | `PREMATURE_DECLARATION` | LOW | STYLE | `PrematureDeclarationCheck` |
| `ast-style-preserve-stack` | `PRESERVE_STACK` | MEDIUM | BAD_PRACTICE | `PreserveStackTraceCheck` |
| `ast-style-print-stack-trace` | `PRINT_STACK_TRACE` | MEDIUM | BUG | `PrintStackTraceCheck` |
| `ast-style-printf-fail` | `PRINTF_FAIL` | LOW | STYLE | `PrintfFailCheck` |
| `ast-style-printf-format` | `PRINTF_FORMAT` | LOW | STYLE | `PrintfFormatCheck` |
| `ast-style-private-ctors-final` | `PRIVATE_CTORS_FINAL` | LOW | STYLE | `ClassWithOnlyPrivateConstructorsShouldBeFinalCheck` |
| `ast-style-private-members` | `PRIVATE_MEMBERS` | LOW | STYLE | `ClassMembersShouldBePrivateCheck` |
| `ast-style-proper-logger` | `PROPER_LOGGER` | LOW | NAMING | `ProperLoggerCheck` |
| `ast-style-public-static-mutable-field` | `PUBLIC_STATIC_MUTABLE_FIELD` | MEDIUM | BAD_PRACTICE | `PublicStaticMutableFieldCheck` |
| `ast-style-publicfieldaccessedinsynchronizedcontext-sb` | `PUBLICFIELDACCESSEDINSYNCHRONIZEDCONTEXT_SB` | LOW | STYLE | `PublicFieldAccessedInSynchronizedContextCheck` |
| `ast-style-publicmethodspublicfield-sb` | `PUBLICMETHODSPUBLICFIELD_SB` | LOW | STYLE | `PublicMethodsPublicFieldCheck` |
| `ast-style-publicprotectedfieldinenum` | `PUBLICPROTECTEDFIELDINENUM` | LOW | BUG | `PublicProtectedFieldInEnumCheck` |
| `ast-style-pz-reuse-entry` | `PZ_REUSE_ENTRY` | MEDIUM | BUG | `PzReuseEntryCheck` |
| `ast-style-qba-questionable-boolean` | `QBA_QUESTIONABLE_BOOLEAN` | MEDIUM | BUG | `QbaQuestionableBooleanCheck` |
| `ast-style-questionablebooleanassignment` | `QUESTIONABLEBOOLEANASSIGNMENT` | LOW | STYLE | `QuestionableBooleanAssignmentCheck` |
| `ast-style-random-iv-cbc` | `RANDOM_IV_CBC` | MEDIUM | BAD_PRACTICE | `RandomIvCheck` |
| `ast-style-reachable-assertion` | `REACHABLE_ASSERTION` | MEDIUM | BAD_PRACTICE | `ReachableAssertionCheck` |
| `ast-style-reassign-loop-var` | `REASSIGN_LOOP_VAR` | MEDIUM | BUG | `AvoidReassigningLoopVariablesCheck` |
| `ast-style-reassign-parameter` | `REASSIGN_PARAMETER` | MEDIUM | BAD_PRACTICE | `AvoidReassigningParametersCheck` |
| `ast-style-redirect-without-exit` | `REDIRECT_WITHOUT_EXIT` | HIGH | BUG | `RedirectWithoutExitCheck` |
| `ast-style-reduce-visibility` | `REDUCE_VISIBILITY` | LOW | STYLE | `ReduceVisibilityCheck` |
| `ast-style-redundant-import` | `REDUNDANT_IMPORT` | LOW | STYLE | `RedundantImportCheck` |
| `ast-style-redundant-init` | `REDUNDANT_INIT` | LOW | STYLE | `RedundantFieldInitializerCheck` |
| `ast-style-redundant-method` | `REDUNDANT_METHOD` | LOW | STYLE | `RedundantMethodCallCheck` |
| `ast-style-regex-group` | `REGEX_GROUP` | LOW | STYLE | `RegexGroupCheck` |
| `ast-style-relational-symmetry` | `RELATIONAL_SYMMETRY` | LOW | STYLE | `RelationalOperatorSymmetryCheck` |
| `ast-style-requirethis` | `REQUIRETHIS` | LOW | STYLE | `RequireThisCheck` |
| `ast-style-return-bool` | `RETURN_BOOL` | LOW | STYLE | `ReturnBooleanLiteralCheck` |
| `ast-style-return-from-finally` | `RETURN_FROM_FINALLY` | HIGH | BUG | `ReturnFromFinallyBlockCheck` |
| `ast-style-return-in-finally` | `RETURN_IN_FINALLY` | HIGH | BUG | `ReturnInFinallyBlockCheck` |
| `ast-style-rightcurly` | `RIGHTCURLY` | LOW | STYLE | `RightCurlyCheck` |
| `ast-style-rv-remove-repeat-calls` | `RV_REMOVE_REPEAT_CALLS` | LOW | STYLE | `RvRemoveRepeatCallsCheck` |
| `ast-style-rv-return-ignored` | `RV_RETURN_IGNORED` | MEDIUM | BUG | `RvReturnValueIgnoredCheck` |
| `ast-style-se-bad-field` | `SE_BAD_FIELD` | MEDIUM | BUG | `NonSerializableFieldCheck` |
| `ast-style-seeded-random` | `SEEDED_RANDOM` | MEDIUM | SECURITY | `SeededRandomCheck` |
| `ast-style-self-assignment` | `SELF_ASSIGNMENT` | MEDIUM | CORRECTNESS | `SelfAssignmentCheck` |
| `ast-style-separatorwrap` | `SEPARATORWRAP` | LOW | STYLE | `SeparatorWrapCheck` |
| `ast-style-serializable-subclass` | `SERIALIZABLE_SUBCLASS` | LOW | BAD_PRACTICE | `SerializableSubclassCheck` |
| `ast-style-serializablesuperconstructor` | `SERIALIZABLESUPERCONSTRUCTOR` | LOW | STYLE | `SerializableSuperConstructorCheck` |
| `ast-style-si-instance-before-final` | `SI_INSTANCE_BEFORE_FINAL` | MEDIUM | BUG | `SiInstanceBeforeFinalCheck` |
| `ast-style-signofaddition` | `SIGNOFADDITION` | LOW | STYLE | `SignOfAdditionCheck` |
| `ast-style-simpledateformatwithoutlocale` | `SIMPLEDATEFORMATWITHOUTLOCALE` | LOW | BUG | `SimpleDateFormatWithoutLocaleCheck` |
| `ast-style-simplifiedateformat-locale` | `SIMPLIFIEDATEFORMAT_LOCALE` | MEDIUM | BAD_PRACTICE | `SimpleDateFormatNeedsLocaleCheck` |
| `ast-style-simplify-bool-expr` | `SIMPLIFY_BOOL_EXPR` | LOW | STYLE | `SimplifyBooleanExpressionCheck` |
| `ast-style-simplify-bool-return` | `SIMPLIFY_BOOL_RETURN` | LOW | STYLE | `SimplifyBooleanReturnsCheck` |
| `ast-style-singlespaceseparator` | `SINGLESPACESEPARATOR` | LOW | STYLE | `SingleSpaceSeparatorCheck` |
| `ast-style-singleton-returning-new` | `SINGLETON_RETURNING_NEW` | LOW | BAD_PRACTICE | `SingletonClassReturningNewInstanceCheck` |
| `ast-style-singular-field` | `SINGULAR_FIELD` | LOW | STYLE | `SingularFieldCheck` |
| `ast-style-sleepwithoutinterrupt` | `SLEEPWITHOUTINTERRUPT` | LOW | STYLE | `SleepWithoutInterruptCheck` |
| `ast-style-st-write-static-from-instance` | `ST_WRITE_STATIC_FROM_INSTANCE` | MEDIUM | BUG | `StaticWriteFromInstanceCheck` |
| `ast-style-standard-functional` | `STANDARD_FUNCTIONAL` | LOW | STYLE | `StandardFunctionalInterfaceCheck` |
| `ast-style-startwheregreaterthanend` | `STARTWHEREGREATERTHANEND` | LOW | BUG | `StartWhereGreaterThanEndCheck` |
| `ast-style-staticmethodneednotbesynchronized-sb` | `STATICMETHODNEEDNOTBESYNCHRONIZED_SB` | LOW | STYLE | `StaticMethodNeedNotBeSynchronizedCheck` |
| `ast-style-string-eq-operator` | `STRING_EQ_OPERATOR` | MEDIUM | BUG | `StringEqOperatorCheck` |
| `ast-style-super-clone` | `SUPER_CLONE` | MEDIUM | BUG | `SuperCloneCheck` |
| `ast-style-suppress-warnings-NOT-IMPLEMENTED` | `SUPPRESS_WARNINGS_NOT_IMPLEMENTED` | LOW | STYLE | `SuppressWarningsCheck` |
| `ast-style-suspicious-comment` | `SUSPICIOUS_COMMENT` | INFO | BAD_PRACTICE | `SuspiciousCommentCheck` |
| `ast-style-suspiciousintegerdivision` | `SUSPICIOUSINTEGERDIVISION` | LOW | STYLE | `SuspiciousIntegerDivisionCheck` |
| `ast-style-switch-default-last` | `SWITCH_DEFAULT_LAST` | LOW | STYLE | `SwitchDefaultLastCheck` |
| `ast-style-switch-density` | `SWITCH_DENSITY` | LOW | STYLE | `SwitchDensityCheck` |
| `ast-style-switch-few-cases` | `SWITCH_FEW_CASES` | LOW | STYLE | `SwitchWithTooFewCasesCheck` |
| `ast-style-switch-no-default` | `SWITCH_NO_DEFAULT` | MEDIUM | BUG | `SwitchWithoutDefaultCheck` |
| `ast-style-swl-sleep-lock` | `SWL_SLEEP_LOCK` | MEDIUM | BUG | `SwlSleepWithLockCheck` |
| `ast-style-synchronized-lock` | `SYNCHRONIZED_LOCK` | MEDIUM | BUG | `SynchronizedLockCheck` |
| `ast-style-system-exit-usage` | `SYSTEM_EXIT_USAGE` | MEDIUM | BAD_PRACTICE | `SystemExitUsageCheck` |
| `ast-style-system-out-println` | `STYLE_SYSTEM_OUT` | LOW | STYLE | `SystemOutCheck` |
| `ast-style-system-out-println` | `SYSTEM_OUT_PRINTLN` | LOW | STYLE | `SystemOutPrintlnCheck` |
| `ast-style-system-println` | `SYSTEM_PRINTLN` | MEDIUM | BAD_PRACTICE | `SystemPrintlnCheck` |
| `ast-style-tab-character` | `TAB_CHARACTER` | LOW | STYLE | `TabCharacterCheck` |
| `ast-style-test-no-assertion` | `TEST_NO_ASSERTION` | MEDIUM | CORRECTNESS | `TestWithoutAssertionCheck` |
| `ast-style-thread-in-servlet` | `THREAD_IN_SERVLET` | HIGH | BAD_PRACTICE | `ThreadInServletCheck` |
| `ast-style-todo-comment` | `TODO_COMMENT` | LOW | STYLE | `TodoCommentCheck` |
| `ast-style-too-many-fields` | `TOO_MANY_FIELDS` | LOW | STYLE | `TooManyFieldsCheck` |
| `ast-style-too-many-locals` | `TOO_MANY_LOCALS` | LOW | STYLE | `TooManyLocalsCheck` |
| `ast-style-too-many-methods` | `TOO_MANY_METHODS` | LOW | STYLE | `TooManyMethodsCheck` |
| `ast-style-too-many-parameters` | `TOO_MANY_PARAMETERS` | LOW | STYLE | `TooManyParametersCheck` |
| `ast-style-uc-useless-object` | `UC_USELESS_OBJECT` | LOW | STYLE | `UcUselessObjectCheck` |
| `ast-style-unchecked-loop-condition` | `UNCHECKED_LOOP_CONDITION` | LOW | BUG | `UncheckedLoopConditionCheck` |
| `ast-style-uncleared-sensitive-info` | `UNCLEARED_SENSITIVE_INFO` | MEDIUM | SECURITY | `UnclearedSensitiveInfoCheck` |
| `ast-style-unconfirmed-cast` | `UNCONFIRMED_CAST` | MEDIUM | BUG | `UnconfirmedCastCheck` |
| `ast-style-unnecessary-local-before-return` | `UNNECESSARY_LOCAL_BEFORE_RETURN` | LOW | STYLE | `UnnecessaryLocalBeforeReturnCheck` |
| `ast-style-unnecessary-parens` | `UNNECESSARY_PARENS` | LOW | STYLE | `UnnecessaryParenthesesCheck` |
| `ast-style-unnecessary-return` | `UNNECESSARY_RETURN` | LOW | STYLE | `UnnecessaryReturnCheck` |
| `ast-style-unnecessaryenumcase-sb` | `UNNECESSARYENUMCASE_SB` | LOW | STYLE | `UnnecessaryEnumCaseCheck` |
| `ast-style-unprotected-cred-transport` | `UNPROTECTED_CRED_TRANSPORT` | MEDIUM | BAD_PRACTICE | `UnprotectedCredTransportCheck` |
| `ast-style-unsalted-one-way-hash` | `UNSALTED_ONE_WAY_HASH` | MEDIUM | BAD_PRACTICE | `UnsaltedOneWayHashCheck` |
| `ast-style-unused-assignment` | `UNUSED_ASSIGNMENT` | MEDIUM | BUG | `UnusedAssignmentCheck` |
| `ast-style-unused-formal-parameter` | `UNUSED_FORMAL_PARAMETER` | LOW | STYLE | `UnusedFormalParameterCheck` |
| `ast-style-unused-import` | `UNUSED_IMPORT` | LOW | STYLE | `UnusedImportCheck` |
| `ast-style-unused-imports` | `UNUSED_IMPORTS` | LOW | STYLE | `UnusedImportsCheck` |
| `ast-style-unused-label` | `UNUSED_LABEL` | LOW | STYLE | `UnusedLabelCheck` |
| `ast-style-unused-private-field` | `UNUSED_PRIVATE_FIELD` | LOW | STYLE | `UnusedPrivateFieldCheck` |
| `ast-style-uppercase-suffix` | `UPPERCASE_SUFFIX` | LOW | STYLE | `UppercaseSuffixCheck` |
| `ast-style-upperell` | `UPPERELL` | LOW | STYLE | `UpperEllCheck` |
| `ast-style-use-assertnull` | `USE_ASSERTNULL` | LOW | STYLE | `UseAssertNullInsteadOfAssertTrueCheck` |
| `ast-style-use-assertsame` | `USE_ASSERTSAME` | LOW | STYLE | `UseAssertSameInsteadOfAssertTrueCheck` |
| `ast-style-use-concurrenthashmap` | `USE_CONCURRENTHASHMAP` | MEDIUM | BAD_PRACTICE | `UseConcurrentHashMapCheck` |
| `ast-style-use-diamond` | `USE_DIAMOND` | LOW | STYLE | `UseDiamondOperatorCheck` |
| `ast-style-use-enumeration-instead-of-iterator` | `USE_ENUMERATION_INSTEAD_OF_ITERATOR` | LOW | STYLE | `UseEnumerationInsteadOfIteratorCheck` |
| `ast-style-use-object-for-clearer-api` | `USE_OBJECT_FOR_CLEARER_API` | LOW | STYLE | `UseObjectForClearerAPI` |
| `ast-style-use-try-with-resources` | `USE_TRY_WITH_RESOURCES` | LOW | STYLE | `UseTryWithResourcesCheck` |
| `ast-style-use-varargs` | `USE_VARARGS` | MEDIUM | BAD_PRACTICE | `UseVarargsCheck` |
| `ast-style-useless-object` | `USELESS_OBJECT` | LOW | PERFORMANCE | `UselessObjectCheck` |
| `ast-style-uselessoperationonimmutable` | `USELESSOPERATIONONIMMUTABLE` | LOW | STYLE | `UselessOperationOnImmutableCheck` |
| `ast-style-uwf-field-not-init` | `UWF_FIELD_NOT_INIT` | LOW | STYLE | `UwfFieldNotInitializedCheck` |
| `ast-style-visibility-modifier` | `VISIBILITY_MODIFIER` | LOW | STYLE | `VisibilityModifierCheck` |
| `ast-style-wait-notify-in-loop` | `WAIT_NOTIFY_IN_LOOP` | HIGH | MULTITHREADING | `WaitNotifyCheck` |
| `ast-style-weak-crypto` | `WEAK_CRYPTO` | HIGH | SECURITY | `WeakCryptoCheck` |
| `ast-style-while-literal-boolean` | `WHILE_LITERAL_BOOLEAN` | LOW | STYLE | `WhileLoopWithLiteralBooleanCheck` |
| `ast-style-whitespaceafter` | `WHITESPACEAFTER` | LOW | STYLE | `WhitespaceAfterCheck` |
| `ast-style-whitespacearound` | `WHITESPACEAROUND` | LOW | STYLE | `WhitespaceAroundCheck` |
| `ast-style-wmi-wrong-map-iter` | `WMI_WRONG_MAP_ITER` | LOW | PERFORMANCE | `WmiWrongMapIteratorCheck` |
| `bu-0505-superfluous-instanceof` | `SUPERFLUOUS_INSTANCEOF` | LOW | STYLE | `SuperfluousInstanceofCheck` |
| `bu-0507-random-used-once` | `RANDOM_USED_ONCE` | LOW | PERFORMANCE | `RandomUsedOnceCheck` |
| `dfa-style-incorrect-block-delimitation` | `INCORRECT_BLOCK_DELIMITATION` | LOW | BAD_PRACTICE | `IncorrectBlockDelimitationCheck` |
| `dfa-style-missing-default-case` | `MISSING_DEFAULT_CASE` | LOW | BAD_PRACTICE | `MissingDefaultCaseCheck` |
| `dfa-style-numeric-conversion` | `NUMERIC_CONVERSION` | MEDIUM | CORRECTNESS | `NumericConversionCheck` |
| `dfa-style-off-by-one-loop` | `OFF_BY_ONE_LOOP` | MEDIUM | CORRECTNESS | `OffByOneLoopCheck` |

## 规则详细列表

> 阅读约定：每条规则按 `规则 ID / 短名 / 严重度 / 类型 / 规则类` 列出，并附 ① 规则说明（来自源码 Javadoc）② 与 PMD / SpotBugs / Checkstyle / SonarQube 的等价映射 ③ 可调参数表。

### ast-bug-bc-equals-no-null  —  `BC_EQUALS_NO_NULL`

- **规则类**: `org.plumb.style.bug.BcEqualsNoNullCheckCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> equals() doesn't null-check the argument (BC_EQUALS_NO_NULL_CHECK).

**规则约束 / 可调参数**: (无)

### ast-bug-comparator-compare  —  `COMPARATOR_COMPARE`

- **规则类**: `org.plumb.style.bug.ComparatorCompareCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> A {@link java.util.Comparator#compare(Object, Object) Comparator.compare()} method that returns a
> raw `1` or `-1` literal instead of delegating to {@link Integer#compare(int, int)}.
>
> The previous implementation had two bugs (S1 audit 2026-10-17):
>
> <ol>
> <li>It tested the same field twice (`getNameAsString()` against both `"compareTo"`
> and `"compare"`), making the second check unreachable and the rule fire on EVERY
> `compareTo` call in the codebase.
> <li>It never actually inspected `return` statements, so the rule never caught the
> anti-pattern it was supposed to catch.
> </ol>
>
> The corrected logic walks every {@link MethodDeclaration}, checks whether it is a `Comparator<T>.compare(T, T)` override (name = `"compare"`, 2 parameters, declaring class
> implements `Comparator` or extends a class that does), and inspects each `return`
> statement for a literal `1` / `-1`.

**规则约束 / 可调参数**: (无)

### ast-bug-compare-objects-with-equals  —  `COMPARE_OBJECTS_WITH_EQUALS`

- **规则类**: `org.plumb.style.bug.CompareObjectsWithEqualsCheck`
- **分类**: `bug`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-595
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use.equals() not == for object comparison.

**规则约束 / 可调参数**: (无)

### ast-bug-compareto-return-value  —  `COMPARETO_RETURN_VALUE`

- **规则类**: `org.plumb.style.bug.CompareToReturnValueCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-bug-covariant-equals  —  `COVARIANT_EQUALS`

- **规则类**: `org.plumb.style.bug.CovariantEqualsCheck`
- **分类**: `bug`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> equals() should accept Object, not a subtype.

**规则约束 / 可调参数**: (无)

### ast-bug-equals-avoid-null  —  `EQUALS_AVOID_NULL`

- **规则类**: `org.plumb.style.bug.EqualsAvoidNullCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **CWE**: CWE-476
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> null literal should be on the left of == or !=.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `allowEqualsOnBoxedPrimitives` | BOOLEAN | `true` | Allow == on boxed primitives (Integer,
> Long,...) |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `allowEqualsOnBoxedPrimitives` | BOOLEAN | `true` | Allow == on boxed primitives (Integer, Long,...) |

### ast-bug-equals-null  —  `EQUALS_NULL`

- **规则类**: `org.plumb.style.bug.EqualsNullCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Comparing with null using `.equals(null)` -- should be `==`.
>
> M5 part-2 migration (2026-10-17): the previous implementation used `visit(CompilationUnit) + findAll(MethodCallExpr.class)`, which costs one full-AST walk per file
> just to find `MethodCallExpr` nodes -- wasted work because the dispatcher already gives us
> a `visit(MethodCallExpr)` callback that fires once per `MethodCallExpr` with no extra
> walking. This rewrite uses the per-node-type callback directly; behaviour is unchanged.

**规则约束 / 可调参数**: (无)

### ast-bug-incorrect-return-comparison  —  `INCORRECT_RETURN_COMPARISON`

- **规则类**: `org.plumb.style.bug.IncorrectReturnComparisonCheck`
- **分类**: `bug`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-253
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> detects CWE-253 (Incorrect Check of Function Return Value) when a method call whose return
> indicates a count or sentinel is compared against the wrong literal.
>
> The representative pattern is `if (input.read(buf) == 0)` — a check for "zero bytes
> read" — but `read()` returns `-1` on EOF and `1..buf.length` on a normal read,
> so `== 0` never matches the actual failure mode. The correct check is `== -1` or
> `< buf.length`.
>
> Heuristic, intentionally narrow to keep the false-positive rate low:
>
> <li>Method call on the LHS of a `==` or `!=` {@link BinaryExpr}
> <li>Method name is in a curated "count/sentinel" list (read, readLine, hashCode, compareTo,
> ...)
> <li>RHS is a literal {@link IntegerLiteralExpr} that is NOT in the "valid sentinel" set
> (currently `{-1`})
>
> The curated list bounds what this rule reports. Code that needs broader coverage than the list
> provides can use the AI deduplication mode instead.

**规则约束 / 可调参数**: (无)

### ast-bug-incorrect-return-check  —  `INCORRECT_RETURN_CHECK`

- **规则类**: `org.plumb.style.bug.IncorrectReturnValueCheckChecker`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **CWE**: CWE-253
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Compare against boolean literal directly (use 'x' or '!x').
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `ignoreEnclosed` | BOOLEAN | `true` | Skip == true inside parentheses (common in ternaries) |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreEnclosed` | BOOLEAN | `true` | Skip == true inside parentheses (common in ternaries) |

### ast-bug-invalidcomparetoobject  —  `INVALIDCOMPARETOOBJECT`

- **规则类**: `org.plumb.style.bug.InvalidCompareToObjectCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-bug-jumbled-incrementer  —  `JUMBLED_INCREMENTER`

- **规则类**: `org.plumb.style.bug.JumbledIncrementerCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> for-loop incrementer is unusual (non-1 step or wrong direction).

**规则约束 / 可调参数**: (无)

### ast-bug-method-signature  —  `METHOD_SIGNATURE`

- **规则类**: `org.plumb.style.bug.MethodSignatureCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> too many same-typed parameters : use a parameter object.

**规则约束 / 可调参数**: (无)

### ast-bug-np-always-null  —  `NP_ALWAYS_NULL`

- **规则类**: `org.plumb.style.bug.NpAlwaysNullCheck`
- **分类**: `bug`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-476
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method always returns null (NP_ALWAYS_NULL) : fix or annotate @Nullable.

**规则约束 / 可调参数**: (无)

### ast-bug-np-load-null  —  `NP_LOAD_NULL`

- **规则类**: `org.plumb.style.bug.NpLoadOfKnownNullCheck`
- **分类**: `bug`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-476
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> loading a value known to be null (NP_LOAD_OF_KNOWN_NULL_VALUE).

**规则约束 / 可调参数**: (无)

### ast-bug-redundant-nullcheck  —  `REDUNDANT_NULLCHECK`

- **规则类**: `org.plumb.style.bug.RedundantNullCheckCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> redundant null check : variable is already known non-null (RCN_REDUNDANT_NULLCHECK).

**规则约束 / 可调参数**: (无)

### ast-bug-redundantnullofnullvalue-sb  —  `REDUNDANTNULLOFNULLVALUE_SB`

- **规则类**: `org.plumb.style.bug.RedundantNullCheckOfNullValueCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Redundant null check of null value.

**规则约束 / 可调参数**: (无)

### ast-bug-return-count  —  `RETURN_COUNT`

- **规则类**: `org.plumb.style.bug.ReturnCountCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method should have a single return (heuristic).
>
> Pattern D per-method visit. If method body has more than one {@link ReturnStmt} descendant,
> fire.

**规则约束 / 可调参数**: (无)

### ast-bug-rv-negating-compareto  —  `RV_NEGATING_COMPARETO`

- **规则类**: `org.plumb.style.bug.RvNegatingCompareToCheck`
- **分类**: `bug`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-682
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> -x.compareTo(y) is unsafe : invert the args (RV_NEGATING_RESULT_OF_COMPARETO).

**规则约束 / 可调参数**: (无)

### ast-bug-unnecessaryfornotnull-sb  —  `UNNECESSARYFORNOTNULL_SB`

- **规则类**: `org.plumb.style.bug.UnnecessaryCheckForNotNullCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Unnecessary check for not null.

**规则约束 / 可调参数**: (无)

### ast-bug-unusednullinequals  —  `UNUSEDNULLINEQUALS`

- **规则类**: `org.plumb.style.bug.UnusedNullCheckInEqualsCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Unused null check in equals.

**规则约束 / 可调参数**: (无)

### ast-design-abstract-coupling  —  `ABSTRACT_COUPLING`

- **规则类**: `org.plumb.style.design.AbstractClassCouplingCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Too many distinct types instantiated in this class.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `max` | INT
> | `20` | Maximum distinct instantiable types per class | | `excludeLocalVariables` | BOOLEAN |
> `false` | Only count types stored in fields |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `max` | INT | `20` | Maximum distinct instantiable types per class |
| `excludeLocalVariables` | BOOLEAN | `false` | Only count types stored in fields |

### ast-design-abstract-no-method  —  `ABSTRACT_NO_METHOD`

- **规则类**: `org.plumb.style.design.AbstractClassWithoutAbstractMethodCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> abstract class has no abstract members.

**规则约束 / 可调参数**: (无)

### ast-design-boolean-expression-complexity  —  `BOOLEAN_EXPRESSION_COMPLEXITY`

- **规则类**: `org.plumb.style.design.BooleanExpressionComplexityCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Boolean expression too complex (default threshold 3 -- meaning 3+ `&&`/`||`
> literals).
>
> findAll(BinaryExpr.class)} before. The complexity count uses a stack walk on each `BinaryExpr` subtree so we don't recurse the whole tree at each top-level `AND`/`OR`.

**规则约束 / 可调参数**: (无)

### ast-design-classfanoutcomplexity  —  `CLASSFANOUTCOMPLEXITY`

- **规则类**: `org.plumb.style.design.ClassFanOutComplexityCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-design-inheritance-depth  —  `INHERITANCE_DEPTH`

- **规则类**: `org.plumb.style.design.ClassInheritanceDepthCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> inheritance tree is too deep (, default 5).

**规则约束 / 可调参数**: (无)

### ast-design-confused-inheritance  —  `CONFUSED_INHERITANCE`

- **规则类**: `org.plumb.style.design.ConfusedInheritanceCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Confused inheritance -- the class declares an `implements` interface that is already
> inherited from its parent.
>
> The classic pattern is:
>
> ```
>
> abstract class AbstractThing implements Closeable { ... }
> class ConcreteThing extends AbstractThing implements Closeable { ... }   // ← redundant
>
> ```
>
> The pre-S4 implementation flagged every class that had `extends` + empty `implements` list, which produced 100% false positives -- almost every JPA / Spring class has
> exactly this shape (`class User extends BaseEntity implements Serializable`).
>
> S4 audit replaced that with a meaningful check: walk the extends/implements list of the parent
> class as written in the same compilation unit (single-file resolution) and flag the child only
> when it declares an interface that the parent already declares.
>
> <b>M5 part-2 migration:</b> the original used `visit(CompilationUnit) +
> findAll(ClassOrInterfaceDeclaration)` (1 walk) + a manual per-class loop. This rewrite keeps the
> per-class logic in `visit(ClassOrInterfaceDeclaration, ctx)` -- the dispatcher hands each
> declaration to us directly. The class-by-name index is still needed (we look up the parent's
> interfaces by simple name) and is built lazily in `init` so the work is amortised across
> every `visit` call within the same file.

**规则约束 / 可调参数**: (无)

### ast-design-ctor-calling-overridable  —  `CTOR_CALLING_OVERRIDABLE`

- **规则类**: `org.plumb.style.design.ConstructorCallingOverridableCheck`
- **分类**: `design`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-688
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-design-coupling  —  `COUPLING`

- **规则类**: `org.plumb.style.design.CouplingBetweenObjectsCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class is coupled to too many other classes (limit 20).

**规则约束 / 可调参数**: (无)

### ast-design-cyclomatic-complexity  —  `CYCLOMATIC_COMPLEXITY`

- **规则类**: `org.plumb.style.design.CyclomaticComplexityCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Cyclomatic complexity exceeds the configured threshold (default 10).

**规则约束 / 可调参数**: (无)

### ast-design-data-class  —  `DATA_CLASS`

- **规则类**: `org.plumb.style.design.DataClassCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> data class : mostly fields, expose behavior.

**规则约束 / 可调参数**: (无)

### ast-design-design-for-extension  —  `DESIGN_FOR_EXTENSION`

- **规则类**: `org.plumb.style.design.DesignForExtensionCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Public method in non-`final` class should be `abstract`, `final`, or `private` (DESIGN_FOR_EXTENSION).

**规则约束 / 可调参数**: (无)

### ast-design-uncommented-empty  —  `UNCOMMENTED_EMPTY`

- **规则类**: `org.plumb.style.design.EmptyMethodInAbstractClass2Check`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty method has no comment explaining why.

**规则约束 / 可调参数**: (无)

### ast-design-java-ncss  —  `JAVA_NCSS`

- **规则类**: `org.plumb.style.design.JavaNCSSCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Unit has too many statements (NCSS).
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `methodMaximum` | INT | `60` | Maximum number of statements in a method | | `classMaximum` | INT
> | `1500` | Maximum number of statements in a class | | `constructorMaximum` | INT | `80` |
> Maximum number of statements in a constructor |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `methodMaximum` | INT | `60` | Maximum number of statements in a method |
| `classMaximum` | INT | `1500` | Maximum number of statements in a class |
| `constructorMaximum` | INT | `80` | Maximum number of statements in a constructor |

### ast-design-law-of-demeter  —  `LAW_OF_DEMETER`

- **规则类**: `org.plumb.style.design.LawOfDemeterCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **CWE**: CWE-1060
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method chain exceeds LoD threshold (use a wrapper method instead).

**规则约束 / 可调参数**: (无)

### ast-design-loose-coupling  —  `LOOSE_COUPLING`

- **规则类**: `org.plumb.style.design.LooseCouplingCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Use the interface type, not the concrete class.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `allowedConcreteTypes` | STRING | `` | Comma-separated list of concrete type names to skip |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `allowedConcreteTypes` | STRING | `` | Comma-separated list of concrete type names to skip |

### ast-design-loose-coupling-2  —  `LOOSE_COUPLING_2`

- **规则类**: `org.plumb.style.design.LoosePackageCouplingCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class depends on many classes in a foreign package.

**规则约束 / 可调参数**: (无)

### ast-design-method-count  —  `METHOD_COUNT`

- **规则类**: `org.plumb.style.design.MethodCountCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Class has too many methods (default threshold 100).

**规则约束 / 可调参数**: (无)

### ast-design-public-ctor-abstract  —  `PUBLIC_CTOR_ABSTRACT`

- **规则类**: `org.plumb.style.design.PublicConstructorInAbstractClassCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Abstract class should not have a `public` constructor.

**规则约束 / 可调参数**: (无)

### ast-error-assignmenttocatchblockparameter  —  `ASSIGNMENTTOCATCHBLOCKPARAMETER`

- **规则类**: `org.plumb.style.error.AssignmentToCatchBlockParameterCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-error-avoid-throwable  —  `AVOID_THROWABLE`

- **规则类**: `org.plumb.style.error.AvoidCatchingThrowableCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Catching `Throwable` is too broad.

**规则约束 / 可调参数**: (无)

### ast-error-instanceof-in-catch  —  `INSTANCEOF_IN_CATCH`

- **规则类**: `org.plumb.style.error.AvoidInstanceofChecksInCatchClauseCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> instanceof in catch : use multiple catch clauses.

**规则约束 / 可调参数**: (无)

### ast-error-lose-exception-info  —  `LOSE_EXCEPTION_INFO`

- **规则类**: `org.plumb.style.error.AvoidLosingExceptionInformationCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-209
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> catch block throws new exception without including original.

**规则约束 / 可调参数**: (无)

### ast-error-catch-log  —  `CATCH_LOG`

- **规则类**: `org.plumb.style.error.CatchExceptionForLoggingCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-390
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> catch block does not use the exception.

**规则约束 / 可调参数**: (无)

### ast-error-catch-generic-exception  —  `STYLE_CATCH_GENERIC`

- **规则类**: `org.plumb.style.error.CatchGenericCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Catching generic Exception or Throwable.

**规则约束 / 可调参数**: (无)

### ast-error-catch-nullpointer-exception  —  `CATCH_NULLPOINTER_EXCEPTION`

- **规则类**: `org.plumb.style.error.CatchNpeCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-396
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Avoid catching NullPointerException (likely swallows a real bug).

**规则约束 / 可调参数**: (无)

### ast-error-catch-of-throwable  —  `CATCH_OF_THROWABLE`

- **规则类**: `org.plumb.style.error.CatchOfThrowableCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-396
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Catching `Throwable` / `Error` is too broad.

**规则约束 / 可调参数**: (无)

### ast-error-catch-throwable  —  `CATCH_THROWABLE`

- **规则类**: `org.plumb.style.error.CatchThrowableCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-396
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid catching Throwable / Error.

**规则约束 / 可调参数**: (无)

### ast-error-do-not-extend-error  —  `DO_NOT_EXTEND_ERROR`

- **规则类**: `org.plumb.style.error.DoNotExtendJavaLangErrorCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> do not extend java.lang.Error.

**规则约束 / 可调参数**: (无)

### ast-error-dropped-exception  —  `DROPPED_EXCEPTION`

- **规则类**: `org.plumb.style.error.DroppedExceptionCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Exception is dropped ? empty `catch` block with no logging or rethrow.

**规则约束 / 可调参数**: (无)

### ast-error-empty-catch-block  —  `EMPTY_CATCH_BLOCK`

- **规则类**: `org.plumb.style.error.EmptyCatchBlockCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Empty catch block ? strict.

**规则约束 / 可调参数**: (无)

### ast-error-empty-finalizer  —  `EMPTY_FINALIZER`

- **规则类**: `org.plumb.style.error.EmptyFinalizerCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty finalize() : remove the method (finalizers are deprecated).

**规则约束 / 可调参数**: (无)

### ast-error-error-message-info-leak  —  `ERROR_MESSAGE_INFO_LEAK`

- **规则类**: `org.plumb.style.error.ErrorMessageInfoLeakCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-209
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-error-explicit-finalize  —  `EXPLICIT_FINALIZE`

- **规则类**: `org.plumb.style.error.ExplicitFinalizeCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-586
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid explicit calls to finalize() (use try-with-resources instead).

**规则约束 / 可调参数**: (无)

### ast-error-finalize-only-overridden  —  `FINALIZE_ONLY_OVERRIDDEN`

- **规则类**: `org.plumb.style.error.FinalizeOnlyOverriddenCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **CWE**: CWE-586
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> finalize() should be declared final or the class should be final.

**规则约束 / 可调参数**: (无)

### ast-error-finalize-protected  —  `FINALIZE_PROTECTED`

- **规则类**: `org.plumb.style.error.FinalizeShouldBeProtectedCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> finalize() should be protected.

**规则约束 / 可调参数**: (无)

### ast-error-finalize-without-super  —  `FINALIZE_WITHOUT_SUPER`

- **规则类**: `org.plumb.style.error.FinalizeWithoutSuperCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-586
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> finalize() should call super.finalize().

**规则约束 / 可调参数**: (无)

### ast-error-generic-exception  —  `GENERIC_EXCEPTION`

- **规则类**: `org.plumb.style.error.GenericExceptionCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> throw a specific exception, not Exception/Throwable.

**规则约束 / 可调参数**: (无)

### ast-error-identical-catch  —  `IDENTICAL_CATCH`

- **规则类**: `org.plumb.style.error.IdenticalCatchBranchesCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> identical catch branches : merge with multi-catch.

**规则约束 / 可调参数**: (无)

### ast-error-illegal-catch  —  `ILLEGAL_CATCH`

- **规则类**: `org.plumb.style.error.IllegalCatchCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Catching `Exception` or `Throwable` is too broad.

**规则约束 / 可调参数**: (无)

### ast-error-iterator-next-exception  —  `ITERATOR_NEXT_EXCEPTION`

- **规则类**: `org.plumb.style.error.IteratorNextExceptionCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: BUG
- **CWE**: CWE-754
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-error-nested-try  —  `NESTED_TRY`

- **规则类**: `org.plumb.style.error.NestedTryCatchCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> nested try-catch : extract the inner try to a method.

**规则约束 / 可调参数**: (无)

### ast-error-no-finalizer  —  `NO_FINALIZER`

- **规则类**: `org.plumb.style.error.NoFinalizerCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Do not override `finalize()` ? deprecated in Java 9+.

**规则约束 / 可调参数**: (无)

### ast-error-suppression-marker-comment  —  `SUPPRESSION_MARKER_COMMENT`

- **规则类**: `org.plumb.style.error.NosonarCommentCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-error-object-finalize-overload  —  `OBJECT_FINALIZE_OVERLOAD`

- **规则类**: `org.plumb.style.error.ObjectFinalizeOverloadCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> `finalize()` should not be overloaded (i.e., should take no parameters).

**规则约束 / 可调参数**: (无)

### ast-error-parse-error-logging  —  `PARSE_ERROR_LOGGING`

- **规则类**: `org.plumb.style.error.ParseErrorLoggingCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-390
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> ParseException caught and silently swallowed.

**规则约束 / 可调参数**: (无)

### ast-error-unchecked-throws  —  `UNCHECKED_THROWS`

- **规则类**: `org.plumb.style.error.PublicMethodNoUncheckedExceptionCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> public method declares unchecked exception in throws.

**规则约束 / 可调参数**: (无)

### ast-error-raw-exception  —  `RAW_EXCEPTION`

- **规则类**: `org.plumb.style.error.RawExceptionCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Throwing raw `Exception` / `RuntimeException` / `Throwable`.

**规则约束 / 可调参数**: (无)

### ast-error-super-finalize  —  `SUPER_FINALIZE`

- **规则类**: `org.plumb.style.error.SuperFinalizeCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-568
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> finalize() must call super.finalize().
>
> Pattern D per-method visit. If method name is `"finalize"` and no descendant `super.finalize()` call, fire (preserves original behavior for abstract / native methods which
> lack a body).

**规则约束 / 可调参数**: (无)

### ast-error-throwable-not-thrown  —  `THROWABLE_NOT_THROWN`

- **规则类**: `org.plumb.style.error.ThrowableNotThrownCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-390
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Caught exception is silently swallowed (re-throw or log instead).

**规则约束 / 可调参数**: (无)

### ast-error-throw-generic-exception  —  `THROW_GENERIC_EXCEPTION`

- **规则类**: `org.plumb.style.error.ThrowGenericExceptionCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **CWE**: CWE-397
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-397: Declaration of Throws for Generic Exception.
>
> Detects method declarations whose `throws`-clause contains {@link Exception}, {@link
> Throwable}, or {@link RuntimeException} (the three "too generic" exception types).

**规则约束 / 可调参数**: (无)

### ast-error-throw-in-finally  —  `THROW_IN_FINALLY`

- **规则类**: `org.plumb.style.error.ThrowInFinallyCheck`
- **分类**: `error`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-584
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> throw in finally : swallows original exception.

**规则约束 / 可调参数**: (无)

### ast-error-throws-count  —  `THROWS_COUNT`

- **规则类**: `org.plumb.style.error.ThrowsCountCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Method declares too many checked exceptions (default threshold 4).

**规则约束 / 可调参数**: (无)

### ast-error-unused-throws  —  `UNUSED_THROWS`

- **规则类**: `org.plumb.style.error.UnusedThrowsCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Declared exception is not thrown by the method.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `ignoreUnchecked` | BOOLEAN | `true` | Skip RuntimeException and subclasses |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreUnchecked` | BOOLEAN | `true` | Skip RuntimeException and subclasses |

### ast-naming-abstract  —  `ABSTRACT`

- **规则类**: `org.plumb.style.naming.AbstractCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Abstract class has no abstract methods.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `onlyEmptyBodies` | BOOLEAN | `true` | Only flag abstract methods with empty/stub bodies |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `onlyEmptyBodies` | BOOLEAN | `true` | Only flag abstract methods with empty/stub bodies |

### ast-naming-abstract-naming  —  `ABSTRACT_NAMING`

- **规则类**: `org.plumb.style.naming.AbstractNamingCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Abstract class should be named starting with `Abstract` (heuristic).

**规则约束 / 可调参数**: (无)

### ast-naming-arraynaming  —  `ARRAYNAMING`

- **规则类**: `org.plumb.style.naming.ArrayNamingCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **PMD**: ArrayNamingStyle

**规则说明**

> Array variable name should be plural or end with `s`.
>
> M5 part-3 migration (2026-10-17): the previous implementation used `visit(CompilationUnit) + findAll(VariableDeclarator.class)` (1 walk per file). This rewrite uses
> the per-node-type callback directly; the dispatcher hands each `VariableDeclarator` to us
> with no extra walking.

**规则约束 / 可调参数**: (无)

### ast-naming-at-least-one-constructor  —  `AT_LEAST_ONE_CONSTRUCTOR`

- **规则类**: `org.plumb.style.naming.AtLeastOneConstructorCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Class is missing an explicit constructor.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `ignoreInterfaceDeclarations` | BOOLEAN | `true` | Skip interface declarations | |
> `ignoreEnumDeclarations` | BOOLEAN | `true` | Skip enum declarations |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreInterfaceDeclarations` | BOOLEAN | `true` | Skip interface declarations |
| `ignoreEnumDeclarations` | BOOLEAN | `true` | Skip enum declarations |

### ast-naming-avoid-final-local-variable  —  `AVOID_FINAL_LOCAL_VARIABLE`

- **规则类**: `org.plumb.style.naming.AvoidFinalLocalVariableCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid final on local variables (use only when capturing a contract).

**规则约束 / 可调参数**: (无)

### ast-naming-bad-abstract-class-name  —  `BAD_ABSTRACT_CLASS_NAME`

- **规则类**: `org.plumb.style.naming.BadAbstractClassNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Abstract class name should start with `Abstract`.
>
> M5 part-3 migration (2026-10-17): per-node-type callback directly; was `visit(CompilationUnit) + findAll(ClassOrInterfaceDeclaration.class)` before.

**规则约束 / 可调参数**: (无)

### ast-naming-bean-members-serialize  —  `BEAN_MEMBERS_SERIALIZE`

- **规则类**: `org.plumb.style.naming.BeanMembersShouldSerializeCheck`
- **分类**: `naming`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Serializable class field is not marked transient or serialVersionUID.

**规则约束 / 可调参数**: (无)

### ast-naming-boolean-get-method-name  —  `BOOLEAN_GET_METHOD_NAME`

- **规则类**: `org.plumb.style.naming.BooleanGetMethodNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Boolean getter should be `isX()` or `hasX()`.
>
> M5 part-3 migration (2026-10-17): per-node-type callback directly; was `visit(CompilationUnit) + findAll(MethodDeclaration.class)` before.

**规则约束 / 可调参数**: (无)

### ast-naming-class-data-abstraction-coupling  —  `CLASS_DATA_ABSTRACTION_COUPLING`

- **规则类**: `org.plumb.style.naming.ClassDataAbstractionCouplingCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Class has too many instance fields (data abstraction coupling).

**规则约束 / 可调参数**: (无)

### ast-naming-class-name-long  —  `CLASS_NAME_LONG`

- **规则类**: `org.plumb.style.naming.ClassNameTooLongCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class name is too long (limit 30).

**规则约束 / 可调参数**: (无)

### ast-naming-class-naming  —  `CLASS_NAMING`

- **规则类**: `org.plumb.style.naming.ClassNamingConventionsCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**:
  - **PMD**: ClassNamingConventions

**规则说明**

> Class name does not match the required pattern.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `format` |
> STRING | `^[A-Z][a-zA-Z0-9]*$` | Regex the class name must match | | `abstractClassFormat` |
> STRING | `` | Regex for abstract classes (empty = same as format) | | `utilityClassFormat` |
> STRING | `` | Regex for utility classes (empty = same as format) |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `format` | STRING | `^[A-Z][a-zA-Z0-9]*$` | Regex the class name must match |
| `abstractClassFormat` | STRING | `` | Regex for abstract classes (empty = same as format) |
| `utilityClassFormat` | STRING | `` | Regex for utility classes (empty = same as format) |

### ast-naming-class-type-parameter-name  —  `CLASS_TYPE_PARAMETER_NAME`

- **规则类**: `org.plumb.style.naming.ClassTypeParameterNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Class type parameter name should be uppercase single letter (`T`, `K`, `V`).

**规则约束 / 可调参数**: (无)

### ast-naming-class-only-private-ctors  —  `CLASS_ONLY_PRIVATE_CTORS`

- **规则类**: `org.plumb.style.naming.ClassWithOnlyPrivateConstructorsCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Class with only private constructors should be `final`.

**规则约束 / 可调参数**: (无)

### ast-naming-compare-class-names  —  `COMPARE_CLASS_NAMES`

- **规则类**: `org.plumb.style.naming.CompareClassNamesCheck`
- **分类**: `naming`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> compare Class references with equals() not ==.

**规则约束 / 可调参数**: (无)

### ast-naming-constant-name  —  `CONSTANT_NAME`

- **规则类**: `org.plumb.style.naming.ConstantNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Static final field should be `UPPER_SNAKE_CASE`.
>
> M5 part-3 migration (2026-10-17): per-node-type callback directly; was `visit(CompilationUnit) + findAll(FieldDeclaration.class)` before.

**规则约束 / 可调参数**: (无)

### ast-naming-constants  —  `STYLE_CONSTANT_NAMING`

- **规则类**: `org.plumb.style.naming.ConstantNamingCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**:
  - **PMD**: ConstantNamingConventions
  - **Checkstyle**: ConstantName

**规则说明**

> Static final field should be UPPER_SNAKE_CASE.

**规则约束 / 可调参数**: (无)

### ast-naming-empty-method  —  `EMPTY_METHOD`

- **规则类**: `org.plumb.style.naming.EmptyMethodCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **PMD**: EmptyMethodInAbstractClass

**规则说明**

> Method has an empty body.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `ignoreAbstractMethods` | BOOLEAN | `true` | Skip abstract methods (no body by design) | |
> `ignoreNativeMethods` | BOOLEAN | `true` | Skip native methods (no Java body) | |
> `ignoreMethodsWithOverrideAnnotation` | BOOLEAN | `false` | Skip methods with @Override
> annotation | | `ignorePublicMethods` | BOOLEAN | `false` | Skip public methods |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreAbstractMethods` | BOOLEAN | `true` | Skip abstract methods (no body by design) |
| `ignoreNativeMethods` | BOOLEAN | `true` | Skip native methods (no Java body) |
| `ignoreMethodsWithOverrideAnnotation` | BOOLEAN | `false` | Skip methods with @Override annotation |
| `ignorePublicMethods` | BOOLEAN | `false` | Skip public methods |

### ast-naming-uncommented-empty-method  —  `UNCOMMENTED_EMPTY_METHOD`

- **规则类**: `org.plumb.style.naming.EmptyMethodInAbstractClassCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Empty public method has no comment explaining why.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `ignoreMethodsWithOverrideAnnotation` | BOOLEAN | `true` | Skip methods with @Override annotation
> |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreMethodsWithOverrideAnnotation` | BOOLEAN | `true` | Skip methods with @Override annotation |

### ast-naming-field-naming  —  `FIELD_NAMING`

- **规则类**: `org.plumb.style.naming.FieldNamingConventionCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> field name should be camelCase.

**规则约束 / 可调参数**: (无)

### ast-naming-final-local-variable  —  `FINAL_LOCAL_VARIABLE`

- **规则类**: `org.plumb.style.naming.FinalLocalVariableCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-naming-generic-naming  —  `GENERIC_NAMING`

- **规则类**: `org.plumb.style.naming.GenericNamingCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Type parameter name does not match the required pattern.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `format` |
> STRING | `^[A-Z][0-9]?$` | Regex for type parameter names (default: single uppercase letter) | |
> `allowMultiChar` | BOOLEAN | `false` | Allow multi-character type parameter names like Key, Value
> |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `format` | STRING | `^[A-Z][0-9]?$` | Regex for type parameter names (default: single uppercase letter) |
| `allowMultiChar` | BOOLEAN | `false` | Allow multi-character type parameter names like Key, Value |

### ast-naming-interface-type-parameter-name  —  `INTERFACE_TYPE_PARAMETER_NAME`

- **规则类**: `org.plumb.style.naming.InterfaceTypeParameterNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Interface type parameter name should be uppercase single letter.

**规则约束 / 可调参数**: (无)

### ast-naming-invalid-log  —  `INVALID_LOG`

- **规则类**: `org.plumb.style.naming.InvalidLogMessageFormatCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> log message lacks placeholder : pass arguments to slf4j.

**规则约束 / 可调参数**: (无)

### ast-naming-local-final-variable-name  —  `LOCAL_FINAL_VARIABLE_NAME`

- **规则类**: `org.plumb.style.naming.LocalFinalVariableNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Local final variable name should be camelCase.

**规则约束 / 可调参数**: (无)

### ast-naming-local-home-naming-NOT-IMPLEMENTED  —  `LOCAL_HOME_NAMING_NOT_IMPLEMENTED`

- **规则类**: `org.plumb.style.naming.LocalHomeNamingConventionCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> EJB local home should end with LocalHome — STUB, not registered.

**规则约束 / 可调参数**: (无)

### ast-naming-local-var-could-be-final  —  `LOCAL_VAR_COULD_BE_FINAL`

- **规则类**: `org.plumb.style.naming.LocalVariableCouldBeFinalCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> local variable could be declared final.

**规则约束 / 可调参数**: (无)

### ast-naming-local-variable-name  —  `LOCAL_VARIABLE_NAME`

- **规则类**: `org.plumb.style.naming.LocalVariableNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **PMD**: LocalVariableNamingConventions
  - **Checkstyle**: LocalVariableName

**规则说明**

> Local variable name should be camelCase.

**规则约束 / 可调参数**: (无)

### ast-naming-local-var-naming  —  `LOCAL_VAR_NAMING`

- **规则类**: `org.plumb.style.naming.LocalVariableNamingConventionsCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> local variable should be camelCase.

**规则约束 / 可调参数**: (无)

### ast-naming-shadow-field  —  `SHADOW_FIELD`

- **规则类**: `org.plumb.style.naming.LocalVariableShadowingFieldCheck`
- **分类**: `naming`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> local variable shadows a field.

**规则约束 / 可调参数**: (无)

### ast-naming-local-var-return  —  `LOCAL_VAR_RETURN`

- **规则类**: `org.plumb.style.naming.LocalVariableThenReturnCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> local variable declared and immediately returned.

**规则约束 / 可调参数**: (无)

### ast-naming-long-variable  —  `LONG_VARIABLE`

- **规则类**: `org.plumb.style.naming.LongVariableCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **PMD**: LongVariable

**规则说明**

> Long variable name (`> 20` chars, strict).

**规则约束 / 可调参数**: (无)

### ast-naming-membername  —  `MEMBERNAME`

- **规则类**: `org.plumb.style.naming.MemberNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: MemberName

**规则说明**

> Non-static field should be camelCase.
>
> M5 part-3 migration (2026-10-17): per-node-type callback directly; was `visit(CompilationUnit) + findAll(FieldDeclaration.class)` before.

**规则约束 / 可调参数**: (无)

### ast-naming-method-name  —  `METHOD_NAME`

- **规则类**: `org.plumb.style.naming.MethodNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**:
  - **PMD**: MethodNamingConventions
  - **Checkstyle**: MethodName

**规则说明**

> Method name should be camelCase.

**规则约束 / 可调参数**: (无)

### ast-naming-method-naming  —  `METHOD_NAMING`

- **规则类**: `org.plumb.style.naming.MethodNamingConventionsCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**:
  - **PMD**: MethodNamingConventions

**规则说明**

> Method name should be camelCase.
>
> M1 audit (2026-10-17) widened the test-method exemption: the previous version only skipped
> methods whose name started with `test`, which works for JUnit 3 / 4 conventions but misses
> JUnit 5's `@Test`-annotated methods with arbitrary names (e.g. `shouldHandleEmptyInput()`, `returns_404_when_path_does_not_exist()`). The new version also
> accepts any method carrying a JUnit `@Test` / `@ParameterizedTest` /
> `@RepeatedTest` / `@TestFactory` / `@TestTemplate` annotation.

**规则约束 / 可调参数**: (无)

### ast-naming-missing-override  —  `MISSING_OVERRIDE`

- **规则类**: `org.plumb.style.naming.MissingOverrideCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Method override missing `@Override` annotation.

**规则约束 / 可调参数**: (无)

### ast-naming-naming-convention  —  `NAMING_CONVENTION`

- **规则类**: `org.plumb.style.naming.NamingConventionCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Local variable starting with uppercase letter (non-`String` types) -- should be camelCase.

**规则约束 / 可调参数**: (无)

### ast-naming-outer-type-filename  —  `OUTER_TYPE_FILENAME`

- **规则类**: `org.plumb.style.naming.OuterTypeFilenameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Outer type filename should match the type name.
>
> This rule is intrinsically per-file (one check per `CompilationUnit`) -- no useful
> per-node-type migration here. Kept as `visit(CompilationUnit)` since the dispatcher hands
> the file root directly.

**规则约束 / 可调参数**: (无)

### ast-naming-equals-hash  —  `EQUALS_HASH`

- **规则类**: `org.plumb.style.naming.OverrideEqualsAndHashCodeCheck`
- **分类**: `naming`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> override equals AND hashCode together.

**规则约束 / 可调参数**: (无)

### ast-naming-package-case  —  `PACKAGE_CASE`

- **规则类**: `org.plumb.style.naming.PackageCaseCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Package name should be all lowercase.
>
> : per-node-type callback already; this checker was already in modern form. Kept `visit(CompilationUnit)` because `PackageDeclaration` is visited once per file at the top
> level (the dispatcher hands the `CompilationUnit` directly; we don't need `findAll`
> here).

**规则约束 / 可调参数**: (无)

### ast-naming-public-constant  —  `PUBLIC_CONSTANT`

- **规则类**: `org.plumb.style.naming.PublicConstantFieldNamingCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> public constant should be SCREAMING_SNAKE_CASE.

**规则约束 / 可调参数**: (无)

### ast-naming-short-class  —  `SHORT_CLASS`

- **规则类**: `org.plumb.style.naming.ShortClassNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class name is too short (limit 3).

**规则约束 / 可调参数**: (无)

### ast-naming-short-method  —  `SHORT_METHOD`

- **规则类**: `org.plumb.style.naming.ShortMethodNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method name is too short.

**规则约束 / 可调参数**: (无)

### ast-naming-short-variable  —  `SHORT_VARIABLE`

- **规则类**: `org.plumb.style.naming.ShortVariableCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**:
  - **PMD**: ShortVariable

**规则说明**

> variable name is too short.

**规则约束 / 可调参数**: (无)

### ast-naming-singleton-class  —  `SINGLETON_CLASS`

- **规则类**: `org.plumb.style.naming.SingletonClassCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Class with all-private constructors should be an enum.

**规则约束 / 可调参数**: (无)

### ast-naming-suspicious-equals  —  `SUSPICIOUS_EQUALS`

- **规则类**: `org.plumb.style.naming.SuspiciousEqualsMethodNameCheck`
- **分类**: `naming`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> equals() method should take java.lang.Object.

**规则约束 / 可调参数**: (无)

### ast-naming-type-name  —  `TYPE_NAME`

- **规则类**: `org.plumb.style.naming.TypeNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: TypeName

**规则说明**

> Type name should be PascalCase.
>
> A type name is reported when it does not match `[A-Z][A-Za-z0-9]*`. This rule is
> disabled by default; enable it explicitly when the naming convention is part of the project's own
> standard.

**规则约束 / 可调参数**: (无)

### ast-naming-unnecessary-fqn  —  `UNNECESSARY_FQN`

- **规则类**: `org.plumb.style.naming.UnnecessaryFullyQualifiedClassNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **PMD**: UnnecessaryFullyQualifiedName

**规则说明**

> Use an `import` instead of writing a fully-qualified class name inline.
>
> S6 audit (2026-10-17) fixed the false-positive flood: the previous implementation fired on
> every `NameExpr` containing a dot, which meant every field access with a package-like
> prefix (`this.foo.bar`, `outer.inner.field`, `MyClass.STATIC_FIELD`) was
> flagged as "should use import". The corrected version resolves the `NameExpr` and only
> fires when the resolution result is a class / interface type -- never on field accesses.

**规则约束 / 可调参数**: (无)

### ast-naming-unnecessary-fqn-enum  —  `UNNECESSARY_FQN_ENUM`

- **规则类**: `org.plumb.style.naming.UnnecessaryFullyQualifiedEnumNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Unnecessary fully-qualified enum name (should be a simple reference with import).
>
> findAll(FieldAccessExpr.class)} before. Note this checker fires on ANY `FieldAccessExpr`
> with a dot -- same caveat as `UnnecessaryFullyQualifiedClassNameCheck` — it's a heuristic.
> The M4 fix in plumb-ai only checks for type references; here we keep the simple-name heuristic
> since this is purely AST-level.

**规则约束 / 可调参数**: (无)

### ast-naming-unused-local-var  —  `UNUSED_LOCAL_VAR`

- **规则类**: `org.plumb.style.naming.UnusedLocalVariableCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Local variable declared but never used.

**规则约束 / 可调参数**: (无)

### ast-naming-use-underscores-in-numeric-literals  —  `USE_UNDERSCORES_IN_NUMERIC_LITERALS`

- **规则类**: `org.plumb.style.naming.UseUnderscoresInNumericLiteralsCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Numeric literal should use underscore separators for readability.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `minDigits`
> | INT | `4` | Minimum consecutive digits to require underscores | | `includeFloat` | BOOLEAN |
> `true` | Also check float/double literals |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `minDigits` | INT | `4` | Minimum consecutive digits to require underscores |
| `includeFloat` | BOOLEAN | `true` | Also check float/double literals |

### ast-naming-use-utility-class  —  `USE_UTILITY_CLASS`

- **规则类**: `org.plumb.style.naming.UseUtilityClassCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class with only static members should be a utility class.

**规则约束 / 可调参数**: (无)

### ast-performance-array-key  —  `ARRAY_KEY`

- **规则类**: `org.plumb.style.performance.ArrayAsKeyOfSetOrMapCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> array used as Map/Set key : use List/Set.

**规则约束 / 可调参数**: (无)

### ast-performance-array-equality  —  `ARRAY_EQUALITY`

- **规则类**: `org.plumb.style.performance.ArrayEqualityCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> arrays should not be compared with == (use Arrays.equals instead).

**规则约束 / 可调参数**: (无)

### ast-performance-array-hashcode-tostring  —  `ARRAY_HASHCODE_TOSTRING`

- **规则类**: `org.plumb.style.performance.ArrayHashCodeAndToStringCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-477
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> arr.toString() / arr.hashCode() should use Arrays.toString / Arrays.hashCode.

**规则约束 / 可调参数**: (无)

### ast-performance-array-store-incompatible  —  `ARRAY_STORE_INCOMPATIBLE`

- **规则类**: `org.plumb.style.performance.ArrayStoreIncompatibleCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Array store of incompatible type.

**规则约束 / 可调参数**: (无)

### ast-performance-array-too-large  —  `ARRAY_TOO_LARGE`

- **规则类**: `org.plumb.style.performance.ArrayTooLargeCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Array created with too-large initial size (4+ digits).

**规则约束 / 可调参数**: (无)

### ast-performance-array-type  —  `ARRAY_TYPE`

- **规则类**: `org.plumb.style.performance.ArrayTypeCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Array type should be written in Java style (Type[]).
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `javaStyle`
> | BOOLEAN | `true` | Prefer Type[] over Type [] and Type arr[] | | `allowCStyleOnParameters` |
> BOOLEAN | `false` | Allow C-style (Type arr[]) on method parameters |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `javaStyle` | BOOLEAN | `true` | Prefer Type[] over Type [] and Type arr[] |
| `allowCStyleOnParameters` | BOOLEAN | `false` | Allow C-style (Type arr[]) on method parameters |

### ast-performance-avoid-array-loops  —  `AVOID_ARRAY_LOOPS`

- **规则类**: `org.plumb.style.performance.AvoidArrayLoopsCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use for-each instead of indexed for-loop over array.

**规则约束 / 可调参数**: (无)

### ast-performance-avoid-calendar-date-instance  —  `AVOID_CALENDAR_DATE_INSTANCE`

- **规则类**: `org.plumb.style.performance.AvoidCalendarDateInstanceCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid new Date() : use Calendar.getInstance() or LocalDate.now().

**规则约束 / 可调参数**: (无)

### ast-performance-avoid-stringbuffer-field  —  `AVOID_STRINGBUFFER_FIELD`

- **规则类**: `org.plumb.style.performance.AvoidStringBufferFieldCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid StringBuffer as a field.

**规则约束 / 可调参数**: (无)

### ast-performance-bc-bad-cast-abstract  —  `BC_BAD_CAST_ABSTRACT`

- **规则类**: `org.plumb.style.performance.BcBadCastToAbstractCollectionCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-704
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> bad cast to abstract collection (BC_BAD_CAST_TO_ABSTRACT_COLLECTION).

**规则约束 / 可调参数**: (无)

### bu-0607-big-decimal-equals  —  `BIG_DECIMAL_EQUALS`

- **规则类**: `org.plumb.style.performance.BigDecimalEqualsCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-682
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> BigDecimal.equals is value-and-scale, use compareTo.

**规则约束 / 可调参数**: (无)

### ast-performance-boxed-constructor  —  `BOXED_CONSTRUCTOR`

- **规则类**: `org.plumb.style.performance.BoxedConstructorCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Boxed constructor usage (`new Integer(...)` / `new Long(...)` / `new
> Double(...)`). Use `Integer.valueOf` or autoboxing instead.

**规则约束 / 可调参数**: (无)

### ast-performance-collection-inappropriate  —  `COLLECTION_INAPPROPRIATE`

- **规则类**: `org.plumb.style.performance.CollectionInappropriateCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Using `Hashtable` / `Vector` / `Stack` -- prefer `java.util.concurrent`
> alternatives or `ArrayList` / `HashMap`.

**规则约束 / 可调参数**: (无)

### ast-performance-string-eq  —  `STRING_EQ`

- **规则类**: `org.plumb.style.performance.CompareStringsWithEqualsCheck`
- **分类**: `performance`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-595
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> `==` / `!=` comparison on a {@link String} value. Use `.equals()` instead.
>
> S3 audit (2026-10-17) replaced the previous name-heuristic with JavaParser's type resolver.
> The old code accepted any `NameExpr` whose text contained `str/name/text/msg/word/label` which produced both false positives (`stream`, `strong`, `buffer`, etc.) and false negatives (`query`, `command`, `input`). The new code asks `StyleContext.resolvedTypeOf(...)` whether the operand is
> actually `java.lang.String`.
>
> When the type solver cannot resolve (e.g. the project isn't on the classpath or the symbol is
> declared in a different module), we fall back to: a `StringLiteralExpr` is always
> considered string-like, and a `NameExpr` whose lowercased name contains `string`
> (case-insensitive, very narrow) is considered string-like. We do NOT continue the old broad
> substring heuristic -- that produced more noise than signal.

**规则约束 / 可调参数**: (无)

### ast-performance-dm-boxed-primitive  —  `DM_BOXED_PRIMITIVE`

- **规则类**: `org.plumb.style.performance.DmBoxedPrimitiveForParsingCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-704
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> new Integer("5") : use Integer.parseInt(s) instead (DM_BOXED_PRIMITIVE_FOR_PARSING).

**规则约束 / 可调参数**: (无)

### ast-performance-dmi-collection-urls  —  `DMI_COLLECTION_URLS`

- **规则类**: `org.plumb.style.performance.DmiCollectionOfUrlsCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-400
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> collection of URLs : equals triggers DNS (DMI_COLLECTION_OF_URLS).

**规则约束 / 可调参数**: (无)

### ast-performance-do-not-call-gc  —  `DO_NOT_CALL_GC`

- **规则类**: `org.plumb.style.performance.DoNotCallGarbageCollectionCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-401
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> do not call System.gc() / Runtime.gc().

**规则约束 / 可调参数**: (无)

### ast-performance-no-float-loop-index  —  `NO_FLOAT_LOOP_INDEX`

- **规则类**: `org.plumb.style.performance.DontUseFloatTypeForLoopIndicesCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Don't use `float` / `double` as a `for`-loop counter.

**规则约束 / 可调参数**: (无)

### ast-performance-dup-string  —  `DUP_STRING`

- **规则类**: `org.plumb.style.performance.DuplicatedStringLiteralCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> duplicated string literal ? extract a constant.

**规则约束 / 可调参数**: (无)

### ast-performance-ec-bad-array-compare  —  `EC_BAD_ARRAY_COMPARE`

- **规则类**: `org.plumb.style.performance.EcBadArrayCompareCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> array == comparison : use Arrays.equals (EC_BAD_ARRAY_COMPARE).

**规则约束 / 可调参数**: (无)

### ast-performance-ec-bad-array-store  —  `EC_BAD_ARRAY_STORE`

- **规则类**: `org.plumb.style.performance.EcBadArrayStoreCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-704
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Array literal has heterogeneous types (EC_BAD_ARRAY_STORE).

**规则约束 / 可调参数**: (无)

### ast-performance-floating-point-loops  —  `FLOATING_POINT_LOOPS`

- **规则类**: `org.plumb.style.performance.FloatingPointLoopsCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> `for` loop with a `float` / `double` counter.

**规则约束 / 可调参数**: (无)

### ast-performance-for-loop-variable-type  —  `FOR_LOOP_VARIABLE_TYPE`

- **规则类**: `org.plumb.style.performance.ForLoopVariableTypeCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-682
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> for-loop counter must not be float/double.

**规则约束 / 可调参数**: (无)

### ast-performance-for-size  —  `FOR_SIZE`

- **规则类**: `org.plumb.style.performance.ForLoopWithSizeMethodCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Cache `Collection.size()` / array length outside the loop body. Calling `.size()`
> inside the loop condition re-evaluates on every iteration; for `ArrayList` and similar
> implementations that's O(1) but it's still wasted work and a smell.
>
> S5 audit (2026-10-17) broadened coverage: the previous version only checked the right operand
> of `<` in `ForStmt`, missing `<=` / `>` / `>=`, the `for (int
> i = list.size(); ...)` form (size on the left), and the `while` counterpart entirely.

**规则约束 / 可调参数**: (无)

### ast-performance-jdk-obsolete-collection  —  `JDK_OBSOLETE_COLLECTION`

- **规则类**: `org.plumb.style.performance.JdkObsoleteCollectionCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Use non-obsolete JDK collection (e.g. `Vector`, `Hashtable`, `Stack`).

**规则约束 / 可调参数**: (无)

### ast-performance-jumbled-incrementer  —  `JUMBLED_INCREMENTER_2`

- **规则类**: `org.plumb.style.performance.JumbledIncrementerLoopCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-190
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Loop incrementer is not the standard form.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `allowDecrement` | BOOLEAN | `true` | Allow i-- as a standard increment |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `allowDecrement` | BOOLEAN | `true` | Allow i-- as a standard increment |

### ast-performance-loops-same-condition  —  `LOOPS_SAME_CONDITION`

- **规则类**: `org.plumb.style.performance.LoopsWithSameConditionCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-performance-malformed-format-string  —  `MALFORMED_FORMAT_STRING`

- **规则类**: `org.plumb.style.performance.MalformedFormatStringCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Malformed `printf` / `format` string literal.

**规则约束 / 可调参数**: (无)

### ast-performance-method-return  —  `METHOD_RETURN`

- **规则类**: `org.plumb.style.performance.MethodReturnCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Non-`void` method with empty body ? should return a value.

**规则约束 / 可调参数**: (无)

### ast-performance-no-array-trailing-comma  —  `NO_ARRAY_TRAILING_COMMA`

- **规则类**: `org.plumb.style.performance.NoArrayTrailingCommaCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Array initialisation should not have a trailing comma.

**规则约束 / 可调参数**: (无)

### ast-performance-non-singleton-primitive-constructor  —  `NON_SINGLETON_PRIMITIVE_CONSTRUCTOR`

- **规则类**: `org.plumb.style.performance.NonSingletonPrimitiveConstructorInCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> `new Boolean/Integer/Long(primitive)` ? use `valueOf` instead.

**规则约束 / 可调参数**: (无)

### ast-performance-null-returned-tostring  —  `NULL_RETURNED_TOSTRING`

- **规则类**: `org.plumb.style.performance.NullReturnedToStringCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> toString() returning null.
>
> Pattern D: per-method {@link MethodDeclaration} visit. For toString() methods, walk the body
> once looking for {@link ReturnStmt} returning {@link NullLiteralExpr}. Replaces visit(CU) + 2
> findAll with a single per-method walk triggered by AstDispatcher.

**规则约束 / 可调参数**: (无)

### ast-performance-prefer-zero-length-arrays  —  `PREFER_ZERO_LENGTH_ARRAYS`

- **规则类**: `org.plumb.style.performance.PreferZeroLengthArraysCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Return an empty array instead of `null` for array-returning methods.
>
> n.findAncestor(MethodDeclaration.class)} so we still skip non-array returns.

**规则约束 / 可调参数**: (无)

### ast-performance-primitives-boxed  —  `PRIMITIVES_BOXED`

- **规则类**: `org.plumb.style.performance.PrimitivesBoxedCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-704
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> new Integer/Long/... : use the literal.

**规则约束 / 可调参数**: (无)

### ast-performance-range-array-index  —  `RANGE_ARRAY_INDEX`

- **规则类**: `org.plumb.style.performance.RangeArrayIndexCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-129
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Array index uses `i + 1` / `i - 1` pattern -- confirm it doesn't reach the array
> length.

**规则约束 / 可调参数**: (无)

### ast-performance-range-array-length  —  `RANGE_ARRAY_LENGTH`

- **规则类**: `org.plumb.style.performance.RangeArrayLengthCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-129
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Array index uses `.length` directly -- should be `.length - 1` (RANGE_ARRAY_LENGTH).

**规则约束 / 可调参数**: (无)

### ast-performance-range-array-offset  —  `RANGE_ARRAY_OFFSET`

- **规则类**: `org.plumb.style.performance.RangeArrayOffsetCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-129
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> arraycopy length / offset off-by-one (RANGE_ARRAY_OFFSET).

**规则约束 / 可调参数**: (无)

### ast-performance-range-string-index  —  `RANGE_STRING_INDEX`

- **规则类**: `org.plumb.style.performance.RangeStringIndexCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> indexOf() >= 0 : use contains (RANGE_STRING_INDEX).

**规则约束 / 可调参数**: (无)

### ast-performance-range-string-length  —  `RANGE_STRING_LENGTH`

- **规则类**: `org.plumb.style.performance.RangeStringLengthCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-129
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> string index equals length() : should be length()-1 (RANGE_STRING_LENGTH).

**规则约束 / 可调参数**: (无)

### ast-performance-return-empty-not-null  —  `RETURN_EMPTY_NOT_NULL`

- **规则类**: `org.plumb.style.performance.ReturnEmptyCollectionNotNullCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> return empty collection instead of null.

**规则约束 / 可调参数**: (无)

### ast-performance-return-null-collection  —  `RETURN_NULL_COLLECTION`

- **规则类**: `org.plumb.style.performance.ReturnEmptyCollectionRatherThanNullCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> return null for collection : use empty.

**规则约束 / 可调参数**: (无)

### ast-performance-returns-null-collection  —  `RETURNS_NULL_COLLECTION`

- **规则类**: `org.plumb.style.performance.ReturnsNullInsteadOfEmptyCollectionCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Return empty collection instead of `null`.

**规则约束 / 可调参数**: (无)

### ast-performance-simplify-bool  —  `SIMPLIFY_BOOL`

- **规则类**: `org.plumb.style.performance.SimplifyBooleanExpressionsCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> simplify x == true / x == false to x / !x.

**规则约束 / 可调参数**: (无)

### ast-performance-simplify-conditional  —  `SIMPLIFY_CONDITIONAL`

- **规则类**: `org.plumb.style.performance.SimplifyConditionalCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> ternary branches are the same : use the value.

**规则约束 / 可调参数**: (无)

### ast-performance-string-buffer-constructor  —  `STRING_BUFFER_CONSTRUCTOR`

- **规则类**: `org.plumb.style.performance.StringBufferConstructorCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use StringBuilder, not StringBuffer.

**规则约束 / 可调参数**: (无)

### ast-performance-string-buffer-field-serial  —  `STRING_BUFFER_FIELD_SERIAL`

- **规则类**: `org.plumb.style.performance.StringBufferFieldInSerialClassCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> `StringBuffer` field in a {@link Serializable} class is not thread-safe -- use `StringBuilder`.

**规则约束 / 可调参数**: (无)

### ast-performance-string-builder-concatenation-in-loop  —  `STRING_BUILDER_CONCATENATION_IN_LOOP`

- **规则类**: `org.plumb.style.performance.StringBuilderConcatenationCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> String concatenation in a loop should use StringBuilder.

**规则约束 / 可调参数**: (无)

### ast-performance-string-equality-with-equals  —  `STRING_EQUALITY_WITH_EQUALS`

- **规则类**: `org.plumb.style.performance.StringEqualityWithEqualsCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> String comparison with == (should use.equals()).

**规则约束 / 可调参数**: (无)

### ast-performance-string-instantiation  —  `STRING_INSTANTIATION`

- **规则类**: `org.plumb.style.performance.StringInstantiationCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid new String("...") : string literals are interned.

**规则约束 / 可调参数**: (无)

### ast-performance-string-literal-equality  —  `STRING_LITERAL_EQUALITY`

- **规则类**: `org.plumb.style.performance.StringLiteralEqualityCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Comparing `String` literals with `==` / `!=` ? use `.equals()`.

**规则约束 / 可调参数**: (无)

### ast-performance-synchronization-on-string-or-boxed  —  `SYNCHRONIZATION_ON_STRING_OR_BOXED`

- **规则类**: `org.plumb.style.performance.SynchronizationOnStringOrBoxedCheck`
- **分类**: `performance`
- **严重度**: HIGH
- **类型**: MULTITHREADING
- **CWE**: CWE-820
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Synchronize on interned String / boxed primitive is unsafe (use a private Object lock).

**规则约束 / 可调参数**: (无)

### ast-performance-unnecessary-boxing  —  `UNNECESSARY_BOXING`

- **规则类**: `org.plumb.style.performance.UnnecessaryBoxingCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Unnecessary primitive boxing (`new Integer(5)` etc.) -- prefer autoboxing or `Integer.valueOf`.

**规则约束 / 可调参数**: (无)

### ast-performance-unnecessary-case-change  —  `UNNECESSARY_CASE_CHANGE`

- **规则类**: `org.plumb.style.performance.UnnecessaryCaseChangeCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use equalsIgnoreCase() instead of toLowerCase().equals().

**规则约束 / 可调参数**: (无)

### ast-performance-unnecessary-cast  —  `UNNECESSARY_CAST`

- **规则类**: `org.plumb.style.performance.UnnecessaryCastCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> unnecessary cast.

**规则约束 / 可调参数**: (无)

### ast-performance-unnecessary-constructor  —  `UNNECESSARY_CONSTRUCTOR`

- **规则类**: `org.plumb.style.performance.UnnecessaryConstructorCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> No-arg empty constructor duplicates the default.

**规则约束 / 可调参数**: (无)

### ast-performance-use-arraylist-instead-of-vector  —  `USE_ARRAYLIST_INSTEAD_OF_VECTOR`

- **规则类**: `org.plumb.style.performance.UseArrayListInsteadOfVectorCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use ArrayList instead of Vector.

**规则约束 / 可调参数**: (无)

### ast-performance-use-assertequals  —  `USE_ASSERTEQUALS`

- **规则类**: `org.plumb.style.performance.UseAssertEqualsInsteadOfAssertTrueCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use assertEquals() instead of assertTrue(a == b).

**规则约束 / 可调参数**: (无)

### ast-performance-use-asserttrue  —  `USE_ASSERTTRUE`

- **规则类**: `org.plumb.style.performance.UseAssertTrueInsteadOfAssertEqualsCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use assertTrue/assertFalse instead of assertEquals(true, x).

**规则约束 / 可调参数**: (无)

### ast-performance-use-collection-is-empty  —  `USE_COLLECTION_IS_EMPTY`

- **规则类**: `org.plumb.style.performance.UseCollectionIsEmptyCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use Collection.isEmpty() instead of size() == 0.

**规则约束 / 可调参数**: (无)

### ast-performance-use-indexof-char  —  `USE_INDEXOF_CHAR`

- **规则类**: `org.plumb.style.performance.UseIndexOfCharCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use indexOf(char) instead of indexOf(String) for single chars.

**规则约束 / 可调参数**: (无)

### ast-performance-useless-string-valueof  —  `USELESS_STRING_VALUEOF`

- **规则类**: `org.plumb.style.performance.UselessStringValueOfCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> String.valueOf() is unnecessary : use the value directly.

**规则约束 / 可调参数**: (无)

### ast-performance-use-short-array-init  —  `USE_SHORT_ARRAY_INIT`

- **规则类**: `org.plumb.style.performance.UseShortArrayInitializerCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use short array initializer form.

**规则约束 / 可调参数**: (无)

### ast-performance-use-stringbuffer  —  `USE_STRINGBUFFER`

- **规则类**: `org.plumb.style.performance.UseStringBufferForStringAppendsCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> "use StringBuilder in loops instead of string.

**规则约束 / 可调参数**: (无)

### ast-security-avoid-coded-ip  —  `AVOID_CODED_IP`

- **规则类**: `org.plumb.style.security.AvoidUsingHardCodedIPCheck`
- **分类**: `security`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Hard-coded IP address in code.

**规则约束 / 可调参数**: (无)

### ast-security-case-insensitive-comparison  —  `CASE_INSENSITIVE_COMPARISON`

- **规则类**: `org.plumb.style.security.CaseInsensitiveComparisonCheck`
- **分类**: `security`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Case-insensitive `String` comparison should use `equalsIgnoreCase` rather than `toLowerCase()/toUpperCase() + equals()`.

**规则约束 / 可调参数**: (无)

### ast-security-cleartext-socket-usage  —  `CLEARTEXT_SOCKET_USAGE`

- **规则类**: `org.plumb.style.security.CleartextSocketUsageCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-319
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-319: cleartext transmission of sensitive information.
>
> The previous DFA half (regex over the raw source file) was lenient by design: it fired on
> every `new Socket(...)` anywhere in the file and then used the AI dedup prompt as the
> primary filter. The AST half narrows to the enclosing method body and checks local variable names
> / string literals for the sensitive-word heuristic, then dedups at one-finding-
> per-enclosing-method.

**规则约束 / 可调参数**: (无)

### ast-security-compare-classes-by-name  —  `COMPARE_CLASSES_BY_NAME`

- **规则类**: `org.plumb.style.security.CompareClassesByNameCheck`
- **分类**: `security`
- **严重度**: LOW
- **类型**: SECURITY
- **CWE**: CWE-273
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-273: compare classes by name / reference equality on `getClass()`.

**规则约束 / 可调参数**: (无)

### ast-security-cors-misconfig  —  `CORS_MISCONFIG`

- **规则类**: `org.plumb.style.security.CorsMisconfigurationCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-942
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-942: permissive CORS configuration (allowedOrigins = "*").

**规则约束 / 可调参数**: (无)

### ast-security-dc-dangerous  —  `DC_DANGEROUS`

- **规则类**: `org.plumb.style.security.DcDangerousCheck`
- **分类**: `security`
- **严重度**: HIGH
- **类型**: SECURITY
- **CWE**: CWE-676
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-dc-deadlock  —  `DC_DEADLOCK`

- **规则类**: `org.plumb.style.security.DcDeadlockCheck`
- **分类**: `security`
- **严重度**: HIGH
- **类型**: SECURITY
- **CWE**: CWE-662
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-hardcoded-absolute-path  —  `HARDCODED_ABSOLUTE_PATH`

- **规则类**: `org.plumb.style.security.DmiHardcodedAbsolutePathCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-22
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Hard-coded absolute path (DMI_HARDCODED_ABSOLUTE_PATH, CWE-22).

**规则约束 / 可调参数**: (无)

### ast-security-env-var-info-exposure-ast  —  `ENV_VAR_INFO_EXPOSURE_AST`

- **规则类**: `org.plumb.style.security.EnvVarInfoExposureAstCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-526
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-hardcoded-credentials  —  `HARDCODED_CREDENTIALS`

- **规则类**: `org.plumb.style.security.HardcodedCredentialsCheck`
- **分类**: `security`
- **严重度**: CRITICAL
- **类型**: SECURITY
- **CWE**: CWE-798
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> String literal assigned to a credential-named variable / field (use config / secrets manager).
>
> S2 audit (2026-10-17) fixed the type filter -- the original `if (!primitiveType &&
> !"String".equals(...)) if(!"char".equals(...))` chain was confusing and the `char` branch
> was dead code (the `char` case had already been let through by the first check). The new
> filter is a clean predicate: accept `String` and `char[]` (the two practical
> representations of credential material) and reject everything else.

**规则约束 / 可调参数**: (无)

### ast-security-hardcoded-ip  —  `HARDCODED_IP`

- **规则类**: `org.plumb.style.security.HardcodedIpCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-547
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Hardcoded IP address in source.

**规则约束 / 可调参数**: (无)

### ast-security-hardcoded-password-sink-ast  —  `HARDCODED_PASSWORD_SINK_AST`

- **规则类**: `org.plumb.style.security.HardcodedPasswordSinkAstCheck`
- **分类**: `security`
- **严重度**: HIGH
- **类型**: SECURITY
- **CWE**: CWE-259
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-hardcoded-uri  —  `HARDCODED_URI`

- **规则类**: `org.plumb.style.security.HardcodedURICheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> hard-coded URI : use a config value.

**规则约束 / 可调参数**: (无)

### ast-security-high-entropy-string  —  `HIGH_ENTROPY_STRING`

- **规则类**: `org.plumb.style.security.HighEntropyStringCheck`
- **分类**: `security`
- **严重度**: LOW
- **类型**: SECURITY
- **CWE**: CWE-798
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-798 / CWE-331: hard-coded credential (high-entropy string).
>
> Classifies a string literal as `LOW / MEDIUM / HIGH` based on Shannon entropy +
> character-class diversity, and emits a finding on `HIGH` literals. The thresholds mirror R9
> (low entropy = < 2.85 bits/char; HIGH requires &ge; 3 distinct char classes).

**规则约束 / 可调参数**: (无)

### ast-security-information-exposure  —  `INFORMATION_EXPOSURE`

- **规则类**: `org.plumb.style.security.InformationExposureCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-200
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-insecure-communication  —  `INSECURE_COMMUNICATION`

- **规则类**: `org.plumb.style.security.InsecureCommunicationCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-319
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-insecure-cookie  —  `INSECURE_COOKIE`

- **规则类**: `org.plumb.style.security.InsecureCookieCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-614
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-insecure-crypto  —  `INSECURE_CRYPTO`

- **规则类**: `org.plumb.style.security.InsecureCryptoCheck`
- **分类**: `security`
- **严重度**: HIGH
- **类型**: SECURITY
- **CWE**: CWE-327
- **外部等价规则**:
  - **SpotBugs**: CUSTOM_MESSAGE_DIGEST_MD5 / CIPHER_DES

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### dfa-security-insecure-temp-file  —  `INSECURE_TEMP_FILE`

- **规则类**: `org.plumb.style.security.InsecureTempFileCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-379
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-379: Insecure Temporary File / Insecure Directory.
>
> Detects creation of `File` / `FileOutputStream` / `FileWriter` with a path
> (literal or alias-of-literal) that contains `insecureDir` or `testcases`.

**规则约束 / 可调参数**: (无)

### ast-security-insecure-temp-file-perms  —  `INSECURE_TEMP_FILE_PERMS`

- **规则类**: `org.plumb.style.security.InsecureTempFilePermsCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-378
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-literal-sourced-hint  —  `LITERAL_SOURCED_HINT`

- **规则类**: `org.plumb.style.security.LiteralSourcedHintCheck`
- **分类**: `security`
- **严重度**: LOW
- **类型**: SECURITY
- **CWE**: CWE-798, CWE-798
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-798: hard-coded credential hint (literal-sourced string).
>
> Fires on every local-variable assignment of a String literal &ge; 8 chars. The check is
> sketch-level: the AI dedup prompt is the primary filter; FP rate is intentionally high so we
> don't miss real secrets.

**规则约束 / 可调参数**: (无)

### ast-security-loop-once  —  `LOOP_ONCE`

- **规则类**: `org.plumb.style.security.LoopExecutingAtMostOnceCheck`
- **分类**: `security`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> loop executes at most once : refactor to a plain statement.

**规则约束 / 可调参数**: (无)

### ast-security-missing-authorization  —  `MISSING_AUTHORIZATION`

- **规则类**: `org.plumb.style.security.MissingAuthorizationCheck`
- **分类**: `security`
- **严重度**: HIGH
- **类型**: SECURITY
- **CWE**: CWE-862
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-missing-input-validation  —  `MISSING_INPUT_VALIDATION`

- **规则类**: `org.plumb.style.security.MissingInputValidationCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-20
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-null-deref-from-return-ast  —  `NULL_DEREF_FROM_RETURN_AST`

- **规则类**: `org.plumb.style.security.NullDerefFromReturnAstCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-690
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> AST-level CWE-690 (NULL Pointer Dereference From Return) checker.

**规则约束 / 可调参数**: (无)

### ast-security-obsolete-api  —  `OBSOLETE_API`

- **规则类**: `org.plumb.style.security.ObsoleteApiCheck`
- **分类**: `security`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **CWE**: CWE-477
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-477: Use of Obsolete Functions.
>
> Detects deprecated / discouraged JDK methods and recommends a modern replacement in the AI
> prompt.

**规则约束 / 可调参数**: (无)

### ast-security-orm-sql-interpolation  —  `ORM_SQL_INTERPOLATION`

- **规则类**: `org.plumb.style.security.OrmSqlInterpolationCheck`
- **分类**: `security`
- **严重度**: HIGH
- **类型**: SECURITY
- **CWE**: CWE-89
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-89: an ORM mapper method whose SQL is built by string interpolation.
>
> <h2>Why this is not a taint rule</h2>
>
> The dangerous MyBatis / JPA shape is:
>
> ```
>
> &#64;Select("SELECT * FROM users WHERE name = '${name}'")
> User findByName(String name);
>
> ```
>
> The tainted value is spliced into the ANNOTATION's string constant. No call ever carries it,
> so there is no data-flow edge for a taint engine to follow -- the value never becomes a call
> argument. The TAC does not even carry annotations: the mapper method lowers to a bare `RETURN`. Reaching this shape therefore needs the annotation itself, which only the AST has.
>
> <h2>What the rule knows about the frameworks</h2>
>
> The two MyBatis placeholder forms have opposite safety properties, and that difference is the
> whole point of the rule:
>
> <li>`#{name`} becomes a `PreparedStatement` placeholder (`?`). The value is
> bound as data and cannot change the SQL structure. This is the safe form.
> <li>`${name`} is a raw string substitution. The value becomes part of the SQL text and
> can change the statement. This is the dangerous form.
>
> `${`} does have legitimate uses -- a dynamic table name, a dynamic column, an `ORDER BY` direction -- because a prepared-statement placeholder cannot stand in for SQL
> structure. So the rule does not report every `${`}; it reports a `${`} whose
> placeholder names a method parameter. A `${`} that names nothing the method receives cannot
> be influenced by the caller and is left alone.
>
> The same distinction applies to the JPA / Hibernate annotation forms that take raw JPQL or
> HQL: `@Query("... " + value)` and `@Query("... ${value`")} are reported, and a query
> whose text carries no interpolation is not.
>
> <h2>What is reported</h2>
>
> <li>A mapper SQL annotation whose text contains a `${...`} naming one of the method's
> parameters.
> <li>A query annotation whose text is built by concatenating a method parameter into the query
> string.
>
> Both are reported at the annotation, because that is where the SQL text lives.

**规则约束 / 可调参数**: (无)

### ast-security-path  —  `PATH`

- **规则类**: `org.plumb.style.security.PathCheck`
- **分类**: `security`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Path manipulation via `getCanonicalPath()` / `getAbsolutePath()`.

**规则约束 / 可调参数**: (无)

### dfa-security-persistent-cookie  —  `PERSISTENT_COOKIE`

- **规则类**: `org.plumb.style.security.PersistentCookieCheck`
- **分类**: `security`
- **严重度**: LOW
- **类型**: SECURITY
- **CWE**: CWE-539
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-539: Information Exposure Through Persistent Cookie.
>
> Detects a `Cookie.setMaxAge(N)` call where `N` is a compile-time constant >
> 3600 (one hour). The check statically folds `*`, `/`, `+`, `-`, `-N` expressions with integer literals.

**规则约束 / 可调参数**: (无)

### ast-security-predictable-key-password  —  `PREDICTABLE_KEY_PASSWORD`

- **规则类**: `org.plumb.style.security.PredictableKeyPasswordCheck`
- **分类**: `security`
- **严重度**: HIGH
- **类型**: SECURITY
- **CWE**: CWE-259
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-predictable-random-seed  —  `PREDICTABLE_RANDOM_SEED`

- **规则类**: `org.plumb.style.security.PredictableRandomSeedCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-332
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-process-control  —  `PROCESS_CONTROL`

- **规则类**: `org.plumb.style.security.ProcessControlCheck`
- **分类**: `security`
- **严重度**: HIGH
- **类型**: SECURITY
- **CWE**: CWE-114
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### dfa-security-runtime-exec  —  `RUNTIME_EXEC`

- **规则类**: `org.plumb.style.security.RuntimeExecCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-511
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-511: Logic / Time Bomb.
>
> Detects `Runtime.exec(...)` calls (and `Runtime.getRuntime().exec(...)` chained
> calls). These can be triggered by logic bombs.

**规则约束 / 可调参数**: (无)

### dfa-security-shell-error-exposure  —  `SHELL_ERROR_EXPOSURE`

- **规则类**: `org.plumb.style.security.ShellErrorExposureCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-535
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-535: Information Exposure Through Shell Error.
>
> Detects `System.err.println / .print / .write(...)` calls (and `OutputStreamWriter`-wrapped variants) whose argument string contains a sensitive keyword
> (session-id, password, cookie, token, secret).

**规则约束 / 可调参数**: (无)

### ast-security-static-api-key  —  `STATIC_API_KEY`

- **规则类**: `org.plumb.style.security.StaticApiKeyCheck`
- **分类**: `security`
- **严重度**: HIGH
- **类型**: SECURITY
- **CWE**: CWE-798
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-unbounded-mem-alloc  —  `UNBOUNDED_MEM_ALLOC`

- **规则类**: `org.plumb.style.security.UnboundedMemAllocCheck`
- **分类**: `security`
- **严重度**: HIGH
- **类型**: SECURITY
- **CWE**: CWE-789
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-unchecked-return  —  `UNCHECKED_RETURN`

- **规则类**: `org.plumb.style.security.UncheckedReturnCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-252
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-252: unchecked return value.

**规则约束 / 可调参数**: (无)

### ast-security-unlogged-security-exception  —  `UNLOGGED_SECURITY_EXCEPTION`

- **规则类**: `org.plumb.style.security.UnloggedSecurityExceptionCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-778
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-unused-variable-assignment  —  `UNUSED_VARIABLE_ASSIGNMENT`

- **规则类**: `org.plumb.style.security.UnusedVariableAstCheck`
- **分类**: `security`
- **严重度**: LOW
- **类型**: STYLE
- **CWE**: CWE-563
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-weak-hash  —  `WEAK_HASH`

- **规则类**: `org.plumb.style.security.WeakHashChecker`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-328
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-328: Use of Weak Hash.
>
> Detects calls to `java.security.MessageDigest.getInstance(String)` with a literal weak /
> broken hash algorithm name. The rule is AST-only (no TAC, no data flow needed) -- a
> single-method-call shape with a string-literal first arg.

**规则约束 / 可调参数**: (无)

### ast-security-weak-prng  —  `WEAK_PRNG`

- **规则类**: `org.plumb.style.security.WeakPrngCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-338
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-security-weak-url-sanitization  —  `WEAK_URL_SANITIZATION`

- **规则类**: `org.plumb.style.security.WeakUrlSanitizationCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-20
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-absent-codeblock-in-case  —  `ABSENT_CODEBLOCK_IN_CASE`

- **规则类**: `org.plumb.style.style.AbsentCodeblockInCaseCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-484
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Case statement is missing a code block.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `requireBraces` | BOOLEAN | `true` | Fire on every multi-statement case body |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `requireBraces` | BOOLEAN | `true` | Fire on every multi-statement case body |

### ast-style-ambiguous-precedence  —  `AMBIGUOUS_PRECEDENCE`

- **规则类**: `org.plumb.style.style.AmbiguousOperatorPrecedenceCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-783
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> ambiguous operator precedence, add explicit parentheses.

**规则约束 / 可调参数**: (无)

### ast-style-annotation-location  —  `ANNOTATION_LOCATION`

- **规则类**: `org.plumb.style.style.AnnotationLocationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Annotation should be on same line as target.

**规则约束 / 可调参数**: (无)

### ast-style-annotation-on-same-line  —  `ANNOTATION_ON_SAME_LINE`

- **规则类**: `org.plumb.style.style.AnnotationOnSameLineCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Annotation should be on same line as target.

**规则约束 / 可调参数**: (无)

### ast-style-annotation-table  —  `ANNOTATION_TABLE`

- **规则类**: `org.plumb.style.style.AnnoTblCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Missing `@author` / `@version` Javadoc tag on a type declaration.

**规则约束 / 可调参数**: (无)

### ast-style-anon-inner-length  —  `ANON_INNER_LENGTH`

- **规则类**: `org.plumb.style.style.AnonInnerLengthCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Anonymous inner class is too long.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `max` | INT
> | `20` | Maximum allowed lines for an anonymous class |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `max` | INT | `20` | Maximum allowed lines for an anonymous class |

### ast-style-assertion-boundary  —  `ASSERTION_BOUNDARY`

- **规则类**: `org.plumb.style.style.AssertionBoundaryCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-assignment-in-condition  —  `ASSIGNMENT_IN_CONDITION`

- **规则类**: `org.plumb.style.style.AssignmentInConditionCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-481
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-481: Assigning Instead of Comparing.

**规则约束 / 可调参数**: (无)

### ast-style-assign-in-operand  —  `ASSIGN_IN_OPERAND`

- **规则类**: `org.plumb.style.style.AssignmentInOperandCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-783
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> assignment in operand : separate from condition.

**规则约束 / 可调参数**: (无)

### ast-style-assignment-to-non-final-static  —  `ASSIGNMENT_TO_NON_FINAL_STATIC`

- **规则类**: `org.plumb.style.style.AssignmentToNonFinalStaticCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Assignment to a non-final static field.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `ignoreFinalFields` | BOOLEAN | `true` | Skip final fields (immutable by design) |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreFinalFields` | BOOLEAN | `true` | Skip final fields (immutable by design) |

### ast-style-atclause-order  —  `ATCLAUSE_ORDER`

- **规则类**: `org.plumb.style.style.AtclauseOrderCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> `@return` before `@param` in Javadoc (heuristic).

**规则约束 / 可调参数**: (无)

### ast-style-deeply-nested-if  —  `DEEPLY_NESTED_IF`

- **规则类**: `org.plumb.style.style.AvoidDeeplyNestedIfStmtsCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> if-statement is nested more than 4 levels deep.

**规则约束 / 可调参数**: (无)

### ast-style-avoid-enums  —  `AVOID_ENUMS`

- **规则类**: `org.plumb.style.style.AvoidEnumsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Enum is a class; consider if it has too many methods.

**规则约束 / 可调参数**: (无)

### ast-style-avoidescapedunicode  —  `AVOIDESCAPEDUNICODE`

- **规则类**: `org.plumb.style.style.AvoidEscapedUnicodeCharactersCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Avoid escaped Unicode characters (use direct).

**规则约束 / 可调参数**: (无)

### ast-style-avoid-literals-in-if  —  `AVOID_LITERALS_IN_IF`

- **规则类**: `org.plumb.style.style.AvoidLiteralsInIfConditionCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Avoid literals in if conditions (use named constants).

**规则约束 / 可调参数**: (无)

### ast-style-avoid-messagedigest-field  —  `AVOID_MESSAGEDIGEST_FIELD`

- **规则类**: `org.plumb.style.style.AvoidMessageDigestFieldCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-362
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid MessageDigest as a field (not thread-safe).

**规则约束 / 可调参数**: (无)

### ast-style-avoid-native-methods  —  `AVOID_NATIVE_METHODS`

- **规则类**: `org.plumb.style.style.AvoidNativeMethodsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid native methods (JNI ties to platform).

**规则约束 / 可调参数**: (无)

### ast-style-avoid-nested-blocks  —  `AVOID_NESTED_BLOCKS`

- **规则类**: `org.plumb.style.style.AvoidNestedBlocksCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Avoid nested blocks.

**规则约束 / 可调参数**: (无)

### ast-style-avoid-print-stack-trace  —  `AVOID_PRINT_STACK_TRACE`

- **规则类**: `org.plumb.style.style.AvoidPrintStackTraceCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-209
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid printStackTrace() : use a logger.

**规则约束 / 可调参数**: (无)

### ast-style-avoid-protected-field-in-final-class  —  `AVOID_PROTECTED_FIELD_IN_FINAL_CLASS`

- **规则类**: `org.plumb.style.style.AvoidProtectedFieldInFinalClassCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Protected field in final class.

**规则约束 / 可调参数**: (无)

### ast-style-avoid-protected-method-in-final-class  —  `AVOID_PROTECTED_METHOD_IN_FINAL_CLASS`

- **规则类**: `org.plumb.style.style.AvoidProtectedMethodInFinalClassCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Protected method in final class (should be private).

**规则约束 / 可调参数**: (无)

### ast-style-reassign-loop-var  —  `REASSIGN_LOOP_VAR`

- **规则类**: `org.plumb.style.style.AvoidReassigningLoopVariablesCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-563
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid reassigning the loop counter inside the body.

**规则约束 / 可调参数**: (无)

### ast-style-reassign-parameter  —  `REASSIGN_PARAMETER`

- **规则类**: `org.plumb.style.style.AvoidReassigningParametersCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method parameter is reassigned.

**规则约束 / 可调参数**: (无)

### ast-style-avoid-star-import  —  `AVOID_STAR_IMPORT`

- **规则类**: `org.plumb.style.style.AvoidStarImportCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: AvoidStarImport

**规则说明**

> Avoid star imports.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `allowStatic` | BOOLEAN | `true` | Allow star imports on static members |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `allowStatic` | BOOLEAN | `true` | Allow star imports on static members |

### ast-style-avoid-static-import  —  `AVOID_STATIC_IMPORT`

- **规则类**: `org.plumb.style.style.AvoidStaticImportCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid static imports.

**规则约束 / 可调参数**: (无)

### ast-style-avoid-threadgroup  —  `AVOID_THREADGROUP`

- **规则类**: `org.plumb.style.style.AvoidThreadGroupCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid ThreadGroup (use Executor instead).

**规则约束 / 可调参数**: (无)

### ast-style-avoid-octal-values  —  `AVOID_OCTAL_VALUES`

- **规则类**: `org.plumb.style.style.AvoidUsingOctalValuesCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-682
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid octal literals (start with 0).

**规则约束 / 可调参数**: (无)

### ast-style-avoid-volatile  —  `AVOID_VOLATILE`

- **规则类**: `org.plumb.style.style.AvoidUsingVolatileCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid volatile (use proper synchronization).

**规则约束 / 可调参数**: (无)

### ast-style-bad-check-for-odd  —  `BAD_CHECK_FOR_ODD`

- **规则类**: `org.plumb.style.style.BadCheckForOddCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> `x % 2 == 1` is wrong for negative numbers — use bitwise AND instead.

**规则约束 / 可调参数**: (无)

### ast-style-badly-overridden-adapter  —  `BADLY_OVERRIDDEN_ADAPTER`

- **规则类**: `org.plumb.style.style.BadlyOverriddenAdapterCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Badly overridden adapter.

**规则约束 / 可调参数**: (无)

### ast-style-bad-shift  —  `BAD_SHIFT`

- **规则类**: `org.plumb.style.style.BadShiftCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-758
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-758: shift amount out of range (negative or &ge; bit width).

**规则约束 / 可调参数**: (无)

### ast-style-badsyntaxinregex  —  `BADSYNTAXINREGEX`

- **规则类**: `org.plumb.style.style.BadSyntaxInRegexCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Bad syntax in regular expression.

**规则约束 / 可调参数**: (无)

### ast-style-bc-equals-no-hash  —  `BC_EQUALS_NO_HASH`

- **规则类**: `org.plumb.style.style.BcEqualsNoHashcodeCallCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> equals() but no hashCode() : broken contract (BC_EQUALS_NO_HASHCODE).

**规则约束 / 可调参数**: (无)

### ast-style-bigdec-double  —  `BIGDEC_DOUBLE`

- **规则类**: `org.plumb.style.style.BigDecimalDoubleConstructorCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-682
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> new BigDecimal(double) : use BigDecimal(String) for exact value.

**规则约束 / 可调参数**: (无)

### ast-style-bitwise-and  —  `BITWISE_AND`

- **规则类**: `org.plumb.style.style.BitwiseAndCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-783
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> bitwise AND `&` in conditional : likely meant logical AND `&&` (BIT_AND).

**规则约束 / 可调参数**: (无)

### ast-style-bitwise-or  —  `BITWISE_OR`

- **规则类**: `org.plumb.style.style.BitwiseOrCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-783
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> bitwise OR `|` in conditional : likely meant logical OR `||` (BIT_OR).

**规则约束 / 可调参数**: (无)

### ast-style-bool-if  —  `BOOL_IF`

- **规则类**: `org.plumb.style.style.BooleanLiteralInIfCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> if-statement with literal/null condition.

**规则约束 / 可调参数**: (无)

### ast-style-bool-negation  —  `BOOL_NEGATION`

- **规则类**: `org.plumb.style.style.BooleanLiteralNegationCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> !true / !false : invert the condition.

**规则约束 / 可调参数**: (无)

### ast-style-boolean-method-returns-null  —  `BOOLEAN_METHOD_RETURNS_NULL`

- **规则类**: `org.plumb.style.style.BooleanMethodReturnCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-476
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Boolean-returning method that returns null is dangerous (autoboxing NPE).

**规则约束 / 可调参数**: (无)

### ast-style-call-super-in-constructor  —  `CALL_SUPER_IN_CONSTRUCTOR`

- **规则类**: `org.plumb.style.style.CallSuperInConstructorCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> subclass constructor should call super(...) explicitly.

**规则约束 / 可调参数**: (无)

### ast-style-call-to-thread-run  —  `CALL_TO_THREAD_RUN`

- **规则类**: `org.plumb.style.style.CallToThreadRunCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-572
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Call `Thread.start()` not `Thread.run()`.
>
> CWE-572. The canonical detector is this {@link AstChecker}, which walks the {@link
> com.github.javaparser.ast.CompilationUnit} via `AstDispatcher` and verifies the call's
> receiver is a `Thread` (or subclass) instance.
>
> Receiver type comes from symbol-solver type resolution via {@link
> StyleContext#resolvedTypeOf}: if the receiver's resolved type descends from `java.lang.Thread`, the rule fires. When symbol resolution fails, the rule falls back to a
> name-based heuristic on the receiver name (`"Thread"` / `"task"` / `"worker"` /
> `"Runnable"`).

**规则约束 / 可调参数**: (无)

### ast-style-call-to-unsupported-method  —  `CALL_TO_UNSUPPORTED_METHOD`

- **规则类**: `org.plumb.style.style.CallToUnsupportedMethodCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Call to unsupported method (method name starting with `internal`).

**规则约束 / 可调参数**: (无)

### ast-style-cast-arithmetic-operand  —  `CAST_ARITHMETIC_OPERAND`

- **规则类**: `org.plumb.style.style.CastArithmeticOperandCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Cast on arithmetic operand may cause overflow.

**规则约束 / 可调参数**: (无)

### ast-style-catch-nullpointer-exception  —  `CATCH_NULLPOINTER_EXCEPTION_2`

- **规则类**: `org.plumb.style.style.CatchNullPointerExceptionCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-395
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-comparator-result  —  `COMPARATOR_RESULT`

- **规则类**: `org.plumb.style.style.CheckTypeOfResultOfComparatorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Check type of result of `Comparator.compare`.

**规则约束 / 可调参数**: (无)

### ast-style-child-class-shadow-field  —  `CHILD_CLASS_SHADOW_FIELD`

- **规则类**: `org.plumb.style.style.ChildClassShadowFieldCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-849
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-class-based-issue-491  —  `CLASS_BASED_ISSUE_491`

- **规则类**: `org.plumb.style.style.ClassBasedIssueCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **CWE**: CWE-491
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-class-field-shadow  —  `CLASS_FIELD_SHADOW`

- **规则类**: `org.plumb.style.style.ClassFieldShadowingCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class field has the same name as the enclosing class.

**规则约束 / 可调参数**: (无)

### ast-style-private-members  —  `PRIVATE_MEMBERS`

- **规则类**: `org.plumb.style.style.ClassMembersShouldBePrivateCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class member should be private if not used outside.

**规则约束 / 可调参数**: (无)

### ast-style-class-variable-visibility  —  `CLASS_VAR_VISIBILITY`

- **规则类**: `org.plumb.style.style.ClassVariableVisibilityCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **CWE**: CWE-500
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> public static field should be final or non-public.

**规则约束 / 可调参数**: (无)

### ast-style-private-ctors-final  —  `PRIVATE_CTORS_FINAL`

- **规则类**: `org.plumb.style.style.ClassWithOnlyPrivateConstructorsShouldBeFinalCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class with only private constructors should be final.

**规则约束 / 可调参数**: (无)

### ast-style-clone-without-super  —  `CLONE_WITHOUT_SUPER`

- **规则类**: `org.plumb.style.style.CloneWithoutSuperCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-664
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> clone() should call super.clone().

**规则约束 / 可调参数**: (无)

### ast-style-collapsible-if  —  `COLLAPSIBLE_IF`

- **规则类**: `org.plumb.style.style.CollapsibleIfCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> nested if statements can be collapsed into one with &&.

**规则约束 / 可调参数**: (无)

### ast-style-collapsible-if-statements  —  `COLLAPSIBLE_IF_STATEMENTS`

- **规则类**: `org.plumb.style.style.CollapsibleIfStatementsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Nested if can be combined with the parent if.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `ignoreElseBranch` | BOOLEAN | `true` | Skip when the parent if has an else branch |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreElseBranch` | BOOLEAN | `true` | Skip when the parent if has an else branch |

### ast-style-comment-default-access  —  `COMMENT_DEFAULT_ACCESS`

- **规则类**: `org.plumb.style.style.CommentDefaultAccessCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Default access modifier should be commented.

**规则约束 / 可调参数**: (无)

### ast-style-comment-info-leak  —  `COMMENT_INFO_LEAK`

- **规则类**: `org.plumb.style.style.CommentInfoLeakCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **CWE**: CWE-615
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-615 (Information Exposure Through Comments).
>
> AI-primary checker. Detects source-file comments whose body contains sensitive-looking data:
> passwords, API keys, connection-URL schemes (jdbc:/mongodb:/ etc.), PEM blocks, AWS access-key
> ids, Stripe live/test keys, GitHub PATs, etc. The static half is intentionally lenient; the AI
> dedup pass judges TP / FP from the actual source context.
>
> The AST implementation walks every comment via the standard {@link
> com.github.javaparser.ast.comments.Comment} attachment system that JavaParser already populates
> when it parses the source, so multi-line comments are handled without manual accumulation. HTML
> comments (`<!-- ... -->`) are covered by a separate pass, because the Java-comment parser
> does not see them.

**规则约束 / 可调参数**: (无)

### ast-style-commentsindentation  —  `COMMENTSINDENTATION`

- **规则类**: `org.plumb.style.style.CommentsIndentationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Comments should be properly indented.

**规则约束 / 可调参数**: (无)

### ast-style-compare-instead-of-assigning  —  `COMPARE_INSTEAD_OF_ASSIGNING`

- **规则类**: `org.plumb.style.style.CompareInsteadOfAssigningCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-482
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-482: Comparing Instead of Assigning.
>
> Detects `(variable == expr)` / `(variable != expr)` expressions where the variable
> is a single-name writer elsewhere in the same method body. The semantic clue that distinguishes
> TP from FP is the absence of any `=` assignment to that variable in the body -- i.e. the
> variable is <em>never</em> written, so `==` is a legitimate comparison. If the variable IS
> written elsewhere (e.g. `isZero = (zeroOrOne == 0)` appears in the same method), the
> comparison `isZero == (...)` is most likely a typo for `isZero = (...)` -- the bug
> pattern.

**规则约束 / 可调参数**: (无)

### ast-style-conditional-constant  —  `CONDITIONAL_CONSTANT`

- **规则类**: `org.plumb.style.style.ConditionalOnConstantCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-570
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> conditional has a constant value.

**规则约束 / 可调参数**: (无)

### ast-style-confusing-ternary  —  `CONFUSING_TERNARY`

- **规则类**: `org.plumb.style.style.ConfusingTernaryCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> nested ternary expression is confusing.

**规则约束 / 可调参数**: (无)

### ast-style-interface-constants  —  `INTERFACE_CONSTANTS`

- **规则类**: `org.plumb.style.style.ConstantsInInterfaceCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> interface with only constants : use a class or enum.

**规则约束 / 可调参数**: (无)

### ast-style-control-statement-braces  —  `CONTROL_STATEMENT_BRACES`

- **规则类**: `org.plumb.style.style.ControlStatementBracesCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> control statement should always use braces.

**规则约束 / 可调参数**: (无)

### ast-style-ctor-parameter-count  —  `CTOR_PARAMETER_COUNT`

- **规则类**: `org.plumb.style.style.CtorParameterCountCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Constructor has too many parameters (default threshold 7).

**规则约束 / 可调参数**: (无)

### ast-style-dataflow-anomaly-NOT-IMPLEMENTED  —  `DATAFLOW_ANOMALY_NOT_IMPLEMENTED`

- **规则类**: `org.plumb.style.style.DataflowAnomalyAnalysisCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> data flow anomaly (UR, DU, DD) — STUB, not registered.

**规则约束 / 可调参数**: (无)

### ast-style-deadcode  —  `DEADCODE`

- **规则类**: `org.plumb.style.style.DeadcodeCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: CORRECTNESS
- **CWE**: CWE-561
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Code after return/throw/break/continue is unreachable.

**规则约束 / 可调参数**: (无)

### ast-style-declaration-order  —  `DECLARATION_ORDER`

- **规则类**: `org.plumb.style.style.DeclarationOrderCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Class member is out of canonical order.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `ignoreConstructors` | BOOLEAN | `false` | Skip constructor ordering check | | `ignoreModifiers`
> | BOOLEAN | `false` | Treat static and instance fields as one group |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreConstructors` | BOOLEAN | `false` | Skip constructor ordering check |
| `ignoreModifiers` | BOOLEAN | `false` | Treat static and instance fields as one group |

### ast-style-default-charset  —  `DEFAULT_CHARSET`

- **规则类**: `org.plumb.style.style.DefaultCharsetCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> `new String()` — relies on platform default charset.

**规则约束 / 可调参数**: (无)

### ast-style-default-package  —  `DEFAULT_PACKAGE`

- **规则类**: `org.plumb.style.style.DefaultPackageCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Class is not in a named package.

**规则约束 / 可调参数**: (无)

### ast-style-deprecated-annotation  —  `DEPRECATED_ANNOTATION`

- **规则类**: `org.plumb.style.style.DeprecatedAnnotationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> `@Deprecated` should declare `since` / `forRemoval`.
>
> Only marker annotations (no arguments) fire; single-member or normal-annotation forms are
> assumed to carry the required metadata.

**规则约束 / 可调参数**: (无)

### ast-style-diamond-operator  —  `DIAMOND_OPERATOR`

- **规则类**: `org.plumb.style.style.DiamondOperatorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Use diamond `<>` operator instead of explicit type.

**规则约束 / 可调参数**: (无)

### ast-style-dmi-bad-month  —  `DMI_BAD_MONTH`

- **规则类**: `org.plumb.style.style.DmiBadMonthCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Month literal in `1..12` range passed to `set/add`/`MONTH` -- use `Calendar.JANUARY (0)` .. `Calendar.DECEMBER (11)` instead (DMI_BAD_MONTH).

**规则约束 / 可调参数**: (无)

### ast-style-dmi-done  —  `DMI_DONE`

- **规则类**: `org.plumb.style.style.DmiDoneCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> @Override method with no real body (DMI_DONE).

**规则约束 / 可调参数**: (无)

### ast-style-dmi-random  —  `DMI_RANDOM`

- **规则类**: `org.plumb.style.style.DmiRandomCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> `new Random(N)` seeded with a predictable constant.

**规则约束 / 可调参数**: (无)

### ast-style-dmi-useless-method  —  `DMI_USELESS_METHOD`

- **规则类**: `org.plumb.style.style.DmiUselessMethodCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> void method with empty body (DMI_USELESS_METHOD).

**规则约束 / 可调参数**: (无)

### ast-style-do-inside-do-privileged  —  `DO_INSIDE_DO_PRIVILEGED`

- **规则类**: `org.plumb.style.style.DoInsideDoPrivilegedCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Action should be inside `doPrivileged`.

**规则约束 / 可调参数**: (无)

### ast-style-dont-import-javalang  —  `DONT_IMPORT_JAVALANG`

- **规则类**: `org.plumb.style.style.DontImportJavaLangCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> java.lang.* is imported automatically : remove import.

**规则约束 / 可调参数**: (无)

### ast-style-dontimportsun  —  `DONTIMPORTSUN`

- **规则类**: `org.plumb.style.style.DontImportSunCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Do not import sun.* packages.

**规则约束 / 可调参数**: (无)

### ast-style-double-checked-locking  —  `DOUBLE_CHECKED_LOCKING`

- **规则类**: `org.plumb.style.style.DoubleCheckedLockingCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-609
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-609 (Double-Checked Locking).
>
> Detects the classic anti-pattern:
>
> ```
> `private static String x = null;            // FLAW: not volatile
>
> public static String helper() {
> if (x == null) {
> synchronized(Klass.class) {
> if (x == null) {
> x = "value";
> `
> }
> }
> }
> }
> ```
>
> Without the `volatile` modifier on the target field, the inner null check can see a
> partially-constructed object on a thread other than the one that performed the initialization
> (the JMM publication guarantee requires `volatile` or equivalent).
>
> Detection strategy:
>
> <ol>
> <li>Walk every {@link IfStmt} via the standard `AstDispatcher`. The condition must be
> `X == null` (a {@link BinaryExpr} with `EQUALS` operator and a {@link
> NullLiteralExpr} on the right side).
> <li>Inside the "then" body, look for a {@link SynchronizedStmt} whose own body contains an
> inner `if (X == null) { X = ... `} (target name matches and a field / variable
> assignment follows).
> <li>Resolve the target identifier. If it names a {@link FieldDeclaration} that does NOT carry
> the `volatile` modifier, fire the finding. Local variables and parameters are skipped
> (the JVM stack is thread-safe; only static / instance fields are affected by the JMM
> publication issue).
> </ol>
>
> Equivalent safe idioms — a `volatile` field, a method-level `synchronized`, a
> fully-synchronized block, or a `ReentrantLock` — do not have the double-check shape and are
> correctly not reported.

**规则约束 / 可调参数**: (无)

### ast-style-duplicate-imports  —  `DUPLICATE_IMPORTS`

- **规则类**: `org.plumb.style.style.DuplicateImportsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> duplicate import : remove the second.

**规则约束 / 可调参数**: (无)

### ast-style-ec-incompatible  —  `EC_INCOMPATIBLE`

- **规则类**: `org.plumb.style.style.EcIncompatibleTypesCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-703
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> incompatible exception types used (EC_INCOMPATIBLE_TYPES).

**规则约束 / 可调参数**: (无)

### ast-style-ec-no-read  —  `EC_NO_READ`

- **规则类**: `org.plumb.style.style.EcNoReadIfEqualToWriteCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-754
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> x = x : pointless self-assignment (EC_NO_READ_IF_EQUAL_TO_WRITE).

**规则约束 / 可调参数**: (无)

### ast-style-ec-null-vararg  —  `EC_NULL_VARARG`

- **规则类**: `org.plumb.style.style.EcNullArgToVarargCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-754
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> null passed as varargs (EC_NULL_ARG_TO_VARARG_METHOD).

**规则约束 / 可调参数**: (无)

### ast-style-ec-unrelated-iface  —  `EC_UNRELATED_IFACE`

- **规则类**: `org.plumb.style.style.EcUnrelatedInterfacesCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> implements unrelated interfaces (EC_UNRELATED_INTERFACES).

**规则约束 / 可调参数**: (无)

### ast-style-ec-unrelated  —  `EC_UNRELATED`

- **规则类**: `org.plumb.style.style.EcUnrelatedTypesCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method returns object unrelated to declared type (EC_UNRELATED_TYPES).

**规则约束 / 可调参数**: (无)

### ast-style-empty-block  —  `EMPTY_BLOCK`

- **规则类**: `org.plumb.style.style.EmptyBlockCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **PMD**: EmptyControlStatement / EmptyCodeBlock

**规则说明**

> Empty block statement.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `allowEmptyCatch` | BOOLEAN | `false` | Skip empty catch blocks | | `allowEmptyLoop` | BOOLEAN |
> `false` | Skip empty for/while/do loops | | `allowEmptyIf` | BOOLEAN | `false` | Skip empty if
> blocks |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `allowEmptyCatch` | BOOLEAN | `false` | Skip empty catch blocks |
| `allowEmptyLoop` | BOOLEAN | `false` | Skip empty for/while/do loops |
| `allowEmptyIf` | BOOLEAN | `false` | Skip empty if blocks |

### ast-style-empty-constructor  —  `EMPTY_CONSTRUCTOR`

- **规则类**: `org.plumb.style.style.EmptyConstructorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> explicit empty constructor is unnecessary.

**规则约束 / 可调参数**: (无)

### ast-style-empty-control-statement  —  `STYLE_EMPTY_CONTROL_STATEMENT`

- **规则类**: `org.plumb.style.style.EmptyControlStatementCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **PMD**: EmptyControlStatement

**规则说明**

> control statement with empty body.

**规则约束 / 可调参数**: (无)

### ast-style-empty-finally-block  —  `EMPTY_FINALLY_BLOCK`

- **规则类**: `org.plumb.style.style.EmptyFinallyBlockCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty finally block : remove it.

**规则约束 / 可调参数**: (无)

### ast-style-empty-if-stmt  —  `EMPTY_IF_STMT`

- **规则类**: `org.plumb.style.style.EmptyIfStmtCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty if-statement (then-branch is a bare ';').

**规则约束 / 可调参数**: (无)

### ast-style-emptylineseparatorcheck-cs  —  `EMPTYLINESEPARATORCHECK_CS`

- **规则类**: `org.plumb.style.style.EmptyLineSeparatorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-empty-method-in-abstract  —  `EMPTY_METHOD_IN_ABSTRACT`

- **规则类**: `org.plumb.style.style.EmptyMethodInAbstractCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Empty method body in abstract class should be marked abstract.

**规则约束 / 可调参数**: (无)

### ast-style-empty-statement-block  —  `EMPTY_STATEMENT_BLOCK`

- **规则类**: `org.plumb.style.style.EmptyStatementBlockCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **CWE**: CWE-398, CWE-390, CWE-390
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-398: Indicator of Poor Code Quality — empty block family.

**规则约束 / 可调参数**: (无)

### ast-style-empty-statement  —  `EMPTY_STATEMENT`

- **规则类**: `org.plumb.style.style.EmptyStatementCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Empty statement (just a semicolon) is useless.

**规则约束 / 可调参数**: (无)

### ast-style-empty-statement-2  —  `EMPTY_STATEMENT_2`

- **规则类**: `org.plumb.style.style.EmptyStatementUsageCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty statement (;) : remove it.

**规则约束 / 可调参数**: (无)

### ast-style-emptystaticinitializer  —  `EMPTYSTATICINITIALIZER`

- **规则类**: `org.plumb.style.style.EmptyStaticInitializerCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Empty static initializer.

**规则约束 / 可调参数**: (无)

### ast-style-empty-switch  —  `EMPTY_SWITCH`

- **规则类**: `org.plumb.style.style.EmptySwitchStatementsCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> switch statement has no cases.

**规则约束 / 可调参数**: (无)

### ast-style-empty-sync-block  —  `EMPTY_SYNC_BLOCK`

- **规则类**: `org.plumb.style.style.EmptySyncBlockCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **CWE**: CWE-585
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-585 -- empty `synchronized` block.
>
> Fires when a {@link SynchronizedStmt} has an empty body. Empty synchronized blocks are always
> a bug or dead code: they acquire a monitor and immediately release it without any synchronization
> happening between, which either (a) is leftover from a refactor, or (b) is a placeholder that was
> never filled in, or (c) -- in the worst case -- is intentional but misleading (the developer was
> trying to use the monitor for memory-visibility side effects, which requires more than just
> acquisition release).
>
> The check walks the {@link com.github.javaparser.ast.CompilationUnit} via {@link
> com.github.javaparser.ast.visitor.VoidVisitorAdapter} (the standard plumb-style `AstDispatcher`) and checks the body of each `synchronized` statement structurally: an empty
> body is one of {@link BlockStmt} with no statements, an {@link EmptyStmt} (just `;`), or a
> body that contains only nested empty statements.

**规则约束 / 可调参数**: (无)

### ast-style-empty-try-block  —  `EMPTY_TRY_BLOCK`

- **规则类**: `org.plumb.style.style.EmptyTryBlockCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty try block : remove it.

**规则约束 / 可调参数**: (无)

### ast-style-empty-while  —  `EMPTY_WHILE`

- **规则类**: `org.plumb.style.style.EmptyWhileStmtCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty while-statement (body is a bare ';').

**规则约束 / 可调参数**: (无)

### ast-style-empty-zip-entry  —  `EMPTY_ZIP_ENTRY`

- **规则类**: `org.plumb.style.style.EmptyZipEntryCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Empty zip entry.

**规则约束 / 可调参数**: (无)

### ast-style-enumabstractmethod  —  `ENUMABSTRACTMETHOD`

- **规则类**: `org.plumb.style.style.EnumAbstractMethodCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> enum should not have abstract methods.

**规则约束 / 可调参数**: (无)

### ast-style-enumidiom-sb  —  `ENUMIDIOM_SB`

- **规则类**: `org.plumb.style.style.EnumIdiomCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Enum idiom check.

**规则约束 / 可调参数**: (无)

### ast-style-env-var-info-exposure  —  `ENV_VAR_INFO_EXPOSURE`

- **规则类**: `org.plumb.style.style.EnvVarInfoExposureCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-526
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-526 (Information Exposure Through Environment Variables).
>
> Fires when a single statement contains both an env-var read (`System.getenv(...)`) and a
> response / log writer call (`getWriter()`, `getOutputStream()`, `IO.writeLine`,
> etc.).

**规则约束 / 可调参数**: (无)

### ast-style-equal-comparison  —  `EQUAL_COMPARISON`

- **规则类**: `org.plumb.style.style.EqualComparisonCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-595
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use.equals() not == for object comparison.

**规则约束 / 可调参数**: (无)

### ast-style-equals-arg-type  —  `EQUALS_ARG_TYPE`

- **规则类**: `org.plumb.style.style.EqualsArgumentTypeCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> equals(Object) param type isn't java.lang.Object (CWE-697).

**规则约束 / 可调参数**: (无)

### ast-style-equals-hashcode  —  `EQUALS_HASHCODE`

- **规则类**: `org.plumb.style.style.EqualsHashCodeCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class overrides equals() but not hashCode().

**规则约束 / 可调参数**: (无)

### ast-style-equals-no-object  —  `EQUALS_NO_OBJECT`

- **规则类**: `org.plumb.style.style.EqualsNoObjectCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> equals() parameter is not Object (EQ_SELF_NO_OBJECT).

**规则约束 / 可调参数**: (无)

### ast-style-esync-empty  —  `ESYNC_EMPTY`

- **规则类**: `org.plumb.style.style.EsyncEmptySyncCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty synchronized block (ESYNC_EMPTY_SYNC).

**规则约束 / 可调参数**: (无)

### ast-style-explicit-finalize  —  `EXPLICIT_FINALIZE_2`

- **规则类**: `org.plumb.style.style.ExplicitFinalizeCallCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-586
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-explicitinitialization  —  `EXPLICITINITIALIZATION`

- **规则类**: `org.plumb.style.style.ExplicitInitializationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> explicit initialization to default value is redundant.

**规则约束 / 可调参数**: (无)

### ast-style-expose-internal-representation  —  `EXPOSE_INTERNAL_REPRESENTATION`

- **规则类**: `org.plumb.style.style.ExposeInternalRepresentationCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-374
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Getter returns a mutable internal array or collection (defensive copy required).

**规则约束 / 可调参数**: (无)

### ast-style-expose-mutable-rep2  —  `EXPOSE_MUTABLE_REP2`

- **规则类**: `org.plumb.style.style.ExposeMutableRepStorageCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-374
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> externally provided mutable stored in field (EI_EXPOSE_REP2) : defensive copy needed.

**规则约束 / 可调参数**: (无)

### ast-style-extends-object  —  `EXTENDS_OBJECT`

- **规则类**: `org.plumb.style.style.ExtendsObjectCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Class explicitly extends Object, the declaration is redundant.

**规则约束 / 可调参数**: (无)

### ast-style-fallthrough  —  `FALLTHROUGH`

- **规则类**: `org.plumb.style.style.FallThroughCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> switch case fallthrough without // fallthrough.

**规则约束 / 可调参数**: (无)

### ast-style-fe-floating-point  —  `FE_FLOATING_POINT`

- **规则类**: `org.plumb.style.style.FeFloatingPointCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> floating-point == comparison (FE_FLOATING_POINT) : use Math.abs(a-b) < eps.

**规则约束 / 可调参数**: (无)

### ast-style-fieldselfassignment  —  `FIELDSELFASSIGNMENT`

- **规则类**: `org.plumb.style.style.FieldSelfAssignmentCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-filetabcharacter  —  `FILETABCHARACTER`

- **规则类**: `org.plumb.style.style.FileTabCharacterCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> File must not contain tab characters.

**规则约束 / 可调参数**: (无)

### ast-style-final-class  —  `FINAL_CLASS`

- **规则类**: `org.plumb.style.style.FinalClassCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Class with only private constructors should be `final`.

**规则约束 / 可调参数**: (无)

### ast-style-final-parameters  —  `FINAL_PARAMETERS`

- **规则类**: `org.plumb.style.style.FinalParametersCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> parameter never reassigned should be final.

**规则约束 / 可调参数**: (无)

### ast-style-fi-useless-block  —  `FI_USELESS_BLOCK`

- **规则类**: `org.plumb.style.style.FiUselessBlockCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-for-foreach  —  `FOR_FOREACH`

- **规则类**: `org.plumb.style.style.ForLoopCanBeForeachCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> for-loop can be replaced with for-each.

**规则约束 / 可调参数**: (无)

### ast-style-for-loop-false-condition  —  `FOR_LOOP_FALSE_CONDITION`

- **规则类**: `org.plumb.style.style.ForLoopFalseConditionCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> for loop has always-false condition.

**规则约束 / 可调参数**: (无)

### ast-style-for-var-count  —  `FOR_VAR_COUNT`

- **规则类**: `org.plumb.style.style.ForLoopVariableCountCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> for-loop declares more than one variable.

**规则约束 / 可调参数**: (无)

### ast-style-form-get-password  —  `FORM_GET_PASSWORD`

- **规则类**: `org.plumb.style.style.FormGetPasswordCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-598
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-he-hashcode-signature  —  `HE_HASHCODE_SIGNATURE`

- **规则类**: `org.plumb.style.style.HeSignatureDeclaresHashcodeOfObjectCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> hashCode(Object) : clashes with Object.hashCode (HE_SIGNATURE_DECLARES_HASHCODE_OF_OBJECT).

**规则约束 / 可调参数**: (无)

### ast-style-hiddenfield  —  `HIDDENFIELD`

- **规则类**: `org.plumb.style.style.HiddenFieldCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Local variable should not shadow field.

**规则约束 / 可调参数**: (无)

### ast-style-hide-utility-class-ctor  —  `HIDE_UTILITY_CLASS_CTOR`

- **规则类**: `org.plumb.style.style.HideUtilityClassConstructorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> utility class should hide public constructor.

**规则约束 / 可调参数**: (无)

### ast-style-icast-int-mult-long  —  `ICAST_INT_MULT_LONG`

- **规则类**: `org.plumb.style.style.IcastIntegerMultiplyCastToLongCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-190
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (long)(a * b) overflows at the multiplication (ICAST_INTEGER_MULTIPLY_CAST_TO_LONG).

**规则约束 / 可调参数**: (无)

### ast-style-ic-superclass-generic  —  `IC_SUPERCLASS_GENERIC`

- **规则类**: `org.plumb.style.style.IcSuperclassGenericCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> superclass uses sub-classed generic (IC_SUPERCLASS_GENERIC).

**规则约束 / 可调参数**: (无)

### ast-style-idempotentoperations  —  `IDEMPOTENTOPERATIONS`

- **规则类**: `org.plumb.style.style.IdempotentOperationsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> idempotent operations (e.g. x = x).

**规则约束 / 可调参数**: (无)

### ast-style-if-else-stmt  —  `IF_ELSE_STMT`

- **规则类**: `org.plumb.style.style.IfElseStmtCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> if/else if chain : consider switch.

**规则约束 / 可调参数**: (无)

### ast-style-iil-deref-readline  —  `IIL_DEREF_READLINE`

- **规则类**: `org.plumb.style.style.IilDereOfReadlineCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-476
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> readLine() result not null-checked (IIL_DEREF_OF_READLINE_VALUE).

**规则约束 / 可调参数**: (无)

### ast-style-il-container-sequencing  —  `IL_CONTAINER_SEQUENCING`

- **规则类**: `org.plumb.style.style.IlContainerSequencingCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-672
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> collection modified during iteration (IL_CONTAINER_SEQUENCING).

**规则约束 / 可调参数**: (无)

### ast-style-im-average  —  `IM_AVERAGE`

- **规则类**: `org.plumb.style.style.ImAverageComputationCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-190
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> "(ab) / 2 can overflow : use a(b - a) / 2 (IM_AVERAGE_COMPUTATION)".

**规则约束 / 可调参数**: (无)

### ast-style-im-bad-check-odd  —  `IM_BAD_CHECK_ODD`

- **规则类**: `org.plumb.style.style.ImBadCheckForOddCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-682
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> x % 2 == 1 fails for negative x (IM_BAD_CHECK_FOR_ODD) : use x % 2 != 0.

**规则约束 / 可调参数**: (无)

### ast-style-immutablefield  —  `IMMUTABLEFIELD`

- **规则类**: `org.plumb.style.style.ImmutableFieldCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-import-control  —  `IMPORT_CONTROL`

- **规则类**: `org.plumb.style.style.ImportControlCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Import is out of canonical order.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `staticImportsSeparate` | BOOLEAN | `true` | Static imports must precede non-static | |
> `sortWithinGroup` | BOOLEAN | `true` | Sort alphabetically within each group |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `staticImportsSeparate` | BOOLEAN | `true` | Static imports must precede non-static |
| `sortWithinGroup` | BOOLEAN | `true` | Sort alphabetically within each group |

### ast-style-import-same-package  —  `IMPORT_SAME_PACKAGE`

- **规则类**: `org.plumb.style.style.ImportFromSamePackageCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> import from same package : remove.

**规则约束 / 可调参数**: (无)

### ast-style-incompatiblemask  —  `INCOMPATIBLEMASK`

- **规则类**: `org.plumb.style.style.IncompatibleMaskCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> bit mask incompatible with the constant.

**规则约束 / 可调参数**: (无)

### ast-style-incomplete-class-declaration  —  `INCOMPLETE_CLASS_DECLARATION`

- **规则类**: `org.plumb.style.style.IncompleteClassDeclarationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class with only private constructors should be final.

**规则约束 / 可调参数**: (无)

### ast-style-incomplete-cleanup  —  `INCOMPLETE_CLEANUP`

- **规则类**: `org.plumb.style.style.IncompleteCleanupCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: RESOURCE
- **CWE**: CWE-459
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### dfa-style-incorrect-block-delimitation  —  `INCORRECT_BLOCK_DELIMITATION`

- **规则类**: `org.plumb.style.style.IncorrectBlockDelimitationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **CWE**: CWE-483
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-483: Incorrect Block Delimitation.
>
> Detects an `if` statement whose body is a single statement (no curly braces) and that
> has at least one following statement in the same enclosing block. The risk: the developer wrote
> the next statement intending it to be part of the `if`, but the missing braces mean it
> always runs.

**规则约束 / 可调参数**: (无)

### ast-style-indentation  —  `INDENTATION`

- **规则类**: `org.plumb.style.style.IndentationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Code indentation check.

**规则约束 / 可调参数**: (无)

### ast-style-infinite-loop  —  `INFINITE_LOOP`

- **规则类**: `org.plumb.style.style.InfiniteLoopCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-835
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Provably-infinite loop detector (CWE-835). Two strategies, both pure AST:
>
> <ol>
> <li><b>Constant-true condition</b>: `while (true)`, `for (; ; )`, `do { ... `
> while (true)}. Fires when the body has no `break` / `return` / `throw`.
> <li><b>Value-bounded modulo proof</b>: the loop condition is `var >= 0` (permanently true
> when `var` stays in `[0, N-1]`) AND the body (or for-step clause) contains
> `var = (var + k) % N;` (a modulo that textually keeps the induction variable in
> `[0, N-1]`).
> </ol>
>
> The rule lives in the style module because it is a pure AST analysis: it needs no
> cross-statement dataflow and no path conditions.

**规则约束 / 可调参数**: (无)

### ast-style-info-exposure-in-comment  —  `INFO_EXPOSURE_IN_COMMENT`

- **规则类**: `org.plumb.style.style.InfoExposureInCommentCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-615
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-info-exposure-querystring  —  `INFO_EXPOSURE_QUERYSTRING`

- **规则类**: `org.plumb.style.style.InfoExposureQueryStringCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-89
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> User-controlled input in a SQL query string (CWE-89).
>
> Heuristic: flag any `StringLiteralExpr` whose name suggests a query (`"sql"`,
> `"query"`, `"hql"`, `"jpql"`, `"select ..."`, `"from "`) and whose
> enclosing context is a controller endpoint. We use the simple-name heuristic because AST-only
> analysis can't see runtime data flow.

**规则约束 / 可调参数**: (无)

### ast-style-inner-assignment  —  `INNER_ASSIGNMENT`

- **规则类**: `org.plumb.style.style.InnerAssignmentCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> assignment inside expression (e.g. while ((x = next()) != 0)).

**规则约束 / 可调参数**: (无)

### ast-style-insecure-temp-file-perms-DEDUPED  —  `INSECURE_TEMP_FILE_PERMS_DEDUPED`

- **规则类**: `org.plumb.style.style.InsecureTempFilePermsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: SECURITY
- **CWE**: CWE-378
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> @deprecated superseded by `security/InsecureTempFilePermsCheck`; retained as a no-op for
> FQN compatibility only.

**规则约束 / 可调参数**: (无)

### ast-style-instantiationtogetclass  —  `INSTANTIATIONTOGETCLASS`

- **规则类**: `org.plumb.style.style.InstantiationToGetClassCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> do not instantiate just to call getClass().

**规则约束 / 可调参数**: (无)

### ast-style-insufficient-session-expiration  —  `INSUFFICIENT_SESSION_EXPIRATION`

- **规则类**: `org.plumb.style.style.InsufficientSessionExpirationCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-613
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-613 (Insufficient Session Expiration).
>
> Fires when `HttpSession.setMaxInactiveInterval(int)` is called with a value `<=
> 0`.

**规则约束 / 可调参数**: (无)

### ast-style-int-division-cast-to-double  —  `INT_DIVISION_CAST_TO_DOUBLE`

- **规则类**: `org.plumb.style.style.IntDivisionCastToDoubleCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Integer division cast to double (precision loss; cast one operand instead).

**规则约束 / 可调参数**: (无)

### ast-style-io-resource-both  —  `IO_RESOURCE_BOTH`

- **规则类**: `org.plumb.style.style.IoResourceBothCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-668
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> resource used as both input and output (IO_RESOURCE_USED_AS_BOTH_INPUT_AND_OUTPUT).

**规则约束 / 可调参数**: (无)

### ast-style-iterator-idiomatic  —  `ITERATOR_IDIOMATIC`

- **规则类**: `org.plumb.style.style.IteratorIdiomaticCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Index-counter loop should be a for-each loop.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `ignoreLoopsWithIndexUsedOutside` | BOOLEAN | `true` | Skip loops where i is read in non-indexing
> ways | | `arrayLengthTolerance` | BOOLEAN | `true` | Accept arr.length as a compare operand |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreLoopsWithIndexUsedOutside` | BOOLEAN | `true` | Skip loops where i is read in non-indexing ways |
| `arrayLengthTolerance` | BOOLEAN | `true` | Accept arr.length as a compare operand |

### ast-style-javadoc-method  —  `JAVADOC_METHOD`

- **规则类**: `org.plumb.style.style.JavadocMethodCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: JavadocMethod

**规则说明**

> public method should have a Javadoc comment.

**规则约束 / 可调参数**: (无)

### ast-style-javadocparagraph  —  `JAVADOCPARAGRAPH`

- **规则类**: `org.plumb.style.style.JavadocParagraphCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Javadoc paragraphs should be separated by single
>
> .

**规则约束 / 可调参数**: (无)

### ast-style-javadoctype  —  `JAVADOCTYPE`

- **规则类**: `org.plumb.style.style.JavadocTypeCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> public class missing Javadoc.

**规则约束 / 可调参数**: (无)

### ast-style-junit-message  —  `JUNIT_MESSAGE`

- **规则类**: `org.plumb.style.style.JUnitAssertionsShouldIncludeMessageCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> JUnit assertion should include a failure message.

**规则约束 / 可调参数**: (无)

### ast-style-junit-no-assert  —  `JUNIT_NO_ASSERT`

- **规则类**: `org.plumb.style.style.JUnitTestsShouldIncludeAssertCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> @Test method has no assertion : it always passes.

**规则约束 / 可调参数**: (无)

### ast-style-lambda-body-length  —  `LAMBDA_BODY_LENGTH`

- **规则类**: `org.plumb.style.style.LambdaBodyLengthCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> lambda body has too many statements (, default 3).

**规则约束 / 可调参数**: (无)

### ast-style-lambda-param-shadowing  —  `LAMBDA_PARAM_SHADOWING`

- **规则类**: `org.plumb.style.style.LambdaParameterShadowingCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-left-curly  —  `LEFT_CURLY`

- **规则类**: `org.plumb.style.style.LeftCurlyCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: LeftCurly

**规则说明**

> left curly brace should be on the same line as declaration.

**规则约束 / 可调参数**: (无)

### ast-style-line-length  —  `LINE_LENGTH`

- **规则类**: `org.plumb.style.style.LineLengthCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: LineLength

**规则说明**

> Line is too long.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `max` | INT
> | `100` | Maximum allowed line length | | `ignorePattern` | STRING | `` | Regex of lines to
> ignore (after trim) | | `ignoreImports` | BOOLEAN | `false` | Skip import statements | |
> `ignoreComments` | BOOLEAN | `true` | Skip comment lines |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `max` | INT | `100` | Maximum allowed line length |
| `ignorePattern` | STRING | `` | Regex of lines to ignore (after trim) |
| `ignoreImports` | BOOLEAN | `false` | Skip import statements |
| `ignoreComments` | BOOLEAN | `true` | Skip comment lines |

### ast-style-literal-boolean-condition  —  `LITERAL_BOOLEAN_CONDITION`

- **规则类**: `org.plumb.style.style.LiteralBooleanConditionCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-570, CWE-571
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-literals-first  —  `LITERALS_FIRST`

- **规则类**: `org.plumb.style.style.LiteralsFirstInComparisonsCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-476
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> literal should be first in equals() to avoid NPE.

**规则约束 / 可调参数**: (无)

### ast-style-locale-dependent-case-conversion  —  `LOCALE_DEPENDENT_CASE_CONVERSION`

- **规则类**: `org.plumb.style.style.LocaleDependentCaseConversionCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: I18N
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> String.toLowerCase/toUpperCase without Locale (locale-dependent).

**规则约束 / 可调参数**: (无)

### ast-style-locale-root  —  `LOCALE_ROOT`

- **规则类**: `org.plumb.style.style.LocaleRootCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **CWE**: CWE-758
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> locale-sensitive API without explicit Locale.

**规则约束 / 可调参数**: (无)

### ast-style-loggers-declaration  —  `LOGGERS_DECLARATION`

- **规则类**: `org.plumb.style.style.LoggersDeclarationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> logger should be private static final.

**规则约束 / 可调参数**: (无)

### ast-style-logger-final  —  `LOGGER_FINAL`

- **规则类**: `org.plumb.style.style.LoggerShouldBeFinalCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Logger field should be static final.

**规则约束 / 可调参数**: (无)

### ast-style-log-info-leak  —  `LOG_INFO_LEAK`

- **规则类**: `org.plumb.style.style.LogInfoLeakCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-533
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-loop-multi-breaks  —  `LOOP_MULTI_BREAKS`

- **规则类**: `org.plumb.style.style.LoopsWithMultipleBreaksCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> loop has multiple break/continue : refactor.

**规则约束 / 可调参数**: (无)

### ast-style-magic-number  —  `MAGIC_NUMBER`

- **规则类**: `org.plumb.style.style.MagicNumberCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: MagicNumber

**规则说明**

> Replace this magic number with a named constant.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `ignoreNumbers` | STRING | `-1,0,1,2` | Comma-separated literal values that are never reported |
> | `ignoreInForLoop` | BOOLEAN | `true` | Skip literals in for-loop header / update |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreNumbers` | STRING | `-1,0,1,2` | Comma-separated literal values that are never reported |
| `ignoreInForLoop` | BOOLEAN | `true` | Skip literals in for-loop header / update |

### ast-style-malformedsync  —  `MALFORMEDSYNC`

- **规则类**: `org.plumb.style.style.MalformedSyncCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Malformed synchronization.

**规则约束 / 可调参数**: (无)

### ast-style-math-op-literal  —  `MATH_OP_LITERAL`

- **规则类**: `org.plumb.style.style.MathematicalOperationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> math on literal values : pre-compute and use a constant.

**规则约束 / 可调参数**: (无)

### ast-style-mc-returns-void  —  `MC_RETURNS_VOID`

- **规则类**: `org.plumb.style.style.MCReturnsVoidCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Method returns void but has getter-style name.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `prefix` |
> STRING | `get` | Getter prefix to check | | `checkAllPublic` | BOOLEAN | `true` | Apply check to
> all public methods with the prefix |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `prefix` | STRING | `get` | Getter prefix to check |
| `checkAllPublic` | BOOLEAN | `true` | Apply check to all public methods with the prefix |

### ast-style-method-arg-could-be-final  —  `METHOD_ARG_COULD_BE_FINAL`

- **规则类**: `org.plumb.style.style.MethodArgumentCouldBeFinalCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method argument could be declared final.

**规则约束 / 可调参数**: (无)

### ast-style-ctor-method  —  `CTOR_METHOD`

- **规则类**: `org.plumb.style.style.MethodCallsFromConstructorCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-665
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> constructor calls overridable method.

**规则约束 / 可调参数**: (无)

### ast-style-method-too-complex  —  `METHOD_TOO_COMPLEX`

- **规则类**: `org.plumb.style.style.MethodTooComplexCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-method-too-long  —  `METHOD_TOO_LONG`

- **规则类**: `org.plumb.style.style.MethodTooLongCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method is too long (limit 50 statements).

**规则约束 / 可调参数**: (无)

### ast-style-mf-class-masks-field  —  `MF_CLASS_MASKS_FIELD`

- **规则类**: `org.plumb.style.style.MfClassMasksFieldCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> a nested class masks an enclosing field of the same name (MF_CLASS_MASKS_FIELD).

**规则约束 / 可调参数**: (无)

### ast-style-mf-method-masks-field  —  `MF_METHOD_MASKS_FIELD`

- **规则类**: `org.plumb.style.style.MfMethodMasksFieldCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> a method masks an enclosing field of the same name (MF_METHOD_MASKS_FIELD).

**规则约束 / 可调参数**: (无)

### ast-style-missing-break  —  `MISSING_BREAK`

- **规则类**: `org.plumb.style.style.MissingBreakCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: CORRECTNESS
- **CWE**: CWE-484
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Switch case falls through to the next case without break/return/throw/continue.

**规则约束 / 可调参数**: (无)

### ast-style-missing-crypto-step  —  `MISSING_CRYPTO_STEP`

- **规则类**: `org.plumb.style.style.MissingCryptoStepCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-325
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-325 (Missing Required Cryptographic Step).
>
> Detects code that uses `Cipher` (encryption) without a corresponding `Mac`
> (message authentication) step. The encryption-only scheme omits the message authentication that
> CWE-325 requires.
>
> The analysis is an AST walk of every {@link MethodCallExpr}: any call to `Cipher.getInstance / init / doFinal / update` without any sibling `Mac.*` call in the same
> file fires the finding on the first cipher call line.

**规则约束 / 可调参数**: (无)

### dfa-style-missing-default-case  —  `MISSING_DEFAULT_CASE`

- **规则类**: `org.plumb.style.style.MissingDefaultCaseCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **CWE**: CWE-478
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-478: Missing Default Case in Switch Statement.
>
> Detects a `switch` statement that has at least one `case` entry but no `default` entry. Without a default, the switch silently does nothing for values outside the listed
> cases.

**规则约束 / 可调参数**: (无)

### ast-style-missing-deprecated  —  `MISSING_DEPRECATED`

- **规则类**: `org.plumb.style.style.MissingDeprecatedCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-477
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Member of a @Deprecated class should also be @Deprecated.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `ignorePrivateMethods` | BOOLEAN | `true` | Skip private methods of @Deprecated classes | |
> `ignoreOverriddenMethods` | BOOLEAN | `true` | Skip methods that are overrides |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignorePrivateMethods` | BOOLEAN | `true` | Skip private methods of @Deprecated classes |
| `ignoreOverriddenMethods` | BOOLEAN | `true` | Skip methods that are overrides |

### ast-style-missingjavadocmethod  —  `MISSINGJAVADOCMETHOD`

- **规则类**: `org.plumb.style.style.MissingJavadocMethodCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Missing Javadoc on public method.

**规则约束 / 可调参数**: (无)

### ast-style-missingjavadocpackage  —  `MISSINGJAVADOCPACKAGE`

- **规则类**: `org.plumb.style.style.MissingJavadocPackageCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Missing package-info.java or Javadoc on package.

**规则约束 / 可调参数**: (无)

### ast-style-missingjavadoctype  —  `MISSINGJAVADOCTYPE`

- **规则类**: `org.plumb.style.style.MissingJavadocTypeCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Missing Javadoc on public type.

**规则约束 / 可调参数**: (无)

### ast-style-missing-password-masking  —  `MISSING_PASSWORD_MASKING`

- **规则类**: `org.plumb.style.style.MissingPasswordMaskingCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-549
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-missing-serial-version-uid  —  `MISSING_SERIAL_VERSION_UID`

- **规则类**: `org.plumb.style.style.MissingSerialVersionUIDCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-609
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Class implements Serializable but is missing serialVersionUID.

**规则约束 / 可调参数**: (无)

### ast-style-missing-static-in-non-instantiable  —  `MISSING_STATIC_IN_NON_INSTANTIABLE`

- **规则类**: `org.plumb.style.style.MissingStaticMethodInNonInstantiableClassCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> non-instantiable class should have a static method or field.

**规则约束 / 可调参数**: (无)

### ast-style-missing-switch-default  —  `MISSING_SWITCH_DEFAULT`

- **规则类**: `org.plumb.style.style.MissingSwitchDefaultCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> switch should have a default case.

**规则约束 / 可调参数**: (无)

### ast-style-modifier-order  —  `MODIFIER_ORDER`

- **规则类**: `org.plumb.style.style.ModifierOrderCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-more-than-one-logger  —  `MORE_THAN_ONE_LOGGER`

- **规则类**: `org.plumb.style.style.MoreThanOneLoggerCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class has more than one Logger field.

**规则约束 / 可调参数**: (无)

### ast-style-multiple-binds-same-port  —  `MULTIPLE_BINDS_SAME_PORT`

- **规则类**: `org.plumb.style.style.MultipleBindsSamePortCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-605
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-multiple-var-decls  —  `MULTIPLE_VAR_DECLS`

- **规则类**: `org.plumb.style.style.MultipleVariableDeclarationsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> multiple variables in one declaration.

**规则约束 / 可调参数**: (无)

### ast-style-mutable-enum-field  —  `MUTABLE_ENUM_FIELD`

- **规则类**: `org.plumb.style.style.MutableEnumFieldCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> enum field is not final (ME_MUTABLE_ENUM_FIELD) : globally mutable state.

**规则约束 / 可调参数**: (无)

### ast-style-mutable-static-should-be-final  —  `MUTABLE_STATIC_SHOULD_BE_FINAL`

- **规则类**: `org.plumb.style.style.MutableStaticShouldBeFinalCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: MALICIOUS_CODE
- **CWE**: CWE-500
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Mutable static field should be final (mutable global state).

**规则约束 / 可调参数**: (无)

### ast-style-nested-block  —  `NESTED_BLOCK`

- **规则类**: `org.plumb.style.style.NestedBlockCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> nested block : extract to a method.

**规则约束 / 可调参数**: (无)

### ast-style-nested-for-depth  —  `NESTED_FOR_DEPTH`

- **规则类**: `org.plumb.style.style.NestedForDepthCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-nested-if-depth  —  `NESTED_IF_DEPTH`

- **规则类**: `org.plumb.style.style.NestedIfDepthCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-nested-switch  —  `NESTED_SWITCH`

- **规则类**: `org.plumb.style.style.NestedSwitchCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> switch nested inside another switch.

**规则约束 / 可调参数**: (无)

### ast-style-newline-at-eof  —  `NEWLINE_AT_EOF`

- **规则类**: `org.plumb.style.style.NewlineAtEndOfFileCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> File should end with a newline.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `lineSeparator` | STRING | `\n` | Required line separator at end of file |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `lineSeparator` | STRING | `\n` | Required line separator at end of file |

### ast-style-nm-confusing  —  `NM_CONFUSING`

- **规则类**: `org.plumb.style.style.NmConfusingInspectionCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> confusing class / method name (NM_CONFUSING_INSPECTION).

**规则约束 / 可调参数**: (无)

### ast-style-nm-same-name-super  —  `NM_SAME_NAME_SUPER`

- **规则类**: `org.plumb.style.style.NmSameSimpleNameAsSuperCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class shares a simple name with its superclass (NM_SAME_SIMPLE_NAME_AS_SUPERCLASS).

**规则约束 / 可调参数**: (无)

### ast-style-nocodeinfile  —  `NOCODEINFILE`

- **规则类**: `org.plumb.style.style.NoCodeInFileCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> File should not contain code.

**规则约束 / 可调参数**: (无)

### ast-style-no-effect-statement  —  `NO_EFFECT_STATEMENT`

- **规则类**: `org.plumb.style.style.NoEffectStatementCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **CWE**: CWE-398, CWE-398
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-noenumtrailingcomma  —  `NOENUMTRAILINGCOMMA`

- **规则类**: `org.plumb.style.style.NoEnumTrailingCommaCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Enum should not have trailing comma.

**规则约束 / 可调参数**: (无)

### ast-style-no-log-statement  —  `NO_LOG_STATEMENT`

- **规则类**: `org.plumb.style.style.NoLogStatementCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> log call may be at a disabled level.

**规则约束 / 可调参数**: (无)

### ast-style-no-long-literal  —  `NO_LONG_LITERAL`

- **规则类**: `org.plumb.style.style.NoLongLiteralCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> long literal should use upper-case L suffix.

**规则约束 / 可调参数**: (无)

### ast-style-non-case-label  —  `NON_CASE_LABEL`

- **规则类**: `org.plumb.style.style.NonCaseLabelInSwitchStatementCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> labeled statement in switch : use case label.

**规则约束 / 可调参数**: (无)

### ast-style-non-serializable-class  —  `NON_SERIALIZABLE_CLASS`

- **规则类**: `org.plumb.style.style.NonSerializableClassInSerializableContextCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-502
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Non-Serializable field in Serializable class.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `ignoreTransient` | BOOLEAN | `true` | Skip transient fields | | `ignoreStatic` | BOOLEAN |
> `true` | Skip static fields | | `ignorePrimitives` | BOOLEAN | `true` | Skip primitive and
> common-JDK serializable types |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreTransient` | BOOLEAN | `true` | Skip transient fields |
| `ignoreStatic` | BOOLEAN | `true` | Skip static fields |
| `ignorePrimitives` | BOOLEAN | `true` | Skip primitive and common-JDK serializable types |

### ast-style-se-bad-field  —  `SE_BAD_FIELD`

- **规则类**: `org.plumb.style.style.NonSerializableFieldCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-684
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Serializable class has a non-Serializable field (SE_BAD_FIELD).

**规则约束 / 可调参数**: (无)

### ast-style-non-serializable-in-session  —  `NON_SERIALIZABLE_IN_SESSION`

- **规则类**: `org.plumb.style.style.NonSerializableInSessionCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-579
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-579 — store a non-{@link java.io.Serializable} object in an `HttpSession`.
>
> Detects calls of the shape `session.setAttribute(name, value)` where:
>
> <ol>
> <li>The receiver's resolved type descends from `javax.servlet.http.HttpSession`. Falls
> through to a name-heuristic when symbol resolution fails (e.g. servlet-api.jar not on the
> classpath).
> <li>The value argument's type either (a) explicitly constructs `new Hashtable()`, `new HashMap()`, `new ArrayList()` (well-known non-`Serializable` containers),
> or (b) resolves to a reference type that does NOT implement `java.io.Serializable`
> and is NOT a primitive.
> </ol>
>
> The receiver type is resolved with the symbol solver, so a custom non-`Serializable`
> class is caught as well as the well-known container literals.

**规则约束 / 可调参数**: (无)

### ast-style-non-static-initializer  —  `NON_STATIC_INITIALIZER`

- **规则类**: `org.plumb.style.style.NonStaticInitializerCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Non-static initializer block : move into a constructor.

**规则约束 / 可调参数**: (无)

### ast-style-no-package  —  `NO_PACKAGE`

- **规则类**: `org.plumb.style.style.NoPackageCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> top-level class has no package declaration.

**规则约束 / 可调参数**: (无)

### ast-style-notify  —  `NOTIFY`

- **规则类**: `org.plumb.style.style.NotifyCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Notify rather than notifyAll.

**规则约束 / 可调参数**: (无)

### ast-style-np-sync-unshared  —  `NP_SYNC_UNSHARED`

- **规则类**: `org.plumb.style.style.NpSyncOnUnsharedVarCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-820
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> synchronized on a local variable (NP_SYNC_INVOKED_ON_UNSHARED_VARIABLE).

**规则约束 / 可调参数**: (无)

### ast-style-null-assignment  —  `NULL_ASSIGNMENT`

- **规则类**: `org.plumb.style.style.NullAssignmentCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> assignment of null outside declaration.

**规则约束 / 可调参数**: (无)

### ast-style-null-deref-from-return  —  `NULL_DEREF_FROM_RETURN`

- **规则类**: `org.plumb.style.style.NullDerefFromReturnCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-690
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-690 (NULL Pointer Dereference From Return).

**规则约束 / 可调参数**: (无)

### dfa-style-numeric-conversion  —  `NUMERIC_CONVERSION`

- **规则类**: `org.plumb.style.style.NumericConversionCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-681
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-681: Incorrect Conversion Between Numeric Types.
>
> Detects a narrowing primitive cast (e.g. `(int)double`, `(float)double`, `(short)int`, `(byte)int`).

**规则约束 / 可调参数**: (无)

### ast-style-cwe580-clone-without-super  —  `CWE580_CLONE_WITHOUT_SUPER`

- **规则类**: `org.plumb.style.style.ObjectModelCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-580
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-580 (clone() without super.clone()).
>
> Detects `public Object clone() { ... return new ...; `} where the body returns a `new SomeClass()` instead of `super.clone()`.

**规则约束 / 可调参数**: (无)

### ast-style-objectsequality  —  `OBJECTSEQUALITY`

- **规则类**: `org.plumb.style.style.ObjectsEqualityCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Objects equality.

**规则约束 / 可调参数**: (无)

### dfa-style-off-by-one-loop  —  `OFF_BY_ONE_LOOP`

- **规则类**: `org.plumb.style.style.OffByOneLoopCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-193
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-193: Off-by-one Error.
>
> Detects a `<=` comparison against a `.length` or `.size()` expression in a
> loop continuation condition.

**规则约束 / 可调参数**: (无)

### ast-style-one-statement-per-line  —  `ONE_STATEMENT_PER_LINE`

- **规则类**: `org.plumb.style.style.OneStatementPerLineCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> multiple statements on the same line.
>
> Pattern D: per-method / per-constructor visit. The body is small (a single block), so we count
> statements by line in one local pass. Replaces visit(CU) + 2 findAll.

**规则约束 / 可调参数**: (无)

### ast-style-only-one-return  —  `ONLY_ONE_RETURN`

- **规则类**: `org.plumb.style.style.OnlyOneReturnCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method should have only one return statement.

**规则约束 / 可调参数**: (无)

### ast-style-only-one-return-strict  —  `ONLY_ONE_RETURN_STRICT`

- **规则类**: `org.plumb.style.style.OnlyOneReturnPerMethodCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method should have only one return (strict variant).

**规则约束 / 可调参数**: (无)

### ast-style-operatorwrap  —  `OPERATORWRAP`

- **规则类**: `org.plumb.style.style.OperatorWrapCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Operator wrap placement.

**规则约束 / 可调参数**: (无)

### ast-style-optional-get  —  `OPTIONAL_GET`

- **规则类**: `org.plumb.style.style.OptionalGetCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-476
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Optional.get() without isPresent() : use orElse/orElseThrow.

**规则约束 / 可调参数**: (无)

### ast-style-optionalreturn  —  `OPTIONALRETURN`

- **规则类**: `org.plumb.style.style.OptionalReturnCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Optional return check.

**规则约束 / 可调参数**: (无)

### ast-style-outertypenumber  —  `OUTERTYPENUMBER`

- **规则类**: `org.plumb.style.style.OuterTypeNumberCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> outer type number (only one outer type per file).

**规则约束 / 可调参数**: (无)

### ast-style-overload-methods-order  —  `OVERLOAD_METHODS_ORDER`

- **规则类**: `org.plumb.style.style.OverloadMethodsDeclarationOrderCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Overloaded methods should be declared together.

**规则约束 / 可调参数**: (无)

### ast-style-overly-broad-catch  —  `OVERLY_BROAD_CATCH`

- **规则类**: `org.plumb.style.style.OverlyBroadCatchCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **CWE**: CWE-396
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-396: Catch of Generic Exception.

**规则约束 / 可调参数**: (无)

### ast-style-overridingmethodmustinvokesuper-sb  —  `OVERRIDINGMETHODMUSTINVOKESUPER_SB`

- **规则类**: `org.plumb.style.style.OverridingMethodMustInvokeSuperCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Overriding method must invoke super.

**规则约束 / 可调参数**: (无)

### ast-style-package-annotation  —  `PACKAGE_ANNOTATION`

- **规则类**: `org.plumb.style.style.PackageAnnotationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> package-info.java should declare a package annotation.

**规则约束 / 可调参数**: (无)

### ast-style-package-declaration  —  `PACKAGE_DECLARATION`

- **规则类**: `org.plumb.style.style.PackageDeclarationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> file should have a package declaration.

**规则约束 / 可调参数**: (无)

### ast-style-package-info  —  `PACKAGE_INFO`

- **规则类**: `org.plumb.style.style.PackageInfoCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> missing package-info.java.

**规则约束 / 可调参数**: (无)

### ast-style-param-hides-field  —  `PARAM_HIDES_FIELD`

- **规则类**: `org.plumb.style.style.ParameterHidesFieldCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-1006
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method parameter shadows a class field.

**规则约束 / 可调参数**: (无)

### ast-style-parenpad  —  `PARENPAD`

- **规则类**: `org.plumb.style.style.ParenPadCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Parentheses padding.

**规则约束 / 可调参数**: (无)

### ast-style-poor-code-quality  —  `POOR_CODE_QUALITY`

- **规则类**: `org.plumb.style.style.PoorCodeQualityCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **CWE**: CWE-398
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-398 (Indicator of Poor Code Quality) ? file-wide check for empty blocks, cases, methods.

**规则约束 / 可调参数**: (无)

### ast-style-predictable-salt  —  `PREDICTABLE_SALT`

- **规则类**: `org.plumb.style.style.PredictableSaltCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: SECURITY
- **CWE**: CWE-760
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-premature-declaration  —  `PREMATURE_DECLARATION`

- **规则类**: `org.plumb.style.style.PrematureDeclarationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> variable declared without initializer.

**规则约束 / 可调参数**: (无)

### ast-style-preserve-stack  —  `PRESERVE_STACK`

- **规则类**: `org.plumb.style.style.PreserveStackTraceCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-209
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> throw new Exception() loses the original stack trace.

**规则约束 / 可调参数**: (无)

### ast-style-printf-fail  —  `PRINTF_FAIL`

- **规则类**: `org.plumb.style.style.PrintfFailCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> System.out.printf/System.err.printf.

**规则约束 / 可调参数**: (无)

### ast-style-printf-format  —  `PRINTF_FORMAT`

- **规则类**: `org.plumb.style.style.PrintfFormatCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> String.format / printf : verify format and arg count.

**规则约束 / 可调参数**: (无)

### ast-style-print-stack-trace  —  `PRINT_STACK_TRACE`

- **规则类**: `org.plumb.style.style.PrintStackTraceCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-209
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Use a logger instead of Throwable.printStackTrace().

**规则约束 / 可调参数**: (无)

### ast-style-proper-logger  —  `PROPER_LOGGER`

- **规则类**: `org.plumb.style.style.ProperLoggerCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Logger field should be named LOG/LOGGER/log.

**规则约束 / 可调参数**: (无)

### ast-style-publicfieldaccessedinsynchronizedcontext-sb  —  `PUBLICFIELDACCESSEDINSYNCHRONIZEDCONTEXT_SB`

- **规则类**: `org.plumb.style.style.PublicFieldAccessedInSynchronizedContextCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Public field accessed in synchronized context.

**规则约束 / 可调参数**: (无)

### ast-style-publicmethodspublicfield-sb  —  `PUBLICMETHODSPUBLICFIELD_SB`

- **规则类**: `org.plumb.style.style.PublicMethodsPublicFieldCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Public method exposes public field.

**规则约束 / 可调参数**: (无)

### ast-style-publicprotectedfieldinenum  —  `PUBLICPROTECTEDFIELDINENUM`

- **规则类**: `org.plumb.style.style.PublicProtectedFieldInEnumCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> enum field should be private.

**规则约束 / 可调参数**: (无)

### ast-style-default-package-2  —  `DEFAULT_PACKAGE_2`

- **规则类**: `org.plumb.style.style.PublicStaticFieldAccessedInSubclassCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class is in the default package.

**规则约束 / 可调参数**: (无)

### ast-style-public-static-mutable-field  —  `PUBLIC_STATIC_MUTABLE_FIELD`

- **规则类**: `org.plumb.style.style.PublicStaticMutableFieldCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-668
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> "public static field of mutable type : use finaldefensive copy".

**规则约束 / 可调参数**: (无)

### ast-style-pz-reuse-entry  —  `PZ_REUSE_ENTRY`

- **规则类**: `org.plumb.style.style.PzReuseEntryCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-672
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> map entry object reused across iterations (PZ_DONT_REUSE_ENTRY_OBJECTS_IN_ITERATORS).

**规则约束 / 可调参数**: (无)

### ast-style-qba-questionable-boolean  —  `QBA_QUESTIONABLE_BOOLEAN`

- **规则类**: `org.plumb.style.style.QbaQuestionableBooleanCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> questionable boolean assignment (QBA_QUESTIONABLE_BOOLEAN_ASSIGNMENT).

**规则约束 / 可调参数**: (无)

### ast-style-questionablebooleanassignment  —  `QUESTIONABLEBOOLEANASSIGNMENT`

- **规则类**: `org.plumb.style.style.QuestionableBooleanAssignmentCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Questionable boolean assignment.

**规则约束 / 可调参数**: (无)

### ast-style-random-iv-cbc  —  `RANDOM_IV_CBC`

- **规则类**: `org.plumb.style.style.RandomIvCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-329
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### bu-0507-random-used-once  —  `RANDOM_USED_ONCE`

- **规则类**: `org.plumb.style.style.RandomUsedOnceCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> new Random() used only once.

**规则约束 / 可调参数**: (无)

### ast-style-reachable-assertion  —  `REACHABLE_ASSERTION`

- **规则类**: `org.plumb.style.style.ReachableAssertionCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> test method should include at least one assertion.

**规则约束 / 可调参数**: (无)

### ast-style-redirect-without-exit  —  `REDIRECT_WITHOUT_EXIT`

- **规则类**: `org.plumb.style.style.RedirectWithoutExitCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-698
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-reduce-visibility  —  `REDUCE_VISIBILITY`

- **规则类**: `org.plumb.style.style.ReduceVisibilityCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> field visibility can be reduced to private.

**规则约束 / 可调参数**: (无)

### ast-style-redundant-init  —  `REDUNDANT_INIT`

- **规则类**: `org.plumb.style.style.RedundantFieldInitializerCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> redundant field initializer (already default value).

**规则约束 / 可调参数**: (无)

### ast-style-redundant-import  —  `REDUNDANT_IMPORT`

- **规则类**: `org.plumb.style.style.RedundantImportCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: RedundantImport

**规则说明**

> Import is redundant (duplicates an earlier import).

**规则约束 / 可调参数**: (无)

### ast-style-redundant-method  —  `REDUNDANT_METHOD`

- **规则类**: `org.plumb.style.style.RedundantMethodCallCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> redundant method : just delegates.

**规则约束 / 可调参数**: (无)

### ast-style-regex-group  —  `REGEX_GROUP`

- **规则类**: `org.plumb.style.style.RegexGroupCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> regex without groups : use String.matches.

**规则约束 / 可调参数**: (无)

### ast-style-relational-symmetry  —  `RELATIONAL_SYMMETRY`

- **规则类**: `org.plumb.style.style.RelationalOperatorSymmetryCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> relational operator asymmetry.

**规则约束 / 可调参数**: (无)

### ast-style-requirethis  —  `REQUIRETHIS`

- **规则类**: `org.plumb.style.style.RequireThisCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Reference to instance member should be qualified with 'this'.

**规则约束 / 可调参数**: (无)

### ast-style-ignored-result  —  `IGNORED_RESULT`

- **规则类**: `org.plumb.style.style.ResultOfMethodCallIgnoredCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-252
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> result of comparison-style method call is ignored.

**规则约束 / 可调参数**: (无)

### ast-style-return-bool  —  `RETURN_BOOL`

- **规则类**: `org.plumb.style.style.ReturnBooleanLiteralCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method always returns true/false : replace with constant.

**规则约束 / 可调参数**: (无)

### ast-style-return-from-finally  —  `RETURN_FROM_FINALLY`

- **规则类**: `org.plumb.style.style.ReturnFromFinallyBlockCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-584
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> return in finally block silently swallows exceptions.

**规则约束 / 可调参数**: (无)

### ast-style-return-in-finally  —  `RETURN_IN_FINALLY`

- **规则类**: `org.plumb.style.style.ReturnInFinallyBlockCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-584
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-rightcurly  —  `RIGHTCURLY`

- **规则类**: `org.plumb.style.style.RightCurlyCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: RightCurly

**规则说明**

> Right curly brace placement.

**规则约束 / 可调参数**: (无)

### ast-style-rv-remove-repeat-calls  —  `RV_REMOVE_REPEAT_CALLS`

- **规则类**: `org.plumb.style.style.RvRemoveRepeatCallsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> same no-arg method called twice : store in a local (RV_REMOVE_REPEAT_METHOD_CALLS).

**规则约束 / 可调参数**: (无)

### ast-style-rv-return-ignored  —  `RV_RETURN_IGNORED`

- **规则类**: `org.plumb.style.style.RvReturnValueIgnoredCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-252
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> return value of this method is ignored (RV_RETURN_VALUE_IGNORED_BAD_PRACTICE).

**规则约束 / 可调参数**: (无)

### ast-style-seeded-random  —  `SEEDED_RANDOM`

- **规则类**: `org.plumb.style.style.SeededRandomCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-336
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-self-assignment  —  `SELF_ASSIGNMENT`

- **规则类**: `org.plumb.style.style.SelfAssignmentCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-682
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> variable should not be self-assigned (no-op, likely copy-paste bug).

**规则约束 / 可调参数**: (无)

### ast-style-separatorwrap  —  `SEPARATORWRAP`

- **规则类**: `org.plumb.style.style.SeparatorWrapCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Separator wrap placement.

**规则约束 / 可调参数**: (无)

### ast-style-serializable-subclass  —  `SERIALIZABLE_SUBCLASS`

- **规则类**: `org.plumb.style.style.SerializableSubclassCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Serializable parent without Serializable subclass (BSK_SERIALIZABLE_SUBCLASS).

**规则约束 / 可调参数**: (无)

### ast-style-serializablesuperconstructor  —  `SERIALIZABLESUPERCONSTRUCTOR`

- **规则类**: `org.plumb.style.style.SerializableSuperConstructorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Serializable super constructor.

**规则约束 / 可调参数**: (无)

### ast-style-bit-signed-check  —  `BIT_SIGNED_CHECK`

- **规则类**: `org.plumb.style.style.SignedBitCheckCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-682
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> bitwise check on a signed value : fragile sign extension (BIT_SIGNED_CHECK).

**规则约束 / 可调参数**: (无)

### ast-style-signofaddition  —  `SIGNOFADDITION`

- **规则类**: `org.plumb.style.style.SignOfAdditionCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Sign of addition.

**规则约束 / 可调参数**: (无)

### ast-style-si-instance-before-final  —  `SI_INSTANCE_BEFORE_FINAL`

- **规则类**: `org.plumb.style.style.SiInstanceBeforeFinalCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-457
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> instance init reads static final before assignment (SI_INSTANCE_BEFORE_FINALS_ASSIGNED).

**规则约束 / 可调参数**: (无)

### ast-style-simplifiedateformat-locale  —  `SIMPLIFIEDATEFORMAT_LOCALE`

- **规则类**: `org.plumb.style.style.SimpleDateFormatNeedsLocaleCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-18
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> new SimpleDateFormat() needs explicit Locale.

**规则约束 / 可调参数**: (无)

### ast-style-simpledateformatwithoutlocale  —  `SIMPLEDATEFORMATWITHOUTLOCALE`

- **规则类**: `org.plumb.style.style.SimpleDateFormatWithoutLocaleCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> SimpleDateFormat should use Locale-specific constructor.

**规则约束 / 可调参数**: (无)

### ast-style-simplify-bool-expr  —  `SIMPLIFY_BOOL_EXPR`

- **规则类**: `org.plumb.style.style.SimplifyBooleanExpressionCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> redundant boolean literal comparison (e.g. x == true).

**规则约束 / 可调参数**: (无)

### ast-style-simplify-bool-return  —  `SIMPLIFY_BOOL_RETURN`

- **规则类**: `org.plumb.style.style.SimplifyBooleanReturnsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> return cond ? true: false : use return cond.

**规则约束 / 可调参数**: (无)

### ast-style-singlespaceseparator  —  `SINGLESPACESEPARATOR`

- **规则类**: `org.plumb.style.style.SingleSpaceSeparatorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Should use single space separator.

**规则约束 / 可调参数**: (无)

### ast-style-singleton-returning-new  —  `SINGLETON_RETURNING_NEW`

- **规则类**: `org.plumb.style.style.SingletonClassReturningNewInstanceCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> utility class method should not return new instance.

**规则约束 / 可调参数**: (无)

### ast-style-singular-field  —  `SINGULAR_FIELD`

- **规则类**: `org.plumb.style.style.SingularFieldCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> field is only used in one method : make it local.

**规则约束 / 可调参数**: (无)

### ast-style-sleepwithoutinterrupt  —  `SLEEPWITHOUTINTERRUPT`

- **规则类**: `org.plumb.style.style.SleepWithoutInterruptCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Sleep without interrupt handling.

**规则约束 / 可调参数**: (无)

### ast-style-standard-functional  —  `STANDARD_FUNCTIONAL`

- **规则类**: `org.plumb.style.style.StandardFunctionalInterfaceCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> custom interface should be a @FunctionalInterface.

**规则约束 / 可调参数**: (无)

### ast-style-startwheregreaterthanend  —  `STARTWHEREGREATERTHANEND`

- **规则类**: `org.plumb.style.style.StartWhereGreaterThanEndCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> loop start value greater than end value.

**规则约束 / 可调参数**: (无)

### ast-style-staticmethodneednotbesynchronized-sb  —  `STATICMETHODNEEDNOTBESYNCHRONIZED_SB`

- **规则类**: `org.plumb.style.style.StaticMethodNeedNotBeSynchronizedCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Static method need not be synchronized.

**规则约束 / 可调参数**: (无)

### ast-style-st-write-static-from-instance  —  `ST_WRITE_STATIC_FROM_INSTANCE`

- **规则类**: `org.plumb.style.style.StaticWriteFromInstanceCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-668
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> instance method writes to static field (ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD).

**规则约束 / 可调参数**: (无)

### ast-style-string-eq-operator  —  `STRING_EQ_OPERATOR`

- **规则类**: `org.plumb.style.style.StringEqOperatorCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-597
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-super-clone  —  `SUPER_CLONE`

- **规则类**: `org.plumb.style.style.SuperCloneCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-668
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> clone() must call super.clone().
>
> Pattern D per-method visit. If method name is `"clone"` and no descendant `super.clone()` call, fire.

**规则约束 / 可调参数**: (无)

### bu-0505-superfluous-instanceof  —  `SUPERFLUOUS_INSTANCEOF`

- **规则类**: `org.plumb.style.style.SuperfluousInstanceofCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **CWE**: CWE-704
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> unnecessary instanceof check.

**规则约束 / 可调参数**: (无)

### ast-style-suppress-warnings-NOT-IMPLEMENTED  —  `SUPPRESS_WARNINGS_NOT_IMPLEMENTED`

- **规则类**: `org.plumb.style.style.SuppressWarningsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> @SuppressWarnings should be specific -- unimplemented.
>
> Detection requires annotation processing / symbol resolution which is outside the AST pass.
> Kept as a registry-skipped placeholder; an analyzer-level checker should replace this when
> needed.

**规则约束 / 可调参数**: (无)

### ast-style-suspicious-comment  —  `SUSPICIOUS_COMMENT`

- **规则类**: `org.plumb.style.style.SuspiciousCommentCheck`
- **分类**: `style`
- **严重度**: INFO
- **类型**: BAD_PRACTICE
- **CWE**: CWE-546
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-546: Suspicious Comment.

**规则约束 / 可调参数**: (无)

### ast-style-suspiciousintegerdivision  —  `SUSPICIOUSINTEGERDIVISION`

- **规则类**: `org.plumb.style.style.SuspiciousIntegerDivisionCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Suspicious integer division.

**规则约束 / 可调参数**: (无)

### ast-style-switch-default-last  —  `SWITCH_DEFAULT_LAST`

- **规则类**: `org.plumb.style.style.SwitchDefaultLastCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> switch default should be the last case.

**规则约束 / 可调参数**: (无)

### ast-style-switch-density  —  `SWITCH_DENSITY`

- **规则类**: `org.plumb.style.style.SwitchDensityCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **SpotBugs**: SF_SWITCH_NO_DEFAULT

**规则说明**

> switch case body exceeds 5 statements.

**规则约束 / 可调参数**: (无)

### ast-style-switch-no-default  —  `SWITCH_NO_DEFAULT`

- **规则类**: `org.plumb.style.style.SwitchWithoutDefaultCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> switch should have a default branch.

**规则约束 / 可调参数**: (无)

### ast-style-switch-few-cases  —  `SWITCH_FEW_CASES`

- **规则类**: `org.plumb.style.style.SwitchWithTooFewCasesCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> switch has fewer than 3 cases : use if-else.

**规则约束 / 可调参数**: (无)

### ast-style-swl-sleep-lock  —  `SWL_SLEEP_LOCK`

- **规则类**: `org.plumb.style.style.SwlSleepWithLockCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-820
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Thread.sleep inside synchronized (SWL_SLEEP_WITH_LOCK_HELD).

**规则约束 / 可调参数**: (无)

### ast-style-synchronized-lock  —  `SYNCHRONIZED_LOCK`

- **规则类**: `org.plumb.style.style.SynchronizedLockCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-820
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> synchronized on a non-final field : use a private final lock.

**规则约束 / 可调参数**: (无)

### ast-style-system-exit-usage  —  `SYSTEM_EXIT_USAGE`

- **规则类**: `org.plumb.style.style.SystemExitUsageCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-382
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid System.exit() / Runtime.exit() in library code.

**规则约束 / 可调参数**: (无)

### ast-style-system-out-println  —  `STYLE_SYSTEM_OUT`

- **规则类**: `org.plumb.style.style.SystemOutCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Avoid System.out / System.err in production code.

**规则约束 / 可调参数**: (无)

### ast-style-system-out-println  —  `SYSTEM_OUT_PRINTLN`

- **规则类**: `org.plumb.style.style.SystemOutPrintlnCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Replace System.out.println with a logger.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `allowInMain` | BOOLEAN | `true` | Skip the main() method | | `allowInTest` | BOOLEAN | `true` |
> Skip methods whose name contains 'test' | | `includeErr` | BOOLEAN | `false` | Also check
> System.err.println/print |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `allowInMain` | BOOLEAN | `true` | Skip the main() method |
| `allowInTest` | BOOLEAN | `true` | Skip methods whose name contains 'test' |
| `includeErr` | BOOLEAN | `false` | Also check System.err.println/print |

### ast-style-system-println  —  `SYSTEM_PRINTLN`

- **规则类**: `org.plumb.style.style.SystemPrintlnCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid System.out.println : use a logger.

**规则约束 / 可调参数**: (无)

### ast-style-tab-character  —  `TAB_CHARACTER`

- **规则类**: `org.plumb.style.style.TabCharacterCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid tab characters in source files.

**规则约束 / 可调参数**: (无)

### ast-style-test-no-assertion  —  `TEST_NO_ASSERTION`

- **规则类**: `org.plumb.style.style.TestWithoutAssertionCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> test method should include at least one assertion.

**规则约束 / 可调参数**: (无)

### ast-style-thread-in-servlet  —  `THREAD_IN_SERVLET`

- **规则类**: `org.plumb.style.style.ThreadInServletCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BAD_PRACTICE
- **CWE**: CWE-383
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-todo-comment  —  `TODO_COMMENT`

- **规则类**: `org.plumb.style.style.TodoCommentCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> TODO/FIXME/XXX comment should be resolved.

**规则约束 / 可调参数**: (无)

### ast-style-too-many-fields  —  `TOO_MANY_FIELDS`

- **规则类**: `org.plumb.style.style.TooManyFieldsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class has too many fields (limit 15).

**规则约束 / 可调参数**: (无)

### ast-style-too-many-locals  —  `TOO_MANY_LOCALS`

- **规则类**: `org.plumb.style.style.TooManyLocalsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method declares more than 15 locals.

**规则约束 / 可调参数**: (无)

### ast-style-too-many-methods  —  `TOO_MANY_METHODS`

- **规则类**: `org.plumb.style.style.TooManyMethodsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class has too many methods (limit 30).

**规则约束 / 可调参数**: (无)

### ast-style-too-many-parameters  —  `TOO_MANY_PARAMETERS`

- **规则类**: `org.plumb.style.style.TooManyParametersCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method / constructor has more than 7 parameters.

**规则约束 / 可调参数**: (无)

### ast-style-uc-useless-object  —  `UC_USELESS_OBJECT`

- **规则类**: `org.plumb.style.style.UcUselessObjectCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> useless object —new X() result discarded (UC_USELESS_OBJECT).

**规则约束 / 可调参数**: (无)

### ast-style-cwe600-uncaught-exception  —  `CWE600_UNCAUGHT_EXCEPTION`

- **规则类**: `org.plumb.style.style.UncaughtExceptionCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-600
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-600 (Uncaught Exception in Servlet) -- file-wide check for risky checked-exception-throwing
> calls.

**规则约束 / 可调参数**: (无)

### ast-style-unchecked-loop-condition  —  `UNCHECKED_LOOP_CONDITION`

- **规则类**: `org.plumb.style.style.UncheckedLoopConditionCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **CWE**: CWE-674
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-674: unchecked loop condition (constant-true loop with no exit).

**规则约束 / 可调参数**: (无)

### ast-style-uncleared-sensitive-info  —  `UNCLEARED_SENSITIVE_INFO`

- **规则类**: `org.plumb.style.style.UnclearedSensitiveInfoCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-226
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-unconfirmed-cast  —  `UNCONFIRMED_CAST`

- **规则类**: `org.plumb.style.style.UnconfirmedCastCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-704
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> cast without matching instanceof check (BC_UNCONFIRMED_CAST).

**规则约束 / 可调参数**: (无)

### ast-style-unnecessaryenumcase-sb  —  `UNNECESSARYENUMCASE_SB`

- **规则类**: `org.plumb.style.style.UnnecessaryEnumCaseCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Unnecessary case in enum switch.

**规则约束 / 可调参数**: (无)

### ast-style-unnecessary-local-before-return  —  `UNNECESSARY_LOCAL_BEFORE_RETURN`

- **规则类**: `org.plumb.style.style.UnnecessaryLocalBeforeReturnCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> return the expression directly (no need for the local).

**规则约束 / 可调参数**: (无)

### ast-style-unnecessary-parens  —  `UNNECESSARY_PARENS`

- **规则类**: `org.plumb.style.style.UnnecessaryParenthesesCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> redundant parentheses around expression.

**规则约束 / 可调参数**: (无)

### ast-style-paren-method  —  `PAREN_METHOD`

- **规则类**: `org.plumb.style.style.UnnecessaryParenthesesMethodCallsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> parentheses around method call are unnecessary.

**规则约束 / 可调参数**: (无)

### ast-style-unnecessary-return  —  `UNNECESSARY_RETURN`

- **规则类**: `org.plumb.style.style.UnnecessaryReturnCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **PMD**: UnnecessaryReturn

**规则说明**

> Final 'return' in void method is unnecessary.

**规则约束 / 可调参数**: (无)

### ast-style-unprotected-cred-transport  —  `UNPROTECTED_CRED_TRANSPORT`

- **规则类**: `org.plumb.style.style.UnprotectedCredTransportCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-523
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-523 (Unprotected Transport of Credentials).
>
> Detects a servlet-emitting string literal that contains a non-SSL `http://` URL AND a
> login form field (`j_security_check` / `j_username` / `j_password`) — the
> credentials will be POSTed in cleartext over the non-SSL channel.
>
> The `(?<!s)http://` pattern tolerates the `https://` form, so a form whose action
> URL is already TLS is not reported.

**规则约束 / 可调参数**: (无)

### ast-style-unsalted-one-way-hash  —  `UNSALTED_ONE_WAY_HASH`

- **规则类**: `org.plumb.style.style.UnsaltedOneWayHashCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-759
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> CWE-759 (Use of a One-Way Hash with no Salt).
>
> Detects `MessageDigest.getInstance("SHA-...")` followed by `.digest(...)` with no
> intervening `.update(...)` call. Without a salt (mixed in via `.update(salt)`), the
> digest is vulnerable to rainbow-table attack.
>
> Detection: linear AST walk over every `Statement`. For each top-level (or any-depth)
> `ExpressionStmt`:
>
> <ol>
> <li>If the statement contains a `MessageDigest.getInstance(...)` call, set a "needs salt"
> flag pointing at the statement's line.
> <li>If the statement contains a `.digest(...)` call (with or without explicit receiver),
> and the "needs salt" flag is set from a preceding line, fire CWE-759.
> <li>If the statement contains a `.update(...)` call, clear the flag.
> </ol>
>
> The per-file linear walk is structurally equivalent to the dfa-side regex (which also did
> file-line-window matching) but without the "no line-wrap" constraint of regex.

**规则约束 / 可调参数**: (无)

### ast-style-unused-assignment  —  `UNUSED_ASSIGNMENT`

- **规则类**: `org.plumb.style.style.UnusedAssignmentCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-563
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> variable is assigned but never used (or overwritten immediately).

**规则约束 / 可调参数**: (无)

### ast-style-unused-formal-parameter  —  `UNUSED_FORMAL_PARAMETER`

- **规则类**: `org.plumb.style.style.UnusedFormalParameterCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **CWE**: CWE-1164
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> unused private method parameter : remove it.

**规则约束 / 可调参数**: (无)

### ast-style-unused-import  —  `UNUSED_IMPORT`

- **规则类**: `org.plumb.style.style.UnusedImportCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-unused-imports  —  `UNUSED_IMPORTS`

- **规则类**: `org.plumb.style.style.UnusedImportsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: UnusedImports

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-unused-label  —  `UNUSED_LABEL`

- **规则类**: `org.plumb.style.style.UnusedLabelCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> label is never used (dead code).

**规则约束 / 可调参数**: (无)

### ast-style-unused-private-field  —  `UNUSED_PRIVATE_FIELD`

- **规则类**: `org.plumb.style.style.UnusedPrivateFieldCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> private field is never read.
>
> Pattern D per-class visit + single body walk that collects a `name -> count` map for
> {@link NameExpr}. For each private field, look up its uses in the pre-computed map (O(1) instead
> of N_field × c.findAll(NameExpr)).
>
> Replaces the original visit(CU) + findAll(ClassOrInterfaceDeclaration) + N_field ×
> c.findAll(NameExpr) -- N×M walks/file down to 2 walks/file.

**规则约束 / 可调参数**: (无)

### ast-style-uppercase-suffix  —  `UPPERCASE_SUFFIX`

- **规则类**: `org.plumb.style.style.UppercaseSuffixCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> long literal should use uppercase L.

**规则约束 / 可调参数**: (无)

### ast-style-upperell  —  `UPPERELL`

- **规则类**: `org.plumb.style.style.UpperEllCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> long literal should use uppercase L (L not l).

**规则约束 / 可调参数**: (无)

### ast-style-use-assertnull  —  `USE_ASSERTNULL`

- **规则类**: `org.plumb.style.style.UseAssertNullInsteadOfAssertTrueCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use assertNull() instead of assertTrue(x == null).

**规则约束 / 可调参数**: (无)

### ast-style-use-assertsame  —  `USE_ASSERTSAME`

- **规则类**: `org.plumb.style.style.UseAssertSameInsteadOfAssertTrueCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use assertSame() instead of assertTrue(a == b) for identity.

**规则约束 / 可调参数**: (无)

### ast-style-use-concurrenthashmap  —  `USE_CONCURRENTHASHMAP`

- **规则类**: `org.plumb.style.style.UseConcurrentHashMapCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-362
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> HashMap field is not thread-safe : use ConcurrentHashMap.

**规则约束 / 可调参数**: (无)

### ast-style-use-diamond  —  `USE_DIAMOND`

- **规则类**: `org.plumb.style.style.UseDiamondOperatorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **PMD**: UseDiamondOperator

**规则说明**

> use <> (diamond) operator for generic type inference.

**规则约束 / 可调参数**: (无)

### ast-style-use-enumeration-instead-of-iterator  —  `USE_ENUMERATION_INSTEAD_OF_ITERATOR`

- **规则类**: `org.plumb.style.style.UseEnumerationInsteadOfIteratorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use Iterator API instead of legacy Enumeration.

**规则约束 / 可调参数**: (无)

### ast-style-useless-object  —  `USELESS_OBJECT`

- **规则类**: `org.plumb.style.style.UselessObjectCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Object created and discarded without use.

**规则约束 / 可调参数**: (无)

### ast-style-uselessoperationonimmutable  —  `USELESSOPERATIONONIMMUTABLE`

- **规则类**: `org.plumb.style.style.UselessOperationOnImmutableCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Useless operation on immutable.

**规则约束 / 可调参数**: (无)

### ast-style-use-object-for-clearer-api  —  `USE_OBJECT_FOR_CLEARER_API`

- **规则类**: `org.plumb.style.style.UseObjectForClearerAPI`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Use object for clearer API.

**规则约束 / 可调参数**: (无)

### ast-style-use-try-with-resources  —  `USE_TRY_WITH_RESOURCES`

- **规则类**: `org.plumb.style.style.UseTryWithResourcesCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **CWE**: CWE-400
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use try-with-resources instead of try-finally to close resources.

**规则约束 / 可调参数**: (无)

### ast-style-use-varargs  —  `USE_VARARGS`

- **规则类**: `org.plumb.style.style.UseVarargsCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use varargs (...) instead of array as last parameter.

**规则约束 / 可调参数**: (无)

### ast-style-uwf-field-not-init  —  `UWF_FIELD_NOT_INIT`

- **规则类**: `org.plumb.style.style.UwfFieldNotInitializedCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> field not initialized in constructor (UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR).

**规则约束 / 可调参数**: (无)

### ast-style-visibility-modifier  —  `VISIBILITY_MODIFIER`

- **规则类**: `org.plumb.style.style.VisibilityModifierCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> field should declare an explicit visibility modifier.

**规则约束 / 可调参数**: (无)

### ast-style-wait-notify-in-loop  —  `WAIT_NOTIFY_IN_LOOP`

- **规则类**: `org.plumb.style.style.WaitNotifyCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: MULTITHREADING
- **CWE**: CWE-662
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> wait()/notify() should be in a loop within a synchronized block.
>
> **Parameters**
>
> | Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
> `requireSyncBlock` | BOOLEAN | `true` | Also require enclosing synchronized block |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `requireSyncBlock` | BOOLEAN | `true` | Also require enclosing synchronized block |

### ast-style-weak-crypto  —  `WEAK_CRYPTO`

- **规则类**: `org.plumb.style.style.WeakCryptoCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: SECURITY
- **CWE**: CWE-327
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (无说明)

**规则约束 / 可调参数**: (无)

### ast-style-while-literal-boolean  —  `WHILE_LITERAL_BOOLEAN`

- **规则类**: `org.plumb.style.style.WhileLoopWithLiteralBooleanCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> while(true) / for(;;) : use break or extract condition.

**规则约束 / 可调参数**: (无)

### ast-style-whitespaceafter  —  `WHITESPACEAFTER`

- **规则类**: `org.plumb.style.style.WhitespaceAfterCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Whitespace after token.

**规则约束 / 可调参数**: (无)

### ast-style-whitespacearound  —  `WHITESPACEAROUND`

- **规则类**: `org.plumb.style.style.WhitespaceAroundCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Whitespace around token.

**规则约束 / 可调参数**: (无)

### ast-style-wmi-wrong-map-iter  —  `WMI_WRONG_MAP_ITER`

- **规则类**: `org.plumb.style.style.WmiWrongMapIteratorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> "iterate keySetget(key) : use entrySet (WMI_WRONG_MAP_ITERATOR)".

**规则约束 / 可调参数**: (无)

## 新增 / 修改规则

新增一条规则的步骤（无需改 plumb-style 的 package 布局）：

1. 在合适的子包下新建 `*Check.java`（如 `src/main/java/org/plumb/style/naming/MyNewCheck.java`）。package 声明要与目录一致：`package org.plumb.style.naming;`。
2. `implements AstChecker`，实现 `name()`、`init(StyleContext)`，以及你关心的那个 `visit(...)` 方法。
3. 在 `visit(...)` 内部用 `ctx.report(line, col, rule, msg, severity, dataflowHead)` 上报。
4. 在 `src/test/java/org/plumb/style/` 下加一个对应的 `MyNewCheckTest.java`（或加进 `AllCheckersSmokeTest`），用最小源码片段验证 fire / no-fire。
5. 跑 `python scripts/gen_rules_doc.py` 重新生成本文档。

