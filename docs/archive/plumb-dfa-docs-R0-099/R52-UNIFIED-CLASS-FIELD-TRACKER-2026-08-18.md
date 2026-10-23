# R52 — Unified Cross-Function Class-Field Tracker

**Status:** ✅ **SHIPPED (2026-08-18)** — no regression, R50 + R51
trackers merged into a single map with a `FieldSource` kind tag.

**Files added/modified**:
- `taint/TaintedCheckerBase.java` (refactor, no functional
  change) — four pre-existing maps (R50's static pair
  + R51's instance pair) collapsed into two unified
  maps + a `FieldSource` record tagging each entry as
  `STATIC` or `ALIASED`. Both write paths now update the
  unified map; the read path uses the kind tag to emit
  the right path message.
- `docs/R52-UNIFIED-CLASS-FIELD-TRACKER-2026-08-18.md`
  (this file).

## Why R52

R50 added `crossFnStaticFieldTaint` +
`crossFnStaticFieldSource` for cross-class static
fields. R51 added `crossFnInstanceFieldTaint` +
`crossFnInstanceFieldSource` for same-type aliased
receivers. Both pairs share the same key shape
(`"ClassName.fieldName"`) and the same value type
(`String` taint label) — but they were two separate
maps because the write paths are gated by different
heuristics (R50: `isLikelyClassName`; R51: `TacType.kind()
== CLASS`) and the read paths emit different messages
(`GLOBAL_STATIC_READ` vs `ALIASED_INSTANCE_READ`).

R52 unifies them. The two pre-existing maps are
**functionally redundant** — anything in the R51 map
overlaps with anything in the R50 map (both have the
same key shape and value type). The only meaningful
difference is the kind tag, which is needed only at
read time for path messaging.

## Design

### Unified map

Two `static final` maps replace the four pre-existing
ones:

```java
private static final Map<String, String>
        crossFnClassFieldTaint = new HashMap<>();
private static final Map<String, FieldSource>
        crossFnClassFieldSource = new HashMap<>();
```

The source-info value type is a record:

```java
private record FieldSource(String functionSig, String kind) {
    static final String KIND_STATIC = "STATIC";
    static final String KIND_ALIASED = "ALIASED";
}
```

The `kind` tag tells the read path whether the write
came from R50's static-class flow or R51's aliased
flow. Both writes update the same key; the most-recent
write wins (same as the pre-R52 behaviour). The tag
preserves information about *which kind* of write was
most recent, so the path message can say
`GLOBAL_STATIC_READ ...` vs `ALIASED_INSTANCE_READ ...`
appropriately.

### Write path

`recordFieldOpForStore` now writes to the unified map
in two places (matching the pre-R52 split):

1. If `isLikelyClassName(recvName)` (R50 / R47
   uppercase heuristic) → write with `KIND_STATIC`.
2. If `classNameOfType(recv.type())` is non-null (R51
   declared-type check) → write with `KIND_ALIASED`.

A clean write (no taint) removes the entries the same
way. Both entry points are guarded separately because
they're gated by different heuristics — a class-name
receiver doesn't necessarily have a class type
(`classRefVars` resolved it from a chained `this.X`
load), and a class-typed receiver isn't necessarily a
class name (it's a local with a declared class type).

### Read path

`restoreFieldTaintOnLoad` consults the unified map
with a single key derivation:

1. Look up by the receiver's declared class type
   (R51 case) — preferred because it matches the
   more-specific "this is a class-typed receiver"
   criterion.
2. If that misses, look up by the resolved receiver
   name (R50 case) — for the "this is a class name"
   case where the TacType is empty or different.

Whichever key matches, the read uses the
corresponding `FieldSource` to build the path:

- `KIND_STATIC` → `"GLOBAL_STATIC_READ <key>
  (set by <fn>) -> <dest>"`.
- `KIND_ALIASED` → `"ALIASED_INSTANCE_READ
  <recvName>.<field> (declared as <type>, set by
  <fn>) -> <dest>"`.

If both keys miss, the read falls through (no taint
restored), exactly as pre-R52.

### `clearCrossFunctionState`

Now a single line pair:

```java
protected void clearCrossFunctionState() {
    crossFnClassFieldTaint.clear();
    crossFnClassFieldSource.clear();
}
```

(pre-R52 had four clears.)

## Numerical results

| Metric | Pre-R52 | Post-R52 | Δ |
|---|---:|---:|---:|
| R10 Juliet baseline | 6939 fires | 6939 fires | 0 |
| R567 cross-fn sweep | 4/4 ✓ | 4/4 ✓ | 0 |
| plumb-dfa unit tests | 583/583 ✓ | 583/583 ✓ | 0 |
| R50 multi-class test | 2/2 ✓ | 2/2 ✓ | 0 |
| R51 receiver-aliasing test | 2/2 ✓ | 2/2 ✓ | 0 |

R52 is a **pure refactor round** — no fires change, no
test count changes, just code-reuse. The functional
behaviour is identical to the post-R51 state.

## Cross-cutting design lessons (R52)

1. **Two parallel trackers with the same key shape
   and value type are a code-smell.** R50 and R51
   each had a pair of maps; the four maps had two
   distinct keys (uppercase class name for R50,
   declared class type for R51) and a common value
   type (`String` taint label). The kind tag was the
   only meaningful difference. R52 collapses them
   into a single pair + a record.
2. **A record is the right shape for "value + tag".**
   `FieldSource(String functionSig, String kind)` is
   a tiny immutable record. The alternative
   (`Map<String, String>` with a sentinel
   concatenation) would have been less type-safe and
   less efficient. Records are essentially free in
   Java 21.
3. **"Propagate to other callers" rounds are a
   normal part of incremental development.** When a
   new helper is added (R50, R51), subsequent rounds
   sweep other callers for consistency. R52 is the
   "merge two parallel implementations into one"
   version of the same pattern.
4. **Refactor rounds are a legitimate deliverable.**
   R52 has no new fires, no new test count, no
   user-visible feature. The win is operational:
   4 maps → 2 maps + 1 record, 4 clears → 2 clears,
   2 write blocks → 1 write block, 2 read blocks → 1
   read block. A future R-round touching
   `crossFnClassFieldTaint` will only need to update
   one place, not four.
5. **The "is the receiver a class name?" and "is the
   receiver's TacType a class?" checks are still
   separate.** R52 unifies the *storage* but the two
   write-entry-points remain distinct because they
   answer different questions (R47's "is this a
   class reference?" vs R51's "does this receiver
   have a class type?"). A future refactor could
   unify the heuristics, but that would be a
   behaviour change, not a refactor.

## R52+ follow-up candidates

- **R53 (MEDIUM)**: cross-function return-value
  taint. R37 / R40 partially address it; R53 could
  complete the picture by folding getter methods
  back to the caller's dest.
- **R54 (LOW)**: refactor `GlobalCallGraphBuilder`'s
  inlined R52 logic to share
  `CallGraphBuilder.r48ResolveCalleeSigWithArgs`
  (after the cross-TU / single-TU mismatch is
  resolved).
- **Bare-name consistency fix**: fix
  `bareNameAndArity` to return a consistent form
  for "no parens" vs "with parens" signatures. R49's
  `normalizeCalleeKey` would shrink to a direct
  `program.function(sig)` lookup.
- **Unify the field-classification heuristic**: a
  shared `FieldOpClassifier` that centralises
  uppercase-class-name + declared-class-type +
  array-vs-class vs primitive checks. R34 / R42 /
  R46 / R47 / R50 / R51 / R52 each have a small piece
  of this logic.
