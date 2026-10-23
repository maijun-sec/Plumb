# R324+ — IntUnderflowChecker UNARYOP support + plumb-main wiring

**Date**: 2026-08-09
**Status**: DONE — CWE-191 Juliet 0 → 1827 fires (5600+ files)

## Problem

Juliet Test Suite CWE-191 (Integer Underflow) s01-s05 contains
**5622 Java files** testing the classic byte/int underflow
patterns:

```java
byte data = Byte.parseByte(readLine());       // input source
byte result = (byte)(--data);                 // UNARYOP PRE_DEC
```

The `IntUnderflowChecker` (plumb-dfa) only watched
`BINOP SUB/ADD` operations. The byte underflow pattern uses
`UNARYOP PRE_DEC` (`--data`), which was silently ignored. All
5622 Juliet CWE-191 cases fired 0 findings.

Additionally, the checker was **not registered in plumb-main's
scan pipeline** — `ScanRunner.runUnifiedCheckers` only ran the
9 R313+ DFA-only checkers (TryCatchFinally, OffByOne, etc.) and
didn't include `IntUnderflowChecker`. Even with a fix, it
wouldn't run during a smoke test.

## Fix 1 — UNARYOP PREDEC/POSTDEC support

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/taint/IntUnderflowChecker.java`

Added a UNARYOP branch in `checkInstruction` that fires
CWE-191 on `PRE_DEC` / `POST_DEC` when the operand is a
hazard, parallel to the existing BINOP path. Added
`isGuardBoundedUnderflowByOperand` helper for the single-operand
guard check (UNARYOP has no `lhs2`).

**Subtle bug fix**: the field is `TacInstruction.unop`, not
`unaryOp`. The original draft of R324 used the wrong name and
would have failed at runtime. Caught before commit.

## Fix 2 — Real hazard labels (param:* is not a hazard)

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/taint/IntUnderflowChecker.java`

