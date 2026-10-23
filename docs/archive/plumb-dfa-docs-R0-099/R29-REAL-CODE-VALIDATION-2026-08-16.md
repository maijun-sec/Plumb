# R29 — Real-Codebase Validation

**Date**: 2026-08-16
**Round**: R29
**Status**: SHIPPED (with reduced scope due to IR builder perf)
**Effort**: ~30 min (mostly waiting on slow IR builder)

## Goal

Validate the R19-R28 source-reading DFA-style checkers on real
open-source Java code (Apache Commons Codec), not just Juliet's
synthetic test cases. Confirm they:
1. **Work** on non-Juliet code (no special imports, FQNs, complex
   packages).
2. **Don't over-fire** on real production-quality code.

## Background

R1-R28 was developed and tested primarily on the Juliet Test Suite
(Juliet_Java/src/testcases/CWE*/). Juliet is a synthetic corpus —
each test case is a single bad() / good1() / good2() template. The
IR builder's behavior on Juliet is well-understood.

Real codebases are different:
- Multi-class packages
- Complex generics, lambdas, nested classes
- FQN usage (`new javax.crypto.spec.IvParameterSpec(...)`) instead
  of imports
- Helper methods, factory methods, builder patterns
- No `/* FLAW */` markers

R29 runs the R19-R28 source-read checkers against
`D:/work/tmp/realcode/codec-src/` (Apache Commons Codec 1.16.0
sources, 80 .java files) to see if the checkers behave
sanely on real code.

## Constraints discovered

The IR builder (`TacTranslator`) is **very slow** on real code.
Some large files (25-30 KB) take 200+ seconds to translate.
Some files take 2000+ seconds. This is much slower than Juliet
because real code has more complex type hierarchies and the
IR builder does full type resolution.

The mvn 30-min hardcoded limit makes a full 80-file sweep
impossible. R29 reduced scope to **5 smallest files
(<5 KB each) + 1 Juliet positive control**.

## R29 ships

### New test: `R561RealCodeSweep.java`

A 1-test smoke sweep that:

1. Discovers `.java` files in `D:/work/tmp/realcode/codec-src/`
   filtered to <5 KB (fast to translate).
2. Sorts by size ascending, takes first 5.
3. Adds a known Juliet CWE-329 file as a **positive control**
   to confirm the pipeline still produces findings.
4. Runs the **7 R19-R28 source-read checkers** on each file:
   - `Cwe329RandomIvChecker` (R28)
   - `EnvVarInfoExposureChecker` (R23)
   - `InfoExposureQueryStringChecker` (R22)
   - `PublicStaticFieldNotFinalChecker` (R21)
   - `ReachableAssertionChecker` (R20)
   - `SensitiveInfoUnclearedChecker` (R24)
   - `SuspiciousCommentChecker` (R19/R21)
5. Tallies per-CWE and per-rule counts.

## Sweep results

```
R29/R561 Real-Codebase Sweep
  Source dir: D:\work\tmp\realcode\codec-src
  Files scanned: 6
  Files with fire: 1
  Total findings: 1
  Elapsed: 1309ms
  Checkers: 7
  Files (top 10 by size):
    - Caverphone.java (3650 bytes)
    - CharEncoding.java (4261 bytes)
    - Caverphone1.java (4630 bytes)
    - HmacAlgorithms.java (4711 bytes)
    - Caverphone2.java (4811 bytes)
    - CWE329_Not_Using_Random_IV_with_CBC_Mode__basic_01.java (3041 bytes)
  Per-CWE breakdown:
    CWE-329: 1
  Per-rule breakdown:
    Cwe329RandomIv: 1
```

**Reading**:
- **5 real-code files: 0 fires.** The R19-R28 source-read
  checkers don't over-fire on real code. No false positives.
- **1 Juliet positive control: 1 fire** (CWE-329, line 46). The
  test pipeline is verified working — the checkers still
  fire on synthetic bad patterns.

**Caveats**:
- Only 5 real files scanned. The 5 files are small
  (<5 KB) and represent the "easy" end of the real-code
  spectrum (small classes, simple methods).
- The 80-file full sweep is blocked by the IR builder's
  200-2000+ second per-file translation time. A
  full sweep is R5+ cross-function work.

## Validation signal

R29 is a **smoke test**, not a benchmark. The signal is:

| Indicator | R29 Result | Interpretation |
|-----------|-----------|----------------|
| Positive control fires | Yes (CWE-329) | Pipeline works |
| Real-code over-firing | 0 fires / 5 files | Good — no FP storm |
| Real-code TP discovery | 0 TPs / 5 files | Expected — files too small for complex patterns |
| Elapsed | 1.3s | Fast enough for CI |

## Recommended next steps for R29+ (R30+ candidates)

1. **Faster IR builder**: The 200-2000s/file translation time
   is a major blocker for real-codebase validation. The
   bottleneck is full type resolution. A type-resolution
   cache (across multiple files in the same compilation unit)
   would help. R30 candidate.
2. **Filter checkers by file characteristics**: Some
   checkers (e.g. CWE-329) only fire on files that import
   `javax.crypto.*`. A pre-filter on imports would
   skip irrelevant files in microseconds.
3. **Bigger real-code samples**: Once the IR builder is
   faster, run a 100+ file sweep on commons-codec +
   commons-lang3 + log4j and tally.
4. **Real TPs in real code**: Look for hand-written
   TPs in real code (e.g. tomcat, spring, jdk) to
   validate the checkers find real bugs, not just
   synthetic ones.

## Files added / changed

| File | Status | Purpose |
|------|--------|---------|
| `plumb-dfa/src/test/java/org/plumb/dfa/unified/integration/R561RealCodeSweep.java` | NEW | Real-code sweep (5 small + 1 control) |
| `D:/work/tmp/r500_juliet_sweep/r561-realcode.txt` | NEW | Sweep results |

## Cumulative R1-R29

- 37+ CWE families
- ~16,317 cweFire hits (R29 added 0 — no new cweFire, just validation)
- 107 checkers
- 207+ unit tests
- **New**: Real-codebase validation infrastructure
- 0 regressions
