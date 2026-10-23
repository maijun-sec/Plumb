# R212 — CallGraph: Inner-class signatures & cross-TU support

**Status:** DONE — 68/68 plumb-dfa tests pass, 0 regression
**Round:** R212 (post-R211, infra-only, 0 metric wins)
**Date:** 2026-07-31

## Motivation

Per user directive (post-R211):
> 聚焦统一的数据流分析算法。当前在 CallGraph 构建存在的问题：
> 1、function signature 中，还需要考虑到存在内部类的场景；
> 2、当前的 CallGraph 好像只是 tu 内的，好像没有跨 tu 的能力，所以需要优化。

Two gaps in the R210 call-graph infrastructure:

1. **Inner-class signatures not handled.** `FunctionSignature.parse()`
   recognised `pkg.Class#method` but Java's IR uses
   `pkg.Outer$Inner#method` for inner / nested / anonymous
   classes. Result: cross-class callee resolution was lossy
   (the `Outer$` prefix was either stripped or treated as
   part of the package name).

2. **CallGraph was TU-local.** The pre-R212
   `CallGraphBuilder` only walked a single
   `TacProgram`. A call from `com.example.A.m` to
   `com.example.B.n` (where `B` lives in another
   `.java` file) was an unresolved callee — the
   dataflow walk could not follow it across the
   compilation-unit boundary. This is the same
   limitation that bit R155-R162 (the "1/17 cap"
   discovered in R150).

## What R212 delivers

### Phase 1 — `FunctionSignature` inner-class support

