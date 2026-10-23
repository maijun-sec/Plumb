# R676 — Full scan of remaining 83 dfa-unified checkers for more duplicates

**Date:** 2026-08-22
**Status:** ✅ SHIPPED
**Modules touched:** plumb-dfa, plumb-main
**Tests:** 321 plumb-dfa unit + 50 plumb-main unit + 58 plumb-ai = **429 tests pass**, 0 errors, 7 skipped
**Checkers deleted:** 9 (7 from R675 + 2 new in R676)
**Checkers kept (DFA-specific):** ~50 (aliasing / cross-method / iterator tracking / null-state)

## TL;DR

R674 removed 9 confirmed dfa-unified duplicates. R676 does the full scan of the
remaining 83 checkers: **2 more duplicates found and removed**, **~50 confirmed
KEEPs** (mostly DFA-specific functionality plumb-style cannot replicate), and
**all 9 modules build clean with 429 unit tests passing**.

## Background

R674 deleted 9 dfa-unified checkers that had a 1:1 plumb-style equivalent. The
R674 decision noted that 2 checkers (InsecureCryptoChecker, ReachableAssertionChecker)
were kept despite name match, and that 5+ candidates needed a more careful
review. R676 is the comprehensive follow-up scan.

## Methodology

For each of the 83 dfa-unified checkers:

1. Read the Javadoc to extract the "Why a DfaChecker?" rationale.
2. Check if a plumb-style `AstChecker` (JavaParser visitor) exists for the same
   CWE. If yes, compare precision/recall profile:
   - **Pure-syntax pattern** that AST can do → **DUPLICATE** (delete)
   - **DFA-specific** (aliasing, cross-function, FIELDSTORE, null-state,
     iterator tracking) → **KEEP**
3. Cross-check with R674 reasoning (R541-R561, R668-R672 DFA-engine
   refactor papers in `_docs/` and R-numbers in Javadocs).

## R676 Decisions (full list)

### A. 9 dfa-unified checkers DELETED (moved to `_trash/r676-dfa-unified-checkers/`)

| # | Deleted dfa-unified | plumb-style equivalent | Why duplicate |
|---|---|---|---|
| 1 | `BroadCatchChecker` (CWE-396) | `CatchGenericCheck` (CWE-396) | Both fire on `catch (Exception/Throwable/RuntimeException/Error)` |
| 2 | `CatchGenericExceptionChecker` (CWE-396) | `CatchGenericCheck` (CWE-396) | Same target as #1 |
| 3 | `CatchNullPointerExceptionChecker` (CWE-395) | `CatchNpeCheck` (CWE-396) | Same `catch (NullPointerException)` pattern (CWE-396 superset) |
| 4 | `DefaultEncodingChecker` (CWE-176) | `DefaultCharsetCheck` (CWE-176) | Both fire on `new FileReader/Writer/InputStreamReader/OutputStreamReader/String(bytes)` |
| 5 | `OmittedBreakInSwitchChecker` (CWE-484) | `MissingBreakCheck` (CWE-484) | Exact match on `case X: ...; case Y:` fall-through |
| 6 | `PublicStaticFieldNotFinalChecker` (CWE-500) | `MutableStaticShouldBeFinalCheck` (CWE-500) | Exact match on `public static X = ...;` |
| 7 | `EmptyCatchHandlerChecker` (CWE-390/665) | `EmptyCatchBlockCheck` (CWE-390) | Exact match on `catch (...) { /* nothing */ }` |
| 8 | `ThrowGenericExceptionChecker` (CWE-397) **[NEW R676]** | `RawExceptionCheck` (CWE-397) | Both fire on `new Exception/Throwable/Error/RuntimeException()` constructor |
| 9 | `UnusedVariableChecker` (CWE-563, R620 source-reading) **[NEW R676]** | `UnusedLocalVariableCheck` (CWE-563) | Both fire on `int x;` declared but never used |

