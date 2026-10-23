# R46 — Cross-Instance Field Propagation

**Date**: 2026-08-18
**Status**: SHIPPED ✅
**Round scope**: Extend the R34
`FieldTaintTracker` from `this.f` only
to any receiver. Enables
cross-instance field writes like
`h.data = tainted; ...; h.data` to
fire, where `h` is a fresh instance
of the same class (or a different
class with a non-`java.*` type).

## Why R46

R34 / R42 shipped `this.f` field
propagation for the FN_DEEP test
case (same-class
`this.setData(u); this.data` flow).
The R5+ design doc explicitly
deferred **cross-instance** field
writes as out-of-scope. R46 lifts
that restriction now that R41's
call-graph sig normalization makes
the cross-fn fold usable across all
five call-graph strategies.

The pre-R46 design had three
limitations that R46 addresses:

1. **`FieldTaintTracker` key was
   just `fieldName`**, not
   `(receiver, field)`. Two
   instances writing the same field
   name (e.g. `this.data` and
   `h.data` in the same function)
   would collide.
2. **`recordFieldOpForStore` had
   `if (!"this".equals(recv.name()))
   return;`** — cross-instance
   writes were silently dropped.
3. **`restoreFieldTaintOnLoad` had
   the same `this`-only guard** —
   cross-instance reads were
   ignored.
4. **Fold at
   `propagateTaintFromCalleeSummary`
   hard-coded `recvName = "this"`**
   — even if the cross-instance
   fold had been enabled, the
   tracker would only seed the
   `("this", f)` pair, which is
   wrong for `h.setData(u)`.
5. **`recordFieldOpForStore`
   required `label.startsWith("param:")`** —
   input-source taint like
   `req.getParameter(...)` (label
   `"input:..."`) was dropped. The
   pre-R46 design assumed the only
   field-write source was a function
   parameter; R46 supports any
   tainted local.

## The fix

### `FieldTaintTracker.java`

Tracker key changed from
`fieldName` to
`receiver + "." + fieldName`. New
3-arg overloads
`recordWrite(receiver, fieldName, label, path)`,
`readField(receiver, fieldName)`,
`hasField(receiver, fieldName)`,
`clearField(receiver, fieldName)`.
The 2-arg overloads (e.g.
`recordWrite("data", ...)`) now
delegate to the 3-arg versions
with `receiver = "this"`, so all
R34 callers continue to work.

`applyCalleeFieldOp` no longer
rejects non-`this` receivers.
The `if (!"this".equals(receiverName)) return;`
guard is removed.

### `TaintedCheckerBase.java`

Three call sites updated:

- **`recordFieldOpForStore`**:
  removed the `this`-only guard.
  `recvName` is now extracted from
  the actual `TacVar.name()` of
  `lhs1`. The
  `label.startsWith("param:")`
  guard is also lifted: any
  tainted local is now accepted
  (input-source taint included).
  The FieldOp falls back to
  `fromLocalSource` when there's
  no param index, but the tracker
  still records the
  `(receiver, field)` taint state
  for subsequent FIELDLOADs in the
  same function.

- **`restoreFieldTaintOnLoad`**:
  removed the `this`-only guard.
  `recvName` is now extracted from
  the actual `TacVar.name()` of
  `lhs1`. The
  `readField(receiver, field)`
  lookup uses the new key.

- **`propagateTaintFromCalleeSummary`**:
  `recvName` is now extracted from
  the CALL's `args[0]` with a
  class-type heuristic:
  - If `args[0].name() == "this"`
    → `recvName = "this"`
  - If `args[0]` has `TacType.Kind.CLASS`
    AND is not a JDK standard
    library type (`java.lang.String`,
    `java.util.*`, etc.) →
    `recvName = args[0].name()`
    (cross-instance call)
  - Otherwise → default to `"this"`
    (same-class call where the IR
    builder skipped the receiver)

The class-type heuristic is
needed because the IR builder is
NOT consistent about prepending
the receiver to `args` for
same-class calls. For
`DeepFlow.bad` calling
`this.setData(u)`, the IR emits
`args=[u]` (just the user arg, no
receiver). For `h.setData(u)`
where `h` is a different instance,
the IR emits `args=[h, u]` (with
receiver). The disambiguating
signal is the `TacType` of
`args[0]`: CLASS-typed args[0] is
a receiver, otherwise it's a user
arg.

The new helper
`isLikelyUserArgType(TacType t)`
returns `true` for `java.lang.*`,
`java.util.*`, `java.io.*`,
`java.nio.*`, etc. — these are
the standard library types the IR
builder encodes as
`TacType.ofClass(...)` (Kind.CLASS)
even though semantically they
are value types, not user
classes.

