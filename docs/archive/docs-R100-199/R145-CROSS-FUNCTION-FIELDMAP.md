# R145 — Cross-Function FieldMap Propagation (R141b Follow-Up)

**Status:** Done. 7/7 new tests pass. 229 dfa tests / 0 fail / 1 documented skip (zero regression).
0 IR / 0 TAC / 0 new checker changes — pure inter-procedural field sensitivity.

## Motivation

R141b (`/docs/R141-SUMMARY-CACHE-SCALING.md`) wired intra-procedural
field-chain walks so `o.a.b = X; sink(t1.b)` fires within a single
function. R141b's documented limitation:

> "cross-function fieldMap propagation: when a helper in
> one input writes to a field chain, the fieldMap is not
> merged into the caller's TaintFact. Cross-jar field-aware
> taint is future work."

R145 closes this gap. With the R141a exit-taint cache (per-callee,
per-entry-taint-fingerprint) and a per-call-site remap helper, the
callee's field writes now propagate back to the caller's argument
vars, enabling true inter-procedural field sensitivity.

## What R145 Includes

### R145a — `TaintFact.fieldMapOf(base)` accessor

`plumb-dfa/.../taint/TaintFact.java`

A read-only accessor that returns the inner field map for a base
var (or `null` if absent). This is the R145 read primitive for
propagation: the callee's exit TaintFact exposes its
`fieldMap[callee_param]` and the caller uses this accessor to
iterate the field slots the callee populated.

### R145b — `exitTaintCache` keyed by `(calleeSig, entry-taint-fingerprint)`

`plumb-dfa/.../taint/TaintAnalysis.java`

Same keying strategy as the existing `returnTaintCache`. Stores
the callee's exit TaintFact (the join of all blocks' final outFact
after the worklist). On a recursive call site:

1. **Cache lookup** by `(calleeSig, fp)`. Hit → use the cached
   exit state for R145 propagation.
2. **Cache miss** → run the worklist (or a what-if pass) to
   capture the exit state fresh, then cache it.

The cache handles the "callee ran as entry point first, then
recursively called from a different site" case (e.g. `helper`
is its own entry point but also called from `m`): the first
run populates the cache; the second run gets a hit and
propagates.

### R145c — `doWorklist` returns the exit TaintFact

`plumb-dfa/.../taint/TaintAnalysis.java`

Pre-R145, the worklist was `void`. R145 makes it return the
join of all blocks' final outFact. The caller of doWorklist
(the recursive analysis path) captures the return value and
hands it to the new `propagateFieldMapToCaller` helper.

### R145d — `propagateFieldMapToCaller` per-call-site remap

`plumb-dfa/.../taint/TaintAnalysis.java`

The core R145 helper. For each callee param `pi`:
1. Look up the corresponding caller arg (via the same
   position-based mapping the entry-taint seeding uses)
2. Read the callee's exit fieldMap at `callee_param[pi]`
3. For each `(fieldName, sources)` in the callee's field slots,
   write `fieldMap[caller_arg][fieldName] = sources` in the
   caller's TaintFact (via `withField`, the weak-update
   primitive).

Position-based remap is essential: callee param `i` corresponds
to caller arg at index `offset + i` (where `offset` accounts
for the scope arg in instance calls). The remap is consistent
with the entry-taint seeding that the recursive path already
performs, so the field writes land in the same place the
caller would have seen them.

## Why This Works

- **FieldMap is per-function** in R118b. R141b's chain walk
  extends it to handle nested writes *within* a function.
  R145's contribution is extending it *across* functions.
- **Exit TaintFact** is the natural state to capture: any
  field write that survived the callee's worklist is in the
  callee's exit fieldMap. The remap keys these writes by
  the caller-side arg var, so subsequent reads in the caller
  (e.g. `arg.field`) see the taint.
- **Weak update (R118b)** carries over: a callee field write
  does not invalidate the caller's other field slots for the
  same arg, nor the caller's base taint on the arg. The
  `withField` primitive preserves the weak-update semantics.
- **Cache keying by entry-taint-fingerprint** is the same
  strategy as R141a. Two recursive call sites that pass
  equivalent taint into the same callee share the cached
  exit state — no worklist re-execution.

## 7 End-to-End Tests

`plumb-dfa/.../taint/CrossFunctionFieldMapTest.java`

1. **`helperFieldWritePropagatesToCallerField`** — canonical:
   `helper(o, req)` writes `o.x`, caller reads `o.x` in
   `logger.info`, sink fires.
