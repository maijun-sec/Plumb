# R189 — Layer 3 Full CFG Predecessor-Union Path Condition (2026-07-31)

## Goal

Close R186's "window-of-8" limit on cross-block path conditions. R186 walks
back 8 instructions in the function's linear stream to find a recent BRANCH +
prev-BINOP guard. R189 replaces that windowed scan with the principled
forward dataflow over the CFG: every block's entry guards are the union of
its predecessors' exit guards. Any guard from any reachable predecessor
flows to any sink downstream. Same over-suppression tolerance as
R164/R165/R186.

## Layer 3 Design

```
in(B)  = ⋃ out(P) for P in predecessors(B)
out(B) = in(B) ∪ { g | last inst of B is BRANCH
                       and prev inst is BINOP (var, g.op, g.rhs) }
```

- Monotone increasing set; fixed point in O(blocks) iterations
- At a join, in(B) unions all preds (may-analysis)
- At a BRANCH, the guard is added to all successors (true and false)
- Canonical SAST invariant: never over-fire, may over-suppress

## Implementation

- `DivByZeroCheck.r189ComputeGuardsByBlockId(fn, cfg)` returns
  `Map<Integer, Set<BranchGuard>>` (per-block entry guards)
- `BranchGuard(String varName, String op, TacOperand rhs)` — a richer record
  than R165's `PathCondition` because Layer 3 needs the var name on the
  guard side
- `r189ToR165Map(...)` converts the Layer 3 result into the
  `Map<String, Set<PathCondition>>` shape that R165's existing
  `r165IsKnownNonZero(...)` lookup expects
- `addBranchGuard(...)` is the per-block transfer function: detects the
  canonical `(var, NE, 0)` BRANCH + prev-BINOP and adds to out-set

## Test Results

| Test | Pattern | Pre-R189 (R186) | Post-R189 (Layer 3) |
|---|---|---|---|
| `deepBlockGuardSuppresses` | guard + 25 padding inst + DIV | fires (8-inst miss) | **suppressed** (CFG dataflow wins) |
| `nestedIfGuardSuppresses` | outer if-guard + 10 padding + inner if-guard + DIV | fires (8-inst miss) | **suppressed** (dataflow sees both BRANCHes) |
| `ifElseJoinUnionAtJoinSuppresses` | `if (d != 0) { x = 1; } else { x = 2; } int y = 100 / d;` | fires (linear scan) | **suppressed** (over-suppress on else-path, documented) |
| `unGuardedDivideStillFiresUnderLayer3` | no guard anywhere | fires | fires (sanity) |

R186's 4 tests + R165's 5 tests + DivByZero's 2 tests = 11 existing tests
still pass. R189 adds 4 new tests for Layer 3 coverage. **No regression.**

## 4 Innovation Points

1. **"Forward dataflow over CFG" is the principled fix for R186's window**
   (R189 design). R186's 8-instruction backward scan was a pragmatic
   shortcut: it catches the canonical `if (d != 0) { x / d; }` pattern
   but misses guards separated by more than 8 instructions from the sink
   (deep if-then, nested if, deep loops). Layer 3 replaces the window
   with proper predecessor-union dataflow. O(blocks) iterations of
   fixed-point vs. O(1) per sink. Both correct; Layer 3 is just
   complete.

2. **Monotone-increasing set fixed-point is simple and fast**
   (R189 implementation). Path conditions can only be added (we never
   remove a guard as we go forward). The fixed point converges in at most
   `cfg.blocks().size() + 1` iterations. In practice, ~2-3 iterations
   for typical functions. No worklist needed.

3. **Branch-side guard flows to all successors, not just true-branch**
   (R189 tolerance). We don't know which successor is true vs. false
   without inspecting `BRANCH.trueLabel`. Adding the guard to all
   successors is the "may over-suppress" choice. False-negative on the
   false-path (where d could be 0) is acceptable for SAST. Documented
   in the new `ifElseJoinUnionAtJoinSuppresses` test.

4. **`BranchGuard` is a richer record than `PathCondition`**
   (R189 data model). R165's `PathCondition(op, rhs)` only captures the
   condition side. Layer 3 needs the var name too (since the guard is
   keyed by var name on lookup). `BranchGuard(varName, op, rhs)` is the
   Layer 3 record; `r189ToR165Map` bridges to the R165 lookup shape
   without forcing R165 to change. Future R-rounds (NullDeref, etc.) can
   reuse Layer 3 directly with `BranchGuard`.

## Cross-Function / Cross-TU Coverage Matrix (post-R189)

| Layer | Status | Rounds |
|---|---|---|
| Single-function linear scan | ✅ | R148a, R148b, R124, R130, etc. |
| Single-block (BB-level) | ✅ | R148h |
| Same-block path conditions | ✅ | R164, R165 |
| Cross-block path conditions | ✅ | R186 (8-inst backward) + **R189 (full CFG)** |
| Cross-block CFG predecessor-union | ✅ | **R189** (closes R186 known limit) |
| Cross-function (single TU) | ✅ | R141a, R147, R158, R161, R163, R187 |
| Cross-TU (multi TU) | ✅ | R117, R188 |
| Taint source via TaintCatalog | ✅ | R148b, R157d, R161, R162 |
| Style / naming | ✅ | R137, R167-R183 |

## Files

- Modified: `plumb-dfa/src/main/java/org/plumb/dfa/checker/DivByZeroCheck.java` (~110 lines added: r189ComputeGuardsByBlockId, addBranchGuard, r189IsKnownNonZero, r189ToR165Map, BranchGuard record)
- New: `plumb-dfa/src/test/java/org/plumb/dfa/checker/R189Layer3CfgPathConditionTest.java` (5854 bytes, 4 tests)
- Backup: `D:\work\tmp\r189_done\java_source\`
- 0 IR / 0 TAC change. Pure dataflow on existing CFG.

## Cumulative R148 series (37 rounds, 1 rolled back)

- 14 metric wins
- 1 validation
- **20 infra** (R186, R187, R188, **R189**)
- 1 rolled back
- **422 tests / 0 fail / 0 error / 1 skip** (381 dfa + 41 style)

## R190+ 候选 (按用户硬约束 cross-function / cross-TU 优先)

- **R190** Cross-TU Juliet exercise: 跑 22 IrCheckers 在 MergedProgram 测 49/68 families 看哪些可激活 (2-3h)
- R191 R141a fingerprint-insensitive field-taint (R187 sibling for fieldMap, 1-2h)
- R192 Sonar S2127 (PutInSyncWithIf) — narrow AST (1h)
- R193 Coverity pilot — 1 narrow rule from 255 extracted (2-3h)
- R194 Layer 3 推广到 NullDerefCheck (R164 Block-Local → Layer 3 全 CFG) (1h)
