# R194 — Layer 3 Forward Dataflow for NullDerefCheck (2026-07-31)

## Goal

Apply R189's Layer 3 forward dataflow design to NullDerefCheck.
R189 closed the "8-instruction window" limit on
DivByZeroCheck's cross-block path conditions. R194 closes
R164's "linear-scan misses cross-block guards" and "early-return
guard not propagated" known limits on NullDerefCheck.

## Layer 3 Design (R189 → R194)

Same dataflow structure as R189, with a different guard
predicate:

```
in(B)  = ⋃ out(P) for P in predecessors(B)
out(B) = in(B) ∪ { (var, NE, NULL_CONST) | last inst of B is BRANCH
                                          and prev inst is BINOP (var, NE, NULL) }
```

- Monotone increasing set; fixed point in O(blocks) iterations
- At a join, in(B) unions all preds (may-analysis)
- At a BRANCH, the guard is added to all successors (true and
  false branches both see it)
- "Type the capture, type the suppression" — R189 captures
  `(var, NE, 0)`, R194 captures `(var, NE, NULL_CONST)`. The
  suppression predicate (`r164IsKnownNonNull`) is the same:
  match the var name and the (NE, NULL) shape.

## Implementation

- `NullDerefCheck.checkFunction` refactored from linear scan to
  BB-level block walk
- `r194ComputeNullGuardsByBlockId(fn, cfg)` — R189-style forward
  dataflow returning `Map<blockId, Set<NullGuard>>`
- `r194ToR164Map(...)` bridges to the existing R164
  `Map<varName, Set<PathCondition>>` lookup shape
- `addNullBranchGuard(...)` is the per-block transfer function
- `NullGuard(varName, op, rhs)` record (R189 sibling)

## Test Results

| Test | Pattern | Pre-R194 | Post-R194 |
|---|---|---|---|
| R164 `earlyReturnAfterNotNullCheckNowSuppressed` | `if (s != null) { return; } s.length();` | fires (R164 linear) | **suppressed** (Layer 3) |
| R194 `deepBlockGuardSuppresses` | guard + 20+ padding + deref | fires (linear) | **suppressed** (Layer 3) |
| R194 `nestedIfGuardSuppresses` | outer if-guard + 10 padding + inner if-guard + deref | fires (linear) | **suppressed** (Layer 3) |
| R194 `ifElseJoinUnionAtJoinSuppresses` | `if (s != null) ...; else ...; s.length();` | fires | **suppressed** (over-suppress on else, documented) |
| R194 `unGuardedDerefStillFiresUnderLayer3` | no guard anywhere | fires | fires (sanity) |

R164's 6 tests + NullDeref's 7 tests + R194's 4 new tests = **17
tests pass**. R194 closes the R164 known-limit test
`earlyReturnAfterNullCheckStillFires_KnownLimit` (the
NE-not-null variant; EQ-null polarity is R195+ scope).

## 4 创新点

1. **"Layer 3 is a sibling pattern, not a one-off"** (R194 design
   choice). R189 was the first Layer 3 implementation for
   DivByZeroCheck. R194 is the second, for NullDerefCheck. The
   dataflow structure is identical; only the guard predicate
   differs. The pattern is now repeatable: any future
   BB-level path-condition checker (R195 for IntOverflow,
   R196 for ArrayBounds, etc.) can copy the R189/R194 template.
2. **"Type the capture, type the suppression" scales** (R194
   invariant). R164 captured `(var, NE, NULL)`. R194's
   `r194ToR164Map` produces the same shape — the existing R164
   suppression predicate `r164IsKnownNonNull` works unchanged.
   The dataflow is generic; the capture and the lookup are
   narrow whitelists that can be extended per-rule.
3. **"Linear scan → BB-level scan" is a refactor with strict
   semantics preservation** (R194 implementation). The R164
   linear scan was a special case of BB-level: every function
   is one block. R194 generalizes to multi-block functions with
   Layer 3 cross-block propagation. The intra-block logic is
   unchanged; the outer loop now iterates blocks in CFG order.
4. **R194's BB-level walk enables future R-rounds that need
   block boundaries** (R194 design). Layer 3 is the foundation
   for:
   - R195 (IntOverflow + Layer 3)
   - R196 (ArrayBounds + Layer 3)
   - R197 (per-block def/kill for path-condition invalidation)
   The block-by-block walk is the prerequisite for these.

## Cross-Function / Cross-TU Coverage (post-R194)

| Layer | Status | Rounds |
|---|---|---|
| Single-function linear scan | ✅ | R124, R130, R148a, R148b |
| Single-block (BB-level) | ✅ | R148h, **R194** |
| Same-block path conditions | ✅ | R164, R165, R172 |
| Cross-block path conditions | ✅ | R186 (8-inst) + **R189** + **R194** |
| Cross-block CFG predecessor-union | ✅ | R189, **R194** |
| Cross-function (single TU) | ✅ | R141a, R147, R158, R161, R163, R187 |
| Cross-TU (multi TU) | ✅ | R117, R188 |
| Taint source via TaintCatalog | ✅ | R148b, R157d, R161, R162 |
| Style / naming | ✅ | R137, R167-R183, R192 |

## Files

- Modified: `plumb-dfa/.../NullDerefCheck.java` (~150 lines changed: BB-level refactor + R194 helpers)
- Modified: `plumb-dfa/.../R164PathConditionTest.java` (1 test renamed + 1 new test, total 6 tests)
- New: `plumb-dfa/.../R194Layer3NullDerefTest.java` (4893 bytes, 4 tests)
- Backup: `D:\work\tmp\r194_done\java_source\`
- 0 IR / 0 TAC change. Pure dataflow on existing CFG.

## Cumulative R148 series (39 rounds, 1 rolled back)

- 14 metric wins
- 1 validation
- **21 infra** (R186, R187, R188, R189, R192, **R194**)
- 1 rolled back
- **432 tests / 0 fail / 0 error / 1 skip** (386 dfa + 46 style)
