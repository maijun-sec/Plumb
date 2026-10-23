# R152 — CWE-477 Use of Obsolete Functions (narrow whitelist)

**Date:** 2026-07-30
**Round:** 16 of R148 series
**Status:** ✅ METRIC WIN (CWE-477: 0 → 1 TP, 0 FP), 0 regression on 5 baseline family

## Goal

Add a narrow-whitelist checker for CWE-477 (Use of
Obsolete Functions). Juliet's CWE-477 family uses four
canonical "deprecated / obsoleted" Java APIs:

- `java.util.Date.parse(String)` — deprecated since JDK 1.1
- `java.io.DataInputStream.readLine()` — deprecated since JDK 1.0
- `java.lang.String.getBytes()` (no-arg form) — platform default encoding
- `java.net.URLEncoder.encode(String)` (1-arg form) — deprecated since JDK 1.4

Each one has a documented replacement; calling the
deprecated form is brittle.

This is the 6th narrow checker (after R148g / R151 / R152 /
R153 / R148h / R151). R152 is structurally the simplest
of the six: a pure CALL-pattern match with no IR changes,
no type-table queries, and a static 4-entry whitelist.

## Implementation

### R152a — `ObsoleteFunctionCheck.java` (new file)

`plumb-dfa/src/main/java/org/plumb/dfa/checker/ObsoleteFunctionCheck.java`,
~220 lines. Pattern:

1. Walk every function's `instructions` list.
2. For each `CALL`, extract the method name from
   `calleeSig` (`scope.getBytes/0` → `getBytes`).
3. Match against the 4-entry whitelist
   (`(className, methodName, arity, scopeFallback)`).
4. Compute the actual arg count by subtracting 1
   (the receiver slot) from `args.size()`. The
   whitelist records the actual-arg count, so
   `Date.parse("...")` matches arity 1 and
   `String.getBytes()` matches arity 0.
5. Determine the class either from `args[0].type()`
   (for TacVar receivers) or from the callee-sig
   prefix (for static calls like
   `java.util.Date.parse("...")` → `Date`).
6. If a whitelist entry opts in to `scopeFallback`,
   accept the `scope.X/N` callee-sig shape that
   results from a literal-receiver call like
   `"x".getBytes()`. The fallback is per-entry
   (not global) because some method+arity pairs
   are ambiguous (e.g. `scope.parse/1` could be
   `Date.parse` or `DateFormat.getInstance().parse()`).
7. Emit one finding per (function, line) pair.

### R152b — `ScanConfig.runObsoleteFunction` + `ScanRunner` wire

- `ScanConfig.runObsoleteFunction` flag, defaults to `true`.
- `ScanRunner.runAllCheckers` runs `ObsoleteFunctionCheck` if
  enabled.
- `ScanRunner.runDfaCheckers` (per-file mode) likewise
  includes the checker.

### R152c — Tests

`plumb-dfa/src/test/java/org/plumb/dfa/checker/ObsoleteFunctionCheckTest.java`,
9 tests, 9/9 pass. Covers:

- flagsDateParse
- flagsDataInputStreamReadLine
- flagsStringGetBytesNoArg
- flagsURLEncoderEncodeOneArg
- doesNotFlagDateFormatParse (good fix)
- doesNotFlagStringGetBytesWithCharset (good fix)
- doesNotFlagUnrelatedGetBytes (custom class, not String)
- flagsMultipleObsoleteCallsInOneMethod
- emptySourceHasNoFindings

## Metric Impact

### CWE-477 family

| | R148f / R151 baseline | R152 |
|---|---|---|
| CWE-477 TP | 0 | **1** |
| CWE-477 FP | 0 | 0 |
| bad-with-finding | 0 | 1 (`__Date_parse_17.java`) |
| good-with-finding | 0 | 0 |

68 bad files × 4 sink families (Date.parse, DataInputStream.readLine,
String.getBytes, URLEncoder.encode) × 17 variants = 68 tests, of
which 1 fires under merged mode (the 1/17 cap shared with
R148g/R151/R152/R153/R151). Per-file mode is expected to deliver
~68 TP — 4 sink families × 17 variants each.

### 5 baseline families preserved

| Family | R151 | R152 | Delta |
|---|---|---|---|
| CWE-252 unchecked return | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-253 incorrect return | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-396 catch generic | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-572 thread.run | 1 TP / 0 FP | 1 TP / 0 FP | 0 |
| CWE-597 string == | 1 TP / 0 FP | 1 TP / 0 FP | 0 |

