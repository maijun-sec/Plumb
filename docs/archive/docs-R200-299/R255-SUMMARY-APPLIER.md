# R255 — SummaryApplier wires MethodSummary into the DFA engine

## Why

R254 produced
[`MethodSummary`](../plumb-dfa/src/main/java/org/plumb/dfa/unified/summary/MethodSummary.java)
objects — a clean
inter-procedural
description of every
function's return-value
shape and side
effects. R255 wires
that infrastructure
into the
[`DfaEngine`](../plumb-dfa/src/main/java/org/plumb/dfa/unified/engine/DfaEngine.java)
and exposes a
[`SummaryApplier`](../plumb-dfa/src/main/java/org/plumb/dfa/unified/summary/SummaryApplier.java)
helper that caller-side
checkers invoke at
every `CALL`
instruction.

## What R255 added

1. **Two new built-in
   `FactKind`s** in
   [`BuiltinFactKinds`](../plumb-dfa/src/main/java/org/plumb/dfa/unified/fact/BuiltinFactKinds.java)
   - `plumb.call.return-value-folded`
     — "this operand's
     value was folded
     at the call site
     from the callee's
     MethodSummary".
     `PROPAGATED_BY_DECL`.
   - `plumb.call.side-effect-recorded`
     — "this call has
     side effects
     recorded by the
     callee's
     MethodSummary".
     `PROPAGATED_BY_DECL`.
   - The built-in count
     goes from 13 to
     15.

2. **`MethodSummaryCache`
   on `AnalysisContext`**
   — a per-engine cache
   parallel to the
   existing
   `FunctionSummaryCache`.
   Initialised in the
   `AnalysisContext`
   ctor; exposed via
   `ctx.methodSummaryCache()`.

3. **`DfaEngine` auto-
   populates the cache**
   in the RPO loop:
   after every function
   is analysed and the
   existing
   `FunctionSummary`
   is merged, the
   engine calls
   `buildAndStoreMethodSummary`
   to build a
   `MethodSummary`
   from the function's
   IR and put it in
   the cache. The
   builder is the
   same R254
   `MethodSummaryBuilder`
   used by unit tests;
   it's independent of
   the per-checker
   summary pipeline.

4. **`SummaryApplier`
   helper** — a small,
   static, no-state
   utility that
   caller-side checkers
   invoke at every
   `CALL`:
   - `apply(call, ctx, args)`
     — looks up the
     callee in the
     cache, folds the
     return value, and
     records the side
     effect. Returns
     the applied
     summary (or
     `null` for an
     unresolvable
     callee).
   - `applyReturnValue(call, s, ctx, args)`
     — writes a
     `RETURN_VALUE_FOLDED`
     fact on the
     call's dest.
   - `applySideEffect(call, s, ctx)`
     — writes a
     `SIDE_EFFECT_RECORDED`
     fact on the call
     site (or the dest
     if the call has
     one).

5. **`SummaryApplier`'s
   encoding** is a
   stable, string-based
   protocol so checkers
   can match on the
   fact's `value`
   without coupling to
   the builder's
   internal types:
   - `void` — `RETURN_VOID`
   - `const:<literal>` — `RETURN_CONST`
   - `param:<i>[:<arg>]` — `RETURN_PARAM`
   - `this-field:<name>` — `RETURN_THIS_FIELD`
   - `static-field:<FQCN.field>` — `RETURN_STATIC_FIELD`
   - `local:<name>` — `RETURN_LOCAL_VAR`
   - `binop:<op>:<lhs>:<rhs>` — `RETURN_BINARY_OP`
   - `cond:<trueArm>:<falseArm>` — `RETURN_CONDITIONAL`
   - `new:<type>` — `RETURN_NEW_INSTANCE`
   - `top` — `UNKNOWN` (R254 fallback)
   - For side effects: `pure`, or a
     comma-joined set of
     `writes-this:<name>`,
     `writes-param:<i>`,
     `writes-static:<FQCN.field>`,
     `calls:<sig>`, `may-throw`.

## What's not in R255 (deferred)

- **No checker
  consumes
  `RETURN_VALUE_FOLDED`
  yet.** R255 only
  wires the
  infrastructure.
  A follow-up R256+
  needs to teach
  e.g. the
  `LiteralBooleanConditionChecker`
  to read
  `const:true` /
  `const:false` and
  short-circuit the
  analysis on a
  branch whose
  condition is
  foldable.
- **No `SideEffect`
  invalidation
  yet.** A checker
  that wants to
  invalidate read
  state after a
  `WRITES_THIS_FIELD`
  call has to do it
  itself by reading
  the
  `SIDE_EFFECT_RECORDED`
  fact. R257+ can
  build a generic
  invalidator that
  walks the fact
  bag and clears
  the relevant
  read state.

## Pitfalls fixed during R255

- **`FunctionSignature.parse`
  returns `null` for
  unparseable
  signatures** (e.g.
  Juliet's
  `bad():void`,
  `main(String[]):void`,
  `<clinit>():void`)
  rather than
  throwing. The
  initial
  `parseSignature`
  helper only caught
  `RuntimeException`
  and was letting the
  `null` through to
  the
  `MethodSummary.<init>`
  `requireNonNull`
  guard, causing
  NPEs on every
  Juliet function.
  Fixed by adding a
  null check after
  the catch in both
  `MethodSummaryBuilder`
  and
  `DfaEngine.parseFnSignature`.
- **`FactBag.add(Fact)`
  not
  `add(TacOperand, Fact)`.**
  The single-arg
  overload is the
  correct API; the
  two-arg shape was
  a misread of the
  API.

## Test coverage

```
R255SummaryApplierTest: 12/0/0/0
- 7 RETURN_* fold tests (const/param/this-field/static-field/new/void/unknown)
- 1 missing-callee fallback test
- 2 side-effect tests (records for impure, silent for pure)
- 1 unresolvable callee (null calleeSig) test
- 1 cache-integration sanity test
```

The full
`plumb-dfa`
suite is now
**433/0/0/0**, with
R255 contributing
12 new tests and zero
regressions.

## R256 (next)

R256 will teach
specific checkers
to consume the
`RETURN_VALUE_FOLDED`
fact. The highest-
value target is the
branch-condition
checkers
(`LiteralBooleanCondition`,
`AssignmentInConditional`,
`EmptyIfBlock`,
`UnusedLabel`,
`DeadCodeAfterReturn`),
all of which can
short-circuit on
`const:true` /
`const:false` /
`param:0` after a
fold. A real-Juliet
sweep is the natural
validation step.
