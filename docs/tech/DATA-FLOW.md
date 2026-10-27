# Dataflow Analysis (plumb-dfa)

[English](../README.md) | [中文](../zh/tech/DATA-FLOW.md)

This document describes the actual implementation of `plumb-dfa`. Everything here was checked against the source code; every class name mentioned in the text can be found under
`plumb-dfa/src/main/java/org/plumb/dfa/`, and references use the format `file:line`.

The engine is neither IFDS nor a tabulation solver; it is a per-checker forward dataflow engine,
paired with an independent points-to solver and a Z3 false-positive filter that is only enabled in specific checks.

---

## 1. Engine skeleton

### 1.1 Entry point and the two phases

The entry point is `DfaEngine` (`engine/DfaEngine.java`), with three run modes exposed:

| Method | Purpose |
| --- | --- |
| `run(TacProgram)` (`:261`) | Single program |
| `runSequential(TacProgram)` (`:1022`) | Single-threaded |
| `runParallel(TacProgram, int)` (`:1002`) | Top-level worklist parallelism |
| `runWithGlobalStore(List<TacProgram>, int)` (`:340`) | Cross-file global store (the CLI default strategy `global`) |

All modes eventually go through the same skeleton. The real scheduling happens in `runWorklist` (`:1081`):

```
initEngine(ctx)              // once for all checkers
warmUpMethodSummaries(...)   // path-insensitive, syntax-level summary warm-up
computeParamOrigins(...)     // cross-function parameter origin labels, propagated to the fixpoint
solvePointsTo(...)           // whole-program points-to, solved once
  ↓
each function (queued only when its dependency count reaches zero):
  analyseOneFunction(ctx, fn)      // forward instruction traversal
  summariseOneFunction(ctx, fn)    // collect summaries into the shared cache
  ↓
finish(ctx)
```

### 1.2 What "backward flow-insensitive + forward flow-sensitive" actually corresponds to

This is the place where documentation most easily goes wrong, so it is checked against the code item by item:

| Claim | Corresponding code | Accurate? |
| --- | --- | --- |
| Backward, flow-insensitive pre-analysis | `warmUpMethodSummaries` (`:606`) → `MethodSummaryBuilder.build` | **Partially accurate**. It is a single linear IR scan that classifies the return shape and collects side effects, with **no dataflow solving**. It is a path-insensitive syntactic summary, not a backward flow analysis. |
| Forward, flow-sensitive instruction traversal | `analyseOneFunction` (`:1214`), traversing in `bbRpoOrder(fn)` order | **Accurate**. Every checker maintains its own forward lattice (taint / nullable / interval) and propagates it over the instructions. |
| Points-to is an independent pre-solve | `solvePointsTo` (`:667`) | **Accurate**. One whole-program solve; the result hangs off `AnalysisContext`, and all checkers share the same fixpoint. |

**Key fact: the engine does not have "a backward pass followed by a separate forward pass".** There is only one
forward traversal; the summaries are a product of that traversal, not the input of another analysis pass.

### 1.3 Instruction order: BB-RPO, forward within a block

`bbRpoOrder(TacFunction)` (`:1317`):

- Basic blocks are ordered in **reverse post-order** (reverse the DFS post-order, `:1347-1350`)
- The instructions inside a block keep their **original forward order** (`:1358-1363`)

Why the block is not reversed: every checker in the engine is a forward lattice, and reversing a block would put the use before the def, breaking propagation outright.
(The `DfaEngine` class comment once claimed "BB-RPO + intra-block reverse", which does not match the implementation and has been corrected.)

### 1.4 Checker SPI

`DfaChecker` (`engine/DfaChecker.java`):

```
initEngine(ctx)
  for fn in callgraph RPO:
    initFunction(ctx, fn)
    for ins in bbRpoOrder(fn):
        if ins is CALL:  SummaryApplier.apply(ins, ctx, ins.args)
                         sideEffectConsumer().recordCall(ins, ctx)
        checkInstruction(ins, fn, ctx)
    finishFunction(ctx, fn)
  finish(ctx)
```

