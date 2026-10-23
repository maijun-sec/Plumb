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
  - `StyleCheckRegistry.builtins()` — 全部 504 条
  - `StyleCheckRegistry.load("Foo.*")` — 按简单名 glob 过滤
  - `StyleCheckRegistry.byCategory("security")` — 按子包分类过滤

## 统计概览

- 总规则数: **504**

| 分类 | 数量 | 说明 |
| ---- | ---- | ---- |
| `bug` | 19 | Bug 模式 (容易导致运行时错误的写法) |
| `design` | 18 | 设计 (复杂度、耦合度) |
| `error` | 33 | 错误处理 (异常、try/catch、finalize) |
| `naming` | 51 | 命名 (标识符、命名风格) |
| `performance` | 63 | 性能 (低效实现、可优化写法) |
| `security` | 9 | 安全 (注入、硬编码、加密) |
| `style` | 311 | 风格 (代码风格、格式) |

## 按分类索引

### bug  (19 条)

| 规则 ID | 短名 | 严重度 | 类型 | 规则类 |
| ------- | ---- | ------ | ---- | ------ |
| `pl-0154-compare-objects-with-equals` | `COMPARE_OBJECTS_WITH_EQUALS` | HIGH | BUG | `CompareObjectsWithEqualsCheck` |
| `pl-0159-jumbled-incrementer` | `JUMBLED_INCREMENTER` | LOW | STYLE | `JumbledIncrementerCheck` |
| `pl-0261-rcn-redundant-nullcheck` | `REDUNDANT_NULLCHECK` | LOW | STYLE | `RedundantNullCheckCheck` |
| `pl-0280-comparator-compare` | `COMPARATOR_COMPARE` | LOW | STYLE | `ComparatorCompareCheck` |
| `pl-0286-method-signature` | `METHOD_SIGNATURE` | LOW | STYLE | `MethodSignatureCheck` |
| `pl-0297-np-always-null` | `ALWAYS_NULL` | MEDIUM | BUG | `NpAlwaysNullCheck` |
| `pl-0314-rv-negating-compareto` | `NEGATING_RESULT_OF_COMPARETO` | MEDIUM | BUG | `RvNegatingCompareToCheck` |
| `pl-0329-np-load-null` | `LOAD_OF_KNOWN_NULL_VALUE` | MEDIUM | BUG | `NpLoadOfKnownNullCheck` |
| `pl-0330-bc-equals-no-null` | `EQUALS_NO_NULL_CHECK` | LOW | STYLE | `BcEqualsNoNullCheckCheck` |
| `pl-0422-return-count` | `RETURN_COUNT` | LOW | STYLE | `ReturnCountCheck` |
| `pl-0440-compareto-return-value` | `COMPARETO_RETURN_VALUE` | LOW | STYLE | `CompareToReturnValueCheck` |
| `pl-0507-invalid-compareto-object` | `INVALID_COMPARETO_OBJECT` | LOW | BUG | `InvalidCompareToObjectCheck` |
| `pl-0573-equals-null` | `EQUALS_NULL` | LOW | STYLE | `EqualsNullCheck` |
| `pl-0707-equals-avoid-null` | `EQUALS_AVOID_NULL` | LOW | BAD_PRACTICE | `EqualsAvoidNullCheck` |
| `pl-0728-covariant-equals` | `COVARIANT_EQUALS` | MEDIUM | BUG | `CovariantEqualsCheck` |
| `pl-0736-incorrect-return-check` | `INCORRECT_RETURN_CHECK` | LOW | STYLE | `IncorrectReturnValueCheckChecker` |
| `pl-656-redundant-null-check-null` | `REDUNDANT_NULL_CHECK_NULL` | LOW | STYLE | `RedundantNullCheckOfNullValueCheck` |
| `pl-662-unnecessary-check-not-null` | `UNNECESSARY_CHECK_NOT_NULL` | LOW | STYLE | `UnnecessaryCheckForNotNullCheck` |
| `pl-665-unused-null-check-equals` | `UNUSED_NULL_CHECK_EQUALS` | LOW | STYLE | `UnusedNullCheckInEqualsCheck` |

### design  (18 条)

| 规则 ID | 短名 | 严重度 | 类型 | 规则类 |
| ------- | ---- | ------ | ---- | ------ |
| `pl-0029-cyclomatic-complexity` | `CYCLOMATIC_COMPLEXITY` | LOW | STYLE | `CyclomaticComplexityCheck` |
| `pl-0058-law-of-demeter` | `OF_DEMETER` | LOW | STYLE | `LawOfDemeterCheck` |
| `pl-0160-abstract-no-method` | `ABSTRACT_CLASS_WITHOUT_ABSTRACT_METHOD` | LOW | STYLE | `AbstractClassWithoutAbstractMethodCheck` |
| `pl-0238-uncommented-empty` | `UNCOMMENTED_EMPTY_METHOD` | LOW | STYLE | `EmptyMethodInAbstractClass2Check` |
| `pl-0239-coupling` | `COUPLING_BETWEEN_OBJECTS` | LOW | STYLE | `CouplingBetweenObjectsCheck` |
| `pl-0240-data-class` | `CLASS` | LOW | STYLE | `DataClassCheck` |
| `pl-0241-loose-coupling` | `PACKAGE_COUPLING` | LOW | STYLE | `LoosePackageCouplingCheck` |
| `pl-0272-inheritance-depth` | `INHERITANCE_DEPTH` | LOW | STYLE | `ClassInheritanceDepthCheck` |
| `pl-0405-design-for-extension` | `DESIGN_FOR_EXTENSION` | LOW | STYLE | `DesignForExtensionCheck` |
| `pl-0441-ctor-calling-overridable` | `CTOR_CALLING_OVERRIDABLE` | MEDIUM | BUG | `ConstructorCallingOverridableCheck` |
| `pl-0456-public-ctor-abstract` | `PUBLIC_CTOR_ABSTRACT` | LOW | STYLE | `PublicConstructorInAbstractClassCheck` |
| `pl-0466-class-fanout-complexity` | `FANOUT_COMPLEXITY` | LOW | STYLE | `ClassFanOutComplexityCheck` |
| `pl-0581-method-count` | `METHOD_COUNT` | LOW | STYLE | `MethodCountCheck` |
| `pl-0597-abstract-coupling` | `ABSTRACT_COUPLING` | LOW | STYLE | `AbstractClassCouplingCheck` |
| `pl-0599-bool-expr-complexity` | `EXPR_COMPLEXITY` | LOW | STYLE | `BooleanExpressionComplexityCheck` |
| `pl-0727-java-ncss` | `JAVA_NCSS` | LOW | STYLE | `JavaNCSSCheck` |
| `pl-0732-loose-coupling` | `LOOSE_COUPLING` | LOW | STYLE | `LooseCouplingCheck` |
| `pl-637-confused-inheritance` | `CONFUSED_INHERITANCE` | LOW | STYLE | `ConfusedInheritanceCheck` |

### error  (33 条)

| 规则 ID | 短名 | 严重度 | 类型 | 规则类 |
| ------- | ---- | ------ | ---- | ------ |
| `bu-0803-catch-nullpointer-exception` | `NPE` | MEDIUM | BAD_PRACTICE | `CatchNpeCheck` |
| `bu-1106-throwable-not-thrown` | `THROWABLE_NOT_THROWN` | MEDIUM | BAD_PRACTICE | `ThrowableNotThrownCheck` |
| `pl-0030-explicit-finalize` | `EXPLICIT_FINALIZE` | MEDIUM | BAD_PRACTICE | `ExplicitFinalizeCheck` |
| `pl-0031-finalize-only-overridden` | `FINALIZE_ONLY_OVERRIDDEN` | LOW | BAD_PRACTICE | `FinalizeOnlyOverriddenCheck` |
| `pl-0032-finalize-without-super` | `FINALIZE_WITHOUT_SUPER` | MEDIUM | BAD_PRACTICE | `FinalizeWithoutSuperCheck` |
| `pl-0074-do-not-extend-error` | `NOT_EXTEND_ERROR` | MEDIUM | BAD_PRACTICE | `DoNotExtendJavaLangErrorCheck` |
| `pl-0117-parse-error` | `ERROR_LOGGING` | MEDIUM | BAD_PRACTICE | `ParseErrorLoggingCheck` |
| `pl-0122-instanceof-in-catch` | `INSTANCEOF_IN_CATCH` | MEDIUM | BAD_PRACTICE | `AvoidInstanceofChecksInCatchClauseCheck` |
| `pl-0123-lose-exception-info` | `LOSING_EXCEPTION_INFORMATION` | MEDIUM | BAD_PRACTICE | `AvoidLosingExceptionInformationCheck` |
| `pl-0171-empty-finalizer` | `FINALIZER` | MEDIUM | BAD_PRACTICE | `EmptyFinalizerCheck` |
| `pl-0175-finalize-protected` | `FINALIZE_SHOULD_BE_PROTECTED` | LOW | BAD_PRACTICE | `FinalizeShouldBeProtectedCheck` |
| `pl-0178-identical-catch` | `IDENTICAL_CATCH_BRANCHES` | MEDIUM | BAD_PRACTICE | `IdenticalCatchBranchesCheck` |
| `pl-0197-nested-try` | `NESTED_TRY_CATCH` | MEDIUM | BAD_PRACTICE | `NestedTryCatchCheck` |
| `pl-0200-throw-finally` | `IN_FINALLY` | HIGH | BUG | `ThrowInFinallyCheck` |
| `pl-0202-catch-throwable` | `THROWABLE` | MEDIUM | BAD_PRACTICE | `CatchThrowableCheck` |
| `pl-0253-unchecked-throws` | `PUBLIC_METHOD_UNCHECKED_THROWS` | LOW | STYLE | `PublicMethodNoUncheckedExceptionCheck` |
| `pl-0257-catch-log` | `EXCEPTION_LOG` | MEDIUM | BAD_PRACTICE | `CatchExceptionForLoggingCheck` |
| `pl-0273-generic-exception` | `GENERIC_EXCEPTION` | LOW | STYLE | `GenericExceptionCheck` |
| `pl-0276-suppression-marker` | `SUPPRESSION_MARKER` | LOW | STYLE | `NosonarCommentCheck` |
| `pl-0411-illegal-catch` | `ILLEGAL_CATCH` | LOW | STYLE | `IllegalCatchCheck` |
| `pl-0425-super-finalize` | `FINALIZE` | MEDIUM | BUG | `SuperFinalizeCheck` |
| `pl-0426-throws-count` | `THROWS_COUNT` | LOW | STYLE | `ThrowsCountCheck` |
| `pl-0435-catch-of-throwable` | `OF_THROWABLE` | MEDIUM | BUG | `CatchOfThrowableCheck` |
| `pl-0447-iterator-next-exception` | `ITERATOR_NEXT_EXCEPTION` | LOW | BUG | `IteratorNextExceptionCheck` |
| `pl-0453-object-finalize-overload` | `OBJECT_FINALIZE_OVERLOAD` | LOW | STYLE | `ObjectFinalizeOverloadCheck` |
| `pl-0457-raw-exception` | `EXCEPTION` | LOW | STYLE | `RawExceptionCheck` |
| `pl-0497-assign-catch-var` | `ASSIGN_CATCH_VAR` | LOW | BUG | `AssignmentToCatchBlockParameterCheck` |
| `pl-0539-empty-catch-block-strict` | `CATCH_BLOCK_STRICT` | LOW | STYLE | `EmptyCatchBlockCheck` |
| `pl-0554-catch-throwable` | `AVOID_THROWABLE` | LOW | STYLE | `AvoidCatchingThrowableCheck` |
| `pl-0584-no-finalizer` | `FINALIZER` | LOW | STYLE | `NoFinalizerCheck` |
| `pl-0714-unused-throws` | `UNUSED_THROWS` | LOW | STYLE | `UnusedThrowsCheck` |
| `pl-641-dropped-exception` | `DROPPED_EXCEPTION` | LOW | STYLE | `DroppedExceptionCheck` |
| `pl-registry-catch_generic` | `CATCH_GENERIC` | ? | ? | `CatchGenericCheck` |

### naming  (51 条)

| 规则 ID | 短名 | 严重度 | 类型 | 规则类 |
| ------- | ---- | ------ | ---- | ------ |
| `pl-0028-compare-classes` | `COMPARE_CLASSES` | MEDIUM | BUG | `CompareClassNamesCheck` |
| `pl-0054-avoid-final-local` | `FINAL_LOCAL` | LOW | STYLE | `AvoidFinalLocalVariableCheck` |
| `pl-0097-unnecessary-fqn` | `UNNECESSARY_FULLY_QUALIFIED_NAME` | LOW | STYLE | `UnnecessaryFullyQualifiedClassNameCheck` |
| `pl-0105-local-var-could-be-final` | `VAR_COULD_BE_FINAL` | LOW | STYLE | `LocalVariableCouldBeFinalCheck` |
| `pl-0110-at-least-one-constructor` | `AT_LEAST_ONE_CONSTRUCTOR` | LOW | STYLE | `AtLeastOneConstructorCheck` |
| `pl-0113-empty-method-in-abstract` | `UNCOMMENTED_EMPTY_METHOD` | LOW | STYLE | `EmptyMethodInAbstractClassCheck` |
| `pl-0115-bean-serialize` | `MEMBERS_SHOULD_SERIALIZE` | MEDIUM | BAD_PRACTICE | `BeanMembersShouldSerializeCheck` |
| `pl-0137-suspicious-equals` | `SUSPICIOUS_EQUALS_METHOD_NAME` | MEDIUM | BAD_PRACTICE | `SuspiciousEqualsMethodNameCheck` |
| `pl-0146-use-utility-class` | `UTILITY_CLASS` | LOW | STYLE | `UseUtilityClassCheck` |
| `pl-0181-invalid-log` | `INVALID_LOG_MESSAGE_FORMAT` | LOW | BAD_PRACTICE | `InvalidLogMessageFormatCheck` |
| `pl-0184-local-var-naming` | `VARIABLE_NAMING_CONVENTIONS` | LOW | NAMING | `LocalVariableNamingConventionsCheck` |
| `pl-0189-method-naming` | `METHOD_NAMING_CONVENTIONS` | LOW | NAMING | `MethodNamingConventionsCheck` |
| `pl-0194-shadow-field` | `SHADOW_FIELD` | MEDIUM | BAD_PRACTICE | `LocalVariableShadowingFieldCheck` |
| `pl-0204-style-class-naming` | `CLASS` | LOW | NAMING | `ClassNamingConventionsCheck` |
| `pl-0206-equals-hash` | `OVERRIDE_EQUALS_AND_HASHCODE` | MEDIUM | BUG | `OverrideEqualsAndHashCodeCheck` |
| `pl-0220-short-class` | `CLASS_NAME` | LOW | NAMING | `ShortClassNameCheck` |
| `pl-0221-short-method` | `METHOD_NAME` | LOW | NAMING | `ShortMethodNameCheck` |
| `pl-0222-short-variable` | `VARIABLE` | LOW | NAMING | `ShortVariableCheck` |
| `pl-0232-package-case` | `PACKAGE_CASE` | LOW | NAMING | `PackageCaseCheck` |
| `pl-0247-public-constant` | `PUBLIC_CONSTANT_FIELD_NAMING` | LOW | NAMING | `PublicConstantFieldNamingCheck` |
| `pl-0248-local-var-return` | `VAR_THEN_RETURN` | LOW | STYLE | `LocalVariableThenReturnCheck` |
| `pl-0251-class-name-long` | `NAME_TOO_LONG` | LOW | NAMING | `ClassNameTooLongCheck` |
| `pl-0275-field-naming` | `NAMING_CONVENTION` | LOW | NAMING | `FieldNamingConventionCheck` |
| `pl-0404-constant-name` | `CONSTANT_NAME` | LOW | NAMING | `ConstantNameCheck` |
| `pl-0408-final-local-variable` | `LOCAL_VARIABLE` | LOW | STYLE | `FinalLocalVariableCheck` |
| `pl-0414-method-name` | `METHOD_NAME` | LOW | NAMING | `MethodNameCheck` |
| `pl-0429-unused-local-var` | `UNUSED_LOCAL_VAR` | LOW | STYLE | `UnusedLocalVariableCheck` |
| `pl-0432-bad-abstract-class-name` | `ABSTRACT_CLASS_NAME` | LOW | NAMING | `BadAbstractClassNameCheck` |
| `pl-0433-boolean-get-method-name` | `BOOLEAN_GET_METHOD_NAME` | LOW | NAMING | `BooleanGetMethodNameCheck` |
| `pl-0438-class-only-private-ctors` | `ONLY_PRIVATE_CTORS` | LOW | STYLE | `ClassWithOnlyPrivateConstructorsCheck` |
| `pl-0467-class-data-abstraction` | `DATA_ABSTRACTION_COUPLING` | LOW | STYLE | `ClassDataAbstractionCouplingCheck` |
| `pl-0480-missing-override-strict` | `MISSING_OVERRIDE_STRICT` | LOW | STYLE | `MissingOverrideCheck` |
| `pl-0536-class-type-param` | `TYPE_PARAM` | LOW | STYLE | `ClassTypeParameterNameCheck` |
| `pl-0545-interface-type-param` | `INTERFACE_TYPE_PARAM` | LOW | STYLE | `InterfaceTypeParameterNameCheck` |
| `pl-0547-local-final-var-name` | `FINAL_VAR_NAME` | LOW | STYLE | `LocalFinalVariableNameCheck` |
| `pl-0548-local-var-name` | `VAR_NAME` | LOW | STYLE | `LocalVariableNameCheck` |
| `pl-0549-member-name` | `MEMBER_NAME` | LOW | STYLE | `MemberNameCheck` |
| `pl-0551-abstract-naming` | `ABSTRACT_NAMING` | LOW | STYLE | `AbstractNamingCheck` |
| `pl-0552-array-naming-strict` | `NAMING_STRICT` | LOW | STYLE | `ArrayNamingCheck` |
| `pl-0577-local-home-naming` | `HOME_NAMING` | LOW | STYLE | `LocalHomeNamingConventionCheck` |
| `pl-0579-long-var-strict` | `VAR_STRICT` | LOW | STYLE | `LongVariableCheck` |
| `pl-0589-type-name` | `NAME` | LOW | STYLE | `TypeNameCheck` |
| `pl-0700-empty-method` | `EMPTY_METHOD` | LOW | STYLE | `EmptyMethodCheck` |
| `pl-0704-abstract` | `ABSTRACT` | LOW | STYLE | `AbstractCheck` |
| `pl-0710-generic-naming` | `GENERIC_NAMING` | LOW | NAMING | `GenericNamingCheck` |
| `pl-0713-use-underscores-in-numeric-literals` | `USE_UNDERSCORES_IN_NUMERIC_LITERALS` | LOW | STYLE | `UseUnderscoresInNumericLiteralsCheck` |
| `pl-0731-singleton-class` | `SINGLETON_CLASS` | LOW | STYLE | `SingletonClassCheck` |
| `pl-620-outer-type-filename` | `TYPE_FILENAME` | LOW | STYLE | `OuterTypeFilenameCheck` |
| `pl-647-naming-convention` | `NAMING_CONVENTION` | LOW | STYLE | `NamingConventionCheck` |
| `pl-664-unnecessary-fully-qualified-enum` | `UNNECESSARY_FULLY_QUALIFIED_ENUM` | LOW | STYLE | `UnnecessaryFullyQualifiedEnumNameCheck` |
| `pl-registry-constant_naming` | `CONSTANT_NAMING` | ? | ? | `ConstantNamingCheck` |

### performance  (63 条)

| 规则 ID | 短名 | 严重度 | 类型 | 规则类 |
| ------- | ---- | ------ | ---- | ------ |
| `bu-0801-array-hashcode-tostring` | `HASHCODE_TOSTRING` | MEDIUM | CORRECTNESS | `ArrayHashCodeAndToStringCheck` |
| `bu-0804-synchronization-on-string-or-boxed` | `STRING_BOXED` | HIGH | MULTITHREADING | `SynchronizationOnStringOrBoxedCheck` |
| `bu-0903-string-builder-concatenation-in-loop` | `STRING_BUILDER_CONCATENATION_IN_LOOP` | MEDIUM | PERFORMANCE | `StringBuilderConcatenationCheck` |
| `bu-1104-string-equality-with-equals` | `STRING_EQUALITY_WITH_EQUALS` | MEDIUM | CORRECTNESS | `StringEqualityWithEqualsCheck` |
| `pl-0045-use-arraylist-instead-of-vector` | `ARRAYLIST_INSTEAD_OF_VECTOR` | LOW | PERFORMANCE | `UseArrayListInsteadOfVectorCheck` |
| `pl-0062-for-loop-float` | `LOOP_FLOAT` | MEDIUM | BUG | `ForLoopVariableTypeCheck` |
| `pl-0067-unnecessary-cast` | `UNNECESSARY_CAST` | LOW | STYLE | `UnnecessaryCastCheck` |
| `pl-0073-do-not-call-gc` | `NOT_CALL_GC` | MEDIUM | BAD_PRACTICE | `DoNotCallGarbageCollectionCheck` |
| `pl-0083-avoid-calendar-date-instance` | `CALENDAR_DATE_INSTANCE` | LOW | BAD_PRACTICE | `AvoidCalendarDateInstanceCheck` |
| `pl-0084-avoid-stringbuffer-field` | `STRINGBUFFER_FIELD` | LOW | BAD_PRACTICE | `AvoidStringBufferFieldCheck` |
| `pl-0086-unnecessary-case-change` | `UNNECESSARY_CASE_CHANGE` | LOW | STYLE | `UnnecessaryCaseChangeCheck` |
| `pl-0087-use-short-array-init` | `SHORT_ARRAY_INITIALIZER` | LOW | STYLE | `UseShortArrayInitializerCheck` |
| `pl-0089-use-assertequals` | `ASSERTEQUALS_INSTEAD_OF_ASSERTTRUE` | LOW | STYLE | `UseAssertEqualsInsteadOfAssertTrueCheck` |
| `pl-0093-return-empty-not-null` | `RETURN_EMPTY_NOT_NULL` | MEDIUM | BAD_PRACTICE | `ReturnEmptyCollectionNotNullCheck` |
| `pl-0108-use-stringbuffer` | `STRINGBUFFER_FOR_STRING_APPENDS` | MEDIUM | PERFORMANCE | `UseStringBufferForStringAppendsCheck` |
| `pl-0109-string-instantiation` | `STRING_INSTANTIATION` | LOW | STYLE | `StringInstantiationCheck` |
| `pl-0121-avoid-array-loops` | `ARRAY_LOOPS` | LOW | STYLE | `AvoidArrayLoopsCheck` |
| `pl-0139-use-asserttrue` | `ASSERTTRUE_INSTEAD_OF_ASSERTEQUALS` | LOW | STYLE | `UseAssertTrueInsteadOfAssertEqualsCheck` |
| `pl-0140-use-collection-is-empty` | `COLLECTION_IS_EMPTY` | LOW | PERFORMANCE | `UseCollectionIsEmptyCheck` |
| `pl-0153-for-size` | `LOOP_WITH_SIZE_METHOD` | LOW | PERFORMANCE | `ForLoopWithSizeMethodCheck` |
| `pl-0155-use-indexof-char` | `INDEXOF_CHAR` | LOW | PERFORMANCE | `UseIndexOfCharCheck` |
| `pl-0157-useless-string-valueof` | `USELESS_STRING_VALUEOF` | LOW | PERFORMANCE | `UselessStringValueOfCheck` |
| `pl-0204-dup-string` | `DUPLICATED_STRING_LITERAL` | LOW | STYLE | `DuplicatedStringLiteralCheck` |
| `pl-0213-string-eq` | `COMPARE_STRINGS_WITH_EQUALS` | HIGH | BUG | `CompareStringsWithEqualsCheck` |
| `pl-0223-simplify-bool` | `SIMPLIFY_BOOLEAN_EXPRESSIONS` | LOW | STYLE | `SimplifyBooleanExpressionsCheck` |
| `pl-0225-simplify-conditional` | `SIMPLIFY_CONDITIONAL` | LOW | STYLE | `SimplifyConditionalCheck` |
| `pl-0237-return-null-collection` | `RETURN_EMPTY_COLLECTION_RATHER_THAN_NULL` | MEDIUM | BAD_PRACTICE | `ReturnEmptyCollectionRatherThanNullCheck` |
| `pl-0246-array-key` | `AS_KEY` | MEDIUM | BUG | `ArrayAsKeyOfSetOrMapCheck` |
| `pl-0288-primitives-boxed` | `PRIMITIVES_BOXED` | MEDIUM | BAD_PRACTICE | `PrimitivesBoxedCheck` |
| `pl-0294-string-buffer-constructor` | `STRING_BUFFER_CONSTRUCTOR` | LOW | STYLE | `StringBufferConstructorCheck` |
| `pl-0296-dm-boxed-primitive` | `BOXED_PRIMITIVE_FOR_PARSING` | MEDIUM | BUG | `DmBoxedPrimitiveForParsingCheck` |
| `pl-0300-ec-bad-array-compare` | `BAD_ARRAY_COMPARE` | MEDIUM | BUG | `EcBadArrayCompareCheck` |
| `pl-0322-range-array-length` | `ARRAY_LENGTH` | MEDIUM | BUG | `RangeArrayLengthCheck` |
| `pl-0323-range-array-offset` | `ARRAY_OFFSET` | MEDIUM | BUG | `RangeArrayOffsetCheck` |
| `pl-0324-range-string-length` | `STRING_LENGTH` | MEDIUM | BUG | `RangeStringLengthCheck` |
| `pl-0325-range-string-index` | `STRING_INDEX` | LOW | STYLE | `RangeStringIndexCheck` |
| `pl-0326-range-array-index` | `ARRAY_INDEX` | MEDIUM | BUG | `RangeArrayIndexCheck` |
| `pl-0332-bc-bad-cast-abstract` | `BAD_CAST_TO_ABSTRACT_COLLECTION` | MEDIUM | BUG | `BcBadCastToAbstractCollectionCheck` |
| `pl-0336-ec-bad-array-store` | `BAD_ARRAY_STORE` | MEDIUM | BUG | `EcBadArrayStoreCheck` |
| `pl-0341-dmi-collection-urls` | `COLLECTION_OF_URLS` | MEDIUM | BUG | `DmiCollectionOfUrlsCheck` |
| `pl-0439-collection-inappropriate` | `COLLECTION_INAPPROPRIATE` | LOW | STYLE | `CollectionInappropriateCheck` |
| `pl-0450-loops-same-condition` | `SAME_CONDITION` | LOW | BUG | `LoopsWithSameConditionCheck` |
| `pl-0452-null-returned-tostring` | `RETURNED_TOSTRING` | LOW | STYLE | `NullReturnedToStringCheck` |
| `pl-0490-string-literal-equality` | `STRING_LITERAL_EQUALITY` | LOW | STYLE | `StringLiteralEqualityCheck` |
| `pl-0505-floating-point-loops` | `FLOATING_POINT_LOOPS` | LOW | BUG | `FloatingPointLoopsCheck` |
| `pl-0511-malformed-format-string` | `MALFORMED_FORMAT_STRING` | LOW | BUG | `MalformedFormatStringCheck` |
| `pl-0515-non-singleton-primitive` | `SINGLETON_PRIMITIVE` | LOW | BUG | `NonSingletonPrimitiveConstructorInCheck` |
| `pl-0517-prefer-zero-length-arrays` | `PREFER_ZERO_LENGTH_ARRAYS` | LOW | BUG | `PreferZeroLengthArraysCheck` |
| `pl-0523-returns-null-collection` | `RETURNS_NULL_COLLECTION` | LOW | BUG | `ReturnsNullInsteadOfEmptyCollectionCheck` |
| `pl-0527-string-buffer-field-serial` | `STRING_BUFFER_FIELD_SERIAL` | LOW | BUG | `StringBufferFieldInSerialClassCheck` |
| `pl-0569-float-loop-index-strict` | `LOOP_INDEX_STRICT` | LOW | STYLE | `DontUseFloatTypeForLoopIndicesCheck` |
| `pl-0613--array-equality` | `EQUALITY` | MEDIUM | CORRECTNESS | `ArrayEqualityCheck` |
| `pl-0706-array-type` | `ARRAY_TYPE` | LOW | STYLE | `ArrayTypeCheck` |
| `pl-0708-jumbled-incrementer` | `JUMBLED_INCREMENTER` | MEDIUM | BUG | `JumbledIncrementerLoopCheck` |
| `pl-0730-unnecessary-constructor` | `UNNECESSARY_CONSTRUCTOR` | LOW | STYLE | `UnnecessaryConstructorCheck` |
| `pl-615-no-array-trailing-comma` | `ARRAY_TRAILING_COMMA` | LOW | STYLE | `NoArrayTrailingCommaCheck` |
| `pl-629-array-store-incompatible` | `STORE_INCOMPATIBLE` | LOW | STYLE | `ArrayStoreIncompatibleCheck` |
| `pl-630-array-too-large` | `TOO_LARGE` | LOW | STYLE | `ArrayTooLargeCheck` |
| `pl-634-boxed-constructor` | `CONSTRUCTOR` | LOW | STYLE | `BoxedConstructorCheck` |
| `pl-644-jdk-obsolete-collection` | `OBSOLETE_COLLECTION` | LOW | STYLE | `JdkObsoleteCollectionCheck` |
| `pl-646-method-return-check` | `METHOD_RETURN_CHECK` | LOW | STYLE | `MethodReturnCheck` |
| `pl-registry-decimal_equals` | `DECIMAL_EQUALS` | ? | ? | `BigDecimalEqualsCheck` |
| `pl-unnecessary-boxing` | `UNNECESSARY_BOXING` | LOW | STYLE | `UnnecessaryBoxingCheck` |

