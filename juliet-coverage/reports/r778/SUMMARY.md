# R778 — Eliminate remaining 3868 "unknown-file" findings (2026-10-10)

## Goal

Resolve the 3868 `"file":"unknown"` findings that R777's
measurement-unblock surfaced. R777 fixed `Finding.dfa(...)`'s
sentinel-value bug, so the inner-DFA checkers that use `dfa(...)` no
longer hardcode `"unknown"`. But the *outer* `Finding.violation(...)`
9-arg factory (used by structural checkers
`UncheckedReturnChecker`, `InsecureCommunicationChecker`,
`CatchGenericExceptionChecker`) still hardcoded
`Path.of("unknown")`, AND a separate race-condition bug
(`currentFunction` shared across worker threads) caused the
`plumb.resource.leak` rule's `summarizeFunction` emits to lose their
file-path resolution.

---

## Root cause analysis (2 bugs)

### Bug 1: `Finding.violation(...)` hardcodes `"unknown"` (same Lesson 1138 as R777)

`org.plumb.reporter.Finding.violation(...)` (the 9-arg factory) wrote
`Path.of("unknown")` for the file field. Pre-R777, the same factory
in `Finding.dfa(...)` did the same; R777 changed `dfa` to preserve
empty. R778a applies the same fix to `violation`.

```java
// Pre-R778a: hardcoded "unknown" short-circuited the
// FindingReporter.report() enrichment gate (which checks
// ff.filePath().isEmpty()).
return new Finding(Path.of("unknown"), ...);

// R778a — preserve empty filePath so the reporter
// can enrich via sourcePathForEnclosingClass().
return new Finding(Path.of(""), ...);
```

### Bug 2: `setCurrentFunction` cleared to null BEFORE summary-time emit

`ResourceLeakChecker` (a `PathSensitiveCheckerBase` subclass) emits
its "not closed on every path" finding from `summarizeFunction(...)`,
not from `checkInstruction(...)`. The engine calls
`analyseOneFunction(ctx, fn)` first (which sets `currentFunction(fn)`
in a `try` block and clears it to `null` in a `finally` block) and
only THEN calls `summariseOneFunction(ctx, fn)`. The summary-time
emit thus runs with `currentFunction == null`, so the reporter
cannot resolve the file path.

Pre-R778a: 786 of the 3868 unknown-file findings came from
`ResourceLeakChecker.summarizeFunction`. Even with Bug 1 fixed,
those 786 stayed unknown until this fix.

```java
// DfaEngine.summariseOneFunction now sets the current
// function on the per-thread slot so emit-during-summary
// findings can be enriched. analyseOneFunction's finally
// block has already cleared the slot by the time we get
// here, so we re-set it.
ctx.setCurrentFunction(fn);
for (DfaChecker c : checkers) {
    ...
    s = c.summarizeFunction(ctx, fn);  // may emit
    ...
}
```

### Bonus fix: `currentFunction` now ThreadLocal

Pre-R778a: `currentFunction` was a plain reference field on
`AnalysisContext`. The parallel worklist's worker pool
(`Executors.newFixedThreadPool(threads, ...)`) shares the same
context across all workers; a worker's `setCurrentFunction(fn)`
write would race with another worker's `report()` read. R778a
changes the field to a `ThreadLocal<TacFunction>` so each
worker thread sees its own current function. The
`FindingReporter.report()` synchronized block didn't prevent
this race; the ThreadLocal does.

