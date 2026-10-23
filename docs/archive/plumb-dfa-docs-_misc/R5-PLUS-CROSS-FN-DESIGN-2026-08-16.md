# Plumb R5+ Cross-Function Summary-Fold Design

**Date**: 2026-08-16
**Author**: Mavis
**Status**: Design (not yet implemented — multi-day project)

## Context

The Plumb Java DFA framework currently has FUNCTION-LOCAL analysis
in the taint-style checkers (TaintedCheckerBase subclass family).
Cross-function taint propagation is partially supported via
`FunctionSummary` + `SummaryApplier`, but the implementation has
gaps that result in:

- **126 CWE-259 fires missing** on real Juliet (cross-method
  `buildQuery(u)` → `execute(q)` patterns)
- **23 CWE-256 fires missing** on real Juliet (cross-method
  `password = helper(...)` patterns)
- **2 FN cases in R567** (`FN_DEEP` cross-method-via-field,
  `FN_CROSS` cross-method-via-helper)

## What Works Today (R218 / R215 / R255)

The current cross-fn flow:

1. **Per-function analysis** (`DfaEngine.run`):
   - For each function, the taint-style checker runs
     `checkInstruction` over each instruction
   - When a CALL is hit, `SummaryApplier.apply` is called
   - The applier checks if the callee has a known summary
     (in `FunctionSummaryCache`)
   - If the summary has `TaintPropagate` for parameter N,
     the caller's argument N is marked tainted
2. **Summary building** (`FunctionSummary`):
   - Per-function taint operations are recorded
   - `TaintPropagate(paramIdx)` = "this function propagates taint
     from its parameter to its return value"
   - `Sink(family)` = "this function contains a sink call"

## What's Missing (R5+ gap)

The current flow has THREE structural limitations:

### 1. Summary-Fold propagation stops at "parameter → caller arg"

When `helper(tainted_string)` is called and `helper` has
`TaintPropagate(0)` in its summary, the caller's arg is marked
tainted. **But the function body of `helper` is never re-analyzed**:
- If `helper` does `String t = arg.toLowerCase(); return t;`,
  the `toLowerCase` is recorded as a String passthrough in
  `propagateStringPassthroughs`, but this is per-function state
- When the summary is re-applied at the caller, the passthroughs
  are LOST — the taint goes directly to the call arg, not through
  the local `t`
- For long helper chains (`a → b → c → sink`), each hop loses
  the intermediate path

### 2. Inter-procedural control flow is collapsed

When a function has multiple basic blocks, the current
`checkInstruction` walks them linearly. But:
- A `String tainted = if (x) helper(badInput) else literal;`
  pattern isn't tracked across the if/else
- The control flow is preserved in `TacCfg` but the
  `FunctionSummary` only records the union of paths, not
  the conditional state

### 3. Field-level propagation is incomplete

The `FN_DEEP` test case in R567:
```java
class DeepFlow {
    String data;  // field
    public void bad(HttpServletRequest req, Connection conn) {
        String u = req.getParameter("u");
        setData(u);  // this.data = u
        Statement s = conn.createStatement();
        s.execute("SELECT * FROM t WHERE u='" + data + "'");
    }
    void setData(String d) { this.data = d; }
}
```
- `setData` propagates taint from `d` to `this.data` (a field)
- But `this.data` is not visible to `bad()`'s analysis
- The taint is "stored" in a field, then "read back" in `bad()`
- Current flow doesn't track this

## Proposed Architecture (R5+)

### Phase 1: Enhanced FunctionSummary

Extend `FunctionSummary` to record:

```java
public final class FunctionSummary {
    // Existing: per-param taint propagation
    Map<Integer, Set<Operation>> paramOps;
    // Existing: return value taint propagation
    List<Operation> returnOps;
    // NEW: field reads/writes
    List<FieldOp> fieldOps;
    // NEW: conditional path facts
    Map<Integer, Set<PathFact>> pathFacts;
    // NEW: string passthroughs (so they survive fold)
    List<StringOp> stringOps;
}
```

The key change: `stringOps` records the taint-passthrough methods
called inside this function (e.g. `toLowerCase`, `substring`).
When the summary is applied at the caller, these are re-applied
in order.

### Phase 2: Two-pass analysis

For each function:
1. **Forward pass**: build the summary (record param taint,
   field ops, string passthroughs, return taint)
2. **Backward pass**: for each sink, walk backward through the
   function's taint flow to construct a path (with conditional
   branching)

Then for each caller:
1. **Apply summary** to seed call args with the right taint
2. **Run forward pass** for the caller (using the seeded taint)
3. If the caller calls another function, recurse

### Phase 3: Field propagation (limited)

For `FN_DEEP`-style patterns, add a `FieldTaintTracker`:
- When a function writes to `this.f`, record the taint state
- When the same function reads from `this.f`, restore the taint
- Only track `this.f` (not other class instances, not static
  fields — too complex)

## Implementation Plan

This is a multi-day project. Estimated timeline:

- **Day 1**: Design + Phase 1 implementation
  - `FunctionSummary` field additions
  - `SummaryApplier` enhanced fold
  - Unit tests for string-passthrough survival
- **Day 2**: Phase 2 — Two-pass analysis
  - `TaintedCheckerBase.checkInstruction` refactor
  - Per-function CFG walk
  - Unit tests for cross-fn chain
- **Day 3**: Phase 3 — Field propagation
  - `FieldTaintTracker` new class
  - Integrate with `TaintedCheckerBase.initFunction`
  - Real Juliet sweep to measure impact
- **Day 4**: Validation + cleanup
  - Run R7/R8/R567 unit tests
  - Run R10 baseline regression
  - Document + release

Expected impact:
- CWE-256: 38 → ~100 fires (+62 from cross-method helpers)
- CWE-259: 54 → ~180 fires (+126 from cross-fn buildQuery)
- CWE-89: 2115 → ~2200 fires (small gain)
- R567 FN cases: 0 fires → 2 fires (both fixed)
- Performance: +5-10% (two-pass analysis) but acceptable

## Out of Scope (deferred)

- **Cross-class field access**: too complex for current cadence
- **Static field tracking**: not a common Juliet pattern
- **Cross-TU taint**: needs cross-TU call graph (R460-34 territory)
- **Heap-allocated object taint**: too imprecise
- **Reflection / dynamic dispatch taint**: orthogonal problem

## Success Criteria

- All existing R7-R577 unit tests pass
- R10 baseline: fire count +5% to +15% (no FP regression)
- CWE-256 + CWE-259 fire count +100% on Juliet
- R567 `FN_DEEP` and `FN_CROSS` tests pass
- New R5+ unit tests (10+) for the cross-fn machinery
- Documentation in `docs/R5-PLUS-CROSS-FN-2026-08-XX.md`

## Status

**Design only**. Implementation deferred to a 5-day sprint.
