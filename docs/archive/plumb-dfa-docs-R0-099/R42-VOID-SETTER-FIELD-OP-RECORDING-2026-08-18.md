# R42 — Void-Setter FieldOp Recording (FN_DEEP fix)

**Date**: 2026-08-18
**Round**: R42
**Status**: SHIPPED — **R567 FN_DEEP fixed**, 0 regressions, 0 Juliet baseline change
**Effort**: ~1 hour (TaintedCheckerBase + FieldTaintTracker)

## Why R42

R41 fixed the call-graph sig
mismatch and unblocked R567 FN_CROSS
(via the R40 conservative arg→dest
rule). R42 addresses the
parallel problem for the
void-setter pattern
(`R567 FN_DEEP`).

The FN_DEEP test:
```java
class DeepFlow {
    String data;
    public void bad(req, conn) {
        String u = req.getParameter("u");
        setData(u);   // void setter, no return
        Statement s = conn.createStatement();
        s.execute("... " + data + " ...");
    }
    void setData(String d) { this.data = d; }
}
```

For this to fire:
1. `setData(u)` is called with u
   tainted.
2. The call's R34 fold must apply
   the callee's `FieldOp("data", 0)`
   to seed the caller's `this.data`
   tracker.
3. Subsequent `FIELDLOAD this.data`
   in the caller must restore the
   taint.

Pre-R42, the `setData` callee was
analyzed standalone with the param
`d` NOT tainted (no source). The
`recordFieldOpForStore` method had
an early-return guard:
```java
if (!taintedVars.containsKey(valueVar)) return;
```

This guard **silently dropped the
FieldOp** for void-setter patterns
because the param wasn't tainted
during standalone analysis. Without
the FieldOp, the caller's R34 fold
had nothing to apply, and the
`this.data` tracker remained empty.
The subsequent `FIELDLOAD` then
restored nothing, and the
`BINOP`/`CALL` to `s.execute`
saw `data` as clean.

R42 fixes this by removing the
tainted-only guard and recording a
`FieldOp` whenever the field's
value is a **direct param reference**
(`this.f = d` where d is a param),
regardless of taint state. The
caller's R34 fold can then use this
FieldOp to seed `this.data` from
the caller's matching arg.

## What R42 ships

### 1. recordFieldOpForStore: record FieldOp on direct param reference

`TaintedCheckerBase.recordFieldOpForStore`
now:

1. Checks if the value is a direct
   param reference (e.g. `d` in
   `this.data = d`).
2. If yes, records a
   `FieldOp.fromParam(fieldName, paramIndex)`
   regardless of taint state.
3. If the param is currently tainted
   (standalone analysis path with a
   source), records the taint state
   in the tracker.
4. If the param is NOT currently
   tainted (the common case for
   void-setters), clears any prior
   taint for the field (a clean
   write should overwrite a previous
   tainted write) and lets the
   caller-side R34 fold re-seed if
   the caller passes a tainted arg.

### 2. R42 caller-side offset fallback

`propagateTaintFromCalleeSummary`
now uses a **two-tier offset
fallback** for the FieldOp's
`callArgIndex`:

1. Try `sourceParamIndex + 1`
   (instance method convention —
   args[0] is the receiver).
2. If that's out of bounds, fall
   back to `sourceParamIndex`
   (static method convention — no
   receiver).

This is the same offset fallback
that R40 introduced for the
arg→dest rule, applied to the R34
field-fold path.

## R42 numerical results

### R567 — 4 expected / 4 actual ✓

| Case | Pre-R42 | Post-R42 |
|---|---|---|
| SAFE_SQL | 0 / OK | 0 / OK |
| SAFE_PATH | 0 / OK | 0 / OK |
| SAFE_XSS | 0 / OK | 0 / OK |
| **FN_DEEP** | 1 / FN | **1 / OK** ✓ |
| **FN_CROSS** | 1 / OK | 1 / OK (R41 fixed) |
| FP_LITERAL_SINK | 0 / OK | 0 / OK |
| FP_THROUGH_SANITIZER | 0 / OK | 0 / OK |
| MULTI_SINK | 2 / OK | 2 / OK |
| **TOTAL** | **4 / 3** | **4 / 4** (1 FN case fixed) |

**R567 is now 100% passing.**

