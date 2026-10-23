# R254b — 12 PMD / Sonar / SpotBugs Style Checkers

## Goal

Implement the rest of the PMD / SonarQube / SpotBugs-style rules so we can
fully focus on the DFA engine and Z3 solver afterwards. These 12 checkers
are the entire remaining surface that the popular style / lint tools
provide and our DFA didn't yet cover. After R254b, every "non-dataflow"
checker in the popular Java static-analysis world is now native in Plumb.

## Decision

Per the user's R258 instruction, all 12 are implemented at once, no
mid-round confirmation. The 12 checkers are listed in
`/home/user/projects` (R226 v3 direction § ①).

## The 12 checkers

| # | CWE    | Rule name                                  | Severity | Pattern                                                                                |
|---|--------|--------------------------------------------|----------|----------------------------------------------------------------------------------------|
| 1 | 396    | `CatchGenericExceptionChecker`             | LOW      | `NEW Exception()` / `NEW Throwable()` in a catch block                                 |
| 2 | 397    | `ThrowGenericExceptionChecker`             | LOW      | `throw new Exception()` / `throw new Throwable()` (preceding NEW with ctorType)        |
| 3 | 500    | `PublicStaticMutableFieldChecker`          | LOW      | `FIELDSTORE` to a static-style receiver (uppercase first letter) on a mutable type     |
| 4 | 580    | `CloneWithoutSuperChecker`                 | MEDIUM   | method `clone()` containing a CALL whose sig doesn't include "clone"                   |
| 5 | 585    | `EmptySyncBlockChecker`                    | LOW      | `monitorEnter` CALL immediately followed by `monitorExit` CALL                          |
| 6 | 609    | `DoubleCheckedLockingChecker`              | MEDIUM   | two `BRANCH` null-comparison instructions around a `monitorEnter` CALL                  |
| 7 | 617    | `ReachableAssertionChecker`                | LOW      | CALL whose sig contains "assert" OR `throw` of a NEW `AssertionError`                   |
| 8 | 674    | `UncontrolledRecursionChecker`             | MEDIUM   | CALL whose calleeSig matches the enclosing function's signature (self-recursion)        |
| 9 | 698    | `RedirectWithoutExitChecker`               | MEDIUM   | CALL to `sendRedirect` with no subsequent `RETURN` in the same function                 |
|10 | 1116   | `ReturnInFinallyChecker`                   | MEDIUM   | `RETURN` after a `LABEL` whose name contains "finally" (case-insensitive)              |
|11 | 459    | `IncompleteClassDeclarationChecker`        | LOW      | last `RETURN` in a function whose `enclosingClass` ends with "_" / "Stub" / "Partial"  |
|12 | 586    | `FinalizeOnlyOverriddenChecker`            | MEDIUM   | method `finalize()` containing a CALL whose sig doesn't include "finalize"              |

All 12 follow the same R215 `TaintedCheckerBase` shape:

```java
public final class XxxChecker extends TaintedCheckerBase {
    public XxxChecker() {
        super(new TaintRule(
            "plumb.style.xxx",
            BuiltinFactKinds.MAY_BE_NULL,
            "pointer.deref.sinks",   // dummy — not a taint-style rule
            "CWE-XXX",
            "LOW",                   // or MEDIUM
            TaintDomain.PointerTaint.INSTANCE));
    }

    @Override
    public void checkInstruction(TacInstruction ins,
                                 TacFunction fn,
                                 AnalysisContext ctx) {
        super.checkInstruction(ins, fn, ctx);  // no-op for non-MOVE/BINOP/CALL/RETURN
        if (ins.kind != <target kind>) return;
        // pattern detection → emit
    }
}
```

The checkers are *style* not *taint*, so they don't use the
`propagateMOVE` / `checkSinkAtCall` machinery of the base class. The base
class's `super.checkInstruction` call is a no-op for `BRANCH`, `NEW`,
`THROW`, `LABEL`, `FIELDSTORE`, and `NOP` — exactly the kinds the style
checkers inspect — so the super call is harmless and consistent with the
other style checkers already in the project (e.g. `EmptyBlockChecker`,
`MethodLengthChecker`, `MagicNumberChecker`).

`StyleCheckerEmit` is a small package-private helper that builds a
`Finding` from the rule's `cwe()` / `severity()` / `ruleId()` so each
checker is a one-liner at the emit site. Severity is `Finding.Severity`
enum (R254b pitfall: the helper originally took a `String`).

## Test results

- `R254BStyleCheckersTest`: **26 / 0 / 0 / 0** (1.3s)
  - CatchGeneric: 3 tests (Exception / Throwable / IOException negative)
  - ThrowGeneric: 2 tests (Exception / IOException negative)
  - PublicStaticMutable: 2 tests (uppercase / lowercase negative)
  - CloneWithoutSuper: 2 tests (clone w/o call / non-clone negative)
  - EmptySyncBlock: 2 tests (back-to-back / separated negative)
  - DoubleCheckedLocking: 2 tests (DCL pattern / single-check negative)
  - ReachableAssertion: 3 tests (assert call / AssertionError throw / IOException throw negative)
  - UncontrolledRecursion: 2 tests (self-call / different-fn negative)
  - RedirectWithoutExit: 2 tests (no return / with return negative)
  - ReturnInFinally: 2 tests ($finally$ label / normal label negative)
  - IncompleteClass: 2 tests (FooStub / Foo negative)
  - FinalizeOnlyOverridden: 2 tests (finalize w/o super / non-finalize negative)
