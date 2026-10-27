# Cross-Function Analysis: Bottom-Up Summary Construction, Top-Down Precise Analysis

[English](../README.md) | [中文](../zh/tech/CROSS-FUNCTION-ANALYSIS.md)

This document explains the two-phase cross-function analysis design of the `plumb-dfa` engine ( / /
 / ).

**The two phases have completely different precision models**:

| Phase | Sensitivity | Explanation |
| --- | --- | --- |
| **Phase 1 (bottom-up summary construction)** | **path-insensitive** + **context-insensitive** | The summary is the merged result of "all paths + all callers" inside the callee; the same callee is computed once and reused everywhere |
| **Phase 2 (top-down precise analysis)** | **fully context-sensitive** (each call site is independent) + **path-sensitive (Z3 verification)** | The caller folds the summary into its own fact bag / path conditions / points-to map; all results are concrete values from the caller's point of view |

> Companion source code:
> - `plumb-dfa/src/main/java/org/plumb/dfa/summary/Operation.java` ——
> - `plumb-dfa/src/main/java/org/plumb/dfa/summary/FunctionSummary.java` ——
> - `plumb-dfa/src/main/java/org/plumb/dfa/summary/MethodSummary.java` + `MethodSummaryBuilder.java` ——
> - `plumb-dfa/src/main/java/org/plumb/dfa/summary/SummaryApplier.java` ——
> - `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintedCheckerBase.java` ——
> - `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintDomain.java` ——
> - `plumb-dfa/src/main/java/org/plumb/dfa/points/PointsToAnalysis.java` ——
> - `plumb-dfa/src/main/java/org/plumb/dfa/solver/PathCondition.java` + `GuardStore.java` + `Z3Bridge.java` + `LocalSolverBridge.java`
> - `plumb-dfa/src/main/java/org/plumb/dfa/engine/DfaEngine.java` — engine scheduling

## 1. Design motivation

The tabulation (IFDS / IDE) style of cross-function analysis packs all facts into one shared worklist:

```text
each caller's (call-site, fact) → strung into one workflow along the call/return edges
```

That is reasonable for pure taint analysis with a single checker, but Plumb's unified engine has to answer
several heterogeneous questions in parallel: taint (StringTaint / NumericTaint / PointerTaint / MixedTaint), null-deref,
points-to, resource leak, Z3 path conditions, side effects (writing this.field / writing a static / calling
another method). Putting every kind of fact into one unified lattice makes the worklist blow up to the
`O(functions × call-sites × domain_kinds)` level, with a low cache hit rate and hard parallelisation.

Plumb's two-phase design **decouples "the semantics of the callee" from "the concrete use by the caller"**:

```text
Phase 1 (the callee introduces itself)  ──►  Summary  =  a template (path-insensitive / context-insensitive)
Phase 2 (the caller applies the template)  ──►  Precise analysis  =  concrete values from the caller's point of view (context-sensitive / path-sensitive via Z3)
```

| Phase | Order | Task | Output |
| --- | --- | --- | --- |
| **Phase 1 (bottom-up)** | The callee comes before the caller | Walk the function body, collect the per-param set of `Operation`s, compute the `MethodSummary` (return value shape + side effects) | `FunctionSummaryCache` + `MethodSummaryCache` |
| **Phase 2 (top-down)** | When the caller encounters a CALL | Look up the cache → fold the summary into the caller's own facts / path conditions / points-to / side effects | `RETURN_VALUE_FOLDED` fact / `SIDE_EFFECT_RECORDED` fact / `taintedVars` increment / `GuardStore` increment / `pointsTo` increment |

**Key property**: the callee's summary is computed only once (cached by signature); callers no longer wait
for each other — as soon as all of a caller's callees are done, it can start. The dependency between the two
phases is **one-directional**, so worklist parallelism is possible.

## 2. The CallGraph point of view: how does cross-TU work?

Before entering Phase 1/Phase 2, one question has to be answered: does the engine see **one TU's
TacProgram**, or **a TacProgram merged from multiple TUs**? This decides whether cross-file calls can be resolved.

### 2.1 Two paths

