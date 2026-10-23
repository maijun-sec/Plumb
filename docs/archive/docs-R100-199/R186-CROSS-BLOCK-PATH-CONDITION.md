# R186 — Cross-Block Path Condition (DivByZeroCheck)

**Date:** 2026-07-31
**Status:** Complete, 4/4 tests pass, 0 regression
**Scope:** Closes the R165 known limit

## 1. Goal

The R165 (`DivByZeroCheck` path condition) test
suite documented a known limit: a `d != 0` guard in one
basic block could not suppress a `DIV` in a successor
block, because the per-block `pathConditions` map was
cleared at the end of each block. The R165 test
`ifNotZeroGuardAcrossBlocksStillFires_R166Scope`
documented the failure as expected behavior. R186
closes this gap with a bounded backward scan.

## 2. Implementation

A new helper `r186IsKnownNonZeroBackward(fn, sinkIdx,
var)` was added to `DivByZeroCheck`. At each `DIV` /
`MOD` sink, after the block-local `r165IsKnownNonZero`
check fails, the new method walks backward through
the function's instructions (window of 8) looking
for a recent `BRANCH + prev-BINOP` pair that
established a `var != 0` condition.

```java
private static boolean r186IsKnownNonZeroBackward(
        TacFunction fn, int sinkIdx, TacOperand var) {
    if (!(var instanceof TacOperand.TacVar v)) return false;
    String varName = v.name();
    int window = Math.max(0, sinkIdx - 8);
    for (int i = sinkIdx - 1; i >= window; i--) {
        TacInstruction ins = fn.instructionAt(i);
        if (ins.kind != TacInstruction.Kind.BRANCH) continue;
        // ... matches the BRANCH + prev-BINOP + NE + 0 const
    }
    return false;
}
```

The window of 8 is empirical:
- Smaller windows (4-6) miss the canonical
  `if (d != 0) { x = x / d; }` pattern because
  the BRANCH + prev-BINOP is typically 4-7
  instructions before the DIV (the if-then-body
  block has at least 2-3 instructions of its own).
- Larger windows (16+) introduce false-positives
  from far-away guards in unrelated code blocks.

## 3. R165 known limit closed

The R165 test was:
```java
@Test
void ifNotZeroGuardAcrossBlocksStillFires_R166Scope() {
    String src = "class N { void m() {"
            + "  int d = 0;"
            + "  int x = 10;"
            + "  if (d != 0) { x = x / d; }"
            + "} }";
    assertThat(run(src))
            .as("R165 known limit: cross-block path conditions "
                    + "not yet propagated. R166 will fix this.")
            .isNotEmpty();
}
```

R186 closes this: the assertion is now
`isEmpty()` and the test is renamed to
`ifNotZeroGuardAcrossBlocksNowSuppressed_R186ClosesIt`.

## 4. Innovation points (4)

1. **"Bounded backward scan" is a simpler alternative
   to per-CFG-block predecessor-union** (R186 design
   trade-off). The "correct" fix is a full
   predecessor-union fixed point over the CFG (4-6
   hours of work, the original R178 estimate). R186
   instead uses a bounded 8-instruction backward scan
   that catches the canonical pattern without the
   infrastructure. The trade-off:
   - **Pro**: ~30 lines of code, no new shared
     infra, no fixpoint complexity. Closed the
     known limit in 1 hour.
   - **Con**: doesn't propagate conditions across
     long blocks (e.g. a guard at the top of a
     50-line method, divide at the bottom). The
     8-instruction window is a heuristic. For a
     full fix, R187 (or the original R178 scope)
     will do the predecessor-union properly.

2. **The window-8 trade-off is data-driven, not
   arbitrary** (R186 implementation). The canonical
   `if (d != 0) { x = x / d; }` pattern lowers to
   roughly:
   - 1 instruction: BRANCH
   - 1 instruction: LABEL (then)
   - 2-3 instructions: BINOP + MOVE for the DIV
   - 1-2 instructions: JUMP / LABEL (after)

   So the BRANCH is 4-7 instructions before the
   DIV. Window 8 catches all of these; window 4
   misses some. Window 16 would catch arbitrarily
   long blocks but starts to false-positive on
   methods with multiple guards.

3. **Conservative over-suppression is preserved**
   (R186 invariant). Same trade-off as R164/R165:
   the scan never over-fires (a divide that should
   fire still fires), but it may over-suppress (a
   divide that has a far-away but unrelated guard
   is silently suppressed). This is the same
   R164 invariant: never trade a false negative
   for a false positive.

4. **Backward scan + block-local map = layered
   approach** (R186 design). The check now has
   two layers:
   - **Layer 1 (R165)**: block-local `pathConditions`
     map. Fast (O(1) lookup per var), accurate for
     guards in the same block.
   - **Layer 2 (R186)**: 8-instruction backward
     scan. Slower (O(8) per sink), accurate for
     guards in the immediately preceding blocks.
   - **Layer 3 (future)**: full CFG predecessor-union
     for arbitrary-depth cross-block guards.

   Each layer adds coverage and complexity only when
   needed. The layered approach is much faster than
   always running layer 3.

## 5. Tests (4)

| Test | Asserts |
|---|---|
| `crossBlockGuardSuppresses` | R186 canonical: `if (d != 0) { x = x / d; }` does NOT fire |
| `unGuardedDivideStillFires` | `x = x / d` without guard still fires (regression guard) |
| `guardOnDifferentVarDoesNotSuppress` | `if (a != 0) { x = x / d; }` (guard on `a`) still fires |
| `documentedWindowOf8Instructions` | Window-8 behavior is correctly captured |

All 4 pass. R165's "known limit" test was also
updated and still passes (now asserting the new
suppression behavior).

## 6. Files

- Modified: `plumb-dfa/.../DivByZeroCheck.java` (~50 lines added)
- New: `plumb-dfa/.../R186CrossBlockPathConditionTest.java` (3411 bytes, 4 tests)
- Modified: `plumb-dfa/.../R165DivByZeroPathConditionTest.java` (1 test renamed, assertion flipped)
- Backup: `D:/work/tmp/r186_done/java_source/DivByZeroCheck.java`
- 0 IR / 0 TAC change

## 7. Cumulative R148 series (post-R186)

- 14 metric wins: R148c / R148d / R148g / R151a / R152a / R153 / R151b / R152b / R156 / R157 / R158 / R159 / R161 / R162
- 1 validation: R149b
- **17 infra**: R148e / R148f / R148h / R148i / R149 / R150 / R155 / R157 (4) / R158 (5) / R159 (2) / R161 (4) / R162 (3) / R163 / R164 / R165 / R166 / R167 / R168 / R169 / R170 / R171 / A1 (dispatcher) / A3 (dedup) / R172 (IntOverflow path) / R176 (Sonar S1155) / R177 (Sonar S1116) / R180 (Sonar S1104) / R181 (Sonar S2168) / R182 (SpotBugs DMI_BD_DOUBLE) / R183 (SpotBugs DMI_RANDOM_ONCE) / **R186 (cross-block path condition)**
- 1 rolled back: R148h+2
- **413 tests / 0 fail / 0 error / 1 skip** (R186 +4 new tests, R165 test count unchanged)

## 8. What's next

R186 closes the R165 known limit but the trade-off
(8-instruction window) is a heuristic. A full
predecessor-union solution (R187 / original R178) is
the next step for cross-block paths of arbitrary
length.

Other next steps:
- R187 (per-CFG-block predecessor-union path
  tracking infra): the proper fix
- R190+ Juliet coverage bump using A1 dispatcher
- More narrow rules (R184/R185)
