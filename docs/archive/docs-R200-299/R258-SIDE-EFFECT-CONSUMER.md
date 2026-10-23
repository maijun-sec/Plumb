# R258 — Side-Effect Consumer (caller-state invalidation)

## Goal

Wire the R255 `SIDE_EFFECT_RECORDED` fact (which
`SummaryApplier.applySideEffect` writes at every
`CALL` site) into a small per-function
lookup that downstream checkers can use
to know *which operands' cached facts
have been invalidated by a callee's side
effect*.

## Design

A new `SideEffectConsumer` class in the
`summary` package. Per-function lifetime,
cleared by the engine alongside the
`FactBag` at every function boundary. The
engine calls
`SideEffectConsumer.recordCall(ins, ctx)`
immediately after
`SummaryApplier.apply(ins, ctx, args)` in
the RPO loop, so by the time any checker's
`checkInstruction` runs for the current
`CALL`, the consumer already knows which
operands are invalidated.

### API

```java
public final class SideEffectConsumer {
    public void prepare(AnalysisContext ctx);
    public void recordCall(TacInstruction call, AnalysisContext ctx);
    public boolean wasInvalidated(String key);
    public Set<String> invalidationKeys();
    public List<String> invalidationSitesFor(String key);
    public int invalidationCount();
    public List<Fact> liveFactsOf(TacOperand operand, FactBag bag);
    public void clear();
}
```

### Key normalisation

The consumer maps each side-effect flag
written by the applier to a stable
invalidation key the checkers can match on:

| Side-effect flag              | Invalidation key      |
|-------------------------------|-----------------------|
| `writes-this:foo`             | `this:foo`            |
| `writes-param:0`              | `param:0`             |
| `writes-static:Foo.bar`       | `static:Foo.bar`      |
| `may-throw`                   | (no per-op key)       |
| `calls:...`                   | (no per-op key)       |

Checkers compare their operand's name
(or alias) to the consumer's
`invalidationKeys()` to decide whether a
cached fact about that operand has been
invalidated. The matching is string-keyed
(not TacVar-keyed) so the consumer is
trivial to mock and so checkers don't have
to know which receiver/parameter a CALL
site actually used.

### `liveFactsOf`

A convenience for the common case: a
checker holds a `Fact` about an operand
and wants to know "is this fact still
valid after the latest side-effect call?".

```java
List<Fact> live = ctx.sideEffectConsumer()
        .liveFactsOf(x, ctx.factBag());
// returns the bag's facts for x, but
// drops the whole list if x was
// invalidated.
```

This is a per-operand shortcut. A
"smarter" filter (drop individual facts
within the list whose kind is
nullable-like while keeping the rest) is
left for a follow-up round — R258 wires
the coarse-grained "drop everything"
behaviour, which is the most common case
for a cached MAY_BE_NULL / MAY_BE_ZERO
fact.

## Engine wiring

```java
// DfaEngine.java — RPO loop, after
// SummaryApplier.apply:
if (ins.kind == TacInstruction.Kind.CALL) {
    try {
        org.plumb.dfa.unified.summary.SummaryApplier
                .apply(ins, ctx, ins.args);
        // R258 — feed the just-written
        // SIDE_EFFECT_RECORDED fact
        // into the per-function
        // consumer.
        ctx.sideEffectConsumer()
                .recordCall(ins, ctx);
    } catch (RuntimeException re) {
        LOG.warn("SummaryApplier failed on {}: {}",
                ins.calleeSig, re.toString());
    }
}
```