### security  (9 条)

| 规则 ID | 短名 | 严重度 | 类型 | 规则类 |
| ------- | ---- | ------ | ---- | ------ |
| `bu-1107-hardcoded-credentials` | `HARDCODED_CREDENTIALS` | CRITICAL | SECURITY | `HardcodedCredentialsCheck` |
| `pl-0190-hardcoded-uri` | `HARDCODED_URI` | MEDIUM | STYLE | `HardcodedURICheck` |
| `pl-0281-loop-once` | `EXECUTING_AT_MOST_ONCE` | LOW | STYLE | `LoopExecutingAtMostOnceCheck` |
| `pl-0327-insecure-crypto` | `INSECURE_CRYPTO` | HIGH | SECURITY | `InsecureCryptoCheck` |
| `pl-0339-dmi-hardcoded-path` | `HARDCODED_ABSOLUTE_PATH` | MEDIUM | BUG | `DmiHardcodedAbsolutePathCheck` |
| `pl-0434-case-insensitive-comparison` | `INSENSITIVE_COMPARISON` | LOW | STYLE | `CaseInsensitiveComparisonCheck` |
| `pl-0446-hardcoded-ip` | `HARDCODED_IP` | MEDIUM | SECURITY | `HardcodedIpCheck` |
| `pl-0558-hard-coded-ip` | `CODED_IP` | LOW | STYLE | `AvoidUsingHardCodedIPCheck` |
| `pl-652-path-check` | `CHECK` | LOW | STYLE | `PathCheck` |

### style  (311 条)

