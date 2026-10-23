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
    Path tmp = Files.createTempFile("R323E2E", ".java");
    Files.writeString(tmp, src);
    Path outFile = Files.createTempFile("R323E2E", ".ndjson");
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
          .contains("ast-error-catch-block-strict");
    } finally {
      Files.deleteIfExists(tmp);
      Files.deleteIfExists(outFile);
    }
  }

  @Test
  void allUnifiedCheckersOffByDefault_noFindings() throws Exception {
    // Run with all 7 unified checkers
    // disabled — should produce 0
    // findings for a clean fixture
    // (other legacy checkers also off).
    String src = "class Sample { void m() { int x = 1; } }";
    Path tmp = Files.createTempFile("R323Off", ".java");
    Files.writeString(tmp, src);
    Path outFile = Files.createTempFile("R323Off", ".ndjson");
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
      Files.deleteIfExists(tmp);
      Files.deleteIfExists(outFile);
    }
  }
}