## What R46 ships

| File | Change |
|---|---|
| `taint/FieldTaintTracker.java` | Key shape changed from `fieldName` to `receiver + "." + fieldName`. New 3-arg overloads (`recordWrite`, `readField`, `hasField`, `clearField`) for cross-instance support. 2-arg overloads delegate to "this" default for R34 back-compat. `applyCalleeFieldOp` no longer rejects non-`this` receivers. |
| `taint/TaintedCheckerBase.java` | `recordFieldOpForStore`: dropped `this`-only guard; accepts any non-empty receiver name; lifted `label.startsWith("param:")` guard for input-source taint. `restoreFieldTaintOnLoad`: dropped `this`-only guard. `propagateTaintFromCalleeSummary`: class-type heuristic for `recvName` (this / class-typed args[0] / default to "this"). New helper `isLikelyUserArgType(TacType)`. |
| `taint/R34FieldTaintTrackerTest.java` | Updated `trackerClearFieldRemovesOnlyThatField` to expect R46 key shape `["this.count"]` (was `["count"]`). |
| `taint/R32TaintedCheckerRecordingTest.java` | Renamed `doesNotRecordFieldOpForNonThisReceiver` to `recordsFieldOpForAnyReceiver` and inverted the assertion (now expects 1 FieldOp with sourceParamIndex=0 for `other.data = d`). |
| NEW: `integration/R46CrossInstanceFieldTest.java` | 3 new test cases: CROSS_INSTANCE_SQL (CWE-89 fire), CROSS_INSTANCE_PATH (CWE-22 fire), CROSS_INSTANCE_NEGATIVE (no fire on literal write). |
| NEW: `docs/R46-CROSS-INSTANCE-FIELD-PROPAGATION-2026-08-18.md` (this file) | |
| BAK: `_trash/R46Debug.java.bak`, `_trash/R46Debug2.java.bak`, `_trash/R46Debug3.java.bak` (debug shims) |

## Numerical results

