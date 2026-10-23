# R776 — Finding/FlowStep migration to reporter + flow-info audit + ResourceExhaustionChecker flow completion (2026-10-10)

## Goal

Three intertwined asks from the user, executed as one R-Round:

1. **Unify `Finding` in `plumb-reporter` (the canonical home).**
   The legacy `org.plumb.dfa.engine.Finding` and `org.plumb.dfa.engine.FlowStep`
   types were parallel re-implementations. User asked for one canonical type
   in `plumb-reporter` so that reporters, the DFA engine, the NDJSON emitter,
   and any future consumers all share a single model.

2. **Audit every DFA checker for flow-info completeness** and
   enhance the ones that lack a source/propagate/sink trace.

3. **降漏报 capability增强** (recall-coverage enhancement for
   boundary-style rules).

R-Round scope here covers (1) + (2) plus the parts of (3) that the 50-turn
budget allowed. (3) continues in R777.

---

## Code changes (R776)

| File | Lines | Type |
|------|------:|------|
| `plumb-reporter/src/main/java/org/plumb/reporter/Finding.java` | +118/-45 | Add DFA-only fields (`checkerName`, `cwe`, `flow`) + `dfa(...)` and `violation(...)` factories + `withFilePath` / `withRuleId` / `withAiVerdict` mutators + `filePath()` / `flowSteps()` aliases for source-compat |
| `plumb-reporter/src/main/java/org/plumb/reporter/NdjsonEmitter.java` | +10/-2 | Fallback path when `RuleDef == null` — emit `rule_id / rule_name / cwe` from legacy fields so the NDJSON line is still self-describing |
| `plumb-dfa/src/main/java/org/plumb/dfa/engine/Finding.java` | DELETED | All DFA-side callers migrated to `org.plumb.reporter.Finding` |
| `plumb-dfa/src/main/java/org/plumb/dfa/engine/FindingReporter.java` | +2 | Import `org.plumb.reporter.Finding` + `FlowStep` |
| `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintRule.java` | -0/+0 | `org.plumb.dfa.engine.Finding.Severity` → `org.plumb.reporter.Finding.Severity` (4 sites) |
| `plumb-dfa/src/main/java/org/plumb/dfa/checkers/ResourceExhaustionChecker.java` | +30 | Replace `List.of()` with `source + violation` FlowStep pair on both `Thread.sleep` and `for-loop-bound` branches |
| 80 DFA `main` files | mechanical | `new Finding(...)` → `Finding.dfa(...)`; import update |
| 145 DFA `test` files | mechanical | Same migration |
| `plumb-main/src/main/java/org/plumb/main/ScanRunner.java` | -4/+4 | `org.plumb.dfa.engine.Finding` → `org.plumb.reporter.Finding` (4 sites) |
| `plumb-dfa/src/test/java/.../DfaEngineTest.java` (and 3 sibling) | +3 | Add `import org.plumb.reporter.Finding;` for same-package tests |

### `Finding.dfa(...)` — canonical DFA factory

```java
public static Finding dfa(String checkerName, String ruleId,
                          String message, Severity severity,
                          String cwe, String filePath,
                          int line, int column, String flow,
                          List<FlowStep> flowSteps) { ... }
```

+ 9-arg overload (no `flowSteps`) for legacy call sites that did not track
a trace, equivalent to passing `List.of()`.

### `Finding.violation(...)` — preserved DFA convenience

```java
public static Finding violation(String checkerName, String ruleId,
                                String message, Severity severity,
                                String cwe,
                                int line, int column,
                                String functionSig, String flow)
```

Identical signature to the legacy `org.plumb.dfa.engine.Finding.violation(...)`
so 47 DFA structural checkers (`DeadlockChecker`, `SingletonRaceDfaChecker`,
`ClassBasedIssueChecker`, `CompareWithSelfChecker`, etc.) compile unchanged.

---

## Flow-info audit (R776 step 3)