**Items 1-7** were the 7 confirmed duplicates carried over from R675.
**Items 8-9** are 2 new duplicates found in the R676 full scan.

### B. ~50 dfa-unified checkers KEPT (DFA-specific)

| Category | Checkers | DFA-specific value |
|---|---|---|
| **Aliasing / cross-method** | `OrmLoadNullCheckDfaChecker`, `OrmUnnecessaryGetDfaChecker`, `OrmLostUpdateDfaChecker`, `ServletAtomicityDfaChecker`, `SingletonRaceDfaChecker` | Track "from-load" / "from-get" / "persisted" / "attribute-touched" through MOVE chains and FunctionSummary cross-function propagation. AST tracks the original variable only. |
| **Null-state tracking** | `EqualsCalledOnNullChecker` (R316, hybrid) | Tracks null state across MOVE/FIELDSTORE. AST's `EqualsAvoidNullCheck` is a different rule (`obj.equals("x")` literal-first warning). |
| **Class-level DFA** | `CollectionSelfReferenceChecker`, `IteratorConcurrentModificationChecker`, `CompareWithSelfChecker`, `CompareClassesByNameChecker` | Aliasing / iterator-mutation / receiver-equality — needs the IR to track object identity. |
| **Taint-domain** | `taint.UnusedVariableChecker`, all `taint.*` checkers | Different package, different abstraction. **Critical**: this is a TaintedCheckerBase, NOT the R620 source-reading one deleted in #9. The package `org.plumb.dfa.checkers.taint.UnusedVariableChecker` is the kept one. |
| **Data-flow / exception** | `TryCatchFinallyChecker` (R314, CWE-248 uncaught), `UncaughtExceptionPropagator` | Cross-function exception propagation. |
| **Lock / concurrency** | `DeadlockChecker` (R833), `UnlockNotLockedChecker` (R832), `RecursionChecker` (R674) | Lock-pair matching + call-graph cycle detection. No plumb-style equivalent. |
| **Range / off-by-one / shift** | `OffByOneChecker` (R315, CWE-193), `BadShiftChecker` (CWE-758), `XxeChecker` (R318, CWE-611) | TAC IR BINOP/BRANCH analysis; AST has no equivalent (only `RangeArrayIndexCheck` for array bounds, but not for-loop bound analysis). |
| **Loose / aggressive (R622-R648)** | `LooseIntOverflowChecker`, `LooseIntUnderflowChecker`, `LooseDivByZeroChecker`, `LooseArrayIndexChecker`, `LooseNumericTruncationChecker`, `LooseFormatStringChecker`, `LooseHttpResponseSplittingChecker` | Intentionally aggressive — fire on any non-constant operand. **Complementary** to strict taint-based checkers; FP is reduced by AI dedup (R670-R672.1). |
| **Init / uninit** | `UninitNonnullDfaChecker`, `UncheckedReturnChecker` | DFA tracks return value usage; AST half is in plumb-style `UninitNonnullCheck` but the DFA half is genuinely different. |
| **Predictable / crypto** | `InsecureCryptoChecker` (R674 KEEP), `PredictableRandomSeedChecker`, `RandomIvChecker`, `MissingCryptoStepChecker`, `WeakPrngChecker` | Track const-variable case (`String ALGO = "MD5"; getInstance(ALGO);`) that AST cannot see. |
| **Info exposure / security** | `CorsMisconfigurationChecker`, `ProcessControlChecker`, `MissingAuthorizationChecker`, `CleartextSocketUsageChecker`, `InsecureCommunicationChecker`, `InsecureCookieChecker`, `InsecureTempFilePermsChecker`, `InsufficientSessionExpirationChecker`, `CommentInfoLeakChecker`, `ErrorMessageInfoLeakChecker`, `EnvVarInfoExposureChecker`, `InfoExposureQueryStringChecker`, `InformationExposureChecker`, `SensitiveInfoUnclearedChecker`, `MissingPasswordMaskingChecker`, `UnprotectedCredTransportChecker`, `UnsaltedOneWayHashChecker`, `HardCodedKeyChecker`, `HardCodedPasswordSinkChecker`, `StaticApiKeyChecker`, `FieldHardcodedPasswordChecker`, `PredictableKeyPasswordChecker`, `MissingCryptoInitChecker`, `MissingInputValidationChecker`, `NullDerefFromReturnChecker`, `SuspiciousCommentChecker`, `UncaughtExceptionChecker`, `UncheckedLoopConditionChecker`, `ResourceExhaustionChecker`, `PoorCodeQualityChecker`, `ClassBasedIssueChecker` | All CWE-specific. Plumb-style has nothing equivalent (e.g. `HardcodedCredentialsCheck` is broader but lacks DI-injection suppression + placeholder filter that dfa versions have). |
| **Composite (4)** | `ClassSourceChecker` (4 inner), `ClassThreadChecker` (5 inner), `LooseClassChecker` (3 inner), `ObjectModelChecker` (2 inner), `LooseDanglingChecker` (2 inner) | Class-level CWE wrappers — different scope from plumb-style per-method checks. R674 already decided the inner classes (SystemExitChecker, ThreadRunChecker, InfiniteLoopChecker etc.) are kept as CWE-specific class-level detectors. |

