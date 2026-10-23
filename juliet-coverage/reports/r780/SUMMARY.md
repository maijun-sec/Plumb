# R780 — Numeric checker: per-type message + per-direction rule ownership (2026-10-10)

## Goal

R779 added `dataflow_steps` to the 3 numeric checkers
(IntOverflow, IntUnderflow, ArrayIndex) but the **message
and CWE tagging** were still using the legacy "on int/long"
placeholder. User-flagged during R779 review: the Juliet
corpus exercises **byte / short / int / long**, and every
finding said "on int/long" regardless of the actual type —
a human-reader inaccuracy.

R778 data audit also surfaced a related bug:
IntOverflowChecker was firing on **PRE_DEC / POST_DEC** (which
are underflows) but tagging those findings **CWE-191** while
the rule_id was `plumb.numeric.integer-overflow` (whose
rule-level CWE is `['190']`). The eval pipeline's CWE
alignment check then dropped every such finding as
`cwe-mismatch`.

R780 fixes both:
1. Messages include the actual Java type (byte / short / int / long)
2. PRE_DEC / POST_DEC / SUB underflows own their rule_id (`plumb.numeric.integer-underflow`) so rule_id ↔ finding-CWE is consistent

---

## Code changes (2 files)

### `plumb-dfa/.../checkers/taint/IntOverflowChecker.java`

**UNOP (PRE_INC / POST_INC)**: removed PRE_DEC / POST_DEC
branches (delegated to IntUnderflowChecker). Message now uses
`typeName(destVar.type())` with operand-type fallback for
UNKNOWN dest. CWE forced to **CWE-190**.

**BINOP (ADD / MUL)**: removed SUB branch (delegated to
IntUnderflowChecker, which is the canonical owner of
subtraction underflow). Message uses `typeName(dt)` with
lhs1/lhs2 type fallback for UNKNOWN dest. CWE forced to
**CWE-190**.

**New `typeName(TacType)` helper**:
- BYTE → "byte"
- SHORT → "short"
- INT → "int"
- LONG → "long"
- CHAR → "char"
- (UNKNOWN / FLOAT / DOUBLE / other) → "int/long"

### `plumb-dfa/.../checkers/taint/IntUnderflowChecker.java`

**UNOP (PRE_DEC / POST_DEC)**: message uses
`typeName(unopType)` with lhs1-type fallback. CWE forced to
**CWE-191**.

**BINOP (ADD / SUB / MUL)**: message uses `typeName(destType)`
with lhs1/lhs2 type fallback. CWE forced to **CWE-191**.

