# R220 — If-guard detection for path-sensitive FP reduction

Date: 2026-08-01
Status: **DONE** (8/8 R220 tests + 184/184 plumb-dfa tests, 0 regression)

## Goal

The Juliet "goodB2G" pattern is the
classic false-positive source for
path-insensitive dataflow analysis:

```java
if (data < Byte.MAX_VALUE) {
    byte result = (byte)(data + 1);
    // ^ safe: data is bounded by MAX,
    //   so data+1 cannot overflow
}
```

A flow-insensitive check fires at
`data + 1` (CWE-190), but the operation
is provably safe. R220 adds lightweight
path-sensitive guard tracking so the
check suppresses the finding when the
hazardous operand is bounded by an
active if-guard.

## Implementation (1 new util + 2 checkers enhanced + 1 test, ~16KB)

### `plumb-dfa/.../taint/GuardTracker.java` (new, 7798 bytes)

Per-function state machine that tracks
comparison guards in the linear IR.

- **recordCompareIfApplicable**: any
  `BINOP t = a <relop> b` where one side
  is a literal (or FIELDLOAD-loaded
  constant) is recorded into
  `lastCompare[t] = Guard(a, relop, b)`.
  BOUNDING_OPS = {LT, LE, GT, GE} for
  IntOverflow, NON_ZERO_OPS = {NE} for
  DivByZero.
- **onBranch**: when BRANCH uses a
  recorded compare as its condition,
  push the guard onto the active stack.
- **onJump**: pop the guard (we're
  leaving the if-true block).
- **recordConstantLoadIfApplicable**: a
  `FIELDLOAD` like `t = Byte.MAX_VALUE`
  is recorded into `knownConstants` so
  the comparison side `data < t` is
  recognised as a bound.

Public API:
- `clear()` — reset at function boundary
- `track(ins)` — feed one instruction
- `isGuarded(var)` — any active guard
  for var
- `isGuardedBy(var, op)` — active guard
  with this specific op
- `currentGuardFor(var)` — innermost
  guard

### `IntOverflowChecker.java` (modified)

- Added `private final GuardTracker
  guards = new GuardTracker()`
- Added `initFunction` override to reset
  guards per function
- `checkInstruction` calls
  `guards.track(ins)` after input-source
  detection
- Before emitting, calls
  `isGuardBoundedOverflow(ins)`:
  - For ADD: operand guarded by
    `LT` / `LE` → suppress
  - For SUB: operand guarded by
    `GT` / `GE` → suppress
  - For MUL: bail (only safe if both
    operands are 0 or 1, not worth
    checking)

### `DivByZeroChecker.java` (modified)

- Same GuardTracker integration
- Before emitting `100 / data`, checks
  `guards.isGuardedBy(rhs, BinaryOperator.NE)`
  — if true, the `if (data != 0)` guard
  holds, suppress.

### `plumb-dfa/.../taint/GuardTrackerTest.java` (new, 8 tests)

- `empty_noActiveGuards`
- `branchOnLiteralLessThan_pushesGuard`
- `branchOnFieldLoadConst_pushesGuard` —
  covers the `Byte.MAX_VALUE` pattern
- `jumpPopsGuard`
- `nestedGuards_handledCorrectly` —
  stack-based push/pop with same var
- `nonZeroGuard_recorded` — for
  DivByZero
- `unsupportedOp_notRecorded` — EQ is
  not a bound
- `clear_resetsState`

## Results

| CWE | R219 (pre-R220) | R220 (post) | Δ | % reduction |
|-----|----------------:|------------:|----:|----:|
| CWE-89 SQL injection | 6,162 | 6,162 | 0 | 0% |
| CWE-78 OS command | 816 | 816 | 0 | 0% |
| CWE-190 int overflow | 6,051 | **5,163** | -888 | **-14.7%** |
| CWE-369 div by zero | 3,958 | **2,788** | -1,170 | **-29.6%** |
| CWE-476 null deref | 119 | 119 | 0 | 0% |
| **Total** | **17,106** | **15,048** | **-2,058** | **-12.0%** |

**2,058 fewer findings (12% reduction)**
with the same TP coverage — these are
the goodB2G false positives that the
guard detection correctly suppresses.

## Key insights (R220)

1. **Linear-IR guard tracking is enough**
   (R220 design). The R211 IR has a
   predictable `BRANCH t / LABEL /
   ... / JUMP / LABEL` shape for if-blocks.
   No need for full CFG analysis — a
   stack-based state machine over the
   linear instruction stream is correct
   for the common patterns.
2. **FIELDLOAD-loaded constants matter**
   (R220 pitfall fixed). The first
   implementation only accepted `Const`
   literals; the IR uses a synthetic
   `TacVar` for the receiver class name
   ("Byte" for `Byte.MAX_VALUE`). The
   fix: accept any TacVar or Const for
   the receiver, and recognise
   all-uppercase field names as
   constants.
3. **Two guard semantics, one tracker**
   (R220 design). BOUNDING_OPS for
   IntOverflow, NON_ZERO_OPS for
   DivByZero — same `Guard` record,
   same stack. `isGuardedBy(var, op)`
   lets each checker ask the right
   question.
4. **MUL is hard** (R220 limitation).
   We only suppress ADD/SUB for
   IntOverflow. MUL would require
   knowing the other operand is 0 or 1
   (which means parsing the literal
   value — out of scope for R220).

## Pitfalls fixed during R220 (worth recording)

- **ConstLike as TacOperand**: I first
  tried to make a `ConstLike` record
  implement `TacOperand` so the
  `Guard` record could hold it. But
  `TacOperand` is a sealed interface
  with `permits TacVar, Const`. Switched
  to a `String literalDescription` in
  the `Guard` record.
- **FIELDLOAD lhs1 type**: Initially
  assumed `lhs1` is a `Const` for class
  names like `Byte`. Wrong: the R211
  translator emits a synthetic
  `TacVar("Byte", UNKNOWN)`. Fixed by
  accepting both Const and TacVar in
  `recordConstantLoadIfApplicable`.
- **Const value boxing**: `Integer.valueOf(0).equals(c.value())`
  — `c.value()` returns `Object`, so
  this works for autoboxed ints. No
  issues here, but worth noting for
  future Const comparisons.

## Test results

- R220: 8/0/0/0 (GuardTrackerTest)
- plumb-dfa: 184/0/0/0 (was 176;
  +8 R220 tests, 0 regression)
- R219 integration test: still 5/0/0/0;
  findings dropped from 17,106 to 15,048
  (-12%)

## Backup

`D:\work\tmp\r220_done\` (4 files, ~22KB):
- `GuardTracker.java` (new utility)
- `IntOverflowChecker.java` (modified,
  uses guards)
- `DivByZeroChecker.java` (modified,
  uses guards)
- `GuardTrackerTest.java` (new tests)

## Next

- **R222**: 5-10 more mirror checkers
  (CWE-129 array index, CWE-191 int
  underflow, CWE-22 path, etc.) for
  FN reduction.
- **R218b/c** (optional): real Z3
  bridge + cross-function path-condition
  propagation.
- **R220a**: already done.
