# R333 DivByZero float/double filter

**Date**: 2026-08-09
**Status**: APPLIED (both legacy + unified checkers)

## Problem

`DivByZeroCheck` (legacy C/C++) and `DivByZeroChecker` (unified Java
DFA) both fire CWE-369 on any `DIV` / `MOD` `BINOP` whose rhs
`Interval` contains 0. But in Java, **float / double division by
zero does NOT throw an exception** — it produces `Infinity` or
`NaN` per IEEE 754. This means every CWE-369 Juliet test with a
`float` or `double` divisor was a false positive.

Juliet CWE-369 s01 directory contains 100% `float_` files (no
`int_`/`long_`/`short_`/`char_`). The pre-R333 baseline showed:

| CWE-369 s01 (float-only) | Pre-R333 |
|---|---:|
| Total fires | 2584 |
| True positives | 0 (all float div, no exception) |
| False positives | 2584 |

## Fix

`isFloatDivision(TacInstruction)` helper: returns true if either
operand is a `TacVar` typed `FLOAT` / `DOUBLE`, or a `Const` of kind
`FLOAT` / `DOUBLE`. Applied to **both** checkers:

- `plumb-dfa/.../DivByZeroCheck.java` (legacy, R189 path)
- `plumb-dfa/.../DivByZeroChecker.java` (unified)

The dest type is usually `UNKNOWN` in the IR (TacBuilder doesn't
propagate binop result type), so the check goes through operand
types instead.

## Result

| CWE-369 directory | Pre-R333 | Post-R333 | Delta |
|---|---:|---:|---:|
| s01 (all float_) | 2584 | 0 | -2584 |
| s02 (mixed) | (TBD) | 254 int + 599 (file misattribution) | mostly TPs |
| Total precision improvement | | | 100% on s01 |

CWE-369 s01 went from 2584 FP to 0 fires (because all files in s01
are `float_`).

## Test impact

3 pre-existing tests expected float div by zero to fire (R341
debug, R341 goodG2B suppress, R347 math.abs guard). Updated to
reflect the new (correct) behavior — float div does not fire.

- `R341Cwe369DebugTest.floatDivByZeroFires` → `floatDivByZeroDoesNotFire`
- `R341Cwe369GoodG2BSuppressTest.zeroFloatLiteralDivisorStillFires` → now `isEmpty`
- `R341Cwe369GoodG2BSuppressTest.zeroDoubleLiteralDivisorStillFires` → now `isEmpty`
- `R347MathAbsGuardTest.unconstrainedDataStillFires` → now `isEmpty`
- `R347MathAbsGuardTest.mathAbsNegativeEpsilonDoesNotSuppress` → now `isEmpty`

## Pitfall (跨项目通用)

- **Float division is NOT integer division in any IEEE-754 JVM**:
  `100.0 / 0.0` produces `Infinity` (not an exception), and
  `0.0 / 0.0` produces `NaN`. Static analyzers that fire
  "divide-by-zero" on float types produce 100% FP for any
  codebase using doubles. **Always** filter float/double
  operands from integer-arithmetic overflow/underflow checks
  unless the rule explicitly covers IEEE-754 edge cases.
- **Dest type is often UNKNOWN in TAC IR**: the TacBuilder
  doesn't propagate binop result types. To check if a BINOP is
  a float operation, look at the operand types (`lhs1.type()`,
  `lhs2.type()`) or the `Const.kind()` — not the `dest.type()`.
- **Pre-existing tests for "this used to fire" are common
  liabilities**: when a behavior changes from "FP" to "correctly
  no-fire", find and update every test that asserted the old
  behavior, even if the test name (e.g. `floatDivByZeroFires`)
  still matches the old intent.