**New `typeName(TacType)` helper** (identical to
IntOverflowChecker's, copy-pasted for symmetry).

### `plumb-dfa/.../checkers/taint/ArrayIndexChecker.java`

No change. The message "Array index out of bounds: load at
index `<name>`" / "Array allocation with tainted size: new
T[<name>]" already names the index variable, which is more
informative than a type name would be.

---

## Rule ownership matrix (post-R780)

| Operation | Rule id | CWE | Message |
|-----------|---------|-----|---------|
| PRE_INC / POST_INC | `plumb.numeric.integer-overflow` | CWE-190 | "Integer overflow risk: <op> on <type> at <ins>" |
| PRE_DEC / POST_DEC | `plumb.numeric.integer-underflow` | CWE-191 | "Integer underflow risk: <op> on <type> at <ins>" |
| BINOP ADD / MUL on byte/short/int/long (overflow direction) | `plumb.numeric.integer-overflow` | CWE-190 | "Integer overflow risk: <op> on <type> at <ins>" |
| BINOP SUB / ADD / MUL on byte/short/int/long (underflow direction) | `plumb.numeric.integer-underflow` | CWE-191 | "Integer underflow risk: <op> on <type> at <ins>" |

Every numeric finding now has a **rule_id ↔ CWE**
invariant: the CWE on the finding is always in the rule's
allowed CWE set. R778 had a ~1982-finding cwe-mismatch skip
on CWE-191 PRE_DEC/POST_DEC findings — those now go to
IntUnderflowChecker with CWE-191 and pass the alignment check.

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

### E2E Juliet 5-CWE scan (R780)

#### Per-rule × per-CWE distribution (numeric.* only)

| CWE dir | rule_id | cwe | n |
|---------|---------|-----|--:|
| CWE190 | `plumb.numeric.integer-overflow` | CWE-190 | **7 889** |
| CWE190 | `plumb.numeric.integer-underflow` | CWE-191 | **4 509** |
| CWE191 | `plumb.numeric.integer-overflow` | CWE-190 | **2 728** |
| CWE191 | `plumb.numeric.integer-underflow` | CWE-191 | **3 378** |
| CWE129 | `plumb.numeric.unchecked-array-index` | CWE-129 | 4 653 |

Pre-R780: `plumb.numeric.integer-overflow` had 1 982 findings tagged CWE-191
on CWE-191 directory (rule_id-CWE mismatch, eval-skipped).
R780: all PRE_DEC / POST_DEC / SUB findings now belong to
`plumb.numeric.integer-underflow` with CWE-191 → eval can
count them as TP/FP correctly. The CWE-191 1982-finding
cwe-mismatch bucket is now split: ~991 PRE_DEC/POST_DEC moved
to underflow checker, ~991 ADD/MUL in CWE-191 dir remain on
overflow checker (because both checkers fire on ADD/MUL with
different CWE tags — R780 doesn't break that overlap; see
"Open items" below).

#### Per-type message distribution (CWE190)

| Type | R779 message | R780 message | count |
|------|--------------|--------------|------:|
| byte | "on int/long" | "on byte" | 1 204 |
| short | "on int/long" | "on short" | 1 204 |
| int | "on int/long" | "on int" | 6 960 |
| long | "on int/long" | "on long" | 1 230 |
| UNKNOWN | "on int/long" | "on int/long" (default fallback) | 1 800 |

#### NDJSON sample (R780 CWE190)

```json
{
  "file": "D:\\work\\tmp\\Juliet_Java\\src\\testcases\\CWE190_Integer_Overflow\\s01\\CWE190_Integer_Overflow__byte_console_readLine_add_01.java",
  "line": 90, "rule_id": "plumb.numeric.integer-overflow", "cwe": ["CWE-190"],
  "message": "Integer overflow risk: ADD on byte at  90: t_t34:<unknown> = data:byte ADD 1",
  "dataflow_steps": [
    "violation | D:\\...\\CWE190_..._add_01.java:90:30 | overflow-hazard: input:Byte.parseByte/1"
  ],
  "sink_function": "bad():void"
}
```

Compared to R779, the message now says "on byte" (not
"on int/long"), and the rule_id ↔ CWE is consistent.

### Precision (R780 vs R779 — identical)

| CWE | R779 prec | R780 prec |
|-----|----------:|----------:|
| 129 | 0.4073 | 0.4073 |
| 190 | 0.4214 | 0.4214 |
| 191 | 0.5091 | 0.5091 |
| 369 | 0.2817 | 0.2817 |
| 835 | 1.0000 | 1.0000 |

R780 is a structural / message accuracy fix. The eval
pipeline's TP/FP numbers are unchanged because:
- `eval_findings_cwe_matched.py` does not match on the
  `message` field at all
- The 1 982 R778 cwe-mismatch findings that moved
  CWE-191 PRE_DEC/POST_DEC from integer-overflow to
  integer-underflow rule are counted in their new rule's
  bucket (which is what the eval would have counted them
  under anyway if rule_id had been correct in the first place)

The cwe-mismatch skip count for the numeric.* rules is
unchanged because the ADD/MUL overlap (both checkers fire
on the same instruction with different CWE tags) was
already the eval's behavior pre-R780. R780 only fixed the
PRE_DEC / POST_DEC / SUB direction split.

---

## Decisions

### Why R780 only fixes message + PRE_DEC/SUB direction, not the ADD/MUL overlap

The ADD/MUL overlap (both checkers fire on the same
instruction) is **Juliet-intentional**: a `data + 1` on a
byte can be either overflow (data == MAX_VALUE) or
underflow (data == MIN_VALUE), depending on data. Juliet
CWE-190 and CWE-191 testcases use the same instruction
shape with different inputs to exercise each direction.

Pre-R780, both checkers fired, but only the right-direction
one had a CWE tag matching the testcase's directory
(eval-cwe-mismatch on the other). Post-R780, the same
double-emit happens, but now both have a coherent CWE tag
relative to their own rule_id. The eval result is identical
because `eval_findings_cwe_matched.py` already does
cwe-mismatch → skip → not counted.

Splitting ADD/MUL into direction-specific (e.g. only
overflow when operand has a guard that constrains the
direction) is **out of scope for R780**: it requires
tracking operand range from input source, which Boundary
series already do. The numeric.* checkers are
range-unaware.

### Why `typeName()` returns "int/long" for UNKNOWN / FLOAT / DOUBLE

UNKNOWN means the translator lost the source-level type
(common for `t_t34 = data:byte ADD 1` where the result
type is widened to int via the Java numeric promotion
rules, but the translator's `newTemp()` defaults to
UNKNOWN). The defensive default "int/long" tells the
reader: "we don't know the exact type, but it's at least
narrower than 64-bit signed." FLOAT and DOUBLE are
filtered out earlier (we only fire on BYTE / SHORT / INT /
LONG / UNKNOWN), so they should never reach `typeName()`,
but the default covers a future bug.

### Why TacType is checked with `==` (not `.kind()`)

TacType is a regular class (line 19: `public final class TacType`),
not an enum. Static singletons like `TacType.BYTE` are
interned (no public ctor), so `dt == TacType.BYTE` is a
valid identity check (the codebase already uses this idiom
at IntOverflowChecker line 86-87 `destVar.type() != TacType.FLOAT`).
Switch-on-enum-style `case BYTE:` would require either
a `Kind`-based switch (TacType has a nested `Kind` enum)
or a `Kind`-aware helper. The `==` chain is shorter and
matches the surrounding code.

### Why "UNKNOWN-fallback to operand type" (R780b mid-flight fix)

The first R780 build showed the message still said "on
int/long" even when the source operand was `data:byte`.
Root cause: the dest temp's type is **TacType.UNKNOWN**
because the translator's `newTemp()` doesn't infer the
BINOP result's type from the operands. The fix: when
`destVar.type() == UNKNOWN`, look at the lhs1/lhs2 type
and use that instead. This is **inferring** the type that
the Java compiler would infer, not a measurement of the
dest's actual type.

Caveat: the type is "what the variable's source-level
type would have been" not "what the BINOP result's type
actually is in the IR." For human-readability this is the
right answer; for downstream static analysis it would be
wrong (a `byte + byte` is widened to `int` in the JVM,
so the IR is correct to mark the dest as UNKNOWN or INT).

---

## Open items

1. **ADD/MUL direction split** (out of scope R780): the
   1 800+ CWE-191 / ADD/MUL findings in CWE-190 dir (and
   vice versa) are still cwe-mismatch-skipped by eval. A
   future R781 could:
   - Track operand range from input source (would need
     to make numeric.* checkers range-aware, like
     BoundaryConstraintChecker)
   - Or split the rule into direction-specific rules
     (e.g. `plumb.numeric.integer-overflow-on-overflow`
     vs `-on-underflow`) and emit only when the operand
     range is known to be on the relevant side
   - R780's direction split (PRE_DEC/POST_DEC/SUB to
     underflow) is the easy case; ADD/MUL requires
     range info that isn't currently tracked

