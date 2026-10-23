package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * end-to-end smoke test that runs {@link ScanRunner} on a tiny .java fixture and asserts the 2
 * DFA-only checkers (IteratorConcurrentModification, CollectionSelfReference) fire when their flags
 * are on, and stay silent when off.
 *
 * <p>Companion to {@link R323ScanRunnerUnifiedE2ETest}, which covers the 7 unified checkers that
 * were originally wired. This test closes the gap that was left.
 */
class R329ScanRunnerR312E2ETest {

  /**
   * A single .java file with both anti-patterns inline:
   *
   * <ol>
   *   <li>{@code for (String s : list) list.add(x);} ? classic CME source. CWE-820.
   *   <li>{@code list.addAll(list);} ? receiver and argument are the same collection. CWE-754.
   * </ol>
   */
  private static final String FIXTURE =
      """
      import java.util.ArrayList;
      import java.util.List;
      public class Sample {
          // 1. IteratorConcurrentModification: for-each + add (CWE-820)
          void iterateAndMutate(List<String> list) {
              for (String s : list) {
                  list.add("x");
              }
          }
          // 2. CollectionSelfReference: list.addAll(list) (CWE-754)
          void selfReference(List<String> list) {
              list.addAll(list);
          }
      }
      """;

  @Test
  void bothR312CheckersFireOnFixture() throws Exception {
    Path tmp = Files.createTempFile("R329E2E", ".java");
    Files.writeString(tmp, FIXTURE);
    Path outFile = Files.createTempFile("R329E2E", ".ndjson");
    try {
      ScanConfig cfg = new ScanConfig();
      cfg.inputs.add(tmp);
      cfg.outputPath = outFile;
      cfg.outputFormat = ScanConfig.OutputFormat.NDJSON;
      // Disable everything except the 2 checkers
      // so the assertions are unambiguous.
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
      // Keep the 2 checkers on (default true).
      int rc = new ScanRunner(cfg).run();
      assertThat(rc).isEqualTo(0);
      String ndjson = Files.readString(outFile);
      assertThat(ndjson)
          .as("IteratorConcurrentModification should fire on for-each + add")
          .contains("CWE-820");
      assertThat(ndjson)
          .as("CollectionSelfReference should fire on list.addAll(list)")
          .contains("CWE-754");
    } finally {
      Files.deleteIfExists(tmp);
      Files.deleteIfExists(outFile);
    }
  }

  @Test
  void bothR312CheckersOffByDefault_noFindings() throws Exception {
    // Same fixture, but with the 2
    // checkers disabled. We also disable the
    // other 7 unified checkers and the legacy
    // checkers so the only source of findings
    // is the 2 checkers. Result: empty
    // output file.
    Path tmp = Files.createTempFile("R329Off", ".java");
    Files.writeString(tmp, FIXTURE);
    Path outFile = Files.createTempFile("R329Off", ".ndjson");
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
      new ScanRunner(cfg).run();
      String ndjson = Files.readString(outFile);
      assertThat(ndjson.trim()).isEmpty();
    } finally {
      Files.deleteIfExists(tmp);
      Files.deleteIfExists(outFile);
    }
  }
}