This fix is forward-looking — the CWE190.yaml `cgStrategy: global`
scan in this run still uses the sequential main-thread path (so
the race didn't actually fire in the r777/r778 E2E), but a
future `maxParallelism: 4` worker-pool scan would have hit it.

### Bonus fix: `FindingReporter.report()` last-resort fallback

When the `enclosingClass` lookup fails (e.g. synthesized
functions whose enclosing class wasn't registered in the path
map), the reporter now falls back to
`ctx.program().sourcePath()`. For single-file scans this is the
.java file; for the merged cross-TU scan the synthetic
`MergedProgram.asProgram()` has a null source path so the
fallback resolves to nothing and the finding keeps the empty
filePath — that case is unchanged.

---

## Code changes (4 files)

| File | Change |
|------|--------|
| `plumb-reporter/src/main/java/org/plumb/reporter/Finding.java` | `Finding.violation(...)` factory: `Path.of("unknown")` → `Path.of("")` (R778a 9-arg legacy factory). |
| `plumb-dfa/src/main/java/org/plumb/dfa/engine/AnalysisContext.java` | `currentFunction` field type: `TacFunction` → `ThreadLocal<TacFunction>`. `setCurrentFunction(...)` writes to `currentFunction.set(fn)`. `currentFunction()` returns `currentFunction.get()`. |
| `plumb-dfa/src/main/java/org/plumb/dfa/engine/DfaEngine.java` | `summariseOneFunction(ctx, fn)`: re-set `ctx.setCurrentFunction(fn)` BEFORE iterating checkers (covers summary-time emits). |
| `plumb-dfa/src/main/java/org/plumb/dfa/engine/FindingReporter.java` | `report(...)`: add last-resort fallback to `ctx.program().sourcePath()` when `enclosingClass` lookup fails. |

### Companion fixes (compile-only, no behavior change)

| File | Change |
|------|--------|
| `plumb-ai/src/main/java/org/plumb/ai/tools/GetDataflowTraceWithFactsTool.java` | `List<String> steps = f.dataflowSteps()` → `List<FlowStep> steps = f.dataflowSteps()`. R776 fallout: plumb-ai was using the legacy string step API; R776 changed the canonical type to `List<FlowStep>`. |
| `plumb-ai/src/test/java/org/plumb/ai/tools/CrossFunctionToolsTest.java` | Same: use `FlowStep.of(role, expr)` instead of `List.of("source | ... | ...")`. |

---

## Verification

### Targeted unit suite (78/78 pass — unchanged from R776/R777)

```
BoundaryConstraintCheckerTest + RangeTest + R774InfiniteLoopCheckerTest +
CompareClassesByNameCheckerTest + R215MirrorCheckersTest +
TaintedCheckerBaseTest + R218aZ3BridgeWireTest +
R222bIntUnderflowCheckerTest + IntUnderflowCheckerTest +
IntOverflowCheckerTest + DivByZeroCheckerTest + ArrayIndexCheckerTest +
ResourceExhaustionCheckerTest

Tests run: 78, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### E2E Juliet 5-CWE scan (R778a)

| CWE | R777 unknown count | R778 unknown count | R778 total findings |
|-----|-------------------:|-------------------:|--------------------:|
| 129 | 863 (eval-skipped: 2831 / 4120) | **0** | 36633 |
| 190 | 1173 | **0** | 54615 |
| 191 | 1157 | **0** | 43315 |
| 369 | 659 | **0** | 25078 |
| 835 | 16 | **0** | 22 |
| **Total** | **3868** | **0** | **159663** |

**R778 is the second measurement-unblock in this series** (R777 was
the first). Same Lesson 1138 / 1140 family. 3868 findings that R777
surfaced as `file="unknown"` are now resolved to the actual Juliet
.java file path. R778 also did not change the precision number per
CWE (R776/R777's numbers stand):

| CWE | R776/R777 prec | R778 prec |
|-----|---------------:|----------:|
| 129 | 0.4073 | 0.4073 |
| 190 | 0.4214 | 0.4214 |
| 191 | 0.5091 | 0.5091 |
| 369 | 0.2817 | 0.2817 |
| 835 | 1.0000 | 1.0000 |

This confirms R778 is purely an **enrichment fix** — no checker
behavior changed, the same TP/FP findings now have valid paths and
go through the same eval pipeline.

### NDJSON sample (R778 CWE190 line, after fix)

```json
{
  "file": "D:\\work\\tmp\\Juliet_Java\\src\\testcases\\CWE190_Integer_Overflow\\s01\\CWE190_Integer_Overflow__int_connect_tcp_add_01.java",
  "line": 104, "column": -1, "severity": "high",
  "rule_id": "plumb.unchecked-return", "cwe": ["CWE-252"],
  "message": "Return value of java.net.Socket.close/0 is discarded  ...",
  "dataflow_steps": [
    "violation | D:\\...\\CWE190_..._add_01.java:104 | unchecked-return: java.net.Socket.close/0"
  ],
  "sink_function": "bad():void"
}
```

`file` and `dataflow_steps[0].file` now carry the same Juliet path.

---

## Decisions

### Why 4 files (3 engine + 1 reporter) instead of 1 broad fix

The bugs are 3 distinct shapes:
- `Finding.violation(...)` factory (R776-era fallout)
- `setCurrentFunction` race (architectural, pre-R776)
- `summariseOneFunction` emit timing (R717 worklist, pre-R776)

Each one needs a different shape of fix. A single "add a
`withFilePathFromFunction` everywhere" would have required touching
every checker (33+ files) instead of 4. The 3 engine + 1 reporter
shape preserves the R776 migration's "engine-side enrichment"
contract.

### Why ThreadLocal (not volatile or per-Finding capture)

Three options for the `currentFunction` race:
1. `volatile` — fixes memory visibility but not the worker-overwrite
   race (Worker A sets fnA, Worker B sets fnB before Worker A
   reports, Worker A reads fnB).
2. ThreadLocal — each worker thread sees its own. Cheap (one
   field per thread), no API change.
3. Per-Finding capture — pass `fn` to `report(finding, fn)`. 47+
   callers to update, breaks the `report(Finding)` API contract.

ThreadLocal wins on cost. The downside is that
`FindingReporter.report()` callers from outside the engine's
worker pool (e.g. test code) need to call
`ctx.setCurrentFunction(fn)` before `report()`. The R776
contract already has test-side helpers for this; no new
test code was needed for the r778 change.

### Why a fallback to `ctx.program().sourcePath()`

For the cgStrategy=global merged cross-TU scan,
`MergedProgram.asProgram()` returns a stub with `sourcePath() ==
null`. The fallback therefore does nothing for the merged path.
The intent is to handle the single-file / single-class case
where `setCurrentFunction(fn)` resolves to a function whose
`enclosingClass` wasn't registered in the path map. The fallback
gives one more chance to populate filePath before the finding
ends up in NDJSON with empty file.

### Why not fix `ResourceLeakChecker.summarizeFunction` directly

The "set currentFunction in summariseOneFunction" change in
DfaEngine fixes the root cause (engine's state machine is
wrong) instead of the symptom (one checker emits from summary).
`ResourceLeakChecker` is the only checker that emits from
`summarizeFunction` today, but other checkers might do so in
the future; the engine-level fix is forward-looking.

---

## Lesson 1139 (R778a)

### 1139 (engine state machine outlasts one emit phase)
The engine runs `analyseOneFunction(fn)` (sets+clears
`currentFunction`) followed by `summariseOneFunction(fn)`. A
checker that emits a finding from `summarizeFunction` sees
`currentFunction == null`. **Lesson**: when an engine
publishes per-task state on a context, the lifetime of the
publish must cover every phase that can emit. The
`summariseOneFunction` fix preserves the publish across the
summary phase, matching the analyse phase. Future
`{function, instruction, summary, finish}-phase` engines
should explicitly enumerate which phases set/clear the
publish slot.

### 1140 (R778a bonus — `Finding.violation` factory Lesson 1138 sibling)
The Lesson 1138 fix in R777 only addressed `Finding.dfa(...)`.
The legacy 9-arg `Finding.violation(...)` factory still
hardcoded `Path.of("unknown")`. **Lesson**: when fixing a
sentinel-value bug, audit every factory that produces the
same shape of object. Two factories for the same type
(`Finding.dfa` + `Finding.violation`) means two places to
patch; one-patch audits miss the other.

### 1141 (R778a bonus — ThreadLocal fixes data races invisible to synchronized blocks)
The `FindingReporter.report()` method is `synchronized`, but
`setCurrentFunction` was a plain field write. The
synchronized report() block serializes the reports, but
the field race happens at the set/clear site, OUTSIDE
the synchronized block. **Lesson**: when a `synchronized`
method depends on state that other threads write WITHOUT
holding the same lock, the synchronization is incomplete.
The fix is to make the shared state not shared (ThreadLocal)
or to extend the lock coverage to all writers (which would
be expensive here because `setCurrentFunction` is on the
hot path inside `analyseOneFunction`'s per-instruction loop).

---

## Files (R778)

- `plumb-reporter/src/main/java/org/plumb/reporter/Finding.java` (1 line in violation factory: `Path.of("unknown")` → `Path.of("")`)
- `plumb-dfa/src/main/java/org/plumb/dfa/engine/AnalysisContext.java` (1 line: field type `TacFunction` → `ThreadLocal<TacFunction>`; 2 lines: `setCurrentFunction` and `currentFunction` use `.set()` / `.get()`)
- `plumb-dfa/src/main/java/org/plumb/dfa/engine/DfaEngine.java` (1 line added: `ctx.setCurrentFunction(fn)` in `summariseOneFunction`)
- `plumb-dfa/src/main/java/org/plumb/dfa/engine/FindingReporter.java` (1 block added: `ctx.program().sourcePath()` last-resort fallback)
- `plumb-ai/src/main/java/org/plumb/ai/tools/GetDataflowTraceWithFactsTool.java` (R776 fallout: `List<String>` → `List<FlowStep>`)
- `plumb-ai/src/test/java/org/plumb/ai/tools/CrossFunctionToolsTest.java` (same R776 fallout fix)
- `juliet-coverage/reports/r778/SUMMARY.md` (this file)
- `D:\tmp\plumb_test\scan\r778\CWE{129,190,191,369,835}.ndjson` (5 NDJSON files, 159663 findings, 0 unknown-file)
- `D:\tmp\plumb_test\eval\r778\global_*.csv` (eval output, identical prec to R777)

---

## Cross-cutting principle (R776 + R777 + R778)

Three measurement-unblock R-Rounds in a row (R776, R777, R778),
each resolving a different sentinel-value / lookup bug:

1. **R776**: `Finding` + `FlowStep` live in `plumb-reporter`. The
   parallel DFA engine had a parallel `Finding` type that was
   deleted; all 80 DFA main files + 145 DFA test files migrated.
2. **R777**: `Finding.dfa(...)` factory preserves `""` instead of
   writing `Path.of("unknown")`, so the reporter's
   `isEmpty()`-gated enrichment fires.
3. **R778**: `Finding.violation(...)` factory also preserves `""`
   (R776 fallout), `currentFunction` becomes ThreadLocal (fixes
   worker-pool race invisible to `synchronized` report), and
   `summariseOneFunction` re-publishes `currentFunction` so
   summary-time emits can resolve the file path.

The pattern:
- **Lesson 1132 (delete parallel types)** — R776
- **Lesson 1138 (sentinel-value write disables downstream gate)** — R777
- **Lesson 1139 (engine state machine outlasts one emit phase)** — R778
- **Lesson 1140 (audit every factory that produces the same shape)** — R778
- **Lesson 1141 (synchronized block doesn't fix races outside the lock)** — R778

Future factory audits: `grep -n "Path.of" plumb-reporter/src/main/java/org/plumb/reporter/Finding.java`
to find any remaining `Path.of("...")` literal in a factory.
Future emit-phase audits: `grep -n "ctx.reporter().report" plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintedCheckerBase.java`
to find emit sites and verify they run during the analyse phase
(or that the engine state machine publishes `currentFunction`
during the relevant phase).