| 规则 ID | 短名 | 严重度 | 类型 | 规则类 |
| ------- | ---- | ------ | ---- | ------ |
| `bu-0701-extends-object` | `EXTENDS_OBJECT` | LOW | STYLE | `ExtendsObjectCheck` |
| `bu-0702-empty-method-in-abstract` | `METHOD_IN_ABSTRACT` | LOW | STYLE | `EmptyMethodInAbstractCheck` |
| `bu-0705-comment-default-access` | `COMMENT_DEFAULT_ACCESS` | LOW | STYLE | `CommentDefaultAccessCheck` |
| `bu-0805-boolean-method-returns-null` | `BOOLEAN_RETURNS_NULL` | HIGH | BUG | `BooleanMethodReturnCheck` |
| `bu-0901-mutable-static-should-be-final` | `MUTABLE_STATIC_SHOULD_BE_FINAL` | MEDIUM | MALICIOUS_CODE | `MutableStaticShouldBeFinalCheck` |
| `bu-0902-locale-dependent-case-conversion` | `LOCALE_DEPENDENT_CASE_CONVERSION` | LOW | I18N | `LocaleDependentCaseConversionCheck` |
| `bu-0904-useless-object` | `USELESS_OBJECT` | LOW | PERFORMANCE | `UselessObjectCheck` |
| `bu-0905-int-division-cast-to-double` | `DIVISION_CAST_TO_DOUBLE` | LOW | STYLE | `IntDivisionCastToDoubleCheck` |
| `bu-1001-unreachable-code` | `UNREACHABLE_CODE` | LOW | CORRECTNESS | `DeadcodeCheck` |
| `bu-1103-avoid-print-stack-trace` | `PRINT_STACK_TRACE` | MEDIUM | BAD_PRACTICE | `AvoidPrintStackTraceCheck` |
| `bu-1105-expose-internal-representation` | `EXPOSE_INTERNAL_REPRESENTATION` | MEDIUM | CORRECTNESS | `ExposeInternalRepresentationCheck` |
| `cov-0001-missing-break` | `MISSING_BREAK` | LOW | CORRECTNESS | `MissingBreakCheck` |
| `pl-0021-empty-finally-block` | `FINALLY_BLOCK` | LOW | STYLE | `EmptyFinallyBlockCheck` |
| `pl-0024-empty-constructor` | `CONSTRUCTOR` | LOW | STYLE | `EmptyConstructorCheck` |
| `pl-0025-empty-sync-block` | `SYNC_BLOCK` | LOW | STYLE | `EmptySyncBlockCheck` |
| `pl-0027-cn-idiom-no-super-call` | `IDIOM` | HIGH | BUG | `CloneWithoutSuperCheck` |
| `pl-0033-class-only-private-ctors` | `ONLY_PRIVATE_CTORS` | LOW | STYLE | `IncompleteClassDeclarationCheck` |
| `pl-0036-public-static-mutable` | `PUBLIC_STATIC_MUTABLE` | MEDIUM | BAD_PRACTICE | `PublicStaticMutableFieldCheck` |
| `pl-0037-reachable-assertion` | `REACHABLE_ASSERTION` | MEDIUM | BAD_PRACTICE | `ReachableAssertionCheck` |
| `pl-0039-redirect-without-exit` | `REDIRECT_WITHOUT_EXIT` | MEDIUM | BUG | `RedirectWithoutExitCheck` |
| `pl-0041-system-exit` | `SYSTEM_EXIT` | MEDIUM | BAD_PRACTICE | `SystemExitUsageCheck` |
| `pl-0042-too-many-locals` | `MANY_LOCALS` | LOW | STYLE | `TooManyLocalsCheck` |
| `pl-0043-parameter-number` | `PARAMETER_NUMBER` | LOW | STYLE | `TooManyParametersCheck` |
| `pl-0044-unused-label` | `UNUSED_LABEL` | LOW | STYLE | `UnusedLabelCheck` |
| `pl-0046-use-enum-instead-of-iterator` | `ENUM_INSTEAD_OF_ITERATOR` | LOW | STYLE | `UseEnumerationInsteadOfIteratorCheck` |
| `pl-0047-call-to-thread-run` | `TO_THREAD_RUN` | HIGH | BUG | `CallToThreadRunCheck` |
| `pl-0048-double-checked-locking` | `DOUBLECHECK` | HIGH | MULTITHREADING | `DoubleCheckedLockingCheck` |
| `pl-0052-use-try-with-resources` | `TRY_WITH_RESOURCES` | LOW | STYLE | `UseTryWithResourcesCheck` |
| `pl-0057-switch-density` | `SWITCH_DENSITY` | LOW | STYLE | `SwitchDensityCheck` |
| `pl-0059-unnecessary-local-before-return` | `UNNECESSARY_LOCAL_BEFORE_RETURN` | LOW | STYLE | `UnnecessaryLocalBeforeReturnCheck` |
| `pl-0061-empty-statement` | `STATEMENT` | LOW | STYLE | `EmptyStatementUsageCheck` |
| `pl-0064-visibility-modifier` | `VISIBILITY_MODIFIER` | LOW | STYLE | `VisibilityModifierCheck` |
| `pl-0069-javadoc-method` | `JAVADOC_METHOD` | LOW | STYLE | `JavadocMethodCheck` |
| `pl-0075-avoid-messagedigest-field` | `MESSAGEDIGEST_FIELD` | MEDIUM | BAD_PRACTICE | `AvoidMessageDigestFieldCheck` |
| `pl-0076-avoid-native-methods` | `NATIVE_METHODS` | LOW | BAD_PRACTICE | `AvoidNativeMethodsCheck` |
| `pl-0077-avoid-threadgroup` | `THREADGROUP` | MEDIUM | BAD_PRACTICE | `AvoidThreadGroupCheck` |
| `pl-0078-avoid-octal-values` | `OCTAL_VALUES` | MEDIUM | BAD_PRACTICE | `AvoidUsingOctalValuesCheck` |
| `pl-0079-avoid-volatile` | `VOLATILE` | LOW | BAD_PRACTICE | `AvoidUsingVolatileCheck` |
| `pl-0081-call-super-in-constructor` | `SUPER_IN_CONSTRUCTOR` | MEDIUM | BAD_PRACTICE | `CallSuperInConstructorCheck` |
| `pl-0082-confusing-ternary` | `CONFUSING_TERNARY` | MEDIUM | BAD_PRACTICE | `ConfusingTernaryCheck` |
| `pl-0085-need-braces` | `BRACES` | LOW | STYLE | `ControlStatementBracesCheck` |
| `pl-0090-use-assertnull` | `ASSERTNULL_INSTEAD_OF_ASSERTTRUE` | LOW | STYLE | `UseAssertNullInsteadOfAssertTrueCheck` |
| `pl-0091-use-assertsame` | `ASSERTSAME_INSTEAD_OF_ASSERTTRUE` | LOW | STYLE | `UseAssertSameInsteadOfAssertTrueCheck` |
| `pl-0092-use-concurrenthashmap` | `CONCURRENT_HASHMAP` | MEDIUM | BAD_PRACTICE | `UseConcurrentHashMapCheck` |
| `pl-0096-use-diamond` | `DIAMOND_OPERATOR` | LOW | STYLE | `UseDiamondOperatorCheck` |
| `pl-0098-singleton-returning-new` | `SINGLETON_RETURNING_NEW` | LOW | BAD_PRACTICE | `SingletonClassReturningNewInstanceCheck` |
| `pl-0099-unused-assignment` | `UNUSED_ASSIGNMENT` | MEDIUM | BUG | `UnusedAssignmentCheck` |
| `pl-0101-unused-formal-parameter` | `UNUSED_FORMAL_PARAMETER` | LOW | STYLE | `UnusedFormalParameterCheck` |
| `pl-0103-style-unused-import` | `UNUSED_IMPORT` | LOW | STYLE | `UnusedImportCheck` |
| `pl-0104-method-arg-could-be-final` | `METHOD_ARG_COULD_BE_FINAL` | LOW | STYLE | `MethodArgumentCouldBeFinalCheck` |
| `pl-0106-only-one-return` | `ONE_RETURN` | LOW | STYLE | `OnlyOneReturnCheck` |
| `pl-0107-return-from-finally` | `RETURN_FROM_FINALLY` | HIGH | BUG | `ReturnFromFinallyBlockCheck` |
| `pl-0112-missing-static-in-non-instantiable` | `MISSING_STATIC_IN_NON_INSTANTIABLE` | MEDIUM | BAD_PRACTICE | `MissingStaticMethodInNonInstantiableClassCheck` |
| `pl-0114-premature-declaration` | `PREMATURE_DECLARATION` | LOW | STYLE | `PrematureDeclarationCheck` |
| `pl-0116-simpledateformat-locale` | `SIMPLEDATEFORMAT_LOCALE` | MEDIUM | BAD_PRACTICE | `SimpleDateFormatNeedsLocaleCheck` |
| `pl-0118-default-package` | `DEFAULT_PACKAGE` | LOW | BAD_PRACTICE | `PublicStaticFieldAccessedInSubclassCheck` |
| `pl-0119-literals-first` | `LITERALS_FIRST_IN_COMPARISONS` | MEDIUM | BUG | `LiteralsFirstInComparisonsCheck` |
| `pl-0125-dont-import-javalang` | `IMPORT_JAVA_LANG` | LOW | STYLE | `DontImportJavaLangCheck` |
| `pl-0126-duplicate-imports` | `DUPLICATE_IMPORTS` | LOW | STYLE | `DuplicateImportsCheck` |
| `pl-0127-empty-if-stmt` | `IF_STMT` | MEDIUM | BUG | `EmptyIfStmtCheck` |
| `pl-0129-empty-switch` | `SWITCH_STATEMENTS` | MEDIUM | BUG | `EmptySwitchStatementsCheck` |
| `pl-0130-empty-try-block` | `TRY_BLOCK` | LOW | STYLE | `EmptyTryBlockCheck` |
| `pl-0132-equal-comparison` | `COMPARISON` | HIGH | BUG | `EqualComparisonCheck` |
| `pl-0135-redundant-init` | `REDUNDANT_FIELD_INITIALIZER` | LOW | STYLE | `RedundantFieldInitializerCheck` |
| `pl-0142-while-literal-boolean` | `LOOP_WITH_LITERAL_BOOLEAN` | LOW | STYLE | `WhileLoopWithLiteralBooleanCheck` |
| `pl-0149-system-println` | `SYSTEM_PRINTLN` | MEDIUM | BAD_PRACTICE | `SystemPrintlnCheck` |
| `pl-0151-preserve-stack` | `PRESERVE_STACK_TRACE` | MEDIUM | BAD_PRACTICE | `PreserveStackTraceCheck` |
| `pl-0152-reassign-loop-var` | `REASSIGNING_LOOP_VARIABLES` | MEDIUM | BUG | `AvoidReassigningLoopVariablesCheck` |
| `pl-0162-assign-in-operand` | `ASSIGNMENT_IN_OPERAND` | MEDIUM | BAD_PRACTICE | `AssignmentInOperandCheck` |
| `pl-0163-deeply-nested-if` | `DEEPLY_NESTED_IF_STMTS` | MEDIUM | BAD_PRACTICE | `AvoidDeeplyNestedIfStmtsCheck` |
| `pl-0166-reassign-parameter` | `REASSIGNING_PARAMETERS` | MEDIUM | BAD_PRACTICE | `AvoidReassigningParametersCheck` |
| `pl-0167-bigdec-double` | `BIGDECIMAL_DOUBLE` | HIGH | BUG | `BigDecimalDoubleConstructorCheck` |
| `pl-0168-private-ctors-final` | `WITH_ONLY_PRIVATE_CONSTRUCTORS_SHOULD_BE_FINAL` | LOW | STYLE | `ClassWithOnlyPrivateConstructorsShouldBeFinalCheck` |
| `pl-0176-for-foreach` | `LOOP_CAN_BE_FOREACH` | LOW | STYLE | `ForLoopCanBeForeachCheck` |
| `pl-0177-for-var-count` | `LOOP_VARIABLE_COUNT` | LOW | STYLE | `ForLoopVariableCountCheck` |
| `pl-0180-import-same-package` | `IMPORT_FROM_SAME_PACKAGE` | LOW | STYLE | `ImportFromSamePackageCheck` |
| `pl-0182-junit-message` | `ASSERTIONS_SHOULD_INCLUDE_MESSAGE` | LOW | STYLE | `JUnitAssertionsShouldIncludeMessageCheck` |
| `pl-0183-junit-no-assert` | `TESTS_SHOULD_INCLUDE_ASSERT` | MEDIUM | BUG | `JUnitTestsShouldIncludeAssertCheck` |
| `pl-0187-only-one-return-strict` | `ONE_RETURN_STRICT` | LOW | STYLE | `OnlyOneReturnPerMethodCheck` |
| `pl-0191-nested-block` | `NESTED_BLOCK` | LOW | STYLE | `NestedBlockCheck` |
| `pl-0193-class-field-shadow` | `FIELD_SHADOW` | MEDIUM | BAD_PRACTICE | `ClassFieldShadowingCheck` |
| `pl-0195-bool-negation` | `BOOLEAN_LITERAL_NEGATION` | MEDIUM | BUG | `BooleanLiteralNegationCheck` |
| `pl-0196-return-bool` | `RETURN_BOOLEAN_LITERAL` | LOW | STYLE | `ReturnBooleanLiteralCheck` |
| `pl-0199-interface-constants` | `CONSTANTS_IN_INTERFACE` | LOW | STYLE | `ConstantsInInterfaceCheck` |
| `pl-0207-private` | `MEMBERS_SHOULD_BE_PRIVATE` | LOW | STYLE | `ClassMembersShouldBePrivateCheck` |
| `pl-0208-logger-final` | `LOGGER_SHOULD_BE_FINAL` | MEDIUM | BAD_PRACTICE | `LoggerShouldBeFinalCheck` |
| `pl-0209-switch-default` | `SWITCH_WITHOUT_DEFAULT` | MEDIUM | BUG | `SwitchWithoutDefaultCheck` |
| `pl-0215-ctor-method` | `METHOD_CALL` | MEDIUM | BAD_PRACTICE | `MethodCallsFromConstructorCheck` |
| `pl-0216-conditional-constant` | `CONDITIONAL_ON_CONSTANT` | HIGH | BUG | `ConditionalOnConstantCheck` |
| `pl-0217-empty-while` | `WHILE_STMT` | MEDIUM | BUG | `EmptyWhileStmtCheck` |
| `pl-0218-if-else-stmt` | `ELSE_STMT` | LOW | STYLE | `IfElseStmtCheck` |
| `pl-0219-null-assignment` | `ASSIGNMENT` | LOW | STYLE | `NullAssignmentCheck` |
| `pl-0224-simplify-bool-return` | `SIMPLIFY_BOOLEAN_RETURNS` | LOW | STYLE | `SimplifyBooleanReturnsCheck` |
| `pl-0226-singular-field` | `SINGULAR_FIELD` | LOW | STYLE | `SingularFieldCheck` |
| `pl-0227-too-many-fields` | `MANY_FIELDS` | LOW | STYLE | `TooManyFieldsCheck` |
| `pl-0228-too-many-methods` | `MANY_METHODS` | LOW | STYLE | `TooManyMethodsCheck` |
| `pl-0229-missing-serial-version-uid` | `MISSING_SERIAL_VERSION_UID` | MEDIUM | BUG | `MissingSerialVersionUIDCheck` |
| `pl-0229-use-varargs` | `VARARGS` | MEDIUM | BAD_PRACTICE | `UseVarargsCheck` |
| `pl-0231-more-than-one-logger` | `THAN_ONE_LOGGER` | MEDIUM | BAD_PRACTICE | `MoreThanOneLoggerCheck` |
| `pl-0233-proper-logger` | `PROPER_LOGGER` | LOW | NAMING | `ProperLoggerCheck` |
| `pl-0234-no-package` | `PACKAGE` | LOW | STYLE | `NoPackageCheck` |
| `pl-0236-non-case-label` | `CASE_LABEL_IN_SWITCH_STATEMENT` | MEDIUM | BUG | `NonCaseLabelInSwitchStatementCheck` |
| `pl-0249-method-too-long` | `METHOD_TOO_LONG` | LOW | STYLE | `MethodTooLongCheck` |
| `pl-0250-switch-few` | `SWITCH_FEW_CASES` | LOW | STYLE | `SwitchWithTooFewCasesCheck` |
| `pl-0252-loop-breaks` | `MULTIPLE_BREAKS` | LOW | STYLE | `LoopsWithMultipleBreaksCheck` |
| `pl-0255-paren-method` | `METHOD_CALL` | LOW | STYLE | `UnnecessaryParenthesesMethodCallsCheck` |
| `pl-0256-redundant-method` | `REDUNDANT_METHOD_CALL` | LOW | STYLE | `RedundantMethodCallCheck` |
| `pl-0258-bool-if` | `BOOLEAN_LITERAL_IF` | MEDIUM | BUG | `BooleanLiteralInIfCheck` |
| `pl-0259-reduce-visibility` | `REDUCE_VISIBILITY` | LOW | STYLE | `ReduceVisibilityCheck` |
| `pl-0260-bc-unconfirmed-cast` | `UNCONFIRMED_CAST` | MEDIUM | BUG | `UnconfirmedCastCheck` |
| `pl-0262-bit-and` | `AND` | MEDIUM | BUG | `BitwiseAndCheck` |
| `pl-0263-bit-signed-check` | `SIGNED_CHECK` | MEDIUM | BUG | `SignedBitCheckCheck` |
| `pl-0264-bsk-serializable-subclass` | `SERIALIZABLE_SUBCLASS` | LOW | BAD_PRACTICE | `SerializableSubclassCheck` |
| `pl-0265-ei-expose-rep2` | `EXPOSE_REP2` | MEDIUM | BUG | `ExposeMutableRepStorageCheck` |
| `pl-0266-me-mutable-enum` | `MUTABLE_ENUM_FIELD` | LOW | BAD_PRACTICE | `MutableEnumFieldCheck` |
| `pl-0267-st-write-static-from-instance` | `WRITE_TO_STATIC_FROM_INSTANCE_METHOD` | MEDIUM | BUG | `StaticWriteFromInstanceCheck` |
| `pl-0268-eq-self-no-object` | `SELF_NO_OBJECT` | HIGH | BUG | `EqualsNoObjectCheck` |
| `pl-0269-se-bad-field` | `BAD_FIELD` | MEDIUM | BUG | `NonSerializableFieldCheck` |
| `pl-0270-bit-or` | `OR` | MEDIUM | BUG | `BitwiseOrCheck` |
| `pl-0271-tab-character` | `CHARACTER` | LOW | STYLE | `TabCharacterCheck` |
| `pl-0274-deprecated-annotation` | `DEPRECATED_ANNOTATION` | LOW | STYLE | `DeprecatedAnnotationCheck` |
| `pl-0277-nested-switch` | `NESTED_SWITCH` | LOW | STYLE | `NestedSwitchCheck` |
| `pl-0278-one-statement-per-line` | `STATEMENT_PER_LINE` | LOW | STYLE | `OneStatementPerLineCheck` |
| `pl-0279-lambda-body-length` | `LAMBDA_BODY_LENGTH` | LOW | STYLE | `LambdaBodyLengthCheck` |
| `pl-0282-synchronized-lock` | `SYNCHRONIZED_LOCK` | MEDIUM | BUG | `SynchronizedLockCheck` |
| `pl-0284-switch-default-last` | `SWITCH_DEFAULT_LAST` | LOW | STYLE | `SwitchDefaultLastCheck` |
| `pl-0285-math-op-literal` | `OP_LITERAL` | LOW | STYLE | `MathematicalOperationCheck` |
| `pl-0287-infinite-loop` | `INFINITE_LOOP` | LOW | STYLE | `InfiniteLoopCheck` |
| `pl-0289-no-long-literal-suffix` | `LITERAL_SUFFIX` | LOW | STYLE | `NoLongLiteralCheck` |
| `pl-0290-optional-get` | `OPTIONAL_GET` | MEDIUM | BUG | `OptionalGetCheck` |
| `pl-0291-no-log-statement` | `LOG_AT_DISABLED_LEVEL` | LOW | PERFORMANCE | `NoLogStatementCheck` |
| `pl-0292-printf-format` | `PRINTF_FORMAT` | LOW | STYLE | `PrintfFormatCheck` |
| `pl-0293-locale-root` | `LOCALE_ROOT` | LOW | STYLE | `LocaleRootCheck` |
| `pl-0295-regex-group` | `GROUP` | LOW | STYLE | `RegexGroupCheck` |
| `pl-0298-ec-incompatible` | `INCOMPATIBLE_TYPES` | MEDIUM | BUG | `EcIncompatibleTypesCheck` |
| `pl-0299-ec-unrelated` | `UNRELATED_TYPES` | LOW | STYLE | `EcUnrelatedTypesCheck` |
| `pl-0301-esync-empty` | `EMPTY_SYNC` | LOW | STYLE | `EsyncEmptySyncCheck` |
| `pl-0302-fe-floating-point` | `FLOATING_POINT` | MEDIUM | BUG | `FeFloatingPointCheck` |
| `pl-0303-fi-useless-block` | `USELESS_BLOCK` | LOW | STYLE | `FiUselessBlockCheck` |
| `pl-0304-ic-superclass-generic` | `SUPERCLASS_GENERIC` | MEDIUM | BUG | `IcSuperclassGenericCheck` |
| `pl-0305-icast-int-mult-long` | `INTEGER_MULTIPLY_CAST_TO_LONG` | MEDIUM | BUG | `IcastIntegerMultiplyCastToLongCheck` |
| `pl-0306-im-average` | `AVERAGE_COMPUTATION` | MEDIUM | BUG | `ImAverageComputationCheck` |
| `pl-0307-im-bad-check-odd` | `BAD_CHECK_FOR_ODD` | MEDIUM | BUG | `ImBadCheckForOddCheck` |
| `pl-0308-io-resource-both` | `RESOURCE_USED_AS_BOTH_INPUT_AND_OUTPUT` | MEDIUM | BUG | `IoResourceBothCheck` |
| `pl-0309-mf-class-masks-field` | `CLASS_MASKS_FIELD` | LOW | STYLE | `MfClassMasksFieldCheck` |
| `pl-0310-mf-method-masks-field` | `METHOD_MASKS_FIELD` | LOW | STYLE | `MfMethodMasksFieldCheck` |
| `pl-0311-nm-confusing` | `CONFUSING_INSPECTION` | LOW | NAMING | `NmConfusingInspectionCheck` |
| `pl-0312-pz-reuse-entry` | `REUSE_ENTRY_OBJECTS` | MEDIUM | BUG | `PzReuseEntryCheck` |
| `pl-0313-qba-boolean-assign` | `QUESTIONABLE_BOOLEAN_ASSIGNMENT` | MEDIUM | BUG | `QbaQuestionableBooleanCheck` |
| `pl-0315-rv-remove-repeat-calls` | `REMOVE_REPEAT_METHOD_CALLS` | LOW | STYLE | `RvRemoveRepeatCallsCheck` |
| `pl-0316-rv-return-ignored` | `RETURN_VALUE_IGNORED_BAD_PRACTICE` | MEDIUM | BUG | `RvReturnValueIgnoredCheck` |
| `pl-0317-si-instance-before-final` | `INSTANCE_BEFORE_FINALS_ASSIGNED` | MEDIUM | BUG | `SiInstanceBeforeFinalCheck` |
| `pl-0318-swl-sleep-lock` | `SLEEP_WITH_LOCK_HELD` | MEDIUM | BUG | `SwlSleepWithLockCheck` |
| `pl-0319-uwf-field-not-init` | `FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR` | LOW | STYLE | `UwfFieldNotInitializedCheck` |
| `pl-0320-wmi-wrong-iter` | `WRONG_MAP_ITERATOR` | LOW | PERFORMANCE | `WmiWrongMapIteratorCheck` |
| `pl-0321-il-container-sequencing` | `CONTAINER_SEQUENCING` | MEDIUM | BUG | `IlContainerSequencingCheck` |
| `pl-0327-uc-useless-object` | `USELESS_OBJECT` | LOW | STYLE | `UcUselessObjectCheck` |
| `pl-0328-np-sync-unshared` | `SYNC_INVOKED_ON_UNSHARED_VARIABLE` | MEDIUM | BUG | `NpSyncOnUnsharedVarCheck` |
| `pl-0331-bc-equals-no-hash` | `EQUALS_NO_HASHCODE` | MEDIUM | BUG | `BcEqualsNoHashcodeCallCheck` |
| `pl-0333-ec-no-read` | `NO_READ_IF_EQUAL_TO_WRITE` | MEDIUM | BUG | `EcNoReadIfEqualToWriteCheck` |
| `pl-0334-ec-unrelated-iface` | `UNRELATED_INTERFACES` | LOW | STYLE | `EcUnrelatedInterfacesCheck` |
| `pl-0335-ec-null-vararg` | `NULL_ARG_TO_VARARG_METHOD` | MEDIUM | BUG | `EcNullArgToVarargCheck` |
| `pl-0337-nm-same-name-super` | `SAME_SIMPLE_NAME_AS_SUPERCLASS` | LOW | NAMING | `NmSameSimpleNameAsSuperCheck` |
| `pl-0338-he-hashcode-signature` | `SIGNATURE_DECLARES_HASHCODE_OF_OBJECT` | HIGH | BUG | `HeSignatureDeclaresHashcodeOfObjectCheck` |
| `pl-0340-dmi-bad-month` | `BAD_MONTH` | LOW | STYLE | `DmiBadMonthCheck` |
| `pl-0342-dmi-done` | `DONE` | LOW | STYLE | `DmiDoneCheck` |
| `pl-0343-dmi-useless-method` | `USELESS_METHOD` | LOW | STYLE | `DmiUselessMethodCheck` |
| `pl-0344-iil-deref-readline` | `DEREF_OF_READLINE_VALUE` | MEDIUM | BUG | `IilDereOfReadlineCheck` |
| `pl-0401-atclause-order` | `ATCLAUSE_ORDER` | LOW | STYLE | `AtclauseOrderCheck` |
| `pl-0403-avoid-static-import` | `STATIC_IMPORT` | LOW | STYLE | `AvoidStaticImportCheck` |
| `pl-0406-equals-hashcode` | `EQUALS_HASHCODE` | MEDIUM | BUG | `EqualsHashCodeCheck` |
| `pl-0407-final-class` | `CLASS` | LOW | STYLE | `FinalClassCheck` |
| `pl-0409-final-parameters` | `PARAMETERS` | LOW | STYLE | `FinalParametersCheck` |
| `pl-0410-hide-utility-class-ctor` | `UTILITY_CLASS_CTOR` | LOW | STYLE | `HideUtilityClassConstructorCheck` |
| `pl-0412-inner-assignment` | `ASSIGNMENT` | LOW | STYLE | `InnerAssignmentCheck` |
| `pl-0413-left-curly` | `CURLY` | LOW | STYLE | `LeftCurlyCheck` |
| `pl-0415-missing-switch-default` | `MISSING_SWITCH_DEFAULT` | LOW | STYLE | `MissingSwitchDefaultCheck` |
| `pl-0416-modifier-order` | `MODIFIER_ORDER` | LOW | STYLE | `ModifierOrderCheck` |
| `pl-0417-multiple-var-decls` | `MULTIPLE_VARIABLE_DECLARATIONS` | LOW | STYLE | `MultipleVariableDeclarationsCheck` |
| `pl-0418-nested-for-depth` | `NESTED_FOR_DEPTH` | LOW | STYLE | `NestedForDepthCheck` |
| `pl-0419-nested-if-depth` | `NESTED_IF_DEPTH` | LOW | STYLE | `NestedIfDepthCheck` |
| `pl-0420-package-declaration` | `PACKAGE_DECLARATION` | LOW | STYLE | `PackageDeclarationCheck` |
| `pl-0423-simplify-bool-expr` | `SIMPLIFY_BOOL_EXPR` | LOW | STYLE | `SimplifyBooleanExpressionCheck` |
| `pl-0424-super-clone` | `CLONE` | MEDIUM | BUG | `SuperCloneCheck` |
| `pl-0427-todo-comment` | `COMMENT` | LOW | STYLE | `TodoCommentCheck` |
| `pl-0428-unnecessary-parens` | `UNNECESSARY_PARENS` | LOW | STYLE | `UnnecessaryParenthesesCheck` |
| `pl-0430-unused-private-field` | `UNUSED_PRIVATE_FIELD` | LOW | STYLE | `UnusedPrivateFieldCheck` |
| `pl-0436-cast-arithmetic-operand` | `ARITHMETIC_OPERAND` | LOW | STYLE | `CastArithmeticOperandCheck` |
| `pl-0437-child-class-shadow-field` | `CLASS_SHADOW_FIELD` | MEDIUM | BUG | `ChildClassShadowFieldCheck` |
| `pl-0442-diamond-operator` | `DIAMOND_OPERATOR` | LOW | STYLE | `DiamondOperatorCheck` |
| `pl-0444-equals-arg-type` | `EQUALS_ARG_TYPE` | MEDIUM | BUG | `EqualsArgumentTypeCheck` |
| `pl-0445-for-loop-false-condition` | `LOOP_FALSE_CONDITION` | LOW | BUG | `ForLoopFalseConditionCheck` |
| `pl-0448-lambda-param-shadowing` | `LAMBDA_PARAM_SHADOWING` | LOW | STYLE | `LambdaParameterShadowingCheck` |
| `pl-0449-loggers-declaration` | `LOGGERS_DECLARATION` | LOW | STYLE | `LoggersDeclarationCheck` |
| `pl-0451-method-too-complex` | `METHOD_TOO_COMPLEX` | MEDIUM | BUG | `MethodTooComplexCheck` |
| `pl-0454-package-info` | `PACKAGE_INFO` | LOW | STYLE | `PackageInfoCheck` |
| `pl-0455-printf-fail` | `PRINTF_FAIL` | LOW | STYLE | `PrintfFailCheck` |
| `pl-0458-relational-symmetry` | `RELATIONAL_SYMMETRY` | LOW | STYLE | `RelationalOperatorSymmetryCheck` |
| `pl-0459-standard-functional` | `STANDARD_FUNCTIONAL` | LOW | STYLE | `StandardFunctionalInterfaceCheck` |
| `pl-0461-uppercase-suffix` | `UPPERCASE_SUFFIX` | LOW | STYLE | `UppercaseSuffixCheck` |
| `pl-0462-annotation-table` | `ANNOTATION_TABLE` | LOW | STYLE | `AnnoTblCheck` |
| `pl-0464-avoid-nested-blocks` | `NESTED_BLOCKS` | LOW | STYLE | `AvoidNestedBlocksCheck` |
| `pl-0468-ctor-parameter-count` | `PARAMETER_COUNT` | LOW | STYLE | `CtorParameterCountCheck` |
| `pl-0470-explicit-initialization` | `EXPLICIT_INITIALIZATION` | LOW | STYLE | `ExplicitInitializationCheck` |
| `pl-0471-fallthrough` | `FALLTHROUGH` | LOW | STYLE | `FallThroughCheck` |
| `pl-0477-javadoc-type-strict` | `JAVADOC_TYPE_STRICT` | LOW | STYLE | `JavadocTypeCheck` |
| `pl-0487-outer-type-number` | `TYPE_NUMBER` | LOW | STYLE | `OuterTypeNumberCheck` |
| `pl-0492-upper-ell` | `ELL` | LOW | STYLE | `UpperEllCheck` |
| `pl-0498-bad-odd-check` | `ODD_CHECK` | LOW | BUG | `BadCheckForOddCheck` |
| `pl-0501-dmi-random` | `RANDOM` | LOW | BUG | `DmiRandomCheck` |
| `pl-0502-enum-abstract-method` | `ABSTRACT_METHOD` | LOW | BUG | `EnumAbstractMethodCheck` |
| `pl-0504-field-self-assignment` | `SELF_ASSIGNMENT` | LOW | BUG | `FieldSelfAssignmentCheck` |
| `pl-0506-incompatible-mask` | `INCOMPATIBLE_MASK` | LOW | BUG | `IncompatibleMaskCheck` |
| `pl-0508-iterator-idiomatic` | `ITERATOR_IDIOMATIC` | LOW | STYLE | `IteratorIdiomaticCheck` |
| `pl-0520-public-field-enum` | `PUBLIC_FIELD_ENUM` | LOW | BUG | `PublicProtectedFieldInEnumCheck` |
| `pl-0524-simple-date-format` | `SIMPLE_DATE_FORMAT` | LOW | BUG | `SimpleDateFormatWithoutLocaleCheck` |
| `pl-0525-start-greater-end` | `GREATER_END` | LOW | BUG | `StartWhereGreaterThanEndCheck` |
| `pl-0533-protected-field-final` | `PROTECTED_FIELD_FINAL` | LOW | STYLE | `AvoidProtectedFieldInFinalClassCheck` |
| `pl-0534-protected-method-final` | `PROTECTED_METHOD_FINAL` | LOW | STYLE | `AvoidProtectedMethodInFinalClassCheck` |
| `pl-0538-declaration-order` | `DECLARATION_ORDER` | LOW | STYLE | `DeclarationOrderCheck` |
| `pl-0540-empty-line-separator-strict` | `LINE_SEPARATOR_STRICT` | LOW | STYLE | `EmptyLineSeparatorCheck` |
| `pl-0541-empty-statement` | `STATEMENT` | LOW | STYLE | `EmptyStatementCheck` |
| `pl-0555-avoid-enum` | `ENUM` | LOW | STYLE | `AvoidEnumsCheck` |
| `pl-0557-literals-in-if` | `LITERALS_IN_IF` | LOW | STYLE | `AvoidLiteralsInIfConditionCheck` |
| `pl-0566-dataflow-anomaly` | `DATAFLOW_ANOMALY` | LOW | STYLE | `DataflowAnomalyAnalysisCheck` |
| `pl-0567-default-package` | `DEFAULT_PACKAGE` | LOW | STYLE | `DefaultPackageCheck` |
| `pl-0572-empty-static-init` | `STATIC_INIT` | LOW | STYLE | `EmptyStaticInitializerCheck` |
| `pl-0574-idempotent-operations` | `IDEMPOTENT_OPERATIONS` | LOW | STYLE | `IdempotentOperationsCheck` |
| `pl-0575-immutable-field-strict` | `IMMUTABLE_FIELD_STRICT` | LOW | STYLE | `ImmutableFieldCheck` |
| `pl-0576-instantiation-to-class` | `INSTANTIATION_TO_CLASS` | LOW | STYLE | `InstantiationToGetClassCheck` |
| `pl-0586-package-annotation` | `PACKAGE_ANNOTATION` | LOW | STYLE | `PackageAnnotationCheck` |
| `pl-0588-suppress-warnings` | `SUPPRESS_WARNINGS` | LOW | STYLE | `SuppressWarningsCheck` |
| `pl-0595-import-control` | `IMPORT_CONTROL` | LOW | STYLE | `ImportControlCheck` |
| `pl-0598-anon-inner-length` | `INNER_LENGTH` | LOW | STYLE | `AnonInnerLengthCheck` |
| `pl-0608--self-assignment` | `ASSIGNMENT` | MEDIUM | CORRECTNESS | `SelfAssignmentCheck` |
| `pl-0609--param-hides-field` | `HIDES_FIELD` | MEDIUM | CORRECTNESS | `ParameterHidesFieldCheck` |
| `pl-0610--collapsible-if` | `COLLAPSIBLE_IF` | LOW | STYLE | `CollapsibleIfCheck` |
| `pl-0611--ignored-result` | `IGNORED_RESULT` | MEDIUM | CORRECTNESS | `ResultOfMethodCallIgnoredCheck` |
| `pl-0612--ambiguous-precedence` | `AMBIGUOUS_PRECEDENCE` | MEDIUM | CORRECTNESS | `AmbiguousOperatorPrecedenceCheck` |
| `pl-0614--test-no-assertion` | `NO_ASSERTION` | MEDIUM | CORRECTNESS | `TestWithoutAssertionCheck` |
| `pl-0700-missing-deprecated` | `MISSING_DEPRECATED` | MEDIUM | BUG | `MissingDeprecatedCheck` |
| `pl-0705-collapsible-if-statements` | `COLLAPSIBLE_IF_STATEMENTS` | LOW | STYLE | `CollapsibleIfStatementsCheck` |
| `pl-0709-mc-returns-void` | `MC_RETURNS_VOID` | HIGH | BUG | `MCReturnsVoidCheck` |
| `pl-0709-overload-methods-order` | `OVERLOAD_METHODS_DECLARATION_ORDER` | LOW | STYLE | `OverloadMethodsDeclarationOrderCheck` |
| `pl-0711-non-serializable-class` | `NON_SERIALIZABLE_CLASS` | MEDIUM | BUG | `NonSerializableClassInSerializableContextCheck` |
| `pl-0712-absent-codeblock-in-case` | `ABSENT_CODEBLOCK_IN_CASE` | MEDIUM | BUG | `AbsentCodeblockInCaseCheck` |
| `pl-0712-assignment-to-non-final-static` | `ASSIGNMENT_TO_NON_FINAL_STATIC` | MEDIUM | BAD_PRACTICE | `AssignmentToNonFinalStaticCheck` |
| `pl-0713-wait-notify-in-loop` | `WAIT_NOTIFY_IN_LOOP` | HIGH | MULTITHREADING | `WaitNotifyCheck` |
| `pl-0715-newline-at-eof` | `NEWLINE_AT_EOF` | LOW | STYLE | `NewlineAtEndOfFileCheck` |
| `pl-0719-line-length` | `LINE_LENGTH` | LOW | STYLE | `LineLengthCheck` |
| `pl-0720-avoid-star-import` | `AVOID_STAR_IMPORT` | LOW | STYLE | `AvoidStarImportCheck` |
| `pl-0721-unused-imports` | `UNUSED_IMPORTS` | LOW | STYLE | `UnusedImportsCheck` |
| `pl-0722-redundant-import` | `REDUNDANT_IMPORT` | LOW | STYLE | `RedundantImportCheck` |
| `pl-0723-system-out-println` | `SYSTEM_OUT_PRINTLN` | LOW | STYLE | `SystemOutPrintlnCheck` |
| `pl-0724-print-stack-trace` | `PRINT_STACK_TRACE` | MEDIUM | BUG | `PrintStackTraceCheck` |
| `pl-0725-empty-block` | `EMPTY_BLOCK` | LOW | STYLE | `EmptyBlockCheck` |
| `pl-0726-magic-number` | `MAGIC_NUMBER` | LOW | STYLE | `MagicNumberCheck` |
| `pl-0729-unnecessary-return` | `UNNECESSARY_RETURN` | LOW | STYLE | `UnnecessaryReturnCheck` |
| `pl-0733-non-static-initializer` | `NON_STATIC_INITIALIZER` | LOW | STYLE | `NonStaticInitializerCheck` |
| `pl-601-annotation-location` | `ANNOTATION_LOCATION` | LOW | STYLE | `AnnotationLocationCheck` |
| `pl-602-annotation-on-same-line` | `ANNOTATION_ON_SAME_LINE` | LOW | STYLE | `AnnotationOnSameLineCheck` |
| `pl-603-avoid-escaped-unicode` | `ESCAPED_UNICODE` | LOW | STYLE | `AvoidEscapedUnicodeCharactersCheck` |
| `pl-604-comments-indentation` | `COMMENTS_INDENTATION` | LOW | STYLE | `CommentsIndentationCheck` |
| `pl-605-file-tab-character` | `TAB_CHARACTER` | LOW | STYLE | `FileTabCharacterCheck` |
| `pl-606-hidden-field` | `HIDDEN_FIELD` | LOW | STYLE | `HiddenFieldCheck` |
| `pl-607-indentation` | `INDENTATION` | LOW | STYLE | `IndentationCheck` |
| `pl-608-javadoc-paragraph` | `JAVADOC_PARAGRAPH` | LOW | STYLE | `JavadocParagraphCheck` |
| `pl-611-missing-javadoc-method` | `MISSING_JAVADOC_METHOD` | LOW | STYLE | `MissingJavadocMethodCheck` |
| `pl-612-missing-javadoc-package` | `MISSING_JAVADOC_PACKAGE` | LOW | STYLE | `MissingJavadocPackageCheck` |
| `pl-613-missing-javadoc-type` | `MISSING_JAVADOC_TYPE` | LOW | STYLE | `MissingJavadocTypeCheck` |
| `pl-616-no-code-in-file` | `CODE_IN_FILE` | LOW | STYLE | `NoCodeInFileCheck` |
| `pl-617-no-enum-trailing-comma` | `ENUM_TRAILING_COMMA` | LOW | STYLE | `NoEnumTrailingCommaCheck` |
| `pl-619-operator-wrap` | `OPERATOR_WRAP` | LOW | STYLE | `OperatorWrapCheck` |
| `pl-621-paren-pad` | `PAD` | LOW | STYLE | `ParenPadCheck` |
| `pl-622-require-this` | `REQUIRE_THIS` | LOW | STYLE | `RequireThisCheck` |
| `pl-623-right-curly` | `CURLY` | LOW | STYLE | `RightCurlyCheck` |
| `pl-624-separator-wrap` | `SEPARATOR_WRAP` | LOW | STYLE | `SeparatorWrapCheck` |
| `pl-625-single-space-separator` | `SINGLE_SPACE_SEPARATOR` | LOW | STYLE | `SingleSpaceSeparatorCheck` |
| `pl-626-whitespace-after` | `WHITESPACE_AFTER` | LOW | STYLE | `WhitespaceAfterCheck` |
| `pl-627-whitespace-around` | `WHITESPACE_AROUND` | LOW | STYLE | `WhitespaceAroundCheck` |
| `pl-631-assertion-boundary` | `ASSERTION_BOUNDARY` | LOW | STYLE | `AssertionBoundaryCheck` |
| `pl-632-bad-syntax-in-regex` | `SYNTAX_IN_REGEX` | LOW | STYLE | `BadSyntaxInRegexCheck` |
| `pl-633-badly-overridden-adapter` | `OVERRIDDEN_ADAPTER` | LOW | STYLE | `BadlyOverriddenAdapterCheck` |
| `pl-635-call-to-unsupported-method` | `TO_UNSUPPORTED_METHOD` | LOW | STYLE | `CallToUnsupportedMethodCheck` |
| `pl-636-check-comparator-result` | `COMPARATOR_RESULT` | LOW | STYLE | `CheckTypeOfResultOfComparatorCheck` |
| `pl-638-default-charset` | `DEFAULT_CHARSET` | LOW | STYLE | `DefaultCharsetCheck` |
| `pl-639-do-inside-do-privileged` | `INSIDE_DO_PRIVILEGED` | LOW | STYLE | `DoInsideDoPrivilegedCheck` |
| `pl-640-dont-import-sun` | `IMPORT_SUN` | LOW | STYLE | `DontImportSunCheck` |
| `pl-642-empty-zip-entry` | `ZIP_ENTRY` | LOW | STYLE | `EmptyZipEntryCheck` |
| `pl-643-enum-idiom` | `IDIOM` | LOW | STYLE | `EnumIdiomCheck` |
| `pl-645-malformed-sync` | `MALFORMED_SYNC` | LOW | STYLE | `MalformedSyncCheck` |
| `pl-648-notify-check` | `NOTIFY_CHECK` | LOW | STYLE | `NotifyCheck` |
| `pl-649-objects-equality` | `OBJECTS_EQUALITY` | LOW | STYLE | `ObjectsEqualityCheck` |
| `pl-650-optional-return` | `OPTIONAL_RETURN` | LOW | STYLE | `OptionalReturnCheck` |
| `pl-651-overriding-must-invoke-super` | `OVERRIDING_MUST_INVOKE_SUPER` | LOW | STYLE | `OverridingMethodMustInvokeSuperCheck` |
| `pl-653-public-field-sync-context` | `PUBLIC_FIELD_SYNC_CONTEXT` | LOW | STYLE | `PublicFieldAccessedInSynchronizedContextCheck` |
| `pl-654-public-method-public-field` | `PUBLIC_METHOD_PUBLIC_FIELD` | LOW | STYLE | `PublicMethodsPublicFieldCheck` |
| `pl-655-questionable-boolean-assign` | `QUESTIONABLE_BOOLEAN_ASSIGN` | LOW | STYLE | `QuestionableBooleanAssignmentCheck` |
| `pl-657-serializable-super-ctor` | `SERIALIZABLE_SUPER_CTOR` | LOW | STYLE | `SerializableSuperConstructorCheck` |
| `pl-658-sign-of-addition` | `OF_ADDITION` | LOW | STYLE | `SignOfAdditionCheck` |
| `pl-659-sleep-without-interrupt` | `WITHOUT_INTERRUPT` | LOW | STYLE | `SleepWithoutInterruptCheck` |
| `pl-660-static-method-need-not-sync` | `STATIC_METHOD_NEED_NOT_SYNC` | LOW | STYLE | `StaticMethodNeedNotBeSynchronizedCheck` |
| `pl-661-suspicious-integer-division` | `SUSPICIOUS_INTEGER_DIVISION` | LOW | STYLE | `SuspiciousIntegerDivisionCheck` |
| `pl-663-unnecessary-enum-case` | `UNNECESSARY_ENUM_CASE` | LOW | STYLE | `UnnecessaryEnumCaseCheck` |
| `pl-666-useless-op-immutable` | `USELESS_OP_IMMUTABLE` | LOW | STYLE | `UselessOperationOnImmutableCheck` |
| `pl-886-use-object-clearer-api` | `USE_OBJECT_CLEARER_API` | LOW | STYLE | `UseObjectForClearerAPI` |
| `pl-registry-empty_control_statement` | `EMPTY_CONTROL_STATEMENT` | ? | ? | `EmptyControlStatementCheck` |
| `pl-registry-instanceof` | `INSTANCEOF` | ? | ? | `SuperfluousInstanceofCheck` |
| `pl-registry-system_out` | `SYSTEM_OUT` | ? | ? | `SystemOutCheck` |
| `pl-registry-used_once` | `USED_ONCE` | ? | ? | `RandomUsedOnceCheck` |
| `pl-registry-var_visibility` | `VAR_VISIBILITY` | ? | ? | `ClassVariableVisibilityCheck` |

## 规则详细列表

> 阅读约定：每条规则按 `规则 ID / 短名 / 严重度 / 类型 / 规则类` 列出，并附 ① 规则说明（来自源码 Javadoc）② 与 PMD / SpotBugs / Checkstyle / SonarQube 的等价映射 ③ 可调参数表。

### pl-0330-bc-equals-no-null  —  `EQUALS_NO_NULL_CHECK`

- **规则类**: `org.plumb.style.bug.BcEqualsNoNullCheckCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> equals() doesn't null-check the argument (BC_EQUALS_NO_NULL_CHECK).

**规则约束 / 可调参数**: (无)

### pl-0280-comparator-compare  —  `COMPARATOR_COMPARE`

- **规则类**: `org.plumb.style.bug.ComparatorCompareCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> compare() should return Integer.compare result, not 1/-1.

**规则约束 / 可调参数**: (无)

### pl-0154-compare-objects-with-equals  —  `COMPARE_OBJECTS_WITH_EQUALS`

- **规则类**: `org.plumb.style.bug.CompareObjectsWithEqualsCheck`
- **分类**: `bug`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-595
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use.equals() not == for object comparison.

**规则约束 / 可调参数**: (无)

### pl-0440-compareto-return-value  —  `COMPARETO_RETURN_VALUE`

- **规则类**: `org.plumb.style.bug.CompareToReturnValueCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> compareTo must return -1/0/1.

**规则约束 / 可调参数**: (无)

### pl-0728-covariant-equals  —  `COVARIANT_EQUALS`

- **规则类**: `org.plumb.style.bug.CovariantEqualsCheck`
- **分类**: `bug`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> equals() should accept Object, not a subtype.

**规则约束 / 可调参数**: (无)

### pl-0707-equals-avoid-null  —  `EQUALS_AVOID_NULL`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `allowEqualsOnBoxedPrimitives` | BOOLEAN | `true` | Allow == on boxed primitives (Integer, Long,...) |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `allowEqualsOnBoxedPrimitives` | BOOLEAN | `true` | Allow == on boxed primitives (Integer, Long,...) |

### pl-0573-equals-null  —  `EQUALS_NULL`

- **规则类**: `org.plumb.style.bug.EqualsNullCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> comparing with null using.equals() (should use ==).