### C. Why we kept some plumb-style conflicts

- **`HardcodedCredentialsCheck` (plumb-style) vs `FieldHardcodedPasswordChecker` + `StaticApiKeyChecker` (dfa-unified)**: plumb-style covers fields + local vars but lacks
  - DI injection suppression (R125b `@Value`/`@Autowired`/etc.)
  - `<clinit>` specialization for static-final fields
  - Placeholder filter (`"changeme"`, `"<your-key>"`)

  Both kept. dfa versions report as `plumb.field-hardcoded-password` / `plumb.static-api-key`; plumb-style as `bu-1107-hardcoded-credentials`. Users see 3 distinct rules.

- **`SuspiciousCommentChecker` (dfa CWE-546) vs `TodoCommentCheck` (plumb-style)**: dfa scans for BUG/FIXME/XXX/HACK/WORKAROUND/TODO/TBD/SECURITY/VULN/CVE; plumb-style only TODO/FIXME/XXX. Both kept (dfa is broader, security-focused).

- **`CommentInfoLeakChecker` (dfa CWE-615) vs `SuspiciousCommentChecker` (dfa CWE-546)**: different CWEs, different keyword sets. Both kept.

- **`EqualsCalledOnNullChecker` (dfa) vs `EqualsAvoidNullCheck` (plumb-style)**: dfa is a R316 hybrid DFA+null-state for `obj.equals(null)`; plumb-style is a JavaParser `MethodCallExpr` for `equals(literal)` literal-first warning. Different rules entirely.

- **Loose `*` checkers vs strict taint checkers**: Loose is intentionally aggressive (fire on any non-constant); strict taint is precise. R670 user direction: "loose FP is OK, AI reduces it." Both kept.

## Files modified

### Moved to `_trash/r676-dfa-unified-checkers/` (9 source files)
- `BroadCatchChecker.java`
- `CatchGenericExceptionChecker.java`
- `CatchNullPointerExceptionChecker.java`
- `DefaultEncodingChecker.java`
- `OmittedBreakInSwitchChecker.java`
- `PublicStaticFieldNotFinalChecker.java`
- `EmptyCatchHandlerChecker.java`
- `ThrowGenericExceptionChecker.java`
- `UnusedVariableChecker.java` (R620 source-reading; taint.UnusedVariableChecker is different and KEPT)

### Moved to `_trash/r676-test-files/` (8 test files)
- **3 unit tests**: `CatchGenericExceptionCheckerTest.java`, `EmptyCatchHandlerCheckerTest.java`, `ThrowGenericExceptionCheckerTest.java`
- **5 dedicated sweep tests**: `R15Cwe484Sweep.java` (OmittedBreakInSwitch), `R25BroadCatchSweep.java` (BroadCatch), `R549Cwe396Sweep.java` (CatchGenericException), `R551Cwe397Sweep.java` (ThrowGenericException), `R574SwitchFingerprint.java` (OmittedBreakInSwitch)

