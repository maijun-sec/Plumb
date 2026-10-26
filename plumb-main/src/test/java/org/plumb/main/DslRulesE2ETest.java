package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test for the Kotlin AST-rule engine as the runner drives it.
 *
 * <p>The unit tests in {@code plumb-dsl-kotlin} cover the matcher language. These cover the SEAM: a
 * rule file handed to the runner through {@code ScanConfig.dslRuleFiles} must (a) load, (b) be
 * registered in the live rule registry, (c) survive the whitelist / blacklist filters, and (d)
 * reach the output.
 *
 * <p>Every one of those steps has been broken at least once during development, and each failure
 * looked the same from the outside: the scan ran, produced the usual findings, and the DSL finding
 * was simply absent. So the assertions below check the DSL rule id specifically -- a test that only
 * asserted "some finding was produced" would pass even with the DSL engine completely dead.
 */
class DslRulesE2ETest {

  private static final String SAMPLE =
      """
      public class Sample {
          public void run() {
              try {
                  int x = 1 / 0;
              } catch (Exception e) {
              }
          }
      }
      """;

  private static final String RULE_FILE =
      """
      rules:
        - id: plumb.dsl.it-empty-catch
          name: it empty catch block
          description: A catch block with no statements
          severity: MEDIUM
          type: BAD_PRACTICE
          cwe: [CWE-390]
          nodeType: CatchClause
          matcher: |
            CatchClause catchClause: catchClause match { empty }
      """;

  /** A config with every built-in check switched off, so only the DSL rule can fire. */
  private static ScanConfig minimalConfig(Path input, Path output) {
    ScanConfig cfg = new ScanConfig();
    cfg.inputs.add(input);
    cfg.outputPath = output;
    cfg.outputFormat = ScanConfig.OutputFormat.NDJSON;
    cfg.runStyle = true;
    cfg.runTaint = false;
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
    cfg.runUncheckedLoopCondition = false;
    cfg.runResourceExhaustion = false;
    cfg.runUncontrolledMemAlloc = false;
    cfg.runNumericTruncation = false;
    cfg.runBigDecimalDoubleConstructor = false;
    return cfg;
  }

  @Test
  void aDslRuleFileProducesItsFinding(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("Sample.java");
    Files.writeString(src, SAMPLE);
    Path rules = dir.resolve("rules.yaml");
    Files.writeString(rules, RULE_FILE);
    Path out = dir.resolve("out.ndjson");

    ScanConfig cfg = minimalConfig(src, out);
    cfg.dslRuleFiles.add(rules);
    new ScanRunner(cfg).run();

    String ndjson = Files.readString(out);
    assertThat(ndjson)
        .as("the DSL rule should have produced a finding")
        .contains("plumb.dsl.it-empty-catch");
    assertThat(ndjson).as("the finding should carry the rule's CWE").contains("CWE-390");
    assertThat(ndjson)
        .as("the finding should carry the rule's severity")
        .contains("\"severity\":\"medium\"");
  }

  @Test
  void aDslRuleFileWithNoRulesProducesNoDslFinding(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("Sample.java");
    Files.writeString(src, SAMPLE);
    Path rules = dir.resolve("rules.yaml");
    Files.writeString(rules, "rules: []\n");
    Path out = dir.resolve("out.ndjson");

    ScanConfig cfg = minimalConfig(src, out);
    cfg.dslRuleFiles.add(rules);
    new ScanRunner(cfg).run();

    String ndjson = Files.readString(out);
    assertThat(ndjson).as("no DSL rule was configured").doesNotContain("plumb.dsl.");
  }

  @Test
  void aDisabledDslRuleDoesNotProduceItsFinding(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("Sample.java");
    Files.writeString(src, SAMPLE);
    Path rules = dir.resolve("rules.yaml");
    Files.writeString(
        rules, RULE_FILE.replace("severity: MEDIUM", "severity: MEDIUM\n          enabled: false"));
    Path out = dir.resolve("out.ndjson");

    ScanConfig cfg = minimalConfig(src, out);
    cfg.dslRuleFiles.add(rules);
    new ScanRunner(cfg).run();

    String ndjson = Files.readString(out);
    assertThat(ndjson).as("the rule is disabled").doesNotContain("plumb.dsl.it-empty-catch");
  }

  @Test
  void aBrokenRuleIsSkippedInLenientMode(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("Sample.java");
    Files.writeString(src, SAMPLE);
    Path rules = dir.resolve("rules.yaml");
    // A matcher that cannot compile.
    Files.writeString(
        rules,
        """
        rules:
          - id: plumb.dsl.it-broken
            name: it broken rule
            description: never fires
            nodeType: CatchClause
            matcher: |
              CatchClause catchClause: this is not valid kotlin (((
        """);
    Path out = dir.resolve("out.ndjson");

    ScanConfig cfg = minimalConfig(src, out);
    cfg.dslRuleFiles.add(rules);
    cfg.dslStrict = false;
    // One broken rule must not take the scan down.
    new ScanRunner(cfg).run();

    String ndjson = Files.readString(out);
    assertThat(ndjson).as("the broken rule must not fire").doesNotContain("plumb.dsl.it-broken");
  }

  @Test
  void aBrokenRuleAbortsTheScanInStrictMode(@TempDir Path dir) throws Exception {
    Path src = dir.resolve("Sample.java");
    Files.writeString(src, SAMPLE);
    Path rules = dir.resolve("rules.yaml");
    Files.writeString(
        rules,
        """
        rules:
          - id: plumb.dsl.it-broken
            name: it broken rule
            description: never fires
            nodeType: CatchClause
            matcher: |
              CatchClause catchClause: this is not valid kotlin (((
        """);
    Path out = dir.resolve("out.ndjson");

    ScanConfig cfg = minimalConfig(src, out);
    cfg.dslRuleFiles.add(rules);
    cfg.dslStrict = true;

    // In strict mode a rule that does not compile aborts the scan.
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ScanRunner(cfg).run())
        .isInstanceOf(RuntimeException.class);
  }
}
