# R330 — filePath Fill-in for New DFA Checker Findings

Date: 2026-08-04  
Status: DONE — 0 regression, +1 unit test + 1 e2e assertion

## Summary

The R312+ new DFA checkers (R312a IteratorConcurrentModification,
R312b CollectionSelfReference, R314 TryCatchFinally, R315 OffByOne,
R316 EqualsCalledOnNull, R317 CompareWithSelf, R318 Xxe, R319
ReturnFromFinally, R320 InsecureCrypto, R321 EmptyCatchHandler,
R324 UncaughtExceptionPropagator) emit findings with an empty
`filePath` field. The `ScanRunner.convertUnifiedFinding` then falls
back to `Path.of("unknown")` for the SARIF / NDJSON / console
emission. R328 (the Juliet coverage sweep) revealed this as
a real problem: the file path was `"unknown"` for every new-checker
finding, which limits triage.

R330 fixes this by:

1. The engine tracks the "current function" in
   `AnalysisContext.currentFunction` and a per-enclosing-class
   source-path map in `AnalysisContext.sourcePathByEnclosingClass`.
2. The `FindingReporter` consults both when a finding is reported.
   If the finding's `filePath` is empty and there's a current
   function with a registered source path, the reporter fills it
   in via a new `Finding.withFilePath` method.
3. The `DfaEngine` populates the source-path map from
   `TacProgram.sourcePath()` for single-file scans, and from a
   caller-supplied `Map<String, Path>` for merged scans
   (the `MergedProgram.asProgram()` synthetic has no
   `sourcePath`).
4. The `ScanRunner` builds the per-enclosing-class map from the
   merged program's underlying sources and passes it to the
   engine via `DfaEngine.withFunctionSourcePaths`.

## Why DFA checkers don't carry the file path

The TAC IR does not preserve the file path. `TacFunction` has
`enclosingClass` and `origin` (an enum) but no `Path`. The legacy
`plumb-dfa` engine stored source paths in its own
`AnalysisContext` via a per-function lookup; the unified engine
didn't.

For single-file scans, the `TacProgram` has a single
`sourcePath()`, so the engine can register it against every
function's enclosing class. For merged multi-file scans, the
synthetic `MergedProgram.asProgram()` returns a `TacProgram`
with `sourcePath = null`, so the engine needs the per-enclosing-class
map from the caller (which has the underlying source list).

## Implementation

### `Finding.withFilePath` (R330 new method)

```java
public Finding withFilePath(String newPath) {
    if (newPath == null || newPath.equals(filePath)) return this;
    return new Finding(checkerName, ruleId, message, severity, cwe,
            newPath, line, column, flow);
}
```

Backward compatible — returns `this` if the path is unchanged.

### `AnalysisContext` (R330 additions)

- `setCurrentFunction(TacFunction)` / `currentFunction()` — the
  function the engine is currently analysing.
- `registerSourcePath(String enclosingClass, Path sourcePath)` —
  the per-enclosing-class source-path map.
- `sourcePathForEnclosingClass(String enclosingClass)` — the
  lookup used by `FindingReporter`.

### `DfaEngine` (R330 additions)

- `setCurrentFunction(fn)` called at the start of each
  `analyseOneFunction` (and reset to `null` in `finally`).
- `registerSourcePaths(ctx, program, functionSourcePaths)` —
  populates the map from either the caller's pre-built map
  (merged case) or `program.sourcePath()` (single-file fallback).
- `withFunctionSourcePaths(Map<String, Path>)` — setter for the
  caller to inject the map before `run`.

### `FindingReporter` (R330 additions)

- Constructor takes a back-reference to `AnalysisContext`
  (`FindingReporter(AnalysisContext)`).
- Legacy no-arg constructor preserved (`FindingReporter()`) for
  backward compatibility.
- `setContext(AnalysisContext)` — for the chicken-and-egg between
  context (takes reporter) and reporter (needs context). The
  engine creates the reporter first (no ctx), then the context,
  then calls `setContext` to wire the back-ref.
- `report(Finding)` fills in `filePath` from
  `ctx.sourcePathForEnclosingClass(currentFunction().enclosingClass())`
  if the finding's `filePath` is empty.

### Checkers that emit from `finish()` (R330)

R324 `UncaughtExceptionPropagator` emits from its `finish()`
callback, not from `checkInstruction`. By the time `finish()`
runs, the engine has reset `currentFunction` to `null`. R324
sets `ctx.setCurrentFunction(fn)` explicitly before each
emission, so the reporter can fill in the file path.

### `ScanRunner` (R330 additions)

- `runUnifiedCheckers(program, functionSourcePaths)` — new
  signature that takes the source-path map.
