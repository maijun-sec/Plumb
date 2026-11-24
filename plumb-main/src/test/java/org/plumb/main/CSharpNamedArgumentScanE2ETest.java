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
 * The end-to-end proof that a named argument is analysed as the parameter it names.
 *
 * <p><b>The shape.</b> A C# call may name its arguments, and then the source order is not the
 * parameter order. The engine binds formal {@code i} to {@code args[i+1]} by position, so a named
 * argument landed on the wrong formal: the callee's tainted value was written into the caller's
 * wrong variable, and the sink that reads the right one saw nothing.
 *
 * <p><b>Why the by-reference case is the one that costs a finding.</b> A value the callee returns
 * can also travel the other way -- through a conservative rule that says "the result may carry the
 * argument" -- so a misbound by-value argument is usually covered. A value the callee WRITES BACK
 * into an {@code out} parameter has no such second route: it exists only if the write-back is bound
 * to the right formal. That is why the first case below is the one that was missed.
 *
 * <p><b>Why the attribution is asserted, not only the finding.</b> A rule that reported every call
 * with a tainted argument would pass the first case and be useless. The fourth case says the
 * finding is attributed to the callee's RETURN rather than to the conservative rule, which is what
 * tells the two apart.
 *
 * <p><b>Why the negative case matters as much.</b> The named call with clean values has to stay
 * silent, or the fix buys its finding with a report on every call.
 */
class CSharpNamedArgumentScanE2ETest {

  /** The command-injection sink the C# table names. */
  private static final String COMMAND_CWE = "CWE-78";

  /** The callee writes the caller's argument into its out parameter. */
  private static final String NAMED_IN_REVERSE_ORDER =
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
              if (Producer.Copy(value: out result, input: input))
              {
                  System.Diagnostics.Process.Start(result);
              }
          }
      }
      """;

  /** The same call, with the arguments named in declaration order. */
  private static final String NAMED_IN_DECLARATION_ORDER =
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
              if (Producer.Copy(input: input, value: out result))
              {
                  System.Diagnostics.Process.Start(result);
              }
          }
      }
      """;

  /** The same call, with nothing tainted anywhere. */
  private static final String NAMED_AND_CLEAN =
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
              var input = "not-a-command";
              string result;
              if (Producer.Copy(value: out result, input: input))
              {
                  System.Diagnostics.Process.Start(result);
              }
          }
      }
      """;

  /** The callee returns its second parameter, and the call names it first. */
  private static final String NAMED_RETURN_IS_TAINTED =
      """
      using System;

      namespace Demo;

      public class Producer
      {
          public static string Echo(string first, string second)
          {
              return second;
          }
      }

      public class Vulnerable
      {
          public void Run()
          {
              var tainted = Console.ReadLine();
              var clean = "not-a-command";
              var returned = Producer.Echo(second: tainted, first: clean);
              System.Diagnostics.Process.Start(returned);
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
  @DisplayName("a named argument written in the wrong order still reaches the sink")
  void aNamedArgumentWrittenInTheWrongOrderStillReachesTheSink() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", NAMED_IN_REVERSE_ORDER);

    assertThat(findings(ndjson, COMMAND_CWE))
        .as(
            "the callee writes the caller's tainted argument into its out parameter, and the sink"
                + " consumes the caller's variable for it; the write-back has to land on the"
                + " parameter the call NAMED")
        .isNotEmpty();
    assertThat(ndjson)
        .as("the finding has to name the write-back, or it was found by accident")
        .contains("writes back into arg#");
  }

  @Test
  @DisplayName("a named argument written in declaration order still reaches the sink")
  void aNamedArgumentWrittenInDeclarationOrderStillReachesTheSink() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", NAMED_IN_DECLARATION_ORDER);

    assertThat(findings(ndjson, COMMAND_CWE))
        .as("the control: naming the arguments in declaration order has to keep working")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a named argument with a clean value does not reach the sink")
  void aNamedArgumentWithACleanValueDoesNotReachTheSink() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", NAMED_AND_CLEAN);

    assertThat(findings(ndjson, COMMAND_CWE))
        .as("a clean value stays clean, or the fix buys its finding with a report on every call")
        .isEmpty();
  }

  @Test
  @DisplayName("a named argument is bound to the argument the callee returns")
  void aNamedArgumentIsBoundToTheArgumentTheCalleeReturns() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", NAMED_RETURN_IS_TAINTED);

    assertThat(findings(ndjson, COMMAND_CWE))
        .as("the callee returns the parameter the call named, and that parameter is tainted")
        .isNotEmpty();
    assertThat(ndjson)
        .as(
            "the taint has to come from the callee's RETURN, not from the conservative rule that"
                + " says the result may carry any tainted argument -- the conservative rule would"
                + " report this call whatever the names were")
        .contains("callee-ret:");
  }

  @Test
  @DisplayName("a plain by-value argument still reaches the sink")
  void aPlainByValueArgumentStillReachesTheSink() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", BY_VALUE_ARGUMENT);

    assertThat(findings(ndjson, COMMAND_CWE))
        .as("the control: the by-value path has to keep working, or the named path proves nothing")
        .isNotEmpty();
  }

  /**
   * The findings that carry the given CWE.
   *
   * @param ndjson the NDJSON the scan produced
   * @param cwe the CWE to look for
   * @return the lines that name it
   */
  private static List<String> findings(String ndjson, String cwe) {
    return ndjson.lines().filter(line -> line.contains("\"cwe\":[\"" + cwe)).toList();
  }

  /**
   * Runs a scan over one source file, with everything but the taint checker disabled.
   *
   * @param fileName the file's name -- its extension selects the front end
   * @param source the source text
   * @return the NDJSON the scan produced
   */
  private static String scan(String fileName, String source) throws Exception {
    Path dir = Files.createTempDirectory("plumb-csharp-named-e2e");
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