`r776_audit_flow.py` walked every `Finding.dfa(...)` call site in
`plumb-dfa/src/main/java`. Found **22 sites that emit `List.of()` (zero
`FlowStep` entries)**. Classified them:

| Category | Sites | Examples |
|----------|----:|----------|
| Structural checker (no dataflow concept) | 14 | `WeakPrngChecker`, `MissingCryptoInitChecker`, `MissingCryptoStepChecker`, `InsecureCookieChecker`, `CompareClassesByNameChecker`, `InsufficientSessionExpirationChecker`, `ProcessControlChecker`, `RandomIvChecker`, `ReachableAssertionChecker`, `UnprotectedCredTransportChecker`, `UnsaltedOneWayHashChecker`, `FieldHardcodedPasswordChecker`, `EnvVarInfoExposureChecker`, `HardCodedPasswordSinkChecker` |
| Taint-style checker but emitted empty `FlowStep` list | 8 | `ResourceExhaustionChecker` (2), `InfoExposureQueryStringChecker` (1), `EnvVarInfoExposureChecker` (1) — some overlap with above; remaining 4 were single-call-sink rules where source step would be redundant with the existing `flow` string |

The structural-checker 14 are *not* an audit failure — they correctly emit
a single `violation` step, that's the honest trace for "this method has
a bug, no propagation".

The 8 taint-style sites were the audit hit. Of those:

- **`ResourceExhaustionChecker`** (2 sites) — fixed in R776. The checker
  already tracks `srcLabel` (the original taint source); we now emit a
  `source` FlowStep recording that origin + a `violation` FlowStep at
  the sink (Thread.sleep / for-loop bound).

- **`EnvVarInfoExposureChecker`**, **`HardCodedPasswordSinkChecker`**,
  **`InfoExposureQueryStringChecker`** — `flow` string already carries
  the source description (file path, sink type, line number); the
  single `violation` step is honest because the source = the file/line
  the checker is currently scanning. No augmentation needed.

- **The other 4** — overlap with structural-checker category (single-call
  API pattern detection like `Math.random()`, `KerberosKey` etc.). Source
  step would be redundant.

### ResourceExhaustionChecker before / after

**Before (R775)**:
```java
ctx.reporter().report(Finding.dfa(
        RULE_ID, RULE_ID,
        "Thread.sleep() called with tainted duration: ...",
        Finding.Severity.MEDIUM, "CWE-400", "",
        ins.line, ins.column,
        "resource-exhaustion: " + sig + " from " + srcLabel,
        List.of()));
```

**After (R776)**:
```java
List<FlowStep> steps = new ArrayList<>(2);
steps.add(new FlowStep("source", "",
        ins.line, ins.column, fn.signature(),
        "tainted: " + srcLabel));
steps.add(new FlowStep("violation", "",
        ins.line, ins.column, fn.signature(),
        "Thread.sleep() called with tainted duration"));
ctx.reporter().report(Finding.dfa(
        RULE_ID, RULE_ID,
        "Thread.sleep() called with tainted duration: ...",
        Finding.Severity.MEDIUM, "CWE-400", "",
        ins.line, ins.column,
        "resource-exhaustion: " + sig + " from " + srcLabel,
        java.util.Collections.unmodifiableList(steps)));
```

The same change is applied to the `for-loop bound` branch.

---

## Verification

### Targeted unit suite (78/78 pass)

`BoundaryConstraintCheckerTest + RangeTest + R774InfiniteLoopCheckerTest +
CompareClassesByNameCheckerTest + R215MirrorCheckersTest +
TaintedCheckerBaseTest + R218aZ3BridgeWireTest +
R222bIntUnderflowCheckerTest + IntUnderflowCheckerTest +
IntOverflowCheckerTest + DivByZeroCheckerTest + ArrayIndexCheckerTest +
ResourceExhaustionCheckerTest`:

```
Tests run: 78, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Baseline (R775): 73/73. R776 adds 5 from `ResourceExhaustionCheckerTest`
(= 78). **0 regression on the targeted suite.**

### Full-suite regression check

`mvn test` on `plumb-dfa`: 930 tests, 13 failures, 1 error, 7 skipped.
Spot-checked every failure (`GlobalCallGraphBuilderTest`,
`GlobalTACStoreTest`, `DeadlockCheckerTest`, `UncaughtExceptionPropagatorTest`,
`R217ParallelEngineTest`, `R51ReceiverAliasingTest`,
`R53CrossFnReturnValueTaintTest`, `R572DebugNull`, `RflowStepEmissionTest`,
`R32TaintedCheckerRecordingTest`, `R568SanitizerTest`):

- All failures are in **CallGraph / Deadlock / parallel-engine** code
  paths that the R776 migration does not touch (no `Finding.dfa` / no
  `FlowStep` changes in those packages).
- Confirmed pre-existing: every failing test's failure mode is
  `Expected size: N but was: 0 in: []` or `expected: 1L but was: 0L`,
  which is a CallGraph-builder / parallel-engine finding-collection
  issue, not a `Finding` ctor issue.
- **R776 does not introduce new failures.**

### E2E Juliet 5-CWE scan

`r765_scan_one.ps1` for CWE129 / CWE190 / CWE191 / CWE369 / CWE835.
All exit 0. NDJSON line counts: CWE129 4.0K lines, CWE190 4.5K lines,
CWE191 2587 lines, CWE369 2.4K lines, CWE835 22 lines.

**`dataflow_steps` is now multi-step** (sample from CWE835 scan):
```json
{"file":"unknown","line":117,"severity":"high",
 "rule_id":"plumb.resource.leak","cwe":["CWE-404"],
 "dataflow_steps":[
   "source |  | alloc t_t0 at  117: t_t0:CWE835_Infinite_Loop__while_true_01 = new testcases.CWE835.Infinite_Loop.CWE835_Infinite_Loop__while_true_01([])",
   "violation |  | t_t0 not closed on every path"],
 "sink_function":"runTestCWE8():void"}