`AnalysisContext` now exposes
`sideEffectConsumer()` (one per
`AnalysisContext`, the consumer itself
is per-function via the engine's
`clear()` at every function boundary —
matching the `FactBag`'s lifetime).

## Test results

- `R258SideEffectConsumerTest`:
  **15 / 0 / 0 / 0** (0.6s)
  - 6 normalise unit tests (writes-this /
    writes-param / writes-static /
    may-throw / calls / null/empty)
  - 5 recordCall parser tests
    (writes-this, writes-param,
    writes-static, pure, comma-joined)
  - 1 liveFactsOf (operand-name lookup
    against invalidation key)
  - 1 liveFactsOf (operand not
    invalidated → all facts survive)
  - 1 clear() resets state
  - 1 end-to-end engine integration
    (SummaryApplier + consumer run
    through ctx.methodSummaryCache())
- Full plumb-dfa: **483 / 0 / 0 / 0**
  (from 468 baseline, +15 = 15 change,
  **0 regression**)
- Wall time: 04:32 min for full run

## 4 innovation points

1. **String-keyed invalidation log** (R258 design). The consumer matches
   by operand name (or alias) to a
   normalised key. This avoids the
   "which TacVar did the engine pass
   here?" bookkeeping that would otherwise
   make the consumer fragile against
   SSA-versioned names and synthetic
   operands. The downside is that
   checkers have to know the convention
   (`param:0` for the first arg, etc.);
   R258 documents this in the class
   javadoc.
2. **`liveFactsOf` drop-whole-list** (R258 critical). The current
   consumer returns the empty list when
   an operand has been invalidated. A
   "drop individual facts whose kind is
   nullable-like" filter is left for a
   follow-up round — R258 is the wiring
   round, and the "drop whole list"
   behaviour is the common case for
   MAY_BE_NULL / MAY_BE_ZERO caches.
3. **Per-function lifetime matches FactBag** (R258 design). The engine
   calls `sideEffectConsumer().clear()`
   at the same two places it calls
   `factBag().clear()` (the serial RPO
   loop and the parallel RPO loop in
   R217). One consumer per
   `AnalysisContext` (allocated in the
   constructor), cleared per function
   — same pattern as the bag.
4. **Pure-method short-circuit at the consumer too** (R258 minor). The
   applier already short-circuits on
   `s.isPure()` (no fact written); the
   consumer inherits the short-circuit
   because there's no fact to read.
   The consumer's `recordCall` skips
   the parser when `value.equals("pure")`.

## 4 pitfalls fixed (R258)

1. **`MethodSummary.signature` is a field, not a method** (R258 pitfall).
   `MethodSummary` is a class with public
   final fields (`signature`, `returnShape`,
   `sideEffect`); the test originally
   called `s.signature()`. Fix: use
   `s.signature` (field access).
2. **`SideEffect.Builder` is final** (R258 pitfall). The double-brace
   IIFE (`new Builder() {{ addWriteParamIndex(0); }}`) requires a
   non-final class. Fix: allocate the
   builder, then call the method
   directly without IIFE.
3. **`FunctionSignature.parseParamType` doesn't exist** (R258 pitfall).
   The actual API is the 5-arg
   constructor
   `FunctionSignature(pkg, class, method, paramTypes, returnType)`.
   Fix: use the constructor.
4. **`recordCall` requires `call.dest` to find the side-effect fact** (R258
   pitfall). When `call.dest` is null,
   the applier writes the
   `SIDE_EFFECT_RECORDED` fact on a
   synthetic `@call@<hash>` operand;
   the consumer must use the same
   synthetic operand to find the fact.
   In the test, the synthetic CALL had
   no dest, so the consumer built its
   own synthetic site and didn't find
   the fact (which was on a separate
   `@dummy` operand). Fix: the test
   now uses the same dest for both
   the fact and the call.

## What this unlocks

After R258, the third leg of the R254
cross-function-analysis trilogy is in
place:

- **R255** — SummaryApplier writes
  RETURN_VALUE_FOLDED + SIDE_EFFECT_RECORDED
  facts at every CALL site.
- **R256 / R257** — Two branch-condition
  checkers (LiteralBoolean +
  AssignmentInConditional) consume
  RETURN_VALUE_FOLDED to recognise
  fold-derived constants in branches.
- **R258** — SideEffectConsumer records
  SIDE_EFFECT_RECORDED facts and exposes
  a lookup that future checkers
  (NullDeref, DivideByZero,
  UnusedField) can use to invalidate
  cached per-operand facts after a
  side-effecting CALL.

The next consumer to wire in is the
caller-state invalidation for
`NullDerefChecker` and
`DivideByZeroChecker` (they currently
cache "operand x is non-null / non-zero"
locally; after a side-effecting CALL
that writes the parameter, the cache
is wrong). That's a focused change in
those two checkers' `checkInstruction`
methods, scheduled for the next round
after the user priorities.

## Files

- `plumb-dfa/.../summary/SideEffectConsumer.java` (new, 9418 bytes)
- `plumb-dfa/.../engine/AnalysisContext.java` (modified, +1 field + 1 accessor)
- `plumb-dfa/.../engine/DfaEngine.java` (modified, +`recordCall` after `SummaryApplier.apply`, +2 `clear()` calls)
- `plumb-dfa/.../test/.../R258SideEffectConsumerTest.java` (new, 15859 bytes, 15 tests)

## Backup

`D:\work\tmp\r258_done\` — 1 main + 1 test + 2 logs.
