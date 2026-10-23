# R195 — Layer 3 Forward Dataflow for IntegerOverflowCheck (2026-07-31)

## Goal

Apply R189/R194's Layer 3 forward dataflow design to
IntegerOverflowCheck. Closes R172's "linear scan misses
cross-block guards" known limit. R195 is the third Layer 3
implementation after R189 (DivByZero) and R194 (NullDeref).

## Layer 3 Design (R189 → R194 → R195)

Same dataflow structure, with a different guard predicate:

```
in(B)  = ⋃ out(P) for P in predecessors(B)
out(B) = in(B) ∪ { g | last inst of B is BRANCH
                       and prev inst is BINOP (var, op, NUM_CONST) }
                       where op ∈ {LT, LE, GT, GE}
```

- Monotone increasing set; fixed point in O(blocks) iterations
- At a join, in(B) unions all preds (may-analysis)
- At a BRANCH, the guard is added to all successors (true and
  false branches both see it)
- Recognizes all four bounds ops (LT/LE/GT/GE) — that's the
  superset of R172's `isBoundedBothEnds` (which only reads
  LT/GT). Adding LE/GE is a strict generalization.

## Implementation

- `IntegerOverflowCheck.checkFunction` refactored from linear
  scan to BB-level block walk
- `r195ComputeIntGuardsByBlockId(fn, cfg)` — R189/R194-style
  forward dataflow returning `Map<blockId, Set<IntGuard>>`
- `r195ToR172Map(...)` bridges to the existing R172
  `Map<varName, Set<PathCondition>>` lookup shape
- `addIntBranchGuard(...)` is the per-block transfer function
- `IntGuard(varName, op, rhs)` record (R189/R194 sibling)
- `capturePathCondition` and `isBoundedBothEnds` now take
  `pathConditions` as parameter (not a class field) so each
  block can have its own

## Test Results (4/4 new + 8/8 existing)

| Test | Pattern | Pre-R195 (R172) | Post-R195 (Layer 3) |
|---|---|---|---|
| R195 `deepBlockLTMaxGuardSuppresses` | guard + 20+ padding + ADD | fires | **suppressed** (Layer 3) |
| R195 `deepBlockGTMinGuardSuppresses` | guard + 20+ padding + SUB | fires | **suppressed** (Layer 3) |
| R195 `nestedIfGuardSuppresses` | outer if-guard + 10 padding + inner if-guard + ADD | fires | **suppressed** (Layer 3) |
| R195 `unGuardedOverflowStillFiresUnderLayer3` | no guard anywhere | fires | fires (sanity) |

R172's 4 tests + IntOverflow's 4 tests + R195's 4 new tests =
**12 tests pass, 0 regression**.

## 4 创新点

1. **"Layer 3 is now a 3-rule pattern"** (R195 design).
   R189 (DivByZero) was the first Layer 3, R194 (NullDeref)
   was the second, R195 (IntOverflow) is the third. The
   dataflow structure is identical; only the guard predicate
   differs. The pattern is now routine: any future BB-level
   path-condition checker (R196+ for ArrayBounds, BadShift,
   etc.) can copy the R189/R194/R195 template.
2. **"Four-op guard predicate" superset** (R195 design
   choice). R189 captures `(var, NE, 0)`, R194 captures
   `(var, NE, NULL_CONST)`, R195 captures
   `(var, LT/LE/GT/GE, NUM_CONST)`. The four-op capture
   matches what R172's `isBoundedBothEnds` consumes
   (LT/GT) and adds LE/GE as a free improvement.
3. **"Path conditions are now per-block, not per-function"**
   (R195 implementation choice). Pre-R195 the
   `pathConditions` map was a class field cleared at every
   function. R195 makes it a per-block local that gets
   seeded from Layer 3 entry guards and rebuilt at the top
   of each block iteration. Same suppression predicate
   (`isBoundedBothEnds`) is reused unchanged.
4. **R195 closes a real 8-instruction-window limit on
   IntOverflow** (R195 practical impact). R172's linear
   scan cleared path conditions at RETURN/THROW and within
   a single block kept them. R195's Layer 3 sees guards
   from any predecessor block. The deep-block test
   (20+ padding instructions between guard and overflow)
   was the canonical R172 known limit; R195 closes it.

## Cross-Function / Cross-TU Coverage (post-R195)

| Layer | Status | Rounds |
|---|---|---|
| Single-function linear scan | ✅ | R124, R130, R148a, R148b, R172 |
| Single-block (BB-level) | ✅ | R148h, R194, **R195** |
| Same-block path conditions | ✅ | R164, R165, R172 |
| Cross-block path conditions | ✅ | R186 + R189 + R194 + **R195** |
| Cross-block CFG predecessor-union | ✅ | R189, R194, **R195** |
| Cross-function (single TU) | ✅ | R141a, R147, R158, R161, R163, R187 |
| Cross-TU (multi TU) | ✅ | R117, R188 |
| Taint source via TaintCatalog | ✅ | R148b, R157d, R161, R162 |
| Style / naming | ✅ | R137, R167-R183, R192 |

## Files

- Modified: `plumb-dfa/.../IntegerOverflowCheck.java` (~110 lines changed: BB-level refactor + R195 helpers)
- New: `plumb-dfa/.../R195Layer3IntOverflowTest.java` (4954 bytes, 4 tests)
- Backup: `D:\work\tmp\r195_done\java_source\`
- 0 IR / 0 TAC change. Pure dataflow on existing CFG.

## Cumulative R148 series (40 rounds, 1 rolled back)

- 14 metric wins
- 1 validation
- **22 infra** (R186, R187, R188, R189, R192, R194, **R195**)
- 1 rolled back
- **436 tests / 0 fail / 0 error / 1 skip** (390 dfa + 46 style)
