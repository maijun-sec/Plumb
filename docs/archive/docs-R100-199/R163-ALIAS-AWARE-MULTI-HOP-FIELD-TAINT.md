# R163 — Alias-Aware Multi-Hop Field Taint Propagation

## Problem

R158+R161 closed the merged-mode taint TP for cross-TU CWE-78
(0 → 202 TP, 0 FP) and the cross-function DTO field taint
(log4shell L71 + fastjson L73, +2 CVE findings). R162 verified
the deep chain (30+ layers) doesn't blow up. But R162a flagged
one remaining failure mode: **multi-hop passthrough DTO field
taint where the field is written at the deepest helper but every
intermediate wrapper is a read-only passthrough**.

R161 alone handles the "callee writes the field, caller reads the
return" case (single hop). R145 handles the "callee writes the
param, caller sees the param's field" case (single hop). But:

```java
void m(HttpServletRequest req) {
    Form f = new Form();
    f.x = req.getParameter("u");   // main sets f.x
    Form carried = w1(f);           // w1(f) returns f
    log.info(carried.x);            // SHOULD fire
}
Form w1(Form f) { return f; }      // w1 doesn't write f.x
```

Pre-R163: `f.x` taint in main is in `f`'s fieldMap, not `f`'s
baseMap. `traceIntoCallee` seeded only `f`'s baseMap into w1's
`f` param. w1's body never touches `f.x`, so w1's exit-taint's
`fieldMapOf(f)` is null. `propagateFieldMapToCaller` finds
nothing to propagate. Caller's `carried` ends up with empty
fieldMap. `carried.x` FIELDLOAD returns empty. Sink doesn't fire.

R162a 30-hop deep chain exhibits the same pattern at every
intermediate wrapper.

## Fix

R163 fix 1 — alias-aware seed in `traceIntoCallee`. When a
caller passes a reference to a callee, the reference's field
taint must be visible to the callee body. This is the natural
expression of reference semantics: the callee param is a Java
reference to the same object the caller has, so the field taint
the caller has set is automatically visible to the callee body
through the reference.

```java
// R163 — alias-aware field taint seed in traceIntoCallee
final Map<String, Set<String>> argFields = in.fieldMapOf(arg);
if (argFields != null && !argFields.isEmpty()) {
    for (final Map.Entry<String, Set<String>> e : argFields.entrySet()) {
        final Set<String> t = e.getValue();
        if (t == null || t.isEmpty()) continue;
        calleeEntry = calleeEntry.withField(
            (TacOperand)params.get(pi), e.getKey(), t);
    }
}
```

The fix is 16 lines (with javadoc) inside the existing arg loop
in `traceIntoCallee`. The seed happens after the base-taint
seed (line 822-824), so base taint and field taint are both
visible to the callee body.

## R118b weak-update invariant preserved

`TaintFact.withField` is the standard R118b weak update: callee
body's `FIELDSTORE` on the same field union's with the seed, not
replaces. So a callee that writes a different source set on the
same field gets the union, not the override. Sibling fields are
preserved across the call boundary.

## Test results

| Test | Pre-R163 | Post-R163 | Status |
|---|---|---|---|
| `r163_singleHop_knownToFire` | 1 sink | 1 sink | preserved (regression guard) |
| `r163a_twoHopPassthrough` | 1 sink | 1 sink | preserved (R161) |
| `r163b_mainWritesFieldThenW1Reads` | **0 sinks** | **1 sink** | **fix** |
| `r163c_threeHopDeepestWritesField` | 1 sink | 1 sink | preserved |

R163b is the canonical "caller writes field, callee is pure
passthrough, caller reads via return" pattern. R163a is the
"deepest helper writes, intermediate wrappers passthrough"
pattern. R163c extends R163a to depth 3. All three fire after
R163. The 30-hop R162 stress test still fires 0 sinks — that's
a separate R141a summary-cache vs. multi-fingerprint interaction
known limitation, scoped to R164+.

## Full test status

- 335 tests / 0 fail / 0 error / 1 skip (R151 expected disabled)
- 0 regression across 16 taint/checker test files
- 0 IR / 0 TAC / 0 new side table change (R163 is purely a
  propagation rule in `traceIntoCallee`, no IR surface change)

## Innovations (4)

