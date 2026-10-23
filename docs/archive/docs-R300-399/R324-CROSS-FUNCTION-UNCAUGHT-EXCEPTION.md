# R324 — Cross-Function Uncaught Exception Propagation (CWE-248)

Date: 2026-08-04  
Status: DONE — 5 unit tests + 4 e2e tests, 0 regression, +1 DFA checker

## Summary

R314 covers the **intra-function** half of CWE-248 (a `throw` with no matching
`catch` in the same function). R324 adds the **cross-function** half: a call
site where the callee has at least one `THROW` instruction, but the caller
function has zero `CATCH_HANDLER` instructions. The exception will propagate
out of the caller — exactly what CWE-248 describes.

## Design

### Single-checker, two-pass architecture

The DFA engine visits functions in reverse post-order (RPO). A caller's
`checkInstruction` is invoked *after* the callee's analysis only when the call
graph's RPO frontier is strict. In the presence of mutual recursion or even
the first caller in RPO, the callee's data may not yet be in the cache. To
avoid that race, R324 does both passes inside one checker:

1. **Per-function `checkInstruction`**: collect two boolean facts —
   `hasThrow[functionKey] = true` on every `THROW`, and
   `hasCatch[functionKey] = true` on every `CATCH_HANDLER`. (R314's data on
   the same instructions is unaffected; the two checkers are independent.)
2. **Global `finish` pass**: re-walk every function's `CALL` instructions.
   For each call, if `hasThrow.containsKey(calleeSig)` (i.e. the callee
   is in the program and it throws) AND `!hasCatch.containsKey(callerKey)`
   (i.e. the caller has zero catches), emit a CWE-248 finding on the call
   site.

`finish` runs once after every function has been visited, so the two maps
are complete by then.

### Key normalisation

The TAC IR uses two different signature shapes:
- `TacFunction.signature()` returns the full declaration form
  `foo(int, String):void` (used internally by the engine).
- `TacInstruction.calleeSig` on a CALL returns the short form `foo/2`
  (method name + arity, used by the translator when emitting call sites).

R324 keys both maps by the short form (`fn.name() + "/" + fn.params().size()`)
so a `hasThrow.containsKey(ins.calleeSig)` lookup in `finish` works
directly. A `functionKey(TacFunction)` helper centralises the conversion —
if the translator ever changes the short form, only that one method
needs to update.

## Implementation

### `UncaughtExceptionPropagator.java` (new)

Location: `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/UncaughtExceptionPropagator.java`

```java
public final class UncaughtExceptionPropagator implements DfaChecker {
    private final Map<String, Boolean> hasThrow = new HashMap<>();
    private final Map<String, Boolean> hasCatch = new HashMap<>();
    private final Set<String> fired = new HashSet<>();   // dedup: callerKey|calleeKey

    @Override public String name() { return "UncaughtExceptionPropagator"; }

    @Override
    public void initEngine(AnalysisContext ctx) {
        hasThrow.clear(); hasCatch.clear(); fired.clear();
    }

    @Override
    public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
        switch (ins.kind) {
            case THROW -> hasThrow.put(functionKey(fn), Boolean.TRUE);
            case CATCH_HANDLER -> hasCatch.put(functionKey(fn), Boolean.TRUE);
            default -> {}
        }
    }

    @Override
    public void finish(AnalysisContext ctx) {
        for (TacFunction fn : ctx.program().functions()) {
            String callerKey = functionKey(fn);
            if (hasCatch.containsKey(callerKey)) continue;   // caller has at least one catch
            for (TacInstruction ins : fn.instructions()) {
                if (ins.kind != TacInstruction.Kind.CALL) continue;
                if (ins.calleeSig == null) continue;
                if (!hasThrow.containsKey(ins.calleeSig)) continue;
                String key = callerKey + "|" + ins.calleeSig;
                if (!fired.add(key)) continue;
                emit(ctx, ins, fn, ins.calleeSig);
            }
        }
    }

    private static String functionKey(TacFunction fn) {
        return fn.name() + "/" + fn.params().size();
    }
}
```

### `ScanConfig` + `ConfigLoader` + `ScanRunner` wiring (R324 +)

- `ScanConfig.runUncaughtExceptionPropagator` (default `true`)
- `ConfigLoader` reads `checks.uncaughtExceptionPropagator` from YAML
- `ScanRunner.runUnifiedCheckers` adds the checker when the flag is on
- `ScanRunner.scanFile` (single-file path) also recognises the flag, so
  the unified engine gets to translate the file

