# R254 — Inter-procedural method summary (return shape + side effect)

## Why

R253 (project-root mode)
gives the engine a way to
look up cross-file
references — class
locations, static-field
literals. R254 turns
that into a usable
inter-procedural
analysis primitive: a
**method summary** that
captures "what does
calling this method
look like at the call
site?".

The summary answers
two questions the
engine asks at every
`CALL`:

1. **Return value shape**
   — is the return
   value a literal, a
   parameter pass-through,
   `this.field`, a
   static field, a
   `new` instance, a
   binary-op of two
   operands, a
   conditional, or
   unknown?
2. **Side effects** —
   does the method
   write any
   `this.field`,
   mutate any
   parameter, write
   any static field,
   call any other
   method, or may it
   throw?

The engine consults
the summary at the
call site and uses
the shape to
fold the
return value into a
known constant /
parameter reference,
and uses the
side-effect set to
invalidate
caller-side read
state.

## Why inter-procedural, not inline

R254 was originally
sketched as "method
body inlining" — copy
the callee into the
caller's IR at the
call site. That's a
common shortcut in
toy analyses but it
blows up the IR size
exponentially, doesn't
compose with the
existing
**R226 VTA + R240
1-CFA** call graph
machinery, and
destroys function
boundaries. The
inter-procedural
**summary-based**
approach — the
textbook answer, the
one R226 / R236 already
use for points-to —
sits on top of the
call graph and the
existing summary infra
without doubling any
work.

## What's in the shape

Ten
[`ReturnShape.Kind`](../plumb-dfa/src/main/java/org/plumb/dfa/unified/summary/ReturnShape.java)
values cover everything
the engine can fold
without re-analysing
the callee:

| Kind | Callee body pattern | Engine action |
|------|---------------------|---------------|
| `RETURN_VOID` | bare `return;` or no explicit return | mark call as "no return" |
| `RETURN_CONST` | `return true / 5 / "x" / null` | bind return to const |
| `RETURN_PARAM` | `return p0` (alias-chased) | bind return to caller arg |
| `RETURN_THIS_FIELD` | `return this.field` | bind return to `receiver.field` |
| `RETURN_STATIC_FIELD` | `return Helper.MAX` | bind return to `Helper.MAX` (consult `StaticFieldResolver` for value) |
| `RETURN_LOCAL_VAR` | `x = f(); return x;` (chaseable) | chase the def; if it's a `const`, fold to that |
| `RETURN_BINARY_OP` | `return a + b` | fold if both operands are foldable |
| `RETURN_CONDITIONAL` | `if (b) return true; else return false;` | return is `cond ? true : false` (disjunctive) |
| `RETURN_NEW_INSTANCE` | `return new Foo();` | fresh allocation site; escape-analysis-trackable |
| `UNKNOWN` | everything else | return value = TOP; state preserved (R254 fallback) |

## What's in the side effect

| Bucket | Detection | Use |
|--------|-----------|-----|
| `writesThisFields` | `FIELDSTORE` on `this` | caller reads to this.field → re-load |
| `writesParamIndices` | `ARRAYSTORE` on a param alias, or `FIELDSTORE` through a param alias | caller state through that param may have changed |
| `writesStaticFields` | `FIELDSTORE` on a class name | global state change |
| `callsMethods` | every `CALL` callee signature | summary chain (R255) |
| `mayThrow` | `THROW` instruction, or `CALL` to a `throws`-declared method | exception-path-aware checkers |
| `isPure()` | all five above empty | method can be re-evaluated freely |

## Files

```
plumb-dfa/src/main/java/org/plumb/dfa/unified/summary/
  ReturnShape.java              (new) — shape enum + payload + builder
  SideEffect.java               (new) — side-effect record + mutable builder
  MethodSummary.java            (new) — combine signature + shape + sideEffect
  MethodSummaryBuilder.java     (new) — IR → summary
  MethodSummaryCache.java       (new) — bounded LRU keyed by FunctionSignature
```

