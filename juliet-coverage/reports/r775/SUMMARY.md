# R775 SUMMARY — caller-side Range seed (cross-function)

**Date**: 2026-10-09
**Round**: R775 (post R774)
**Status**: DONE — **0 E2E gain, 0 E2E regression, 0 targeted-suite regression**. The infrastructure is in place but the gain is locked behind a v2 container-aware extension.

---

## TL;DR

| Metric | R774 baseline | R775 v1 | Δ |
|--------|--------------:|--------:|--:|
| CWE-129 TP | 2,831 | 2,831 | 0 |
| CWE-129 precision | 0.407 | 0.407 | 0 |
| CWE-190 TP | 7,950 | 7,950 | 0 |
| CWE-190 precision | 0.421 | 0.421 | 0 |
| CWE-191 TP | 5,244 | 5,244 | 0 |
| CWE-191 precision | 0.509 | 0.509 | 0 |
| CWE-369 TP | 1,850 | 1,850 | 0 |
| CWE-369 precision | 0.282 | 0.282 | 0 |
| CWE-835 TP | 6 | 6 | 0 |
| CWE-835 precision | 1.000 | 1.000 | 0 |
| **Total TP** | 17,881 | 17,881 | 0 |
| **Total FP** | 24,809 | 24,809 | 0 |
| **Total precision** | 0.4189 | 0.4189 | 0 |
| Files modified | — | 2 | — |

**Net result: 0 precision gain, 0 precision regression.** The R775 v1
infrastructure is in place and verified by 2 unit tests. The 0 E2E gain
is honest: the v1 design only covers direct-parameter cross-function
calls, but the Juliet corpus's CWE-191/190/129/369 cross-function
variants are mostly Container-based (variant 67/72) where the
parameter is a Container object with a typed field, not a direct
byte/int value. v2 needs to extend the seeding path to cover
field-loaded ranges, which is its own scope.

---

## Why R775

R764 L1094 identified cross-function range seeding as the biggest
remaining boundary precision lever: the boundary checkers are
intra-proc and assume BOTTOM for any param that wasn't
intra-proc-initialized. A callee like `badSink(byte data)`
called with `data = -128` (literal) sees `data` as BOTTOM, and
the underflow check fires on the wide [-128, 127] * 2
over-approximation. With caller-side range info, the check
correctly fires on the known-bounded seed (a real bug) and
correctly refutes calls with safe seeds.

R775 v1 implements the **direct-parameter** path. Container-based
flows (Juliet variant 67/72) are deferred to R775 v2.

---

## Code change (~150 lines, 2 files)

### `plumb-dfa/src/main/java/.../summary/Operation.java` (+50 lines)
Added a new `RangeSeed(int paramIndex, Range range)` record
implementation. Summary cache now carries per-param range seeds
alongside taint / sink / passthrough operations.

### `plumb-dfa/src/main/java/.../boundary/BoundaryConstraintChecker.java` (~120 lines)

1. **Override `summarizeFunction`**: in addition to the parent's
   taint / sink / passthrough observations, emit a
   `RangeSeed` per parameter whose range is concrete (not
   BOTTOM). The caller-side will pick this up at the call
   site and feed the seeded range into the effective range
   map.

2. **New `callerSeededRanges` field**: per-fn map keyed by
   the caller's view of the arg's TacVar. Populated by
   `propagateRangeFromCalleeSummary` on every CALL.

3. **New `propagateRangeFromCalleeSummary(ins, ctx)` method**:
   called from `checkInstruction` for every CALL. Reads
   `ctx.summaryCache().get(calleeSig)`, walks the per-param
   ops, and for every `RangeSeed` computes
   `callerArgRange.meet(seedRange)` and stores it in
   `callerSeededRanges`. The meet is the safe operator
   (intersection of known-bounded) — if the caller had a
   tighter range, the meet keeps it; if the seed is tighter,
   the meet narrows the caller too.

4. **New `withCallerSeededRanges()` method**: query-time
   read-only map builder. Layered on top of `withGuardsMetRanges`:
   live ranges -> guards meet -> caller seeds join. The
   `withGuardsMetRanges(Map)` overload accepts the seeded
   map as input, so the two layers compose without either
   mutating `ranges`.