## Tests

### Unit (`UncaughtExceptionPropagatorTest`)

5 tests, all passing:

| Test | What it checks |
|------|---------------|
| `callerHasNoCatch_calleeThrows_fires` | Basic case: `foo()` throws, `bar()` has no try-catch → CWE-248 on the call site |
| `callerHasTryCatch_calleeThrows_doesNotFire` | `bar()` wraps in try-catch → no R324 finding |
| `calleeDoesNotThrow_doesNotFire` | `foo()` doesn't throw → no finding |
| `multipleCallers_oneProtected_oneNot_onlyUnprotectedFires` | `caller1()` no-catch + `caller2()` has-catch → only `caller1` fires |
| `intraProgramCalleeOnly_jdkCallsIgnored` | `list.add(...)` (JDK) → no finding (callee not in our `hasThrow` map) |

### E2E (`R324ScanRunnerUncaughtE2ETest`, `R324ConfigLoaderUncaughtTest`)

4 tests in `plumb-main`:
- R324 flag default = true
- YAML `uncaughtExceptionPropagator: false` → flag = false
- E2E: full `ScanRunner` run on a fixture with `foo() throws / bar() no-catch`
  → NDJSON output contains `CWE-248` and `UncaughtExceptionPropagator`
- E2E off: same fixture, all checkers off → empty output

## Limitations (intentional, for R324 v1)

- **No type matching.** A caller with `catch (NullPointerException e)` that
  calls a callee throwing `IllegalStateException` is not flagged (the coarse
  check sees that the caller has at least one catch). A future R-round can
  refine by tracking the ctorType of the `NEW` that produces the thrown
  operand and comparing to the ctorType of each `CATCH_HANDLER`.
- **Only intra-program callees.** JDK and library calls are silently ignored
  (we don't have their bodies). This avoids false positives on common
  patterns like `Files.readAllBytes(...)` (the developer almost always
  writes a try-catch for it).
- **No rethrows.** A function with zero catches that only rethrows via a
  declared `throws Exception` is still flagged — which is the desired
  behaviour (the developer should think about whether the exception
  really needs to propagate).

## Pitfalls encountered

1. **Signature format mismatch.** First implementation keyed `hasThrow` by
   `fn.signature()` (full form `foo():void`) but `ins.calleeSig` is the short
   form (`foo/0`). Direct `hasThrow.containsKey(ins.calleeSig)` was always
   false. **Fix:** normalise to `name/arity` via a `functionKey` helper, then
   both lookups use the same string.
2. **Race with RPO.** Putting the cross-function check in `checkInstruction`
   on `CALL` would not work for callers at the RPO frontier (callee summary
   not yet in the cache). **Fix:** two passes — `checkInstruction` collects
   facts, `finish` emits. `finish` runs once at the very end so the maps
   are complete.
3. **`JavaSourceParser.parse(Path)` vs `parseFromString(Path, String)`.**
   The new test class initially called the wrong overload (compilation
   error: `String cannot be converted to Path`). **Fix:** use
   `parseFromString(Path.of("Sample.java"), src)`, matching the pattern in
   the other DFA checker tests (e.g. `TryCatchFinallyCheckerTest`).

## Numbers

| Module | Before R324 | After R324 | Δ |
|--------|------------|-----------|---|
| `plumb-dfa` checkers | 31 | 32 | +1 |
| `plumb-dfa` tests | 450 | 455 | +5 |
| `plumb-main` tests | 11 | 15 | +4 |
| 9 modules total tests | 3531 | 3539 | +8 |

0 regressions across all R-rounds (cumulative since R148f).

## Files changed

- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/UncaughtExceptionPropagator.java` (new, 174 lines)
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/checkers/UncaughtExceptionPropagatorTest.java` (new, 5 tests)
- `plumb-main/src/main/java/org/plumb/main/ScanConfig.java` (+1 flag)
- `plumb-main/src/main/java/org/plumb/main/ConfigLoader.java` (+1 YAML key)
- `plumb-main/src/main/java/org/plumb/main/ScanRunner.java` (+1 checker, +1 OR-chain entry)
- `plumb-main/src/test/java/org/plumb/main/R324ConfigLoaderUncaughtTest.java` (new, 2 tests)
- `plumb-main/src/test/java/org/org/plumb/main/R324ScanRunnerUncaughtE2ETest.java` (new, 2 tests)
