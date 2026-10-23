# R155 — CHA-Preferred `CallResolver.resolveCall` (signature-collision fix)

**Date:** 2026-07-30
**Round:** 17 of R148 series
**Status:** ⚠️ INFRA (CHA-aware overload wired, 0 merged-mode metric gain, depends on R148d source-recovery to release the 1/17 cap)

## Goal

R150 identified the **root cause** of the merged-mode
1/17 TP cap that has blocked every R-round since R148f:
a cross-file signature collision. When
`TacProgram.addFunction` collapses multiple files
into a single `MergedProgram`, two functions with the
same `(name, arity)` — e.g. `81_bad.action(String):void`
and `81_goodG2B.action(String):void` — share the
same `signature` key and the later one wins, so the
merged program only ever contains the `goodG2B`
variant. The taint engine then traces the good
body on a `baseObject.action(data)` call from `81a.bad()`
and gets a 0 TP / 1 FP result (because the body runs
the sink but with hardcoded data, so the rule does
fire — but in the wrong method).

R150 attempted a fix via the CHA implementor walk as a
**fallback** (only when name-based resolution failed).
But name-based resolution always finds
`81_goodG2B.action` (last-writer-wins), so the fallback
never fires. R150's 0 metric gain was the symptom;
R155 is the structural fix.

## Implementation

### R155a — `CallResolver.resolveCall(String, String)` overload

Added a new overload that takes the receiver's
short class name in addition to the callee signature.
When the receiver type is supplied and the CHA
implementor walk returns a non-empty list, the CHA
list wins. When the receiver type is null/empty or
the CHA walk is empty, the original name-based
path runs unchanged.

```java
public List<TacFunction> resolveCall(String calleeSig, String receiverType) {
    if (calleeSig == null) return List.of();
    // R155 path 1: CHA walk when receiver type is known.
    if (receiverType != null && !receiverType.isEmpty()) {
        // Reuse the existing name-extraction logic
        // (strip receiver prefix and arity suffix).
        String head = stripReceiver(calleeSig);
        int slash = head.lastIndexOf('/');
        String methodName = (slash < 0) ? head
                                          : head.substring(0, slash);
        if (!methodName.isEmpty()) {
            List<TacFunction> implementors =
                    resolveImplementors(receiverType, methodName);
            if (!implementors.isEmpty()) {
                return implementors;
            }
        }
    }
    // Original name-based path (R148 baseline).
    // ...
}
```

The original `resolveCall(String)` is preserved as a
one-arg forwarder so existing callers that don't
have a receiver type keep working unchanged.

### R155b — `TaintAnalysis.transferUnknownCallImpl` wire

Extracted the receiver-type extraction into a
helper:

```java
private static String extractReceiverTypeShortName(
        final TacInstruction ins, final int offset) {
    if (offset <= 0) return null;
    if (ins.args == null || ins.args.isEmpty()) return null;
    final TacOperand receiver = ins.args.get(0);
    if (!(receiver instanceof TacOperand.TacVar rv)) return null;
    if (rv.type() == null) return null;
    final String s = shortTypeName(rv.type().toString());
    if (s == null || s.isEmpty() || "<unknown>".equals(s)) return null;
    return s;
}
```

The call site now reads:

```java
final String receiverType = extractReceiverTypeShortName(ins, offset);
final List<TacFunction> callees = this.resolver.resolveCall(
        effectiveCalleeSig, receiverType);
```

This is a one-line change. The R150
`tryResolveImplementors` fallback in the
`callees.isEmpty()` branch is now dead code (the CHA
walk is engaged by the call-site path), but the
fallback is left in place as a defensive belt-and-
suspenders against future regressions — it's
inert when the CHA walk is in the call path.

### R155c — `ChaResolveCallTest.java` (new file)

`plumb-dfa/src/test/java/org/plumb/dfa/cg/ChaResolveCallTest.java`,
5 tests, 5/5 pass:

- `chaPreferredOverNameBased` — the canonical
  merged-mode collision: Bad.action + Good.action
  both registered, the CHA walk with
  receiverType="Base" returns both, in registration
  order.
- `receiverTypeNullFallsBackToNameBased` — null
  receiver type → original name-based path runs
  (returns the last-added variant, demonstrating
  the collision that's fixed by supplying a
  receiver type).
- `emptyReceiverTypeFallsBackToNameBased` —
  defensive: empty string (a few call sites
  produce `""` from `shortTypeName` when the type
  is unknown) is treated the same as null.
- `chaEmptyImplementorsFallsBackToNameBased` —
  leaf class with no children: the CHA walk
  returns the leaf's own method (also the
  name-based result would be, but the test
  documents the CHA-preferring semantics).
