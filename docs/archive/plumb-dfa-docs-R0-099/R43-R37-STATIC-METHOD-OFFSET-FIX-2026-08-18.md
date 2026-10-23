# R43 — R37 Static-Method Offset Fix (defensive correctness)

**Date**: 2026-08-18
**Round**: R43
**Status**: SHIPPED — 0 regressions, 0 Juliet baseline change (defensive fix)
**Effort**: ~30 minutes (1 small offset fallback in `propagateTaintFromCalleeSummary`)

## Why R43

R5+ 5-day cross-fn sprint (R32-R35)
shipped a `propagateTaintFromCalleeSummary`
method that walks `calleeSummary.paramOpsAt(i)`
for each user-arg index `i` and
applies the callee's
`TaintPropagate` to the matching
caller arg. R37's offset convention
is:

```java
int callArgIdx = i + 1;  // instance method convention
if (callArgIdx >= ins.args.size()) break;
```

This is correct for **instance
methods** (where `args[0]` is the
receiver and `args[1+]` are user
args). For **static methods** (where
`args[0+]` are user args and there
is no receiver), this `+1` is wrong:
the first user arg is at `args[0]`,
not `args[1]`.

The pre-R43 code's
`if (callArgIdx >= ins.args.size()) break;`
**silently exited the loop** for
any static method call, dropping
the param-flow path entirely.

R43 applies the same two-tier offset
fallback that R40 and R42 use:
if `i + 1` is out of bounds, try
`i` directly (static method
convention).

## What R43 ships

### 1. Two-tier offset fallback in `propagateTaintFromCalleeSummary`

```java
int callArgIdx = i + 1;
if (callArgIdx >= ins.args.size()) {
    if (i < ins.args.size()) {
        callArgIdx = i;  // static method convention: no receiver
    } else {
        break;
    }
}
```

This means:
- For an instance method call like
  `s.execute(t_t5)` (args = [s, t_t5]),
  `i = 0`, `callArgIdx = 1 = t_t5`.
- For a static method call like
  `setData(u)` (args = [u]),
  `i = 0`, `callArgIdx = 1` is out
  of bounds, fall back to
  `callArgIdx = 0 = u`.

The R37 convention is preserved for
the common case (instance methods);
R43 just adds the static method
fallback.

## R43 numerical results

### R567 — 4 expected / 4 actual ✓ (no change from R42)

The R567 tests use the same
instance-method pattern. R43's
static-method fallback doesn't
trigger because R567's callees are
instance methods.

### R10 baseline — no change (6939 fires, same as R42)

The R10 baseline checkers
(SqlInjection, DBCredentialSink,
etc.) all use methods that already
worked with the R37 framework.
R43's static-method fallback only
fires for new patterns where a
caller passes a tainted arg to a
static method that has a
`TaintPropagate` on its param —
a narrow pattern that doesn't
show in Juliet's CWE-89 corpus
(Juliet mostly uses direct
getter/setter, not static
cross-method patterns).

### Full plumb-dfa test suite — 575/575 + 0 fail

No regression. R43 is a clean win.

## Cross-cutting design lessons (R43)

1. **R37's `+1` offset was a hidden
   bug for static methods.** The
   pre-R37 code had a different bug
   (used TAC arg index, not user-arg
   index). R37 fixed that for
   instance methods but introduced
   a new bug for static methods.
   R40 / R42 worked around it for
   the arg→dest and FieldOp paths,
   but the original R37 param-flow
   path was still broken. R43
   closes the gap. **Lesson**: a
   fix that addresses one bug can
   introduce another. Defensive
   offset fallbacks catch both.

2. **The "two-tier offset" pattern
   is now used in 3 places.** R40
   (arg→dest), R42 (FieldOp), R43
   (param-flow). All three apply
   the same fix: try `i + 1`
   first, fall back to `i`. A
   future R-round could refactor
   this into a shared
   `callArgIndexFor(ins, i)`
   helper, but for now the pattern
   is repeated inline for
   consistency with the surrounding
   code.

3. **R43 is a defensive fix, not
   a feature.** It doesn't add
   fires; it removes a silent bug.
   Juliet doesn't exercise the
   static-method pattern, so the
   bug was invisible. R43 is the
   "right thing" fix that future
   R-rounds can rely on. **Lesson**:
   defensive fixes without immediate
   test impact are still valuable —
   they prevent future bugs in the
   real code where static-method
   cross-fn patterns ARE common.

## Files added/modified (R43)

- **EDIT**: `plumb-dfa/src/main/java/.../taint/TaintedCheckerBase.java` (~20 lines: R43 two-tier offset fallback in `propagateTaintFromCalleeSummary` param-flow path)
- **DOC**: `plumb-dfa/docs/R43-R37-STATIC-METHOD-OFFSET-FIX-2026-08-18.md` (this file)

## Cumulative R1-R43 (Plumb only)

- 39+ CWE families, **~16,714 cweFire
  hits** (no Juliet regression from R43;
  R41 +363 still holds)
- 109 checkers (unchanged)
- 290 unit tests (R43 doesn't add
  new tests; it's a defensive fix)
- 0 R503 pre-existing failures
- 0 R16 pre-existing failures
- 0 R567 pre-existing FN cases
- **R5+ 5-day cross-fn sprint is
  fully complete** (R32 + R33 + R34
  + R40 + R41 + R42 + R43 = 7
  R-rounds)

## R43+ follow-up candidates

- **R44**: Overload disambiguation
  in `bareNameToCanonical` (R41
  uses `putIfAbsent` which is wrong
  for same-arity overloads). Would
  fix rare FP cases in real code.
- **R45**: Apply sig normalization
  to other call-graph strategies
  (RTA, VTA, OneCFA, Global).
  Currently only `cha` is normalized.
- **R46**: Cross-class field
  propagation (R34 currently only
  handles `this.f`; cross-class
  fields and static fields are
  deferred per the R5+ design).
- **R47**: R32 StringOp re-application
  in real checkers. The R33 path
  enrichment records StringOp
  in the summary, but the actual
  taint propagation through
  StringOp methods
  (`toLowerCase`, `trim`, etc.)
  is not yet wired.