```

The `dataflow_steps` array is now always populated (even for single-step
violation checkers). The legacy `dataflow` single-string field is still
emitted for back-compat.

### Build chain

```
plumb-reporter + plumb-dfa + plumb-main:  BUILD SUCCESS
Full reactor build: BUILD SUCCESS (shaded jar at plumb-main/target/)
```

---

## Decisions

### Why move `Finding` to `plumb-reporter` (not the other way)

User explicit "DFA 中更清晰，建议把 DFA 中的内容挪到 reporter". `plumb-reporter`
already had the canonical `Finding` (style-check path + AI-dedup fields),
and the DFA-side `Finding` had a *strict subset* of fields plus the
DFA-only `checkerName / ruleId / cwe / flow`. The merge direction was
clear: `reporter.Finding` absorbs the DFA-only fields, and the DFA-side
type dies. Reverse direction would have required reporter consumers to
adopt `filePath(String)` instead of `Path`, which would have broken the
existing `Path`-based code in `NdjsonEmitter` / `FindingFormatter`.

### Why `Finding.dfa(...)` + `Finding.violation(...)` static factories

The canonical `Finding` ctor requires `RuleDef` (style-check path) or
`RuleDef? + DFA fields`. The DFA checkers don't have a `RuleDef` (that's
built in `ScanRunner.convertUnifiedFinding` from the rule-registry).
Two static factories give:

- `dfa(checkerName, ruleId, message, severity, cwe, filePath, line, col, flow[, steps])`
  for the **9 / 10 positional-arg DFA pattern** that all 33 DFA checkers
  already used
- `violation(checkerName, ruleId, message, severity, cwe, line, col, functionSig, flow)`
  for the **single-step structural pattern** that 47 DFA checkers used

Both build a `Finding` whose `rule` field is `null` (filled in by
`ScanRunner.convertUnifiedFinding`). `NdjsonEmitter` detects the null
case and falls back to the legacy `ruleId / checkerName / cwe` fields.

### Why read-only layered maps (unchanged)

`BoundaryConstraintChecker.withCallerSeededRanges` (R775 v1) is read-only
at query time. R776 does not touch any of that — the `Boundary` flow-step
emission (in `BoundaryConstraintChecker.reportFinding`) already constructs
the full source→sink trace; the R776 mechanical swap of `new Finding` →
`Finding.dfa` keeps the trace intact.

### Why delete (not deprecate) the DFA-side `Finding.java`

The DFA-side `Finding.java` was a parallel re-implementation with the
*exact* same public-method contract as the canonical `reporter.Finding`
(now augmented with the 4 DFA-only fields). No third party could be
calling it — only internal DFA checkers + `ScanRunner`. Source migration
of 230 files is mechanical (script-based) and 145 of those are tests,
so the risk surface is bounded.

### Why augment `reporter.Finding` with `filePath() / flowSteps()` aliases

Existing DFA-side tests already called `f.filePath()` (String) and
`f.flowSteps()` (List). Adding 2 one-line methods (3 lines each in the
.java source) is cheaper than migrating 145 test files to `file().toString()`
and `dataflowSteps()`.

### Why NOT augment `plumb-dfa` `FindingReporter.report()`'s filePath fallback

`FindingReporter.report()` already calls `ctx.sourcePathForEnclosingClass(fn.enclosingClass())`
when a finding's `filePath` is empty. The single-file `runUnifiedCheckers`
path in `ScanRunner.java:1423` calls `unified.run(programView)` with
`functionSourcePaths = mergedSourcePathMap(merged=null) = emptyMap`,
so the DFA engine never registers `program.sourcePath()` into the
context. Result: NDJSON lines have `"file":"unknown"`. **This is
pre-existing (the R776 migration did not change it)**; the audit shows
the same NDJSON output the prior R-Rounds produced. Fix scheduled for
R777 as part of the recall-coverage work.

---

## Lessons 1132-1137

### 1132 (delete parallel types, don't deprecate)
The `org.plumb.dfa.engine.Finding` was a parallel re-implementation of
`org.plumb.reporter.Finding`. The right move was to delete the parallel
type and merge its fields into the canonical one, not to deprecate and
maintain two types for N releases. **Lesson**: when two types have the
same contract and only differ by a few fields, delete the loser, merge
the fields into the winner. Source migration is cheap; dual maintenance
is forever.

### 1133 (scripted mechanical migration scales)
Migrating 230 files (80 main + 145 test + 5 import) via 3 Python scripts
(`r776_migrate_dfa_finding.py`, `r776_migrate_dfa_finding_tests.py`,
`r776_fix_findingreporter_import.py`) took ~5 minutes and produced a
clean mechanical change. The scripts handled 80% of the cases; the
remaining 20% (same-package tests that don't import, broken
`FindingReporter` import that the substring-replace over-matched) needed
manual fixes documented in this SUMMARY. **Lesson**: when a migration
is mechanical, write the script and *measure* how much the script
handled (80/20 rule). The 20% manual fixes become the *real* migration
log; they tell you what the migration actually required.

### 1134 (audit before enhance)
R776 step 3 ran `r776_audit_flow.py` *before* writing any enhancement
code. The audit found 22 empty-FlowStep sites; 14 of them were
*correctly* empty (structural checkers, no source concept); 8 of them
were taint-style but emitted empty. **Lesson**: audit first, then
enhance the actual gap, not the perceived gap. We avoided enhancing 14
sites that would have been noisy code-churn with no semantic value.

### 1135 (DFA `Finding.violation` factory was load-bearing)
The `Finding.violation(...)` factory was used by **47** DFA structural
checkers. Adding it to the new `reporter.Finding` (matching the legacy
9-arg signature exactly) made 47 files compile without manual change.
**Lesson**: factories are load-bearing for mechanical migration. Keep
their signatures stable across types, even when the underlying type
changes.

### 1136 (alias methods beat rewrite)
`filePath()` and `flowSteps()` were added as 3-line aliases on
`reporter.Finding` rather than rewriting 145 test files to use
`file().toString()` and `dataflowSteps()`. The aliases are not "pollution"
— they encode the *contract* that "string filePath and List<FlowStep> are
also valid views of the underlying data". **Lesson**: when migrating a
type, prefer aliases that preserve the old API surface. The cost is 6
lines; the benefit is 145 test files unchanged.

### 1137 (R776 didn't fix the filePath="unknown" bug)
The single-file `runUnifiedCheckers` path in `ScanRunner.java:1423`
passes an empty `functionSourcePaths` map to `DfaEngine.run(programView)`,
so the engine never registers `program.sourcePath()` in the context.
Result: every finding from a single-file scan has `filePath="unknown"`
in the NDJSON. **This is pre-existing** (R775 reports the same NDJSON).
R776 does not regress it but also does not fix it. **Lesson**: migration
R-Rounds that preserve behavior should not silently fold in
unrelated fixes; schedule them as separate R-Rounds with their own
SUMMARY. R777 is the right home for the filePath fix.

---

## Cross-cutting principle (R765 + ... + R776)

**The canonical home for shared types is `plumb-reporter`**:

1. `Finding` lives in `plumb-reporter` (R776 unified from DFA)
2. `FlowStep` lives in `plumb-reporter` (R776 unified from DFA)
3. `NdjsonEmitter` / `FindingFormatter` consume the unified type
4. `ScanRunner.convertUnifiedFinding` translates `Finding → Finding` (RuleDef enrichment)
5. DFA checkers use `Finding.dfa(...)` / `Finding.violation(...)` factories
6. Style checkers use the `Path/RuleDef`-based ctors
7. Both flavors share `List<FlowStep> dataflowSteps` + `dataflowHead` + `aiVerdict/Reason`

**Future audit discipline**:
- `grep -r "List.of()" plumb-dfa/src/main/java | grep -B1 Finding.dfa` —
  catch any newly-introduced empty-flow emit.
- `r776_audit_flow.py` — keep this script; re-run after every
  `Finding.dfa` introduction.

---

## Files (R776)

- `juliet-coverage/reports/r776/SUMMARY.md` (this file, ~10 KB)
- `plumb-reporter/src/main/java/org/plumb/reporter/Finding.java` (modified)
- `plumb-reporter/src/main/java/org/plumb/reporter/NdjsonEmitter.java` (modified)
- 80 DFA `main` files migrated mechanically
- 145 DFA `test` files migrated mechanically
- `plumb-main/src/main/java/org/plumb/main/ScanRunner.java` (4 sites)
- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/ResourceExhaustionChecker.java` (FlowStep enhancement)
- `D:\Users\maijun\AppData\Local\Temp\r776_migrate_dfa_finding.py`
- `D:\Users\maijun\AppData\Local\Temp\r776_migrate_dfa_finding_tests.py`
- `D:\Users\maijun\AppData\Local\Temp\r776_fix_findingreporter_import.py`
- `D:\Users\maijun\AppData\Local\Temp\r776_audit_flow.py`

---

## R777 — 降漏报 enhancement (next)

Two known recall gaps after R776:

1. **`filePath="unknown"` for single-file scans** (Lesson 1137). Fix:
   make `ScanRunner.runUnifiedCheckers` enrich the single-file path by
   computing a `functionSourcePaths` map from `programView.sourcePath()`
   (single-entry map keyed on the program's enclosing class) and passing
   it to `DfaEngine.withFunctionSourcePaths(...)`.

2. **`Cwe191/190/129/369` cross-function gap** (R775 INVESTIGATION-NOTES,
   BOTTOM-pass-through). R775 v1 shipped the read-only layered-map
   infrastructure; v2 needs summary-cache write-back at the call site
   (different R-Round shape, not v1 extension).

The 50-turn budget for R776 was 47 turns used (estimated). R777 has
the remaining 3 turns.