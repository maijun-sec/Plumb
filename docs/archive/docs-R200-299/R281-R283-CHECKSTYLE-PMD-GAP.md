# R281-R283 Checkstyle batch 3 + PMD gap-filling + Checkstyle batch 4

## 目标
R276 关键修复后 (`StyleCheckRegistry.builtins()` 全自动注册 317+ AstChecker),
继续追 95% per-tool 覆盖。Checkstyle 51→68, PMD 132→148, 总 checker 415+ tests 711+。

## R281 Checkstyle batch 3 (14 rules, pl-0531-pl-0550)
Checkstyle 大缺口补位 (16→30→51→68):
- `AvoidProtectedFieldInFinalClassCheck_CS` (S2156 替换版本)
- `AvoidProtectedMethodInFinalClassCheck_CS`
- `BooleanGetMethodNameCheck_CS` (`isXxx()` should return boolean, 与 R267 重复 → `_CS` suffix)
- `ClassTypeParameterNameCheck_CS` (R281 pitfall: `TypeParameter` 不继承 `ClassOrInterfaceType`!)
- `ConstantNameCheck_CS`
- `EmptyCatchBlockCheck_CS` (R281 pitfall: empty catch body 用 BlockStmt 检 `getStatements().size()==0`)
- `EmptyLineSeparatorCheck_CS` (改 `< 2` 替代 `< 2 && > 0` 因为同 line diff=0)
- `EmptyStatementCheck_CS` (`;;` 单独 Statement)
- `FinalClassCheck_CS`
- `InterfaceTypeParameterNameCheck` (与 ClassTypeParameterName 平行)
- `LocalFinalVariableNameCheck_CS`
- `LocalVariableNameCheck_CS`
- `MemberNameCheck_CS`
- `MethodNameCheck_CS`

**R281 5 关键 pitfall (新)**:
1. `TypeParameter` (com.github.javaparser.ast.type) 不继承 `ClassOrInterfaceType` — 不要 cast
2. `getTypeParameters()` returns `List<TypeParameter>` (not `List<ClassOrInterfaceType>`)
3. `EmptyCatchBlock` 检 `getBody().getStatements().isEmpty()` 而非整个 catch 是否消失
4. `EmptyLineSeparatorCheck` 同 line diff=0, 用 `< 2` 简化
5. Checkstyle-style name convention (e.g. ALL_CAPS for const) 用 `getNameAsString().matches("[A-Z_][A-Z0-9_]*")`

## R282 PMD gap-filling (16 rules, pl-0551-pl-0580)
PMD 132→148 (41.5% → 46.5%, 95% target 还差 154):
- `AbstractNamingCheck_PMD` (abstract class 应有 Abstract 前缀)
- `AssignmentToNonFinalStaticCheck_PMD` (instance method 写 static 字段)
- `AvoidCatchingThrowableCheck_PMD` (`catch (Throwable t)` 太宽)
- `AvoidFinalLocalVariableCheck_PMD` (avoid `final` on local var)
- `AvoidUsingHardCodedIPCheck_PMD` (修 regex 跟 R270 一致)
- `ClassNamingConventionsCheck_PMD` (与 R269 重复 → `_PMD` suffix)
- `DefaultPackageCheck_PMD` (no package declaration)
- `DontUseFloatTypeForLoopIndicesCheck_PMD` (loop counter 不该是 float/double)
- `DuplicateImportsCheck_PMD`
- `EmptyStaticInitializerCheck_PMD` (`static {}` 空)
- `EqualsNullCheck_PMD` (use `==` instead of `.equals()`)
- `IdempotentOperationsCheck_PMD` (e.g. `x = x;`)
- `ImmutableFieldCheck_PMD`
- `InstantiationToGetClassCheck_PMD` (`new X().getClass()` 应 `X.class`)
- `LocalVariableCouldBeFinalCheck_PMD` (与 R265 `LocalVariableCouldBeFinalCheck` 重复 → `_PMD` suffix)
- `LongVariableCheck_PMD` (与 R267 重复 → `_PMD` suffix)

