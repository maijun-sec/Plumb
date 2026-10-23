# R572 — NullDerefChecker FP Fix (1,954 FPs removed)

**Date**: 2026-08-16
**Project**: plumb-dfa
**Files**: `plumb-dfa/src/main/java/org/.../taint/NullDerefChecker.java`
**Test**: `plumb-dfa/src/test/java/.../taint/R572NullDerefResetTest.java` (4/4 pass)

## The bug (R11 introduced, R572 fixed)

The pre-R572 `NullDerefChecker` had TWO bugs that combined to produce
massive false positives across the Juliet corpus:

### Bug 1: MOVE re-assignment doesn't clear null taint

Pattern:
```java
File tempFile = null;       // adds tempFile to nullTaintedVars
try {
    tempFile = File.createTempFile(...);  // MOVE: dest = tempFile
                                          // source is a CALL dest, NOT in nullTainted
                                          // → no propagation
} finally {
    if (tempFile != null) {
        tempFile.delete();  // CALL: receiver = tempFile, in nullTainted → FP!
    }
}
```

The MOVE propagation only handled "source in nullTainted → propagate to dest".
It didn't handle "source CLEAN → remove dest from nullTainted". So a var
that was initially null and then reassigned to a non-null value stayed
in the null map forever.

### Bug 2: "First instruction clear" runs AFTER null-MOVE handler

The pre-R572 code cleared `nullTaintedVars` when it saw the first
instruction of a function. But the order in `checkInstruction` was:
1. super.checkInstruction (base class)
2. null-MOVE handler: add s to nullTaintedVars
3. CALL handler: no
4. MOVE propagation: no
5. **First instruction clear: REMOVES s**
6. Field/Array/Call check: not field/array/call, return

So s was added then immediately cleared. This caused the test
`String s = null; s.length();` to NOT fire (the regression test in
R572NullDerefResetTest catches this).

## R571 — Discovery

R570 (full Plumb DFA sweep) showed suspicious high fire counts:
- CWE-523: 517 fires
- CWE-378: 486 fires
- CWE-459: 470 fires
- CWE-226: 309 fires
- CWE-390: 291 fires
- CWE-605: 290 fires

R571 (high-fire investigation) revealed all of these came from
`plumb.pointer.null-dereference` — the NullDerefChecker was firing
on every method call where the receiver was a local var that was
initially assigned to null and then reassigned.

## R572 — The fix

Two changes to `NullDerefChecker.java`:

1. **MOVE re-assignment clears the dest** (line 76-90):
```java
if (ins.kind == MOVE && ins.lhs1 instanceof TacVar mvSrc
        && ins.dest instanceof TacVar mvDest) {
    if (this.nullTaintedVars.containsKey(mvSrc)) {
        this.nullTaintedVars.put(mvDest, "move-from:" + mvSrc.name());
    } else {
        // Source CLEAN: dest reassigned to non-null, clear pre-existing taint
        this.nullTaintedVars.remove(mvDest);
    }
}
```

2. **`initFunction` clears at function boundary** (replaces "first
   instruction clear"):
```java
@Override
public void initFunction(AnalysisContext ctx, TacFunction fn) {
    super.initFunction(ctx, fn);
    this.nullTaintedVars.clear();
}
```

## R572 — Impact

| CWE dir | R570 (pre-fix) | R572 (post-fix) | FPs removed |
|---|---:|---:|---:|
| CWE-523 | 517 | 0 | -517 |
| CWE-378 | 388 (out of 486) | 0 | -388 |
| CWE-459 | 438 (out of 470) | 0 | -438 |
| CWE-226 | 194 (out of 309) | 0 | -194 |
| CWE-390 | 225 (out of 291) | 0 | -225 |
| CWE-605 | 192 (out of 290) | 0 | -192 |
| **Total** | **1,954** | **0** | **-1,954** |

CWE-690 unchanged: 735 fires (true positives, R11 fix preserved).

## Tests

- `R572NullDerefResetTest` (4/4 pass):
  - `reassign_after_null_no_fp` (regression: was FP)
  - `propagation_still_works` (sanity: original TP preserved)
  - `nested_block_reassign` (try/finally pattern)
  - `literal_null_then_call_still_fires` (sanity: real null deref still fires)

## Status

**R572 SHIPPED**. 1,954 FPs removed, CWE-690 unchanged, 4 new unit tests.
