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
 * The end-to-end proof that a C# run has a SOURCE.
 *
 * <p><b>Why this is its own test.</b> A sink catalogue without a source family produces a scan that
 * looks perfect and is empty: the value reaching the sink is untainted because nothing ever tainted
 * it, so the checker has nothing to report and the scan reports success. "The code is clean" and
 * "the engine never saw untrusted data" are the same output.
 *
 * <p>The C# scope had no source family at all, so this is the failure this pins shut. Each case
 * reads from a different untrusted place -- the console, the environment, a file -- and the value
 * reaches a sink the C# table names.
 *
 * <p><b>Why the negative case matters.</b> Without it, a front end that marked EVERY value as
 * tainted would pass all three positive cases. The negative case reads nothing: a literal reaches
 * the sink, and a literal is clean, so there must be no finding.
 */
class CSharpTaintSourceScanE2ETest {

  /** The command-injection sink the C# table names. */
  private static final String COMMAND_CWE = "CWE-78";

  /** A value read from the console. */
  private static final String FROM_CONSOLE =
      """
      using System;

      namespace Demo;

      public class Vulnerable
      {
          public void Run()
          {
              var input = Console.ReadLine();
              System.Diagnostics.Process.Start(input);
          }
      }
      """;

  /** A value read from the environment. */
  private static final String FROM_ENVIRONMENT =
      """
      using System;

      namespace Demo;

      public class Vulnerable
      {
          public void Run()
          {
              var input = Environment.GetEnvironmentVariable("PATH");
              System.Diagnostics.Process.Start(input);
          }
      }
      """;

  /** A value read from a file. */
  private static final String FROM_FILE =
      """
      using System;

      namespace Demo;

      public class Vulnerable
      {
          public void Run()
          {
              var input = System.IO.File.ReadAllText("payload.txt");
              System.Diagnostics.Process.Start(input);
          }
      }
      """;

  /** A value that comes from nowhere: a literal is clean. */
  private static final String FROM_A_LITERAL =
      """
      using System;

      namespace Demo;

      public class Benign
      {
          public void Run()
          {
              var input = "not-a-command";
              System.Diagnostics.Process.Start(input);
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
    Path dir = Files.createTempDirectory("plumb-csharp-source-e2e");
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
  @DisplayName("a value read from the console reaches the sink")
  void consoleReadReachesTheSink() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", FROM_CONSOLE);

    assertThat(ndjson)
        .as("the console read must be a source and the value must reach the sink")
        .contains(COMMAND_CWE);
  }

  @Test
  @DisplayName("a value read from the environment reaches the sink")
  void environmentReadReachesTheSink() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", FROM_ENVIRONMENT);

    assertThat(ndjson)
        .as("the environment read must be a source and the value must reach the sink")
        .contains(COMMAND_CWE);
  }

  @Test
  @DisplayName("a value read from a file reaches the sink")
  void fileReadReachesTheSink() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", FROM_FILE);

    assertThat(ndjson)
        .as("the file read must be a source and the value must reach the sink")
        .contains(COMMAND_CWE);
  }

  @Test
  @DisplayName("a literal that reaches the sink is not reported")
  void literalReachingTheSinkIsNotReported() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Benign.cs", FROM_A_LITERAL);

    assertThat(ndjson)
        .as("a literal is clean, so the source families must not taint it")
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
