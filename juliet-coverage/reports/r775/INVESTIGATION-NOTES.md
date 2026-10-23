# R775 v1 — deep investigation findings (2026-10-09)

After R775 v1 shipped 0 E2E gain, deeper investigation revealed
the **real blocker** for cross-function range propagation that
needs a fundamentally different R-Round to address.

---

## Setup

R775 v1 ships:
- `Operation.RangeSeed(paramIndex, range)` record
- `BoundaryConstraintChecker.summarizeFunction` override emits
  per-param seeds
- `propagateRangeFromCalleeSummary(ins, ctx)` reads the
  cache at every CALL, meets seed with caller arg range
- `withCallerSeededRanges()` query-time layered map

**Targeted suite 66/66 pass, 0 regression.** E2E CWE-129/190/191/369
all unchanged at 0.407/0.421/0.509/0.282. CWE-835 still 1.000.

---

## The real blocker (discovered after E2E)

Initial R775 SUMMARY claimed R775 v1 "only covers direct-parameter
cross-function calls, not Container-based variant 67/72". Deeper
debug reveals this is **only half the picture**. R775 v1 also
**fails to fire on direct-parameter variant 22/52** because of
a fundamental architecture issue:

```
Juliet variant 22:
  22a.bad():
    byte data = MIN_VALUE;            // data is in 22a's ranges map
    (new 22b()).badSink(data);         // CALL instruction

  22b.badSink(byte data):
    data * 2;                          // data is in 22b's ranges map
```

When `analyseOneFunction(22a)` runs:
- `data = MIN_VALUE` is recorded in 22a's ranges map
- The CALL to 22b.badSink is reached
- `propagateRangeFromCalleeSummary` looks up `22b.badSink/1`
  in `ctx.summaryCache()`
- **22b's summary is empty** because `analyseOneFunction(22b)`
  ran FIRST (RPO order: callees before callers). When 22b
  ran, its `ranges` map had `data` as BOTTOM — 22b doesn't
  track its own param's range from the call site because
  the call-site arg is in **caller's** ranges, not callee's.
- 22b's `summarizeFunction` emits `RangeSeed(0, BOTTOM)`,
  filtered out.
- Caller-side lookup of 22b's summary sees zero seeds.
- R775 v1 emits **0 E2E effect** on direct-parameter variant
  22/52.

This is **not** "only fires on direct-param" — it doesn't even
fire on direct-param. The seed mechanism is fundamentally
self-defeating when the callee analyzes BOTTOM and emits BOTTOM
seeds.

---

## What R775 v2 actually needs

To make `data = MIN_VALUE` in caller propagate to `data * 2`
in callee, one of the following must happen:

1. **Summary cache write-back at call site** (architectural
   change). The caller writes a new summary entry for the
   callee that includes the caller's known range for the
   argument, then re-runs the callee analysis. This requires
   a new `FunctionSummaryCache.putWithSeed(calleeSig, summary)`
   method and a re-analysis loop.

2. **Two-pass analysis** (architectural change). First pass:
   analyze everything with BOTTOM, populate cache. Second
   pass: for every (caller, callee) pair where caller knows
   the arg range, write a new summary with a fresh
   `RangeSeed`, then re-analyze the callee. The engine's
   existing worklist-style `drainRerunFns` mechanism is
   the right hook (R717), but the seed mechanism needs
   to feed into it.

3. **Inlining** (architectural change). Replace every call
   to a callee with the callee's body, scoped to the
   caller's context. The R772a/R775 read-only layered-map
   pattern already supports this if we move the body.
   Expensive.

None of these fit R775 v1's "small 0-gain R-Round that
locks in a pattern" mandate. R775 v2 is a different shape:
**summary cache write-back** OR **two-pass re-analysis with
seed injection**.

---

## R775 v1 honest verdict

R775 v1 is an **investigation R-Round** that:

- Established the cross-function range seed architecture
  (new `Operation` variant, `summarizeFunction` override,
  layered read-only query-time map, 2 unit tests).
- Discovered, via the smoke test, that the design's
  BOTTOM-pass-through gap is the real blocker.
- Produced 0 E2E gain but 0 regression — the architecture
  is in place and could be useful with a different cache
  injection mechanism (R775 v2).

R775 v1 stays as the **foundation** R-Round. R775 v2 is
**summary-cache write-back** or **two-pass re-analysis**,
not an extension of v1's pattern.

---

## R775 v2 path (next R-Round)

The next R-Round has two viable shapes:

**Option A: caller-side summary write-back at the call site**
- New method `FunctionSummaryCache.putWithCallerSeeds(calleeSig, seeds)`
- Caller-side propagate rewrites the cache entry after
  every CALL with a `RangeSeed` for each arg whose
  range is known
- Re-run callee analysis (via the existing
  `drainRerunFns` worklist)
- Higher precision — actually fires on direct-param
  cross-function
- Risk: re-analysis can change other call sites'
  summaries; needs worklist fixpoint

**Option B: field-level cross-function (the original v2 plan)**
- For Container-based variant 67/72: caller knows
  `dataContainer.containerOne = MIN_VALUE`. Need a
  new `Operation.FieldRangeSeed(paramIndex, fieldName, range)`
  that propagates to FIELDLOAD sites.
- Same cache write-back problem as Option A — callee
  needs to know the field's range at the FIELDLOAD site.
- More general: also covers Juliet variant 6x/7x.

**Recommendation: Option A first** — it's a smaller change
that fires on the most common case. Option B can be
layered on top once A is in place.