The cache honours the
caller's `maxEntries`
(floor at 1) so test
configurations that
want a tiny cache work
without surprising
behaviour. On a miss
for an unresolvable
callee the cache
installs a sentinel
`UNKNOWN` summary —
this is the
**"return value = TOP,
state preserved"**
fallback the design
call chose.

## Algorithm (builder)

Path-insensitive
single-pass over the
IR:

1. Collect every
   `RETURN` instruction
   up front.
2. Build a `lastDef`
   map: for every
   instruction with a
   `TacVar` dest, record
   the latest defining
   instruction per
   variable name.
3. For each `RETURN`:
   - `lhs1` is `Const` →
     `RETURN_CONST`.
   - `lhs1` is `TacVar`
     → chase through
     `MOVE` aliases
     (cycle-guarded) →
     `RETURN_PARAM` (if
     alias of a
     parameter),
     `RETURN_THIS_FIELD`
     (last def is
     `FIELDLOAD` on
     `this`),
     `RETURN_STATIC_FIELD`
     (last def is
     `FIELDLOAD` on a
     class-named
     receiver),
     `RETURN_NEW_INSTANCE`
     (last def is
     `NEW`),
     `RETURN_BINARY_OP`
     (last def is
     `BINOP`),
     `RETURN_LOCAL_VAR`
     (any other def).
4. If all returns
   classify the same
   way, that is the
   shape. Special case:
   two boolean-const
   returns with distinct
   values → `RETURN_CONDITIONAL`.
5. Walk the IR once
   more to collect
   side effects:
   `FIELDSTORE` on
   `this` (write this
   field), `FIELDSTORE`
   on a class name
   (write static
   field), `ARRAYSTORE`
   on a parameter alias
   (write through
   parameter), `CALL`
   (record callee for
   `callsMethods`),
   `THROW` (set
   `mayThrow`).

## What's wired in R254

- The
  `MethodSummary` /
  `Builder` / `Cache`
  triad is in place and
  unit-tested. R255
  will add the
  `applySummary`
  hook into the
  engine's `CALL`
  resolution phase.
- The shape covers
  the ten
  classifications.
- The side effect
  covers all five
  buckets.
- The cache honours
  LRU eviction and
  thread-safe
  install / lookup.

## What's NOT in R254 (deferred)

- **`applySummary` at
  the engine's
  `CALL` site** —
  deferred to R255.
  The summary infra
  is in place; the
  engine doesn't
  consult it yet.
- **Context-sensitive
  summaries** (keyed
  on `CallSiteContext`
  from R243) — R254
  first cut is
  context-insensitive
  per design call.
  R255+ can wrap
  `cache.get(...)` in
  a per-call-site
  layer.
- **Recursive /
  self-recursive
  methods** — the
  builder doesn't
  recurse into
  callees. The
  summary reports
  the callee name
  in `callsMethods`
  so the engine can
  chase it later;
  the builder itself
  is a single pass
  per function.
- **Conditional /
  multi-return
  methods beyond the
  bool-const
  two-arm
  pattern** —
  everything else
  returns
  `UNKNOWN` and
  defers to the
  R254 fallback
  (TOP, state
  preserved).

## Test coverage

```
R254MethodSummaryBuilderTest: 26/0/0/0
- 10 RETURN_* shape tests
- 5 side-effect bucket tests
- 7 cache behaviour tests
- 4 misc / edge cases
```

The full
`plumb-dfa`
suite is now
**421/0/0/0**, with
R254 contributing
26 new tests and zero
regressions.

## R255 (next) — wire into the engine

R255 will introduce
`SummaryApplier` /
`applySummary` in the
DFA engine's `CALL`
resolution phase.
The applier:

1. Resolves the
   callee via
   `CallGraphStrategy`
   (R226 VTA / R240
   1-CFA).
2. Looks up the
   summary in the
   per-engine
   `MethodSummaryCache`.
3. Applies the
   `ReturnShape` to
   fold the call's
   return-value
   symbolic state.
4. Applies the
   `SideEffect` to
   invalidate
   caller-side read
   state.

The integration
inherits the R226 VTA
virtual-dispatch
machinery and the
R236
`applyCalleeSummary`
entry point — R254
slots in alongside
those, not on top of
them.
