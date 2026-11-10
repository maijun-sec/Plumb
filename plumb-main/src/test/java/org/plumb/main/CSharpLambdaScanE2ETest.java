/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The end-to-end proof that a C# lambda reaches the ANALYSIS.
 *
 * <p><b>Why the lambda is its own case.</b> A C# lambda used to be a hole in the front end: the
 * translator reported {@code UNHANDLED_NODE} and gave up on the whole node. The consequence was
 * invisible -- the file was scanned, the scan reported success, and the sink INSIDE the lambda was
 * never analysed. "No finding" and "never analysed" look identical from the outside.
 *
 * <p>So the proof has to be a scan, not a unit test of the translator. A translator test proves the
 * translator emits a {@code CALL}; it does not prove the emitted {@code CALL} reaches the taint
 * engine, and it does not prove the catalogue recognises it.
 *
 * <p>The chain this test walks, link by link:
 *
 * <ol>
 *   <li>the {@code .cs} file is DISCOVERED and routed to the C# front end;
 *   <li>the front end lowers the lambda to its OWN function, so the lambda body is analysed at all;
 *   <li>the lambda's parameter is a trust boundary (every parameter is seeded as a source), so the
 *       value reaching the sink is tainted;
 *   <li>the sink symbol is the one the C# table names -- {@code System.Diagnostics.Process.Start};
 *   <li>the taint engine propagates the source to the sink argument and the checker reports the
 *       CWE.
 * </ol>
 *
 * <p>The third test is the control that keeps the first honest: a lambda that does NOT reach a sink
 * must produce no finding. Without it, a front end that reported every lambda body as a finding
 * would pass.
 */
class CSharpLambdaScanE2ETest {

  /** The command-injection sink the C# table names. */
  private static final String COMMAND_CWE = "CWE-78";

  /** The line of the sink inside the lambda body of {@link #LAMBDA_REACHES_SINK}. */
  private static final int LAMBDA_SINK_LINE = 9;

  /** A lambda whose parameter reaches the sink. */
  private static final String LAMBDA_REACHES_SINK =
      """
      using System;

      namespace Demo;

      public class Vulnerable
      {
          public void Run(string userInput)
          {
              Action<string> handler = value => System.Diagnostics.Process.Start(value);
              handler(userInput);
          }
      }
      """;

  /** The same sink, called directly -- the control for the lambda case. */
  private static final String DIRECT_REACHES_SINK =
      """
      using System;

      namespace Demo;

      public class Vulnerable
      {
          public void Run(string userInput)
          {
              System.Diagnostics.Process.Start(userInput);
          }
      }
      """;

  /** A lambda whose parameter reaches NOTHING. */
  private static final String LAMBDA_REACHES_NOTHING =
      """
      using System;

      namespace Demo;

      public class Benign
      {
          public void Run(string userInput)
          {
              Action<string> handler = value => { var length = value.Length; };
              handler(userInput);
          }
      }
      """;

  /**
   * Runs a scan over one source file, with everything but the taint checker disabled.
   *
   * @param fileName the file's name -- its extension selects the front end
   * @param source the source text
   * @return the NDJSON the scan produced
   */
  private static String scan(String fileName, String source) throws Exception {
    Path dir = Files.createTempDirectory("plumb-csharp-lambda-e2e");
    Path input = dir.resolve(fileName);
    Files.writeString(input, source);
    Path outFile = dir.resolve("out.ndjson");
    try {
      ScanConfig cfg = new ScanConfig();
      cfg.inputs.add(input);
      cfg.outputPath = outFile;
      cfg.outputFormat = ScanConfig.OutputFormat.NDJSON;
      // The taint checker is the one under test. Everything else is off, so a
      // finding can only have come from the taint chain.
      cfg.runTaint = true;
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
      cfg.runErrorMessageInfoLeak = false;
      cfg.runCommentInfoLeak = false;
      cfg.runTryCatchFinally = false;
      cfg.runOffByOne = false;
      cfg.runEqualsCalledOnNull = false;
      cfg.runCompareWithSelf = false;
      cfg.runXxe = false;
      cfg.runReturnFromFinally = false;
      cfg.runEmptyCatchHandler = false;

      int rc = new ScanRunner(cfg).run();
      assertThat(rc).as("the scan must succeed").isEqualTo(0);
      return Files.exists(outFile) ? Files.readString(outFile) : "";
    } finally {
      deleteRecursively(dir);
    }
  }

  @Test
  @DisplayName("a C# lambda that reaches a sink is reported")
  void lambdaReachingTheSinkIsReported() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", LAMBDA_REACHES_SINK);

    assertThat(ndjson)
        .as("the lambda body must reach the taint engine and be reported as " + COMMAND_CWE)
        .contains(COMMAND_CWE);

    // The finding must be reported at the sink INSIDE the lambda body, not
    // at the call site. Without this, a finding produced anywhere in the file
    // would satisfy the assertion above -- and the point of the test is that
    // the lambda body is analysed at all.
    assertThat(findingLines(ndjson))
        .as("the finding must be located at the sink inside the lambda body")
        .containsExactly(LAMBDA_SINK_LINE);
  }

  /**
   * The locations of the command-injection findings in an NDJSON scan output.
   *
   * <p>NDJSON is one JSON object per line, so a record that mentions the CWE is a finding of that
   * class. Its OWN {@code "line"} is the first one in the record -- the emitter writes it before
   * the trace, and the trace's nodes carry their own line numbers, so taking the first is what
   * isolates the finding's location from its steps.
   *
   * @param ndjson the scan output
   * @return the location of every finding that carries the command-injection CWE
   */
  private static List<Integer> findingLines(String ndjson) {
    java.util.regex.Pattern linePattern = java.util.regex.Pattern.compile("\"line\":(\\d+)");
    List<Integer> lines = new java.util.ArrayList<>();
    for (String record : ndjson.split("\\R")) {
      if (!record.contains(COMMAND_CWE)) {
        continue;
      }
      java.util.regex.Matcher matcher = linePattern.matcher(record);
      if (matcher.find()) {
        lines.add(Integer.parseInt(matcher.group(1)));
      }
    }
    return lines;
  }

  @Test
  @DisplayName("the same sink called directly is reported")
  void directCallReachingTheSinkIsReported() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", DIRECT_REACHES_SINK);

    assertThat(ndjson)
        .as("the direct call must reach the taint engine and be reported as " + COMMAND_CWE)
        .contains(COMMAND_CWE);
  }

  @Test
  @DisplayName("a C# lambda that reaches no sink is not reported")
  void lambdaReachingNothingIsNotReported() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Benign.cs", LAMBDA_REACHES_NOTHING);

    assertThat(ndjson)
        .as("lowering the lambda must not invent findings for a value that reaches no sink")
        .doesNotContain(COMMAND_CWE);
  }

  /** Deletes a directory tree, best effort. */
  private static void deleteRecursively(Path root) {
    try (var walk = Files.walk(root)) {
      List<Path> paths = walk.sorted(java.util.Comparator.reverseOrder()).toList();
      for (Path path : paths) {
        Files.deleteIfExists(path);
      }
    } catch (Exception ignore) {
      // Best effort.
    }
  }
}