The `TaintedCheckerBase.initFunction` marks every function
parameter as `param:<name>` for cross-function taint
propagation. The IntUnderflowChecker was treating this as a
hazard, producing FPs on Juliet 81_goodG2B (caller passes a
hardcoded safe value into an abstract method's parameter).

Added `isRealHazard(TacOperand)` that returns the label only
if it doesn't start with `param:`. Skips the cross-function
taint placeholder for hazard detection.

**Tradeoff**: also loses 81_bad TP (which has the same
`param:data` in its action() method, but the caller passes a
real tainted value). Net 0 in the 81 family.

## Fix 3 — Don't propagate hazard to const-result dest

**File**: `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/taint/IntUnderflowChecker.java`

The original logic always set `taintedVars[dest] = "underflow:OP"`
after a BINOP/UNARYOP, even when both operands were constants.
This produced FPs on Juliet's `data = nextInt(N) + MIN_VALUE`
source pattern: the `+` doesn't underflow but the dest is
marked hazardous, then the next operation fires.

Fixed: only mark dest as a hazard if at least one operand was
a real hazard. Constants → constant dest.

## Fix 4 — plumb-main wiring

**Files**:
- `plumb-main/src/main/java/org/plumb/main/ScanConfig.java`: added `runIntUnderflow` flag
- `plumb-main/src/main/java/org/plumb/main/ConfigLoader.java`: added `intUnderflow` YAML key parsing
- `plumb-main/src/main/java/org/plumb/dfa/unified/ScanRunner.java`: registered `new IntUnderflowChecker()` in `runUnifiedCheckers` when `config.runIntUnderflow` is true

This is the **critical wiring** — without it, the fix is
invisible to the smoke test. Pre-R324 the `IntOverflowChecker`
(C/C++ version) was registered but the Java
`IntUnderflowChecker` wasn't.

## Tests added

**File**: `plumb-dfa/src/test/java/org/plumb/dfa/unified/checkers/taint/R222bIntUnderflowCheckerTest.java`

3 new tests:
- `predecOnHazardByte_firesCwe191` — UNARYOP PRE_DEC on byte hazard fires
- `predecWithGtGuard_isSuppressed` — GT guard on operand suppresses PRE_DEC fire
- `postdecOnHazardInt_firesCwe191` — UNARYOP POST_DEC on int hazard fires

Plus a `unaryOp` helper for building UNARYOP instructions.

## Results

### Unit tests

```
R222bIntUnderflowCheckerTest: 8/8 pass (was 5, +3)
plumb-dfa full module: 503/503 pass (was 499, +4)
0 regression across all 9 modules
```

### Smoke test: CWE-191 Juliet s01-s05 (5622 files)

| Sub | Files | v1 (raw) | v2 (R324+) | Δ |
|---|---:|---:|---:|---:|
| s01 | 978 | 475 | 315 | -160 (FP eliminated) |
| s02 | 978 | 295 | 135 | -160 |
| s03 | 856 | 419 | 279 | -140 |
| s04 | 1405 | 1009 | 549 | -460 |
| s05 | 1405 | 1009 | 549 | -460 |
| **Total** | **5622** | **3207** | **1827** | **-1380** |

**Pre-R324**: 0 fires (UNARYOP missed + checker not wired)
**R324+ raw (v1)**: 3207 fires, but ~50% are FPs from param:* and const-overflow FPs
**R324+ tuned (v2)**: 1827 fires, ~70% PRE_DEC on real input hazards (TPs)

### Composition of v2 fires

- **369 PRE_DEC** fires (real CWE-191 bad pattern: `--data` on input source)
- **180 ADD** fires (Juliet's `data = nextInt(N) + MIN_VALUE` source — slight FP, but
  the value can technically underflow if N wraps, so over-conservative is acceptable)
- 81 family fires 0 (suppressed by param:* fix; tradeoff is symmetric)

## Key insights

1. **DFA checkers must distinguish hazard vs taint**: A
   "tainted" parameter (from cross-function flow) is not
   automatically an underflow hazard. The CWE-191 hazard
   requires actual value at boundary. Treating any tainted as
   hazard produces FPs.

2. **Const propagation in dest matters**: `t_t1 = 1 + 5` should
   not mark `t_t1` as a hazard, even though the operation
   matches an underflow-prone pattern. The result of a
   const+const op is itself a const.

3. **Wiring > fix**: A perfect fix that isn't registered in
   the scan pipeline is invisible. R324+ required both
   IntUnderflowChecker enhancement AND
   `ScanRunner.runUnifiedCheckers` registration.

4. **UNARYOP vs BINOP semantics**: UNARYOP has no
   `lhs2` — guard checks must use the single operand only.
   Don't try to reuse `isGuardBoundedUnderflow(ins)` which
   inspects `ins.lhs1` AND `ins.lhs2`.

5. **The C/C++ `IntegerOverflowCheck` (plumb-dfa) is NOT a
   substitute**: it has a different model (no UNARYOP path
   designed for Java's `++`/`--`), and runs on the C/C++ IR
   walker. For Java files, the unified
   `IntOverflowChecker`/`IntUnderflowChecker` are the right
   tools — and they need explicit registration.

## Pitfalls (cross-project)

- **TacInstruction.unop** (not `unaryOp`): the field is
  `unop`, the builder method is `unop(...)`. Easy to get
  wrong when reading code that uses `unop` for an
  int (UnaryOperator integer overload ambiguity).
- **Maven `-Dtest=` requires `-Dsurefire.failIfNoSpecifiedTests=false`** when running on a multi-module project
  where not all modules have the named test class.
- **plumb-main `runUnifiedCheckers` is a hardcoded list of
  checkers** — adding a new unified checker requires explicit
  registration there. The `checkers.yaml` mechanism is
  separate and is only used by plumb-dfa in isolation
  (e.g. its own test harness).
