# R143 — Per-Class Source Path in Cross-Jar Scans (R142 Follow-Up)

**Status:** Done. 5/5 new tests pass. 222 dfa tests / 0 fail / 1 documented skip (zero regression).
0 IR / 0 TAC / 0 new checker changes — pure reporting-fidelity round.

## Motivation

R142 (`/docs/R142-CROSS-JAR-TYPE-HIERARCHY.md`) closed the
cross-jar type hierarchy gap: `BytecodeAnalyzer(List<Path>)`
builds a single SootUp `JavaView` over all inputs, so calls
from input[0]'s classes to input[1]'s classes resolve and
cross-function taint propagates.

R142's known limitation (per the doc):

> "Per-class source path granularity in cross-jar mode: the
> `TacProgram.sourcePath` for a class is the first input's path
> (we don't know which input each class came from). Findings
> report the first input's path; the user can see findings from
> cross-jar, but can't tell which `.jar` the finding's class
> came from. R143 should expose `view.getClassSource(FQN)` to
> get the actual source path per class."

R143 closes this gap. With SootUp 3.0.0's per-class
`SootClassSource` API, every class carries the
`AnalysisInputLocation` that produced it. R143 maps that back
to the user-supplied input path and uses it as
`TacProgram.sourcePath` per class. Findings now report the
correct artifact (the actual `.jar`, or the actual `.class`
file in a directory input).

## What R143 Includes

### R143a — `locationToInput` map built in `openView()`

`plumb-tac/.../tac/bytecode/BytecodeAnalyzer.java`

The `openView()` method now records every `locationFor(input)`
result in a `Map<AnalysisInputLocation, Path>` field. The
`AnalysisInputLocation` instance is the same object the
`JavaView` uses, so identity-based lookup is exact (no
overrides of `equals` needed — we use the default `Object`
equality, which is reference equality for these classes).

```java
private final Map<AnalysisInputLocation, Path> locationToInput = new HashMap<>();

private View openView() {
    List<AnalysisInputLocation> locations = new ArrayList<>();
    for (Path input : inputs) {
        AnalysisInputLocation loc = locationFor(input);
        locations.add(loc);
        locationToInput.put(loc, input);
    }
    return new JavaView(locations);
}
```

### R143b — `resolveClassSourcePath(cls)` per-class resolution

`plumb-tac/.../tac/bytecode/BytecodeAnalyzer.java`

A new helper that, for a given `SootClass`, asks
`cls.getClassSource().getAnalysisInputLocation()` and looks
up the corresponding user input. The path is shaped by the
input kind:

| Input kind            | Reported sourcePath                              |
|-----------------------|--------------------------------------------------|
| `.jar` / `.war` /     | The archive file (e.g. `lib.jar`). The per-class  |
| `.ear` / `.zip`       | `getSourcePath()` returns the path INSIDE the    |
|                       | archive (`com/foo/Lib.class`); we replace that   |
|                       | with the archive itself.                         |
| Directory             | The actual `.class` file path. Matches the       |
|                       | AST `.java` per-class granularity.              |
| Single `.class` file  | The `.class` file itself (the class IS the input).|
| Unknown location      | First input (defensive fallback).                |

This is the user-facing benefit of R143: in a 2-`.jar` scan,
a finding inside `Lib.logIt` now reports `lib.jar` (where
`Lib` lives), not `app.jar` (the first input, which is the
R142 stand-in).

### R143c — Per-class source path in the existing pipeline

`TacProgram.sourcePath` is the same field it was — R143 just
sets it correctly. The downstream propagation in
`AnalysisContext.sourcePathForFunction` (R118a) and
`MergedProgram.sourcePathFor` (R117) already look up the
per-function source path from the underlying `TacProgram`.
So the existing `Finding.file` reporting automatically gets
the per-class fidelity — no engine changes needed.

## End-to-End CLI Validation

```
$ java -jar plumb-main-0.1.0-SNAPSHOT-all.jar \
    -i D:\r143_e2e\app.jar -i D:\r143_e2e\lib.jar -f ndjson
{"file":"D:\\r143_e2e\\lib.jar","line":"3","column":"-1",
 "severity":"low","rule_id":"bu-0108-taint-sink-log",
 "rule_name":"TAINT_SINK_LOG_INJECTION",
 "rule_type":"taint","cwe":["CWE-117"],
 "message":"Tainted l1:java.lang.String ([bu-0001-taint-request-param])
           flows into org.slf4j.Logger.info/1 ...",
 "dataflow":"..."}
```

The `file` column is `lib.jar` (where `Lib.logIt` lives).
R142 would have shown `app.jar` (the first input, which is
incorrect attribution).

## 5 New End-to-End Tests

`plumb-dfa/.../dfa/PerClassSourcePathTest.java`

1. **`directoryInputReportsActualClassFileForEachClass`** —
   2 directory inputs, each class's sourcePath is the actual
   `.class` file inside its own input directory.
2. **`jarInputReportsArchivePathForEachClass`** — 2
   `.jar` inputs, each class's sourcePath is the actual
   `.jar`. This is the headline R143 fix.
3. **`singleJarInputStillReportsTheJar`** — regression
   guard: single `.jar` input still reports the `.jar`.
4. **`sourcePathPropagatesThroughMergedProgram`** — R118a
   + R143 composition: `MergedProgram.sourcePathFor(sig)`
   returns the right per-class path for each function.
5. **`mixedJarAndDirectoryInputsResolveEachToItsOwn`** —
   mixed input list (a directory + a `.jar`), each class
   attributed to the input it actually came from.

All 5/5 pass. Full test count: 222 dfa / 0 fail / 1 documented skip
(pre-existing Z3 skip in `WebAppConfigCheckTest`, not R143-related).

## Why This Works Without IR Changes

- `TacProgram.sourcePath` was always a per-program field
  (not per-function). With the AST path, one `TacProgram`
  per `.java` file, so `sourcePath` = the `.java` file.
  This naturally gave per-file granularity.
- With the bytecode path, one `TacProgram` per class
  (introduced in R140). R142 didn't update the sourcePath,
  so it stayed as `inputs.get(0)`. R143 fixes the value.
- `MergedProgram.functionSourcePath` (R117) and
  `AnalysisContext.sourcePathForFunction` (R118a) already
  look up the per-function path from the underlying
  `TacProgram`. No engine changes needed.

## Cumulative Wins (R115 → R143)

- 29 rounds
- 39 checker categories (R143 0 new, pure reporting fix)
- 27+ CWE families
- 256 tests (222 dfa + 19 tac + 10 style + 2 reporter + 3 main) / 0 fail / 1 documented skip
- 31 per-round design docs
- 14 end-to-end demo files
- 125 end-to-end findings (examples) + 14 security findings (PetClinic, 100% precision)
- **NEW R140**: `.jar` / `.class` bytecode input front-end
- **NEW R141**: function summary cache by entry-taint-fingerprint — 20+ layer scaling
- **NEW R141b**: 7 end-to-end nested-field chain tests
- **NEW R142**: cross-jar type hierarchy
- **NEW R143**: per-class source path in cross-jar scans

## R143 Limitations / R144+ Candidates

- **Cross-jar type hierarchy is shallow**: a class in
  input[0] that extends a class in input[1] is loaded, but
  the type hierarchy isn't walked. `instanceof` checks
  across jars don't work. R144 could add a class-level
  lookup table.
- **No Maven multi-module auto-detect**: user manually
  lists inputs. R147 candidate (deferred from R139).
- **R118c exception-path taint still deferred**: try/catch
  edges in CFG.
- **R141b cross-function fieldMap propagation**: when a
  helper in one input writes to a field chain, the fieldMap
  is not merged into the caller's TaintFact.
- **Real Spring Boot `.war` validation (R140c candidate)**:
  vulnerable Spring Boot `.war` (with entry point) for
  CVE-2022-42889 + CVE-2021-44228 real recall baseline.
- **R148 TRUST_BOUNDARY_VIOLATION (Coverity)**: entry-point
  classification + cross-domain storage leak.

## Paper Contribution

R143 is the **"Build infra, then use it"** cadence, again:
R117's `MergedProgram.functionSourcePath` (per-function
source path map) + R118a's `AnalysisContext.sourcePathForFunction`
+ R140's bytecode front-end + R142's multi-input view — all
the infrastructure was already there. R143 just plugs the
final gap: set `TacProgram.sourcePath` per class using
`SootClassSource.getAnalysisInputLocation()`.

The whole round is ~50 lines of code (1 helper + 1 map
field + 1 lookup) and 0 IR / 0 TAC / 0 engine changes.
This is the "minor reporting-fidelity round" pattern: when
the right infra exists, fixing a 22-round-old gap is a
50-line change.

R143 also demonstrates the **"Cross-jar / cross-input
infrastructure rounds"** pattern emerging in the project:
R140 (input front-end) → R141a (scaling) → R141b
(translator cooperation) → R142 (cross-jar type hierarchy)
→ R143 (per-class source path). Each is additive; each
benefits from the previous rounds' infrastructure.

The user's explicit non-functional requirement (R141
context):

> "对于 java 语言这类面向对象语言来说，域敏感的分析，
> 跨TU和跨函数的分析（分析20层朝上性能不会衰减太多），
> 污点分析，域敏感、跨过程的指针分析都是非常重要能力。"

R143 directly serves "跨TU" (cross-TU) by giving the user
visibility into which artifact a cross-TU finding came from
— without it, the user has to manually inspect every jar to
find the source of a finding.
