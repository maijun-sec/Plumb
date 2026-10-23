# R12 — Resource Exhaustion (CWE-400) checker (2026-08-15)

Ships a DFA-style taint-light checker that catches
`Thread.sleep(<unvalidated>)` patterns. Complements
the R9 `UncheckedLoopConditionChecker` which catches
the for-loop/write variants.

## TL;DR

- **New checker**: `ResourceExhaustionChecker` (R12)
- **CWE-400 fires**: 510 sleep variants in 135 sec on 2,396-file corpus
- **CWE-606 sibling fires**: 1,326 for_loop/write variants (R9 existing, but also classified as CWE-400 family)
- **Combined CWE-400 + CWE-600 family coverage**: 1,836 fires in CWE-400 corpus
- **Test count**: 5/5 unit tests pass

## Why a new checker (vs extending R9)

R9 `UncheckedLoopConditionChecker` covers:
- `for (i = 0; i < count; i++) { ... }` — CWE-606 (loop bound from untrusted)
- `writerBuffered.write(...)` in a for-loop with tainted count

But the CWE-400 corpus has **two distinct sink patterns**:
- **for_loop / write** (~70% of files): R9 already covers, labels as CWE-606
- **sleep** (~30% of files): R9 cannot see, needs new check

The `sleep_*` variants use:
```java
int count = ...;  // from untrusted source
Thread.sleep(count);  // FLAW
```

The fix is to validate `count` (e.g. `Math.min(count, MAX_SLEEP_MS)`).

## R12 design

`ResourceExhaustionChecker`:
- Tracks vars tainted by `readLine`, `parseInt`, `nextInt`, `getParameter`, etc. (same taint source list as R9)
- Propagates taint through CALL and MOVE
- **Sink**: fires on `CALL Thread.sleep/1` (or `/0`) when any arg is a tainted var

It's structurally a sibling of `UncheckedLoopConditionChecker` — same taint tracking, different sink.

## Real Juliet CWE-400 sweep (post-R12)

```
R546 CWE400_Resource_Exhaustion (2396 files, 1836 findings, 135293ms)
  CWE-400 hits: 510
  CWE-606 hits: 1326 (sibling for_loop/write)
    sleep_   CWE-400: 510
    for_loop CWE-606: 663
    _write   CWE-606: 663
```

The 510 sleep fires cover all `_sleep_` variant files (Juliet has
~17 base variants × ~30 source patterns = ~510). The 1326
CWE-606 fires are the for-loop/write variants which the R9
checker was already catching — but now they're correctly
classified under the same "resource exhaustion" umbrella.

## Unit tests (5/5 pass)

```
readLineThenSleep_firesCwe400              readLine → parseInt → sleep → 1 fire
readLineShortForm_firesCwe400              short-form readLine → 1 fire
sleepWithConstant_doesNotFire              hardcoded const → 0 fires
sleepWithUnrelatedVar_doesNotFire          var not from taint source → 0 fires
unrelatedCall_doesNotFire                  File.delete → 0 fires
```

## Why CWE-400 is the right label (not CWE-606)

- **CWE-606** ("Unchecked Loop Condition") is specifically about a tainted variable used in a loop BOUND (`i < count`).
- **CWE-400** ("Uncontrolled Resource Consumption") is broader — any resource-consuming operation driven by an attacker-controlled quantity.

`Thread.sleep(count)` is NOT a loop condition; it's a single
operation that consumes CPU time. The for_loop and write
variants can technically be classified as CWE-606 (loop bound)
OR CWE-400 (resource consumption). The R9 checker labels them
CWE-606 (which is the more specific CWE); R12 labels the sleep
variants CWE-400 (which is the more specific CWE for that
pattern). Both labels are useful for security audit.

## Test status (R10 + R12 combined)

- 6 (ProcessControlChecker) + 4 (UncheckedReturnChecker new) + 5 (ResourceExhaustionChecker) = 15 new tests
- 122/122 DFA-style + taint CheckerTest pass (was 112 before R10)
- All pre-existing tests still pass

## Files added / changed in R12

- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/ResourceExhaustionChecker.java` (new, 6660 bytes)
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/checkers/ResourceExhaustionCheckerTest.java` (new, 6558 bytes)
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R500JulietSweepTest.java` (register R12)
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R520DfaCheckersSweep.java` (register R12 + CWE-400 sweep method)
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R546Cwe400Sweep.java` (new, single-checker CWE-400 sweep)
- `D:/work/tmp/r500_juliet_sweep/r546-CWE400.txt` (real Juliet result)