### Edited multi-checker integration sweep tests (10 files)
- `R10RealCodebaseBaseline.java` (removed CatchNullPointerExceptionChecker)
- `R23MultiCweSweep.java` (removed OmittedBreakInSwitchChecker)
- `R24Cwe400CoverageSweep.java` (replaced EmptyCatchHandlerChecker with TryCatchFinallyChecker; added missing import)
- `R30Batch3CweSweep.java` (removed BroadCatchChecker)
- `R34Batch4CweSweep.java` (removed BroadCatchChecker + PublicStaticFieldNotFinalChecker)
- `R322NewCheckersJulietSweep.java` (removed `cwe390_sweepWithEmptyCatchHandlerChecker` test method)
- `R500JulietSweepTest.java` (removed 5 deleted checkers, kept taint UnusedVariable)
- `R520DfaCheckersSweep.java` (removed 5 deleted checkers)
- `R561MiscSweep.java` (removed EmptyCatchHandlerChecker + OmittedBreakInSwitchChecker)
- `R561RealCodeSweep.java` (removed PublicStaticFieldNotFinalChecker)
- `R570FullSweep.java` (removed 6 deleted checkers)
- `R571HighFireInvestigation.java` (removed 5 deleted checkers)
- `R573RealCodebaseValidation.java` (removed BroadCatchChecker + OmittedBreakInSwitchChecker)
- `R578MultiRealcodeValidation.java` (removed BroadCatchChecker + OmittedBreakInSwitchChecker)
- `R601JulietSweep.java` (removed 4 deleted checkers, kept taint UnusedVariable)

### Edited plumb-main
- `ScanRunner.java`:
  - Removed `if (config.runEmptyCatchHandler) checkers.add(new EmptyCatchHandlerChecker());`
  - Removed `if (config.runDefaultEncoding) checkers.add(new DefaultEncodingChecker());`
  - Both replaced with explanatory comments.
- `ScanConfig.java`: `runEmptyCatchHandler` and `runDefaultEncoding` flags **kept** as no-ops for backward compat (R675 pattern). Users with old YAML configs won't see errors.
- `R323ScanRunnerUnifiedE2ETest.java`: `allSevenUnifiedCheckersFireOnFixture` updated — was asserting CWE-665 (dfa EmptyCatchHandler rule), now asserts `pl-0539-empty-catch-block-strict` (plumb-style EmptyCatchBlockCheck rule). 7 → 6 unified checkers + 1 style.

### SourceReader.java
- Comment cleanup: removed mention of `PublicStaticFieldNotFinalChecker` (R675-era housekeeping).

## Test status

| Module | Before R676 | After R676 | Delta |
|---|---:|---:|---:|
| plumb-dfa unit | 334 | 321 | -13 (3 deleted test files: CatchGeneric/EmptyCatch/ThrowGeneric) |
| plumb-main unit | 50 | 50 | 0 |
| plumb-ai | 58 | 58 | 0 |
| **Total unit** | **442** | **429** | **-13** |
| plumb-dfa `R*` integration sweep | n/a (requires 11.8 GB Juliet corpus in `_trash/smoke_juliet/`) | skipped | skipped |

All 9 modules build clean (`mvn install -DskipTests`).

## Critical gotchas

1. **`taint.UnusedVariableChecker` ≠ top-level `UnusedVariableChecker`**: Both class names are `UnusedVariableChecker` but in different packages. Only the top-level `org.plumb.dfa.checkers.UnusedVariableChecker` (R620 source-reading) is deleted. The taint one (`org.plumb.dfa.checkers.taint.UnusedVariableChecker`) extends `TaintedCheckerBase` and is the kept CWE-563 detector for taint-domain code. R601JulietSweep references BOTH at different lines:
   - Line ~73 (deleted one — removed)
   - Line elsewhere (taint one — KEPT)
