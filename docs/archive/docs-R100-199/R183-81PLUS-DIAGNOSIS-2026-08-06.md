# R183 CWE-81 81+ Pattern Cross-Class Dispatch — Diagnosis

**Date:** 2026-08-07
**Scope:** CWE-81 XSS Error Message — Flow 81+ abstract-method dispatch
**Status:** DIAGNOSIS ONLY (no fix landed yet, deferred to R184+)

## What the 81+ pattern looks like

The Juliet CWE-81 family has 4 distinct file roles for the flow 81+ pattern (8 sources × 4 = 32 files):

| File | Role | Method |
|------|------|--------|
| `_81_base.java` | abstract class with `abstract void action(String data, HttpServletRequest, HttpServletResponse)` | (abstract) |
| `_81a.java` | has `bad(request, response)` → calls `new _81_bad().action(data, ...)` | `bad()` |
| `_81_bad.java` | concrete subclass of `_81_base` → implements `action(...)` with the XSS sink | `action()` |
| `_81_goodG2B.java` | concrete subclass of `_81_base` → implements `action(...)` with the XSS sink but a hardcoded source | `action()` |

The taint chain is:
1. `_81a.bad()`: `data = readerBuffered.readLine()` — source (bu-0005-taint-network-recv)
2. `_81a.bad()`: `new _81_bad().action(data, request, response)` — cross-class abstract dispatch
3. `_81_bad.action(...)`: `response.sendError(404, "<br>... " + data)` — sink (bu-0104-taint-sink-xss)

## Current state (R182)

- CWE-81 score: 48.5% (166 TP / 342 bad files)
- 81+ files: 0 TP / 16 isBad=true files (8 `_81a.java` + 8 `_81_bad.java`)
- All TPs come from the `_01`..`_80` flow variants where the sink is directly in `bad()`.

## Why the 81+ files don't fire

The TaintAnalysis has the right infrastructure to handle this:
- `CallResolver.resolveImplementors(baseType, methodName)` does a BFS through `classToChildren` to find every concrete subclass's `action` method. The docstring (CallResolver.java:262–272) explicitly cites Juliet flow 81+ as the use case.
- `transferUnknownCallImpl` walks the mergedKey-keyed cache and picks the first concrete implementor (R158 step 5).

The empirical evidence shows it's not firing. Likely root causes (not yet isolated):

1. **Receiver type not extracted**: `baseObject` in `_81a.bad()` is declared as `CWE81_*_81_base baseObject` (the abstract type). The `TacOperand.TacVar` for `baseObject` should have `type() = CWE81_*_81_base`, but the parser might emit it as `<unknown>` (the type was filled in by the user, not auto-resolved).
2. **classToChildren not populated**: The CallResolver indexes class hierarchies at startup. If `_81_bad` and `_81_goodG2B` aren't found in the index (e.g. they're loaded after the index is built), the implementor walk returns empty.
3. **The `new _81_bad()` constructor isn't resolved**: the receiver type at the call site is the abstract base, but the actual instance is `_81_bad`. The dispatch analysis should follow the `new` to narrow the receiver type, but the current code uses the declared type only.

## Diagnostic next steps

A targeted R183 fix would:

1. Add a debug log of `classToChildren` for the 4 abstract base classes in CWE-81 to confirm the index is populated.
2. Add a debug log of `extractReceiverTypeShortName` output for the `baseObject.action(...)` call site.
3. Compare R158's "stop at first concrete" walk output for the 81+ pattern vs the working 66+ pattern (which has a similar cross-class structure but does fire — see CWE-78 66b FPs earlier).

## Workaround (for R184+)

For CWE-81, the 81+ pattern is 16 of 342 bad files. Even fixing 100% of the 81+ pattern would only push CWE-81 from 48.5% to ~53.2% (+16 TP). The bigger wins for CWE-81 are the `_21` (if-true with constant) and `_68` (deep nested call) flow variants which are also at 0% today.

## Action

Defer R183. Move to R184 (CWE-89 sub-batching) and R185 (CWE-476 null-deref path-condition), which have higher expected gains. Come back to R183 with a separate, focused effort once the 81+ diagnostic is complete.