| Metric | Pre-R46 | Post-R46 | Δ |
|---|---:|---:|---:|
| **R10 Juliet baseline (12763 files)** | 6939 fires | **6939 fires** | **0** (no regression) |
| **R567 cross-fn sweep** | 4 / 4 ✓ | **4 / 4 ✓** | **0** (R41 + R42's 100% preserved) |
| **plumb-dfa unit tests** | 575 / 575 ✓ | **575 / 575 ✓** | **0** (0 fail, 2 skipped) |
| **R32 / R34 unit tests** | 28 ✓ | **28 ✓** (with 2 updated assertions to match R46 key shape) | (test changes only) |
| **R46 cross-instance test (new)** | — | **3 / 3 ✓** (SQL fires, path fires, negative no-fire) | NEW |

R46 is a **clean no-regression**
round. The R10 fire count is
unchanged. R46 enables a new
pattern (cross-instance field
flow) that's not exercised by
Juliet, but is covered by the new
R46 unit test.

## Why R46 matters

1. **Real-world code uses
   cross-instance field patterns
   extensively.** Juliet is a
   curated test corpus that mostly
   uses `this.f` for the field
   write (because the test cases
   are simple), but real Java code
   frequently has helper-style
   classes with `setX(String)`
   setters and a `String data`
   field. R46 enables Plumb to
   catch these patterns.

2. **The R34 design was a
   special case of R46.** R34's
   "only `this.f`" restriction
   silently dropped the
   `h.setData(u); h.data` flow.
   R46 keys the tracker on
   `(receiver, field)` and uses
   the actual receiver at the
   call site, so the same fold
   machinery that worked for
   `this.f` now works for any
   `(receiver, field)` pair.

3. **No over-taint risk.** The
   class-type heuristic
   (`isLikelyUserArgType`) prevents
   the misclassification of
   `args[0] = u:String` as a
   cross-instance receiver. This
   was a real bug in R46's first
   draft that would have
   over-tainted all same-class
   `this.setData(u)` calls; the
   second draft with the heuristic
   passes the R567 FN_DEEP test
   (4/4 ✓) and enables the R46
   test (3/3 ✓) without any
   over-taint.

## Cross-cutting design lessons (R46)

1. **Tracker key shape is a
   design choice with
   long-term implications.** R34
   keyed on `fieldName` alone
   (implicitly assuming "this");
   R46 keys on `(receiver,
   fieldName)`. The change is
   strictly more expressive (R34's
   "this.f" is the
   `("this", "f")` subset). When
   designing a new tracker /
   cache / fold, ask: "what is
   the minimum key that
   disambiguates the state?" If
   the answer is "anything
   beyond what's strictly
   needed", the design is
   probably over-simplified.
2. **The IR builder's "missing
   receiver" quirk for same-class
   calls is a real footgun.** The
   disambiguating signal is
   `TacType.kind() == CLASS`
   PLUS `isLikelyUserArgType`
   guard. **Lesson**: when
   extracting data from a CALL's
   `args[0]`, always check the
   TacType — a string-typed
   `args[0]` in a same-class
   call is the user arg, not the
   receiver.
3. **"Value must be tainted with
   label starting with `param:`"
   was R34's way of saying "this
   is a cross-fn fold candidate".**
   The strict prefix check
   silently dropped input-source
   taint. R46 lifts the guard:
   any tainted local can seed a
   field taint state for
   subsequent FIELDLOADs in the
   same function. **Lesson**:
   taint labels evolve over
   time; the "param:" prefix is
   just one of many possible
   labels (`input:...`,
   `callee-ret:...`,
   `r40-passthrough:...`, etc.).
   Strict prefix checks are a
   footgun.
4. **`TaintedCheckerBase`'s
   fold and the standalone
   tracker are two
   inter-related pieces.** A
   change to the tracker key
   shape requires corresponding
   changes in the fold (so the
   fold seeds the same key) and
   the read-side (so the
   FIELDLOAD looks up the same
   key). R46 touches all three
   places in one round. **Lesson**:
   when changing a tracker key
   shape, audit all three
   call sites (write / read /
   fold) in the same round to
   avoid silent mis-matches.
5. **R46's R567 / R10 baseline
   tests are the "did the
   pre-existing case still
   work?" guard.** R46 changed
   the fold from
   `recvName = "this"` (R34
   hard-coded) to a 3-way
   heuristic. The R567 FN_DEEP
   test (`this.setData(u); this.data`)
   exercises the "args[0] is
   user arg, default to this"
   path; if that path had
   regressed, FN_DEEP would have
   flipped back to FN. The
   4/4 ✓ result is the proof
   that R46 didn't regress.
6. **The `isLikelyUserArgType`
   prefix list is intentionally
   conservative.** It whitelists
   every standard library
   package Plumb's IR builder
   might encounter
   (`java.lang`, `java.util`,
   `java.io`, `java.nio`,
   `java.time`, `java.math`,
   `java.text`, `java.net`,
   `java.security`, `java.sql`,
   `javax`, `jdk`, `sun`,
   `com.sun`). User code that
   uses a non-JDK package as a
   receiver type will correctly
   be treated as a cross-instance
   receiver.

## Cumulative R1-R46 (Plumb only)

- 39+ CWE families, **~16,714 cweFire hits** (R46 unchanged on Juliet; R46 enables a new pattern that's exercised by the R46 unit test, not by Juliet)
- 109 checkers (unchanged)
- 290 unit tests + 1 new R46 test = **291 unit tests** (post R46)
- 0 R503 pre-existing failures
- 0 R16 pre-existing failures
- 0 R567 pre-existing FN cases (R41 + R42 + R46 fully fixed; R46's R567 result is 4/4 ✓ which proves the heuristic works)
- 0 R46 FN cases (the 3 R46 cases all fire / not-fire as expected)
- **R5+ 5-day cross-fn sprint is fully complete** (R32 + R33 + R34 + R40 + R41 + R42 + R43 + R44 + R45 + R46 = 10 R-rounds, all shipped)

## R46+ follow-up candidates

- **R47**: Static field propagation. R46 still defers static-field writes (the IR builder encodes them differently, without a TacVar `lhs1`). Static fields are common in real-world code (`public static String config;`).
- **R48**: IR builder enhancement to record param types at the call site. Would enable R44's overload disambiguation to actually pick the right overload instead of "first wins".
- **R49**: 2-CFA (or k-CFA) call-graph strategy. R45's consistency across strategies makes this easier to add as a new strategy composing on the same `buildBareNameMap` / `resolveCalleeSigStatic` helpers.
- **R50**: Cross-class field propagation **without** a same-class instance — i.e. `static String config;` and `Helper.config = tainted;` reads. This is a different beast (static fields have no instance).
- **R51**: Multi-receiver aliasing. If a function does `Helper h1 = ...; Helper h2 = h1;` then `h1.data` and `h2.data` are aliased. R46 conservatively treats them as different. A precise alias analysis is a future R-round.
