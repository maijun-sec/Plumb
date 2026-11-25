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
 * The end-to-end proof that an extension method's receiver reaches the parameter it is.
 *
 * <p><b>The shape.</b> An extension method's receiver is its FIRST FORMAL -- the {@code this}
 * parameter -- and it is also the call's receiver. The engine binds formal {@code i} to {@code
 * args[i+1]}, and it derives the number of parameters from the argument count. So a reduced
 * extension call whose argument list was exactly as long as the parameter list read every formal
 * after the first one argument too far, and did not read the last formal at all.
 *
 * <p><b>Why the by-reference case is the one that costs a finding.</b> A value the callee RETURNS
 * can also travel the other way -- through a conservative rule that says "the result may carry the
 * argument" -- so a misbound by-value argument is usually covered. A value the callee WRITES BACK
 * into an {@code out} parameter has no such second route: it exists only if the write-back is bound
 * to the right formal. That is why the first case below is the one that was missed.
 *
 * <p><b>Why the controls are the same method.</b> The explicit static form of the same extension
 * method reaches the same callee key and therefore the same summary; only the call syntax differs.
 * A difference in the readings between the two is caused by the argument shape alone.
 *
 * <p><b>Why the null-dereference case is here.</b> The receiver is read from {@code args[0]}, so a
 * fix that moved the receiver out of that slot would drop a real finding. The last case says the
 * receiver is still there.
 */
class CSharpExtensionMethodScanE2ETest {

  /** The command-injection sink the C# table names. */
  private static final String COMMAND_CWE = "CWE-78";

  /** The null-dereference finding the receiver slot carries. */
  private static final String NULL_DEREF_CWE = "CWE-476";

  /** The extension method writes its receiver into its out parameter; the call is reduced. */
  private static final String REDUCED_BY_REFERENCE =
      """
      using System;

      namespace Demo;

      public static class Ext
      {
          public static void Fill(this string s, out string v)
          {
              v = s;
          }
      }

      public class Vulnerable
      {
          public void Run()
          {
              var tainted = Console.ReadLine();
              string result;
              tainted.Fill(out result);
              System.Diagnostics.Process.Start(result);
          }
      }
      """;

  /** The same method, the same callee, the same taint -- called in its explicit static form. */
  private static final String EXPLICIT_STATIC_BY_REFERENCE =
      """
      using System;

      namespace Demo;

      public static class Ext
      {
          public static void Fill(this string s, out string v)
          {
              v = s;
          }
      }

      public class Vulnerable
      {
          public void Run()
          {
              var tainted = Console.ReadLine();
              string result;
              Ext.Fill(tainted, out result);
              System.Diagnostics.Process.Start(result);
          }
      }
      """;

  /** The control: the same shape, but the method is not an extension method. */
  private static final String PLAIN_STATIC_BY_REFERENCE =
      """
      using System;

      namespace Demo;

      public class Plain
      {
          public static void Fill(string s, out string v)
          {
              v = s;
          }
      }

      public class Vulnerable
      {
          public void Run()
          {
              var tainted = Console.ReadLine();
              string result;
              Plain.Fill(tainted, out result);
              System.Diagnostics.Process.Start(result);
          }
      }
      """;

  /** The extension method returns its receiver, and the receiver is tainted. */
  private static final String REDUCED_RETURNS_THE_RECEIVER =
      """
      using System;

      namespace Demo;

      public static class Ext
      {
          public static string Echo(this string s, string other)
          {
              return s;
          }
      }

      public class Vulnerable
      {
          public void Run()
          {
              var tainted = Console.ReadLine();
              var returned = tainted.Echo("not-a-command");
              System.Diagnostics.Process.Start(returned);
          }
      }
      """;

  /** The same call with nothing tainted anywhere. */
  private static final String REDUCED_AND_CLEAN =
      """
      using System;

      namespace Demo;

      public static class Ext
      {
          public static string Echo(this string s, string other)
          {
              return s;
          }
      }

      public class Vulnerable
      {
          public void Run()
          {
              var returned = "not-a-command".Echo("also-not-a-command");
              System.Diagnostics.Process.Start(returned);
          }
      }
      """;

  /** The control: a plain instance method. */
  private static final String PLAIN_INSTANCE =
      """
      using System;

      namespace Demo;

      public class Plain
      {
          public string Echo(string other)
          {
              return other;
          }
      }

      public class Vulnerable
      {
          public void Run()
          {
              var tainted = Console.ReadLine();
              var plain = new Plain();
              var returned = plain.Echo(tainted);
              System.Diagnostics.Process.Start(returned);
          }
      }
      """;

  @Test
  @DisplayName("a value an extension method writes back reaches the caller")
  void aValueAnExtensionMethodWritesBackReachesTheCaller() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", REDUCED_BY_REFERENCE);

    assertThat(findings(ndjson, COMMAND_CWE))
        .as(
            "the callee writes the caller's receiver into its out parameter, and the caller reads"
                + " that variable at the sink")
        .isNotEmpty();
    assertThat(ndjson)
        .as("the finding has to name the write-back, or it came from somewhere else")
        .contains("writes back into arg#");
  }

  @Test
  @DisplayName("the control: the same method in its explicit static form reaches the caller")
  void theControlTheSameMethodInItsExplicitStaticFormReachesTheCaller() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", EXPLICIT_STATIC_BY_REFERENCE);

    assertThat(findings(ndjson, COMMAND_CWE))
        .as(
            "the control: the same method, the same callee key and the same taint, so the reduced"
                + " form has to reach the caller too")
        .isNotEmpty();
  }

  @Test
  @DisplayName("the control: a plain static method with the same shape reaches the caller")
  void theControlAPlainStaticMethodWithTheSameShapeReachesTheCaller() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", PLAIN_STATIC_BY_REFERENCE);

    assertThat(findings(ndjson, COMMAND_CWE))
        .as("the control: the by-reference path has to keep working")
        .isNotEmpty();
  }

  @Test
  @DisplayName("an extension method's receiver is bound to the parameter it is")
  void anExtensionMethodsReceiverIsBoundToTheParameterItIs() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", REDUCED_RETURNS_THE_RECEIVER);

    assertThat(findings(ndjson, COMMAND_CWE))
        .as("the callee returns the receiver, and the receiver is tainted")
        .isNotEmpty();
    assertThat(ndjson)
        .as(
            "the taint has to come from the callee's RETURN, not from the conservative rule that"
                + " says the result may carry any tainted argument -- the conservative rule would"
                + " report this call whatever the shape was")
        .contains("callee-ret:");
  }

  @Test
  @DisplayName("an extension call with nothing tainted stays silent")
  void anExtensionCallWithNothingTaintedStaysSilent() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", REDUCED_AND_CLEAN);

    assertThat(findings(ndjson, COMMAND_CWE))
        .as("the negative case: the fix must not report every extension call")
        .isEmpty();
  }

  @Test
  @DisplayName(
      "the receiver stays in the receiver slot, so a call on a possibly-null receiver is still"
          + " reported")
  void theReceiverStaysInTheReceiverSlot() throws Exception {
    CSharpSidecarSupport.assumeSidecarAvailable();

    String ndjson = scan("Vulnerable.cs", REDUCED_BY_REFERENCE);

    assertThat(findings(ndjson, NULL_DEREF_CWE))
        .as(
            "the receiver is read from the first argument slot, so a call on a possibly-null"
                + " receiver is still a finding")
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
    Path dir = Files.createTempDirectory("plumb-csharp-extension-e2e");
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