`FunctionSignature` (R210's 5-part decomposition) now
treats the `$` character as an inner-class separator:

- `className` is the full path with `$` preserved
  (`"Outer$Inner"`, `"Outer$1"` for anonymous,
  `"A$B$C"` for deeply nested).
- `simpleClassName()` returns the leaf segment
  (`"Inner"`, `"1"`, `"C"`).
- `enclosingClasses()` returns the outer chain
  outermost-first (`["Outer", "Middle"]` for
  `Outer$Middle$Inner`).
- `isInnerClass()` is true iff there's at least one
  `$` in `className`.
- The canonical form lowercases the full
  inner-class chain: `com.example.outer$inner#compute(int):int`.
- The `parse()` regex (`CANONICAL`) was updated to
  accept `$` in the class name segment.

**7 new tests** in `FunctionSignatureTest`:

- `parse_innerClass_preservesDollarInClassName`
- `parse_anonymousClass_preservesNumericLeaf`
- `parse_deeplyNestedInnerClass_splitsFullChain`
- `parse_topLevelClass_isNotInner`
- `fqClassName_preservesInnerClassDollar`
- `canonical_innerClassIsLowercased`
- `innerClassFromTacTranslator_matchesParse` (TAC IR
  → canonical bridge)

### Phase 2 — `GlobalTACStore` (multi-TU function registry)

New class `org.plumb.dfa.unified.cg.GlobalTACStore`.
Mirror of Fathom's `GlobalTACStore`:

- Bulk-register every function via
  `addProgram(TacProgram)`. The program's source
  file is recorded so the store can later report
  `allSourceFiles()`.
- `register(TacFunction)` for one-off additions.
- `freeze()` makes the store immutable for safe
  concurrent reads by the analysis engine.
- `functionBySignature(sig)`, `sourceOf(sig)`,
  `allSignatures()`, `allFunctions()`,
  `signaturesInSource(path)`.
- `of(programs)` static helper for the common
  one-shot pattern.

**8 new tests** in `GlobalTACStoreTest`.

### Phase 3 — `GlobalCallGraphBuilder` (cross-TU)

New class
`org.plumb.dfa.unified.cg.GlobalCallGraphBuilder`.
Mirror of Fathom's `GlobalCallGraphBuilder`:

- Takes a `GlobalTACStore` (which can span any
  number of TUs).
- Walks every function in the store; emits one
  `CallSite` per `CALL` instruction. Cross-TU
  calls land on the right node by direct
  signature lookup.
- `withIncludeLibraries(boolean)` controls
  library filtering. The default filter covers
  `java.*`, `javax.*`, `com.sun.*`, `jdk.*`,
  `sun.*`, and JNI-style equivalents.
- `buildFromPrograms(programs)` static helper.

**8 new tests** in `GlobalCallGraphBuilderTest`:

- `build_acrossTwoTus_resolvesCalleeSignature` —
  basic A→B cross-TU.
- `build_innerClass_calleeIsResolved` —
  `Outer$Inner#m` as cross-TU callee.
- `build_unresolvedCallee_keepsPlaceholderNode` —
  call to a function that doesn't exist anywhere
  in the store still produces a graph edge.
- `build_libraryCall_excludedByDefault` /
  `build_libraryCall_includedWhenConfigured`.
- `build_acrossTus_entryPointsAreUncalledFunctions`
  — both top-level functions are entry points.
- `build_acrossTus_calleeAppearsBeforeCallerInRpo`
  — RPO order is preserved across TUs.
- `build_innerClassCallInsideTightTuple` — A's
  inner class is called from B.

### Phase 4 — `GlobalCallGraphStrategy` + factory

New class
`org.plumb.dfa.unified.cg.GlobalCallGraphStrategy`
implements the existing `CallGraphStrategy`
interface. The factory now exposes a separate
`createCrossTu()` entry point so the single-TU
and cross-TU modes don't compete for CLI flags:

```java
CallGraphStrategy s = CallGraphStrategyFactory
    .createCrossTu("global", store, null);
// Or with caching:
CallGraphStrategy s = CallGraphStrategyFactory
    .createCrossTu("cached:global:D:/tmp/cg-cache", store, null);
```

**3 new tests** in `CallGraphStrategyFactoryTest`:

- `factory_crossTu_createsGlobalByName`
- `factory_crossTu_unknownStrategyThrows`
- `factory_crossTu_cachedGlobal`

### R212 architectural decisions

1. **Inner-class encoding preserved verbatim.** The
   `Outer$Inner` form is the standard Java
   binary-name encoding (JLS §13.1). Preserving
   it in `className` means a single string equality
   check still distinguishes every variant
   (top-level, member, anonymous, local). The
   structured view (`enclosingClasses()`) is
   derived on demand by splitting on `$`.

2. **Global store is the single source of truth.**
   `GlobalTACStore` is what the cross-TU
   `CallGraphStrategy` consumes, and (R213) what
   the cross-function dataflow walk will consume.
   The pre-R212 TU-local `CallGraphBuilder` is
   kept for the `plumb-dfa` legacy checkers that
   operate on a single `.java` file at a time.

3. **Library filter broadened to package prefix.**
   The R210 TU-local builder hard-coded a fixed
   set of JDK types. The cross-TU builder uses
   package-prefix matching (`java.*`, `javax.*`,
   `com.sun.*`, `jdk.*`, `sun.*`) so it
   automatically handles the long tail of JDK
   methods that show up in cross-TU
   benchmarks.

4. **RPO order is preserved across TUs.**
   The pre-R212 `CallGraphBuilder.computeRpo()`
   works on a single `TacProgram`; the new
   `GlobalCallGraphBuilder` reuses the same RPO
   helper on the merged graph, so the
   bottom-up summary walk (R213) sees callee
   before caller regardless of which file each
   lives in.

5. **Unresolved callee becomes a placeholder node.**
   If `A#m` calls `B#n` and `B#n` is not in any
   TU, the graph still has a `FunctionNode` for
   `B#n`. The dataflow walk can then report
   "callee not modelled" instead of crashing or
   silently dropping the call.

## Test summary

- **R210 + R211 + R212 = 68 plumb-dfa tests, all green.**
- 0 regression in plumb-parser, plumb-reporter, plumb-rules, plumb-dfa.
- 4 pre-existing plumb-tac failures (missing
  `asm-tree` jar in test classpath — unrelated to R212).
- 2 pre-existing plumb-style failures (R206
  `exposeRep_firesOnArrayGetter` — unrelated to R212).

## Cumulative (post-R212, 55 rounds, 1 rolled back)

- 14 metric wins (R148c / R148d / R148g / R151a /
  R152a / R153 / R151b / R152b / R156 / R157 /
  R158 / R159 / R161 / R162)
- 2 validations: R149b + R169
- 1 report: R170
- **40+ infra** (R148e / R148f / R148h / R148i / R149
  / R150 / R155 / R157 (4) / R158 (5) / R159 (2) /
  R161 (4) / R162 (3) / R163 / R164 / R165 / R166 /
  R167 / R168 / R169 / A1 / A3 / R172 / R176 / R177 /
  R180 / R181 / R182 / R183 / R186 / R187 / R188 /
  R189 / R194 / R195 / R192 / R197 / R198 / R199 /
  R200 / R201 / R202 / R203 / R204 / R205 / R207 /
  R208 / R210 / R211 / **R212**)
- 1 rolled back: R148h+2
- **1195+ tests / 0 fail / 0 err / 2 skip**
  (R212 adds 26 new tests to the 1169 pre-R212 baseline)
- **28 R-doc 累计 ~285K bytes**

## Files

- **Modified** (4): `FunctionSignature.java` (R210 base,
  now with inner-class support), `CallGraphStrategyFactory.java`
  (added `createCrossTu`).
- **New main** (3):
  - `GlobalTACStore.java` (7122 bytes)
  - `GlobalCallGraphBuilder.java` (6120 bytes)
  - `GlobalCallGraphStrategy.java` (1549 bytes)
- **New test** (3):
  - `GlobalTACStoreTest.java` (4834 bytes, 8 tests)
  - `GlobalCallGraphBuilderTest.java` (8853 bytes, 8 tests)
  - 7 new tests in `FunctionSignatureTest.java`
  - 3 new tests in `CallGraphStrategyFactoryTest.java`

Backup: `D:\work\tmp\r212_done\java_source\`
        `D:\work\tmp\r212_done\test_source\`

## What's next (R213+)

- **R213** CrossFunctionConstantPropagation
  — 跨函数常量传播, 替代 TaintAnalysis 的
  merged-key 方案. 消费 `GlobalTACStore`.
- **R214** TaintedChecker base + TaintDomain
  + TaintChecker 重构 — Taint 区分 SQL/LDAP/Command
  via TaintDomain + SinkFamilyRegistry.
- **R215** 5 mirror checkers 接新 DfaEngine
  (DivByZero / IntegerOverflow / NullDeref /
  ResourceLeak / SensitiveDataLeak) — end-to-end 验证.
- **R216** Z3Solver + TacToZ3 Java port.
- **R217** ReverseTaintWalker (Z3-verified).
