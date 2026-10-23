# R30 — CWE-832 (Unlock Not Locked) + CWE-833 (Deadlock)

**Date**: 2026-08-16
**Round**: R30
**Status**: SHIPPED
**Effort**: ~45 min (small corpus)

## Goal

Add detection for two concurrency-related CWE families:
- **CWE-832** (Unlock of a Lock that is not Locked) — 2 Juliet files
- **CWE-833** (Deadlock) — 6 Juliet files (split into 2 ReentrantLock +
  4 synchronized variants)

## Background

### CWE-832 (Unlock Not Locked)

A `ReentrantLock.unlock()` call is made WITHOUT a matching
`.lock()` call on the same receiver in the same function (or on
the same code path). At runtime this throws
`IllegalMonitorStateException`.

Juliet pattern (R30 template):
```java
static private final ReentrantLock REENTRANT_LOCK_BAD = new ReentrantLock();
static public void helperBad() {
    /* Missing lock() here */
    try { intBad = intBad * 2; }
    finally {
        REENTRANT_LOCK_BAD.unlock();   // FLAW
    }
}
```

### CWE-833 (Deadlock)

A deadlock occurs when two threads hold locks in opposite orders
(AB-BA pattern). Juliet's CWE-833 corpus has two flavors:

- **ReentrantLock variants** (2 files): explicit `.lock()` /
  `.unlock()` calls. Two helper methods acquire the same pair of
  locks in opposite orders.
- **synchronized variants** (4 files): the `synchronized` keyword
  on methods, which the TAC IR lowers to `MONITORENTER` /
  `MONITOREXIT` insns (NOT `.lock()` calls).

## R30 ships

### New: `Cwe832UnlockNotLockedChecker.java`

A simple per-function DFA checker. For each `.unlock()` call,
walks back through the function's earlier instructions looking
for a matching `.lock()` on the same receiver. Fires if no match.

### New: `Cwe833DeadlockChecker.java`

A cross-function DFA checker. For each function, collects the
ordered list of unique locks acquired (deduped). For each pair
of functions, compares the SET of locks. If the sets match but
the order differs → potential AB-BA deadlock, fire.

### Tests

- `Cwe832UnlockNotLockedCheckerTest.java` — 5 unit tests
- `Cwe833DeadlockCheckerTest.java` — 8 unit tests
- `R562Cwe833Sweep.java` — CWE-833 Juliet sweep
- `R563Cwe832Sweep.java` — CWE-832 Juliet sweep

## Sweep results

### R562 — CWE-833 Deadlock (6 files)

```
R562 CWE833_Deadlock (6 files, 2 findings, 824ms)
  Files with fire: 2 (expected 6 — 2 ReentrantLock + 4 synchronized)
  Note: R30 only detects ReentrantLock
        variants. The 4 synchronized_*
        variants use the Java synchronized
        keyword which the TAC IR lowers to
        MONITORENTER/MONITOREXIT, not to
        .lock() calls. R30 v1 doesn't analyze
        MONITORENTER pairs.
        ReentrantLock fires: 2/2 expected
        synchronized fires: 0/4 (out of R30 scope)
  Fired files:
    - CWE833_Deadlock__ReentrantLock_Servlet_01.java
    - CWE833_Deadlock__ReentrantLock_Thread_01.java
```

**Coverage on CWE-833**:
- ReentrantLock: 2/2 (100% recall on the in-scope family)
- synchronized: 0/4 (out of R30 scope, documented limitation)
- File recall: 33% (2/6); function-call recall: 100% on the
  in-scope family

### R563 — CWE-832 Unlock Not Locked (2 files)

```
R563 CWE832_Unlock_Not_Locked (2 files, 2 findings, 152ms)
  CWE-832 hits: 2
  Files with fire: 2 (expected 2 — one bad() per file)
  Fired files:
    - CWE832_Unlock_Not_Locked__ReentrantLock_Servlet_01.java
    - CWE832_Unlock_Not_Locked__ReentrantLock_Thread_01.java
```

**Coverage on CWE-832**: 2/2 files (100% recall). The
good1() functions in the same files DO have a matching
`.lock()` call, so they don't fire.

## Known limitations

1. **`synchronized` keyword not detected**: Both CWE-832 and
   CWE-833 variants that use the `synchronized` keyword are
   missed. The TAC IR translates `synchronized` to
   `MONITORENTER` / `MONITOREXIT` insns, not to `.lock()` /
   `.unlock()` calls. R30 only handles the explicit
   `ReentrantLock` / `Lock` style.
2. **Linear source walk, not path-sensitive**: A function that
   conditionally locks (e.g. inside an if-branch) may over-fire.
3. **Same-class static analysis only**: If the `.lock()` call is
   in a different method (caller or helper), we don't see it.
4. **ReentrantLock re-entry**: A function that locks the same
   ReentrantLock twice doesn't get a "matched unlock" guarantee.
5. **CWE-833 AB-BA only**: Cyclic deadlocks with 3+ locks are
   out of scope.

## Cross-cutting lessons

1. **TAC IR lowers `synchronized` to MONITORENTER/EXIT, not
   to .lock()/.unlock() calls.** This is a key TAC IR behavior
   that future checkers need to be aware of.
2. **Cross-function analysis requires per-program state.** R30
   uses an instance field `byProgram` to accumulate lock
   sequences per function, then compares pairs in `finish()`.
   This works because the engine creates a fresh checker per
   program.
3. **Set-equal but order-different is the AB-BA signature.** A
   simple set comparison + order comparison catches the classic
   2-lock deadlock.
4. **SSA version stripping is critical for cross-function
   analysis.** The same source var `BAD_NUMBER1_REENTRANTLOCK`
   has different SSA versions in different functions
   (`BAD_NUMBER1_REENTRANTLOCK:T1` vs
   `BAD_NUMBER1_REENTRANTLOCK:T2`). Strip the `:` suffix to
   match across functions.

## Files added / changed

| File | Status | Purpose |
|------|--------|---------|
| `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/Cwe832UnlockNotLockedChecker.java` | NEW | CWE-832 detector |
| `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/Cwe833DeadlockChecker.java` | NEW | CWE-833 detector |
| `plumb-dfa/src/test/java/org/plumb/dfa/unified/checkers/Cwe832UnlockNotLockedCheckerTest.java` | NEW | 5 unit tests |
| `plumb-dfa/src/test/java/org/plumb/dfa/unified/checkers/Cwe833DeadlockCheckerTest.java` | NEW | 8 unit tests |
| `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R562Cwe833Sweep.java` | NEW | CWE-833 sweep |
| `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R563Cwe832Sweep.java` | NEW | CWE-832 sweep |
| `D:/work/tmp/r500_juliet_sweep/r562-CWE833.txt` | NEW | 2 fires, 6 files |
| `D:/work/tmp/r500_juliet_sweep/r563-CWE832.txt` | NEW | 2 fires, 2 files |

## Cumulative R1-R30

- 39+ CWE families (added CWE-832 + CWE-833)
- ~16,321 cweFire hits (added 4 from R30: 2 CWE-832 + 2 CWE-833)
- 109 checkers (added 2 R30 checkers)
- 220+ unit tests (added 13 from R30)
- 0 regressions