**规则约束 / 可调参数**: (无)

### pl-0736-incorrect-return-check  —  `INCORRECT_RETURN_CHECK`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `ignoreEnclosed` | BOOLEAN | `true` | Skip == true inside parentheses (common in ternaries) |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreEnclosed` | BOOLEAN | `true` | Skip == true inside parentheses (common in ternaries) |

### pl-0507-invalid-compareto-object  —  `INVALID_COMPARETO_OBJECT`

- **规则类**: `org.plumb.style.bug.InvalidCompareToObjectCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> compareTo() comparing against Object.

**规则约束 / 可调参数**: (无)

### pl-0159-jumbled-incrementer  —  `JUMBLED_INCREMENTER`

- **规则类**: `org.plumb.style.bug.JumbledIncrementerCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> for-loop incrementer is unusual (non-1 step or wrong direction).

**规则约束 / 可调参数**: (无)

### pl-0286-method-signature  —  `METHOD_SIGNATURE`

- **规则类**: `org.plumb.style.bug.MethodSignatureCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> too many same-typed parameters — use a parameter object.

**规则约束 / 可调参数**: (无)

### pl-0297-np-always-null  —  `ALWAYS_NULL`

- **规则类**: `org.plumb.style.bug.NpAlwaysNullCheck`
- **分类**: `bug`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-476
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method always returns null (NP_ALWAYS_NULL) — fix or annotate @Nullable.

**规则约束 / 可调参数**: (无)

### pl-0329-np-load-null  —  `LOAD_OF_KNOWN_NULL_VALUE`

- **规则类**: `org.plumb.style.bug.NpLoadOfKnownNullCheck`
- **分类**: `bug`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-476
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> loading a value known to be null (NP_LOAD_OF_KNOWN_NULL_VALUE).

**规则约束 / 可调参数**: (无)

### pl-0261-rcn-redundant-nullcheck  —  `REDUNDANT_NULLCHECK`

- **规则类**: `org.plumb.style.bug.RedundantNullCheckCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> redundant null check — variable is already known non-null (RCN_REDUNDANT_NULLCHECK).

**规则约束 / 可调参数**: (无)

### pl-656-redundant-null-check-null  —  `REDUNDANT_NULL_CHECK_NULL`

- **规则类**: `org.plumb.style.bug.RedundantNullCheckOfNullValueCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Redundant null check of null value.

**规则约束 / 可调参数**: (无)

### pl-0422-return-count  —  `RETURN_COUNT`

- **规则类**: `org.plumb.style.bug.ReturnCountCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method should have a single return (heuristic).

**规则约束 / 可调参数**: (无)

### pl-0314-rv-negating-compareto  —  `NEGATING_RESULT_OF_COMPARETO`

- **规则类**: `org.plumb.style.bug.RvNegatingCompareToCheck`
- **分类**: `bug`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-682
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> -x.compareTo(y) is unsafe — invert the args (RV_NEGATING_RESULT_OF_COMPARETO).

**规则约束 / 可调参数**: (无)

### pl-662-unnecessary-check-not-null  —  `UNNECESSARY_CHECK_NOT_NULL`

- **规则类**: `org.plumb.style.bug.UnnecessaryCheckForNotNullCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Unnecessary check for not null.

**规则约束 / 可调参数**: (无)

### pl-665-unused-null-check-equals  —  `UNUSED_NULL_CHECK_EQUALS`

- **规则类**: `org.plumb.style.bug.UnusedNullCheckInEqualsCheck`
- **分类**: `bug`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Unused null check in equals.

**规则约束 / 可调参数**: (无)

### pl-0597-abstract-coupling  —  `ABSTRACT_COUPLING`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `max` | INT | `20` | Maximum distinct instantiable types per class |
> | `excludeLocalVariables` | BOOLEAN | `false` | Only count types stored in fields |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `max` | INT | `20` | Maximum distinct instantiable types per class |
| `excludeLocalVariables` | BOOLEAN | `false` | Only count types stored in fields |

### pl-0160-abstract-no-method  —  `ABSTRACT_CLASS_WITHOUT_ABSTRACT_METHOD`

- **规则类**: `org.plumb.style.design.AbstractClassWithoutAbstractMethodCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> abstract class has no abstract members.

**规则约束 / 可调参数**: (无)

### pl-0599-bool-expr-complexity  —  `EXPR_COMPLEXITY`

- **规则类**: `org.plumb.style.design.BooleanExpressionComplexityCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> boolean expression too complex (default 3).

**规则约束 / 可调参数**: (无)

### pl-0466-class-fanout-complexity  —  `FANOUT_COMPLEXITY`

- **规则类**: `org.plumb.style.design.ClassFanOutComplexityCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class has too many other class references (fan-out).

**规则约束 / 可调参数**: (无)

### pl-0272-inheritance-depth  —  `INHERITANCE_DEPTH`

- **规则类**: `org.plumb.style.design.ClassInheritanceDepthCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> inheritance tree is too deep (, default 5).

**规则约束 / 可调参数**: (无)

### pl-637-confused-inheritance  —  `CONFUSED_INHERITANCE`

- **规则类**: `org.plumb.style.design.ConfusedInheritanceCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Confused inheritance.

**规则约束 / 可调参数**: (无)

### pl-0441-ctor-calling-overridable  —  `CTOR_CALLING_OVERRIDABLE`

- **规则类**: `org.plumb.style.design.ConstructorCallingOverridableCheck`
- **分类**: `design`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-688
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> constructor calls overridable method.

**规则约束 / 可调参数**: (无)

### pl-0239-coupling  —  `COUPLING_BETWEEN_OBJECTS`

- **规则类**: `org.plumb.style.design.CouplingBetweenObjectsCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class is coupled to too many other classes (limit 20).

**规则约束 / 可调参数**: (无)

### pl-0029-cyclomatic-complexity  —  `CYCLOMATIC_COMPLEXITY`

- **规则类**: `org.plumb.style.design.CyclomaticComplexityCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> cyclomatic complexity exceeds 10.

**规则约束 / 可调参数**: (无)

### pl-0240-data-class  —  `CLASS`

- **规则类**: `org.plumb.style.design.DataClassCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> data class — mostly fields, expose behavior.

**规则约束 / 可调参数**: (无)

### pl-0405-design-for-extension  —  `DESIGN_FOR_EXTENSION`

- **规则类**: `org.plumb.style.design.DesignForExtensionCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> public method should be abstract, final, or private in non-final class.

**规则约束 / 可调参数**: (无)

### pl-0238-uncommented-empty  —  `UNCOMMENTED_EMPTY_METHOD`

- **规则类**: `org.plumb.style.design.EmptyMethodInAbstractClass2Check`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty method has no comment explaining why.

**规则约束 / 可调参数**: (无)

### pl-0727-java-ncss  —  `JAVA_NCSS`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `methodMaximum` | INT | `60` | Maximum number of statements in a method |
> | `classMaximum` | INT | `1500` | Maximum number of statements in a class |
> | `constructorMaximum` | INT | `80` | Maximum number of statements in a constructor |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `methodMaximum` | INT | `60` | Maximum number of statements in a method |
| `classMaximum` | INT | `1500` | Maximum number of statements in a class |
| `constructorMaximum` | INT | `80` | Maximum number of statements in a constructor |

### pl-0058-law-of-demeter  —  `OF_DEMETER`

- **规则类**: `org.plumb.style.design.LawOfDemeterCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **CWE**: CWE-1060
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method chain exceeds LoD threshold (use a wrapper method instead).

**规则约束 / 可调参数**: (无)

### pl-0732-loose-coupling  —  `LOOSE_COUPLING`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `allowedConcreteTypes` | STRING | `` | Comma-separated list of concrete type names to skip |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `allowedConcreteTypes` | STRING | `` | Comma-separated list of concrete type names to skip |

### pl-0241-loose-coupling  —  `PACKAGE_COUPLING`

- **规则类**: `org.plumb.style.design.LoosePackageCouplingCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class depends on many classes in a foreign package.

**规则约束 / 可调参数**: (无)

### pl-0581-method-count  —  `METHOD_COUNT`

- **规则类**: `org.plumb.style.design.MethodCountCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> too many methods in class (default 100).

**规则约束 / 可调参数**: (无)

### pl-0456-public-ctor-abstract  —  `PUBLIC_CTOR_ABSTRACT`

- **规则类**: `org.plumb.style.design.PublicConstructorInAbstractClassCheck`
- **分类**: `design`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> abstract class should not have public constructor.

**规则约束 / 可调参数**: (无)

### pl-0497-assign-catch-var  —  `ASSIGN_CATCH_VAR`

- **规则类**: `org.plumb.style.error.AssignmentToCatchBlockParameterCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> catch block parameter should not be reassigned.

**规则约束 / 可调参数**: (无)

### pl-0554-catch-throwable  —  `AVOID_THROWABLE`

- **规则类**: `org.plumb.style.error.AvoidCatchingThrowableCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> catching Throwable is too broad.

**规则约束 / 可调参数**: (无)

### pl-0122-instanceof-in-catch  —  `INSTANCEOF_IN_CATCH`

- **规则类**: `org.plumb.style.error.AvoidInstanceofChecksInCatchClauseCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> instanceof in catch — use multiple catch clauses.

**规则约束 / 可调参数**: (无)

### pl-0123-lose-exception-info  —  `LOSING_EXCEPTION_INFORMATION`

- **规则类**: `org.plumb.style.error.AvoidLosingExceptionInformationCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-209
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> catch block throws new exception without including original.

**规则约束 / 可调参数**: (无)

### pl-0257-catch-log  —  `EXCEPTION_LOG`

- **规则类**: `org.plumb.style.error.CatchExceptionForLoggingCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-390
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> catch block does not use the exception.

**规则约束 / 可调参数**: (无)

### pl-registry-catch_generic  —  `CATCH_GENERIC`

- **规则类**: `org.plumb.style.error.CatchGenericCheck`
- **分类**: `error`
- **严重度**: ?
- **类型**: ?
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Catching generic Exception or Throwable.

**规则约束 / 可调参数**: (无)

### bu-0803-catch-nullpointer-exception  —  `NPE`

- **规则类**: `org.plumb.style.error.CatchNpeCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-396
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Avoid catching NullPointerException (likely swallows a real bug).

**规则约束 / 可调参数**: (无)

### pl-0435-catch-of-throwable  —  `OF_THROWABLE`

- **规则类**: `org.plumb.style.error.CatchOfThrowableCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-396
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> catching Throwable/Error is too broad.

**规则约束 / 可调参数**: (无)

### pl-0202-catch-throwable  —  `THROWABLE`

- **规则类**: `org.plumb.style.error.CatchThrowableCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-396
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid catching Throwable / Error.

**规则约束 / 可调参数**: (无)

### pl-0074-do-not-extend-error  —  `NOT_EXTEND_ERROR`

- **规则类**: `org.plumb.style.error.DoNotExtendJavaLangErrorCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> do not extend java.lang.Error.

**规则约束 / 可调参数**: (无)

### pl-641-dropped-exception  —  `DROPPED_EXCEPTION`

- **规则类**: `org.plumb.style.error.DroppedExceptionCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Exception is dropped.

**规则约束 / 可调参数**: (无)

### pl-0539-empty-catch-block-strict  —  `CATCH_BLOCK_STRICT`

- **规则类**: `org.plumb.style.error.EmptyCatchBlockCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty catch block — strict.

**规则约束 / 可调参数**: (无)

### pl-0171-empty-finalizer  —  `FINALIZER`

- **规则类**: `org.plumb.style.error.EmptyFinalizerCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty finalize() — remove the method (finalizers are deprecated).

**规则约束 / 可调参数**: (无)

### pl-0030-explicit-finalize  —  `EXPLICIT_FINALIZE`

- **规则类**: `org.plumb.style.error.ExplicitFinalizeCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-586
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid explicit calls to finalize() (use try-with-resources instead).

**规则约束 / 可调参数**: (无)

### pl-0031-finalize-only-overridden  —  `FINALIZE_ONLY_OVERRIDDEN`

- **规则类**: `org.plumb.style.error.FinalizeOnlyOverriddenCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **CWE**: CWE-586
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> finalize() should be declared final or the class should be final.

**规则约束 / 可调参数**: (无)

### pl-0175-finalize-protected  —  `FINALIZE_SHOULD_BE_PROTECTED`

- **规则类**: `org.plumb.style.error.FinalizeShouldBeProtectedCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> finalize() should be protected.

**规则约束 / 可调参数**: (无)

### pl-0032-finalize-without-super  —  `FINALIZE_WITHOUT_SUPER`

- **规则类**: `org.plumb.style.error.FinalizeWithoutSuperCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-586
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> finalize() should call super.finalize().

**规则约束 / 可调参数**: (无)

### pl-0273-generic-exception  —  `GENERIC_EXCEPTION`

- **规则类**: `org.plumb.style.error.GenericExceptionCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> throw a specific exception, not Exception/Throwable.

**规则约束 / 可调参数**: (无)

### pl-0178-identical-catch  —  `IDENTICAL_CATCH_BRANCHES`

- **规则类**: `org.plumb.style.error.IdenticalCatchBranchesCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> identical catch branches — merge with multi-catch.

**规则约束 / 可调参数**: (无)

### pl-0411-illegal-catch  —  `ILLEGAL_CATCH`

- **规则类**: `org.plumb.style.error.IllegalCatchCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> catching Exception/Throwable is too broad.

**规则约束 / 可调参数**: (无)

### pl-0447-iterator-next-exception  —  `ITERATOR_NEXT_EXCEPTION`

- **规则类**: `org.plumb.style.error.IteratorNextExceptionCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: BUG
- **CWE**: CWE-754
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> iterator.next() without hasNext() check.

**规则约束 / 可调参数**: (无)

### pl-0197-nested-try  —  `NESTED_TRY_CATCH`

- **规则类**: `org.plumb.style.error.NestedTryCatchCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> nested try-catch — extract the inner try to a method.

**规则约束 / 可调参数**: (无)

### pl-0584-no-finalizer  —  `FINALIZER`

- **规则类**: `org.plumb.style.error.NoFinalizerCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> do not override finalize().

**规则约束 / 可调参数**: (无)

### pl-0276-suppression-marker  —  `SUPPRESSION_MARKER`

- **规则类**: `org.plumb.style.error.NosonarCommentCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Suppression-marker comment suppresses a finding — review and add a reason.

**规则约束 / 可调参数**: (无)

### pl-0453-object-finalize-overload  —  `OBJECT_FINALIZE_OVERLOAD`

- **规则类**: `org.plumb.style.error.ObjectFinalizeOverloadCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> finalize() should not be overloaded.

**规则约束 / 可调参数**: (无)

### pl-0117-parse-error  —  `ERROR_LOGGING`

- **规则类**: `org.plumb.style.error.ParseErrorLoggingCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-390
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> ParseException caught and silently swallowed.

**规则约束 / 可调参数**: (无)

### pl-0253-unchecked-throws  —  `PUBLIC_METHOD_UNCHECKED_THROWS`

- **规则类**: `org.plumb.style.error.PublicMethodNoUncheckedExceptionCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> public method declares unchecked exception in throws.

**规则约束 / 可调参数**: (无)

### pl-0457-raw-exception  —  `EXCEPTION`

- **规则类**: `org.plumb.style.error.RawExceptionCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> throwing raw Exception/RuntimeException.

**规则约束 / 可调参数**: (无)

### pl-0425-super-finalize  —  `FINALIZE`

- **规则类**: `org.plumb.style.error.SuperFinalizeCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-568
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> finalize() must call super.finalize().

**规则约束 / 可调参数**: (无)

### bu-1106-throwable-not-thrown  —  `THROWABLE_NOT_THROWN`

- **规则类**: `org.plumb.style.error.ThrowableNotThrownCheck`
- **分类**: `error`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-390
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Caught exception is silently swallowed (re-throw or log instead).

**规则约束 / 可调参数**: (无)

### pl-0200-throw-finally  —  `IN_FINALLY`

- **规则类**: `org.plumb.style.error.ThrowInFinallyCheck`
- **分类**: `error`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-584
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> throw in finally — swallows original exception.

**规则约束 / 可调参数**: (无)

### pl-0426-throws-count  —  `THROWS_COUNT`

- **规则类**: `org.plumb.style.error.ThrowsCountCheck`
- **分类**: `error`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method should declare at most N checked exceptions (default 4).

**规则约束 / 可调参数**: (无)

### pl-0714-unused-throws  —  `UNUSED_THROWS`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `ignoreUnchecked` | BOOLEAN | `true` | Skip RuntimeException and subclasses |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreUnchecked` | BOOLEAN | `true` | Skip RuntimeException and subclasses |

### pl-0704-abstract  —  `ABSTRACT`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `onlyEmptyBodies` | BOOLEAN | `true` | Only flag abstract methods with empty/stub bodies |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `onlyEmptyBodies` | BOOLEAN | `true` | Only flag abstract methods with empty/stub bodies |

### pl-0551-abstract-naming  —  `ABSTRACT_NAMING`

- **规则类**: `org.plumb.style.naming.AbstractNamingCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> abstract class should be named starting with Abstract (heuristic).

**规则约束 / 可调参数**: (无)

### pl-0552-array-naming-strict  —  `NAMING_STRICT`

- **规则类**: `org.plumb.style.naming.ArrayNamingCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **PMD**: ArrayNamingStyle

**规则说明**

> array name should be plural or end with 's'.

**规则约束 / 可调参数**: (无)

### pl-0110-at-least-one-constructor  —  `AT_LEAST_ONE_CONSTRUCTOR`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `ignoreInterfaceDeclarations` | BOOLEAN | `true` | Skip interface declarations |
> | `ignoreEnumDeclarations` | BOOLEAN | `true` | Skip enum declarations |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreInterfaceDeclarations` | BOOLEAN | `true` | Skip interface declarations |
| `ignoreEnumDeclarations` | BOOLEAN | `true` | Skip enum declarations |

### pl-0054-avoid-final-local  —  `FINAL_LOCAL`

- **规则类**: `org.plumb.style.naming.AvoidFinalLocalVariableCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid final on local variables (use only when capturing a contract).

**规则约束 / 可调参数**: (无)

### pl-0432-bad-abstract-class-name  —  `ABSTRACT_CLASS_NAME`

- **规则类**: `org.plumb.style.naming.BadAbstractClassNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> abstract class name should start with 'Abstract'.

**规则约束 / 可调参数**: (无)

### pl-0115-bean-serialize  —  `MEMBERS_SHOULD_SERIALIZE`

- **规则类**: `org.plumb.style.naming.BeanMembersShouldSerializeCheck`
- **分类**: `naming`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Serializable class field is not marked transient or serialVersionUID.

**规则约束 / 可调参数**: (无)

### pl-0433-boolean-get-method-name  —  `BOOLEAN_GET_METHOD_NAME`

- **规则类**: `org.plumb.style.naming.BooleanGetMethodNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> boolean getter should be 'isX()' or 'hasX()'.

**规则约束 / 可调参数**: (无)

### pl-0467-class-data-abstraction  —  `DATA_ABSTRACTION_COUPLING`

- **规则类**: `org.plumb.style.naming.ClassDataAbstractionCouplingCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class has too many instance fields (data abstraction coupling).

**规则约束 / 可调参数**: (无)

### pl-0251-class-name-long  —  `NAME_TOO_LONG`

- **规则类**: `org.plumb.style.naming.ClassNameTooLongCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class name is too long (limit 30).

**规则约束 / 可调参数**: (无)

### pl-0204-style-class-naming  —  `CLASS`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `format` | STRING | `^[A-Z][a-zA-Z0-9]*$` | Regex the class name must match |
> | `abstractClassFormat` | STRING | `` | Regex for abstract classes (empty = same as format) |
> | `utilityClassFormat` | STRING | `` | Regex for utility classes (empty = same as format) |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `format` | STRING | `^[A-Z][a-zA-Z0-9]*$` | Regex the class name must match |
| `abstractClassFormat` | STRING | `` | Regex for abstract classes (empty = same as format) |
| `utilityClassFormat` | STRING | `` | Regex for utility classes (empty = same as format) |

### pl-0536-class-type-param  —  `TYPE_PARAM`

- **规则类**: `org.plumb.style.naming.ClassTypeParameterNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class type parameter name should be uppercase single letter (T, K, V).

**规则约束 / 可调参数**: (无)

### pl-0438-class-only-private-ctors  —  `ONLY_PRIVATE_CTORS`

- **规则类**: `org.plumb.style.naming.ClassWithOnlyPrivateConstructorsCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class with only private constructors should be final.

**规则约束 / 可调参数**: (无)

### pl-0028-compare-classes  —  `COMPARE_CLASSES`

- **规则类**: `org.plumb.style.naming.CompareClassNamesCheck`
- **分类**: `naming`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> compare Class references with equals() not ==.

**规则约束 / 可调参数**: (无)

### pl-0404-constant-name  —  `CONSTANT_NAME`

- **规则类**: `org.plumb.style.naming.ConstantNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> static final field should be UPPER_SNAKE.

**规则约束 / 可调参数**: (无)

### pl-registry-constant_naming  —  `CONSTANT_NAMING`

- **规则类**: `org.plumb.style.naming.ConstantNamingCheck`
- **分类**: `naming`
- **严重度**: ?
- **类型**: ?
- **外部等价规则**:
  - **PMD**: ConstantNamingConventions
  - **Checkstyle**: ConstantName

**规则说明**

> Static final field should be UPPER_SNAKE_CASE.

**规则约束 / 可调参数**: (无)

### pl-0700-empty-method  —  `EMPTY_METHOD`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `ignoreAbstractMethods` | BOOLEAN | `true` | Skip abstract methods (no body by design) |
> | `ignoreNativeMethods` | BOOLEAN | `true` | Skip native methods (no Java body) |
> | `ignoreMethodsWithOverrideAnnotation` | BOOLEAN | `false` | Skip methods with @Override annotation |
> | `ignorePublicMethods` | BOOLEAN | `false` | Skip public methods |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreAbstractMethods` | BOOLEAN | `true` | Skip abstract methods (no body by design) |
| `ignoreNativeMethods` | BOOLEAN | `true` | Skip native methods (no Java body) |
| `ignoreMethodsWithOverrideAnnotation` | BOOLEAN | `false` | Skip methods with @Override annotation |
| `ignorePublicMethods` | BOOLEAN | `false` | Skip public methods |

### pl-0113-empty-method-in-abstract  —  `UNCOMMENTED_EMPTY_METHOD`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `ignoreMethodsWithOverrideAnnotation` | BOOLEAN | `true` | Skip methods with @Override annotation |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreMethodsWithOverrideAnnotation` | BOOLEAN | `true` | Skip methods with @Override annotation |

### pl-0275-field-naming  —  `NAMING_CONVENTION`

- **规则类**: `org.plumb.style.naming.FieldNamingConventionCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> field name should be camelCase.

**规则约束 / 可调参数**: (无)

### pl-0408-final-local-variable  —  `LOCAL_VARIABLE`

- **规则类**: `org.plumb.style.naming.FinalLocalVariableCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> local variable never reassigned should be final.

**规则约束 / 可调参数**: (无)

### pl-0710-generic-naming  —  `GENERIC_NAMING`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `format` | STRING | `^[A-Z][0-9]?$` | Regex for type parameter names (default: single uppercase letter) |
> | `allowMultiChar` | BOOLEAN | `false` | Allow multi-character type parameter names like Key, Value |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `format` | STRING | `^[A-Z][0-9]?$` | Regex for type parameter names (default: single uppercase letter) |
| `allowMultiChar` | BOOLEAN | `false` | Allow multi-character type parameter names like Key, Value |

### pl-0545-interface-type-param  —  `INTERFACE_TYPE_PARAM`

- **规则类**: `org.plumb.style.naming.InterfaceTypeParameterNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> interface type parameter name should be uppercase single letter.

**规则约束 / 可调参数**: (无)

### pl-0181-invalid-log  —  `INVALID_LOG_MESSAGE_FORMAT`

- **规则类**: `org.plumb.style.naming.InvalidLogMessageFormatCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> log message lacks placeholder — pass arguments to slf4j.

**规则约束 / 可调参数**: (无)

### pl-0547-local-final-var-name  —  `FINAL_VAR_NAME`

- **规则类**: `org.plumb.style.naming.LocalFinalVariableNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> local final variable name should be camelCase.

**规则约束 / 可调参数**: (无)

### pl-0577-local-home-naming  —  `HOME_NAMING`

- **规则类**: `org.plumb.style.naming.LocalHomeNamingConventionCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> EJB local home should end with LocalHome.

**规则约束 / 可调参数**: (无)

### pl-0105-local-var-could-be-final  —  `VAR_COULD_BE_FINAL`

- **规则类**: `org.plumb.style.naming.LocalVariableCouldBeFinalCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> local variable could be declared final.

**规则约束 / 可调参数**: (无)

### pl-0548-local-var-name  —  `VAR_NAME`

- **规则类**: `org.plumb.style.naming.LocalVariableNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **PMD**: LocalVariableNamingConventions
  - **Checkstyle**: LocalVariableName

**规则说明**

> local variable name should be camelCase.

**规则约束 / 可调参数**: (无)

### pl-0184-local-var-naming  —  `VARIABLE_NAMING_CONVENTIONS`

- **规则类**: `org.plumb.style.naming.LocalVariableNamingConventionsCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> local variable should be camelCase.

**规则约束 / 可调参数**: (无)

### pl-0194-shadow-field  —  `SHADOW_FIELD`

- **规则类**: `org.plumb.style.naming.LocalVariableShadowingFieldCheck`
- **分类**: `naming`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> local variable shadows a field.

**规则约束 / 可调参数**: (无)

### pl-0248-local-var-return  —  `VAR_THEN_RETURN`

- **规则类**: `org.plumb.style.naming.LocalVariableThenReturnCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> local variable declared and immediately returned.

**规则约束 / 可调参数**: (无)

### pl-0579-long-var-strict  —  `VAR_STRICT`

- **规则类**: `org.plumb.style.naming.LongVariableCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **PMD**: LongVariable

**规则说明**

> long variable name (> 20 chars, strict).

**规则约束 / 可调参数**: (无)

### pl-0549-member-name  —  `MEMBER_NAME`

- **规则类**: `org.plumb.style.naming.MemberNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: MemberName

**规则说明**

> non-static field should be camelCase.

**规则约束 / 可调参数**: (无)

### pl-0414-method-name  —  `METHOD_NAME`

