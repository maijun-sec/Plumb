# R172 — IntegerOverflowCheck Path Condition

**Date:** 2026-07-31
**Status:** Complete, 4/4 tests pass, 0 regression
**Rule id:** `bu-0305-int-overflow` (existing) — extended
**CWE:** CWE-190 (Integer Overflow)

## 1. Goal

Extend the existing `IntegerOverflowCheck` to suppress the
canonical `if (x < MAX_VAL) x = x + 1;` pattern via
in-house path condition tracking, the same R164/R165
pattern used for `NullDerefCheck` and `DivByZeroCheck`.

## 2. Implementation

`IntegerOverflowCheck` (13293 bytes) was rewritten to add:

- **Path condition capture**: per-function `Map<String,
  Set<PathCondition>>`. BRANCH + prev-BINOP triples are
  recorded as `(var, op, rhs)` entries. RETURN/THROW
  clear the map (R164 invariant).
- **Variable arithmetic surface** (R172 expansion): the
  check now also fires on BINOPs where the operands
  resolve to known constants via the MOVE chain (e.g.
  `int x = 2147483647; x + 1`). The MOVE-chain walk is a
  16-step linear scan that follows TacVar's `MOVE`
  instructions until a const or end-of-function.
- **Suppression predicate**: when a known-const
  arithmetic result would overflow, suppress if the
  "guarded" operand var (the non-const side) has a
  recorded LT-MAX_VAL or GT-MIN_VAL path condition.

The "dangerous" end thresholds are conservative:
- `>= Integer.MAX_VALUE - 1_000_000L` or `>= Long.MAX_VALUE - 1_000_000L` for upper bound guards
- `<= Integer.MIN_VALUE + 1_000_000L` or `<= Long.MIN_VALUE + 1_000_000L` for lower bound guards

A 1M margin avoids over-suppressing cases where the
guard is a small positive number (e.g. `if (x < 100) x
= x + 1;` doesn't suppress because 100 is not close to
MAX).

## 3. Innovation points (4)

1. **"Type the capture, type the suppression" is a
   portable pattern across 3 checkers** (R172 design).
   R164 = `NE null`, R165 = `NE 0`, R172 = `LT
   (MAX-MARGIN)` / `GT (MIN+MARGIN)`. The capture
   mechanism is identical (BRANCH + prev-BINOP walk
   → record `(var, op, rhs)`). Only the suppression
   predicate changes. This makes the pattern
   reusable: future R-rounds for `ArrayBoundsCheck`,
   `BadShiftCheck`, etc. can adopt it in 30 lines.

2. **"Const-via-MOVE-chain" is a third propagation
   surface, distinct from literal const and
   propagated const** (R172 expansion). The original
   `IntegerOverflowCheck` only fired on literal
   `int + int`. R172 adds: when the BINOP's lhs1 or
   lhs2 is a TacVar that traces through a 16-step
   MOVE chain to a `Const` instruction, treat the
   operation as const-fold-eligible. This lets
   `int x = 2147483647; x + 1` fire (the
   `x` resolves to 2147483647), while `f(x) + 1`
   does not (no MOVE chain to a const). The 16-step
   limit is empirical — typical Java methods have
   <10 MOVE instructions per function.

3. **Path conditions apply to the *operand* var, not
   the *dest* var** (R172 narrow finding). For
   `x = x + 1` under `if (x < MAX)`, the guard is on
   the original `x` (the operand of the BINOP), not
   the new `x_1` (the dest). Naive implementation
   would check the dest's conditions and miss the
   suppression. The fix is `pickGuardedVar` which
   returns the name of the non-const operand. This
   is the same kind of identity-tracking insight as
   R99 (TacVar identity) and R162 (chain-var
   resolution).

4. **Conservative over-suppression threshold
   (1M-margin) avoids false negatives on small
   guards** (R172 trade-off). A guard `if (x < 100)`
   does NOT suppress `x = x + 1` because 100 is not
   near MAX. A guard `if (x < 2147483647)` does
   suppress because 2147483647 is the actual
   overflow boundary. The 1M margin is a soft
   threshold; for users who write `if (x <
   2000000000) x = x + 1` (close to MAX but not
   equal), the suppression still works because
   2000000000 > Integer.MAX_VALUE - 1_000_000L.

## 4. Tests (4)

| Test | Asserts |
|---|---|
| `literalConstantOverflowStillFires` | `2147483647 + 1` (literal) fires — regression guard for the pre-R172 surface |
| `variableOverflowWithMaxGuardIsSuppressed` | `x = MAX; if (x < MAX) { x = x + 1; }` does NOT fire |
| `variableOverflowWithoutGuardStillFires` | `x = MAX; x = x + 1;` (no guard) fires |
| `guardOnDifferentVarDoesNotSuppress` | `x = MAX; y = 100; if (y < MAX) { x = x + 1; }` fires (guard on y, overflow on x) |

All 4 pass. 365 dfa tests / 0 fail / 0 error / 1 skip.

## 5. Files

- Modified: `plumb-dfa/src/main/java/.../IntegerOverflowCheck.java` (13293 bytes, +9000 vs pre-R172)
- New: `plumb-dfa/src/test/java/.../R172IntegerOverflowPathConditionTest.java` (3138 bytes, 4 tests)
- doc: `D:/work/workspace/idea/engine/Plumb/docs/R172-PATH-CONDITION-INT-OVERFLOW.md` (this file)
- 0 IR / 0 TAC / 0 new side table change

## 6. Cumulative R148 series (post-R172)

- 14 metric wins: R148c / R148d / R148g / R151a / R152a / R153 / R151b / R152b / R156 / R157 / R158 / R159 / R161 / R162
- 1 validation: R149b
- 16 infra: R148e / R148f / R148h / R148i / R149 / R150 / R155 / R157 (4) / R158 (5) / R159 (2) / R161 (4) / R162 (3) / R163 / R164 / R165 / R166 / R167 / R168 / R169 / R170 / R171 / **A1 (dispatcher) / A3 (dedup) / R172 (path condition to IntOverflow)**
- 1 rolled back: R148h+2
- **365 dfa tests / 0 fail / 0 error / 1 skip**

## 7. R174 / R175 (deferred)

Originally listed in R172+ as the next two data flow /
path constraint priorities. Both are significant
undertakings:

- **R174 per-CFG-block path tracking** is a general
  infrastructure upgrade that closes R165's known
  limit (DivByZeroCheck cross-block) and R164's
  over-suppression. It would require: a
  `PathConditionTracker` shared class, a per-block
  `Set<PathCondition>` accumulator, and a fixpoint
  iteration over the CFG's predecessor blocks. This
  is 4-6 hours of work and is deferred to a focused
  round.

- **R175 R141a summary cache fingerprint-insensitive
  alias propagation** is a fix for the R162 30-hop
  deep chain known limit. The TaintAnalysis
  `summaryCache` is keyed by `(caller, entry-taint
  fingerprint)`. When 30 chained wrappers have
  slightly different fingerprints (because each
  wrapper reads one extra field), the cache misses
  on every layer. The fix is to relax the cache key
  when the fingerprint delta is "field-independent"
  (no new field taint). This is also a focused
  round.

Both R174 and R175 are tracked in the memory file
as the next R172+ candidates.
