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
 * The end-to-end proof that a value written back into a by-reference argument is still analysed.
 *
 * <p><b>The shape.</b> C#'s {@code out} and {@code ref} arguments are written by the CALLEE and
 * read by the CALLER. The value that crosses the call boundary travels through the formal
 * parameter, not through the call's result, so the call's one destination cannot carry it. Before
 * this, the front end had no way to say "this argument is written back" and the engine had no way
 * to ask "what did the callee write into it", so the value was simply lost at the call boundary.
 *
 * <p><b>And a variable nothing writes is indistinguishable from a clean one.</b> That is the whole
 * defect: a callee that put attacker-controlled text into its {@code out} parameter lost that text,
 * and a sink that consumed the variable was never reported.
 *
 * <p><b>Why the negative cases matter as much.</b> A rule that tainted every by-reference argument
 * would pass the first case and be useless: it would report every C# program that passes an {@code
 * out} variable, whether the value in it was attacker-controlled or not. So the second case says a
 * clean value written into an {@code out} parameter stays clean, and the third says the taint does
 * not leak from the return value into the {@code out} variable -- the two travel on different paths
 * and only one of them is a write-back.
 */
class CSharpOutParamScanE2ETest {

  /** The command-injection sink the C# table names. */
  private static final String COMMAND_CWE = "CWE-78";

  /** The callee copies the caller's argument into its out parameter. */
  private static final String COPIES_A_TAINTED_ARGUMENT =
      """
      using System;

      namespace Demo;

      public class Producer
      {
          public static bool Copy(string input, out string value)
          {
              value = input;
              return true;
          }
      }

      public class Vulnerable
      {
          public void Run()
          {
              var input = Console.ReadLine();
              string result;
              if (Producer.Copy(input, out result))
              {
                  System.Diagnostics.Process.Start(result);
              }
          }
      }
      """;

  /** The callee writes a constant into its out parameter. */
  private static final String WRITES_A_CONSTANT =
      """
      using System;

      namespace Demo;

      public class Producer
      {
          public static bool Constant(out string value)
          {
              value = "constant";
              return true;
          }
      }

      public class Vulnerable
      {
          public void Run()
          {
              string result;
              if (Producer.Constant(out result))
              {
                  System.Diagnostics.Process.Start(result);
              }
          }
      }
      """;

  /** The taint lands in the RETURN value, and the out parameter gets a constant. */
  private static final String TAINT_LANDS_IN_THE_RETURN =
      """
      using System;

      namespace Demo;

      public class Producer
      {
          public static string Mixed(string input, out string value)
          {
              value = "constant";
              return input;
          }
      }

      public class Vulnerable
      {
          public void Run()
          {
              var input = Console.ReadLine();
              string result;
              var returned = Producer.Mixed(input, out result);
              System.Diagnostics.Process.Start(result);
          }
      }
      """;

  /** The callee reads the source itself and puts it into its out parameter. */
  private static final String SOURCE_INSIDE_THE_CALLEE =
      """
      using System;

      namespace Demo;

      public class Producer
      {
          public static bool Read(out string value)
          {
              value = Console.ReadLine();
              return true;
          }
      }

      public class Vulnerable
      {
          public void Run()
          {
              string result;
              if (Producer.Read(out result))
              {
                  System.Diagnostics.Process.Start(result);
              }
          }
      }
      """;

  /** The control: a plain by-value argument still reaches the sink. */
  private static final String BY_VALUE_ARGUMENT =
      """
      using System;

      namespace Demo;

      public class Producer
      {
          public static string Echo(string input)
          {
              return input;
          }
      }

      public class Vulnerable
      {
          public void Run()
          {
              var input = Console.ReadLine();
              var returned = Producer.Echo(input);
              System.Diagnostics.Process.Start(returned);
          }
      }
      """;

  @Test
  @DisplayName("a value written back into an out argument reaches the sink")
  void aValueWrittenBackIntoAnOutArgumentReachesTheSink() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", COPIES_A_TAINTED_ARGUMENT);

    assertThat(ndjson)
        .as(
            "the callee writes the caller's tainted argument into its out parameter, and the sink"
                + " consumes the caller's variable for it")
        .contains(COMMAND_CWE);
  }

  @Test
  @DisplayName("a constant written into an out argument does not reach the sink")
  void aConstantWrittenIntoAnOutArgumentDoesNotReachTheSink() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", WRITES_A_CONSTANT);

    assertThat(ndjson)
        .as("a constant is clean, so the caller's variable for the out argument stays clean")
        .doesNotContain(COMMAND_CWE);
  }

  @Test
  @DisplayName("the taint in the return value does not leak into the out argument")
  void theTaintInTheReturnValueDoesNotLeakIntoTheOutArgument() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", TAINT_LANDS_IN_THE_RETURN);

    assertThat(ndjson)
        .as(
            "the tainted value is in the return, and the out parameter holds a constant; a rule"
                + " that tainted the out argument because the call produced something tainted would"
                + " report this")
        .doesNotContain(COMMAND_CWE);
  }

  @Test
  @DisplayName("a source read inside the callee is written back into the out argument")
  void aSourceReadInsideTheCalleeIsWrittenBackIntoTheOutArgument() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", SOURCE_INSIDE_THE_CALLEE);

    assertThat(ndjson)
        .as(
            "the callee reads the console itself and puts the line into its out parameter; there is"
                + " no argument to blame the taint on, so the write-back has to fire at every call"
                + " site")
        .contains(COMMAND_CWE);
  }

  @Test
  @DisplayName("a plain by-value argument still reaches the sink")
  void aPlainByValueArgumentStillReachesTheSink() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", BY_VALUE_ARGUMENT);

    assertThat(ndjson)
        .as(
            "the control: the by-value path has to keep working, or the by-reference path proves"
                + " nothing")
        .contains(COMMAND_CWE);
  }

  /**
   * Runs a scan over one source file, with everything but the taint checker disabled.
   *
   * @param fileName the file's name -- its extension selects the front end
   * @param source the source text
   * @return the NDJSON the scan produced
   */
  private static String scan(String fileName, String source) throws Exception {
    Path dir = Files.createTempDirectory("plumb-csharp-out-e2e");
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