- **规则类**: `org.plumb.style.naming.MethodNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**:
  - **PMD**: MethodNamingConventions
  - **Checkstyle**: MethodName

**规则说明**

> method name should match pattern (camelCase).

**规则约束 / 可调参数**: (无)

### pl-0189-method-naming  —  `METHOD_NAMING_CONVENTIONS`

- **规则类**: `org.plumb.style.naming.MethodNamingConventionsCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**:
  - **PMD**: MethodNamingConventions

**规则说明**

> method name should be camelCase.

**规则约束 / 可调参数**: (无)

### pl-0480-missing-override-strict  —  `MISSING_OVERRIDE_STRICT`

- **规则类**: `org.plumb.style.naming.MissingOverrideCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method override missing @Override annotation.

**规则约束 / 可调参数**: (无)

### pl-647-naming-convention  —  `NAMING_CONVENTION`

- **规则类**: `org.plumb.style.naming.NamingConventionCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Naming convention.

**规则约束 / 可调参数**: (无)

### pl-620-outer-type-filename  —  `TYPE_FILENAME`

- **规则类**: `org.plumb.style.naming.OuterTypeFilenameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Outer type filename should match class name.

**规则约束 / 可调参数**: (无)

### pl-0206-equals-hash  —  `OVERRIDE_EQUALS_AND_HASHCODE`

- **规则类**: `org.plumb.style.naming.OverrideEqualsAndHashCodeCheck`
- **分类**: `naming`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> override equals AND hashCode together.

**规则约束 / 可调参数**: (无)

### pl-0232-package-case  —  `PACKAGE_CASE`

- **规则类**: `org.plumb.style.naming.PackageCaseCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> package name should be all lowercase.

**规则约束 / 可调参数**: (无)

### pl-0247-public-constant  —  `PUBLIC_CONSTANT_FIELD_NAMING`

- **规则类**: `org.plumb.style.naming.PublicConstantFieldNamingCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> public constant should be SCREAMING_SNAKE_CASE.

**规则约束 / 可调参数**: (无)

### pl-0220-short-class  —  `CLASS_NAME`

- **规则类**: `org.plumb.style.naming.ShortClassNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class name is too short (limit 3).

**规则约束 / 可调参数**: (无)

### pl-0221-short-method  —  `METHOD_NAME`

- **规则类**: `org.plumb.style.naming.ShortMethodNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method name is too short.

**规则约束 / 可调参数**: (无)

### pl-0222-short-variable  —  `VARIABLE`

- **规则类**: `org.plumb.style.naming.ShortVariableCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**:
  - **PMD**: ShortVariable

**规则说明**

> variable name is too short.

**规则约束 / 可调参数**: (无)

### pl-0731-singleton-class  —  `SINGLETON_CLASS`

- **规则类**: `org.plumb.style.naming.SingletonClassCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Class with all-private constructors should be an enum.

**规则约束 / 可调参数**: (无)

### pl-0137-suspicious-equals  —  `SUSPICIOUS_EQUALS_METHOD_NAME`

- **规则类**: `org.plumb.style.naming.SuspiciousEqualsMethodNameCheck`
- **分类**: `naming`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> equals() method should take java.lang.Object.

**规则约束 / 可调参数**: (无)

### pl-0589-type-name  —  `NAME`

- **规则类**: `org.plumb.style.naming.TypeNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: TypeName

**规则说明**

> type name should be PascalCase.

**规则约束 / 可调参数**: (无)

### pl-0097-unnecessary-fqn  —  `UNNECESSARY_FULLY_QUALIFIED_NAME`

- **规则类**: `org.plumb.style.naming.UnnecessaryFullyQualifiedClassNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **PMD**: UnnecessaryFullyQualifiedName

**规则说明**

> avoid fully qualified class name — use import.

**规则约束 / 可调参数**: (无)

### pl-664-unnecessary-fully-qualified-enum  —  `UNNECESSARY_FULLY_QUALIFIED_ENUM`

- **规则类**: `org.plumb.style.naming.UnnecessaryFullyQualifiedEnumNameCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Unnecessary fully qualified enum name.

**规则约束 / 可调参数**: (无)

### pl-0429-unused-local-var  —  `UNUSED_LOCAL_VAR`

- **规则类**: `org.plumb.style.naming.UnusedLocalVariableCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> local variable declared but never used.

**规则约束 / 可调参数**: (无)

### pl-0713-use-underscores-in-numeric-literals  —  `USE_UNDERSCORES_IN_NUMERIC_LITERALS`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `minDigits` | INT | `4` | Minimum consecutive digits to require underscores |
> | `includeFloat` | BOOLEAN | `true` | Also check float/double literals |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `minDigits` | INT | `4` | Minimum consecutive digits to require underscores |
| `includeFloat` | BOOLEAN | `true` | Also check float/double literals |

### pl-0146-use-utility-class  —  `UTILITY_CLASS`

- **规则类**: `org.plumb.style.naming.UseUtilityClassCheck`
- **分类**: `naming`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class with only static members should be a utility class.

**规则约束 / 可调参数**: (无)

### pl-0246-array-key  —  `AS_KEY`

- **规则类**: `org.plumb.style.performance.ArrayAsKeyOfSetOrMapCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> array used as Map/Set key — use List/Set.

**规则约束 / 可调参数**: (无)

### pl-0613--array-equality  —  `EQUALITY`

- **规则类**: `org.plumb.style.performance.ArrayEqualityCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> arrays should not be compared with == (use Arrays.equals instead).

**规则约束 / 可调参数**: (无)

### bu-0801-array-hashcode-tostring  —  `HASHCODE_TOSTRING`

- **规则类**: `org.plumb.style.performance.ArrayHashCodeAndToStringCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-477
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> arr.toString() / arr.hashCode() should use Arrays.toString / Arrays.hashCode.

**规则约束 / 可调参数**: (无)

### pl-629-array-store-incompatible  —  `STORE_INCOMPATIBLE`

- **规则类**: `org.plumb.style.performance.ArrayStoreIncompatibleCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Array store of incompatible type.

**规则约束 / 可调参数**: (无)

### pl-630-array-too-large  —  `TOO_LARGE`

- **规则类**: `org.plumb.style.performance.ArrayTooLargeCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Array created with too large initial size.

**规则约束 / 可调参数**: (无)

### pl-0706-array-type  —  `ARRAY_TYPE`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `javaStyle` | BOOLEAN | `true` | Prefer Type[] over Type [] and Type arr[] |
> | `allowCStyleOnParameters` | BOOLEAN | `false` | Allow C-style (Type arr[]) on method parameters |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `javaStyle` | BOOLEAN | `true` | Prefer Type[] over Type [] and Type arr[] |
| `allowCStyleOnParameters` | BOOLEAN | `false` | Allow C-style (Type arr[]) on method parameters |

### pl-0121-avoid-array-loops  —  `ARRAY_LOOPS`

- **规则类**: `org.plumb.style.performance.AvoidArrayLoopsCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use for-each instead of indexed for-loop over array.

**规则约束 / 可调参数**: (无)

### pl-0083-avoid-calendar-date-instance  —  `CALENDAR_DATE_INSTANCE`

- **规则类**: `org.plumb.style.performance.AvoidCalendarDateInstanceCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid new Date() — use Calendar.getInstance() or LocalDate.now().

**规则约束 / 可调参数**: (无)

### pl-0084-avoid-stringbuffer-field  —  `STRINGBUFFER_FIELD`

- **规则类**: `org.plumb.style.performance.AvoidStringBufferFieldCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid StringBuffer as a field.

**规则约束 / 可调参数**: (无)

### pl-0332-bc-bad-cast-abstract  —  `BAD_CAST_TO_ABSTRACT_COLLECTION`

- **规则类**: `org.plumb.style.performance.BcBadCastToAbstractCollectionCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-704
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> bad cast to abstract collection (BC_BAD_CAST_TO_ABSTRACT_COLLECTION).

**规则约束 / 可调参数**: (无)

### pl-registry-decimal_equals  —  `DECIMAL_EQUALS`

- **规则类**: `org.plumb.style.performance.BigDecimalEqualsCheck`
- **分类**: `performance`
- **严重度**: ?
- **类型**: ?
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> BigDecimal.equals is value-and-scale, use compareTo.

**规则约束 / 可调参数**: (无)

### pl-634-boxed-constructor  —  `CONSTRUCTOR`

- **规则类**: `org.plumb.style.performance.BoxedConstructorCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Boxed constructor usage.

**规则约束 / 可调参数**: (无)

### pl-0439-collection-inappropriate  —  `COLLECTION_INAPPROPRIATE`

- **规则类**: `org.plumb.style.performance.CollectionInappropriateCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> using Hashtable/Vector.

**规则约束 / 可调参数**: (无)

### pl-0213-string-eq  —  `COMPARE_STRINGS_WITH_EQUALS`

- **规则类**: `org.plumb.style.performance.CompareStringsWithEqualsCheck`
- **分类**: `performance`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-595
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use.equals() not == for String comparison.

**规则约束 / 可调参数**: (无)

### pl-0296-dm-boxed-primitive  —  `BOXED_PRIMITIVE_FOR_PARSING`

- **规则类**: `org.plumb.style.performance.DmBoxedPrimitiveForParsingCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-704
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> new Integer("5") — use Integer.parseInt(s) instead (DM_BOXED_PRIMITIVE_FOR_PARSING).

**规则约束 / 可调参数**: (无)

### pl-0341-dmi-collection-urls  —  `COLLECTION_OF_URLS`

- **规则类**: `org.plumb.style.performance.DmiCollectionOfUrlsCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-400
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> collection of URLs — equals triggers DNS (DMI_COLLECTION_OF_URLS).

**规则约束 / 可调参数**: (无)

### pl-0073-do-not-call-gc  —  `NOT_CALL_GC`

- **规则类**: `org.plumb.style.performance.DoNotCallGarbageCollectionCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-401
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> do not call System.gc() / Runtime.gc().

**规则约束 / 可调参数**: (无)

### pl-0569-float-loop-index-strict  —  `LOOP_INDEX_STRICT`

- **规则类**: `org.plumb.style.performance.DontUseFloatTypeForLoopIndicesCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> float/double for loop counter (strict).

**规则约束 / 可调参数**: (无)

### pl-0204-dup-string  —  `DUPLICATED_STRING_LITERAL`

- **规则类**: `org.plumb.style.performance.DuplicatedStringLiteralCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> duplicated string literal — extract a constant.

**规则约束 / 可调参数**: (无)

### pl-0300-ec-bad-array-compare  —  `BAD_ARRAY_COMPARE`

- **规则类**: `org.plumb.style.performance.EcBadArrayCompareCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> array == comparison — use Arrays.equals (EC_BAD_ARRAY_COMPARE).

**规则约束 / 可调参数**: (无)

### pl-0336-ec-bad-array-store  —  `BAD_ARRAY_STORE`

- **规则类**: `org.plumb.style.performance.EcBadArrayStoreCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-704
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> array literal has heterogeneous types (EC_BAD_ARRAY_STORE).

**规则约束 / 可调参数**: (无)

### pl-0505-floating-point-loops  —  `FLOATING_POINT_LOOPS`

- **规则类**: `org.plumb.style.performance.FloatingPointLoopsCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> for loop using floating-point counter.

**规则约束 / 可调参数**: (无)

### pl-0062-for-loop-float  —  `LOOP_FLOAT`

- **规则类**: `org.plumb.style.performance.ForLoopVariableTypeCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-682
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> for-loop counter must not be float/double.

**规则约束 / 可调参数**: (无)

### pl-0153-for-size  —  `LOOP_WITH_SIZE_METHOD`

- **规则类**: `org.plumb.style.performance.ForLoopWithSizeMethodCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> cache list.size() outside the for-loop.

**规则约束 / 可调参数**: (无)

### pl-644-jdk-obsolete-collection  —  `OBSOLETE_COLLECTION`

- **规则类**: `org.plumb.style.performance.JdkObsoleteCollectionCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Use non-obsolete JDK collection.

**规则约束 / 可调参数**: (无)

### pl-0708-jumbled-incrementer  —  `JUMBLED_INCREMENTER`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `allowDecrement` | BOOLEAN | `true` | Allow i-- as a standard increment |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `allowDecrement` | BOOLEAN | `true` | Allow i-- as a standard increment |

### pl-0450-loops-same-condition  —  `SAME_CONDITION`

- **规则类**: `org.plumb.style.performance.LoopsWithSameConditionCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> nested loops have same condition.

**规则约束 / 可调参数**: (无)

### pl-0511-malformed-format-string  —  `MALFORMED_FORMAT_STRING`

- **规则类**: `org.plumb.style.performance.MalformedFormatStringCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> malformed format string literal.

**规则约束 / 可调参数**: (无)

### pl-646-method-return-check  —  `METHOD_RETURN_CHECK`

- **规则类**: `org.plumb.style.performance.MethodReturnCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Method should return value.

**规则约束 / 可调参数**: (无)

### pl-615-no-array-trailing-comma  —  `ARRAY_TRAILING_COMMA`

- **规则类**: `org.plumb.style.performance.NoArrayTrailingCommaCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Array initialization should not have trailing comma.

**规则约束 / 可调参数**: (无)

### pl-0515-non-singleton-primitive  —  `SINGLETON_PRIMITIVE`

- **规则类**: `org.plumb.style.performance.NonSingletonPrimitiveConstructorInCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Boolean constructor unnecessary (use Boolean.valueOf).

**规则约束 / 可调参数**: (无)

### pl-0452-null-returned-tostring  —  `RETURNED_TOSTRING`

- **规则类**: `org.plumb.style.performance.NullReturnedToStringCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> toString() returning null.

**规则约束 / 可调参数**: (无)

### pl-0517-prefer-zero-length-arrays  —  `PREFER_ZERO_LENGTH_ARRAYS`

- **规则类**: `org.plumb.style.performance.PreferZeroLengthArraysCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> return null instead of empty array.

**规则约束 / 可调参数**: (无)

### pl-0288-primitives-boxed  —  `PRIMITIVES_BOXED`

- **规则类**: `org.plumb.style.performance.PrimitivesBoxedCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-704
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> new Integer/Long/... — use the literal.

**规则约束 / 可调参数**: (无)

### pl-0326-range-array-index  —  `ARRAY_INDEX`

- **规则类**: `org.plumb.style.performance.RangeArrayIndexCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-129
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> array index can equal length (RANGE_ARRAY_INDEX).

**规则约束 / 可调参数**: (无)

### pl-0322-range-array-length  —  `ARRAY_LENGTH`

- **规则类**: `org.plumb.style.performance.RangeArrayLengthCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-129
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> array index equals length — should be length-1 (RANGE_ARRAY_LENGTH).

**规则约束 / 可调参数**: (无)

### pl-0323-range-array-offset  —  `ARRAY_OFFSET`

- **规则类**: `org.plumb.style.performance.RangeArrayOffsetCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-129
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> arraycopy length / offset off-by-one (RANGE_ARRAY_OFFSET).

**规则约束 / 可调参数**: (无)

### pl-0325-range-string-index  —  `STRING_INDEX`

- **规则类**: `org.plumb.style.performance.RangeStringIndexCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> indexOf() >= 0 — use contains (RANGE_STRING_INDEX).

**规则约束 / 可调参数**: (无)

### pl-0324-range-string-length  —  `STRING_LENGTH`

- **规则类**: `org.plumb.style.performance.RangeStringLengthCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-129
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> string index equals length() — should be length()-1 (RANGE_STRING_LENGTH).

**规则约束 / 可调参数**: (无)

### pl-0093-return-empty-not-null  —  `RETURN_EMPTY_NOT_NULL`

- **规则类**: `org.plumb.style.performance.ReturnEmptyCollectionNotNullCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> return empty collection instead of null.

**规则约束 / 可调参数**: (无)

### pl-0237-return-null-collection  —  `RETURN_EMPTY_COLLECTION_RATHER_THAN_NULL`

- **规则类**: `org.plumb.style.performance.ReturnEmptyCollectionRatherThanNullCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> return null for collection — use empty.

**规则约束 / 可调参数**: (无)

### pl-0523-returns-null-collection  —  `RETURNS_NULL_COLLECTION`

- **规则类**: `org.plumb.style.performance.ReturnsNullInsteadOfEmptyCollectionCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> return null instead of empty collection.

**规则约束 / 可调参数**: (无)

### pl-0223-simplify-bool  —  `SIMPLIFY_BOOLEAN_EXPRESSIONS`

- **规则类**: `org.plumb.style.performance.SimplifyBooleanExpressionsCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> simplify x == true / x == false to x / !x.

**规则约束 / 可调参数**: (无)

### pl-0225-simplify-conditional  —  `SIMPLIFY_CONDITIONAL`

- **规则类**: `org.plumb.style.performance.SimplifyConditionalCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> ternary branches are the same — use the value.

**规则约束 / 可调参数**: (无)

### pl-0294-string-buffer-constructor  —  `STRING_BUFFER_CONSTRUCTOR`

- **规则类**: `org.plumb.style.performance.StringBufferConstructorCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use StringBuilder, not StringBuffer.

**规则约束 / 可调参数**: (无)

### pl-0527-string-buffer-field-serial  —  `STRING_BUFFER_FIELD_SERIAL`

- **规则类**: `org.plumb.style.performance.StringBufferFieldInSerialClassCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> StringBuffer is not safe in serializable class.

**规则约束 / 可调参数**: (无)

### bu-0903-string-builder-concatenation-in-loop  —  `STRING_BUILDER_CONCATENATION_IN_LOOP`

- **规则类**: `org.plumb.style.performance.StringBuilderConcatenationCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> String concatenation in a loop should use StringBuilder.

**规则约束 / 可调参数**: (无)

### bu-1104-string-equality-with-equals  —  `STRING_EQUALITY_WITH_EQUALS`

- **规则类**: `org.plumb.style.performance.StringEqualityWithEqualsCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> String comparison with == (should use.equals()).

**规则约束 / 可调参数**: (无)

### pl-0109-string-instantiation  —  `STRING_INSTANTIATION`

- **规则类**: `org.plumb.style.performance.StringInstantiationCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid new String("...") — string literals are interned.

**规则约束 / 可调参数**: (无)

### pl-0490-string-literal-equality  —  `STRING_LITERAL_EQUALITY`

- **规则类**: `org.plumb.style.performance.StringLiteralEqualityCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> comparing String literals with ==/!= (use.equals).

**规则约束 / 可调参数**: (无)

### bu-0804-synchronization-on-string-or-boxed  —  `STRING_BOXED`

- **规则类**: `org.plumb.style.performance.SynchronizationOnStringOrBoxedCheck`
- **分类**: `performance`
- **严重度**: HIGH
- **类型**: MULTITHREADING
- **CWE**: CWE-820
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Synchronize on interned String / boxed primitive is unsafe (use a private Object lock).

**规则约束 / 可调参数**: (无)

### pl-unnecessary-boxing  —  `UNNECESSARY_BOXING`

- **规则类**: `org.plumb.style.performance.UnnecessaryBoxingCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> unnecessary primitive boxing (e.g. new Integer(5)).

**规则约束 / 可调参数**: (无)

### pl-0086-unnecessary-case-change  —  `UNNECESSARY_CASE_CHANGE`

- **规则类**: `org.plumb.style.performance.UnnecessaryCaseChangeCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use equalsIgnoreCase() instead of toLowerCase().equals().

**规则约束 / 可调参数**: (无)

### pl-0067-unnecessary-cast  —  `UNNECESSARY_CAST`

- **规则类**: `org.plumb.style.performance.UnnecessaryCastCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> unnecessary cast.

**规则约束 / 可调参数**: (无)

### pl-0730-unnecessary-constructor  —  `UNNECESSARY_CONSTRUCTOR`

- **规则类**: `org.plumb.style.performance.UnnecessaryConstructorCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> No-arg empty constructor duplicates the default.

**规则约束 / 可调参数**: (无)

### pl-0045-use-arraylist-instead-of-vector  —  `ARRAYLIST_INSTEAD_OF_VECTOR`

- **规则类**: `org.plumb.style.performance.UseArrayListInsteadOfVectorCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use ArrayList instead of Vector.

**规则约束 / 可调参数**: (无)

### pl-0089-use-assertequals  —  `ASSERTEQUALS_INSTEAD_OF_ASSERTTRUE`

- **规则类**: `org.plumb.style.performance.UseAssertEqualsInsteadOfAssertTrueCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use assertEquals() instead of assertTrue(a == b).

**规则约束 / 可调参数**: (无)

### pl-0139-use-asserttrue  —  `ASSERTTRUE_INSTEAD_OF_ASSERTEQUALS`

- **规则类**: `org.plumb.style.performance.UseAssertTrueInsteadOfAssertEqualsCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use assertTrue/assertFalse instead of assertEquals(true, x).

**规则约束 / 可调参数**: (无)

### pl-0140-use-collection-is-empty  —  `COLLECTION_IS_EMPTY`

- **规则类**: `org.plumb.style.performance.UseCollectionIsEmptyCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use Collection.isEmpty() instead of size() == 0.

**规则约束 / 可调参数**: (无)

### pl-0155-use-indexof-char  —  `INDEXOF_CHAR`

- **规则类**: `org.plumb.style.performance.UseIndexOfCharCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use indexOf(char) instead of indexOf(String) for single chars.

**规则约束 / 可调参数**: (无)

### pl-0157-useless-string-valueof  —  `USELESS_STRING_VALUEOF`

- **规则类**: `org.plumb.style.performance.UselessStringValueOfCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> String.valueOf() is unnecessary — use the value directly.

**规则约束 / 可调参数**: (无)

### pl-0087-use-short-array-init  —  `SHORT_ARRAY_INITIALIZER`

- **规则类**: `org.plumb.style.performance.UseShortArrayInitializerCheck`
- **分类**: `performance`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use short array initializer form.

**规则约束 / 可调参数**: (无)

### pl-0108-use-stringbuffer  —  `STRINGBUFFER_FOR_STRING_APPENDS`

- **规则类**: `org.plumb.style.performance.UseStringBufferForStringAppendsCheck`
- **分类**: `performance`
- **严重度**: MEDIUM
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> "use StringBuilder in loops instead of string.

**规则约束 / 可调参数**: (无)

### pl-0558-hard-coded-ip  —  `CODED_IP`

- **规则类**: `org.plumb.style.security.AvoidUsingHardCodedIPCheck`
- **分类**: `security`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> hard-coded IP address in code.

**规则约束 / 可调参数**: (无)

### pl-0434-case-insensitive-comparison  —  `INSENSITIVE_COMPARISON`

- **规则类**: `org.plumb.style.security.CaseInsensitiveComparisonCheck`
- **分类**: `security`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> case-insensitive String comparison should use equalsIgnoreCase.

**规则约束 / 可调参数**: (无)

### pl-0339-dmi-hardcoded-path  —  `HARDCODED_ABSOLUTE_PATH`

- **规则类**: `org.plumb.style.security.DmiHardcodedAbsolutePathCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-22
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> hard-coded absolute path (DMI_HARDCODED_ABSOLUTE_PATH).

**规则约束 / 可调参数**: (无)

### bu-1107-hardcoded-credentials  —  `HARDCODED_CREDENTIALS`

- **规则类**: `org.plumb.style.security.HardcodedCredentialsCheck`
- **分类**: `security`
- **严重度**: CRITICAL
- **类型**: SECURITY
- **CWE**: CWE-798
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> String literal assigned to a credential-named variable (use config / secrets manager).

**规则约束 / 可调参数**: (无)

### pl-0446-hardcoded-ip  —  `HARDCODED_IP`

- **规则类**: `org.plumb.style.security.HardcodedIpCheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: SECURITY
- **CWE**: CWE-547
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> hardcoded IP address.

**规则约束 / 可调参数**: (无)

### pl-0190-hardcoded-uri  —  `HARDCODED_URI`

- **规则类**: `org.plumb.style.security.HardcodedURICheck`
- **分类**: `security`
- **严重度**: MEDIUM
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> hard-coded URI — use a config value.

**规则约束 / 可调参数**: (无)

### pl-0327-insecure-crypto  —  `INSECURE_CRYPTO`

- **规则类**: `org.plumb.style.security.InsecureCryptoCheck`
- **分类**: `security`
- **严重度**: HIGH
- **类型**: SECURITY
- **外部等价规则**:
  - **SpotBugs**: CUSTOM_MESSAGE_DIGEST_MD5 / CIPHER_DES

**规则说明**

> Use of broken / risky cryptographic algorithm.

**规则约束 / 可调参数**: (无)

### pl-0281-loop-once  —  `EXECUTING_AT_MOST_ONCE`

- **规则类**: `org.plumb.style.security.LoopExecutingAtMostOnceCheck`
- **分类**: `security`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> loop executes at most once — refactor to a plain statement.

**规则约束 / 可调参数**: (无)

### pl-652-path-check  —  `CHECK`

- **规则类**: `org.plumb.style.security.PathCheck`
- **分类**: `security`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Path manipulation.

**规则约束 / 可调参数**: (无)

### pl-0712-absent-codeblock-in-case  —  `ABSENT_CODEBLOCK_IN_CASE`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `requireBraces` | BOOLEAN | `true` | Fire on every multi-statement case body |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `requireBraces` | BOOLEAN | `true` | Fire on every multi-statement case body |

### pl-0612--ambiguous-precedence  —  `AMBIGUOUS_PRECEDENCE`

- **规则类**: `org.plumb.style.style.AmbiguousOperatorPrecedenceCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-783
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> ambiguous operator precedence, add explicit parentheses.

**规则约束 / 可调参数**: (无)

### pl-601-annotation-location  —  `ANNOTATION_LOCATION`

- **规则类**: `org.plumb.style.style.AnnotationLocationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Annotation should be on same line as target.

**规则约束 / 可调参数**: (无)

### pl-602-annotation-on-same-line  —  `ANNOTATION_ON_SAME_LINE`

- **规则类**: `org.plumb.style.style.AnnotationOnSameLineCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Annotation should be on same line as target.

**规则约束 / 可调参数**: (无)

### pl-0462-annotation-table  —  `ANNOTATION_TABLE`

- **规则类**: `org.plumb.style.style.AnnoTblCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> @author/@version tag is missing.

**规则约束 / 可调参数**: (无)

### pl-0598-anon-inner-length  —  `INNER_LENGTH`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `max` | INT | `20` | Maximum allowed lines for an anonymous class |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `max` | INT | `20` | Maximum allowed lines for an anonymous class |

### pl-631-assertion-boundary  —  `ASSERTION_BOUNDARY`

- **规则类**: `org.plumb.style.style.AssertionBoundaryCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Assertion boundary check.

**规则约束 / 可调参数**: (无)

### pl-0162-assign-in-operand  —  `ASSIGNMENT_IN_OPERAND`