**The summary contract**: defects found inside a function body are reported directly through `ctx.reporter().report(...)` and **do not go into the summary**;
only "what the caller needs to know" goes into `FunctionSummary`, merged by `FunctionSummary.merge` into
`ctx.summaryCache()` and indexed by the function signature. On the caller side, when a CALL is encountered, `SummaryApplier.apply` consumes it.

---

## 2. The fact domain

The `Fact` in `fact/Fact.java` is the quadruple `(FactKind, operand, value, defining instruction)`.

`FactKind.Trait` is **not a mechanism the engine executes, but a declaration of intent**. No production code reads a trait:
`FactKind.has(Trait)` exists, but only tests call it; the engine never does. The actual propagation of a fact is implemented
by **the checker that owns it** — taint propagation happens directly in `taint/TaintedCheckerBase.propagateMOVE(...)`,
independently of traits. `plumb-dfa/src/test/java/org/plumb/dfa/fact/FactTraitSemanticsTest.java`
pins this fact down: it asserts that no `.has(...Trait...)` call exists in the production code.

> Why this is worth spelling out: `PROPAGATED_BY_DECL` reads like a switch — with it, the engine should copy the fact from
> the definition to all uses. That is not what happens. Keeping the traits is justified (they keep the catalogue readable,
> and they are the input for future trait-driven propagation), but they **are not a mechanism today**.

`BuiltinFactKinds.registryWithBuiltins()` registers **15** built-in kinds
(`BuiltinFactKinds.java:225-320`):

| kind name | Constant | Location | Declared traits |
| --- | --- | --- | --- |
| `dfa-taint-untrusted-source` | `UNTRUSTED_SOURCE` | `:56` | SOURCE, BY_DECL, THROUGH_CALL |
| `dfa-taint-untrusted-sink` | `UNTRUSTED_SINK` | `:66` | SINK, IS_PATH_SINK |
| `plumb.nullable.may-be-null` | `MAY_BE_NULL` | `:77` | BY_DECL |
| `plumb.numeric.may-be-zero` | `MAY_BE_ZERO` | `:85` | BY_DECL |
| `plumb.resource.alloc-source` | `ALLOC_SOURCE` | `:96` | BY_DECL, THROUGH_CALL |
| `plumb.resource.free-sink` | `FREE_SINK` | `:106` | IS_PATH_SINK |
| `plumb.pointer.deref-sink` | `DEREF_SINK` | `:117` | IS_PATH_SINK |
| `plumb.array.index-sink` | `INDEX_SINK` | `:125` | IS_PATH_SINK |
| `plumb.numeric.overflow-hazard` | `NUMERIC_OVERFLOW_HAZARD` | `:135` | NOT_PROPAGATED_THROUGH_LOOP, IS_PATH_SINK |
| `plumb.sensitive.data` | `SENSITIVE_DATA` | `:146` | SOURCE, BY_DECL, THROUGH_CALL |
| `plumb.sensitive.pii` | `SENSITIVE_PII` | `:156` | SOURCE, BY_DECL, THROUGH_CALL |
| `plumb.loop.induction` | `LOOP_INDUCTION` | `:167` | NOT_PROPAGATED_THROUGH_LOOP |
| `plumb.call.unmodeled-callee-ret` | `UNMODELED_CALLEE_RET` | `:178` | BY_DECL, THROUGH_CALL |
| `plumb.call.return-value-folded` | `RETURN_VALUE_FOLDED` | `:204` | BY_DECL |
| `plumb.call.side-effect-recorded` | `SIDE_EFFECT_RECORDED` | `:216` | BY_DECL |

**These are two different catalogues, not one**: `org.plumb.dfa.config.ConfigLoader.parseFactKinds`
(`plumb-dfa/src/main/java/org/plumb/dfa/config/ConfigLoader.java:227-263`) first puts these 15 built-in kinds into the registry, then overwrites or
appends them with the **12** entries of `fact-kinds.yaml` — the YAML entries use a different naming scheme
(`UntrustedSource`, `MayBeNull`, `Freed`, etc.). The two naming spaces differ but they **land in the same registry**,
so the effective number of fact kinds is 27. `capability_baseline` measures the two catalogues separately
(`builtin:` / `yaml:` prefixes), and missing either one is rejected.

