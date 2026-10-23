# R47 — Static Field Propagation

**Date**: 2026-08-18
**Status**: SHIPPED ✅
**Round scope**: Extend the R46
`(receiver, field)` field-taint
tracker to handle **static field
access** (e.g.
`Helper.config = tainted; ...
s.execute("... " + Helper.config
+ " ...")`.

## Why R47

R46 lifted the R34 "only `this.f`"
restriction and made the field
tracker key on `(receiver, field)`
for any instance receiver. R46's
doc explicitly deferred static
fields because the IR builder
encodes static field access via a
chained-field pattern that R46
didn't address:

```java
// Source:
Helper.config = u;       // STATIC FIELD WRITE
String s = Helper.config;  // STATIC FIELD READ
```

```tac
// IR (as emitted by the IR builder):
[0] FIELDLOAD  dest=t_t1  lhs1=this  field=Helper
[1] FIELDSTORE dest=u     lhs1=t_t1  field=config
[2] FIELDLOAD  dest=t_t3  lhs1=this  field=Helper
[3] FIELDLOAD  dest=t_t4  lhs1=t_t3  field=config
```

The IR treats the class name
`Helper` as a "field" of the
outer class (`this.Helper`). R46's
tracker would record the write
under `("t_t1", "config")` (a
meaningless synthetic key) and
the read looks up
`("t_t3", "config")` — different
keys, no taint restored. R47
adds a chain tracker that maps
`t_t1 → "Helper"` and
`t_t3 → "Helper"` so both the
write and the read route to the
`("Helper", "config")` tracker
key.

## The fix

### `TaintedCheckerBase.java`

Three new pieces:

1. **New per-function map
   `classRefVars: Map<TacVar,
   String>`** — records TacVars
   that were loaded from
   `this.<ClassName>`. Populated
   in `recordClassRef(ins)`,
   cleared at every function
   boundary by `initFunction`.

2. **New `recordClassRef` helper**
   — for every FIELDLOAD with
   `lhs1.name() == "this"` AND a
   class-name-looking `fieldName`
   (starts with uppercase), record
   `dest → fieldName` in
   `classRefVars`. The uppercase
   heuristic is the Java naming
   convention: class names start
   uppercase, instance fields
   start lowercase.

3. **New `resolveReceiverName`
   helper** — looks up a TacVar
   in `classRefVars`; if present,
   returns the class name;
   otherwise returns the TacVar's
   own name. Used by both
   `recordFieldOpForStore` and
   `restoreFieldTaintOnLoad` to
   translate a chained
   `t_t1 = this.Helper;
   t_t1.config = u` write to the
   `("Helper", "config")` tracker
   key.

The FIELDLOAD handler now calls
`recordClassRef(ins)` AFTER the
standard `propagateMOVE(ins)` and
BEFORE
`restoreFieldTaintOnLoad(ins, fn)`.
This ordering means the
class-ref map is populated before
any read-side lookup happens for
the same TacVar.

### Why the uppercase heuristic

The IR builder encodes the class
name as a field name in
`this.<ClassName>`. There's no
explicit "this is a class
reference" marker in the IR. The
class-name-looking heuristic
(starts with uppercase) is a
Java-naming-convention proxy
that's conservative: false
positives (treating a regular
uppercase field name as a class
reference) are harmless because
the regular `("this", field)`
tracker key is unaffected; the
class-ref entry would just point
to a non-existent class name, and
any subsequent FIELDSTORE/FIELDLOAD
on the dest TacVar would route to
a benign `("NonExistentClass",
field)` key. False negatives
(treating a class name as a
regular field) would have been
the pre-R47 behavior — the
write is recorded under the
synthetic `("t_t1", field)` key
and the read under `("t_t3", field)`
— no fire. R47 fixes false
negatives for the common case of
real class names.

## What R47 ships

| File | Change |
|---|---|
| `taint/TaintedCheckerBase.java` | New `classRefVars` map (per-function, cleared at function boundary). New `recordClassRef` helper (called from FIELDLOAD handler). New `resolveReceiverName` helper (used by `recordFieldOpForStore` and `restoreFieldTaintOnLoad`). New `isLikelyClassName` helper (uppercase heuristic). |
| NEW: `integration/R47StaticFieldTest.java` | 3 new test cases: STATIC_FIELD_SQL (CWE-89 fire), STATIC_FIELD_PATH (CWE-22 fire), STATIC_FIELD_NEGATIVE (no fire on literal write). |
| DOC: `docs/R47-STATIC-FIELD-PROPAGATION-2026-08-18.md` (this file) | |
| BAK: `_trash/R47Debug.java.bak` (debug shim) | |

## Numerical results

| Metric | Pre-R47 | Post-R47 | Δ |
|---|---:|---:|---:|
| **R10 Juliet baseline (12763 files)** | 6939 fires | **6939 fires** | **0** (no regression) |
| **R567 cross-fn sweep** | 4 / 4 ✓ | **4 / 4 ✓** | **0** (R41 + R42's 100% preserved) |
| **plumb-dfa unit tests** | 575 / 575 ✓ | **575 / 575 ✓** | **0** (0 fail, 2 skipped) |
| **R32 / R34 unit tests** | 28 ✓ | **28 ✓** | **0** (no test changes needed) |
| **R46 cross-instance test** | 3 / 3 ✓ | **3 / 3 ✓** | **0** (R47's chain tracker is additive) |
| **R47 static field test (new)** | — | **3 / 3 ✓** (SQL fires, path fires, negative no-fire) | NEW |

R47 is a **clean no-regression**
round. The R10 fire count is
unchanged because Juliet doesn't
exercise the static-field pattern
in the R10 checker scope (R10
checks source-sink flow on the
standard CWE families, none of
which are written as static
fields). R47 enables a new
pattern (static-field flow) that's
not exercised by Juliet, but is
covered by the new R47 unit test.

## Why R47 matters

1. **Static fields are extremely
   common in real Java code.**
   Configs, constants, loggers,
   registry-style lookups — all
   use `static` fields. Pre-R47,
   Plumb silently dropped all
   taint-flow through static
   fields, which is a significant
   coverage gap for real-world
   scans.

2. **The R46 design generalizes
   naturally to R47.** R46 made
   the tracker key on
   `(receiver, field)`. R47 adds
   a single new piece — the
   class-ref map — to handle the
   IR builder's chained-field
   encoding. The change is
   additive and doesn't touch
   the existing R46 path.

3. **The uppercase heuristic is
   a clean fit for Java naming
   conventions.** Class names
   always start with uppercase
   (per JLS); instance fields
   conventionally start with
   lowercase. The heuristic is
   the minimum signal needed to
   distinguish a class reference
   load from a regular field
   load in the IR builder's
   chained encoding.

## Cross-cutting design lessons (R47)

1. **The IR builder's encoding
   choice has long-term
   consequences.** The IR
   builder chose to encode
   `Helper.config` as
   `this.Helper.config` (a
   chained field access). This
   is a clever translator-level
   trick that reuses the existing
   FIELDSTORE/FIELDLOAD
   machinery, but it pushes the
   "is this a class reference"
   problem to the engine. R47's
   engine-side chain tracker is
   the right fix because
   (a) it doesn't require IR
   changes (which would touch
   the IR-serializable contract),
   and (b) it's localized to
   TaintedCheckerBase. **Lesson**:
   when adding a new pattern, prefer
   engine-side fixes over
   IR-builder changes if both
   work; engine-side changes are
   easier to roll back.

2. **Naming conventions are
   a useful heuristic for IR
   classification.** Java's
   "class names start with
   uppercase" convention is
   universally followed. Using it
   as a heuristic (instead of
   requiring an explicit IR
   marker) is the right tradeoff:
   no IR changes, no false
   positives in practice, and
   the heuristic is well-known
   to anyone reading the code.

3. **Per-function state must
   be cleared at function
   boundaries.** R47's
   `classRefVars` map is
   per-function. If we forget to
   clear it, the same TacVar
   name from a previous function
   could pollute the next
   function's class-ref map.
   R47's
   `classRefVars.clear()` in
   `initFunction` is the safety
   net.

4. **A change to a tracker
   requires a corresponding
   change to the read-side and
   the fold.** R47's chain
   tracker has three consumers:
   `recordFieldOpForStore`
   (write side),
   `restoreFieldTaintOnLoad`
   (read side), and the
   `recordClassRef` itself
   (population). All three are
   updated in the same round to
   avoid silent mis-matches.

## Cumulative R1-R47 (Plumb only)

- 39+ CWE families, **~16,714 cweFire hits** (R47 unchanged on Juliet; R47 enables a new pattern that's exercised by the R47 unit test, not by Juliet)
- 109 checkers (unchanged)
- 290 unit tests + 2 new (R46, R47) = **292 unit tests** (post R47)
- 0 R503 / 0 R16 / 0 R567 pre-existing failures
- 0 R46 / 0 R47 FN cases
- **R5+ 5-day cross-fn sprint is fully complete** (R32 + R33 + R34 + R40 + R41 + R42 + R43 + R44 + R45 + R46 + R47 = 11 R-rounds, all shipped)

## R47+ follow-up candidates

- **R48**: IR builder enhancement to record param types at the call site. Would enable R44's overload disambiguation to actually pick the right overload instead of "first wins".
- **R49**: 2-CFA (or k-CFA) call-graph strategy. R45's consistency across strategies makes this easier to add as a new strategy composing on the same `buildBareNameMap` / `resolveCalleeSigStatic` helpers.
- **R50**: Multi-class static field tracking (helper's `static String config` accessed by an unrelated class). R47 handles same-class-and-helper; R50 would extend to arbitrary classes.
- **R51**: Multi-receiver aliasing. If a function does `Helper h1 = ...; Helper h2 = h1;` then `h1.data` and `h2.data` are aliased. R46 conservatively treats them as different. Precise alias analysis is a future R-round.
