package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * end-to-end smoke test that runs {@link ScanRunner} on a tiny .java fixture and asserts the
 * plumb-dfa engine fires the 6 remaining DFA-only checkers (TryCatchFinally, OffByOne,
 * EqualsCalledOnNull, CompareWithSelf, Xxe, EmptyCatchHandler) alongside the plumb-style AST
 * checkers. ReturnFromFinally is now covered by plumb-style's {@code ReturnFromFinallyBlockCheck}.
 *
 * <p>The fixture contains anti-patterns for each of the 6 unified checkers plus the one style
 * check. We don't insist on every checker firing on every test ? we assert a minimum coverage (at
 * least the dedicated anti-pattern fires) so the test stays robust against future DFA-engine
 * tuning.
 */
class R323ScanRunnerUnifiedE2ETest {

  @Test
  void allSevenUnifiedCheckersFireOnFixture() throws Exception {
    // A single .java file with all 7
    // anti-patterns inline.
    String src =
        """
        import java.util.List;
        public class Sample {
            // 1. EqualsCalledOnNull: s.equals(null)  — CWE-754
            boolean isEmpty(String s) {
                return s.equals(null);
            }
            // 2. OffByOne: <= arr.length  — CWE-193
            int sumBad(int[] arr) {
                int s = 0;
                for (int i = 0; i <= arr.length; i++) {
                    s += arr[i];
                }
                return s;
            }
            // 3. CompareWithSelf: x == x  — CWE-571
            boolean same(int x) {
                return x == x;
            }
            // 4. TryCatchFinally: catch (Exception e) {} generic catch
            void swallow() {
                try { int x = 1; }
                catch (Exception e) { /* swallow */ }
            }
            // 5. ReturnFromFinally: return in finally
            int withFinally() {
                try { return 1; }
                finally { return 2; }
            }
            // 6. EmptyCatchHandler: catch (Error e) {} empty
            void emptyError() {
                try { int x = 1; }
                catch (Error e) { /* swallow */ }
            }
        }
        """;
    // R1124: the fixture goes into its OWN directory.
    //
    // ScanRunner.maybeExpandForCrossTu() lists the parent directory of a
    // single-file input and pulls in sibling .java files (the cross-TU
    // auto-expand, capped at CROSS_TU_AUTO_EXPAND_LIMIT siblings). A fixture
    // written straight into the shared system temp directory therefore
    // drags in whatever else happens to be sitting there -- a stray
    // QE-R744-backup.java left by an earlier session was enough to make
    // this test fail, with findings the fixture never contained.
    //
    // An isolated per-test directory has exactly one .java file in it, so
    // the expansion is a no-op and the assertions measure this fixture only.
    Path dir = Files.createTempDirectory("R323E2E");
    Path tmp = dir.resolve("Sample.java");
    Files.writeString(tmp, src);
    Path outFile = dir.resolve("out.ndjson");
    try {
      ScanConfig cfg = new ScanConfig();
      cfg.inputs.add(tmp);
      cfg.outputPath = outFile;
      cfg.outputFormat = ScanConfig.OutputFormat.NDJSON;
      // Run the 6 unified DFA checkers + a
      // handful of basic taint/dfa checks
      // to keep the fixture focused. The
      // ReturnFromFinally anti-pattern is
      // covered by plumb-style, so
      // style is enabled.
      cfg.runTaint = false;
      cfg.runStyle = true;
      cfg.runDivByZero = false;
      cfg.runResourceLeak = false;
      cfg.runNullDeref = false;
      cfg.runHardcodedCred = false;
      cfg.runIntOverflow = false;
      cfg.runArrayBounds = false;
      cfg.runWeakCrypto = false;
      cfg.runInsecureRandom = false;
      cfg.runPredictableSeed = false;
      cfg.runPredictableKey = false;
      cfg.runStaticApiKey = false;
      cfg.runInsecureComms = false;
      cfg.runCorsMisconfig = false;
      cfg.runInsecureCookie = false;
      cfg.runBadShift = false;
      cfg.runInfiniteLoop = false;
      cfg.runConstantCondition = false;
      cfg.runUncheckedReturn = false;
      cfg.runIncorrectReturn = false;
      cfg.runThreadRun = false;
      cfg.runStringEquality = false;
      cfg.runCatchGeneric = false;
      cfg.runObsoleteFunction = false;
      cfg.runSensitiveDataLeak = false;
      cfg.runFieldHardcodedPassword = false;
      cfg.runMissingAuthorization = false;
      cfg.runMissingInputValidation = false;
      cfg.runInformationExposure = false;
      // Keep the 7 unified checkers on
      // (default is true, so don't need
      // to set them explicitly).
      int rc = new ScanRunner(cfg).run();
      assertThat(rc).isEqualTo(0);
      String ndjson = Files.readString(outFile);
      // All 7 unified checkers should fire.
      assertThat(ndjson).as("EqualsCalledOnNull should fire on s.equals(null)").contains("CWE-754");
      assertThat(ndjson).as("OffByOne should fire on <= arr.length").contains("CWE-193");
      assertThat(ndjson).as("CompareWithSelf should fire on x == x").contains("CWE-571");
      assertThat(ndjson)
          .as("TryCatchFinally should fire on catch (Exception e) {}")
          .contains("CWE-396");
      assertThat(ndjson)
          .as("ReturnFromFinally should fire on return in finally")
          .contains("CWE-584");
      assertThat(ndjson)
          .as(
              "plumb-style EmptyCatchBlockCheck should fire on catch (Error e) {} (R676 — dfa"
                  + " EmptyCatchHandler removed, plumb-style covers it)")
          // R1124: the emitted rule_id is the CANONICAL id
          // ("ast-error-empty-catch-block"), not the legacy alias
          // ("ast-error-catch-block-strict"). The alias exists only for
          // backwards compatibility with old YAML whitelists; the NDJSON
          // contract carries the canonical id. This test asserted the alias
          // and had therefore never passed since the initial commit.
          .contains("ast-error-empty-catch-block");
    } finally {
      Files.deleteIfExists(outFile);
      Files.deleteIfExists(tmp);
      Files.deleteIfExists(dir);
    }
  }

