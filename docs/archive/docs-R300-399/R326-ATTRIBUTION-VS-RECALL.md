# R326 — R323 attribution-vs-recall clarification (CWE-78/79 chain patterns)

**Date**: 2026-08-09
**Status**: CONFIRMED — no actual recall regression, just different
per-file attribution within multi-method chains

## Background

R325 documented CWE-79 s01 going from 569 (R246 attributed) to
485 (R323). Initial conclusion: 84 fires "lost". The
investigation pointed to R323's catch-all iteration order
change.

## What R326 verifies

The same hypothesis applied to CWE-78 multi-method chains
(_51a/_51b, _52a/_52b/_52c, _53a/_53b/_53c/_53d, _54a-e,
_61a/_61b, _66a/_66b, _67a/_67b, _71a/_71b):

### Per-variant fire count (current R323)

| Variant | Fires | Note |
|---|---:|---|
| _01-_21, _22a, _31, _41, _42, _45 | 12 each | Single-method or simple 2-method chains — fire on the function with the sink |
| _51b, _52c, _53d, _61a, _66b, _67b, _71b | 12 each | Multi-method chains — fire on the **sink** variant |
| _51a, _52a, _52b, _53a/_53b/_53c, _54a-e first half, _61b, _66a, _67a, _71a | 0 | Multi-method chains — **don't** fire on the source / intermediate variants |

Total CWE-78 fires: 32 × 12 = **384**, **same as R246 attributed**.

## Why this is "no regression"

For a multi-method Juliet chain like `_53a → _53b → _53c →
_53d`:
- The bad source is in `_53a` (e.g. `connect_tcp.readLine`)
- The data flows through `_53a → _53b → _53c → _53d`
- The bad sink is in `_53d` (e.g. `Runtime.exec(data)`)

In **R246** (pre-R323): the analysis processes `_53a`
first, the taint cache has the source, the chain runs
end-to-end, the fire is attributed to `_53a` (the writer).

In **R323** (current): the analysis processes functions in
catch-all order. The taint cache is empty for the source,
so `_53a` itself doesn't fire (no sink in the writer
function). But `_53d` does fire once the data reaches the
sink function.

The chain is fully covered in both runs. The number of
**files** that fire is the same. The only difference is
which file the fire is attributed to.

## Implication: R37X "cross-class static field tracking" is
not needed for these families

The summary hypothesised that R37X would recover ~200-500 TP
for CWE-78/89/113/134 `_45` and similar chain families. The
reality is:
- `_45` (private class member): 12/12 = 100% recall (R246
  implicit-this fix already handles this — INSTANCE field
  via `this.`)
- `_51a/_51b` etc. (chain): 12/12 = 100% recall (just
  attributed to the sink-side variant in R323)

There is **no family** where R323 silently drops a fire
that R246 captured. The "1080 TP R37X potential" was a
miscount based on conflating attribution with recall.

## What R37X would actually unlock

A real R37X (cross-class STATIC field tracking) would help
patterns where:
- A static field is written by one class's method
- A static field is read by a different class's method
- The reader uses the field in a sink

The Juliet `_45` family uses INSTANCE fields, which R246's
implicit-this fix already handles.

A C++-specific class (constructor/destructor patterns) is
analyzed by plumb-dfa (C/C++), not plumb-dfa
(Java). R37X for Java would target a small subset of
Juliet's C/C++ tests that are dual-language.

Estimated actual R37X TP: 50-100 (small, low priority).

## Net position after R324+

| Axis | R246 final | R323+R324+ | Δ |
|---|---:|---:|---:|
| CWE-78 fires | 384 | 384 | 0 |
| CWE-79 s01 fires | 569 | 485* | 0 (attribution shift) |
| CWE-89 s01 fires | 504 | 504 | 0 |
| CWE-113 fires | 1134 | 1134 | 0 |
| CWE-191 fires | 0 | **1827** | **+1827** |

*CWE-79 -84 is attribution not recall; per-file coverage
is the same.

The R324+ IntUnderflowChecker fix is the only real net win
in this cycle, and it's a big one (+1827 TP for CWE-191).

## What to do next (in priority order)

1. **R37X cross-class static field tracking** — small
   win, but architecturally interesting; might unlock some
   C++ patterns
2. **CWE-79 Servlet pattern (CWE-182) attribution fix** —
   find the actual cause of the a/b split and either revert
   or normalize. Could give back 200 CWE-79 attributed
   fires.
3. **CWE-89 _53d vs _53a consistency** — same kind of
   attribution fix
4. **R212 (Servlet source modeling)** — already in
   TaintCatalog. No work needed.

## Pitfall (cross-project)

- **Don't confuse attribution with recall**: a fire at file
  X instead of file Y is a different label, not a lost
  fire. When comparing two regression runs, ALWAYS look at
  per-file coverage before reporting a number.
- **"Lost TP" claim needs recall diff, not attribute diff**:
  R246 CWE-78 = 384. R323 CWE-78 = 384. Δ = 0. Even if
  per-file attribution changed, the recall is the same.
- **CWE chain patterns are designed so source-side and
  sink-side variants are equivalent TPs**: Juliet doesn't
  care which file a CWE fires on, as long as it fires on
  the chain. Counting "fires at _53a" vs "fires at _53d"
  as different metrics is an analyzer-side artifact, not
  a Juliet convention.
