# R146 — Cross-Function Chain-Var Field Propagation (R141b Follow-Up)

**Status:** Done. 6/6 new tests pass. 235 dfa tests / 0 fail / 1 documented skip (zero regression).
0 IR / 0 TAC / 0 new checker changes — pure inter-procedural chain-walk extension.

## Motivation

R141b (`/docs/R141-SUMMARY-CACHE-SCALING.md`) wired intra-procedural
field-chain walks so `o.a.b = X; sink(t1.b)` fires within a single
function. R145 extended fieldMap propagation across function
boundaries for direct (non-chained) field writes. R146 closes
the gap for the chained case across function boundaries: when
a helper does `String t1 = param.a; param.a.b = X;` and the
caller does `String t1' = arg.a; String t2 = t1'.b; sink(t2);`,
the chain walk in the caller sees the taint.

## What R146 Includes

### R146a — `TaintFact.withField` / `with` preserve `fieldChain`

`plumb-dfa/.../taint/TaintFact.java`

Pre-R146, `withField` and `with` (the two primary mutation
primitives) used the 2-arg `TaintFact` constructor, which
defaults `fieldChain` to `Map.of()`. This silently dropped
the chain side table on every baseMap/fieldMap update. The
R141b intra-procedural SINK analysis was unaffected because
the SINK runs in the same TaintFact flow where the chain was
set; but R146 reads the *exit* TaintFact after `doWorklist`
returns, and by then the chain is gone.

R146 changes `withField` and `with` to use the 3-arg
constructor and pass `this.fieldChain` explicitly. The chain
side table now persists across all updates.

### R146b — `TaintFact.union` merges `fieldChain`

`plumb-dfa/.../taint/TaintFact.java`

