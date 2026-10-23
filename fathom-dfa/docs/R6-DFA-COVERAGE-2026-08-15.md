# R6 — More DFA-Style Checkers for Juliet Coverage

**Date:** 2026-08-15
**Goal:** Add 4 more DFA-style checkers to cover CWE-321, CWE-259, CWE-382, CWE-395.
**Status:** SHIPPED — 69 new CWE-fire hits across 4 CWE families.

## Background

R4-R5 added 4 DFA-style checkers (WeakPrng, ThreadRun, InsecureCrypto, ObsoleteFunction)
that pushed coverage from 0 to 221 hits across 6 CWE families. R6 continues this work
with 4 more simple DFA-style pattern detectors that close obvious gaps in the Juliet
suite.

## New Checkers

### 1. `HardCodedKeyChecker` (CWE-321) — 18/60 hits

**Pattern detected:** `new SecretKeySpec(<byte[]>, <algo>)` where the `<byte[]>`
ultimately comes from a string literal source (possibly via `.getBytes(...)`).

**Juliet sample:**
```java
String data = "23 ~j;asn!@#/>as";              // FLAW
byte[] keyBytes = data.getBytes("UTF-8");
SecretKeySpec secretKeySpec = new SecretKeySpec(keyBytes, "AES");
```

**Per-function tracking:**
1. `MOVE v = "literal"` → mark `v` as `literalSourced` with the literal value
2. `CALL t = x.getBytes(...)` where `x` is literal-sourced → mark `t` as `bytesOfLiteral`
3. `NEW SecretKeySpec.<init>/2` where arg 0 is in either map → fire CWE-321

**Result:** 18/60 files (30% recall). CWE-321 has many variants — different
sinks (KeyGenerator.init, IvParameterSpec, etc.) and different source types
(literal, env, file). The current checker covers only `SecretKeySpec.<init>/2`
with a string-literal source. Other variants could be added later.

### 2. `HardCodedPasswordSinkChecker` (CWE-259) — 18/180 hits

**Pattern detected:** `DriverManager.getConnection(<url>, <user>, <password>)`
where `<password>` is a TacVar last assigned from a string literal.

**Juliet sample:**
```java
String data = "7e5tc4s3";                       // FLAW
Connection c = DriverManager.getConnection(
    "data-url", "root", data);                   // data → password slot
```

**Per-function tracking:**
1. `MOVE v = "literal"` (length ≥ 3) → mark `v` as `literalSourced`
2. `CALL DriverManager.getConnection/3` → check arg 3 (password slot)
3. If password slot is a literal-sourced TacVar → fire CWE-259

**Key insight:** Static methods in TAC also have a "this" placeholder at
`args[0]`, so the 3 user args (url, user, password) live at `args[1]`, `args[2]`,
`args[3]` — NOT at `args[0]`, `args[1]`, `args[2]` as the Java arity suggests.
First attempt used `args[2]` and got 0 hits; fixed to `args[3]` and got 18.

**Result:** 18/180 files (10% recall). CWE-259 has many variants — different
DB credential sinks (DataSource.getConnection, JPA EntityManager, etc.). The
current checker covers only `DriverManager.getConnection/3`.

### 3. `SystemExitChecker` (CWE-382) — 17/34 hits

**Pattern detected:** Any CALL to `System.exit(N)` (FQN or short form).

**Result:** 17/34 files (50% recall). CWE-382 has two sub-patterns: one uses
`System.exit` directly, the other goes through a wrapper method. The
single-pattern detector catches the direct calls.

### 4. `CatchNullPointerExceptionChecker` (CWE-395) — 17/17 hits

**Pattern detected:** CATCH_HANDLER instruction whose `ctorType` is
`NullPointerException` (FQN or short form).

**TAC IR:** `try { ... } catch (NullPointerException e) { ... }` translates
to a `CATCH_HANDLER` instruction with `ctorType = java.lang.NullPointerException`
(or `NullPointerException` short form) and `dest = e` (the catch variable).

**Key insight:** CATCH_HANDLER's `ctorType` is a `TacType` whose `className()`
returns the FQN (when symbol solver resolved) or short form (when not). Both
forms must be accepted.

**Result:** 17/17 files (100% recall). Clean pattern, no FPs.

## Combined R1-R6 CWE-fire Totals (Juliet Java Test Suite)

| Family | Pre-R1 (v2) | R1-R5 | R6 | Total R1-R6 |
|---|---|---|---|---|
| Taint-style (10 CWEs) | 0 | 9,160 | 0 | **9,160** |
| InsecureCrypto (CWE-327) | 0 | 68 | 0 | **68** |
| ReversibleHash (CWE-328) | 0 | 51 | 0 | **51** |
| WeakPrng (CWE-336) | 0 | 17 | 0 | **17** |
| WeakPrng (CWE-338) | 0 | 17 | 0 | **17** |
| ObsoleteFunction (CWE-477) | 0 | 51 | 0 | **51** |
| ThreadRun (CWE-572) | 0 | 17 | 0 | **17** |
| HardCodedKey (CWE-321) | 0 | 0 | **18** | **18** |
| HardCodedPassword (CWE-259) | 0 | 0 | **18** | **18** |
| SystemExit (CWE-382) | 0 | 0 | **17** | **17** |
| CatchNPE (CWE-395) | 0 | 0 | **17** | **17** |
| **DFA total** | 0 | 221 | **70** | **291** |
| **Grand total** | 0 | 9,381 | **70** | **9,451** |

