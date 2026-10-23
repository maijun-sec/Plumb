package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * end-to-end smoke test that runs {@link ScanRunner} on a tiny .java fixture and asserts the
 * cross-function UncaughtExceptionPropagator fires when the caller has no try-catch but calls a
 * callee that throws.
 */
class R324ScanRunnerUncaughtE2ETest {

  @Test
  void uncaughtThrowAcrossFunctionsFires() throws Exception {
    // foo() throws RuntimeException.
    // bar() calls foo() with no try-catch.
    // Expected: CWE-248 from
    // UncaughtExceptionPropagator on the
    // call site in bar().
    String src =
        """
        public class Sample {
            public void foo() {
                throw new RuntimeException("boom");
            }
            public void bar() {
                foo();
            }
        }
        """;
    Path tmp = Files.createTempFile("R324E2E", ".java");
    Files.writeString(tmp, src);
    Path outFile = Files.createTempFile("R324E2E", ".ndjson");
    try {
      ScanConfig cfg = new ScanConfig();
      cfg.inputs.add(tmp);
      cfg.outputPath = outFile;
      cfg.outputFormat = ScanConfig.OutputFormat.NDJSON;
      // Disable everything to keep the
      // fixture focused.
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
      cfg.runIteratorConcurrentModification = false;
      cfg.runCollectionSelfReference = false;
      // Keep uncaughtExceptionPropagator on (default true).
      new ScanRunner(cfg).run();
      String ndjson = Files.readString(outFile);
      assertThat(ndjson)
          .as("UncaughtExceptionPropagator should fire CWE-248 on cross-function call")
          .contains("CWE-248");
      assertThat(ndjson)
          .as("The check is from R324 UncaughtExceptionPropagator")
          .contains("UncaughtExceptionPropagator");
      // The filePath should be
      // filled in (the temp file's
      // absolute path), not "unknown".
      // We can't predict the exact
      // string, but it should NOT be
      // "unknown" since this is a
      // single-file scan with a real
      // path.
      assertThat(ndjson)
          .as("the filePath should be filled in from the input file (not 'unknown')")
          .doesNotContain("\"file\":\"unknown\"");
    } finally {
      Files.deleteIfExists(tmp);
      Files.deleteIfExists(outFile);
    }
  }

  @Test
  void uncaughtPropagatorOffByDefault_noCrossFunctionFinding() throws Exception {
    // Same fixture as above but with
    // runUncaughtExceptionPropagator = false.
    // The other try-catch-finally
    // check would still fire (intra-fn
    // throw in foo has no catch in foo
    // either), so we keep it off too
    // and verify the cross-fn finding is
    // gone.
    String src =
        """
        public class Sample {
            public void foo() {
                throw new RuntimeException("boom");
            }
            public void bar() {
                foo();
            }
        }
        """;
    Path tmp = Files.createTempFile("R324Off", ".java");
    Files.writeString(tmp, src);
    Path outFile = Files.createTempFile("R324Off", ".ndjson");
    try {
      ScanConfig cfg = new ScanConfig();
      cfg.inputs.add(tmp);
      cfg.outputPath = outFile;
      cfg.outputFormat = ScanConfig.OutputFormat.NDJSON;
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
      cfg.runIteratorConcurrentModification = false;
      cfg.runCollectionSelfReference = false;
      cfg.runUncaughtExceptionPropagator = false;
      new ScanRunner(cfg).run();
      String ndjson = Files.readString(outFile);
      assertThat(ndjson.trim()).as("No checkers on -> empty output").isEmpty();
    } finally {
      Files.deleteIfExists(tmp);
      Files.deleteIfExists(outFile);
    }
  }
}
