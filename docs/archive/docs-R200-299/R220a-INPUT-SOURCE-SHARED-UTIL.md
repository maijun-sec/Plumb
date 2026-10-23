# R220a — Shared input-source pattern catalogue

Date: 2026-08-01
Status: **DONE** (9/9 R220a tests + 176/176 plumb-dfa tests, 0 regression)

## Goal

Polish step after R219: the R219 enhanced
checkers (IntOverflowChecker, DivByZeroChecker)
each carried their own private copy of the
"is this an input source call?" logic
(`isInputSourceCall`). Three issues with
that:

1. **Drift risk**: Adding a new input source
   pattern (e.g. `FileInputStream.read(...)`)
   required editing two files. Easy to forget
   one.
2. **Wasted code**: 30+ lines of substring
   matchers duplicated in two files.
3. **No extension point**: If a future checker
   (e.g. CWE-191 int underflow) wanted a
   slightly different catalogue, there was no
   way to provide one.

## Implementation (1 new file + 2 modified, ~10KB)

### `plumb-dfa/.../taint/InputSourcePatterns.java` (new, 3722 bytes)

Immutable catalogue of input-source call
patterns. The default set covers the standard
library methods Juliet uses:

- Reader / Scanner: `readLine / read / nextInt /
  nextLong / nextShort / nextByte / nextLine /
  nextFloat / nextDouble`
- Numeric parsing: `parseByte / parseShort /
  parseInt / parseLong / parseDouble /
  parseFloat`
- Environment: `System.getenv /
  System.getProperty`

Two ways to use it:

```java
// Static convenience (used by R215/R216
// mirrors):
if (InputSourcePatterns.isInputSourceCall(callee)) {
    // mark dest as hazard
}

// Instance form (custom catalogue):
InputSourcePatterns custom = new InputSourcePatterns(
    List.of(".fooBar", ".baz"));
if (custom.matches(callee)) { ... }
```

### Modified checkers (R220a)

- `IntOverflowChecker.java`: replaced
  private `isInputSourceCall(...)` (~16 lines)
  with `InputSourcePatterns.isInputSourceCall(...)`.
  Kept the local `isStringConcat(...)` helper
  since that's IntOverflow-specific.
- `DivByZeroChecker.java`: same.

### `plumb-dfa/.../taint/InputSourcePatternsTest.java` (new, 9 tests)

Locks the contract for the catalogue:
- `defaults_containsReadLineAndParseMethods` —
  guards the base list
- `defaults_containsScannerNextMethods` —
  guards the Scanner family
- `defaults_containsSystemEnv` — guards
  env-var access
- `staticIsInputSourceCall_matchesAllDefaultPatterns` —
  every default pattern matches a synthetic
  callee
- `staticIsInputSourceCall_nullCalleeIsFalse` —
  null safety
- `staticIsInputSourceCall_rejectsUnrelatedCall` —
  negative test
- `staticIsInputSourceCall_matchesCalleeSigFormat` —
  confirms the IR callee format
  `Class.method/arity` (with `.` not `#`)
- `customCatalogue_canExtendDefaults` —
  custom catalogue works
- `customCatalogue_doesNotMutateDefaults` —
  immutability

## Pitfall fixed during R220a (worth recording)

- **Callee IR format**: Initially wrote test
  with `BufferedReader#readLine/0` (Fathom /
  ASM-style). Wrong: the plumb-tac IR uses
  `BufferedReader.readLine/0` (with `.` not
  `#`). Fixed the test to match. This is the
  same pitfall we hit in R219 but we missed
  it in the unit test until the test ran.

## Test results

- R220a: 9/0/0/0
- plumb-dfa: 176/0/0/0 (was 167; +9
  R220a tests, 0 regression)
- R219 integration test: still 5/0/0/0;
  findings per family unchanged (CWE-89
  6,162, CWE-78 816, CWE-190 6,051, CWE-369
  3,862-3,958, CWE-476 119) — refactor is
  observably equivalent

## Backup

`D:\work\tmp\r220a_done\` (5 files, ~22KB)

## Next

- **R220**: if-guard detection for
  path-sensitive FP reduction. Goal: the
  CWE-190 goodB2G pattern
  `if (data < MAX) { data + 1 }` should
  not fire. Need to track BRANCH/JUMP
  control flow to know when a BINOP is
  inside a guard.
- **R222**: more mirror checkers for FN
  reduction (CWE-129, CWE-191, CWE-22).
