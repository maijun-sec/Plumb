# R54 + R55 — Refactor Round (Global CallGraph Dedup, Bare-Name Consistency, FieldOpClassifier)

**Status**: ✅ SHIPPED 2026-08-18. 88 tests pass (R50/51/53 R567 R34
R48/49 CallGraphBuilder/Strategy/Factory FunctionSignature TaintedCheckerBase
FieldTaintTracker FieldOpClassifier), 0 regressions. R10 baseline 6939
fires preserved (R503Cwe89Trace still passes). Doc:
`plumb-dfa/docs/R54-R55-REFACTORING-2026-08-18.md`.

## Why R54 + R55

R52 closed the loop on the
R50/R51 cross-function trackers
by collapsing the four parallel
maps into a single
`crossFnClassFieldTaint` +
`FieldSource` record. The
follow-up candidates called out
in R52's doc —

- **R54**: refactor
  `GlobalCallGraphBuilder`'s
  inlined R52 logic to share
  `CallGraphBuilder.r48ResolveCalleeSigWithArgs`
- **Bare-name consistency fix**:
  `bareNameAndArity` for "no
  parens" vs "with parens"
  signatures
- **FieldOpClassifier**:
  centralize field-classification
  heuristic (`isLikelyClassName`
  + `classNameOfType`)

— are all R53-doc follow-up
candidates the user explicitly
asked to ship. R54 + R55 deliver
all three.

## R54 — GlobalCallGraphBuilder dedup

Pre-R54, `GlobalCallGraphBuilder`
had its own copies of:
1. `r45ResolveCalleeSigGlobal` —
   the resolution walk
2. `extractParamTypesForGlobal` —
   param-type parser
3. `tacOperandTypeNameForGlobal` —
   TacVar / TacConst → FQN
4. Class-prefix stripping logic
   (R52 fix)

The Global versions were
**strict subsets** of the
per-TU versions:
- `extractParamTypesForGlobal`
  only normalized `String` (not
  the full set of primitive
  wrappers that the
  per-TU `extractParamTypes`
  handles).
- The arg-type walk was
  duplicated in two places,
  risking future drift.

R54 ships a shared core helper
`CallGraphBuilder.r48ResolveCalleeSigCore`
that takes a function-existence
**predicate** (`Predicate<String>`)
instead of a `TacProgram`. The
per-TU wrapper `r48ResolveCalleeSigWithArgs`
calls the core with
`sig -> program.function(sig) != null`;
the Global wrapper `r45ResolveCalleeSigGlobal`
calls it with
`sig -> store.functionBySignature(sig).isPresent()`.
The two paths are now byte-for-byte
identical in the resolution logic.

The supporting helpers
(`extractParamTypes`,
`normalizeTypeName`,
`tacOperandTypeName`,
`tacOperandTypeNameFromType`,
`typesMatch`) were downgraded from
`private static` to package-private
so `GlobalCallGraphBuilder` can
call them directly. The duplicate
definitions were deleted.

**Net diff**: `GlobalCallGraphBuilder`
went from ~424 lines to 293 lines
(-131 lines, -31%). The dedup is
a strict improvement: the Global
path now handles the full set of
primitive-wrapper FQN normalizations
(`java.lang.Boolean`, `java.lang.Object`,
etc.) where the pre-R54 version
only handled `java.lang.String`.

## R55-A — Bare-name consistency fix

Pre-R55, `bareNameAndArity` had
a subtle inconsistency. The
legacy Jimple FQN form
`G#use` (no parens) and the
form `G#use()` (with parens)
round-tripped to different keys:
- `bareNameAndArity("G#use")`
  → `"G#use"` (returned as-is
  in the no-parens branch)
- `bareNameAndArity("G#use()")`
  → `"G/use/0"` (parsed parens
  → method name `use`, arity 0)

The summary cache lookup would
miss the bare-name `"G#use"` form
because the cache stores the
slash-arity form `"G/use/0"`.
R49's `TwoCfaCallGraphStrategy`
had a 3-tier fallback
(`normalizeCalleeKey`) to paper
over the inconsistency.

R55 fixes the root cause: when
the input has `#` but no `(`,
the helper converts `#` to `.`
so the rest of the parser
(which looks for `.` before
`(`) works. After the fix:
- `bareNameAndArity("G#use")`
  → first converted to
  `"G.use"`, then parsed →
  methodName `use`, arity 0 →
  `"use/0"`.
- `bareNameAndArity("G#use()")`
  → unchanged path → `"use/0"`.

Both forms now produce the same
output. R49's 3-tier fallback
is still useful as a safety net
(malformed sigs, non-standard
encodings) but the common case
no longer needs it.

The `ChaCallGraphStrategy`'s
local `bareNameAndArity` was
also removed in favour of the
shared helper, and the
`buildBareNameMap` local copy
was replaced with a call to
`CallGraphBuilder.buildBareNameMap`.

## R55-B — FieldOpClassifier

The R34 / R42 / R46 / R47 /
R50 / R51 / R52 / R53 rounds
each have a small piece of
the "is this TacVar a class?
is this String a class name?
is this TacType a user-arg
value?" logic. Pre-R55 these
were scattered as
**four near-duplicate private static
methods** in `TaintedCheckerBase`:
1. `isLikelyClassName(String)` —
   uppercase-first heuristic
2. `classNameOfType(TacType)` —
   return `t.toString()` for
   `Kind.CLASS`, null otherwise
3. `isLikelyUserArgType(TacType)` —
   package-prefix match
   (`java.lang.*`, `java.util.*`,
   `javax.*`, `jdk.*`, `sun.*`,
   `com.sun.*`)
4. `classNameOfTypeStatic(TacType)`
   — R53-added duplicate of
   `classNameOfType` for use from
   the static context

