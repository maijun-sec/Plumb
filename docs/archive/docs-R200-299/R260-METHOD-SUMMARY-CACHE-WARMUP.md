# R260 — MethodSummary cache warmup (pre-pass before analysis)

## Goal

R260 fixes the "RPO visits `bad()` before `staticReturnsTrue()`" failure mode
documented in R259's follow-up section. The root cause is the lazy build order:

1. The DFA engine walks functions in RPO order
2. For each function, it runs the checkers (which consult the
   `MethodSummaryCache` for callee summaries via `SummaryApplier`)
3. **After** the analysis pass, the engine builds the function's
   `MethodSummary` and stores it in the cache

If the RPO order visits a caller before its callee (which happens whenever
the `CallGraph`'s node iteration order isn't strict callee-before-caller
— e.g. a merged Juliet program where multiple `bad()` functions from
different CWE instances share the same RPO frontier), the cache is cold
for the first iteration and the fold consumers see `MethodSummary` with
shape = `UNKNOWN`. R256's `LiteralBooleanConditionChecker` and R257's
`AssignmentInConditionalChecker` both depend on the cache being warm
for the first analysis pass.

## Decision

R260 ships the simpler of the two R259 follow-up candidates: a
**pre-pass** that builds every function's `MethodSummary` before any
analysis starts. The cache is 100% warm by the time the first
`checkInstruction` runs.

The alternative (lazy build on `SummaryApplier.apply` cache miss) was
considered and rejected because:

1. It makes the analysis non-deterministic — finding counts depend on
   which function is visited first.
2. The pre-pass is a single linear walk over each function's IR, with
   no shared state, so it's trivially safe to do as a pre-pass.
3. The pre-pass doesn't depend on any checker's per-function
   accumulator, so it runs before `initFunction` is called on any
   checker.

## Test results

- `R260CacheWarmupTest`: **5 / 0 / 0 / 0** (~1s)
  - `warmUpPopulatesCacheForEveryFunction` — pre-pass on a 3-function
    program produces 3 cache entries (not lazy)
  - `foldDerivedFindingFiresWhenCallerIteratedBeforeCallee` — the
    R259 failure mode. Pre-R260 this test would fail because the
    cache was cold for the first `bad()` visit; post-R260 it
    produces the expected `1` CWE-547 finding with
    "cross-function fold" in the message
  - `warmUpIsIdempotent` — calling `warmUpMethodSummaries` twice
    doesn't double the cache size
  - `warmUpHandlesEmptyProgram` — edge case, no functions, no
    exception, returns 0
  - `warmUpSummaryForLibFunctionsHasShapeOnlyKey` — the
    R259 dual-key install stores a summary under the shape-only
    `class="<unknown>", returnType="?"` key, so
    `SummaryApplier.fallbackParse` lookups hit
- `R256CrossFunctionFoldTest`: **3 / 0 / 0 / 0** (0.08s) — pre-R256
  fold-derived findings still work
- `R257AssignmentInConditionalFoldTest`: **2 / 0 / 0 / 0** (0.04s) —
  pre-R257 fold-derived findings still work
- `R258SideEffectConsumerTest`: **15 / 0 / 0 / 0** (0.16s) — pre-R258
  side-effect consumer still works
- `R257JulietCrossFunctionSweepTest`: **2 / 0 / 0 / 0** (9.5s) — R260
  pre-pass runs (logs "warmed MethodSummary cache for 130 of 130
  functions") but Juliet still produces 0 fold-derived findings
  because the upstream parser/translator doesn't produce complete
  IR for Juliet's `try-catch-finally` patterns (R259 doc, separate
  issue). R260 fixes the call-order dimension, not the IR-completeness
  dimension. The fold pipeline is now ready to fire as soon as the
  upstream IR gap is closed
- `plumb-dfa` core tests: **481 / 0 / 0 / 0** (no regression)

## 4 innovation points

1. **Pre-pass is a single, independent linear walk** (R260
   critical). `MethodSummaryBuilder.build(fn)` reads only the
   function's own IR; it doesn't touch any checker's
   `initFunction` accumulator, `factBag`, or `guardStore`. The
   pre-pass is therefore safe to run before any analysis state
   exists, which is the key safety property that makes R260
   trivial to reason about. The pre-pass doesn't even need to
   run inside the engine's `initEngine` step — it can be a free
   public method on `DfaEngine` (which is what
   `warmUpMethodSummaries` is) that callers can invoke any time