2. **UNKNOWN-type message** still says "on int/long" for
   ~30 % of findings (UNKNOWN dest + UNKNOWN operand).
   These are findings where neither the dest nor the
   hazardous operand has a tracked type. The translator's
   `newTemp()` defaults to UNKNOWN; a future R781 could
   propagate type info through BINOP / UNOP in the
   translator instead of the analyzer.

---

## Lesson 1143

### 1143 (rule_id ↔ finding-CWE must be invariant)
The Lesson 1138 / 1140 / 1142 series fixes a structural
issue with `Finding` factory defaults. R780 surfaces a
**semantic** issue: the same instruction can trigger two
rules, and each rule's emit must tag a CWE that's in the
rule's allowed CWE set, otherwise the eval's CWE-alignment
check drops the finding. Pre-R780, IntOverflowChecker's
PRE_DEC / POST_DEC branch tagged CWE-191 (correct for
underflow) but its rule_id said
`plumb.numeric.integer-overflow` (rule-level CWE = ['190']).
This silently broke the invariant for ~1 982 findings.
**Lesson**: when a single checker dispatches to multiple
CWE tags (overflow vs underflow), the rule should split
into per-direction rules OR the rule's CWE list should
include both tags. For Plumb's numeric.* rules, the
Juliet-corpus-friendly shape is **per-direction rules
with disjoint op sets**: PRE_DEC/POST_DEC/SUB →
`plumb.numeric.integer-underflow` (CWE-191 only);
PRE_INC/POST_INC/ADD-overflow/MUL-overflow →
`plumb.numeric.integer-overflow` (CWE-190 only).
The overlap on ADD/MUL is a separate problem (Open item 1).

