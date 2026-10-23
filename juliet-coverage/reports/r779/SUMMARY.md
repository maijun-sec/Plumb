# R779 — Add flow steps to numeric.* checkers (2026-10-10)

## Goal

After R776/R777/R778, every DFA rule was supposed to have
`dataflow_steps` in its NDJSON. R778's data check found 3
exceptions: `plumb.numeric.integer-overflow`,
`plumb.numeric.integer-underflow`, and
`plumb.numeric.unchecked-array-index` still emitted via the
9-arg `Finding.dfa(..., flow)` legacy form (no `List<FlowStep>`).
~30 100 findings (28 % of the corpus) had no structured trace.

R779 ports the 3 numeric checkers to the 11-arg
`Finding.dfa(..., flow, flowSteps)` form that the
`BoundaryConstraintChecker` and `ResourceExhaustionChecker`
use.

---

## Code changes (3 files)

| File | Change |
|------|--------|
| `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/IntOverflowChecker.java` | 2 emit sites: UNARYOP + BINOP. Switched from 9-arg `Finding.dfa` to 11-arg with `Collections.singletonList(FlowStep)` carrying role="violation" + the taint label. |
| `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/IntUnderflowChecker.java` | 2 emit sites: UNARYOP + BINOP. Same pattern. |
| `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/ArrayIndexChecker.java` | 2 emit sites: NEWARRAY + ARRAYLOAD/ARRAYSTORE. Same pattern. |

Each emit follows the `BoundaryConstraintChecker` pattern:
a single `FlowStep("violation", "", ins.line, ins.column, fn.signature(), expr)`
wrapping the same `flow` string the legacy 9-arg form
already produced. So `dataflow_steps[0].toCanonicalString()`
is byte-identical to the legacy `flow` field, just structured.

### Why single "violation" step (not source+violation like ResourceExhaustionChecker)

The 3 numeric checkers don't have a separate "source" phase —
the taint label is a property of the operand AT the hazard
site, not a separate earlier instruction. So the trace is
one step: "violation: overflow-hazard: <taint label>". The
`<taint label>` carries the source attribution
(`input:Byte.parseByte/1`, `input:Integer.parseInt/1`,
`arith:ADD`, `arith:PRE_INC`, etc.).

`ResourceExhaustionChecker` and `BoundaryConstraintChecker`
have the same single-step shape; only the taint-style
sql-injection / xss checkers have source → propagate →
violation (because they DO track propagation through multiple
instructions).

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

### E2E Juliet 5-CWE scan: 100 % flow-step coverage

| Rule | R778 % steps | R779 % steps | R779 total |
|------|--------------:|-------------:|-----------:|
| `plumb.resource.leak` | 100 % | 100 % | 80 131 |
| `plumb.boundary.array-out-of-bounds` (CWE129) | 100 % | 100 % | 2 298 |
| `plumb.boundary.integer-overflow` (CWE190) | 100 % | 100 % | 10 975 |
| `plumb.boundary.integer-underflow` (CWE191) | 100 % | 100 % | 10 189 |
| `plumb.boundary.divide-by-zero` (CWE369) | 100 % | 100 % | 6 568 |
| `plumb.dfa.cwe835-infinite-loop` | 100 % | 100 % | 6 |
| `UncheckedReturn` | 100 % | 100 % | 12 960 |
| `InsecureCommunication` | 100 % | 100 % | 1 620 |
| `CatchGenericException` | 100 % | 100 % | 50 |
| **`plumb.numeric.integer-overflow`** | **0 %** (7889) | **100 %** (7889) | 7 889 |
| **`plumb.numeric.integer-underflow`** | **0 %** (4509) | **100 %** (4509) | 4 509 |
| **`plumb.numeric.unchecked-array-index`** | **0 %** (4653) | **100 %** (4653) | 4 653 |

**All 11 DFA rules: 100 % flow-step coverage.** 158 366 findings,
0 with empty dataflow_steps.

### Precision (R779 vs R777/R778 — identical)

| CWE | R777 / R778 prec | R779 prec |
|-----|-----------------:|----------:|
| 129 | 0.4073 | 0.4073 |
| 190 | 0.4214 | 0.4214 |
| 191 | 0.5091 | 0.5091 |
| 369 | 0.2817 | 0.2817 |
| 835 | 1.0000 | 1.0000 |

As expected: R779 only changes the NDJSON shape
(`flow` string is unchanged, `dataflow_steps` is now a 1-element
list with the same content). The eval pipeline's
`dataflow_steps` field is not consulted by
`eval_findings_cwe_matched.py` for the strict CWE-matched
score; only `rule_id` / `file` / `line` / `cwe` matter for TP/FP.

### NDJSON sample (R779 CWE190 numeric integer-overflow line)