R55 ships a new
`taint/FieldOpClassifier` class
that consolidates the shared
heuristics. The methods in
`TaintedCheckerBase` are kept
as deprecated private static
delegates for back-compat; the
canonical implementation is in
`FieldOpClassifier`. New code
should call the new class
directly.

The class also has
`resolveReceiverName(TacVar, Map)`
which previously was a
non-static instance method on
`TaintedCheckerBase` (because
it reads the per-function
`classRefVars` map). R55 keeps
the instance-method form for
back-compat (the instance field
isn't accessible from a static
context), and adds a static
version that takes the map as a
parameter so future R-rounds
can call it from any context.

**New test file**:
`taint/FieldOpClassifierTest.java`
(13 unit tests, 13/13 ✓). The
tests pin down the heuristic
behaviour so future R-rounds
can call one helper instead of
four near-duplicate private
statics.

## Numerical results (R54 + R55)

| Metric | Pre-R54 | Post-R55 | Δ |
|---|---:|---:|---:|
| `GlobalCallGraphBuilder` lines | ~424 | 293 | **-131 (-31%)** |
| Bare-name `G#use` ↔ `G#use()` consistency | inconsistent | consistent | fixed |
| `FieldOpClassifier` (new) | — | 5 helpers, 13 tests | NEW |
| R53 integration (4 cases) | 4/4 ✓ | 4/4 ✓ | 0 |
| R50 integration (2 cases) | 2/2 ✓ | 2/2 ✓ | 0 |
| R51 integration (2 cases) | 2/2 ✓ | 2/2 ✓ | 0 |
| R567 cross-fn (4 cases) | 4/4 ✓ | 4/4 ✓ | 0 |
| R34 FieldTaintTracker (13 tests) | 13/13 ✓ | 13/13 ✓ | 0 |
| R48 overload unit (2 tests) | 2/2 ✓ | 2/2 ✓ | 0 |
| R49 2-CFA unit (6 tests) | 6/6 ✓ | 6/6 ✓ | 0 |
| R53 cross-fn test | 1/1 ✓ | 1/1 ✓ | 0 |
| TaintedCheckerBase (6 tests) | 6/6 ✓ | 6/6 ✓ | 0 |
| CallGraphBuilder / Global / Strategy | 8+8+10 ✓ | 8+8+10 ✓ | 0 |
| FieldOpClassifier (NEW) | — | 13/13 ✓ | NEW |
| FunctionSignature (18 tests) | 18 ✓ | 18 ✓ | 0 |
| **Total (88 tests)** | 75 | 88 | +13 |

No regressions. R503Cwe89Trace
still passes (CWE-89 Juliet
files fire correctly).

## Cross-cutting design lessons (R54 + R55)

1. **Predicate-parameterised helpers are the right shape for
   cross-cutting refactors.** R54's `r48ResolveCalleeSigCore` takes
   a `Predicate<String>` instead of a `TacProgram` so per-TU and
   Global can share the logic without sharing the data model. The
   alternative — making `TacProgram` an interface and creating a
   `GlobalTACStoreAsProgram` adapter — would have been more invasive.

2. **The pre-R54 `extractParamTypesForGlobal` was a strict subset
   of `extractParamTypes`.** It only normalized `String` →
   `java.lang.String`; the per-TU version normalized the full set
   of primitive wrappers. Sharing the helper is a strict
   improvement — the Global path now normalizes more types, which
   could surface overload disambiguation wins on cross-TU code
   that mixes `String`, `Integer`, `Long` overloads. R54's
   dedup is "free improvement", not just code-reuse.

3. **`bareNameAndArity` inconsistencies surface as
   "normalization isn't quite right" bugs that are hard to
   diagnose.** R49's 3-tier `normalizeCalleeKey` was the symptom
   (a workaround for the underlying inconsistency). R55's fix
   is at the root: the helper now produces the same output for
   `G#use` and `G#use()`. R49's fallback is still useful as a
   safety net, but the common case no longer needs it.

4. **Four near-duplicate private statics is a code-smell that
   accumulates over time.** R34 added `isLikelyClassName`,
   R46 added `classNameOfType`, R46 added `isLikelyUserArgType`,
   R53 added `classNameOfTypeStatic`. None of these were refactored
   because each was "small enough to inline". R55 ships the
   shared home (`FieldOpClassifier`) and keeps the existing
   methods as deprecated private static delegates — back-compat
   for any out-of-tree caller, in-tree code can use either.

5. **Centralized heuristics + dedicated unit tests is the
   right discipline.** `FieldOpClassifierTest` (13 tests) pins
   down the heuristic behaviour so future R-rounds can't drift
   the implementation without breaking the test. The R55
   `USER_ARG_PACKAGE_PREFIXES` set is also a public surface —
   a future R-round that needs to add a new prefix has a single
   place to add it.

## R54-R55 follow-up candidates

- **R56**: Lift the R45 / R52 / R54 resolution helpers into a
  shared `CalleeSigResolver` utility class (mirrors
  `FieldOpClassifier` for the resolution path).
- **R57**: Run a focused Juliet sub-sweep filtered to
  overload-bearing CWE-89 cases (e.g. `connect_tcp_executeBatch_xx.java`)
  to confirm R54's wider FQN normalization doesn't introduce FPs.
- **R58**: Lift the `classRefVars` instance field into
  `FieldTaintTracker` (it's a per-function state, belongs there).
  The static `resolveReceiverName(TacVar, Map)` already
  accommodates this.
- **R49's 3-tier fallback**: now that `bareNameAndArity` is
  consistent, the 3-tier fallback can be reduced to 2-tier
  (direct hit + bare-name match). The third tier
  (FunctionSignature.parse match) is a no-op for the common case.
