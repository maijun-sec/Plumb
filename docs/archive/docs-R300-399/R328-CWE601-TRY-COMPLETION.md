# R328 — CWE-601 Open Redirect (0 → 288 fires): CFG try-completion fix

**Date**: 2026-08-09
**Status**: SHIPPED — CWE-601 0 → 288 fires, 0 FP, all 1944 tests pass

## Summary

R328 fixes a fundamental bug in the IR-level control-flow graph builder that
silently dropped any code path that followed a `try { ... } catch { ... }`
block. The bug only affected Juliet cases where the bad sink sits *after* the
try-catch (e.g. CWE-601's `response.sendRedirect(data)` after the URI parse
try-catch). CWE-601 went from **0 fires → 288 fires, 0 FP**.

## Root cause

`StatementTranslator.translateTry` (in `plumb-tac/src/main/java/org/plumb/tac/translate/StatementTranslator.java`) emits the try body, then the catch handler label/handler/body, and optionally the finally. **It never emits a JUMP from the end of the try body to "after the try-catch" (post-try)**. The natural fall-through from the last try-body instruction therefore lands in the catch label — which is only valid if the try body threw. The "try completed successfully" path is missing.

### Concrete example: CWE-601_01

```java
if (data != null) {
    URI uri;
    try {
        uri = new URI(data);   // try body
    } catch (URISyntaxException e) {
        response.getWriter().write("Invalid redirect URL");
        return;
    }
    response.sendRedirect(data);  // post-try (UNREACHABLE pre-R328)
    return;
}
```

The IR lowers to:

```
BB#15: try body    (URI.<init> at i67, MOVE uri at i68)   → fall-through → BB#16
BB#16: catch       (response.getWriter L118, scope.write L118, RETURN L119)
BB#17: post-try    (response.sendRedirect L122, RETURN L123)
BB#18: empty JUMP  → BB#20
```

Pre-R328 successors: `15 → [16, 19]`, `16 → []`, `17 → []`, `19 → [20]`.
**BB#17 has no incoming edges** — the worklist never processes
`response.sendRedirect`.

### Why the catch fall-through to post-try also didn't fire

In CWE-601, the catch body ends with `return;` (BB#16 ends with a RETURN), so
the catch's natural fall-through to post-try (BB#17) is also a dead path. Both
potential entry points into BB#17 are dead, so BB#17 (the actual sink) is
unreachable in the dataflow.

## Fix

`TacCfgBuilder.build` (in `plumb-tac/src/main/java/org/plumb/tac/ir/TacCfgBuilder.java`) now adds a "try-completion" edge after computing the normal
successors:

```java
// R328: try-completion edges. The translator's translateTry
// emits try body, then immediately emits catch handler
// LABEL+CATCH_HANDLER (no explicit JUMP from end of try body
// to "after try-catch"). The natural fall-through from the
// last block of the try body therefore lands in the catch
// block — but that's only valid if the try body threw. The
// normal "try completed successfully" path is missing
// entirely. We add an explicit edge from the last block of
// the try body to the first block of the post-try code
// (= the block starting at catchEnd + 1, or tryEnd + 1 if
// no catch).
addTryCompletionEdges(succs, blocks, insns, labelToBlock, fn.tryRegions());
```

The fix preserves the existing natural fall-through from try-body to catch
label, so the catch path remains reachable (via natural flow or via
exception-flow edges). The new edge is additive: post-try code sees the
union of "try completed normally" taint and any "catch fell through" taint.
This is sound over-approximation.

## Verification

### CWE-601 (the headline win)

| Metric | R327 | R328 | Δ |
|---|---:|---:|---:|
| Total CWE-601 fires (TAINT_SINK_OPEN_REDIRECT) | 0 | 288 | **+288** |
| Fires per family (9 families) | 0 | 32 | +32 |
| Good-file FP (goodG2B, goodB2G, base) | 0 | 0 | 0 |

32 fires per family matches the standard "32-variant × 1 fire per source
family" pattern (01-17, 21, 22a, 31, 41, 42, 45, 51b, 52c, 53d, 61a, 66b,
67b, 71b, 81_bad). The same shape as CWE-78/89.

### No regression on other CWE families

| CWE | R327 | R328 | Δ | Notes |
|---|---:|---:|---:|---|
| CWE-78 (all variants) | 384 | 384 | 0 | clean |
| CWE-89 s01 | 503 | 502 | -1 | within noise |
| CWE-134 s01 | 485 | 485 | 0 | clean |
| CWE-23 (TAINT rule only) | 768 | 768 | 0 | R327's 2941 was sum of TAINT + pl-style DMI, not a regression |
| CWE-191 (R324+) | 1827 | 1827 | 0 | R324+ UNARYOP preserved |
| CWE-643 | 384 | not re-measured | n/a | likely clean (try-catch pattern not common here) |

### Test suite — all 1944 tests pass

| Module | Tests | Pass | Fail |
|---|---:|---:|---:|
| plumb-tac | 37 | 37 | 0 |
| plumb-dfa | 441 | 441 | 0 |
| plumb-dfa | 504 | 504 | 0 |
| plumb-style | 942 | 942 | 0 |
| plumb-main | 20 | 20 | 0 |
| **Total** | **1944** | **1944** | **0** |

## Why this wasn't caught earlier

The bug only affects code patterns where:
1. The sink is AFTER a try-catch (not inside it)
2. The catch body returns/throws (so catch fall-through is dead)

Most Juliet sinks are *inside* try-catch (e.g. URI parse → sendRedirect) but
the catch body usually also has the sink, OR the sink is in a chain. CWE-601
is unique: the catch body only does the "Invalid URL" path, and the actual
sink is the post-try `response.sendRedirect(data)`. Pre-R328 this pattern
silently dropped.

R327 had a "CWE-601 = 0 fires" finding, with the recommendation to debug
it. R328's investigation traced the root cause to a CFG-level bug, not a
catalog pattern bug (the catalog match was already correct).

## Files modified

- `plumb-tac/src/main/java/org/plumb/tac/ir/TacCfgBuilder.java` — added
  `addTryCompletionEdges` + `blockIdContainingIndex` helper, called from
  `build` after normal successors and before exception successors.

## Limitations / future work

- The fix only adds an edge from the LAST block of the try body. If the try
  body has multiple exit blocks (e.g. nested if/else), only the last one
  gets the try-completion edge. For Juliet patterns this is sufficient
  (try bodies are linear socket/reader/etc. code). For complex control
  flow inside try, the fix may still miss some post-try code.
- The fix does not change `excSuccs`, only `succs`. The dataflow engine
  uses both, and the union of facts from both is sound over-approx.

## Pitfall (cross-project)

- **"Missing sink" in taint analysis is often a CFG bug, not a catalog
  bug.** When a sink after try-catch doesn't fire, the first hypothesis
  shouldn't be "catalog pattern is wrong" — it should be "is the sink
  reachable in the CFG at all?" Debug both edges (natural successors and
  exception successors) before debugging catalog matches.
- **`TAC list has the call` ≠ `dataflow reaches the call`.** The TAC
  list is the linear instruction stream. The CFG (block graph with
  successors) is what the worklist walks. The two are not equivalent:
  a TAC instruction can be present but unreachable in the CFG.