- **规则类**: `org.plumb.style.style.AssignmentInOperandCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-783
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> assignment in operand — separate from condition.

**规则约束 / 可调参数**: (无)

### pl-0712-assignment-to-non-final-static  —  `ASSIGNMENT_TO_NON_FINAL_STATIC`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `ignoreFinalFields` | BOOLEAN | `true` | Skip final fields (immutable by design) |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreFinalFields` | BOOLEAN | `true` | Skip final fields (immutable by design) |

### pl-0401-atclause-order  —  `ATCLAUSE_ORDER`

- **规则类**: `org.plumb.style.style.AtclauseOrderCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> @return before @param in Javadoc (heuristic).

**规则约束 / 可调参数**: (无)

### pl-0163-deeply-nested-if  —  `DEEPLY_NESTED_IF_STMTS`

- **规则类**: `org.plumb.style.style.AvoidDeeplyNestedIfStmtsCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> if-statement is nested more than 4 levels deep.

**规则约束 / 可调参数**: (无)

### pl-0555-avoid-enum  —  `ENUM`

- **规则类**: `org.plumb.style.style.AvoidEnumsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> enum is a class; consider if it has too many methods.

**规则约束 / 可调参数**: (无)

### pl-603-avoid-escaped-unicode  —  `ESCAPED_UNICODE`

- **规则类**: `org.plumb.style.style.AvoidEscapedUnicodeCharactersCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Avoid escaped Unicode characters (use direct).

**规则约束 / 可调参数**: (无)

### pl-0557-literals-in-if  —  `LITERALS_IN_IF`

- **规则类**: `org.plumb.style.style.AvoidLiteralsInIfConditionCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid literals in if conditions (use named constants).

**规则约束 / 可调参数**: (无)

### pl-0075-avoid-messagedigest-field  —  `MESSAGEDIGEST_FIELD`

- **规则类**: `org.plumb.style.style.AvoidMessageDigestFieldCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-362
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid MessageDigest as a field (not thread-safe).

**规则约束 / 可调参数**: (无)

### pl-0076-avoid-native-methods  —  `NATIVE_METHODS`

- **规则类**: `org.plumb.style.style.AvoidNativeMethodsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid native methods (JNI ties to platform).

**规则约束 / 可调参数**: (无)

### pl-0464-avoid-nested-blocks  —  `NESTED_BLOCKS`

- **规则类**: `org.plumb.style.style.AvoidNestedBlocksCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid nested blocks.

**规则约束 / 可调参数**: (无)

### bu-1103-avoid-print-stack-trace  —  `PRINT_STACK_TRACE`

- **规则类**: `org.plumb.style.style.AvoidPrintStackTraceCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-209
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid printStackTrace() — use a logger.

**规则约束 / 可调参数**: (无)

### pl-0533-protected-field-final  —  `PROTECTED_FIELD_FINAL`

- **规则类**: `org.plumb.style.style.AvoidProtectedFieldInFinalClassCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> protected field in final class.

**规则约束 / 可调参数**: (无)

### pl-0534-protected-method-final  —  `PROTECTED_METHOD_FINAL`

- **规则类**: `org.plumb.style.style.AvoidProtectedMethodInFinalClassCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> protected method in final class (should be private).

**规则约束 / 可调参数**: (无)

### pl-0152-reassign-loop-var  —  `REASSIGNING_LOOP_VARIABLES`

- **规则类**: `org.plumb.style.style.AvoidReassigningLoopVariablesCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-563
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid reassigning the loop counter inside the body.

**规则约束 / 可调参数**: (无)

### pl-0166-reassign-parameter  —  `REASSIGNING_PARAMETERS`

- **规则类**: `org.plumb.style.style.AvoidReassigningParametersCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method parameter is reassigned.

**规则约束 / 可调参数**: (无)

### pl-0720-avoid-star-import  —  `AVOID_STAR_IMPORT`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `allowStatic` | BOOLEAN | `true` | Allow star imports on static members |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `allowStatic` | BOOLEAN | `true` | Allow star imports on static members |

### pl-0403-avoid-static-import  —  `STATIC_IMPORT`

- **规则类**: `org.plumb.style.style.AvoidStaticImportCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid static imports.

**规则约束 / 可调参数**: (无)

### pl-0077-avoid-threadgroup  —  `THREADGROUP`

- **规则类**: `org.plumb.style.style.AvoidThreadGroupCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid ThreadGroup (use Executor instead).

**规则约束 / 可调参数**: (无)

### pl-0078-avoid-octal-values  —  `OCTAL_VALUES`

- **规则类**: `org.plumb.style.style.AvoidUsingOctalValuesCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-682
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid octal literals (start with 0).

**规则约束 / 可调参数**: (无)

### pl-0079-avoid-volatile  —  `VOLATILE`

- **规则类**: `org.plumb.style.style.AvoidUsingVolatileCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid volatile (use proper synchronization).

**规则约束 / 可调参数**: (无)

### pl-0498-bad-odd-check  —  `ODD_CHECK`

- **规则类**: `org.plumb.style.style.BadCheckForOddCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> x % 2 == 1 is wrong for negative numbers (use bitwise AND).

**规则约束 / 可调参数**: (无)

### pl-633-badly-overridden-adapter  —  `OVERRIDDEN_ADAPTER`

- **规则类**: `org.plumb.style.style.BadlyOverriddenAdapterCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Badly overridden adapter.

**规则约束 / 可调参数**: (无)

### pl-632-bad-syntax-in-regex  —  `SYNTAX_IN_REGEX`

- **规则类**: `org.plumb.style.style.BadSyntaxInRegexCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Bad syntax in regular expression.

**规则约束 / 可调参数**: (无)

### pl-0331-bc-equals-no-hash  —  `EQUALS_NO_HASHCODE`

- **规则类**: `org.plumb.style.style.BcEqualsNoHashcodeCallCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> equals() but no hashCode() — broken contract (BC_EQUALS_NO_HASHCODE).

**规则约束 / 可调参数**: (无)

### pl-0167-bigdec-double  —  `BIGDECIMAL_DOUBLE`

- **规则类**: `org.plumb.style.style.BigDecimalDoubleConstructorCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-682
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> new BigDecimal(double) — use BigDecimal(String) for exact value.

**规则约束 / 可调参数**: (无)

### pl-0262-bit-and  —  `AND`

- **规则类**: `org.plumb.style.style.BitwiseAndCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-783
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> bitwise AND `&` in conditional — likely meant logical AND `&&` (BIT_AND).

**规则约束 / 可调参数**: (无)

### pl-0270-bit-or  —  `OR`

- **规则类**: `org.plumb.style.style.BitwiseOrCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-783
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> bitwise OR `|` in conditional — likely meant logical OR `||` (BIT_OR).

**规则约束 / 可调参数**: (无)

### pl-0258-bool-if  —  `BOOLEAN_LITERAL_IF`

- **规则类**: `org.plumb.style.style.BooleanLiteralInIfCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> if-statement with literal/null condition.

**规则约束 / 可调参数**: (无)

### pl-0195-bool-negation  —  `BOOLEAN_LITERAL_NEGATION`

- **规则类**: `org.plumb.style.style.BooleanLiteralNegationCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> !true / !false — invert the condition.

**规则约束 / 可调参数**: (无)

### bu-0805-boolean-method-returns-null  —  `BOOLEAN_RETURNS_NULL`

- **规则类**: `org.plumb.style.style.BooleanMethodReturnCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-476
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Boolean-returning method that returns null is dangerous (autoboxing NPE).

**规则约束 / 可调参数**: (无)

### pl-0081-call-super-in-constructor  —  `SUPER_IN_CONSTRUCTOR`

- **规则类**: `org.plumb.style.style.CallSuperInConstructorCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> subclass constructor should call super(...) explicitly.

**规则约束 / 可调参数**: (无)

### pl-0047-call-to-thread-run  —  `TO_THREAD_RUN`

- **规则类**: `org.plumb.style.style.CallToThreadRunCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-572
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> call Thread.start() not Thread.run().

**规则约束 / 可调参数**: (无)

### pl-635-call-to-unsupported-method  —  `TO_UNSUPPORTED_METHOD`

- **规则类**: `org.plumb.style.style.CallToUnsupportedMethodCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Call to unsupported method.

**规则约束 / 可调参数**: (无)

### pl-0436-cast-arithmetic-operand  —  `ARITHMETIC_OPERAND`

- **规则类**: `org.plumb.style.style.CastArithmeticOperandCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> cast on arithmetic operand may cause overflow.

**规则约束 / 可调参数**: (无)

### pl-636-check-comparator-result  —  `COMPARATOR_RESULT`

- **规则类**: `org.plumb.style.style.CheckTypeOfResultOfComparatorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Check type of result of Comparator.compare.

**规则约束 / 可调参数**: (无)

### pl-0437-child-class-shadow-field  —  `CLASS_SHADOW_FIELD`

- **规则类**: `org.plumb.style.style.ChildClassShadowFieldCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-849
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> subclass field shadows parent field.

**规则约束 / 可调参数**: (无)

### pl-0193-class-field-shadow  —  `FIELD_SHADOW`

- **规则类**: `org.plumb.style.style.ClassFieldShadowingCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class field has the same name as the enclosing class.

**规则约束 / 可调参数**: (无)

### pl-0207-private  —  `MEMBERS_SHOULD_BE_PRIVATE`

- **规则类**: `org.plumb.style.style.ClassMembersShouldBePrivateCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class member should be private if not used outside.

**规则约束 / 可调参数**: (无)

### pl-registry-var_visibility  —  `VAR_VISIBILITY`

- **规则类**: `org.plumb.style.style.ClassVariableVisibilityCheck`
- **分类**: `style`
- **严重度**: ?
- **类型**: ?
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> public static field should be final or non-public.

**规则约束 / 可调参数**: (无)

### pl-0168-private-ctors-final  —  `WITH_ONLY_PRIVATE_CONSTRUCTORS_SHOULD_BE_FINAL`

- **规则类**: `org.plumb.style.style.ClassWithOnlyPrivateConstructorsShouldBeFinalCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class with only private constructors should be final.

**规则约束 / 可调参数**: (无)

### pl-0027-cn-idiom-no-super-call  —  `IDIOM`

- **规则类**: `org.plumb.style.style.CloneWithoutSuperCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-664
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> clone() should call super.clone().

**规则约束 / 可调参数**: (无)

### pl-0610--collapsible-if  —  `COLLAPSIBLE_IF`

- **规则类**: `org.plumb.style.style.CollapsibleIfCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> nested if statements can be collapsed into one with &&.

**规则约束 / 可调参数**: (无)

### pl-0705-collapsible-if-statements  —  `COLLAPSIBLE_IF_STATEMENTS`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `ignoreElseBranch` | BOOLEAN | `true` | Skip when the parent if has an else branch |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreElseBranch` | BOOLEAN | `true` | Skip when the parent if has an else branch |

### bu-0705-comment-default-access  —  `COMMENT_DEFAULT_ACCESS`

- **规则类**: `org.plumb.style.style.CommentDefaultAccessCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Default access modifier should be commented.

**规则约束 / 可调参数**: (无)

### pl-604-comments-indentation  —  `COMMENTS_INDENTATION`

- **规则类**: `org.plumb.style.style.CommentsIndentationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Comments should be properly indented.

**规则约束 / 可调参数**: (无)

### pl-0216-conditional-constant  —  `CONDITIONAL_ON_CONSTANT`

- **规则类**: `org.plumb.style.style.ConditionalOnConstantCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-570
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> conditional has a constant value.

**规则约束 / 可调参数**: (无)

### pl-0082-confusing-ternary  —  `CONFUSING_TERNARY`

- **规则类**: `org.plumb.style.style.ConfusingTernaryCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> nested ternary expression is confusing.

**规则约束 / 可调参数**: (无)

### pl-0199-interface-constants  —  `CONSTANTS_IN_INTERFACE`

- **规则类**: `org.plumb.style.style.ConstantsInInterfaceCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> interface with only constants — use a class or enum.

**规则约束 / 可调参数**: (无)

### pl-0085-need-braces  —  `BRACES`

- **规则类**: `org.plumb.style.style.ControlStatementBracesCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> control statement should always use braces.

**规则约束 / 可调参数**: (无)

### pl-0468-ctor-parameter-count  —  `PARAMETER_COUNT`

- **规则类**: `org.plumb.style.style.CtorParameterCountCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> constructor has too many parameters (default 7).

**规则约束 / 可调参数**: (无)

### pl-0566-dataflow-anomaly  —  `DATAFLOW_ANOMALY`

- **规则类**: `org.plumb.style.style.DataflowAnomalyAnalysisCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> data flow anomaly (UR, DU, DD).

**规则约束 / 可调参数**: (无)

### bu-1001-unreachable-code  —  `UNREACHABLE_CODE`

- **规则类**: `org.plumb.style.style.DeadcodeCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: CORRECTNESS
- **CWE**: CWE-561
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Code after return/throw/break/continue is unreachable.

**规则约束 / 可调参数**: (无)

### pl-0538-declaration-order  —  `DECLARATION_ORDER`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `ignoreConstructors` | BOOLEAN | `false` | Skip constructor ordering check |
> | `ignoreModifiers` | BOOLEAN | `false` | Treat static and instance fields as one group |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreConstructors` | BOOLEAN | `false` | Skip constructor ordering check |
| `ignoreModifiers` | BOOLEAN | `false` | Treat static and instance fields as one group |

### pl-638-default-charset  —  `DEFAULT_CHARSET`

- **规则类**: `org.plumb.style.style.DefaultCharsetCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Default charset usage.

**规则约束 / 可调参数**: (无)

### pl-0567-default-package  —  `DEFAULT_PACKAGE`

- **规则类**: `org.plumb.style.style.DefaultPackageCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> classes should be in a named package.

**规则约束 / 可调参数**: (无)

### pl-0274-deprecated-annotation  —  `DEPRECATED_ANNOTATION`

- **规则类**: `org.plumb.style.style.DeprecatedAnnotationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> @Deprecated should declare since / forRemoval.

**规则约束 / 可调参数**: (无)

### pl-0442-diamond-operator  —  `DIAMOND_OPERATOR`

- **规则类**: `org.plumb.style.style.DiamondOperatorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use diamond <> operator instead of explicit type.

**规则约束 / 可调参数**: (无)

### pl-0340-dmi-bad-month  —  `BAD_MONTH`

- **规则类**: `org.plumb.style.style.DmiBadMonthCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> month literal 1-12 — use Calendar.JANUARY (0) (DMI_BAD_MONTH).

**规则约束 / 可调参数**: (无)

### pl-0342-dmi-done  —  `DONE`

- **规则类**: `org.plumb.style.style.DmiDoneCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> @Override method with no real body (DMI_DONE).

**规则约束 / 可调参数**: (无)

### pl-0501-dmi-random  —  `RANDOM`

- **规则类**: `org.plumb.style.style.DmiRandomCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> java.util.Random seeded with predictable value.

**规则约束 / 可调参数**: (无)

### pl-0343-dmi-useless-method  —  `USELESS_METHOD`

- **规则类**: `org.plumb.style.style.DmiUselessMethodCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> void method with empty body (DMI_USELESS_METHOD).

**规则约束 / 可调参数**: (无)

### pl-639-do-inside-do-privileged  —  `INSIDE_DO_PRIVILEGED`

- **规则类**: `org.plumb.style.style.DoInsideDoPrivilegedCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Action should be inside doPrivileged.

**规则约束 / 可调参数**: (无)

### pl-0125-dont-import-javalang  —  `IMPORT_JAVA_LANG`

- **规则类**: `org.plumb.style.style.DontImportJavaLangCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> java.lang.* is imported automatically — remove import.

**规则约束 / 可调参数**: (无)

### pl-640-dont-import-sun  —  `IMPORT_SUN`

- **规则类**: `org.plumb.style.style.DontImportSunCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Do not import sun.* packages.

**规则约束 / 可调参数**: (无)

### pl-0048-double-checked-locking  —  `DOUBLECHECK`

- **规则类**: `org.plumb.style.style.DoubleCheckedLockingCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: MULTITHREADING
- **CWE**: CWE-609
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> double-checked locking is broken on the JMM pre-Java-5 (use volatile or class-holder idiom).

**规则约束 / 可调参数**: (无)

### pl-0126-duplicate-imports  —  `DUPLICATE_IMPORTS`

- **规则类**: `org.plumb.style.style.DuplicateImportsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> duplicate import — remove the second.

**规则约束 / 可调参数**: (无)

### pl-0298-ec-incompatible  —  `INCOMPATIBLE_TYPES`

- **规则类**: `org.plumb.style.style.EcIncompatibleTypesCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-703
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> incompatible exception types used (EC_INCOMPATIBLE_TYPES).

**规则约束 / 可调参数**: (无)

### pl-0333-ec-no-read  —  `NO_READ_IF_EQUAL_TO_WRITE`

- **规则类**: `org.plumb.style.style.EcNoReadIfEqualToWriteCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-754
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> x = x — pointless self-assignment (EC_NO_READ_IF_EQUAL_TO_WRITE).

**规则约束 / 可调参数**: (无)

### pl-0335-ec-null-vararg  —  `NULL_ARG_TO_VARARG_METHOD`

- **规则类**: `org.plumb.style.style.EcNullArgToVarargCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-754
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> null passed as varargs (EC_NULL_ARG_TO_VARARG_METHOD).

**规则约束 / 可调参数**: (无)

### pl-0334-ec-unrelated-iface  —  `UNRELATED_INTERFACES`

- **规则类**: `org.plumb.style.style.EcUnrelatedInterfacesCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> implements unrelated interfaces (EC_UNRELATED_INTERFACES).

**规则约束 / 可调参数**: (无)

### pl-0299-ec-unrelated  —  `UNRELATED_TYPES`

- **规则类**: `org.plumb.style.style.EcUnrelatedTypesCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method returns object unrelated to declared type (EC_UNRELATED_TYPES).

**规则约束 / 可调参数**: (无)

### pl-0725-empty-block  —  `EMPTY_BLOCK`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `allowEmptyCatch` | BOOLEAN | `false` | Skip empty catch blocks |
> | `allowEmptyLoop` | BOOLEAN | `false` | Skip empty for/while/do loops |
> | `allowEmptyIf` | BOOLEAN | `false` | Skip empty if blocks |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `allowEmptyCatch` | BOOLEAN | `false` | Skip empty catch blocks |
| `allowEmptyLoop` | BOOLEAN | `false` | Skip empty for/while/do loops |
| `allowEmptyIf` | BOOLEAN | `false` | Skip empty if blocks |

### pl-0024-empty-constructor  —  `CONSTRUCTOR`

- **规则类**: `org.plumb.style.style.EmptyConstructorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> explicit empty constructor is unnecessary.

**规则约束 / 可调参数**: (无)

### pl-registry-empty_control_statement  —  `EMPTY_CONTROL_STATEMENT`

- **规则类**: `org.plumb.style.style.EmptyControlStatementCheck`
- **分类**: `style`
- **严重度**: ?
- **类型**: ?
- **外部等价规则**:
  - **PMD**: EmptyControlStatement

**规则说明**

> control statement with empty body.

**规则约束 / 可调参数**: (无)

### pl-0021-empty-finally-block  —  `FINALLY_BLOCK`

- **规则类**: `org.plumb.style.style.EmptyFinallyBlockCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty finally block — remove it.

**规则约束 / 可调参数**: (无)

### pl-0127-empty-if-stmt  —  `IF_STMT`

- **规则类**: `org.plumb.style.style.EmptyIfStmtCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty if-statement (then-branch is a bare ';').

**规则约束 / 可调参数**: (无)

### pl-0540-empty-line-separator-strict  —  `LINE_SEPARATOR_STRICT`

- **规则类**: `org.plumb.style.style.EmptyLineSeparatorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> blank line between methods (strict).

**规则约束 / 可调参数**: (无)

### bu-0702-empty-method-in-abstract  —  `METHOD_IN_ABSTRACT`

- **规则类**: `org.plumb.style.style.EmptyMethodInAbstractCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Empty method body in abstract class should be marked abstract.

**规则约束 / 可调参数**: (无)

### pl-0541-empty-statement  —  `STATEMENT`

- **规则类**: `org.plumb.style.style.EmptyStatementCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty statement (just a semicolon) is useless.

**规则约束 / 可调参数**: (无)

### pl-0061-empty-statement  —  `STATEMENT`

- **规则类**: `org.plumb.style.style.EmptyStatementUsageCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty statement (;) — remove it.

**规则约束 / 可调参数**: (无)

### pl-0572-empty-static-init  —  `STATIC_INIT`

- **规则类**: `org.plumb.style.style.EmptyStaticInitializerCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty static initializer.

**规则约束 / 可调参数**: (无)

### pl-0129-empty-switch  —  `SWITCH_STATEMENTS`

- **规则类**: `org.plumb.style.style.EmptySwitchStatementsCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> switch statement has no cases.

**规则约束 / 可调参数**: (无)

### pl-0025-empty-sync-block  —  `SYNC_BLOCK`

- **规则类**: `org.plumb.style.style.EmptySyncBlockCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **CWE**: CWE-1071
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid empty synchronized blocks (lock acquired / released for nothing).

**规则约束 / 可调参数**: (无)

### pl-0130-empty-try-block  —  `TRY_BLOCK`

- **规则类**: `org.plumb.style.style.EmptyTryBlockCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty try block — remove it.

**规则约束 / 可调参数**: (无)

### pl-0217-empty-while  —  `WHILE_STMT`

- **规则类**: `org.plumb.style.style.EmptyWhileStmtCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty while-statement (body is a bare ';').

**规则约束 / 可调参数**: (无)

### pl-642-empty-zip-entry  —  `ZIP_ENTRY`

- **规则类**: `org.plumb.style.style.EmptyZipEntryCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Empty zip entry.

**规则约束 / 可调参数**: (无)

### pl-0502-enum-abstract-method  —  `ABSTRACT_METHOD`

- **规则类**: `org.plumb.style.style.EnumAbstractMethodCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> enum should not have abstract methods.

**规则约束 / 可调参数**: (无)

### pl-643-enum-idiom  —  `IDIOM`

- **规则类**: `org.plumb.style.style.EnumIdiomCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Enum idiom check.

**规则约束 / 可调参数**: (无)

### pl-0132-equal-comparison  —  `COMPARISON`

- **规则类**: `org.plumb.style.style.EqualComparisonCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-595
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use.equals() not == for object comparison.

**规则约束 / 可调参数**: (无)

### pl-0444-equals-arg-type  —  `EQUALS_ARG_TYPE`

- **规则类**: `org.plumb.style.style.EqualsArgumentTypeCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> equals argument should be Object.

**规则约束 / 可调参数**: (无)

### pl-0406-equals-hashcode  —  `EQUALS_HASHCODE`

- **规则类**: `org.plumb.style.style.EqualsHashCodeCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class overrides equals() but not hashCode().

**规则约束 / 可调参数**: (无)

### pl-0268-eq-self-no-object  —  `SELF_NO_OBJECT`

- **规则类**: `org.plumb.style.style.EqualsNoObjectCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> equals() parameter is not Object (EQ_SELF_NO_OBJECT).

**规则约束 / 可调参数**: (无)

### pl-0301-esync-empty  —  `EMPTY_SYNC`

- **规则类**: `org.plumb.style.style.EsyncEmptySyncCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> empty synchronized block (ESYNC_EMPTY_SYNC).

**规则约束 / 可调参数**: (无)

### pl-0470-explicit-initialization  —  `EXPLICIT_INITIALIZATION`

- **规则类**: `org.plumb.style.style.ExplicitInitializationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> explicit initialization to default value is redundant.

**规则约束 / 可调参数**: (无)

### bu-1105-expose-internal-representation  —  `EXPOSE_INTERNAL_REPRESENTATION`

- **规则类**: `org.plumb.style.style.ExposeInternalRepresentationCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-374
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Getter returns a mutable internal array or collection (defensive copy required).

**规则约束 / 可调参数**: (无)

### pl-0265-ei-expose-rep2  —  `EXPOSE_REP2`

- **规则类**: `org.plumb.style.style.ExposeMutableRepStorageCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-374
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> externally provided mutable stored in field (EI_EXPOSE_REP2) — defensive copy needed.

**规则约束 / 可调参数**: (无)

### bu-0701-extends-object  —  `EXTENDS_OBJECT`

- **规则类**: `org.plumb.style.style.ExtendsObjectCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Class explicitly extends Object, the declaration is redundant.

**规则约束 / 可调参数**: (无)

### pl-0471-fallthrough  —  `FALLTHROUGH`

- **规则类**: `org.plumb.style.style.FallThroughCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> switch case fallthrough without // fallthrough.

**规则约束 / 可调参数**: (无)

### pl-0302-fe-floating-point  —  `FLOATING_POINT`

- **规则类**: `org.plumb.style.style.FeFloatingPointCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> floating-point == comparison (FE_FLOATING_POINT) — use Math.abs(a-b) < eps.

**规则约束 / 可调参数**: (无)

### pl-0504-field-self-assignment  —  `SELF_ASSIGNMENT`

- **规则类**: `org.plumb.style.style.FieldSelfAssignmentCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> field self-assignment (this.x = x).

**规则约束 / 可调参数**: (无)

### pl-605-file-tab-character  —  `TAB_CHARACTER`

- **规则类**: `org.plumb.style.style.FileTabCharacterCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> File must not contain tab characters.

**规则约束 / 可调参数**: (无)

### pl-0407-final-class  —  `CLASS`

- **规则类**: `org.plumb.style.style.FinalClassCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class with only private ctors should be final.

**规则约束 / 可调参数**: (无)

### pl-0409-final-parameters  —  `PARAMETERS`

- **规则类**: `org.plumb.style.style.FinalParametersCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> parameter never reassigned should be final.

**规则约束 / 可调参数**: (无)

### pl-0303-fi-useless-block  —  `USELESS_BLOCK`

- **规则类**: `org.plumb.style.style.FiUselessBlockCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> useless single-statement block (FI_USELESS_BLOCK) — drop the braces.

**规则约束 / 可调参数**: (无)

### pl-0176-for-foreach  —  `LOOP_CAN_BE_FOREACH`

- **规则类**: `org.plumb.style.style.ForLoopCanBeForeachCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> for-loop can be replaced with for-each.

**规则约束 / 可调参数**: (无)

### pl-0445-for-loop-false-condition  —  `LOOP_FALSE_CONDITION`

- **规则类**: `org.plumb.style.style.ForLoopFalseConditionCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> for loop has always-false condition.

**规则约束 / 可调参数**: (无)

### pl-0177-for-var-count  —  `LOOP_VARIABLE_COUNT`

- **规则类**: `org.plumb.style.style.ForLoopVariableCountCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> for-loop declares more than one variable.

**规则约束 / 可调参数**: (无)

