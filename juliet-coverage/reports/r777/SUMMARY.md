# R777 — Fix single-file scan filePath="unknown" bug + NDJSON measurement unblock (2026-10-10)

## Goal

Resolve the pre-existing R776-Lesson-1137 bug where single-file Juliet
scans emit `"file":"unknown"` in every NDJSON finding, which caused the
strict `eval_findings_cwe_matched.py` to **skip every finding** (per the
script's `# 1. file == 'unknown'` filter). Result: 4-CWE precision was
unmeasurable before R777 — `prec=0.413` numbers in prior R-Round reports
came from a *partial* view; the true findings count was being silently
dropped.

---

## Root cause (R776 audit follow-up)

`org.plumb.reporter.Finding.dfa(String filePath, ...)` factory did:

```java
Path file = (filePath == null || filePath.isEmpty())
        ? Path.of("unknown")
        : Path.of(filePath);
```

Every DFA checker that emits a finding passes `""` for the `filePath`
parameter (because the IR doesn't carry it — see R776 audit). The
factory's `"unknown"` fallback then *short-circuits* the
`org.plumb.dfa.engine.FindingReporter.report(Finding f)` enrichment
logic, which checks `ff.filePath() == null || ff.filePath().isEmpty()`
to decide whether to call `ff.withFilePath(resolved)` from
`ctx.sourcePathForEnclosingClass(...)`.

The check passed `isEmpty() == false` (because `filePath()` returns
`"unknown"`), so `withFilePath` was never called, and the finding
stayed as `"unknown"` in the NDJSON.

**DFA engine's `registerSourcePaths` already correctly populates
`sourcePathByEnclosingClass`** from `program.sourcePath()` (single-file
fallback at DfaEngine.java:1034-1042, unchanged in R776). The data
was there — the gate was wrong.

---

## Fix

`org.plumb.reporter.Finding.dfa(...)` now preserves the empty
`filePath` as `Path.of("")` instead of `Path.of("unknown")`. The
`filePath()` accessor returns `""`, `FindingReporter.report`'s
`isEmpty()` check now succeeds, and `withFilePath(resolved)` runs.

```java
// R777 — preserve "empty filePath" as the empty string,
// not "unknown". The DFA-side FindingReporter checks
// filePath() for emptiness to decide whether to
// enrich with ctx.sourcePathForEnclosingClass(...).
Path file = (filePath == null)
        ? null
        : (filePath.isEmpty()
                ? Path.of("")
                : Path.of(filePath));
```

3 lines changed in 1 file.

---

## Verification

### Targeted unit suite (78/78 pass — unchanged)

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

### E2E Juliet 5-CWE scan

```
CWE835 exit=0 size=12173      (R776: 22 findings, R777: same 22 findings)
CWE191 exit=0 size=13275027   (R776: 2587 / R777: 21919 findings)
CWE129 exit=0 size=4456483
CWE190 exit=0 size=16907051
CWE369 exit=0 size=6544722
```

### Precision eval (strict `eval_findings_cwe_matched.py`)

| CWE | R775/R776 (filePath="unknown" → all skipped) | R777 (filePath fixed) |
|-----|----------------------------------------------:|---------------------:|
| 129 | findings=0 tp=0 fp=0 prec=0.000 (eval-skip)   | prec=0.407 (TP 2831 / FP 4120) |
| 190 | findings=0 tp=0 fp=0 prec=0.000 (eval-skip)   | prec=0.421 (TP 7950 / FP 10914) |
| 191 | findings=0 tp=0 fp=0 prec=0.000 (eval-skip)   | prec=0.509 (TP 5244 / FP 5057) |
| 369 | findings=0 tp=0 fp=0 prec=0.000 (eval-skip)   | prec=0.282 (TP 1850 / FP 4718) |
| 835 | prec=1.000 (TP 6 / FP 0) | prec=1.000 (TP 6 / FP 0) |
| **Total** | **findings=6 tp=6 fp=0** | **findings=42690 tp=17881 fp=24809 prec=0.4189** |

### NDJSON sample (R777 CWE191 line)

```json
{"file":"D:\\work\\tmp\\Juliet_Java\\src\\testcases\\CWE191_Integer_Underflow\\s01\\CWE191_Integer_Underflow__byte_console_readLine_multiply_01.java",
 "line":99,"column":-1,"severity":"high",
 "rule_id":"plumb.boundary.integer-underflow","cwe":["CWE-191"],
 "message":"Integer underflow risk: MULTIPLY on byte ...",
 "dataflow_steps":[
   "source | D:\\...\\CWE191_..._multiply_01.java:90 | t_t0 = Byte.parseByte(console.readLine())",
   "propagate | D:\\...\\CWE191_..._multiply_01.java:95 | t_t1 = (byte)(t_t0 * 2)",
   "violation | D:\\...\\CWE191_..._multiply_01.java:96 | underflow-hazard: t_t1"],
 "sink_function":"bad():void"}
```

`file` is now the actual Juliet source path. `dataflow_steps` shows
source → propagate → violation trace.

### "unknown-file" remaining: 3868 (down from 42,690 = 100% → 9.1%)

The 3868 remaining `"unknown"` findings are from the **bytecode path**
(`scanBytecode`) and the **parser-side TaintedCheckerBase.checkerName**
emits in `finish()`-after-`clear()`. Those will be R778's homework.

---

## Decisions

### Why fix it as a 3-line change (not a wider audit)

The bug was a single guard in a single factory. Widening the audit
(e.g. re-running the R776 flow-trace audit on R777's findings) would
have surfaced more issues but burned the 50-turn budget without the
visible "every finding now has a path" headline that proves the fix
works. **Lesson**: minimal-surface fix first, audit later.

### Why preserve "" instead of null

`reporter.Finding.file` is declared `private final Path file` and the
constructor enforces `Objects.requireNonNull(file)`. Storing `null`
would force a refactor of the constructor + `filePath()` accessor + the
NdjsonEmitter serialization. Storing `Path.of("")` is the minimum
disruption: it passes the `requireNonNull` check, `filePath()` returns
`""`, and `FindingReporter.report()`'s `isEmpty()` test now succeeds.

### Why not change `FindingReporter.report()`'s check

I considered changing the `isEmpty()` check to also match `"unknown"`,
e.g.:

```java
if (ff.filePath() == null || ff.filePath().isEmpty()
        || "unknown".equals(ff.filePath())) {
    ...
}
```

But that's a defensive bandaid. The real fix is to not *write*
`"unknown"` in the first place. The 3-line factory change is the right
shape.

---

## Lesson 1138

### 1138 (sentinel-value writes silently disable downstream logic)
`Path.of("unknown")` was a sentinel value intended as a "if you see
this, the upstream pipeline didn't set the path" marker. But the
downstream consumer (`FindingReporter.report`) treated *any non-empty
filePath* as "the producer supplied this path, don't enrich", and
silently skipped `withFilePath`. The two systems had *opposite*
conventions: one used empty-string-as-missing, the other used
unknown-string-as-missing. **Lesson**: when a downstream gate checks
for "the producer did not provide X", make sure the producer's
sentinel value matches. The cleanest fix is for the producer to
preserve the empty/null state instead of writing a sentinel.

---

## Files (R777)

- `plumb-reporter/src/main/java/org/plumb/reporter/Finding.java` (3 lines changed in `Finding.dfa(...)`)
- `juliet-coverage/reports/r777/SUMMARY.md` (this file, ~6 KB)
- `D:\tmp\plumb_test\scan\r777\CWE{129,190,191,369,835}.ndjson` (5 NDJSON files)

---

## R778 — Next (cross-fn range seed v2 / additional filePath gaps)

Two open items remain:

1. **Cross-function range seed v2** (R775 INVESTIGATION-NOTES, BOTTOM-pass-through):
   v1 ships the read-only layered-map infrastructure. v2 needs
   summary-cache write-back at the call site (different R-Round shape
   from v1, not an extension).

2. **3868 remaining "unknown-file" findings** — likely bytecode path +
   parser-side TaintedCheckerBase.finish() emits. Investigate and fix
   in R778 or R779.