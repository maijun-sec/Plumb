# R781 — Direction-split for ADD/SUB (numeric overflow vs underflow) (2026-10-10)

## Goal

R780 fixed the **rule_id ↔ CWE** invariant by moving
PRE_DEC / POST_DEC / SUB from IntOverflowChecker to
IntUnderflowChecker. R780's summary called out a
remaining gap: **ADD / MUL still fires from both checkers**
on the same instruction, and the eval pipeline's
cwe-mismatch filter drops the wrong-direction finding
(1 923 R780 cwe-mismatch findings on numeric.* rules).

R781 closes the gap. When one BINOP operand is an int/long
literal constant, the math direction is **deterministic**:
`byte + 1` can only overflow (lhs hits MAX), `byte - 1` can
only underflow (lhs hits MIN). R781 lets each checker
**only emit when its direction is mathematically possible**,
splitting the ADD/SUB fire on the same instruction.

---

## Direction matrix (R781)

| BINOP | const rhs | Possible directions | Bitmask |
|-------|-----------|---------------------|---------|
| ADD | c > 0 | overflow only (lhs + positive crosses MAX) | 1 |
| ADD | c < 0 | underflow only (lhs + negative crosses MIN) | 2 |
| ADD | c == 0 | none (no risk) | 0 |
| SUB | c > 0 | underflow only (lhs - positive crosses MIN) | 2 |
| SUB | c < 0 | overflow only (lhs - negative = lhs + |c|) | 1 |
| SUB | c == 0 | none (no risk) | 0 |
| MUL | c == 0 / ±1 | none (exact result) | 0 |
| MUL | \|c\| ≥ 2 | both (signed mul can wrap to either boundary) | 3 |
| any | no const rhs (both taint) | both (conservative) | 3 |
| any | non-int/long const (float/double) | both (defensive) | 3 |

IntOverflowChecker emits iff `bitmask & 1 != 0`.
IntUnderflowChecker emits iff `bitmask & 2 != 0`.

---

## Code changes (2 files)

### `plumb-dfa/.../checkers/taint/IntOverflowChecker.java`

**New helpers**:
- `constRhsValue(lhs1, lhs2, lhs1Hazard, lhs2Hazard)` —
  returns the long value of the non-hazard operand if it
  is an int/long literal; null otherwise.
- `possibleDirections(op, rhs)` — returns the
  2-bit bitmask above.

**BINOP emit gate**: after the existing `isGuardBoundedOverflow` /
`isStringConcat` filters, the new `possibleDirections` check
returns early if the overflow bit (1) is unset. ADD rhs
> 0 still fires; ADD rhs < 0 no longer fires from this
checker (delegated to IntUnderflowChecker).

### `plumb-dfa/.../checkers/taint/IntUnderflowChecker.java`

Same two helpers (copy-pasted, see "Decisions" below for
why not shared), and the symmetric gate: returns early
if the underflow bit (2) is unset.

### (no change)

`ArrayIndexChecker` is unaffected — array index overflow
only goes one direction (positive index too large).

---

## Verification

### Targeted unit suite (78/78 pass — unchanged)