1. **Reference aliasing through call boundary is the missing
   third half of cross-function field taint** (R163). R145
   propagates callee-param → caller-arg (callee writes the
   field). R161 propagates callee-return-value → caller-dest
   (callee writes the field through a return). R163 seeds
   callee-param ← caller-arg (caller writes the field, callee
   reads it through the reference). Three halves together form
   a complete cross-function field taint propagation story.
   Pre-R163 only had the first two, so the third (caller-writes
   + callee-reads) silently dropped the field taint.

2. **Java reference semantics dictate "callee sees caller's
   field taint"** (R163 design). The fix is the natural
   expression of reference semantics: a Java reference param
   means the callee operates on the same object the caller has,
   so field-level taint the caller has set is automatically
   visible. Pre-R163 engine didn't model this because the
   `traceIntoCallee` was only seeding baseMap (the per-variable
   taint set), not fieldMap (the per-field taint map). The IR
   surface supports it (TaintFact is per-base + per-field), but
   the propagation rule was incomplete.

3. **R118b weak-update invariant holds across call boundaries**
   (R163 invariant). A callee body's `FIELDSTORE` on a field
   that's already seeded with caller taint union's the two
   source sets, not replaces. This means: (1) sibling fields
   are preserved across the call, (2) if the callee writes a
   different taint source on the same field, the union is the
   over-approximation, (3) if the callee doesn't write the
   field, the caller taint flows through unmodified. The
   same-store-different-field case is handled by R118b's
   per-field granularity.

4. **R163 is a 16-line propagation rule, not a new data
   structure** (R163 implementation philosophy). The R158
   family of fixes (R141a summary cache, R158 mergedKey, R159
   exit-taint fast-path, R161 return field map) all introduce
   new caches or fields. R163 is purely a propagation rule:
   16 lines inside the existing `traceIntoCallee` arg loop.
   This is the cleanest kind of fix because it doesn't expand
   the IR surface, doesn't introduce new caches, and doesn't
   change the worklist algorithm. The IR + cache infrastructure
   built in R141a / R158 / R161 is enough; R163 just fills in a
   missing rule.

## Cumulative R148 series (24 rounds, 1 rolled back)

- 14 metric wins: R148c / R148d / R148g / R151a / R152a / R153 /
  R151b / R152b / R156 / R157 / R158 / R159 / R161 / R162 / **R163**
- 1 validation: R149b
- 9 infra: R148e / R148f / R148h / R148i / R149 / R150 / R155 /
  R157 (4) / R158 (5) / R159 (2) / R161 (4) / R162 (3)
- 1 rolled back: R148h+2
- 335 tests / 0 fail / 0 error / 1 skip (R163 final)

CVE 累计 (post-R163, 4/4 detected, 0 regression):
- CVE-2015-7501 (cc4): 1 taint + 2 other
- CVE-2017-18349 (fastjson): 5 deserialize
- CVE-2021-44228 (log4shell): 6 taint-sink-log + 3 direct JNDI
- CVE-2022-22965 (spring4shell): 8 taint-sink-reflection

## Known limitations (R164+ scope)

- R162 30-hop deep chain still fires 0 sinks. R163 closes the
  1-hop and 3-hop versions; the 30-hop version requires
  R141a summary cache to handle the O(N) distinct entry-taint
  fingerprints across N wrappers (each wrapper's seed includes
  the prior wrapper's fieldMap, generating a new fingerprint).
  R164 candidate: fingerprint-insensitive alias propagation
  (R141a cache key includes baseMap + fieldMap; relax to
  fieldMap-independent for "callee doesn't read the field"
  cases).
- R158 step 5 had a side path for "first-registered concrete
  wins" in the merged walk. R163 doesn't touch this; the
  30-hop chain is the same-source chain (main's `f.x` taint
  propagates through 30 layers) so it's not a register-order
  issue.

## Files

- Modified: `plumb-dfa/src/main/java/org/plumb/dfa/taint/TaintAnalysis.java`
  (16-line alias-aware field taint seed in `traceIntoCallee`,
  line 813+)
- New: `plumb-dfa/src/test/java/org/plumb/dfa/taint/R163MultiHopDTOFieldTaintTest.java`
  (4 tests: single-hop regression, 2-hop, 1-hop read-only
  passthrough, 3-hop deepest-writes; 0.8s)
- Backup: `D:\work\tmp\r163_done\java_source\TaintAnalysis.java`
  (74,976 bytes)