### pl-0338-he-hashcode-signature  —  `SIGNATURE_DECLARES_HASHCODE_OF_OBJECT`

- **规则类**: `org.plumb.style.style.HeSignatureDeclaresHashcodeOfObjectCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> hashCode(Object) — clashes with Object.hashCode (HE_SIGNATURE_DECLARES_HASHCODE_OF_OBJECT).

**规则约束 / 可调参数**: (无)

### pl-606-hidden-field  —  `HIDDEN_FIELD`

- **规则类**: `org.plumb.style.style.HiddenFieldCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Local variable should not shadow field.

**规则约束 / 可调参数**: (无)

### pl-0410-hide-utility-class-ctor  —  `UTILITY_CLASS_CTOR`

- **规则类**: `org.plumb.style.style.HideUtilityClassConstructorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> utility class should hide public constructor.

**规则约束 / 可调参数**: (无)

### pl-0305-icast-int-mult-long  —  `INTEGER_MULTIPLY_CAST_TO_LONG`

- **规则类**: `org.plumb.style.style.IcastIntegerMultiplyCastToLongCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-190
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> (long)(a * b) overflows at the multiplication (ICAST_INTEGER_MULTIPLY_CAST_TO_LONG).

**规则约束 / 可调参数**: (无)

### pl-0304-ic-superclass-generic  —  `SUPERCLASS_GENERIC`

- **规则类**: `org.plumb.style.style.IcSuperclassGenericCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> superclass uses sub-classed generic (IC_SUPERCLASS_GENERIC).

**规则约束 / 可调参数**: (无)

### pl-0574-idempotent-operations  —  `IDEMPOTENT_OPERATIONS`

- **规则类**: `org.plumb.style.style.IdempotentOperationsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> idempotent operations (e.g. x = x).

**规则约束 / 可调参数**: (无)

### pl-0218-if-else-stmt  —  `ELSE_STMT`

- **规则类**: `org.plumb.style.style.IfElseStmtCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> if/else if chain — consider switch.

**规则约束 / 可调参数**: (无)

### pl-0344-iil-deref-readline  —  `DEREF_OF_READLINE_VALUE`

- **规则类**: `org.plumb.style.style.IilDereOfReadlineCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-476
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> readLine() result not null-checked (IIL_DEREF_OF_READLINE_VALUE).

**规则约束 / 可调参数**: (无)

### pl-0321-il-container-sequencing  —  `CONTAINER_SEQUENCING`

- **规则类**: `org.plumb.style.style.IlContainerSequencingCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-672
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> collection modified during iteration (IL_CONTAINER_SEQUENCING).

**规则约束 / 可调参数**: (无)

### pl-0306-im-average  —  `AVERAGE_COMPUTATION`

- **规则类**: `org.plumb.style.style.ImAverageComputationCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-190
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> "(ab) / 2 can overflow — use a(b - a) / 2 (IM_AVERAGE_COMPUTATION)".

**规则约束 / 可调参数**: (无)

### pl-0307-im-bad-check-odd  —  `BAD_CHECK_FOR_ODD`

- **规则类**: `org.plumb.style.style.ImBadCheckForOddCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-682
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> x % 2 == 1 fails for negative x (IM_BAD_CHECK_FOR_ODD) — use x % 2 != 0.

**规则约束 / 可调参数**: (无)

### pl-0575-immutable-field-strict  —  `IMMUTABLE_FIELD_STRICT`

- **规则类**: `org.plumb.style.style.ImmutableFieldCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> non-final field is never reassigned (should be final).

**规则约束 / 可调参数**: (无)

### pl-0595-import-control  —  `IMPORT_CONTROL`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `staticImportsSeparate` | BOOLEAN | `true` | Static imports must precede non-static |
> | `sortWithinGroup` | BOOLEAN | `true` | Sort alphabetically within each group |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `staticImportsSeparate` | BOOLEAN | `true` | Static imports must precede non-static |
| `sortWithinGroup` | BOOLEAN | `true` | Sort alphabetically within each group |

### pl-0180-import-same-package  —  `IMPORT_FROM_SAME_PACKAGE`

- **规则类**: `org.plumb.style.style.ImportFromSamePackageCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> import from same package — remove.

**规则约束 / 可调参数**: (无)

### pl-0506-incompatible-mask  —  `INCOMPATIBLE_MASK`

- **规则类**: `org.plumb.style.style.IncompatibleMaskCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> bit mask incompatible with the constant.

**规则约束 / 可调参数**: (无)

### pl-0033-class-only-private-ctors  —  `ONLY_PRIVATE_CTORS`

- **规则类**: `org.plumb.style.style.IncompleteClassDeclarationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class with only private constructors should be final.

**规则约束 / 可调参数**: (无)

### pl-607-indentation  —  `INDENTATION`

- **规则类**: `org.plumb.style.style.IndentationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Code indentation check.

**规则约束 / 可调参数**: (无)

### pl-0287-infinite-loop  —  `INFINITE_LOOP`

- **规则类**: `org.plumb.style.style.InfiniteLoopCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> loop with statically-true condition — confirm intent.

**规则约束 / 可调参数**: (无)

### pl-0412-inner-assignment  —  `ASSIGNMENT`

- **规则类**: `org.plumb.style.style.InnerAssignmentCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> assignment inside expression (e.g. while ((x = next()) != 0)).

**规则约束 / 可调参数**: (无)

### pl-0576-instantiation-to-class  —  `INSTANTIATION_TO_CLASS`

- **规则类**: `org.plumb.style.style.InstantiationToGetClassCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> do not instantiate just to call getClass().

**规则约束 / 可调参数**: (无)

### bu-0905-int-division-cast-to-double  —  `DIVISION_CAST_TO_DOUBLE`

- **规则类**: `org.plumb.style.style.IntDivisionCastToDoubleCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Integer division cast to double (precision loss; cast one operand instead).

**规则约束 / 可调参数**: (无)

### pl-0308-io-resource-both  —  `RESOURCE_USED_AS_BOTH_INPUT_AND_OUTPUT`

- **规则类**: `org.plumb.style.style.IoResourceBothCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-668
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> resource used as both input and output (IO_RESOURCE_USED_AS_BOTH_INPUT_AND_OUTPUT).

**规则约束 / 可调参数**: (无)

### pl-0508-iterator-idiomatic  —  `ITERATOR_IDIOMATIC`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `ignoreLoopsWithIndexUsedOutside` | BOOLEAN | `true` | Skip loops where i is read in non-indexing ways |
> | `arrayLengthTolerance` | BOOLEAN | `true` | Accept arr.length as a compare operand |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreLoopsWithIndexUsedOutside` | BOOLEAN | `true` | Skip loops where i is read in non-indexing ways |
| `arrayLengthTolerance` | BOOLEAN | `true` | Accept arr.length as a compare operand |

### pl-0069-javadoc-method  —  `JAVADOC_METHOD`

- **规则类**: `org.plumb.style.style.JavadocMethodCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: JavadocMethod

**规则说明**

> public method should have a Javadoc comment.

**规则约束 / 可调参数**: (无)

### pl-608-javadoc-paragraph  —  `JAVADOC_PARAGRAPH`

- **规则类**: `org.plumb.style.style.JavadocParagraphCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Javadoc paragraphs should be separated by single .

**规则约束 / 可调参数**: (无)

### pl-0477-javadoc-type-strict  —  `JAVADOC_TYPE_STRICT`

- **规则类**: `org.plumb.style.style.JavadocTypeCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> public class missing Javadoc.

**规则约束 / 可调参数**: (无)

### pl-0182-junit-message  —  `ASSERTIONS_SHOULD_INCLUDE_MESSAGE`

- **规则类**: `org.plumb.style.style.JUnitAssertionsShouldIncludeMessageCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> JUnit assertion should include a failure message.

**规则约束 / 可调参数**: (无)

### pl-0183-junit-no-assert  —  `TESTS_SHOULD_INCLUDE_ASSERT`

- **规则类**: `org.plumb.style.style.JUnitTestsShouldIncludeAssertCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> @Test method has no assertion — it always passes.

**规则约束 / 可调参数**: (无)

### pl-0279-lambda-body-length  —  `LAMBDA_BODY_LENGTH`

- **规则类**: `org.plumb.style.style.LambdaBodyLengthCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> lambda body has too many statements (, default 3).

**规则约束 / 可调参数**: (无)

### pl-0448-lambda-param-shadowing  —  `LAMBDA_PARAM_SHADOWING`

- **规则类**: `org.plumb.style.style.LambdaParameterShadowingCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> lambda parameter name shadows outer variable.

**规则约束 / 可调参数**: (无)

### pl-0413-left-curly  —  `CURLY`

- **规则类**: `org.plumb.style.style.LeftCurlyCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: LeftCurly

**规则说明**

> left curly brace should be on the same line as declaration.

**规则约束 / 可调参数**: (无)

### pl-0719-line-length  —  `LINE_LENGTH`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `max` | INT | `100` | Maximum allowed line length |
> | `ignorePattern` | STRING | `` | Regex of lines to ignore (after trim) |
> | `ignoreImports` | BOOLEAN | `false` | Skip import statements |
> | `ignoreComments` | BOOLEAN | `true` | Skip comment lines |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `max` | INT | `100` | Maximum allowed line length |
| `ignorePattern` | STRING | `` | Regex of lines to ignore (after trim) |
| `ignoreImports` | BOOLEAN | `false` | Skip import statements |
| `ignoreComments` | BOOLEAN | `true` | Skip comment lines |

### pl-0119-literals-first  —  `LITERALS_FIRST_IN_COMPARISONS`

- **规则类**: `org.plumb.style.style.LiteralsFirstInComparisonsCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-476
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> literal should be first in equals() to avoid NPE.

**规则约束 / 可调参数**: (无)

### bu-0902-locale-dependent-case-conversion  —  `LOCALE_DEPENDENT_CASE_CONVERSION`

- **规则类**: `org.plumb.style.style.LocaleDependentCaseConversionCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: I18N
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> String.toLowerCase/toUpperCase without Locale (locale-dependent).

**规则约束 / 可调参数**: (无)

### pl-0293-locale-root  —  `LOCALE_ROOT`

- **规则类**: `org.plumb.style.style.LocaleRootCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **CWE**: CWE-758
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> locale-sensitive API without explicit Locale.

**规则约束 / 可调参数**: (无)

### pl-0449-loggers-declaration  —  `LOGGERS_DECLARATION`

- **规则类**: `org.plumb.style.style.LoggersDeclarationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> logger should be private static final.

**规则约束 / 可调参数**: (无)

### pl-0208-logger-final  —  `LOGGER_SHOULD_BE_FINAL`

- **规则类**: `org.plumb.style.style.LoggerShouldBeFinalCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Logger field should be static final.

**规则约束 / 可调参数**: (无)

### pl-0252-loop-breaks  —  `MULTIPLE_BREAKS`

- **规则类**: `org.plumb.style.style.LoopsWithMultipleBreaksCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> loop has multiple break/continue — refactor.

**规则约束 / 可调参数**: (无)

### pl-0726-magic-number  —  `MAGIC_NUMBER`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `ignoreNumbers` | STRING | `-1,0,1,2` | Comma-separated literal values that are never reported |
> | `ignoreInForLoop` | BOOLEAN | `true` | Skip literals in for-loop header / update |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreNumbers` | STRING | `-1,0,1,2` | Comma-separated literal values that are never reported |
| `ignoreInForLoop` | BOOLEAN | `true` | Skip literals in for-loop header / update |

### pl-645-malformed-sync  —  `MALFORMED_SYNC`

- **规则类**: `org.plumb.style.style.MalformedSyncCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Malformed synchronization.

**规则约束 / 可调参数**: (无)

### pl-0285-math-op-literal  —  `OP_LITERAL`

- **规则类**: `org.plumb.style.style.MathematicalOperationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> math on literal values — pre-compute and use a constant.

**规则约束 / 可调参数**: (无)

### pl-0709-mc-returns-void  —  `MC_RETURNS_VOID`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `prefix` | STRING | `get` | Getter prefix to check |
> | `checkAllPublic` | BOOLEAN | `true` | Apply check to all public methods with the prefix |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `prefix` | STRING | `get` | Getter prefix to check |
| `checkAllPublic` | BOOLEAN | `true` | Apply check to all public methods with the prefix |

### pl-0104-method-arg-could-be-final  —  `METHOD_ARG_COULD_BE_FINAL`

- **规则类**: `org.plumb.style.style.MethodArgumentCouldBeFinalCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method argument could be declared final.

**规则约束 / 可调参数**: (无)

### pl-0215-ctor-method  —  `METHOD_CALL`

- **规则类**: `org.plumb.style.style.MethodCallsFromConstructorCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-665
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> constructor calls overridable method.

**规则约束 / 可调参数**: (无)

### pl-0451-method-too-complex  —  `METHOD_TOO_COMPLEX`

- **规则类**: `org.plumb.style.style.MethodTooComplexCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method cyclomatic complexity > 10.

**规则约束 / 可调参数**: (无)

### pl-0249-method-too-long  —  `METHOD_TOO_LONG`

- **规则类**: `org.plumb.style.style.MethodTooLongCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method is too long (limit 50 statements).

**规则约束 / 可调参数**: (无)

### pl-0309-mf-class-masks-field  —  `CLASS_MASKS_FIELD`

- **规则类**: `org.plumb.style.style.MfClassMasksFieldCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> a nested class masks an enclosing field of the same name (MF_CLASS_MASKS_FIELD).

**规则约束 / 可调参数**: (无)

### pl-0310-mf-method-masks-field  —  `METHOD_MASKS_FIELD`

- **规则类**: `org.plumb.style.style.MfMethodMasksFieldCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> a method masks an enclosing field of the same name (MF_METHOD_MASKS_FIELD).

**规则约束 / 可调参数**: (无)

### cov-0001-missing-break  —  `MISSING_BREAK`

- **规则类**: `org.plumb.style.style.MissingBreakCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: CORRECTNESS
- **CWE**: CWE-484
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Switch case falls through to the next case without break/return/throw/continue.

**规则约束 / 可调参数**: (无)

### pl-0700-missing-deprecated  —  `MISSING_DEPRECATED`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `ignorePrivateMethods` | BOOLEAN | `true` | Skip private methods of @Deprecated classes |
> | `ignoreOverriddenMethods` | BOOLEAN | `true` | Skip methods that are overrides |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignorePrivateMethods` | BOOLEAN | `true` | Skip private methods of @Deprecated classes |
| `ignoreOverriddenMethods` | BOOLEAN | `true` | Skip methods that are overrides |

### pl-611-missing-javadoc-method  —  `MISSING_JAVADOC_METHOD`

- **规则类**: `org.plumb.style.style.MissingJavadocMethodCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Missing Javadoc on public method.

**规则约束 / 可调参数**: (无)

### pl-612-missing-javadoc-package  —  `MISSING_JAVADOC_PACKAGE`

- **规则类**: `org.plumb.style.style.MissingJavadocPackageCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Missing package-info.java or Javadoc on package.

**规则约束 / 可调参数**: (无)

### pl-613-missing-javadoc-type  —  `MISSING_JAVADOC_TYPE`

- **规则类**: `org.plumb.style.style.MissingJavadocTypeCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Missing Javadoc on public type.

**规则约束 / 可调参数**: (无)

### pl-0229-missing-serial-version-uid  —  `MISSING_SERIAL_VERSION_UID`

- **规则类**: `org.plumb.style.style.MissingSerialVersionUIDCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-609
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Class implements Serializable but is missing serialVersionUID.

**规则约束 / 可调参数**: (无)

### pl-0112-missing-static-in-non-instantiable  —  `MISSING_STATIC_IN_NON_INSTANTIABLE`

- **规则类**: `org.plumb.style.style.MissingStaticMethodInNonInstantiableClassCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> non-instantiable class should have a static method or field.

**规则约束 / 可调参数**: (无)

### pl-0415-missing-switch-default  —  `MISSING_SWITCH_DEFAULT`

- **规则类**: `org.plumb.style.style.MissingSwitchDefaultCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> switch should have a default case.

**规则约束 / 可调参数**: (无)

### pl-0416-modifier-order  —  `MODIFIER_ORDER`

- **规则类**: `org.plumb.style.style.ModifierOrderCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> modifier order should be public/protected/private static final abstract.

**规则约束 / 可调参数**: (无)

### pl-0231-more-than-one-logger  —  `THAN_ONE_LOGGER`

- **规则类**: `org.plumb.style.style.MoreThanOneLoggerCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class has more than one Logger field.

**规则约束 / 可调参数**: (无)

### pl-0417-multiple-var-decls  —  `MULTIPLE_VARIABLE_DECLARATIONS`

- **规则类**: `org.plumb.style.style.MultipleVariableDeclarationsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> multiple variables in one declaration.

**规则约束 / 可调参数**: (无)

### pl-0266-me-mutable-enum  —  `MUTABLE_ENUM_FIELD`

- **规则类**: `org.plumb.style.style.MutableEnumFieldCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> enum field is not final (ME_MUTABLE_ENUM_FIELD) — globally mutable state.

**规则约束 / 可调参数**: (无)

### bu-0901-mutable-static-should-be-final  —  `MUTABLE_STATIC_SHOULD_BE_FINAL`

- **规则类**: `org.plumb.style.style.MutableStaticShouldBeFinalCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: MALICIOUS_CODE
- **CWE**: CWE-500
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Mutable static field should be final (mutable global state).

**规则约束 / 可调参数**: (无)

### pl-0191-nested-block  —  `NESTED_BLOCK`

- **规则类**: `org.plumb.style.style.NestedBlockCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> nested block — extract to a method.

**规则约束 / 可调参数**: (无)

### pl-0418-nested-for-depth  —  `NESTED_FOR_DEPTH`

- **规则类**: `org.plumb.style.style.NestedForDepthCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> nested for loop depth > 2 (heuristic).

**规则约束 / 可调参数**: (无)

### pl-0419-nested-if-depth  —  `NESTED_IF_DEPTH`

- **规则类**: `org.plumb.style.style.NestedIfDepthCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> nested if depth > 2 (heuristic).

**规则约束 / 可调参数**: (无)

### pl-0277-nested-switch  —  `NESTED_SWITCH`

- **规则类**: `org.plumb.style.style.NestedSwitchCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> switch nested inside another switch.

**规则约束 / 可调参数**: (无)

### pl-0715-newline-at-eof  —  `NEWLINE_AT_EOF`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `lineSeparator` | STRING | `\n` | Required line separator at end of file |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `lineSeparator` | STRING | `\n` | Required line separator at end of file |

### pl-0311-nm-confusing  —  `CONFUSING_INSPECTION`

- **规则类**: `org.plumb.style.style.NmConfusingInspectionCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> confusing class / method name (NM_CONFUSING_INSPECTION).

**规则约束 / 可调参数**: (无)

### pl-0337-nm-same-name-super  —  `SAME_SIMPLE_NAME_AS_SUPERCLASS`

- **规则类**: `org.plumb.style.style.NmSameSimpleNameAsSuperCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class shares a simple name with its superclass (NM_SAME_SIMPLE_NAME_AS_SUPERCLASS).

**规则约束 / 可调参数**: (无)

### pl-616-no-code-in-file  —  `CODE_IN_FILE`

- **规则类**: `org.plumb.style.style.NoCodeInFileCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> File should not contain code.

**规则约束 / 可调参数**: (无)

### pl-617-no-enum-trailing-comma  —  `ENUM_TRAILING_COMMA`

- **规则类**: `org.plumb.style.style.NoEnumTrailingCommaCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Enum should not have trailing comma.

**规则约束 / 可调参数**: (无)

### pl-0291-no-log-statement  —  `LOG_AT_DISABLED_LEVEL`

- **规则类**: `org.plumb.style.style.NoLogStatementCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> log call may be at a disabled level.

**规则约束 / 可调参数**: (无)

### pl-0289-no-long-literal-suffix  —  `LITERAL_SUFFIX`

- **规则类**: `org.plumb.style.style.NoLongLiteralCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> long literal should use upper-case L suffix.

**规则约束 / 可调参数**: (无)

### pl-0236-non-case-label  —  `CASE_LABEL_IN_SWITCH_STATEMENT`

- **规则类**: `org.plumb.style.style.NonCaseLabelInSwitchStatementCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> labeled statement in switch — use case label.

**规则约束 / 可调参数**: (无)

### pl-0711-non-serializable-class  —  `NON_SERIALIZABLE_CLASS`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `ignoreTransient` | BOOLEAN | `true` | Skip transient fields |
> | `ignoreStatic` | BOOLEAN | `true` | Skip static fields |
> | `ignorePrimitives` | BOOLEAN | `true` | Skip primitive and common-JDK serializable types |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `ignoreTransient` | BOOLEAN | `true` | Skip transient fields |
| `ignoreStatic` | BOOLEAN | `true` | Skip static fields |
| `ignorePrimitives` | BOOLEAN | `true` | Skip primitive and common-JDK serializable types |

### pl-0269-se-bad-field  —  `BAD_FIELD`

- **规则类**: `org.plumb.style.style.NonSerializableFieldCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-684
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Serializable class has a non-Serializable field (SE_BAD_FIELD).

**规则约束 / 可调参数**: (无)

### pl-0733-non-static-initializer  —  `NON_STATIC_INITIALIZER`

- **规则类**: `org.plumb.style.style.NonStaticInitializerCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Non-static initializer block — move into a constructor.

**规则约束 / 可调参数**: (无)

### pl-0234-no-package  —  `PACKAGE`

- **规则类**: `org.plumb.style.style.NoPackageCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> top-level class has no package declaration.

**规则约束 / 可调参数**: (无)

### pl-648-notify-check  —  `NOTIFY_CHECK`

- **规则类**: `org.plumb.style.style.NotifyCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Notify rather than notifyAll.

**规则约束 / 可调参数**: (无)

### pl-0328-np-sync-unshared  —  `SYNC_INVOKED_ON_UNSHARED_VARIABLE`

- **规则类**: `org.plumb.style.style.NpSyncOnUnsharedVarCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-820
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> synchronized on a local variable (NP_SYNC_INVOKED_ON_UNSHARED_VARIABLE).

**规则约束 / 可调参数**: (无)

### pl-0219-null-assignment  —  `ASSIGNMENT`

- **规则类**: `org.plumb.style.style.NullAssignmentCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> assignment of null outside declaration.

**规则约束 / 可调参数**: (无)

### pl-649-objects-equality  —  `OBJECTS_EQUALITY`

- **规则类**: `org.plumb.style.style.ObjectsEqualityCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Objects equality.

**规则约束 / 可调参数**: (无)

### pl-0278-one-statement-per-line  —  `STATEMENT_PER_LINE`

- **规则类**: `org.plumb.style.style.OneStatementPerLineCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> multiple statements on the same line.

**规则约束 / 可调参数**: (无)

### pl-0106-only-one-return  —  `ONE_RETURN`

- **规则类**: `org.plumb.style.style.OnlyOneReturnCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method should have only one return statement.

**规则约束 / 可调参数**: (无)

### pl-0187-only-one-return-strict  —  `ONE_RETURN_STRICT`

- **规则类**: `org.plumb.style.style.OnlyOneReturnPerMethodCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method should have only one return (strict variant).

**规则约束 / 可调参数**: (无)

### pl-619-operator-wrap  —  `OPERATOR_WRAP`

- **规则类**: `org.plumb.style.style.OperatorWrapCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Operator wrap placement.

**规则约束 / 可调参数**: (无)

### pl-0290-optional-get  —  `OPTIONAL_GET`

- **规则类**: `org.plumb.style.style.OptionalGetCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-476
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Optional.get() without isPresent() — use orElse/orElseThrow.

**规则约束 / 可调参数**: (无)

### pl-650-optional-return  —  `OPTIONAL_RETURN`

- **规则类**: `org.plumb.style.style.OptionalReturnCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Optional return check.

**规则约束 / 可调参数**: (无)

### pl-0487-outer-type-number  —  `TYPE_NUMBER`

- **规则类**: `org.plumb.style.style.OuterTypeNumberCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> outer type number (only one outer type per file).

**规则约束 / 可调参数**: (无)

### pl-0709-overload-methods-order  —  `OVERLOAD_METHODS_DECLARATION_ORDER`

- **规则类**: `org.plumb.style.style.OverloadMethodsDeclarationOrderCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Overloaded methods should be declared together.

**规则约束 / 可调参数**: (无)

### pl-651-overriding-must-invoke-super  —  `OVERRIDING_MUST_INVOKE_SUPER`

- **规则类**: `org.plumb.style.style.OverridingMethodMustInvokeSuperCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Overriding method must invoke super.

**规则约束 / 可调参数**: (无)

### pl-0586-package-annotation  —  `PACKAGE_ANNOTATION`

- **规则类**: `org.plumb.style.style.PackageAnnotationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> package-info.java should declare a package annotation.

**规则约束 / 可调参数**: (无)

### pl-0420-package-declaration  —  `PACKAGE_DECLARATION`

- **规则类**: `org.plumb.style.style.PackageDeclarationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> file should have a package declaration.

**规则约束 / 可调参数**: (无)

### pl-0454-package-info  —  `PACKAGE_INFO`

- **规则类**: `org.plumb.style.style.PackageInfoCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> missing package-info.java.

**规则约束 / 可调参数**: (无)

### pl-0609--param-hides-field  —  `HIDES_FIELD`

- **规则类**: `org.plumb.style.style.ParameterHidesFieldCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-1006
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method parameter shadows a class field.

**规则约束 / 可调参数**: (无)

### pl-621-paren-pad  —  `PAD`

- **规则类**: `org.plumb.style.style.ParenPadCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Parentheses padding.

**规则约束 / 可调参数**: (无)

### pl-0114-premature-declaration  —  `PREMATURE_DECLARATION`

- **规则类**: `org.plumb.style.style.PrematureDeclarationCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> variable declared without initializer.

**规则约束 / 可调参数**: (无)

### pl-0151-preserve-stack  —  `PRESERVE_STACK_TRACE`

- **规则类**: `org.plumb.style.style.PreserveStackTraceCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-209
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> throw new Exception() loses the original stack trace.

**规则约束 / 可调参数**: (无)

### pl-0455-printf-fail  —  `PRINTF_FAIL`

- **规则类**: `org.plumb.style.style.PrintfFailCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> System.out.printf/System.err.printf.

**规则约束 / 可调参数**: (无)

### pl-0292-printf-format  —  `PRINTF_FORMAT`

- **规则类**: `org.plumb.style.style.PrintfFormatCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> String.format / printf — verify format and arg count.

**规则约束 / 可调参数**: (无)

### pl-0724-print-stack-trace  —  `PRINT_STACK_TRACE`