Same root cause as R146a, different path: the `union` used
the 2-arg constructor too, so the join of all blocks'
outFacts (the function's "exit" TaintFact) had empty
fieldChain. R146 changes `union` to merge both maps'
`fieldChain` entries (using `putIfAbsent`, which keeps the
first-found chain for each var — correct for our "join all
blocks" exit semantics).

### R146c — `AnalysisContext.chainPropagations` side table

`plumb-dfa/.../AnalysisContext.java`

A per-scan map: `Map<FieldChain, Map<String, Set<String>>>`.
Keyed by chain signature `(immediateBase, fieldName)`;
value is the per-field writes the callee performed on a
chain var with that signature, propagated to the caller's
arg var. The `FIELDLOAD` transfer in the caller consults
this when creating a chain var (e.g. `String t1' = arg.a;`),
applying the writes so the chain walk in the caller (which
visits `fieldMap[chainVar][field]`) finds them.

### R146d — `propagateChainVarToCaller` per-call-site record

`plumb-dfa/.../taint/TaintAnalysis.java`

Called at the CALL site, after the recursive `doWorklist`
returns the callee's exit TaintFact. For each chain var in
the callee's exit state whose chain links directly to a
param (e.g. `(param, "a")`):
- read `fieldMap[chainVar][y] = sources`
- record in the call site's `chainPropagations` map under
  the key `(caller_arg, "x")` with per-field entries
  `{y: sources, ...}`

The chainPropagations side table accumulates across call
sites; the same chain signature may be touched by multiple
call sites and the per-field values are unioned.

### R146e — `FIELDLOAD` consults `chainPropagations`

`plumb-dfa/.../taint/TaintAnalysis.java`

When a chain var is created (`String t1' = arg.a;` →
`FIELDLOAD dest=t1' lhs1=arg fieldName="a"`, with chain
`(arg, "a")` recorded for `t1'`), the transfer consults
`chainPropagations.get((arg, "a"))` and applies the
recorded field writes to `t1'` via `withField`. Sibling
isolation is preserved: the side table records per-field
writes, so `fieldMap[t1'][b] = X` and `fieldMap[t1'][c] = Y`
are independent entries (a `t1'.c` read after a `param.a.b
= X` write still doesn't fire).

## Why This Works

- **Side-table bridge** is the key design: the caller's
  chain var doesn't exist at the time of the CALL — it's
  created later by the caller's `FIELDLOAD`. The
  `chainPropagations` map records the propagation at the
  CALL site and is consumed at the `FIELDLOAD` site. This
  bridges the temporal gap.
- **Per-field granularity** preserves sibling isolation
  (R118b / R141b). Each field write is an independent entry
  in the side table, so `t1'.b` and `t1'.c` are isolated
  in the caller's chain var.
- **Position-based remap** (param `i` → caller arg at
  `offset + i`) is consistent with R145 and the entry-taint
  seeding.
- **Cache-first (R141a)** is preserved: the side table is
  additive across call sites; multiple call sites with the
  same chain signature union their per-field writes.

## Two Pre-Existing Bugs Fixed

Both bugs were latent (the affected paths were only
exercised by R146's new "exit TaintFact has chains"
requirement; R141b's intra-fn SINK analysis happened to
work because the SINK was in the same TaintFact flow).

- **`withField`/`with` discarding `fieldChain`** — every
  baseMap/fieldMap update silently emptied the chain side
  table. The new 3-arg constructor is the correct call.
- **`union` discarding `fieldChain`** — the function-exit
  join produced an empty chain. Same fix.

Both fixes are ~3 lines each; R146 is the round that
finally exercises the path and forces the bug to be visible.

## 6 End-to-End Tests

`plumb-dfa/.../taint/CrossFunctionChainVarTest.java`

1. **`helperChainWritePropagatesToCallerChainRead`** —
   canonical 2-level chain cross-fn.
2. **`helperChainWriteIsolatesSiblingField`** — sibling
   isolation preserved by per-field granularity.
3. **`helperChainWritePropagatesToCallerDirectFieldRead`** —
   implicit chain (caller reads `o.a.b` without explicit
   local); the FIELDLOAD-generated chain var still
   receives the propagation.
4. **`helperChainWritePropagatesAcrossTwoHops`** — `m →
   wrapper → leaf`; the leaf's chain write propagates through
   the wrapper to m.
5. **`noChainWriteInHelperDoesNotFireOnCallerChainRead`** —
   negative control: helper doesn't write, caller reads,
   no fire.
6. **`helperChainWritePropagatesToMultipleCallSites`** —
   2 call sites of the same helper, each propagates
   independently; both sinks fire.

All 6/6 pass. Full test count: 235 dfa / 0 fail / 1 documented
skip (pre-existing Z3 skip in `WebAppConfigCheckTest`, not
R146-related).

## Cumulative Wins (R115 → R146)

- 31 rounds
- 39 checker categories (R146 0 new, pure chain-walk
  extension)
- 27+ CWE families
- 269 tests (235 dfa + 19 tac + 10 style + 2 reporter + 3 main) / 0 fail / 1 documented skip
- 33 per-round design docs (R146 adds ~9KB)
- 14 end-to-end demo files
- 125 end-to-end findings (examples) + 14 security findings (PetClinic, 100% precision)
- **NEW R140**: `.jar` / `.class` bytecode input front-end
- **NEW R141**: function summary cache — 20+ layer scaling
- **NEW R141b**: 7 intra-procedural field-chain tests
- **NEW R142**: cross-jar type hierarchy
- **NEW R143**: per-class source path
- **NEW R145**: cross-function fieldMap propagation
- **NEW R146**: cross-function chain-var field propagation

## R146 Limitations / R147+ Candidates

- **Multi-step chain** (e.g. `param.a.b.c = X` with `t1 = param.a; t2 = t1.b; t2.c = X`) — the
  current implementation only propagates chain vars
  directly linked to a param (single-step chain).
  Multi-step chain propagation is a R147+ candidate.
- **No alias analysis** — `helper(myObj); helper(myObj2)` where
  `myObj` and `myObj2` point to the same object would
  over-propagate. R117+ points-to infra is a starting
  point.
- **No path-sensitive may-analysis** — a callee's
  conditional write propagates regardless of whether the
  caller's context can reach it. R118c-style context
  sensitivity is a R148+ candidate.
- **R118c exception-path taint still deferred**: try/catch
  edges in CFG remain absent.
- **R140c real Spring Boot war** validation still pending.

## Paper Contribution

R146 is the natural completion of the R141b + R145
chain-walk story:

- R141b: intra-procedural field-chain walks
- R145: cross-function fieldMap propagation
- R146: cross-function chain-var field propagation

Together these form the "域敏感 + 跨过程" (domain-sensitive
+ cross-process) field analysis the user asked for. R146
adds the cross-fn chain walk: a helper's nested field write
(`o.a.b = X`) is now visible to the caller's chain walk
(`t1.b` after `t1 = arg.a`).

The implementation is small (~150 lines across
TaintFact + AnalysisContext + TaintAnalysis) because the
infrastructure was already in place: R141b's
`fieldChain` side table, R141a's `(callee, entry-taint-fingerprint)`
cache, R118b's `withField` weak-update primitive, R116's
recursive cross-function taint, R145's per-call-site
remap. R146 is the 7th "build infra, then use it" round.

The hidden bug fix (R146a/R146b: `withField`/`with`/`union`
discarding `fieldChain`) is a 6-line change that has been
latent since R141b — R141b's intra-fn SINK analysis happened
to work because the SINK was in the same TaintFact flow as
the chain set. R146's "exit TaintFact has chains" requirement
finally exposed the bug. The lesson: **a side table that
relies on functional updates needs those updates to preserve
the side table** — using 2-arg constructors that default the
side table to empty silently breaks the side-table invariant.

R146 demonstrates **cross-fn chain walk via a temporal
side-table bridge**: the CALL records, the FIELDLOAD
consumes, with the per-field granularity preserving
sibling isolation. This is the IFDS-style flow composition
applied to chain walks — callee's chain-walk results are
"abstracted" into a per-field propagation map and
"re-instantiated" at the caller's chain-var creation site.