- `mergedSourcePathMap(MergedProgram)` — helper that walks the
  merged program's underlying sources and builds the map.

## Tests

### Unit (R330 in `UncaughtExceptionPropagatorTest`)

```java
@Test
void findingFilePathIsFilledInFromTacProgram() throws Exception {
    // Parse a real file (not a virtual path) so
    // TacProgram.sourcePath() is set. The R324
    // finding should report the file path, not
    // "unknown".
    Path tmp = Files.createTempFile("R330UncaughtThrow", ".java");
    Files.writeString(tmp, src);
    try {
        ParsedSource ps = parser.parse(tmp);
        TacProgram program = new TacTranslator(ps).translate();
        FindingReporter reporter = run(program,
                List.of(new UncaughtExceptionPropagator()));
        // assert filePath contains the temp file name
    } finally {
        Files.deleteIfExists(tmp);
    }
}
```

### E2E (R330 assertion in `R324ScanRunnerUncaughtE2ETest`)

```java
assertThat(ndjson)
        .as("R330 should fill in the filePath from the input file (not 'unknown')")
        .doesNotContain("\"file\":\"unknown\"");
```

## Juliet sweep re-run (R328 after R330)

The R328 sweep was re-run with the R330-fixed JAR. The per-family
finding counts are unchanged (the new DFA checkers are still firing
correctly on the same anti-patterns), but the byte sizes per file
are slightly larger (the new `"file":".../CWE396_*.java"` field is
populated). Example: CWE-396 went from 15341 bytes to 16460 bytes
(38 findings × ~30 extra bytes per finding from the full path).

## Pitfalls encountered

1. **Chicken-and-egg between AnalysisContext and FindingReporter.**
   AnalysisContext takes a reporter in its constructor; the
   reporter needs the context for the R330 filePath pass.
   **Fix:** make `FindingReporter.ctx` non-final with a
   `setContext` setter. The engine creates the reporter (no
   ctx), creates the context with the reporter, then calls
   `setContext(ctx)`.
2. **`currentFunction` reset between `analyseOneFunction` and
   the engine's `finish()`.** R324 emits from `finish()`, so by
   the time its `finish()` runs, the engine has reset
   `currentFunction` to `null`. The reporter's `report()` then
   can't resolve the file path. **Fix:** R324 explicitly calls
   `ctx.setCurrentFunction(fn)` before each emission in its
   `finish()` pass.
3. **Synthetic `MergedProgram.asProgram()` has `sourcePath =
   null`.** The engine's single-file fallback
   (`program.sourcePath()`) doesn't work for merged scans.
   **Fix:** the `ScanRunner` walks the merged program's
   underlying sources and builds a per-enclosing-class map,
   which it passes to the engine via `withFunctionSourcePaths`.
4. **Test fixture uses `parseFromString` with a virtual
   path.** The original R324 unit tests used
   `parseFromString(Path.of("Sample.java"), src)` which sets a
   virtual path that doesn't match any real file. The new R330
   test had to use `parser.parse(tempFile)` instead, so the
   `TacProgram.sourcePath()` is set to the real file.

## Numbers

- `plumb-dfa` tests: 455 → **456** (+1 R330 unit test)
- `plumb-main` tests: 15 (no change, R330 added a sub-assertion
  to an existing test)
- R328 sweep: 14/15 families with findings, total 474 findings
  (unchanged from before R330 — the fix only affects path
  resolution, not the check logic)
- File path correctness: was `"unknown"` for all new DFA checker
  findings; now resolved to the real source file path
- 0 regression across all R-rounds (cumulative since R148f)

## Files

- `plumb-dfa/src/main/java/org/plumb/dfa/unified/engine/Finding.java` (added `withFilePath`)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/engine/AnalysisContext.java` (added `currentFunction`, `sourcePathByEnclosingClass`, `registerSourcePath`, `sourcePathForEnclosingClass`)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/engine/DfaEngine.java` (added `withFunctionSourcePaths`, `registerSourcePaths`, `setCurrentFunction` in `analyseOneFunction`)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/engine/FindingReporter.java` (added `setContext`, `report` fills in filePath)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/UncaughtExceptionPropagator.java` (sets `ctx.setCurrentFunction` before each emit in `finish()`)
- `plumb-dfa/src/test/java/org/.../UncaughtExceptionPropagatorTest.java` (+1 R330 test)
- `plumb-main/src/main/java/org/plumb/main/ScanRunner.java` (runUnifiedCheckers takes functionSourcePaths; new `mergedSourcePathMap` helper)
- `plumb-main/src/test/java/org/.../R324ScanRunnerUncaughtE2ETest.java` (+1 R330 assertion)