- **规则类**: `org.plumb.style.style.PrintStackTraceCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-209
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Use a logger instead of Throwable.printStackTrace().

**规则约束 / 可调参数**: (无)

### pl-0233-proper-logger  —  `PROPER_LOGGER`

- **规则类**: `org.plumb.style.style.ProperLoggerCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: NAMING
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Logger field should be named LOG/LOGGER/log.

**规则约束 / 可调参数**: (无)

### pl-653-public-field-sync-context  —  `PUBLIC_FIELD_SYNC_CONTEXT`

- **规则类**: `org.plumb.style.style.PublicFieldAccessedInSynchronizedContextCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Public field accessed in synchronized context.

**规则约束 / 可调参数**: (无)

### pl-654-public-method-public-field  —  `PUBLIC_METHOD_PUBLIC_FIELD`

- **规则类**: `org.plumb.style.style.PublicMethodsPublicFieldCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Public method exposes public field.

**规则约束 / 可调参数**: (无)

### pl-0520-public-field-enum  —  `PUBLIC_FIELD_ENUM`

- **规则类**: `org.plumb.style.style.PublicProtectedFieldInEnumCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> enum field should be private.

**规则约束 / 可调参数**: (无)

### pl-0118-default-package  —  `DEFAULT_PACKAGE`

- **规则类**: `org.plumb.style.style.PublicStaticFieldAccessedInSubclassCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class is in the default package.

**规则约束 / 可调参数**: (无)

### pl-0036-public-static-mutable  —  `PUBLIC_STATIC_MUTABLE`

- **规则类**: `org.plumb.style.style.PublicStaticMutableFieldCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-668
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> "public static field of mutable type — use finaldefensive copy".

**规则约束 / 可调参数**: (无)

### pl-0312-pz-reuse-entry  —  `REUSE_ENTRY_OBJECTS`

- **规则类**: `org.plumb.style.style.PzReuseEntryCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-672
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> map entry object reused across iterations (PZ_DONT_REUSE_ENTRY_OBJECTS_IN_ITERATORS).

**规则约束 / 可调参数**: (无)

### pl-0313-qba-boolean-assign  —  `QUESTIONABLE_BOOLEAN_ASSIGNMENT`

- **规则类**: `org.plumb.style.style.QbaQuestionableBooleanCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-697
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> questionable boolean assignment (QBA_QUESTIONABLE_BOOLEAN_ASSIGNMENT).

**规则约束 / 可调参数**: (无)

### pl-655-questionable-boolean-assign  —  `QUESTIONABLE_BOOLEAN_ASSIGN`

- **规则类**: `org.plumb.style.style.QuestionableBooleanAssignmentCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Questionable boolean assignment.

**规则约束 / 可调参数**: (无)

### pl-registry-used_once  —  `USED_ONCE`

- **规则类**: `org.plumb.style.style.RandomUsedOnceCheck`
- **分类**: `style`
- **严重度**: ?
- **类型**: ?
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> new Random() used only once.

**规则约束 / 可调参数**: (无)

### pl-0037-reachable-assertion  —  `REACHABLE_ASSERTION`

- **规则类**: `org.plumb.style.style.ReachableAssertionCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> test method should include at least one assertion.

**规则约束 / 可调参数**: (无)

### pl-0039-redirect-without-exit  —  `REDIRECT_WITHOUT_EXIT`

- **规则类**: `org.plumb.style.style.RedirectWithoutExitCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-698
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> sendRedirect() / forward() should be followed by return.

**规则约束 / 可调参数**: (无)

### pl-0259-reduce-visibility  —  `REDUCE_VISIBILITY`

- **规则类**: `org.plumb.style.style.ReduceVisibilityCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> field visibility can be reduced to private.

**规则约束 / 可调参数**: (无)

### pl-0135-redundant-init  —  `REDUNDANT_FIELD_INITIALIZER`

- **规则类**: `org.plumb.style.style.RedundantFieldInitializerCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> redundant field initializer (already default value).

**规则约束 / 可调参数**: (无)

### pl-0722-redundant-import  —  `REDUNDANT_IMPORT`

- **规则类**: `org.plumb.style.style.RedundantImportCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: RedundantImport

**规则说明**

> Import is redundant (duplicates an earlier import).

**规则约束 / 可调参数**: (无)

### pl-0256-redundant-method  —  `REDUNDANT_METHOD_CALL`

- **规则类**: `org.plumb.style.style.RedundantMethodCallCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> redundant method — just delegates.

**规则约束 / 可调参数**: (无)

### pl-0295-regex-group  —  `GROUP`

- **规则类**: `org.plumb.style.style.RegexGroupCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> regex without groups — use String.matches.

**规则约束 / 可调参数**: (无)

### pl-0458-relational-symmetry  —  `RELATIONAL_SYMMETRY`

- **规则类**: `org.plumb.style.style.RelationalOperatorSymmetryCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> relational operator asymmetry.

**规则约束 / 可调参数**: (无)

### pl-622-require-this  —  `REQUIRE_THIS`

- **规则类**: `org.plumb.style.style.RequireThisCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Reference to instance member should be qualified with 'this'.

**规则约束 / 可调参数**: (无)

### pl-0611--ignored-result  —  `IGNORED_RESULT`

- **规则类**: `org.plumb.style.style.ResultOfMethodCallIgnoredCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-252
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> result of comparison-style method call is ignored.

**规则约束 / 可调参数**: (无)

### pl-0196-return-bool  —  `RETURN_BOOLEAN_LITERAL`

- **规则类**: `org.plumb.style.style.ReturnBooleanLiteralCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method always returns true/false — replace with constant.

**规则约束 / 可调参数**: (无)

### pl-0107-return-from-finally  —  `RETURN_FROM_FINALLY`

- **规则类**: `org.plumb.style.style.ReturnFromFinallyBlockCheck`
- **分类**: `style`
- **严重度**: HIGH
- **类型**: BUG
- **CWE**: CWE-584
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> return in finally block silently swallows exceptions.

**规则约束 / 可调参数**: (无)

### pl-623-right-curly  —  `CURLY`

- **规则类**: `org.plumb.style.style.RightCurlyCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: RightCurly

**规则说明**

> Right curly brace placement.

**规则约束 / 可调参数**: (无)

### pl-0315-rv-remove-repeat-calls  —  `REMOVE_REPEAT_METHOD_CALLS`

- **规则类**: `org.plumb.style.style.RvRemoveRepeatCallsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> same no-arg method called twice — store in a local (RV_REMOVE_REPEAT_METHOD_CALLS).

**规则约束 / 可调参数**: (无)

### pl-0316-rv-return-ignored  —  `RETURN_VALUE_IGNORED_BAD_PRACTICE`

- **规则类**: `org.plumb.style.style.RvReturnValueIgnoredCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-252
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> return value of this method is ignored (RV_RETURN_VALUE_IGNORED_BAD_PRACTICE).

**规则约束 / 可调参数**: (无)

### pl-0608--self-assignment  —  `ASSIGNMENT`

- **规则类**: `org.plumb.style.style.SelfAssignmentCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **CWE**: CWE-682
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> variable should not be self-assigned (no-op, likely copy-paste bug).

**规则约束 / 可调参数**: (无)

### pl-624-separator-wrap  —  `SEPARATOR_WRAP`

- **规则类**: `org.plumb.style.style.SeparatorWrapCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Separator wrap placement.

**规则约束 / 可调参数**: (无)

### pl-0264-bsk-serializable-subclass  —  `SERIALIZABLE_SUBCLASS`

- **规则类**: `org.plumb.style.style.SerializableSubclassCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Serializable parent without Serializable subclass (BSK_SERIALIZABLE_SUBCLASS).

**规则约束 / 可调参数**: (无)

### pl-657-serializable-super-ctor  —  `SERIALIZABLE_SUPER_CTOR`

- **规则类**: `org.plumb.style.style.SerializableSuperConstructorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Serializable super constructor.

**规则约束 / 可调参数**: (无)

### pl-0263-bit-signed-check  —  `SIGNED_CHECK`

- **规则类**: `org.plumb.style.style.SignedBitCheckCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-682
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> bitwise check on a signed value — fragile sign extension (BIT_SIGNED_CHECK).

**规则约束 / 可调参数**: (无)

### pl-658-sign-of-addition  —  `OF_ADDITION`

- **规则类**: `org.plumb.style.style.SignOfAdditionCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Sign of addition.

**规则约束 / 可调参数**: (无)

### pl-0317-si-instance-before-final  —  `INSTANCE_BEFORE_FINALS_ASSIGNED`

- **规则类**: `org.plumb.style.style.SiInstanceBeforeFinalCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-457
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> instance init reads static final before assignment (SI_INSTANCE_BEFORE_FINALS_ASSIGNED).

**规则约束 / 可调参数**: (无)

### pl-0116-simpledateformat-locale  —  `SIMPLEDATEFORMAT_LOCALE`

- **规则类**: `org.plumb.style.style.SimpleDateFormatNeedsLocaleCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-18
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> new SimpleDateFormat() needs explicit Locale.

**规则约束 / 可调参数**: (无)

### pl-0524-simple-date-format  —  `SIMPLE_DATE_FORMAT`

- **规则类**: `org.plumb.style.style.SimpleDateFormatWithoutLocaleCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> SimpleDateFormat should use Locale-specific constructor.

**规则约束 / 可调参数**: (无)

### pl-0423-simplify-bool-expr  —  `SIMPLIFY_BOOL_EXPR`

- **规则类**: `org.plumb.style.style.SimplifyBooleanExpressionCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> redundant boolean literal comparison (e.g. x == true).

**规则约束 / 可调参数**: (无)

### pl-0224-simplify-bool-return  —  `SIMPLIFY_BOOLEAN_RETURNS`

- **规则类**: `org.plumb.style.style.SimplifyBooleanReturnsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> return cond ? true: false — use return cond.

**规则约束 / 可调参数**: (无)

### pl-625-single-space-separator  —  `SINGLE_SPACE_SEPARATOR`

- **规则类**: `org.plumb.style.style.SingleSpaceSeparatorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Should use single space separator.

**规则约束 / 可调参数**: (无)

### pl-0098-singleton-returning-new  —  `SINGLETON_RETURNING_NEW`

- **规则类**: `org.plumb.style.style.SingletonClassReturningNewInstanceCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> utility class method should not return new instance.

**规则约束 / 可调参数**: (无)

### pl-0226-singular-field  —  `SINGULAR_FIELD`

- **规则类**: `org.plumb.style.style.SingularFieldCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> field is only used in one method — make it local.

**规则约束 / 可调参数**: (无)

### pl-659-sleep-without-interrupt  —  `WITHOUT_INTERRUPT`

- **规则类**: `org.plumb.style.style.SleepWithoutInterruptCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Sleep without interrupt handling.

**规则约束 / 可调参数**: (无)

### pl-0459-standard-functional  —  `STANDARD_FUNCTIONAL`

- **规则类**: `org.plumb.style.style.StandardFunctionalInterfaceCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> custom interface should be a @FunctionalInterface.

**规则约束 / 可调参数**: (无)

### pl-0525-start-greater-end  —  `GREATER_END`

- **规则类**: `org.plumb.style.style.StartWhereGreaterThanEndCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> loop start value greater than end value.

**规则约束 / 可调参数**: (无)

### pl-660-static-method-need-not-sync  —  `STATIC_METHOD_NEED_NOT_SYNC`

- **规则类**: `org.plumb.style.style.StaticMethodNeedNotBeSynchronizedCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Static method need not be synchronized.

**规则约束 / 可调参数**: (无)

### pl-0267-st-write-static-from-instance  —  `WRITE_TO_STATIC_FROM_INSTANCE_METHOD`

- **规则类**: `org.plumb.style.style.StaticWriteFromInstanceCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-668
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> instance method writes to static field (ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD).

**规则约束 / 可调参数**: (无)

### pl-0424-super-clone  —  `CLONE`

- **规则类**: `org.plumb.style.style.SuperCloneCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-668
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> clone() must call super.clone().

**规则约束 / 可调参数**: (无)

### pl-registry-instanceof  —  `INSTANCEOF`

- **规则类**: `org.plumb.style.style.SuperfluousInstanceofCheck`
- **分类**: `style`
- **严重度**: ?
- **类型**: ?
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> unnecessary instanceof check.

**规则约束 / 可调参数**: (无)

### pl-0588-suppress-warnings  —  `SUPPRESS_WARNINGS`

- **规则类**: `org.plumb.style.style.SuppressWarningsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> @SuppressWarnings should be specific.

**规则约束 / 可调参数**: (无)

### pl-661-suspicious-integer-division  —  `SUSPICIOUS_INTEGER_DIVISION`

- **规则类**: `org.plumb.style.style.SuspiciousIntegerDivisionCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Suspicious integer division.

**规则约束 / 可调参数**: (无)

### pl-0284-switch-default-last  —  `SWITCH_DEFAULT_LAST`

- **规则类**: `org.plumb.style.style.SwitchDefaultLastCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> switch default should be the last case.

**规则约束 / 可调参数**: (无)

### pl-0057-switch-density  —  `SWITCH_DENSITY`

- **规则类**: `org.plumb.style.style.SwitchDensityCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **SpotBugs**: SF_SWITCH_NO_DEFAULT

**规则说明**

> switch case body exceeds 5 statements.

**规则约束 / 可调参数**: (无)

### pl-0209-switch-default  —  `SWITCH_WITHOUT_DEFAULT`

- **规则类**: `org.plumb.style.style.SwitchWithoutDefaultCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> switch should have a default branch.

**规则约束 / 可调参数**: (无)

### pl-0250-switch-few  —  `SWITCH_FEW_CASES`

- **规则类**: `org.plumb.style.style.SwitchWithTooFewCasesCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> switch has fewer than 3 cases — use if-else.

**规则约束 / 可调参数**: (无)

### pl-0318-swl-sleep-lock  —  `SLEEP_WITH_LOCK_HELD`

- **规则类**: `org.plumb.style.style.SwlSleepWithLockCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-820
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Thread.sleep inside synchronized (SWL_SLEEP_WITH_LOCK_HELD).

**规则约束 / 可调参数**: (无)

### pl-0282-synchronized-lock  —  `SYNCHRONIZED_LOCK`

- **规则类**: `org.plumb.style.style.SynchronizedLockCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-820
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> synchronized on a non-final field — use a private final lock.

**规则约束 / 可调参数**: (无)

### pl-0041-system-exit  —  `SYSTEM_EXIT`

- **规则类**: `org.plumb.style.style.SystemExitUsageCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-382
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid System.exit() / Runtime.exit() in library code.

**规则约束 / 可调参数**: (无)

### pl-registry-system_out  —  `SYSTEM_OUT`

- **规则类**: `org.plumb.style.style.SystemOutCheck`
- **分类**: `style`
- **严重度**: ?
- **类型**: ?
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Avoid System.out / System.err in production code.

**规则约束 / 可调参数**: (无)

### pl-0723-system-out-println  —  `SYSTEM_OUT_PRINTLN`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `allowInMain` | BOOLEAN | `true` | Skip the main() method |
> | `allowInTest` | BOOLEAN | `true` | Skip methods whose name contains 'test' |
> | `includeErr` | BOOLEAN | `false` | Also check System.err.println/print |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `allowInMain` | BOOLEAN | `true` | Skip the main() method |
| `allowInTest` | BOOLEAN | `true` | Skip methods whose name contains 'test' |
| `includeErr` | BOOLEAN | `false` | Also check System.err.println/print |

### pl-0149-system-println  —  `SYSTEM_PRINTLN`

- **规则类**: `org.plumb.style.style.SystemPrintlnCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid System.out.println — use a logger.

**规则约束 / 可调参数**: (无)

### pl-0271-tab-character  —  `CHARACTER`

- **规则类**: `org.plumb.style.style.TabCharacterCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> avoid tab characters in source files.

**规则约束 / 可调参数**: (无)

### pl-0614--test-no-assertion  —  `NO_ASSERTION`

- **规则类**: `org.plumb.style.style.TestWithoutAssertionCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: CORRECTNESS
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> test method should include at least one assertion.

**规则约束 / 可调参数**: (无)

### pl-0427-todo-comment  —  `COMMENT`

- **规则类**: `org.plumb.style.style.TodoCommentCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> TODO/FIXME/XXX comment should be resolved.

**规则约束 / 可调参数**: (无)

### pl-0227-too-many-fields  —  `MANY_FIELDS`

- **规则类**: `org.plumb.style.style.TooManyFieldsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class has too many fields (limit 15).

**规则约束 / 可调参数**: (无)

### pl-0042-too-many-locals  —  `MANY_LOCALS`

- **规则类**: `org.plumb.style.style.TooManyLocalsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method declares more than 15 locals.

**规则约束 / 可调参数**: (无)

### pl-0228-too-many-methods  —  `MANY_METHODS`

- **规则类**: `org.plumb.style.style.TooManyMethodsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> class has too many methods (limit 30).

**规则约束 / 可调参数**: (无)

### pl-0043-parameter-number  —  `PARAMETER_NUMBER`

- **规则类**: `org.plumb.style.style.TooManyParametersCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> method / constructor has more than 7 parameters.

**规则约束 / 可调参数**: (无)

### pl-0327-uc-useless-object  —  `USELESS_OBJECT`

- **规则类**: `org.plumb.style.style.UcUselessObjectCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> useless object — new X() result discarded (UC_USELESS_OBJECT).

**规则约束 / 可调参数**: (无)

### pl-0260-bc-unconfirmed-cast  —  `UNCONFIRMED_CAST`

- **规则类**: `org.plumb.style.style.UnconfirmedCastCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-704
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> cast without matching instanceof check (BC_UNCONFIRMED_CAST).

**规则约束 / 可调参数**: (无)

### pl-663-unnecessary-enum-case  —  `UNNECESSARY_ENUM_CASE`

- **规则类**: `org.plumb.style.style.UnnecessaryEnumCaseCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Unnecessary case in enum switch.

**规则约束 / 可调参数**: (无)

### pl-0059-unnecessary-local-before-return  —  `UNNECESSARY_LOCAL_BEFORE_RETURN`

- **规则类**: `org.plumb.style.style.UnnecessaryLocalBeforeReturnCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> return the expression directly (no need for the local).

**规则约束 / 可调参数**: (无)

### pl-0428-unnecessary-parens  —  `UNNECESSARY_PARENS`

- **规则类**: `org.plumb.style.style.UnnecessaryParenthesesCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> redundant parentheses around expression.

**规则约束 / 可调参数**: (无)

### pl-0255-paren-method  —  `METHOD_CALL`

- **规则类**: `org.plumb.style.style.UnnecessaryParenthesesMethodCallsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> parentheses around method call are unnecessary.

**规则约束 / 可调参数**: (无)

### pl-0729-unnecessary-return  —  `UNNECESSARY_RETURN`

- **规则类**: `org.plumb.style.style.UnnecessaryReturnCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **PMD**: UnnecessaryReturn

**规则说明**

> Final 'return' in void method is unnecessary.

**规则约束 / 可调参数**: (无)

### pl-0099-unused-assignment  —  `UNUSED_ASSIGNMENT`

- **规则类**: `org.plumb.style.style.UnusedAssignmentCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BUG
- **CWE**: CWE-563
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> variable is assigned but never used (or overwritten immediately).

**规则约束 / 可调参数**: (无)

### pl-0101-unused-formal-parameter  —  `UNUSED_FORMAL_PARAMETER`

- **规则类**: `org.plumb.style.style.UnusedFormalParameterCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **CWE**: CWE-1164
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> unused private method parameter — remove it.

**规则约束 / 可调参数**: (无)

### pl-0103-style-unused-import  —  `UNUSED_IMPORT`

- **规则类**: `org.plumb.style.style.UnusedImportCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> unused import — remove it.

**规则约束 / 可调参数**: (无)

### pl-0721-unused-imports  —  `UNUSED_IMPORTS`

- **规则类**: `org.plumb.style.style.UnusedImportsCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **Checkstyle**: UnusedImports

**规则说明**

> Import is never used.

**规则约束 / 可调参数**: (无)

### pl-0044-unused-label  —  `UNUSED_LABEL`

- **规则类**: `org.plumb.style.style.UnusedLabelCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> label is never used (dead code).

**规则约束 / 可调参数**: (无)

### pl-0430-unused-private-field  —  `UNUSED_PRIVATE_FIELD`

- **规则类**: `org.plumb.style.style.UnusedPrivateFieldCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> private field is never read.

**规则约束 / 可调参数**: (无)

### pl-0461-uppercase-suffix  —  `UPPERCASE_SUFFIX`

- **规则类**: `org.plumb.style.style.UppercaseSuffixCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> long literal should use uppercase L.

**规则约束 / 可调参数**: (无)

### pl-0492-upper-ell  —  `ELL`

- **规则类**: `org.plumb.style.style.UpperEllCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> long literal should use uppercase L (L not l).

**规则约束 / 可调参数**: (无)

### pl-0090-use-assertnull  —  `ASSERTNULL_INSTEAD_OF_ASSERTTRUE`

- **规则类**: `org.plumb.style.style.UseAssertNullInsteadOfAssertTrueCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use assertNull() instead of assertTrue(x == null).

**规则约束 / 可调参数**: (无)

### pl-0091-use-assertsame  —  `ASSERTSAME_INSTEAD_OF_ASSERTTRUE`

- **规则类**: `org.plumb.style.style.UseAssertSameInsteadOfAssertTrueCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use assertSame() instead of assertTrue(a == b) for identity.

**规则约束 / 可调参数**: (无)

### pl-0092-use-concurrenthashmap  —  `CONCURRENT_HASHMAP`

- **规则类**: `org.plumb.style.style.UseConcurrentHashMapCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **CWE**: CWE-362
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> HashMap field is not thread-safe — use ConcurrentHashMap.

**规则约束 / 可调参数**: (无)

### pl-0096-use-diamond  —  `DIAMOND_OPERATOR`

- **规则类**: `org.plumb.style.style.UseDiamondOperatorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**:
  - **PMD**: UseDiamondOperator

**规则说明**

> use <> (diamond) operator for generic type inference.

**规则约束 / 可调参数**: (无)

### pl-0046-use-enum-instead-of-iterator  —  `ENUM_INSTEAD_OF_ITERATOR`

- **规则类**: `org.plumb.style.style.UseEnumerationInsteadOfIteratorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use Iterator API instead of legacy Enumeration.

**规则约束 / 可调参数**: (无)

### bu-0904-useless-object  —  `USELESS_OBJECT`

- **规则类**: `org.plumb.style.style.UselessObjectCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Object created and discarded without use.

**规则约束 / 可调参数**: (无)

### pl-666-useless-op-immutable  —  `USELESS_OP_IMMUTABLE`

- **规则类**: `org.plumb.style.style.UselessOperationOnImmutableCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Useless operation on immutable.

**规则约束 / 可调参数**: (无)

### pl-886-use-object-clearer-api  —  `USE_OBJECT_CLEARER_API`

- **规则类**: `org.plumb.style.style.UseObjectForClearerAPI`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Use object for clearer API.

**规则约束 / 可调参数**: (无)

### pl-0052-use-try-with-resources  —  `TRY_WITH_RESOURCES`

- **规则类**: `org.plumb.style.style.UseTryWithResourcesCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **CWE**: CWE-400
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use try-with-resources instead of try-finally to close resources.

**规则约束 / 可调参数**: (无)

### pl-0229-use-varargs  —  `VARARGS`

- **规则类**: `org.plumb.style.style.UseVarargsCheck`
- **分类**: `style`
- **严重度**: MEDIUM
- **类型**: BAD_PRACTICE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> use varargs (...) instead of array as last parameter.

**规则约束 / 可调参数**: (无)

### pl-0319-uwf-field-not-init  —  `FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR`

- **规则类**: `org.plumb.style.style.UwfFieldNotInitializedCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> field not initialized in constructor (UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR).

**规则约束 / 可调参数**: (无)

### pl-0064-visibility-modifier  —  `VISIBILITY_MODIFIER`

- **规则类**: `org.plumb.style.style.VisibilityModifierCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> field should declare an explicit visibility modifier.

**规则约束 / 可调参数**: (无)

### pl-0713-wait-notify-in-loop  —  `WAIT_NOTIFY_IN_LOOP`

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
> | Name | Type | Default | Description |
> | ---- | ---- | ------- | ----------- |
> | `requireSyncBlock` | BOOLEAN | `true` | Also require enclosing synchronized block |

**规则约束 / 可调参数**

| 参数名 | 类型 | 默认值 | 说明 |
| ------ | ---- | ------ | ---- |
| `requireSyncBlock` | BOOLEAN | `true` | Also require enclosing synchronized block |

### pl-0142-while-literal-boolean  —  `LOOP_WITH_LITERAL_BOOLEAN`

- **规则类**: `org.plumb.style.style.WhileLoopWithLiteralBooleanCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> while(true) / for(;;) — use break or extract condition.

**规则约束 / 可调参数**: (无)

### pl-626-whitespace-after  —  `WHITESPACE_AFTER`

- **规则类**: `org.plumb.style.style.WhitespaceAfterCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Whitespace after token.

**规则约束 / 可调参数**: (无)

### pl-627-whitespace-around  —  `WHITESPACE_AROUND`

- **规则类**: `org.plumb.style.style.WhitespaceAroundCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: STYLE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> Whitespace around token.

**规则约束 / 可调参数**: (无)

### pl-0320-wmi-wrong-iter  —  `WRONG_MAP_ITERATOR`

- **规则类**: `org.plumb.style.style.WmiWrongMapIteratorCheck`
- **分类**: `style`
- **严重度**: LOW
- **类型**: PERFORMANCE
- **外部等价规则**: (无明显等价规则或为本仓库自研启发式)

**规则说明**

> "iterate keySetget(key) — use entrySet (WMI_WRONG_MAP_ITERATOR)".

**规则约束 / 可调参数**: (无)

## 新增 / 修改规则

新增一条规则的步骤（无需改 plumb-style 的 package 布局）：

1. 在合适的子包下新建 `*Check.java`（如 `src/main/java/org/plumb/style/naming/MyNewCheck.java`）。package 声明要与目录一致：`package org.plumb.style.naming;`。
2. `implements AstChecker`，实现 `name()`、`init(StyleContext)`，以及 需要的 `visit(...)` 方法。
3. 在 `visit(...)` 内部用 `ctx.report(line, col, rule, msg, severity, dataflowHead)` 上报。
4. 在 `src/test/java/org/plumb/style/` 下加一个对应的 `MyNewCheckTest.java`（或加进 `AllCheckersSmokeTest`），用最小源码片段验证 fire / no-fire。
5. 跑 `python scripts/gen_rules_doc.py` 重新生成本文档。