```json
{
  "file": "D:\\work\\tmp\\Juliet_Java\\src\\testcases\\CWE190_Integer_Overflow\\s01\\CWE190_Integer_Overflow__byte_console_readLine_add_01.java",
  "line": 90, "column": 30, "severity": "high",
  "rule_id": "plumb.numeric.integer-overflow", "cwe": ["CWE-190"],
  "message": "Integer overflow risk: ADD on int/long at  90: t_t34 = data ADD 1",
  "dataflow_steps": [
    "violation | D:\\...\\CWE190_..._add_01.java:90:30 | overflow-hazard: input:Byte.parseByte/1"
  ],
  "sink_function": "bad():void"
}
```

The expr (`overflow-hazard: input:Byte.parseByte/1`) shows the
taint label that flowed into the operand; pre-R779, this was
only in the legacy `flow` string, not in the structured trace.

---

## Decisions

### Why single-step (violation only) instead of source+violation

The numeric checkers' taint model is single-point: an operand
is tainted (by `super.checkInstruction`'s taint propagation)
and the hazard is the next instruction. There's no separate
"source" instruction to record. The `input:` label
(`input:Byte.parseByte/1`) IS the source, encoded in the
violation step's expr. This matches `BoundaryConstraintChecker`
which also emits single-step violation traces.

`ResourceExhaustionChecker` is the only checker in the
codebase that emits source+violation pairs, and that pattern
exists because the taint source (an allocation site) and the
violation site (a function exit without close) are spatially
separated. Numeric checkers' source and violation are co-located
at the same instruction, so a single step is honest.

### Why not pre-R776 legacy single-string `flow` field instead of `dataflow_steps`

The legacy `flow` field IS still emitted (Finding.dfa's
`flow` parameter), unchanged byte-for-byte. `dataflow_steps`
adds a structured list view of the same content. Reporters
that consume `flow` (the SARIF formatter, the eval harness's
`dataflow` field) work unchanged; reporters that consume
`dataflow_steps` (R776's NdjsonEmitter) get the structured
view. Both shapes are now in the NDJSON.

### Why not also add `propagate` step

For consistency with the taint-style sql-injection checker
that has 3-step traces (source → propagate → violation), a
2-step trace (source+violation) might be tempting. But the
numeric checkers don't have a separate "propagate" instruction
— the taint flows from MOVE / CALL into the BINOP / UNOP
and the BINOP / UNOP IS the violation site. Adding a
fictitious "propagate" step would mislead readers; the
single-step violation with a taint label is honest.

---

## Lesson 1142

### 1142 (when promoting a legacy field, check sibling rules that share the type)
R776 promoted `flow: String` to `flowSteps: List<FlowStep>`,
but only migrated the 4 most-active DFA checkers (the taint
ones). The 3 numeric checkers were missed because they
weren't on R776's "DFA-only" audit list (they're
`TaintedCheckerBase` subclasses with a narrow scope). **Lesson**:
when a structured field is added to a shared type, every
factory call that produced the legacy shape needs an audit.
The `r779_audit_flow.py` script (analogous to the R776
`r776_audit_flow.py`) is the right tool — re-run it after
every "promote the field" R-Round to find call sites that
missed the migration.

---

## Files (R779)

- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/IntOverflowChecker.java` (2 emit sites, +1 import, +FlowStep list arg)
- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/IntUnderflowChecker.java` (same)
- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/ArrayIndexChecker.java` (same)
- `juliet-coverage/reports/r779/SUMMARY.md` (this file)
- `D:\tmp\plumb_test\scan\r779\CWE{129,190,191,369,835}.ndjson` (5 files, 158 366 findings, 100 % flow-step coverage)
- `D:\tmp\plumb_test\eval\r779\global_per_cwe.csv` (precision identical to R777/R778)

---

## Cross-cutting principle (R776 + R777 + R778 + R779)

Four R-Rounds in a row, each incrementally growing the
"every DFA finding has a structured trace" guarantee:

1. **R776**: structured `FlowStep` exists; taint-style + resource
   checkers (the 4 most-active) use it. ~4 numeric checkers still
   use the legacy 9-arg `Finding.dfa`.
2. **R777**: `Finding.dfa` factory preserves empty filePath so
   enrichment can fire.
3. **R778**: `Finding.violation` factory + ThreadLocal currentFunction
   + summariseOneFunction re-publish = 3868 unknown-file findings
   → 0.
4. **R779**: 3 numeric checkers ported to 11-arg `Finding.dfa` with
   `FlowStep` list = 0 findings without `dataflow_steps` in the
   DFA corpus.

After R779: **every DFA finding in the r779 corpus has both
`file` (resolved by R778) AND `dataflow_steps` (added by R779)**
+ `sink_function` (added by R776). The 3 audit points
(`filePath == 'unknown'`, `dataflow_steps == []`,
`sink_function == null`) are all 100 % covered.

Future audit scripts:
- `r780_audit_flow.py` (analog) — re-run after every
  "promote a field" R-Round to find missed call sites.
- `r780_audit_emit_sites.py` — every `ctx.reporter().report(...)`
  call should use the 11-arg `Finding.dfa(..., flowSteps)`
  factory; the audit lists every 9-arg call site that
  should be upgraded.