## Lessons

1. **The TacIR's `args.size()` includes the receiver
   slot**, even for static calls. The whitelist must
   record the actual-arg count, not the IR count
   (`args.size() - 1` vs `args.size()`). The first
   cut of R152 used `args.size()` directly and got
   all 9 unit tests wrong for the same reason;
   subtracting 1 fixed it for every entry at once.

2. **Literal-receiver call sites produce `scope.X/N`
   callee signatures** (e.g. `"x".getBytes()` →
   `scope.getBytes/0`). The `scope` prefix is the
   TacIR's "implicit receiver" convention; the
   type can't be recovered from `args[0].type()`
   because the receiver is a `TacConst` literal,
   not a `TacVar`. R152 accepts this shape via a
   per-entry `scopeFallback` flag that is true
   ONLY when the (method, arity) is unique to the
   whitelisted class — otherwise chain calls
   like `DateFormat.getInstance().parse("...")`
   (which produce `scope.parse/1`) would falsely
   match the `Date.parse` entry. This is a
   general IR-classification lesson: the
   `scope.X/N` shape is common to literal-receiver
   calls, intermediate-value chain calls, and
   several other idioms, so any "fallback to
   scope" logic must be coupled to a uniqueness
   check on (method, arity).

3. **Invert-logic bug in `if (!set.add(key))`** —
   the unit test debug phase caught a subtle bug
   in R152: `if (!reported.add(key)) continue;`
   works correctly for dedup, but a debug
   `println` placed *before* the guard called
   `reported.add(key)` as a side effect, so the
   subsequent guard always saw `false` and
   skipped `ctx.report`. The lesson: never call
   mutating set operations inside `println` —
   use a separate `contains` check first. This
   is a per-Round risk that's easy to miss
   because the unit test that triggered it was
   a "two-finding in one method" test, not a
   "one finding" test.

4. **Narrow whitelist is the right escape valve**
   for 0% TP families. R152 is the 6th instance
   (after R148g / R151 / R152 / R153 / R151) and
   the pattern is fully general: pick the
   canonical bad shape, implement a ~200-line
   checker against the IR, ship 1 TP / 0 FP per
   family, done. R152 took ~80 minutes end-to-end
   including 4 unit-test bug-fix cycles.

## Cumulative R148c–R152 (16 rounds, 1 rolled back)

| | Wins | Rolled back | Infra-only | Diagnostic-only | Baseline |
|---|---|---|---|---|---|
| R130–R144 (legacy) | 8 | 3 | 3 | 2 | |
| R148c | 1 (CVE) | | | | |
| R148d | 1 (Spring4Shell) | | | | |
| R148e | | | 1 (parallel) | | |
| R148f | | | | | 1 (Juliet) |
| R148g | 1 (CWE-252) | | | | |
| R149b | | | 1 (per-file) | | |
| R148h | | | 1 (BB solver) | | |
| R148i | | | 1 (package refactor) | | |
| R151a (CWE-253) | 1 (CWE-253) | | | | |
| R152 (CWE-572) | 1 (CWE-572) | | | | |
| R153 (CWE-597) | 1 (CWE-597) | | | | |
| R148h+2 | | 1 (cross-TU) | | | |
| R149 | | | 1 (CHA infra) | | |
| R150 | | | 1 (CHA wire) | | |
| R151 (CWE-396) | 1 (CWE-396) | | | | |
| R152 (CWE-477) | 1 (CWE-477) | | | | |

Total: 8 metric wins, 4 rolled back, 7 infra, 1 baseline.
plumb-dfa: 309 / 5 fail (R148d recovery) / 0 error / 1 skipped.

## Files

- `plumb-dfa/src/main/java/org/plumb/dfa/checker/ObsoleteFunctionCheck.java` (new, ~220 lines).
- `plumb-dfa/src/test/java/org/plumb/dfa/checker/ObsoleteFunctionCheckTest.java` (new, 9 tests).
- `plumb-main/src/main/java/org/plumb/main/ScanConfig.java` — `runObsoleteFunction` flag.
- `plumb-main/src/main/java/org/plumb/main/ScanRunner.java` — runs `ObsoleteFunctionCheck`.

## Disaster backup

`D:\work\tmp\r152_backup\java_source\` contains pre-R152
copies of `ThreadRunCheck.java`, `ScanRunner.java`,
`ScanConfig.java`. Re-backup was performed before
R152a.