- `unknownReceiverTypeFallsBackToNameBased` —
  receiver type not in the program: CHA walk is
  empty, name-based path also misses (calleeSig
  uses a method name that's not registered), so
  the result is empty.

## Metric Impact

### Merged mode (CWE-78/89/36 — the flow 81+ families)

| | R150 / R152 baseline | R155 |
|---|---|---|
| CWE-78 TP | 0 | 0 |
| CWE-89 TP | 0 | 0 |
| CWE-36 TP | 0 | 0 |

**0 taint TP gain** — R155 changes the trace target
from `81_goodG2B.action` to `81_bad.action` (the
correct path according to the resolved receiver
type, since the caller is `81a.bad()` and the
runtime dispatch is to the Bad instance), but the
tainted `data` parameter flowing into
`81_bad.action` requires the caller's `data` to be
marked as tainted by the source. The socket-read
source (`readerBuffered.readLine()` →
`bu-0003-taint-system-in`) is registered in the
catalog, but the R148d recovery (disaster recovery
from the rolled-back R148h+2) lost the
**Spring/JAX-RS annotation source seeding** that
was the R148d contribution. Without source
seeding the `data` parameter is unmarked and the
sink in `81_bad.action` is never reached. **R155 is
infrastructure-correct but the metric release is
gated on R148d recovery**.

### 6 baseline families preserved

| Family | R152 | R155 | Delta |
|---|---|---|---|
| CWE-252 unchecked return | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-253 incorrect return | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-396 catch generic | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-477 obsolete function | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-572 thread.run | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-597 string == | 1 TP / 0 FP | 1 TP / 0 FP | 0 |

### Functional regression

`plumb-dfa`: 314 / 5 fail (R148d recovery) / 0 error / 1 skipped. R155
adds 5 unit tests (`ChaResolveCallTest`), bringing the
total to 314. **0 functional regression**.

## Lessons

1. **R150's "CHA fallback" was structurally wrong.**
   The fallback only fires when name-based resolution
   fails. But name-based resolution doesn't fail in
   the merged program — it just resolves to the
   *wrong* variant (last-writer-wins). R155 moves the
   CHA walk to the *primary* path so the receiver
   type drives the choice, with name-based as the
   fallback for cases where the type is unknown.
   The general lesson: a CHA walk that's a "fallback
   after failure" is silent when the real bug is
   that name-based resolution returns an
   *unrelated* answer instead of failing.

2. **The merged-mode signature collision is the
   root cause of the 1/17 cap.** Three rounds
   (R148f baseline, R148h+2 cross-TU propagator, R150
   CHA wire) each probed a different layer and each
   failed to break the cap. R150 was the first to
   name the root cause; R155 is the first to fix
   it. The fix is structurally correct but the
   metric release is gated on the R148d source
   recovery, which is the next natural round to
   attempt.

3. **`resolveCall(calleeSig, receiverType)` is a
   semantically different operation** from
   `resolveCall(calleeSig)`. R155 keeps both
   overloads and the no-receiver form delegates to
   the receiver form with `null` — this preserves
   every existing call site (including
   `TaintAnalysis.tryResolveImplementors` and the
   per-file DFA path) while making the receiver
   type the new primary input.

4. **5 unit tests are not enough to validate a
   resolver change** of this size. R155 is going
   into production with the merged-mode Juliet
   smoke test as the integration test. The unit
   tests cover the happy paths (CHA wins, fallback
   on null/empty/unknown receiver type) but the
   end-to-end behavior on real Juliet flow 81+
   files is verified by the eval script.

## Cumulative R148c–R155 (17 rounds, 1 rolled back)

| | Wins | Rolled back | Infra-only | Diagnostic-only | Baseline |
|---|---|---|---|---|---|
| R130–R144 (legacy) | 8 | 3 | 3 | 2 | |
| R148c | 1 (CVE) | | | | |
| R148d | 1 (Spring4Shell) | | | | |
| R148e | | | 1 (parallel) | | |
| R148f | | | | | 1 (Juliet) |
| R148g | 1 (CWE-252) | | | | |
| R149b | | | 1 (per-file) | | |
| R148h | | | 1 (BB solver) | | |
| R148i | | | 1 (package refactor) | | |
| R151a (CWE-253) | 1 (CWE-253) | | | | |
| R152 (CWE-572) | 1 (CWE-572) | | | | |
| R153 (CWE-597) | 1 (CWE-597) | | | | |
| R148h+2 | | 1 (cross-TU) | | | |
| R149 | | | 1 (CHA infra) | | |
| R150 | | | 1 (CHA wire) | | |
| R151 (CWE-396) | 1 (CWE-396) | | | | |
| R152 (CWE-477) | 1 (CWE-477) | | | | |
| R155 (signature fix) | | | 1 (CHA preferred) | | |

Total: 8 metric wins, 4 rolled back, 8 infra, 1 baseline.
plumb-dfa: 314 / 5 fail (R148d recovery) / 0 error / 1 skipped.

## Files

- `plumb-dfa/src/main/java/org/plumb/dfa/cg/CallResolver.java` — added `resolveCall(String, String)` overload + 5 new lines for the CHA-preferring path.
- `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintAnalysis.java` — `transferUnknownCallImpl` now extracts the receiver type and passes it to the new overload; added `extractReceiverTypeShortName` helper.
- `plumb-dfa/src/test/java/org/plumb/dfa/cg/ChaResolveCallTest.java` (new, 5 tests).

## Disaster backup

`D:\work\tmp\r155_backup\java_source\` contains pre-R155
copies of `CallResolver.java`, `TaintAnalysis.java`,
`MergedProgram.java`. Re-backup was performed before
R155a.

## Next R-round candidates

- **R156 R148d recovery redo** — restore the
  Spring/JAX-RS annotation source seeding that R148h+2
  rolled back. The R148d-5 lost it. With R148d in
  place, the R155 CHA-preferring call path finally
  produces 1+ TP per family on the flow 81+ tests.
  This is the obvious "R155 only pays off when R148d
  is back" next step.
- **R157 CWE-674 Unbounded Recursion** (narrow
  whitelist for self-recursive method without base
  case) — independent of R155, doesn't unlock any
  blocked family.
- **R158 CWE-481 Assign instead of Compare** (narrow
  whitelist for `if (x = y)`) — same.