> Note that there are two `ConfigLoader`s: the one in `plumb-main` reads the scan configuration (YAML `inputs:` / `checks:`),
> while the one in `plumb-dfa` reads the engine configuration (`sink-families.yaml` / `fact-kinds.yaml` / `checkers.yaml`).
> The fact kinds belong to the latter.

**Important distinction**: the fact domain is not "one fact kind per analysis category". In fact only taint / sensitive
have source-like kinds; boundary, points-to and control-flow **have no corresponding fact kind** — they are computed by
their own independent mechanisms (see below).

---

## 3. The four analysis categories

### 3.1 Taint analysis (taint)

**Driver class**: `taint/TaintedCheckerBase.java` (note that it lives in the `dfa/taint/` package, not in
`dfa/checkers/taint/`). It **is both the abstract base class and the propagation engine** — which differs from the
"a subclass needs only a few lines" impression the documentation gives: the 45 concrete subclasses under
`checkers/taint/` override `checkInstruction` in bulk (there are 47 `extends TaintedCheckerBase` in the whole
repository, the other two being `BoundaryConstraintChecker` and the base class itself).

- State: `taintedVars: Map<TacVar, String>` (`:140`) and `taintPaths` (`:149`)
- Propagation: `propagateMOVE` (`:1158`) and friends, dispatched by instruction kind
- Sink decision: looks up `SinkFamilyRegistry` by `sinkFamilyKey` (`config/SinkFamilyRegistry.java:22`, in the
  `org.plumb.dfa.config` package), **not** an IFDS framework
- Cross-function: `FunctionSummary` + `Operation.TaintPropagate`

**Where the source / sink / sanitizer tables live**: there is no `TaintCatalog`. There are in fact two registries:
`TaintRuleRegistry` (`taint/TaintRuleRegistry.java:27`, indexed by rule id and sink family) and
`SinkFamilyRegistry` (`:22`, with a `lookupAll` cache).

**The shape of a sink pattern name**: a TAC callee signature is only fully qualified when the symbol resolver can
resolve the receiver type (`java.sql.Statement.executeQuery/1`); when it cannot, the simple
name is kept (`JdbcTemplate.query/2`) — when scanning a project without the dependency jars, all third-party types
take this path. `SinkPattern` therefore derives mechanically, in addition to the strict form, a **prefix-optional** variant
(`SinkFamily.SinkPattern.relaxedForm`):

```
^org\.springframework\.jdbc\.core\.JdbcTemplate\.query(/\d+)?
        ↓
^(?:[\w.]+\.)?JdbcTemplate\.query\b(/\d+)?
```