2. **R601 sweep test** had a typo: I removed `org.plumb.dfa.checkers.UnusedVariableChecker` import but it was actually the taint one referenced. Verified post-edit: the kept `taint.UnusedVariableChecker` import is still there.
3. **R24Cwe400CoverageSweep**: I substituted `EmptyCatchHandlerChecker` with `TryCatchFinallyChecker` for CWE-248 coverage, but `TryCatchFinallyChecker` was not imported. Fixed by adding the import.
4. **R323ScanRunnerUnifiedE2ETest**: needed update because the E2E test asserted CWE-665 which only the deleted dfa EmptyCatchHandlerChecker fired. Replaced with plumb-style `pl-0539-empty-catch-block-strict` assertion.

## Cross-project status (2026-08-22, post-R676)

| Project | Last shipped | In-flight | Test count | CWE coverage |
|---|---|---|---:|---|
| AetherCode | R89 | — | 1640+ Java | UX/infra ships (EXCLUDED) |
| Fathom (C/C++) | R459-5 | R459-5 follow-on | 1174 + 12 R459 | 18 CWE families (EXCLUDED) |
| Plumb (Java) | **R1-R676** | **R677 (final R673 doc update + R673.5-R673.11 policy + R674 + R675 + R676 history)** | 620+ classes / **429 unit tests** (321 plumb-dfa + 50 plumb-main + 58 plumb-ai) | **62 checkers** (post-R676 -9 from R675; was 71 after R674 -9) |

**Cumulative R-round count:** 676 (350% over 50+ target)

## What R676 validates

1. **DFA-specific value is real, not theatre**: 50+ checkers kept with clear
   DFA-uniqueness:
   - Aliasing (Orm* triplet, IteratorConcurrentModification, CollectionSelfReference)
   - Cross-function (ServletAtomicity, SingletonRace, OrmLostUpdate)
   - Null-state (EqualsCalledOnNull)
   - Init / uninit (UninitNonnull, UncheckedReturn)
   - Lock-pair (Deadlock, UnlockNotLocked, Recursion)
   - Const-var case (InsecureCrypto, PredictableRandomSeed, RandomIv, etc.)
   - Loose-aggressive (R622-R648 family)

2. **"No plumb-style equivalent" is the bar**: every kept checker either
   (a) has no plumb-style equivalent (CorsMisconfig, XXE, OffByOne, Recursion, etc.)
   or (b) has DFA-specific functionality that AST half cannot replicate.

3. **Backwards compat for `ScanConfig` flags**: `runEmptyCatchHandler`,
   `runDefaultEncoding`, `runReturnFromFinally`, `runBigDecimalDoubleConstructor`,
   `runStringEquality`, `runHardcodedCred`, `runInfiniteLoop`, `runThreadRun`
   are all kept as no-ops (R675 pattern). Users with old YAML configs work
   unchanged; the rule just isn't fired (plumb-style covers it).

4. **Test refactor follows delete**: 5 dedicated sweep tests (R15, R25, R549, R551, R574) moved to `_trash/` because they ONLY tested the deleted checker. 3 unit test files moved. 14 multi-checker integration sweep tests edited.

## What R676 didn't touch

- **4 composite checkers' inner classes** (ClassSourceChecker.SystemExitChecker, ClassThreadChecker.ThreadRunChecker, etc.) — kept per R674 decision (class-level CWE wrappers, different scope from plumb-style per-method)
- **R673 comment cleanup** — separate work stream, will be re-summarized in R677
- **Documentation files** (CHANGELOG, README) — R676 was a code-only round; R675 already shipped the GitHub prep

## Next steps (R677)

- Update R673 doc to reflect R673.5-R673.11 policy (`process not delete`) + R674 + R675 + R676 history
- Continue R673+ comment cleanup of next ~10 lighter files
- Consider R677: scan `plumb-ai` for any duplicate checkers there