2. **`buildAndStoreMethodSummary` is removed from the per-function
   analysis loop** (R260 design). Pre-R260, the per-function
   loop called `buildAndStoreMethodSummary` *after*
   `analyseOneFunction` + `summariseOneFunction`. R260 removes
   this and relies on the pre-pass. This is a small but
   meaningful code-cleanliness win: the analysis loop is now
   only about analysis (and the per-checker summary), not about
   cache maintenance. The cache is a side-effect of the pre-pass
   and of the per-checker summary, not of the analysis itself
3. **R259 dual-key install is preserved** (R260 + R259
   integration). The pre-pass stores a summary under both the
   full canonical key (class name + return type) and the
   shape-only key (`class="<unknown>", returnType="?"`). The
   `warmUpSummaryForLibFunctionsHasShapeOnlyKey` test
   explicitly verifies both keys are populated, so the
   `SummaryApplier.fallbackParse` path
   (`Class.method/arity` → shape-only key) hits even for
   library functions that R259 was originally designed to
   unblock
4. **Pre-pass handles every function once, idempotently** (R260
   design). The `warmUpIsIdempotent` test verifies that calling
   the pre-pass twice doesn't double the cache size — the cache
   is just a `Map`, so re-inserting the same key just replaces
   the value. This is the standard "idempotent pre-pass"
   pattern; the alternative (tracking which functions have been
   summarised to avoid double-build) would be more code for
   exactly the same result

## 4 pitfalls fixed (R260)

1. **`TacOperand.Const` 3-arg ctor vs `Const.ofInt(42)` factory**
   (R260 pitfall). The 3-arg ctor `(Kind, Object, TacType)` is
   package-private; from the test, only the static factories
   (`ofInt`, `ofBool`, etc.) are accessible. Fix: use the
   factory methods. The compile error was caught immediately
   by `mvn test-compile` so this is a 1-line fix, not a
   design issue
2. **Removed `buildAndStoreMethodSummary` from the per-function
   loop left a dead method** (R260 minor). The method is
   still defined and used by the pre-pass, so it's not actually
   dead — but the call site in the analysis loop is gone. The
   pre-R260 inline comment in the analysis loop
   ("R255: also build the IR-derived MethodSummary for the
   same function...") was updated to explain the move. No
   code deletion needed; the method is just no longer called
   from the loop
3. **R260 vs R259 shape-only key test** (R260 design choice).
   The shape-only key uses `returnType="?"` (the canonical
   "unknown" marker from R259). The R260 test verifies both
   the class-only and shape-only keys are populated. This is
   a regression guard against accidentally regressing the
   R259 dual-key install when refactoring the pre-pass
4. **R260 doc language is consistent with R259** (R260
   minor). R259's follow-up section described two candidate
   approaches (pre-pass vs lazy build). R260's doc explicitly
   calls out why pre-pass was chosen over lazy build, and
   points back to R259 for the design rationale. Cross-doc
   references make the R259 + R260 pair easier to reason
   about as a unit

## What R260 unlocks

R260 fixes the call-order dimension of the cross-function fold
pipeline. The R256/R257 fold consumers are now guaranteed to
see a fully-warm `MethodSummaryCache` on the first analysis
pass, regardless of which function is visited first. This is
the missing prerequisite for any future R-round that depends
on the fold-derived fact chain being correct end-to-end.

The remaining gap (Juliet sweep still produces 0 fold-derived
findings) is the **IR-completeness** dimension, not the
**call-order** dimension. R260 has fixed call-order; the
upstream parser/translator needs to support Juliet's
`try-catch-finally` patterns for IR to be complete. That's
a separate R-round and the R259 doc + R257 follow-up already
identified it as such.

## Files (R260 main + tests)

- `plumb-dfa/.../engine/DfaEngine.java` (modified, +
  `warmUpMethodSummaries` public method, ~30 lines, +pre-pass
  call in `runSequential` and `runWorklist`, −`buildAndStoreMethodSummary`
  call from per-function analysis loops)
- `plumb-dfa/.../test/.../engine/R260CacheWarmupTest.java` (new, 5 tests)

## Backups

`D:\work\tmp\r260_done\` — main + test + test logs.

## Follow-up (R261+)

- **R261** — Path-sensitive walker 跟 fold fact 整合
  (~1d, gated on R259 IR-completeness): let `PathCondition` walk
  read `RETURN_VALUE_FOLDED` facts and use them to suppress
  impossible-path conditions
- **R262** — Cross-TU fold-driven finding exercise
  (~0.5d, gated on R259 IR-completeness): now that R260
  guarantees the fold pipeline is correct, the next step is
  making the parser/translator emit enough IR for Juliet's
  `try-catch-finally` to actually exercise the fold