  @Test
  void allUnifiedCheckersOffByDefault_noFindings() throws Exception {
    // Run with all 7 unified checkers
    // disabled — should produce 0
    // findings for a clean fixture
    // (other legacy checkers also off).
    String src = "class Sample { void m() { int x = 1; } }";
    // R1124: isolated directory, same reason as the test above -- the
    // cross-TU auto-expand pulls in sibling .java files from the input's
    // parent directory, and the shared system temp directory is not a
    // controlled environment.
    Path dir = Files.createTempDirectory("R323Off");
    Path tmp = dir.resolve("Sample.java");
    Files.writeString(tmp, src);
    Path outFile = dir.resolve("out.ndjson");
    try {
      ScanConfig cfg = new ScanConfig();
      cfg.inputs.add(tmp);
      cfg.outputPath = outFile;
      cfg.outputFormat = ScanConfig.OutputFormat.NDJSON;
      // Disable everything
      cfg.runTaint = false;
      cfg.runStyle = false;
      cfg.runDivByZero = false;
      cfg.runResourceLeak = false;
      cfg.runNullDeref = false;
      cfg.runHardcodedCred = false;
      cfg.runIntOverflow = false;
      cfg.runArrayBounds = false;
      cfg.runWeakCrypto = false;
      cfg.runInsecureRandom = false;
      cfg.runPredictableSeed = false;
      cfg.runPredictableKey = false;
      cfg.runStaticApiKey = false;
      cfg.runInsecureComms = false;
      cfg.runCorsMisconfig = false;
      cfg.runInsecureCookie = false;
      cfg.runBadShift = false;
      cfg.runInfiniteLoop = false;
      cfg.runConstantCondition = false;
      cfg.runUncheckedReturn = false;
      cfg.runIncorrectReturn = false;
      cfg.runThreadRun = false;
      cfg.runStringEquality = false;
      cfg.runCatchGeneric = false;
      cfg.runObsoleteFunction = false;
      cfg.runSensitiveDataLeak = false;
      cfg.runFieldHardcodedPassword = false;
      cfg.runMissingAuthorization = false;
      cfg.runMissingInputValidation = false;
      cfg.runInformationExposure = false;
      cfg.runTryCatchFinally = false;
      cfg.runOffByOne = false;
      cfg.runEqualsCalledOnNull = false;
      cfg.runCompareWithSelf = false;
      cfg.runXxe = false;
      cfg.runReturnFromFinally = false;
      cfg.runEmptyCatchHandler = false;
      new ScanRunner(cfg).run();
      String ndjson = Files.readString(outFile);
      assertThat(ndjson.trim()).isEmpty();
    } finally {
      Files.deleteIfExists(outFile);
      Files.deleteIfExists(tmp);
      Files.deleteIfExists(dir);
    }
  }
}