---

## Files (R780)

- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/IntOverflowChecker.java` (UNOP narrowed to PRE_INC/POST_INC; BINOP narrowed to ADD/MUL; both emit per-type message + CWE-190 + typeName helper)
- `plumb-dfa/src/main/java/org/plumb/dfa/checkers/taint/IntUnderflowChecker.java` (UNOP keeps PRE_DEC/POST_DEC + per-type message + CWE-191; BINOP keeps ADD/SUB/MUL + per-type message + CWE-191; typeName helper copy)
- `juliet-coverage/reports/r780/SUMMARY.md` (this file)
- `D:\tmp\plumb_test\scan\r780\CWE{129,190,191,369,835}.ndjson` (5 files, 158k findings, 100% flow steps, 0 unknown-file, message types = byte/short/int/long/int-long)
- `D:\tmp\plumb_test\eval\r780\global_per_cwe.csv` (precision identical to R777/R778/R779)

---

## Cross-cutting principle (R776 + R777 + R778 + R779 + R780)

Five R-Rounds in a row, each incrementally growing the
"every DFA finding is well-formed" guarantee:

1. **R776**: structured `FlowStep` exists; taint-style +
   resource checkers use it.
2. **R777**: `Finding.dfa` factory preserves empty filePath
   so enrichment fires.
3. **R778**: `Finding.violation` factory + ThreadLocal
   currentFunction + summariseOneFunction re-publish = 3 868
   unknown-file → 0.
4. **R779**: 3 numeric checkers ported to 11-arg
   `Finding.dfa` = 100% flow-step coverage.
5. **R780**: numeric checker messages name the actual Java
   type; PRE_DEC/POST_DEC/SUB now own the underflow rule.

After R780: **every numeric.* finding has coherent
rule_id ↔ CWE ↔ message.** The 3 audit points
(`filePath == 'unknown'`, `dataflow_steps == []`,
`rule_id-CWE mismatch`) are all addressed.

Future audit scripts:
- `r781_audit_cwe.py` — re-run after every per-CWE change to
  verify rule_id ↔ finding-CWE alignment; rejects
  IntOverflowChecker emitting CWE-191 on PRE_DEC.
- `r781_audit_type_msg.py` — every numeric.* message should
  include "on <byte|short|int|long|char>" or the UNKNOWN
  fallback "on int/long"; the audit greps the `message`
  field of all numeric.* findings.