| Path | Entry point | How it is used | Who uses it |
| --- | --- | --- | --- |
| **(A) Merged Program + CHA** (**the default**) | `MergedProgram(List<TacProgram>).asProgram()` | Merge the `TacProgram`s of several .java files into a single `TacProgram` view (`MergedProgram` uses `enclosing-class :: signature` as the merged key, to avoid collisions between methods of the same name) | The `ScanRunner` of `plumb-main` (the production CLI) |
| **(B) GlobalTACStore + Global strategy** | `GlobalTACStore(programs).freeze()` + `CallGraphStrategyFactory.createCrossTu("global", store, null)` | Register several `TacProgram`s directly into a global signature index; `GlobalCallGraphStrategy` resolves them by a direct `calleeSig` lookup | The `ScanRunner` of `plumb-main` (**the default**; with `cgStrategy=global` it goes through `DfaEngine.runWithGlobalStore`) |

> Key fact: the default of `plumb-main` is **path B** — the default value of `ScanConfig.cgStrategy` is
> `"global"`, so `ScanRunner` calls `DfaEngine.runWithGlobalStore(...)`, registering every TU
> into the `GlobalTACStore` and resolving cross-TU calls by `calleeSig`.
>
> Path A is still available: setting `cgStrategy` to `cha` / `rta` / `vta` / `1cfa` / `stub`
> goes through `DfaEngine.run(programView)`, where `programView` is the
> view merged by `MergedProgram.asProgram()`. Both paths can resolve direct cross-TU calls;
> the difference is that path A relies on **a single merged TacProgram**, while path B relies on
> **a global signature index**.

### 2.2 Path A: how MergedProgram + CHA supports cross-TU

```java
// plumb-main/ScanRunner.java (excerpt) —— when cgStrategy != "global"
List<TacProgram> programs = translated.stream().map(t -> t.program).toList();
MergedProgram merged = new MergedProgram(programs);
TacProgram programView = merged.asProgram();
DfaEngine engine = new DfaEngine(cfg, checkers, "cha", null);
engine.run(programView);   //  ← the whole project as one TacProgram
```

`MergedProgram.rebuild()` generates a merged key for every function:

```text
original signature    com.foo.A#m(int):int
original signature    com.bar.B#m(int):int
                       ↓
merged key  com.foo.A :: m(int):int
merged key  com.bar.B :: m(int):int
```

`ChaCallGraphStrategy` operates on the merged `TacProgram.functions()`:

1. **Direct call resolution**: the `calleeSig` of a CALL is already `"com.bar.B#m(int):int"`, which is found
   directly in the merged index (because B#m is in the merged view) → direct cross-TU calls resolve successfully.
2. **Virtual call over-approximation**: `ChaCallGraphStrategy.indexBySimpleName(program)`
   indexes by `method/arity` (for example `m/1`), and CHA adds every function with "the same simple name + the same arity"
   to the call graph as an override — this is the standard CHA over-approx
   (sound but imprecise). If A and B have no inheritance relation, CHA still treats B#m as
   a possible target of A#m, which may introduce false positive call edges.
3. **Library filtering**: by default `includeLibraries = false`, calls to `java.* / javax.* / com.sun.*
   / jdk.* / sun.*` do not take part in the call graph (they are treated as opaque libraries).

> Cross-TU indirect calls (A.m → A.helper → B.work): CHA walks along the call graph edges step by step,
> because both A.helper and B.work are in the merged view, and the RPO scheduling can correctly
> compute the callee before the caller.

### 2.3 Path B: GlobalTACStore + Global strategy

`GlobalTACStore` is an **explicit cross-TU registry** — it indexes the `TacFunction`s of several
`TacProgram`s into one table by canonical signature, providing O(1) resolution.

```java
GlobalTACStore store = GlobalTACStore.of(programs);  // internal addProgram + freeze
CallGraphStrategy strategy = CallGraphStrategyFactory
        .createCrossTu("global", store, null);
CallGraph cg = strategy.build();
DfaEngine engine = new DfaEngine(cfg, checkers, "global", null);  // but the engine goes through the TU-local factory by default
//  ↑ this usage does not actually work: DfaEngine.run() internally calls
//  CallGraphStrategyFactory.create(name, program, cachePath),
//  which throws "Unknown call-graph strategy" for "global".
//  The Global strategy must be used directly through the builder, decoupled from DfaEngine.run().
```

**The correct cross-TU usage** (you control the call graph yourself, without going through DfaEngine.run):

```java
GlobalTACStore store = GlobalTACStore.of(programs);
CallGraph cg = new GlobalCallGraphBuilder(store).build();
// the subsequent analysis consumes cg directly, no longer going through DfaEngine.run
```

The advantages of path B:
- **No dependency on the Java type hierarchy**: direct signature lookup, and virtual calls can also be resolved
  precisely (provided the IR's `calleeSig` already reflects the dynamic type; otherwise the caller's own
  type analysis is still needed).
- **No merging of programs**: the per-TU `sourcePath` mapping is preserved (`GlobalTACStore.sourceOf(sig)`),
  so a reported finding can be located precisely back in the original file.
- **Cross-TU cache key**: the cache file name contains a hash of the source file set, so adding a new
  .java file invalidates it automatically.

The disadvantages of path B:
- The CLI does not wire it up — the 4-arg/5-arg constructors of `DfaEngine` only accept TU-local strategy names.
- To use the Global strategy, you have to bypass `DfaEngine.run` and use `GlobalCallGraphBuilder` +
  a custom traversal directly; this is incompatible with the engine's parallel worklist / warm-up
  (`DfaEngine.run` internally uses `CallGraphStrategyFactory.create(name, program, ...)`, which the Global strategy
  can never reach).

### 2.4 Conclusion: the default behaviour = cross-TU + path-sensitive

Putting the two paths side by side:

| Dimension | Path A (Merged + CHA) | Path B (GlobalTACStore) |
| --- | --- | --- |
| Direct cross-TU calls | ✅ the static `calleeSig` hits in the merged index | ✅ the explicit lookup hits |
| Cross-TU virtual calls | ⚠️ CHA takes all same simple-name + arity, may over-approx | ✅ strictly matched by signature (if the IR records the dynamic type) |
| Call graph precision | sound + leaning towards over-approx | depends on the completeness of the IR information |
| CLI support | ✅ `cgStrategy` set to `cha` / `rta` / `vta` / `1cfa` / `stub` | ✅ **the default** (`cgStrategy=global`) |
| Integration with `DfaEngine` | ✅ one line of `engine.run(programView)` | ✅ `engine.runWithGlobalStore(programs, threads)` |
| Cross-TU cache | ❌ the cache key only looks at the SHA-1 of a single file | ✅ the cache key contains a hash of the source file set |
| Used by which tests | almost all | almost all |

**So the answer to "in plumb-dfa, is the CallGraph used by the analysis a global CallGraph, and does that
involve the ability to point at cross-TU analysis" is**:

- **The production CLI default is global** — the default of `ScanConfig.cgStrategy` is `"global"`, and
  `ScanRunner` goes through `DfaEngine.runWithGlobalStore(...)`: `GlobalTACStore.of(programs)`
  registers every TU into the global signature index, and `GlobalCallGraphStrategy` resolves by `calleeSig`.
- **Path A is still available** — replacing `cgStrategy` with another name falls back to
  `DfaEngine.run(merged.asProgram())`, which resolves cross-TU calls through CHA on the merged view.
- **Neither path is a precise call graph explicitly constructed from the Java type hierarchy**:
  virtual calls are still matched by simple name + arity.
- **A more precise "Global" path exists but is not wired into the CLI** — `GlobalTACStore` +
  `GlobalCallGraphStrategy` are meant for the integration tests; to put them into production, `DfaEngine`
  needs a cross-TU construction path (accepting a `GlobalTACStore` instead of a single `TacProgram`).

> **TODO** (a future release): wire the Global strategy into the main path of `DfaEngine` — add the
> `DfaEngine.createCrossTu(EngineConfig, List<DfaChecker>, GlobalTACStore, int)`
> factory method, building the strategy internally with the `createCrossTu` factory, and integrating it
> with the worklist. That way the CLI can switch to the precise cross-TU mode with `--cg-strategy global`.

## 3. Phase 1 (bottom-up) — Summary construction

### 2.1 When it is called

In single-threaded mode the engine walks `CallGraph.reversePostOrder()`, so the callee always comes before the caller.
In parallel mode it uses a dep-count worklist: a function's dep is "the number of callees that have a body
in the program", the ones with dep=0 enter the ready queue, and after processing, the dep of all their callers is
decremented; the callers whose dep reaches zero enter the ready queue. Both modes guarantee that Phase 1 precedes Phase 2.

The full life cycle of the engine on a single function:

```text
initEngine (per-engine, once)
  └─ for each fn in RPO:
       initFunction(ctx, fn)
         for ins in bbRpoOrder(fn):   // BB-RPO + reverse inside the block
           if ins.kind == CALL:
             # Phase 2: the entry point of the precise analysis from the caller's point of view
             SummaryApplier.apply(ins, ctx, ins.args) // fold
             ctx.sideEffectConsumer().recordCall(ins, ctx) // side effect into the bag
           checkInstruction(ins, fn, ctx)   # one pass per checker (Phase 2 checker logic)
         finishFunction(ctx, fn)
       # Phase 1: every checker introduces itself
       merged = summariseOneFunction(ctx, fn)
       ctx.summaryCache().put(fn.signature(), merged) # write into the cache
       finish(ctx) (per-engine, once)
```

Note that Phase 2 (`SummaryApplier`) is called **before** `checkInstruction` — which means the fact bag the checker
sees in `checkInstruction(ins)` already contains the `RETURN_VALUE_FOLDED` and `SIDE_EFFECT_RECORDED` facts
of the previous line's CALL. This position was introduced precisely so that the
"call + use the return value" pattern closes within a single instruction visit.

**Both Phase 1 and Phase 2 are completed inside `analyseOneFunction`** — all CALLs of a function
have already been folded (Phase 2 locally), and then `summariseOneFunction` (Phase 1 locally
completes) writes the summary, so that the next caller can use it.

### 2.2 MethodSummary — return value shape + side effects

`MethodSummaryBuilder.build(fn)` performs one **path-insensitive**
scan of a `TacFunction`.

- **Return value shape** (9 `ReturnShape.Kind`s):

  ```text
  RETURN_VOID          // no return / bare `return;`
  RETURN_CONST         // return 5 / return true
  RETURN_PARAM         // return p0
  RETURN_THIS_FIELD    // return this.field
  RETURN_STATIC_FIELD  // return ClassName.field
  RETURN_LOCAL_VAR     // return localVar (the alias chain cannot be chased to the root)
  RETURN_BINARY_OP     // return a + b
  RETURN_CONDITIONAL   // the `if (b) return true; else return false;` pattern
  RETURN_NEW_INSTANCE  // return new Foo()
  UNKNOWN              // mismatched return shapes across branches / chase failure
  ```

  It follows the last-def chain + alias tracking (`chase`), following
  `MOVE / FIELDLOAD / NEW / BINOP` all the way to the root.

- **Side effects** (`SideEffect`): one scan over
  →  `FIELDSTORE this / FIELDSTORE static / FIELDSTORE paramAlias / ARRAYSTORE paramAlias /
   CALL / THROW` collected into four sets.

`MethodSummary` is immutable; once built it is written into `MethodSummaryCache`, which the engine
looks up on the caller side.

> **The cost of path insensitivity**: `staticReturnsTrue` always returns `const:true`, no matter how the caller
> passes the arguments. This is a conservative over-approximation — the constant the caller sees is a value the
> callee "might" return on any path, so it **never misses a report**, but it may fold a multi-branch return
> into `top` (`ReturnShape.Kind.UNKNOWN`), losing precision.

### 2.3 FunctionSummary — the set of Operations of each formal parameter

`DfaChecker.summarizeFunction(ctx, fn)` is the hook where a checker proactively reports. `FunctionSummary`
consists of four parts:

| Field | Content |
| --- | --- |
| `paramOps : Map<Integer, Set<Operation>>` | Formal parameter index (0-based, receiver excluded) → the set of `Operation`s of that formal parameter |
| `returnOps : Set<Operation>` | The set of `Operation`s observed on the function's return value |
| `calleesReached : Set<String>` | The signatures of all callees called inside the function (used for cross-referencing) |
| `hasUnmodelledBehavior : boolean` | Whether there is a `HasUnmodelledBehavior` (an O(1) query flag) |

`Operation` is a **sealed interface**; its 9 variants each express one cross-function fact:

| Variant | Carries | Triggering scenario |
| --- | --- | --- |
| `PassThrough()` | The formal parameter flows through the function unchanged | The parameter is named / used but not modified |
| `Sink(familyKey, atParamIndex)` | The formal parameter flows to a sink directly or indirectly | For example a SQL checker sees `prepareStatement(arg)` |
| `WriteToField(fieldName)` | The value of the formal parameter is written to a field of the enclosing class | `this.field = arg` |
| `TaintPropagate(sourceId, confidence)` | The formal parameter flows out carrying a source's taint | Written by the taint framework |
| `HasUnmodelledBehavior(reason)` | The function has side effects that cannot be modelled, such as native / JNI / reflection | A `native` method / a `Class.forName` chain |
| `UnmodelledCall(calleeSig, isLibrary)` | The function called a callee whose body is not visible | An unresolved virtual call / a library function |
| `SolverZ3Predicate(predicate, pathVars)` | Passes the guard condition on this call chain to Z3 | The path-condition bridge |
| `PointsToPropagate(allocSites)` | The points-to set the function exposes | Cross-function points-to |
| `PersistsParam(paramIndex)` | The function persists the parameter object into the DB | -DA ORM_LOST_UPDATE |

The benefit of a sealed interface: the checkers on the caller side use Java 21 pattern matching to write an
exhaustive switch, and the compiler forces you to consider whether to consume a newly added variant.

### 2.4 MethodSummaryCache warm-up

Before, `MethodSummary` was computed serially by the analysis loop, "one computed, one written". A sweep on the real
Juliet test suite exposed a corner case: when `bad()` and its helper `staticReturnsTrue()` sit on the same
RPO boundary in the merged program, `bad()` is occasionally visited first, so the cache is
cold, `SummaryApplier` sees `UNKNOWN`, and the fold fails.

Before the analysis, **run `MethodSummaryBuilder.build()` once for every function in the program**
and write it into the cache:

```java
public int warmUpMethodSummaries(AnalysisContext ctx, TacProgram program) {
    for (TacFunction fn : program.functions()) {
        if (!analysedOrigins.contains(fn.origin())) continue;
        FunctionSignature sig = parseFnSignature(fn);
        ctx.methodSummaryCache().put(sig, fn);   // put lazy-builds internally
        built++;
    }
    return built;
}
```

The warm-up is pure computation in O(instructions) with no side effects, so running it sequentially in one thread
is fine. This step closes the corner case of "the analysis order affects the fold result" for good, and the cache
the parallel workers see is 100% warm.

## 4. Phase 2 (top-down) — Precise analysis

### 3.1 The core: why is Phase 2 said to be **fully context-sensitive**?

The summary itself is a **template that does not distinguish callers**, but once Phase 2 folds it into the caller's own
state, the analysis is entirely from the caller's point of view. Three mechanisms ensure context-sensitivity:

**(1) Per-call-site fact bag**: every caller function has its own `FactBag` instance, and
`SummaryApplier.apply(call, ctx, args)` writes the `RETURN_VALUE_FOLDED` fact to
`call.dest` (the caller's local variable), not to some global table. Two callers calling the same
callee see different fact bags and different facts.

**(2) `RETURN_PARAM` carries the identity of the caller's actual argument**:

```java
case RETURN_PARAM: {
    int i = r.paramIndex;
    if (args != null && i >= 0 && i < args.size()
            && args.get(i) != null) {
        return "param:" + i + ":" + args.get(i);   //  ←  the caller's argument name
    }
    return "param:" + i;
}
```

`RETURN_PARAM(0)` in the summary only says "returns formal parameter 0", but the folded fact encodes
`param:0:<the caller's actual argument>`. When the caller uses this fact for the downstream analysis, it sees
**its own** argument's SSA version / name — a concrete variable of the caller, not of the callee.

**(3) Per-call-site path condition**: `GuardStore` indexes by `var name → predicates`,
and every BRANCH on the caller's path adds its predicate to the caller's own `PathCondition`;
during Z3 verification the `bindings` use the caller's concrete values. The same callee is expanded
into different facts + different guard sets in different callers.

**An example** that illustrates context-sensitivity:

```java
// IO.java
public static boolean staticReturnsTrue() { return true; }

// CWE252_bad.java
void bad() {
    boolean b = IO.staticReturnsTrue();
    if (b) { ... }   // b is always true, the else is a dead branch
}
```

- Phase 1 computes the summary of `IO.staticReturnsTrue`: `MethodSummary.returnShape
  = RETURN_CONST(true)`, and `FunctionSummary` is nearly empty.
- Phase 2 walks `bad()`: the CALL `IO.staticReturnsTrue()` hits the cache →
  `SummaryApplier` writes `RETURN_VALUE_FOLDED(b, "const:true")` on `b`.
  Here `b` is a local variable of `bad()` (`TacVar("b")`), and the fact lives in the `FactBag` of
  `bad()` — **completely independent** of the fact seen by `good()` (if it has a checker of the same name).
- Then `if (b)` is a BRANCH: `LiteralBooleanConditionChecker` reads the `const:true` fact
  on `b` → it knows `b == true` must hold → the else branch is
  dead code → a finding is reported.
- If another caller calls `IO.staticReturnsTrue()` and writes it into its own `c`, that
  fact lives in another `FactBag`, and the two analyses do not interfere.

This is what "the summary is imprecise, but the analysis is fully context-sensitive" means: the summary describes
"what the callee generally returns", but when the caller uses it, **the value is bound to the caller's own
variable, has passed through the caller's own BRANCHes, and is verified with the caller's own Z3 bindings**.

### 3.2 SummaryApplier.apply(call, ctx, args)

Every CALL instruction is invoked by the engine before `checkInstruction`:

```java
public static MethodSummary apply(TacInstruction call,
                                   AnalysisContext ctx,
                                   List<TacOperand> args) {
    String calleeSig = call.calleeSig;
    FunctionSignature sig = FunctionSignature.parse(calleeSig);
    if (sig == null) sig = fallbackParse(calleeSig); // fallback
    MethodSummary s = ctx.methodSummaryCache().get(sig);
    applyReturnValue(call, s, ctx, args);   // → RETURN_VALUE_FOLDED fact
    applySideEffect(call, s, ctx);          // → SIDE_EFFECT_RECORDED fact
    return s;
}
```

The fold result is two `Fact`s, appended to the caller's `FactBag`:

| Fact kind | Written to | Purpose |
| --- | --- | --- |
| `RETURN_VALUE_FOLDED` | `call.dest` | The consumer decides what to do next based on the `value` string: `const:true` / `const:42` / `param:0` / `binop:+:a:b` / `top` |
| `SIDE_EFFECT_RECORDED` | `call.dest` or a sentinel | A pure function produces no fact; otherwise a combination such as `writes-this:f, writes-param:0, calls:io,X, may-throw` is written |

The encoding rules (stable strings):

```text
RETURN_VOID          →  "void"
RETURN_CONST         →  "const:<literal>"
RETURN_PARAM         →  "param:<i>"  or  "param:<i>:<caller-arg>"
RETURN_THIS_FIELD    →  "this-field:<name>"
RETURN_STATIC_FIELD  →  "static-field:<FQCN.field>"
RETURN_LOCAL_VAR     →  "local:<name>"
RETURN_BINARY_OP     →  "binop:<op>:<lhs>:<rhs>"
RETURN_CONDITIONAL   →  "cond:<trueArm>:<falseArm>"
RETURN_NEW_INSTANCE  →  "new:<type>"
UNKNOWN              →  "top"
SIDE_EFFECT          →  "writes-this:f,writes-param:0,calls:io.X,may-throw"  // or "pure"
```

A checker (for example `LiteralBooleanConditionChecker`) only has to look at whether the `value` string starts
with `const:true` to report CWE-547 "dead branch" — it does not need to know how the callee
obtained `true`.

### 3.3 TaintedCheckerBase uses Operation.TaintPropagate

The taint checkers (`SqlInjectionChecker` / `CommandInjectionChecker` / and 30+ more)
extend `TaintedCheckerBase`, which handles the cross-function taint flow for them:

```java
case CALL -> {
    checkSinkAtCall(ins, fn, ctx);                  // 1) sink hit?
    propagateTaintFromCalleeSummary(ins, ctx);      // 2) callee summary → caller taint
}
```

The logic of `propagateTaintFromCalleeSummary`:

```text
calleeSummary = ctx.summaryCache().get(call.calleeSig)
for (i, op) in calleeSummary.paramOps():
    if op is TaintPropagate(sourceId, confidence):
        arg = call.args[i]
        taintedVars[arg] = sourceId + ":" + confidence   // mark the caller's actual argument as tainted
    if op is PassThrough():
        # the formal parameter flows through unchanged, the taint passed in by the caller is kept
    if op is Sink(familyKey, k):
        # the callee has its own sink, the caller side does not report it a second time (avoiding duplicates)
```

A simplified example:

```java
// helper.java
public String loadUser(int id) {
    return request.getParameter("name");    // source
}
void saveUser(int id) {
    String name = loadUser(id);
    db.execute("INSERT INTO t VALUES ('" + name + "')");  // sink
}
```

- Phase 1 runs `loadUser`: the body has a source but no sink → the summary records
  `paramOps[0] = PassThrough()` (the parameter is unused), `returnOps = {TaintPropagate("bu-0001-taint-request-param", 1.0)}`.
- Phase 2 runs `saveUser`: on the CALL `loadUser(id)`
  - `MethodSummaryCache` returns `RETURN_PARAM(0)` → writes
    `RETURN_VALUE_FOLDED(name, "param:0:id")`.
  - `FunctionSummaryCache` returns the `FunctionSummary` above →
    `propagateTaintFromCalleeSummary` sees `TaintPropagate("bu-0001-taint-request-param")` in `returnOps` → marks `name` as
    `taintedVars[name] = "bu-0001-taint-request-param"`, so from the caller's point of
    view `name` is tainted.
- Continuing to `db.execute(...)`: the sink is hit + `name` is in `taintedVars` →
  `SqlInjectionChecker` reports a finding, with the path marked `(after cross-function fold)`.

The whole chain spans two functions and 3 instructions, and the engine maintains no global worklist — it only needs a cache lookup.

### 3.4 Cross-function PointsTo

`PointsToAnalysis.applyCalleeSummary(dest, summary)` merges `PointsToSummary.returnAllocSites`
into the caller's points-to map. The flow:

1. `PointsToAnalysis.buildSummary()` collects every `AllocSite` produced by `NEW /
   NEWARRAY` inside the callee in Phase 1, encodes them as a set of `"functionSig\0insnIdx\0type"` strings
   (written into `Operation.PointsToPropagate(allocSites)` of the `FunctionSummary`).
2. `PointsToAnalysis.applyCalleeSummary` parses these strings in Phase 2 and fills them back into
   the caller's `pointsTo(dest)`, so that `isUseAfterFree` can correctly see, from the caller's point of
   view, "a pointer allocated inside the callee flowed into the caller's dest".

The use-after-free / alias checkers are the biggest beneficiaries of this design — a tabulation solution that
treats points-to as yet another fact in the worklist blows up the state space; Plumb bypasses that dimension with an
independent points-to solver (`PointsToSolver`, one whole-program solve) + summaries.

### 3.5 cross-checker GuardStore

`GuardStore` is a pool of "known guards" that is **shared by one engine run** and **contributed to by
multiple checkers**. When a checker encounters a BRANCH such as `if (x > 0)` in `checkInstruction`, it calls
`ctx.guardStore().add("x", "x > 0")`; any checker that wants to know "has the safe condition of my sink
already been proven by someone else" calls `ctx.guardStore().impliedGuard("x", "x != 0", bindings)`.

The correspondence of the two phases in the guard dimension:

- Phase 1 writes no guards (the callee's BRANCHes do not enter the caller's guard pool).
- Phase 2 adds the BRANCHes the caller itself went through into the `GuardStore`, and calls
  `Z3Bridge.check(antecedent && !consequent, bindings)` to verify the
  implication with Z3 — the `bindings` use the concrete values from the caller's own fact bag.

A concrete example: `DivByZeroChecker` looks up
`impliedGuard("x", "x != 0", bindings)` when it sees `data / x`; if `IntUnderflowChecker` previously
recorded `x >= 1` (which implies `x != 0`), it either takes the fast path of `PathCondition.structurallyImplies`
(purely syntactic inference) or the full Z3 verification. `impliedGuard` returns true →
`DivByZeroChecker` suppresses the finding.