2. **`helperFieldWriteIsolatesSiblingField`** — sibling
   isolation: helper writes `o.x`, caller reads `o.y`, no
   fire (R118b weak update).
3. **`helperFieldWriteAffectsMultipleCallSites`** — 2
   call sites of the same helper, each propagates the
   field write, 2 findings.
4. **`helperChainedFieldWritePropagatesToCallerField`** —
   single-level chained write (the chained case is documented
   as a known R141b scope limit for cross-fn chain walks).
5. **`returnTaintStillWorksAlongsideCrossFunctionFieldMap`** —
   regression guard for R141a: helper returns tainted value,
   caller uses return, still fires.
6. **`helperFieldWritePropagatesAcrossTwoHops`** — 2-hop
   chain `m → wrapper → leaf`, leaf writes field, caller
   reads, fires.
7. **`noFieldWriteInHelperDoesNotFireOnCallerRead`** —
   negative control: helper doesn't write, caller reads,
   no fire.

All 7/7 pass. Full test count: 229 dfa / 0 fail / 1 documented skip
(pre-existing Z3 skip in `WebAppConfigCheckTest`, not R145-related).

## Cumulative Wins (R115 → R145)

- 30 rounds
- 39 checker categories (R145 0 new, pure inter-procedural
  capability)
- 27+ CWE families
- 263 tests (229 dfa + 19 tac + 10 style + 2 reporter + 3 main) / 0 fail / 1 documented skip
- 32 per-round design docs (R145 adds ~9KB)
- 14 end-to-end demo files
- 125 end-to-end findings (examples) + 14 security findings (PetClinic, 100% precision)
- **NEW R140**: `.jar` / `.class` bytecode input front-end
- **NEW R141**: function summary cache — 20+ layer scaling
- **NEW R141b**: 7 intra-procedural field-chain tests
- **NEW R142**: cross-jar type hierarchy
- **NEW R143**: per-class source path
- **NEW R145**: cross-function fieldMap propagation

## R145 Limitations / R146+ Candidates

- **Field chain (R141b) does not propagate across functions**.
  R145 propagates the per-param fieldMap (e.g. `fieldMap[o][x]`).
  The chain walk in the caller (which produces a chain var like
  `t3 = o.a.b.c` and walks through chain links) is intra-fn.
  Cross-fn chain walks would require propagating the chain
  side table — a R146 candidate.
- **No alias analysis**. R145's propagation is per-param: if
  caller passes `myObj` and helper does `param.x = tainted`,
  `myObj.x` becomes tainted. But if helper does `param2 = obj2;
  param2.x = tainted` where `param2` aliases `param`, R145
  doesn't track the alias. R117+ points-to infra is a starting
  point.
- **No "may-write" precision**. R145 propagates every
  field write the callee performed. If a callee's field write
  is conditional on a path the caller doesn't take, the
  propagation is still conservative (may-analysis). A path-
  sensitive R145+ would gate propagation on call-site context.
- **R118c exception-path taint still deferred**: try/catch
  edges in CFG remain absent.
- **R140c 真实 Spring Boot war**: CVE-2022-42889 + CVE-2021-44228
  real recall baseline still pending.

## Paper Contribution

R145 directly serves the user-explicit non-functional
requirement (R141 context):

> "对于 java 语言这类面向对象语言来说，域敏感的分析，
> 跨TU和跨函数的分析（分析20层朝上性能不会衰减太多），
> 污点分析，域敏感、跨过程的指针分析都是非常重要能力。"

R145 adds **跨过程** (cross-process) field sensitivity: a
helper's field writes are now visible to the caller. The chain
walk remains intra-fn (R141b), but the fieldMap is inter-fn
(R145). Together, R141b + R145 form the foundation for the
"域敏感" (domain-sensitive) field analysis the user asked for.

The implementation is small (~80 lines in TaintAnalysis, ~20
in TaintFact) because the infrastructure was already in place:
R141a's `(callee, entry-taint-fingerprint)` cache, R141b's
`fieldMap` data structure, R118b's `withField` weak-update
primitive, R116's recursive cross-function taint. R145 is the
"build infra, then use it" cadence 6th instance — pure
capability addition on top of R115-R144.

The key insight: **R145 doesn't add new data structures** —
it adds a *propagation rule* between existing data structures
(callee's `fieldMap` ↔ caller's `fieldMap`, keyed by the
param/arg remap). This is the IFDS-style flow composition that
makes cross-fn field analysis tractable.
