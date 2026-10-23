# Support status - 04-polymorphic-callgraph

**Dispatch is sound and type-precise.** Interface / abstract / diamond hierarchies resolve; overloads are filtered by parameter type, so an `int` argument no longer binds to a `String` overload, and an arity-2 overload in the same class resolves on its own key.

## Measured

- True positives: **TP 9/9**
- True negatives: **TN 2/2**
- Scan mode: per-file, callgraph strategy: `global`.

## Outstanding cases

none

## Known limitations

none

Full per-case detail: `RESULT.json`. Expectations: `EXPECTATIONS.json`.