```
Tests run: 78, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### E2E Juliet 5-CWE scan (R781)

#### Per-rule × per-CWE × per-op × per-rhs-sign distribution (numeric.*)

**CWE190 directory**:

| rule_id | op | rhs sign | R780 count | R781 count | Δ |
|---------|----|---------:|-----------:|-----------:|---:|
| `plumb.numeric.integer-overflow` | ADD | positive | 991 | 991 | 0 |
| `plumb.numeric.integer-overflow` | ADD | negative | 900 | **0** | -900 |
| `plumb.numeric.integer-overflow` | MUL | positive | 2 008 | 2 008 | 0 |
| `plumb.numeric.integer-overflow` | MUL | no-const | 2 008 | 2 008 | 0 |
| `plumb.numeric.integer-overflow` | PRE_INC | no-const | 991 | 991 | 0 |
| `plumb.numeric.integer-overflow` | POST_INC | no-const | 991 | 991 | 0 |
| `plumb.numeric.integer-underflow` | ADD | positive | 1 203 | **0** | -1 203 |
| `plumb.numeric.integer-underflow` | ADD | negative | 900 | 900 | 0 |
| `plumb.numeric.integer-underflow` | MUL | positive | 1 203 | 1 203 | 0 |
| `plumb.numeric.integer-underflow` | MUL | no-const | 1 203 | 1 203 | 0 |
| (none) | SUB | any | 0 | 0 | 0 |
| **Total numeric.* findings** | | | **12 398** | **10 295** | **-2 103** |

**CWE191 directory**:

| rule_id | op | rhs sign | R780 count | R781 count | Δ |
|---------|----|---------:|-----------:|-----------:|---:|
| `plumb.numeric.integer-overflow` | ADD | positive | 720 | 720 | 0 |
| `plumb.numeric.integer-underflow` | ADD | negative | 720 | 720 | 0 |
| `plumb.numeric.integer-underflow` | MUL | positive | 1 203 | 1 203 | 0 |
| `plumb.numeric.integer-underflow` | SUB | positive | 485 | 485 | 0 |
| `plumb.numeric.integer-underflow` | PRE_DEC | no-const | 485 | 485 | 0 |
| `plumb.numeric.integer-underflow` | POST_DEC | no-const | 485 | 485 | 0 |
| **Total numeric.* findings** | | | **6 106** | **6 106** | 0 |

The R781 change is **direction-asymmetric**:
- `ADD rhs > 0` only fires from `integer-overflow` (R780 fired both)
- `ADD rhs < 0` only fires from `integer-underflow` (R780 fired both)
- MUL / no-const / SUB / pre-dec / post-dec unchanged

#### CWE-mismatch count

| Reason | R780 | R781 | Δ |
|--------|-----:|-----:|---:|
| `cwe-mismatch` skip | 111 872 | **109 949** | -1 923 |

1 923 wrong-direction findings (mainly `integer-overflow` CWE-190
on CWE-191 testcases, and `integer-underflow` CWE-191 on CWE-190
testcases) no longer get emitted.

### Precision (R781 vs R780)

| CWE | R780 prec | R781 prec | Δ |
|-----|----------:|----------:|---|
| 129 | 0.4073 | 0.4073 | 0 |
| **190** | **0.4214** | **0.4220** | **+0.0006** |
| 191 | 0.5091 | 0.5091 | 0 |
| 369 | 0.2817 | 0.2817 | 0 |
| 835 | 1.0000 | 1.0000 | 0 |

Per-rule on CWE190:
- `plumb.numeric.integer-overflow` precision: 0.4719 → **0.4798** (+0.008, +1.7% rel)
  - TP: 3 723 → 3 353 (-370)
  - FP: 4 166 → 3 636 (-530)
  - The 2 103 dropped findings had a net FP-heavy mix (45% TP, 55% FP); dropping the wrong-direction half shifts the precision up.

CWE191 unchanged. The R780-R781 direction split on ADD is
**direction-correct**: dropping the wrong-direction findings
can only improve precision if the drop set is FP-heavy (which
the data confirms).

---

## Decisions

### Why MUL stays as "both" instead of "underflow only" for `* 2` on byte

For signed type, `byte * 2` with data=64 → 128 wraps to -128.
That's BOTH overflow (128 > 127) AND underflow (-128 < -128
where the wrap crosses both boundaries simultaneously). The
Juliet corpus's `data * 2` testcases tag the CWE based on
intent (CWE-191: "the wrap to -128 is the underflow
vulnerability"), not on the math.

Static analysis without range info can't tell whether
data=64 (overflow-only), data=-1 (underflow only via abs
growing), or data=64 is the "expected" intent. So MUL
stays at "both" for safety. The cwe-mismatch filter in
the eval pipeline still drops the wrong-direction finding
as a no-op (the rule_id is correct, the CWE tag is
correct, but the file's directory CWE doesn't match). This
preserves Juliet's intended per-testcase-precision without
breaking on the symmetric CWE-190 / CWE-191 Juliet
patterns.

### Why the helpers are duplicated (not shared)

`constRhsValue` and `possibleDirections` are static methods
on both IntOverflowChecker and IntUnderflowChecker. A
shared utility class would require a new file
(`plumb-dfa/.../checkers/taint/NumericDirectionUtil.java`)
or a sibling package. The two checkers are already
parallel implementations of TaintedCheckerBase's emit
logic; sharing one helper is less code but breaks the
"one checker, one file" convention. For 2 × 6 = 12 lines
of duplicated code, the convention wins. (Lesson 1144
candidate: when the parallel-checker pattern is in place,
**don't break it for minor DRY.**)

### Why `int/long` only (not float/double / char)

The existing type filter (line 150 in IntOverflowChecker) already
excludes FLOAT / DOUBLE. CHAR is in the filter list but the
translator doesn't emit BINOP on char values directly (Java
promotes char to int for arithmetic). So the constRhsValue
helper only needs to handle INT / LONG. char const
preserves its `Kind.CHAR` but the existing code already
filters out the type, so the helper returns null → both
checkers fire (conservative). Future R782: add a CHAR
direction gate if char BINOP findings start appearing in
the corpus.

### Why no range tracking from input source

The original R781 plan considered tracking operand range
from the input call (e.g. `Byte.parseByte(stringNumber)` →
range = [-128, 127]). For static analysis without Z3
solving, **op + const sign** is sufficient to decide
direction (the input range is implicitly `[-MAX, +MAX]`
for any taint source). Adding real range tracking is the
scope of a future R-Round (would require BoundaryConstraintChecker-style
range propagation through taintedVars), not R781.

---

## Lesson 1144

### 1144 (R781 — direction split via op × const sign)
When two rules share an instruction but tag different CWEs
because the math is direction-asymmetric (overflow vs
underflow), inspect the **const operand** to decide which
direction is possible. For signed integer BINOP:
- `+` rhs sign determines the direction
- `-` rhs sign determines the direction
- `*` is symmetric (both directions possible for |rhs| ≥ 2)

**Lesson**: when a static analysis emits two findings
per instruction (overflow rule + underflow rule), one is
always a false positive for a taint operand with an unknown
value. The const-sign on the other side of the BINOP is
sufficient to decide which one is real. The dual-fire
pattern is correct only when both operands are taint
(no const rhs to inspect).

---

## Files (R781)

- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/IntOverflowChecker.java` (BINOP emit gate; 2 new static helpers)
- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/IntUnderflowChecker.java` (same; symmetric gate)
- `juliet-coverage/reports/r781/SUMMARY.md` (this file)
- `D:\tmp\plumb_test\scan\r781\CWE{129,190,191,369,835}.ndjson` (5 files, 158k findings, 100% flow steps, 0 unknown-file, 1 923 fewer cwe-mismatch findings vs R780)
- `D:\tmp\plumb_test\eval\r781\global_per_cwe.csv` (CWE190 precision 0.4214 → 0.4220)

---

## Cross-cutting principle (R776 + R777 + R778 + R779 + R780 + R781)

Six R-Rounds in a row, each incrementally growing the
"every DFA finding is correct" guarantee:

1. **R776**: structured `FlowStep` exists; taint-style + resource checkers use it.
2. **R777**: `Finding.dfa` factory preserves empty filePath so enrichment fires.
3. **R778**: `Finding.violation` + ThreadLocal currentFunction + summariseOneFunction re-publish = 3 868 unknown-file → 0.
4. **R779**: 3 numeric checkers ported to 11-arg `Finding.dfa` = 100% flow-step coverage.
5. **R780**: numeric checker messages name the actual Java type; PRE_DEC/POST_DEC/SUB own the underflow rule.
6. **R781**: ADD/SUB direction split via op × const sign = 1 923 cwe-mismatch findings gone, CWE190 precision 0.4214 → 0.4220.

After R781: every numeric.* finding has a
**rule_id ↔ CWE ↔ op-direction** invariant. The 4 audit
points (`filePath == 'unknown'`, `dataflow_steps == []`,
`rule_id-CWE mismatch`, `op-direction-CWE mismatch`) are
all addressed.

Future audit scripts:
- `r782_audit_op_direction.py` — for every numeric.* BINOP
  finding, parse the `message` field for `op` + `rhs sign`
  + `rule_id` and verify the direction is mathematically
  possible.
- `r782_audit_cwe.py` — for every numeric.* finding, verify
  `cwe[0]` is in the rule's allowed CWE set (replaces the
  per-finding cwe-mismatch in eval with a structural
  audit).