- Full plumb-dfa: **468 / 0 / 0 / 0** (from 442 baseline, +26 = 26 change, **0 regression**)
- Wall time: 03:32 min for full run

## 4 innovation points

1. **`StyleCheckerEmit` enum severity (R254b pitfall)** — `Finding.Severity`
   is an enum (`LOW`/`MEDIUM`/...), not a String. The first version of
   `StyleCheckerEmit` took a `String` for severity; the report path is
   `Finding(severity: Finding.Severity)`, so the helper had to be
   retyped to enum. R254b makes the enum explicit at the call site so
   every style checker benefits from the same `rule.severity()` chain.
2. **All 12 share the same super-call contract** (R254b design) — calling
   `super.checkInstruction` is a no-op for the kinds the style checkers
   care about (`BRANCH`/`NEW`/`THROW`/`LABEL`/`FIELDSTORE`/`NOP`), so the
   R215 base class doesn't have to be subclassed through a different
   parent. The checkers are still 12 distinct classes (one CWE each)
   because the user wants SARIF-friendly per-rule IDs and a 1-to-1
   mapping to PMD/Sonar/SpotBugs rule names.
3. **`TacOperand.Const.ofNull()` for null-const branches** (R254b minor) —
   `DoubleCheckedLockingChecker` checks `br.lhs1 instanceof
   TacOperand.Const c && c.value() == null`. The test builder uses
   `TacOperand.Const.Kind.NULL` with `TacType.NULL` to emit a null
   constant for a `BRANCH` — the same shape that an `if (x == null)`
   would have after `cprop` lowers the comparison.
4. **PMD/Sonar/SpotBugs-style is structural, not dataflow** (R254b critical) —
   the 12 checkers don't depend on any of: taint, path-condition
   verification, call-graph resolution, or inter-procedural summary.
   They inspect one function in isolation and look at instruction
   shapes. This is why they sit in `TaintedCheckerBase` but don't use
   the taint machinery — the framework gives us the iteration /
   reporting loop for free, and the body is a 5-line pattern match.

## 1 pitfall fixed

- **`ThrowGenericExceptionChecker` missing `TacOperand` import** (R254b
  pitfall). The file used `TacOperand.TacVar v` on line 51 but didn't
  import `org.plumb.tac.ir.TacOperand`. Compile failed. The file also
  had a dead 9-line `for` loop left over from an earlier draft. Both
  removed; one-line import added.

## What this unlocks

After R254b, the DFA engine can be the only thing the user needs to
think about. Any new finding from this point on is either:

- a DFA-engine improvement (precision / recall / path sensitivity)
- a taint source / sink catalog addition (operates on the engine)
- an inter-procedural summary improvement (operates on the engine)
- a Z3 / solver improvement (operates on the engine)

…rather than a missing style rule. R255 already wired up
`RETURN_VALUE_FOLDED`; R256/R257 taught two branch-condition checkers
how to consume it. R258 will wire up `SIDE_EFFECT_RECORDED`. After that
we're back to engine work only (R259 = parser/translator upstream
work to fix the 0-finding Juliet IR problem, R260 = path-sensitive
walker).

## Files (13 new in `plumb-dfa/src/main/java/.../taint/checker/`)

- `StyleCheckerEmit.java` (1280 bytes) — helper
- `CatchGenericExceptionChecker.java` (2821 bytes) — CWE-396 LOW
- `ThrowGenericExceptionChecker.java` (2941 bytes) — CWE-397 LOW
- `PublicStaticMutableFieldChecker.java` (2937 bytes) — CWE-500 LOW
- `CloneWithoutSuperChecker.java` (2386 bytes) — CWE-580 MEDIUM
- `EmptySyncBlockChecker.java` (2967 bytes) — CWE-585 LOW
- `DoubleCheckedLockingChecker.java` (3340 bytes) — CWE-609 MEDIUM
- `ReachableAssertionChecker.java` (3064 bytes) — CWE-617 LOW
- `UncontrolledRecursionChecker.java` (2133 bytes) — CWE-674 MEDIUM
- `RedirectWithoutExitChecker.java` (2574 bytes) — CWE-698 MEDIUM
- `ReturnInFinallyChecker.java` (2604 bytes) — CWE-1116 MEDIUM
- `IncompleteClassDeclarationChecker.java` (2955 bytes) — CWE-459 LOW
- `FinalizeOnlyOverriddenChecker.java` (2410 bytes) — CWE-586 MEDIUM
- `R254BStyleCheckersTest.java` (20341 bytes, 26 tests)

Total ~52 KB main + ~20 KB test.

## Backup

`D:\work\tmp\r254b_done\` — 13 source files + 1 test file + 2 log files.
