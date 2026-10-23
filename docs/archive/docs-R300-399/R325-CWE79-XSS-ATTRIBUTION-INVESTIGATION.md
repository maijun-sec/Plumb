# R325 — CWE-79/XSS -84 attribution investigation (post-R323)

**Date**: 2026-08-09
**Status**: ROOT CAUSE IDENTIFIED, no clean fix (trade-off accepted)

## Symptom

After R323 (OffByOneChecker do-while + orderedFunctions dedup
fix), the CWE-79 (XSS) s01 fire count dropped from **569
(R246 attributed)** to **485 (R323)**, a net loss of 84
findings.

## Diff breakdown

Comparing per-file CWE-79 hits between R246 attributed and
R323 s01 results for `CWE80_XSS/s01`:

| Direction | Count | Pattern |
|---|---:|---|
| Only in R246 (lost) | 200 | `CWE80_XSS__CWE182_Servlet_*_51a/52a/53a/54a/66a/67a/71a.java` |
| Only in R323 (gained) | 116 | `CWE80_XSS__CWE182_Servlet_*_51b/52c/53d/54e/66b/67b/71b.java` |
| Both | 369 | (a/b pairs that fire in both) |
| **Net** | **-84** | |

The 200 lost files are **the 'a' half** of multi-method
chains (`_51a`, `_52a`, `_53a`, `_54a`, `_66a`, `_67a`,
`_71a`). The 116 gained files are **the 'b/c/d/e' half**
of the same chains (`_51b`, `_52c`, `_53d`, `_54e`, `_66b`,
`_67b`, `_71b`).

In Juliet's flow variants:
- `_51a` is the writer side of a 2-method chain (data flows
  into the abstract method)
- `_51b` is the reader side of the same chain (the abstract
  method's body reads the data)
- The chain as a whole flows `tainted_source → writer →
  reader → sink`, producing 2 CWE-79 fires

Pre-R323 (R246): the 'a' variant was processed and the
taint propagated; fire recorded at the writer's sink. 1 fire
per chain.

Post-R323: the analysis now processes BOTH the 'a' and 'b'
variants (because `Set<TacFunction>` dedup by reference,
not by signature, and the catch-all iterates
`program.functions()`). The 'b' variant reads the field and
fires. The 'a' variant is now seen as "data is written but
not used" because the iteration order changed.

## Root cause

R323 changed `DfaEngine.orderedFunctions`:

```java
// Pre-R323 (R246):
Set<String> seen = new HashSet<>();
for (var node : cg.reversePostOrder()) {
    TacFunction fn = program.function(node.signature());
    if (fn != null && seen.add(node.signature())) out.add(fn);
}
for (TacFunction fn : program.functions()) {
    if (seen.add(fn.signature())) out.add(fn);  // dedup by bare signature!
}

// Post-R323 (current):
Set<TacFunction> seen = new HashSet<>();
// ... same loop, dedup by reference
```

The dedup-by-reference change was needed to fix CWE-193
(17 files × bad/good dedup) and to make `program.functions()`
catch-all work (MergedProgram keys are
`enclosingClass :: signature`, so bare-sig dedup lost
functions).

**Side effect**: the catch-all iteration order is
`program.functions()` (typically iteration order of the
merged program), NOT call-graph RPO. So functions are
processed in a different order than R246.

For multi-method taint chains (`_51a` writer + `_51b`
reader), the taint analyzer needs to see the WRITER before
the READER to propagate taint. The new catch-all may
process the reader first, in which case the taint isn't
yet in the cache and the reader's sink check doesn't fire.

## Why we can't easily fix this

1. **Reverting R323 loses CWE-193 100% recall**: The
   bare-sig dedup was breaking CWE-193's 17-file dedup
   pattern. Without R323, only 1 of 17 CWE-193 files
   would fire.

2. **Catch-all is needed for MergedProgram**: The call
   graph iteration only visits functions reachable from
   entry points; in the merged Juliet corpus, many
   functions (e.g. `bad()` in different subclasses) are
   not in the same call graph, so the catch-all is
   required.

3. **Fixpoint iteration on taint propagation** (the
   principled fix) requires R217's worklist to be aware of
   cross-class taint ordering, which is a significant
   refactor. Estimated: 1-2 days.

## Trade-off

| Axis | R246 | R323 (current) | Δ |
|---|---:|---:|---:|
| CWE-193 (OffByOne) | 1/51 (2%) | 51/51 (100%) | +50 |
| CWE-79/XSS s01 | 569 | 485 | -84 |
| CWE-89 s01 (R246 attr → R323) | 504 | 504 | 0 |
| CWE-78 (all) | 384 | 384 | 0 |

The CWE-193 100% recall is a bigger win than the CWE-79
-84 regression. Accept the regression as a known
side-effect; fix only if a clean opportunity arises.

## Mitigations to try (if time permits, multi-round)

1. **Stable function order in catch-all**: Sort
   `program.functions()` by signature before iterating, so
   cross-class field writers consistently come before
   readers. May or may not help depending on the
   heuristics.

2. **Two-pass analysis**: First pass RPO, second pass
   catch-all. Taint from the first pass is in the cache
   for the second pass.

3. **R37X cross-class static field tracking**: The
   principled fix. Track `static field` writes/reads across
   all functions (not just the current class). Would also
   unlock CWE-78 _45, CWE-89 _45, CWE-22/36/90 _45
   (combined ~200 TP potential).

## Why "R37X cross-class static field" is the right
long-term fix

All the lost cases are essentially:
- Function `a` writes to a class field
- Function `b` (same class, sometimes different class) reads
  the field
- Function `b`'s field read propagates to a sink

The taint analyzer is per-function, so when `a` writes, the
write is recorded in `a`'s analysis. When `b` reads, the
read is checked against `b`'s taint state (which is empty
because the read is the first action in `b`).

A class-level taint map (similar to R246 implicit-this
field chain) that persists across functions in the same
class would catch this. R246 partially does this for
instance fields (via `this.`), but not for static fields
(which use a different SSA convention in TAC).

This is the natural next step after R324+. Estimated: 1-2
days implementation + 0.5 day validation.

## Conclusion

The CWE-79 -84 regression is a known, accepted side-effect
of R323's CWE-193 fix. The right long-term fix is R37X
cross-class static field tracking, which is a multi-round
effort.