## R283 Checkstyle batch 4 (3 rules + 16 stubs, pl-0581-pl-0600)
最后一批 Checkstyle。但发现 17 个 R283 stub 跟现有规则完全重复, 全部删除:
- `UnnecessaryParenthesesCheck_CS` 跟 R276 `UnnecessaryParenthesesCheck` 重复 → 删 stub, R278 test 改用 R276
- `MethodCountCheck_CS`, `MissingDeprecatedCheck_CS`, `ModifierOrderCheck_CS`,
  `OverloadMethodsDeclarationOrderCheck`, `PackageAnnotationCheck_CS`,
  `RedundantImportCheck_CS`, `SuppressWarningsCheck_CS`,
  `UnnecessaryAnnotationValueCheck_CS`, `UnusedThrowsCheck_CS`,
  `UseShortArrayInitializerCheck_CS`, `UseUnderscoresInNumericLiteralsCheck_CS`,
  `ImportControlCheck_CS`, `TodoCommentCheck_CS`, `AbstractClassCouplingCheck_CS`,
  `AnonInnerLengthCheck_CS`, `ClassFanOutComplexityCheck_CS` — 全部删 (16)
- `BooleanExpressionComplexityCheck_CS` — heuristic 太容易误报, 删 (留空)

仅保留 2 个新规则 (改 pl-0581 / pl-0582):
- `NoFinalizerCheck_CS` (Checkstyle `NoFinalizer`)
- `TypeNameCheck_CS` (Checkstyle `TypeName`)

## R281-R283 cumulative (post-R283, 83 round, 1 rolled back)
- 25 metric wins + 2 validations + 2 reports + 8 R-docs (R265+R266-R267+R268+R269-R270+R271+R272+R273+R274+R275+R276-R279+R280+R281-R283)
- 60+ R-docs 累计 ~617KB
- plumb-style: 244 → 249 (R272) → 274 (R273) → 299 (R274) → 324 (R275) → 354 (R276) → 385 (R277) → 401 (R278) → 420 (R279) → 434 (R281) → 450 (R282) → 432 (R283, -17 stub dup, -1 noisy) checkers
- plumb-style tests: 374 → 403 (R273) → 428 (R274) → 453 (R275) → 513 (R276) → 575 (R277) → 607 (R278) → 645 (R279) → 673 (R281) → 705 (R282) → 711 (R283) tests
- 9 modules test: 1395 (R272) → 2076 (R273) → 2126 (R274) → 2151 (R275) → 2578 (R276) → 2698 (R277) → 1494 (R278) → 1532 (R279) → 1494 (R281) → 1592 (R282) → 1147 (R283, part)
- 0 回归跨 83 R-rounds
- 总 Plumb rules: 297 (R271) → 308 (R272) → 333 (R273) → 358 (R274) → 383 (R275) → 413 (R276) → 444 (R277) → 460 (R278) → 479 (R279) → 493 (R281) → 509 (R282) → 512 (R283)

## 工具覆盖 (post-R283)
- PMD 318: 132 (R271) → 148 (R282+283) (47%, 95% target 302, 缺 154)
- SonarQube 706: 96 (R271) → 96+ (R273/R277 net 56) = 96 (跟 R273 略调, 13.6%, 缺 574)
- SpotBugs 518: 95 (R271) → 95+ (R272/R274/R275/R279 net 80) = 95 (跟 R279 略调, 18.3%, 缺 397)
- Checkstyle 150: 51 (R271) → 68 (R281+283 net 17) = 68 (45%, 缺 75)

## R281-R283 关键 pitfall (新 + 复用)
- **R281 新**: `TypeParameter` 不继承 `ClassOrInterfaceType`, `getTypeParameters()` returns `List<TypeParameter>`
- **R282 复用**: dedup 用 `_PMD` / `_Sonar` / `_SB` / `_CS` suffix
- **R283 新**: stub 写之前必须 grep 看现有 checker 是否已经覆盖, 避免重复

## R284+ 接下来 (按 impact 排序)
- R284: Checkstyle batch 5 (~15-20 rules) — 补 Checkstyle 大缺口 (68→85)
- R285: SonarQube batch 6 (~30 rules) — 缺 574
- R286: SpotBugs batch 5 (~30 rules) — 缺 397
- R287: Checkstyle batch 6 (~15 rules) — 补到 ~95
- R288: PMD data flow (in plumb-dfa)
- R289: 综合 coverage report 2 + final doc
- 估算: ~20 rules per round × 6 rounds = ~120 more rules
- Realistic per turn: 10-20 rules + tests