The variant is only enabled when the receiver is a simple name, and it only relaxes the package prefix: the class name
and the method name must still match exactly, and the method name is followed by a word boundary `\b`, so `JdbcTemplate.query`
does not also match `queryForList`. Without this variant, every framework sink would silently fail to match. See
[the rule authoring guide](../rules/RULE-AUTHORING.md#133-adding-a-new-taint-rule) for the details.

### 3.1.1 Framework entry points: the source is not a call, but a parameter annotation

A scanner that only knows `HttpServletRequest.getParameter(...)` sees **no source at all** in Spring code —
the framework binds request values to method parameters **by annotation**, and there is no request object in the controller body:

```java
@GetMapping("/orders")
public void search(@RequestParam("customer") String customer) {
  repository.findByCustomer(customer);   // the value comes from the HTTP request
}
```

So the source is a **parameter annotation**, and the IR already records those
(`TacFunction.paramAnnotations()`).

There is only one annotation catalogue: `org.plumb.rules.framework.FrameworkInputAnnotations` in `plumb-rules`,
with **30** binding annotations covering 5 frameworks:

| Framework | Annotations |
| --- | --- |
| Spring MVC / Spring Data | `RequestParam` `PathVariable` `RequestBody` `RequestHeader` `RequestAttribute` `CookieValue` `RequestPart` `ModelAttribute` `SessionAttribute` `MatrixVariable` |
| JAX-RS / Jakarta REST | `QueryParam` `PathParam` `HeaderParam` `CookieParam` `FormParam` `BeanParam` `MatrixParam` |
| Quarkus REST | `RestPath` `RestQuery` `RestHeader` `RestCookie` `RestForm` `RestMatrix` `RestBeanParam` |
| Micronaut | `Body` `Header` `QueryValue` `Part` `RequestBean` |
| Jakarta Servlet | `WebParam` |

The source of the catalogue is `scripts/framework_input_annotations.py` (each name records the framework documentation it was
checked against), and the Java side is generated by `scripts/gen_framework_annotations.py`.

**Why there must be only one**: the taint engine (which decides where the taint flow starts) and the AST input-validation
checkers (which decide whether the value has to be validated) must have the same understanding of "what is user input".
If the catalogue has names that only one of the two layers recognises, or if the two layers each keep their own copy,
the trust boundary becomes asymmetric — one layer treats the value as attacker-controlled, the other treats it as
trusted by default. `scripts/check_framework_annotations.py` guards this invariant: every name in the catalogue
must be recognised by both layers.

**The trust boundary is determined by the binding annotation, not by the endpoint annotation**: the endpoint annotations
of JAX-RS and Quarkus REST have the **same names** (`@Path` `@GET` `@POST` …), so the endpoint annotations alone cannot tell them
apart; but the binding annotations differ (`@QueryParam` vs `@RestQuery`), and that is exactly what belongs in the report.
So the taint label has the form `param:customer@spring-mvc:RequestParam` — the `param:` prefix stays unchanged (dozens of
`startsWith("param:")` checks depend on it), and the framework qualifier is a **suffix**.

### 3.1.2 Request beans: the source is neither a call nor a parameter annotation, but a field declaration

A **container annotation** (`@RestBeanParam` `@BeanParam` `@RequestBean`) binds not a single value but a
**group** of values: the framework binds the request values to the **fields** of the bean, so the binding annotation is on
the field, not on the parameter.

```java
public class Parameters {
  @RestQuery("customer")     // the binding annotation is here
  public String customer;
}

public void search(@RestBeanParam Parameters params) {
  repository.findByCustomer(params.customer);   // the value enters here
}
```

The difficulty is **how to find that field**. The bean class has no methods and no initializers, so it **produces no
functions at all** in the IR — `TacFunction.fieldAnnotations()` can only describe the fields of "the class that declares
that function", and a class without functions has no field annotations, so `@RestQuery` is **completely invisible** to the
whole engine: a value reaching a SQL sink has no framework origin to record.

The fix has two layers:

- **TAC**: `TacProgram.classFieldAnnotations()` records the field annotations of **every** declared class, regardless of
  whether that class has functions; `MergedProgram` aggregates them across compilation units (like `classToParent`).
- **DFA**: `FrameworkFieldBindings` resolves at `FIELDLOAD`: the receiver's **type** decides which class's
  fields to look at, the **field name** decides which field, and the **binding annotation** on the field decides the origin and the framework.

So the label is `param:params.customer@quarkus-rest:RestQuery` — the name is a **qualified field
expression**, because the reader needs to know which field of which bean it is.

**The container parameter itself is a "carrier", not a source**: its label is `param:params@carrier`. A field that the
framework **did not** bind must not be reported as a framework entry point — that would be a false declaration, because no
annotation ever bound it. This model **suppresses no finding**: the carrier is still tainted, so values derived from a request
bean still reach the sink and are still reported.

### 3.2 Boundary analysis (boundary)

`checkers/boundary/`, all extending `BoundaryConstraintChecker` (`:64`). Each subclass writes the arithmetic predicate
as a string and hands it to Z3:

| checker | Detects | Predicate location |
| --- | --- | --- |
| `ArrayBoundsConstraintChecker` | Array out of bounds | `idx < 0` (`:85`) |
| `DivZeroConstraintChecker` | Division by zero | `rhs == 0` (`:71`) |
| `OverflowConstraintChecker` | Overflow | `:46` |
| `UnderflowConstraintChecker` | Underflow | `result < typeR.lo` (`:103`) |

The implementation class of the interval abstraction is `checkers/boundary/Range.java` (**not** `Interval` — that class does not exist),
and the `Range` with a method length threshold is defined at `:56`.

### 3.3 Points-to analysis (points-to)

`points/PointsToSolver.java`. The source explicitly declares this to be a **constraint propagation solver, not a tabulation solver**
(`:38-40`), with the structure referencing Qilin (`:36`).

- **Heap abstraction**: field-sensitive `(receiver, name)` (`:80`), array cells (`:83`), and separate
  Map/List `containerCells` (`:92`)
- **Context-sensitive**: `AbstractObject.Policy` has three levels, the default being **`CALL_SITE` (1-CFA)**
  (`points/AbstractObject.java:304-306`), overridable through `TuningConfig.effectivePointsToPolicy()`
- **No use of Z3**: there is no solver reference anywhere under `points/`; Z3 only appears in the path conditions of `solver/`
- Solved once (`DfaEngine.solvePointsTo`, `:667`); on failure it degrades to "no alias information" rather than crashing

### 3.4 Control-flow checks (controlflow)

There are 16 classes under `checkers/controlflow/`, and they **all directly `implements DfaChecker`, with no common base class**:
`DeadlockChecker`, `CollectionSelfReferenceChecker`, `IteratorConcurrentModificationChecker`,
`ServletAtomicityDfaChecker`, `XxeChecker`, `EqualsCalledOnNullChecker`, `OffByOneChecker`,
`MissingCryptoInitChecker`, `TryCatchFinallyChecker`, `UnlockNotLockedChecker`,
`OrmUnnecessaryGetDfaChecker`, `SingletonRaceDfaChecker`, `OrmLostUpdateDfaChecker`,
`CompareWithSelfChecker`, `OrmLoadNullCheckDfaChecker`, `UncaughtExceptionPropagator`.

They are 16 independent TAC pattern checkers that merely share a package name; they are **not** some unified dataflow analysis.

---

## 4. Nullability: a three-valued fact domain

`AnalysisContext.mayBeNull(...)` (`engine/AnalysisContext.java:391-395`) is a **forward to the points-to
solver** and stores no facts itself. The five channels are private fields of `PointsToSolver`:

- `nullFields` `:124`
- `nullArrayCells` `:127`
- `nullContainerCells` `:130`
- `nullReturns` `:133`
- `nullVars` `:136`

**Three-valued semantics** (`PointsToSolver.java:104-121` explains why null cannot be modelled as an `Obj` sentinel: all
nulls would alias each other and pollute `mayAlias`):

| objects | null fact | Conclusion |
| --- | --- | --- |
| Non-empty | Absent | Definitely an object |
| Empty | Present | Definitely null |
| Empty | Absent | **Unknown** (never equal to null) |

⚠️ `AnalysisContext.java:383-390` explicitly warns: `mayBeNull == false` is **not** a proof of non-null
(unmodelled writes leave no trace). This is the same invariant as "an empty set does not prove safety".

---

## 5. Z3: false-positive filtering only

Z3 has exactly **one** call site in the whole engine, `TaintedCheckerBase.java:3139`, on the sink reporting path:

```java
String composed = composePathCondition(pathConditionForSink(...));
if (!composed.isEmpty()) {
    SolverZ3Bridge.Result r = solverBridge.check(composed, Map.of());
    if (r == SolverZ3Bridge.Result.UNSAT) return;   // suppress
}
```

- `pathConditionForSink` **returns null by default** (`:3239-3242`), so a checker that does not override it
  composes an empty condition and **never calls Z3 at all**
- The semantics are unambiguous: Z3 is **only used to suppress false positives, never to discover defects** (`:3237`)
- The call chain: `CachedZ3Bridge` (LRU of 4096 entries, `solver/CachedZ3Bridge.java:41-66`) →
  `Z3Bridge` (native Z3, falling back to `LocalSolverBridge` on failure)
- `StructuralImply` (`solver/StructuralImply.java`) is a cheap first-level decision that comes before SMT
  (`x >= N` implies `x != 0` and the like, `PathCondition.java:136-144`)

### 5.1 PathCondition and GuardTracker are two different things

| | PathCondition | GuardTracker |
| --- | --- | --- |
| Location | `solver/PathCondition.java:35` | `taint/GuardTracker.java:51` |
| Shape | A **list of predicate strings** in a mini-DSL | A **structured guard stack**, one per function |
| Lifetime | Accumulated along the path | Reset per function (`:48-49`) |
| Uses Z3 | Two-level decision through `GuardStore` | **Does not reference Z3 at all** |

The only point where the two meet is one-directional: the checker reads the structured guards from `GuardTracker`, then
**stringifies** them into the `GuardStore` (for example `DivByZeroChecker.java:203-207`), and records
`Operation.SolverZ3Predicate` in the summary.

Z3 is provided by `tools.aqua:z3-turnkey`, which automatically downloads the native library for the platform.

---

## 6. Call graph

`cg/CallGraphStrategyFactory.create` (`:36-70`) actually supports:

| Name | Implementation |
| --- | --- |
| `cha` | `ChaCallGraphStrategy` |
| `rta` | `RtaCallGraphStrategy` |
| `vta` | `VtaCallGraphStrategy` |
| `1cfa` | `OneCfaCallGraphStrategy` |
| `2cfa` | `TwoCfaCallGraphStrategy` |
| `stub` | `StubCallGraphStrategy` |
| `cached:<name>:<path>` | `CachedCallGraphStrategy` |

The cross-TU `createCrossTu` (`:89-112`) only supports `global` → `GlobalCallGraphStrategy` (**the CLI
default**) and the cached variant.

⚠️ **Earlier documentation claimed that `0-CFA` exists; it does not exist in the code**, and an unknown name throws immediately
(`:64-68`) — it is not "missing from the documentation", it is "not selectable".

Note also: the `1cfa` here is a **call graph** strategy, and it is a separate switch from the `CALL_SITE` policy of the
points-to analysis in §3.3.

---

## 7. Number of rules

There are two different measures of "how many rules", and the early documentation mixed them up:

| Measure | Count | Source |
| --- | --- | --- |
| **The checkers actually configured in the engine** | **16** | `plumb-dfa/src/main/resources/checkers.yaml:19-179` |
| The `RuleRegistry` of `plumb-rules` | 85 | `plumb-rules/.../RuleRegistry.java` |

The 16 entries of `checkers.yaml`: `DivideByZero`, `IntegerOverflow`, `NullDereference`,
`ResourceLeak`, `SensitiveDataLeak`, `UncheckedReturn`, `ConstantCondition`,
`OrmLoadNullCheckDfa`, `OrmUnnecessaryGetDfa`, `OrmLostUpdateDfa`, `SingletonRaceDfa`,
`ServletAtomicityDfa`, `BoundaryOverflow`, `BoundaryUnderflow`, `BoundaryDivZero`,
`BoundaryArrayBounds`.

⚠️ Many of the `org.plumb.dfa.checker.*` / `org.plumb.dfa.taint.*` class names registered in `RuleRegistry.java`
**do not exist** under `plumb-dfa` (for example `:1010` points at `org.plumb.dfa.taint.TaintAnalysis`);
that registry is clearly stale and **must not** be used to infer the total number of rules.

---

## 8. Concurrency model

| Stage | Parallel | Explanation |
| --- | --- | --- |
| Parsing (JavaParser) | Yes | At file level, threshold 800 files |
| Translation (AST → TAC) | Yes | In the same round as parsing |
| Style checks | Yes | AST visitor, no cross-file state |
| **DFA (`runParallel`)** | **Yes** | Top-level worklist: functions whose dependency count reaches zero enter `readyQ` (`:1123-1130`) |
| Report output | No | Single-file write |

The concurrency ceiling is controlled by `--max-parallelism` (default `nCPU - 1`, lower bound 1).

⚠️ Early documentation claimed "taint must be single-threaded and shares the summary cache across functions" — **that is outdated**. The engine has a parallel
worklist; the single-thread constraint only exists in `GuardStore` (which says so explicitly at `solver/GuardStore.java:20`).

---

## 9. Relationship to the on-disk cache

```
file ─parse+translate─▶ TacProgram ───[serialisable]───▶ cache/programs/<sha1>.tac
                          │
                          ▼
                     MergedProgram ──[serialisable]───▶ cache/merged.tac
                          │
                          ▼
                       DFA ────  cache/callgraph.tac  ────  (optional)
```

See [`CACHE.md`](./CACHE.md) for the details.
