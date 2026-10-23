# Plumb DFA Architecture Assessment (R694 prep)

Date: 2026-09-29
Author: Mavis (post-R693)

## Goal
Verify the engine's two-pass dataflow architecture (backward
path-insensitive summary → forward flow/path-sensitive) and
inventory the field/array/object/pointer sensitivity layers.

## 1. Pass Structure (verified)

The `DfaEngine` runs **three** logical passes per program:

| Pass | Phase | What it does | Path sensitivity |
|------|-------|--------------|-------------------|
| **1. Shape pre-pass** | `warmUpMethodSummaries` | Linear IR walk of every function to build `MethodSummary` (ReturnShape, SideEffect) | Path-insensitive |
| **2. Main forward pass** | BB-RPO + intra-block reverse per function | Taint propagation, sink detection, call summary fold (callee summaries read in advance) | Forward at CFG level, reverse within block |
| **3. Post-function summary** | `summariseOneFunction` | Collect Sinks / TaintPropagate / StringOps / FieldOps into `FunctionSummary` | Path-insensitive (union of all paths) |

### 1.1 What "intra-block reverse" gives us (verified)

A `TaintedCheckerBase.checkSinkAtCall` fires when a CALL to a
matching sink family has a tainted arg. The reverse-within-block
order means **the sink call site is processed before the MOVE that
defined the arg**, so the arg's taint must already be in the
`taintedVars` map when the sink fires. This is why summary
collection in `summariseOneFunction` (post-loop) sees all sinks
without re-analysing the body.

### 1.2 What "BB-RPO" gives us (verified)

At a CFG join, the forward value is the union of all predecessors'
values. RPO guarantees predecessors are processed first, so the
join's tainted set is the union. **This is flow-sensitive (each
basic block has its own taint state) but path-insensitive (the
join is a union, not a fork).**

### 1.3 The summary cross-function flow (verified)

When the main pass hits a CALL whose callee has a `FunctionSummary`:
1. The engine consults `summaryCache` (signature-keyed) to find
   the callee's `TaintPropagate` ops.
2. The matching caller arg inherits the taint label + path.
3. Subsequent MOVE/BINOPs in the caller propagate the taint.

This is what unlocks cross-file taint (R690 fix).

## 2. Sensitivity Layers (inventory)

| Layer | Implementation | Verified | Status |
|-------|----------------|----------|--------|
| **Field-sensitive** | `FieldTaintTracker` (per-fn `(receiver, field)`) | ✅ | Working |
| **Cross-fn field taint** | `crossFnClassFieldTaint` (class-type keyed) | ✅ | Working |
| **k-CFA call graph** | `1cfa`, `2cfa` strategies | ✅ | Available, not default |
| **VTA points-to** | `VtaCallGraphStrategy` (per-var may-point-to) | ✅ | Available |
| **Local path condition** | `GuardTracker` + Z3 solver | ✅ | Working |
| **Array-element sensitivity** | — | ❌ | Whole-array only |
| **Per-instance field identity** | — | ❌ | Per-class aliasing only |
| **Cross-function path condition** | — | ❌ | Each function summary is path-insensitive |
| **Heap cloning (k-object-sensitive)** | — | ❌ | Not implemented |

## 3. Gaps and Their Cost (Juliet CWE mapping)

| Gap | Juliet CWE hit | FN count | R694 task |
|---|---|---:|---|
| **Array-element insensitive** | CWE129 (`arr[tainted_idx]`) | ~200 | T1 |
| **Per-instance field aliasing** | CWE501 (trust boundary), CWE579 (wrong-allocator) | ~30 | T2 |
| **Path-insensitive summary** | CWE476/690/476 (`if (x != null) x.f` style) | ~100 | T3 |
| **VTA not default in cross-TU** | CWE89, CWE78, CWE113 (helper-method sources) | ~150 | T4 |
| **No per-call-site context (only signature)** | CWE89/78 with overloaded helpers | ~50 | T5 |

## 4. R694 Proposed Tasks (workload-ordered)

### T1 — Array-element sensitivity (small)
**Problem**: `byte[] arr = new byte[tainted_size]; ... arr[0] = src; ...
sink(arr[0])` — current ARRAYLOAD is `propagateMOVE`, treating
whole array as tainted.

**Fix**: Add `arrayTaint: Map<TacVar, Map<IntegerOrSymbol, TaintLabel>>` to track per-element taint. Use SSA index of the index operand as the key (when index is a constant) or a special "tainted-index" marker (when index is tainted).

**Expected gain**: CWE129 +50 TP, CWE789 +20 TP.

### T2 — Per-instance field identity (small)
**Problem**: `Helper h1 = new Helper(); h1.data = tainted;
Helper h2 = new Helper(); sink(h2.data)` — current
`crossFnClassFieldTaint` keyed by "Helper.data" would FP because
it loses h1/h2 identity.

**Fix**: Replace class-level aliasing with per-(TacVar-instance,
field) tracking when receiver is a TacVar, falling back to
class-level only when receiver is the class name itself
(static field). Use the `aliasing` flag in MethodSummary to
mark "this method may alias any Helper instance".

**Expected gain**: CWE501 +5 TP, -FP for CWE579.

### T3 — Path-insensitive summary refinement (medium)
**Problem**: Each `FunctionSummary` is the UNION of all paths
through the function. If path A taints param and path B doesn't,
the summary says "may be tainted" — caller FP risk in some cases.

**Fix**: For high-precision functions, compute **conditional**
summaries (paired `(path-condition, taint-label)` tuples) instead
of unconditional unions. Use Z3 solver to simplify the conditions
at the call site.

**Expected gain**: -50 FP (hard to measure precisely).

### T4 — Make VTA the default cross-TU CG (small)
**Problem**: Default is `global` (signature-only). VTA requires
building per-var points-to sets, which is slower but more precise
for cross-TU analysis.

**Fix**: Change `ScanRunner.scanMerged` default from `global` to
`vta` (or `2cfa`). Make `global` opt-in via `--cg-strategy global`.

**Expected gain**: CWE89/78/113 with helper methods +50 TP.

### T5 — Per-call-site context (signature + caller stack) (medium)
**Problem**: `FunctionSummary` is signature-keyed. Two call sites
with different argument profiles get the same summary.

**Fix**: Key the summary cache by `(signature, caller-sig)`. For
deeper k, use call-string of length k. Limit cache size to prevent
exponential blowup.

**Expected gain**: CWE89/78 with overloaded helpers +30 TP.

## 5. Recommended Order

Apply T1 + T4 first (smallest workload, biggest gain). Then T2.
T3 and T5 are research-quality changes — defer to R695+.

Estimated R694 ROI:
- T1 (3-5 days): CWE129 +50, CWE789 +20
- T2 (2-3 days): CWE501 +5, -FP for CWE579
- T4 (1 day, config-only): CWE89/78/113 +50
- T1+T2+T4 total: ~125 TP, ~5 FP saved