### R10 baseline — no change (6939 fires, same as R41)

The R10 baseline doesn't include
test cases that exercise the
void-setter pattern (Juliet CWE-89
mostly uses direct getter/setter
inside the same method, not
cross-function). R42's impact is
in the R567 FN_DEEP fix, not in
the R10 fire count.

### Full plumb-dfa test suite — 575/575 + 0 fail

```
Found: 577 | Started: 575 | Succeeded: 575 | Failed: 0 | Skipped: 2
```

No regression. R42 is a clean win.

## Cross-cutting design lessons (R42)

1. **The "value must be tainted"
   guard silently drops void-setter
   patterns.** Pre-R42
   `recordFieldOpForStore` had a
   `if (!taintedVars.containsKey(valueVar)) return;`
   guard that was correct for the
   `propagateFieldTaintForWrite`
   use case (don't track clean
   writes) but wrong for the
   `FieldOp` recording use case
   (always record param-source
   writes for cross-fn fold). The
   fix is to distinguish "value is
   a direct param reference" from
   "value is a tainted local" and
   treat them differently.

2. **The R34 field-fold path had a
   hidden offset bug.** R34's
   `applyCalleeFieldOp` was hard-coded
   to `sourceParamIndex + 1` for
   the `callArgIndex`, but the IR
   builder doesn't always prepend
   the receiver for same-class
   calls (just like the
   `propagateTaintFromCalleeSummary`
   issue R40 fixed). R42 applies
   the same two-tier offset fallback
   to the field-fold path. **Lesson**:
   offset bugs in cross-function
   infrastructure are systemic;
   any new cross-fn rule must handle
   the static/instance offset
   carefully.

3. **R42 is the missing piece of
   the R5+ sprint.** R5+ (R32-R35)
   shipped FieldTaintTracker, but
   the FieldOp recording was gated
   on taint. For void-setters with
   no standalone-taint, the FieldOp
   was never recorded, and the
   fold had nothing to apply.
   R42 closes this gap. **Lesson**:
   a fold's "input" must be
   recorded even when the input
   is "clean" in standalone
   analysis; the fold itself
   decides whether to apply it
   based on the caller's state.

4. **R42 + R41 = R567 100%.** The
   two R-rounds together close the
   R5+ 5-day sprint's last open
   item. R567 was the documented
   test case for the R5+ design;
   now it passes cleanly.

## Files added/modified (R42)

- **EDIT**: `plumb-dfa/src/main/java/.../taint/TaintedCheckerBase.java` (~50 lines: recordFieldOpForStore rewrite + R42 offset fallback in propagateTaintFromCalleeSummary)
- **NEW**: `plumb-dfa/target/test-classes/R42Debug.java` (debug tool, 3.4KB)
- **BAK**: `_trash/R42Debug.java.bak`
- **DOC**: `plumb-dfa/docs/R42-VOID-SETTER-FIELD-OP-RECORDING-2026-08-18.md` (this file)

## Cumulative R1-R42 (Plumb only)

- 39+ CWE families, **~16,714 cweFire
  hits** (no Juliet regression from R42;
  R41 +363 still holds)
- 109 checkers (unchanged)
- 290 unit tests (R42 doesn't add
  new tests; fixes existing R567)
- 0 R503 pre-existing failures
- 0 R16 pre-existing failures
- 0 R567 pre-existing FN cases
  (was 2 pre-R40, both fixed by
  R41 + R42)
- **R5+ 5-day cross-fn sprint is
  fully complete** (R32 + R33 + R34
  + R40 + R41 + R42 = 6 R-rounds)

## R42+ follow-up candidates

- **R43**: R37 static-method handling
  fix in `propagateTaintFromCalleeSummary`
  (`maxParams = args.size() - 1` is
  wrong for static methods). The
  R40 / R42 two-tier offset
  fallback works around it but the
  underlying R37 limit remains.
- **R44**: Overload disambiguation
  in `bareNameToCanonical` (R41
  uses `putIfAbsent` which is wrong
  for same-arity overloads).
- **R45**: Apply sig normalization
  to other call-graph strategies
  (RTA, VTA, OneCFA, Global).
- **R46**: Cross-class field
  propagation (R34 currently only
  handles `this.f`; cross-class
  fields and static fields are
  deferred per the R5+ design).