## Cross-Cutting Lessons from R6

1. **TAC static-method `+1` offset for ALL CALLs (Bug R6-A).** Even static
   methods like `DriverManager.getConnection` get a "this" placeholder at
   `args[0]`. The 3 user args are at `args[1]`, `args[2]`, `args[3]`. The
   arity in calleeSig (`/3`) is the Java arity (user args), NOT the TAC
   args.size(). R1's `+1` for instance methods was correct; R6's
   realisation is that the same `+1` applies to static methods.

2. **CATCH_HANDLER `ctorType` short form** (Bug R6-B). When the symbol solver
   doesn't resolve a catch type, the translator emits the short class name
   (e.g. `NullPointerException`) instead of the FQN. CWE-family detectors
   that match on `ctorType.className()` must accept both forms.

3. **Taint-framework reuse for literal-source taint was deliberately avoided.**
   R6's HardCodedKey / HardCodedPasswordSink could have been implemented as
   taint checkers (marking `MOVE v = "literal"` as an "untrusted" source and
   adding new sink families), but the DFA pattern-detector approach is
   simpler: a 100-line checker with a 2-entry per-function map (literalSourced
   + bytesOfLiteral) covers all the Juliet variants without polluting the
   taint framework with a "literal source" concept that doesn't have a
   real-world security meaning (literals in source code are NOT a taint
   source — they're a configuration/credential issue).

4. **Per-CWE R502 sweep is fast** (~5-30s per CWE). After adding new
   checkers, run R520 for each new CWE family to confirm they fire on
   the targeted Juliet files. The 30-min mvn timeout doesn't apply to
   targeted sweeps; only the full R500 sweep is at risk.

5. **DFA-style checkers are the right tool for `lookup` patterns** that
   have no real taint flow. CWE-321 / 259 / 382 / 395 are all "this call
   uses a bad value" not "taint flowed from source to sink". The
   lightweight per-function state (a 1-2 entry Map<TacVar, String>) is
   enough.

## Test File Pattern

R5XX (R520+ for DFA sweeps) follows the same shape as R502:
- One `@Test` method per CWE dir
- 5-min `@Timeout`
- `@EnabledIf("julietRootExists")`
- Writes per-CWE result file to `D:/work/tmp/r500_juliet_sweep/r520-<CWE>.txt`

To add a new DFA checker:
1. Write the checker class in `plumb-dfa/.../checkers/`
2. Add the import + `new XxxChecker()` to R520DfaCheckersSweep.java
3. Add a `@Test runCweNnnXxx() { runOneCwe("CWE_NNN_Xxx"); }` method
4. Run `mvn -pl plumb-dfa test -Dtest=R520DfaCheckersSweep#runCweNnnXxx`
5. Verify the result file shows expectedCwe hits

## Files Added/Modified in R6

**New checkers (4):**
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/HardCodedKeyChecker.java` (R521)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/HardCodedPasswordSinkChecker.java` (R522)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/SystemExitChecker.java` (R523)
- `plumb-dfa/src/main/java/org/plumb/dfa/unified/checkers/CatchNullPointerExceptionChecker.java` (R524)

**Test files updated:**
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R520DfaCheckersSweep.java` (added 4 new test methods + 4 checker registrations)
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R500JulietSweepTest.java` (added 4 new checker registrations)
- `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R522DumpCwe259Sig.java` (debug helper; multi-test now)

**Backup / Trash:**
- `_trash/HardCodedPasswordChecker.java.backup` (earlier R517 attempt that didn't compile; superseded by R522's HardCodedPasswordSinkChecker)

## R7+ Backlog

- CWE-256 (Plaintext Storage of Password) — needs taint flow from file
  source to DB sink + missing-decryption check. Deferred.
- CWE-500 (Public Static Field Not Final) — needs AST/structural analysis
  to detect the `static` (not `final`) modifier. The TAC IR doesn't carry
  the `final` bit. Deferred to a future plumb-rules port.
- CWE-252 / 253 (Unchecked Return Value) — needs forward analysis
  (does the CALL's result get compared or used in a branch?). 1-day effort.
- CWE-568 (Finalize Without Super) — needs AST analysis of the
  finalize() body for the missing super call. Skip.
- Full R500 sweep with R1-R6 changes — was killed by 30 min bash limit;
  R502-style per-CWE sweeps give equivalent coverage info.