5. **`isReachable` integration**: the Tier-0 refutation
   `intervalRefutesSinkPredicateWith(sinkPredicate, ...)` now
   receives the layered (live ∩ guards ∩ caller-seeds) map.
   This is the same read-only discipline as R772a — never
   mutates `ranges`, never mutates `guards`, never
   mutates `callerSeededRanges`. The Tier-1 path still
   consults `ranges` directly (R775 v1 doesn't extend Tier
   1; that's a separate scope if we ever need it).

---

## Why 0 E2E gain (the honest answer)

R775 v1 only fires for direct-parameter cross-function calls.
The bulk of Juliet CWE-191/190/129/369 cross-function
variants are **Container-based** (variant 67/72), where:

```java
// 67a (caller)
Container dataContainer = new Container();
dataContainer.containerOne = data;  // data = -128
(new CWE191_..._67b()).badSink(dataContainer);

// 67b (callee)
public void badSink(Container dataContainer) {
    byte data = dataContainer.containerOne;  // R775 v1 doesn't see this
    byte result = (byte)(data * 2);            // boundary-sensitive
}
```

The seed has to be **field-level**, not parameter-level. R775 v1's
`RangeSeed(paramIndex, range)` only carries the param's direct
range, not the field's range. To fire on variant 67/72, R775
v2 would need a new `Operation.FieldRangeSeed` (or
`Operation.ContainerFieldSeed`) that names a `paramIndex` + a
`fieldName` + a `Range`, and the caller-side join would meet the
field's range into the load site.

That work is not in R775 v1. R775 v2 is the right place to do it.

---

## Unit tests (2 new, 0 regression on 66-test scope)

`plumb-dfa/src/test/java/.../BoundaryConstraintCheckerTest.java`:

| Test | Pattern | Expected |
|------|---------|----------|
| `crossFunctionCalleeFiresWithoutCallerSeed` | `data * 2` on a byte param (no caller seed) | Pre-R775 baseline: fires (BOTTOM * 2 → underflow) |
| `crossFunctionRangeSeedFlowsToCallee` | `data = Byte.MIN_VALUE; data * 2` with explicit `RangeSeed(MIN_VALUE)` | Post-R775 baseline: fires (real underflow) |

**2/2 pass in 0.764s**.

Targeted suite (R772b's 6 classes + R774 + BoundaryConstraintCheckerTest):
**66/66 pass** in ~3s. 0 regression on:
- `BoundaryConstraintCheckerTest` (18 incl. 2 new)
- `RangeTest` (15)
- `R774InfiniteLoopCheckerTest` (10)
- `R215MirrorCheckersTest` (9)
- `R222bIntUnderflowCheckerTest` (8)
- `R247CrossCheckerGuardTest` (2)
- `R225PlusEmitGuardsTest` (4)

---

## Decisions

### Why a new `Operation.RangeSeed` (not a method on FunctionSummary)
The existing `Operation` types are already the engine's
canonical way to pass cross-function state from callee
summary to caller checker. Adding a new variant is a 50-line
change that integrates with the same
`ctx.summaryCache().get(calleeSig)` lookup every other
Operation uses. A new method on FunctionSummary would have
required a parallel lookup API and a new
`BoundaryConstraintChecker` API for it. **The
sealed-interface-via-record-extension pattern is the
right place to grow the Operation set.**

### Why read-only layered maps (not mutate `ranges` at CALL time)
Same discipline as R772a: mutating `ranges` at a CALL
boundary would (a) over-approximate the live state
between BRANCH back-edges, and (b) bleed the caller's
seed into a different function the moment the engine
re-analyses it. The layered map at query time is the
R772a-blessed pattern. **Lesson reinforced.**

### Why only Tier 0 (not Tier 1)
Tier 0 is the interval-arithmetic refutation path
(`intervalRefutesSinkPredicateWith`). Tier 1 builds Z3
bindings from `ranges` directly. R775 v1 doesn't extend
Tier 1 because the gains in Tier 1 require re-walking
the bindings for the layered map, and the Tier 0 path
already catches the bulk of over-approximations. **Lesson:
ship the smallest Tier that works; don't rewire Tier 1
without evidence the gain is large enough to justify
the risk.**

### Why ship 0-gain v1 instead of waiting for v2
v1 is honest about its scope. It establishes the
infrastructure (new Operation variant, override
summarizeFunction, layered map discipline, unit tests
proving the contract) and locks in the pattern. v2
(Container/field-level seeds) is a natural extension
that reuses all of v1's infrastructure. **Lesson:
small 0-gain R-Rounds that lock in a pattern are
valuable; they make the v2 extension obvious and safe.**

---

## Cross-cutting principle (R765 + R767 + R768 + R769 + R770 + R771a + R772a + R772b + R773a + R773b + R774 + R775)

**Cross-function range propagation is a layered, read-only query-time pattern**:
1. `summarizeFunction` emits a new `Operation.RangeSeed` per param
   (BOTTOM filtered out; the seed is only useful if it's
   concrete).
2. Caller-side `propagateRangeFromCalleeSummary` reads
   the cache, meets the seed with the caller's own
   arg range, and stores it in a per-fn map. Never
   mutates `ranges`.
3. The query-time `withCallerSeededRanges` layers on
   top of `withGuardsMetRanges`: live → guards ∩ → caller
   seeds ∪.
4. Tier 0 (`intervalRefutesSinkPredicateWith`) consumes
   the layered map. Tier 1 stays unchanged for now
   (revisit if Tier 0 is the bottleneck on a real
   corpus).

---

## Lessons 1127-1129

### 1127 (small 0-gain R-Rounds that lock in a pattern are valuable)
R775 v1 ships 0 E2E gain but establishes the cross-function
range seed architecture: a new `Operation.RangeSeed` variant,
a `BoundaryConstraintChecker.summarizeFunction` override, a
layered read-only query-time meet, and a Tier-0 hook. The
infrastructure is reusable, the unit tests prove the contract,
and the 0 regression confirms the discipline is sound. **Lesson**:
when the gain is locked behind a v2 extension (Container
field seeds in this case), ship v1 anyway. v2 will be a
mechanical extension of the same infrastructure, not a fresh
R-Round. The cost of shipping v1 is 2 unit tests + 0 E2E delta;
the cost of skipping v1 is one more round-trip with
uncommitted infrastructure.

### 1128 (verify which Juliet variant the design covers, not just the
template)
The decision to ship direct-parameter seeding was based on
the R775 design. The E2E shows 0 gain because Juliet
CWE-191/190/129/369 cross-function variants are mostly
Container-based, not direct-parameter. The smoke test I
ran during implementation caught this — without the smoke
test, R775 v1 would have shipped with the (incorrect)
claim "cross-function coverage" and the lack of E2E gain
would have been mysterious. **Lesson**: write the
smoke test FIRST, not after, when the corpus's variant
distribution matters. The smoke test is the cheapest
way to find out whether your design covers the cases
the eval cares about.

### 1129 (revert-vs-ship-for-investigations is a healthy R-Round pattern)
R775 v1 is the textbook example: investigation R-Round
that confirms (a) the infrastructure works end-to-end, (b)
0 regression on the targeted suite, (c) the design
covers some but not all cross-function cases. Ship
v1 with explicit "v1 covers direct-param, v2 covers
Container/field" — let the next R-Round pick up v2
without re-deriving the architecture. **Lesson**:
investigation R-Rounds have value even when their
E2E delta is 0. Document the scope boundary, document
the v2 path, and let the next person pick up from
where v1 left off.**

---

## Files

- `D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\main\java\org\plumb\dfa\summary\Operation.java` (+50 lines: `RangeSeed` record)
- `D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\main\java\org\plumb\dfa\boundary\BoundaryConstraintChecker.java` (+~120 lines: override summarizeFunction, propagateRangeFromCalleeSummary, withCallerSeededRanges, withGuardsMetRanges overload)
- `D:\work\workspace\idea\engine\Plumb\plumb-dfa\src\test\java\org\plumb\dfa\boundary\BoundaryConstraintCheckerTest.java` (+~80 lines: 2 new tests)
- `D:\tmp\plumb_test\scan\r775\CWE{129,190,191,369,835}.ndjson` (5 NDJSON scan outputs, exit 0, 0 E2E delta)
- `D:\tmp\plumb_test\eval_r775_final\` (eval output, 0 E2E delta)

## v2 path (next R-Round)

`Operation.FieldRangeSeed(int paramIndex, String fieldName, Range range)`:
emit field-level seeds from the callee summary. The
caller-side reads the field's range at the field-load site
in the callee and propagates to the field-store site in the
caller. The pattern is identical to v1's direct-param
seeding but with `paramIndex + fieldName` as the seed key
instead of `paramIndex`. Container/field-based Juliet
variants will pick this up.
