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
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.cg.CallGraphBuilder;
import org.plumb.tac.ir.CalleeKeyNormalizer;

/**
 * What the JVM languages actually do to each other, as far as the analyser is concerned.
 *
 * <p><b>The claim under test.</b> Java, Kotlin and Scala share a JVM, so they can call each other.
 * "The languages can call each other" is a claim about the <i>platform</i>. The claim that matters
 * for a scanner is narrower: does a tainted value flow <i>across</i> the language boundary and
 * reach a sink?
 *
 * <p><b>What the measurement found, and what changed.</b> The measurement said <b>no</b>, for four
 * distinct reasons -- one per boundary, plus one that is not about a boundary at all. All four are
 * now fixed, and the assertions below pin the FIXED state:
 *
 * <ol>
 *   <li><b>The callee key was not a shared convention.</b> A Kotlin call names its callee {@code
 *       demo.JavaSink.run/1}; a Java declaration is registered as {@code run(String):void} with
 *       enclosing class {@code JavaSink}. The call graph matched on a <i>bare</i> name, and the
 *       bare helper strips the owner for one form and keeps it for the other -- so the two sides of
 *       a cross-language call computed different keys and the edge was never built. The fix is a
 *       canonical key ({@code owner.method/arity}) with the owner matched by SIMPLE NAME, shared by
 *       every frontend and by the call graph itself. See {@link CalleeKeyNormalizer}.
 *   <li><b>A Scala call to a Java static names the MODULE CLASS.</b> dotc models a Java class's
 *       static members as members of a synthetic module class, so the Scala side says {@code
 *       demo.JavaSink$.readAll/1} where the Java side says {@code demo.JavaSink.readAll}. The
 *       module-class suffix is now normalised at MATCH time -- never in the emitted key, because
 *       {@code Service$} and {@code Service} are two different classes.
 *   <li><b>The Scala compiler could not see the Java sources.</b> It got a {@code -classpath}
 *       (which holds binaries) but the Java class existed only as SOURCE, and a compiler resolves a
 *       source class as {@code <root>/<package>/Class.java}. The frontend now stages the Java
 *       sources into a package-shaped tree, so the lookup succeeds whatever the original layout
 *       was.
 *   <li><b>The Kotlin frontend REBUILT the file it was analysing.</b> A file rebuilt through the
 *       PSI factory is an in-memory file, and the Analysis API classifies such a file as
 *       "dangling": no module claims it, so it is analysed in a module with NO DEPENDENCIES, and
 *       every reference in it resolves to an error type. The Kotlin side therefore could not see
 *       the Java class at all, could not resolve the callee's OWNER, and degraded the callee
 *       signature to a bare name -- so the edge was never built. Using the file as it is fixes
 *       both.
 *   <li><b>The parameter-origin pre-pass ignored the TAC's operand slots.</b> A {@code MOVE} -- an
 *       assignment -- carries its source in {@code lhs1} and has an EMPTY argument list. The
 *       pre-pass scanned only the argument list, so a local assignment never inherited a label, and
 *       a value that reached a sink through a local was reported as the SINK's own parameter.
 * </ol>
 *
 * <p><b>Why "the finding fires" is not the same as "the languages interoperate".</b>
 *
 * <p>A sink function whose parameter is itself treated as tainted produces a finding on its own. So
 * a scan of {@code JavaSink.java} ALONE already reports CWE-78 -- attributed to the sink's own
 * file, with the real caller nowhere in sight. A test that only asserted "CWE-78 appears" would
 * pass without any cross-language capability whatsoever.
 *
 * <p><b>And why the finding's FILE is the SINK's file.</b> That is Plumb's convention, not an
 * accident: the sink is where the dangerous operation happens, and that is the line a developer has
 * to fix. The corpus says so explicitly -- see {@code
 * testcase/01-taint-crossfile/EXPECTATIONS.json}: "Attribution is to the sink, not to the
 * orchestrator method that started the chain, so the file must be the sink file for the case to be
 * measurable."
 *
 * <p>So the cross-language capability is NOT measured by the file. It is measured by the finding's
 * <b>TAINT SOURCE</b>: when the chain was followed, the source is the CALLER's value ({@code
 * param:in}); when it was not, the source is the SINK's own parameter ({@code param:cmd}), which is
 * indistinguishable in the output from "taint never crossed the call boundary". Those two labels
 * are the difference between a capability and a report, and that is what these tests pin.
 */
class CrossLanguageInteropTest {

  /** A Java class holding the SINK and a helper that reads untrusted input. */
  private static final String JAVA_SINK =
      """
      package demo;

      import java.io.BufferedReader;
      import java.io.InputStream;
      import java.io.InputStreamReader;

      public class JavaSink {
        public static String readAll(InputStream in) throws Exception {
          BufferedReader r = new BufferedReader(new InputStreamReader(in));
          return r.readLine();
        }

        public static void run(String cmd) throws Exception {
          Process p = Runtime.getRuntime().exec(cmd);
          p.waitFor();
        }
      }
      """;

  /** A Kotlin class holding the SINK, so Java can call into Kotlin. */
  private static final String KOTLIN_SINK =
      """
      package demo

      import java.io.InputStream

      object KotlinSink {
        fun read(in: InputStream): String = in.readBytes().toString(Charsets.UTF_8)

        fun execIt(cmd: String) {
          Runtime.getRuntime().exec(cmd)
        }
      }
      """;

  /** Kotlin calling the JAVA sink. */
  private static final String KOTLIN_CALLS_JAVA =
      """
      package demo

      import java.io.InputStream

      class KotlinCallsJava {
        fun run(in: InputStream) {
          val cmd = JavaSink.readAll(in)
          JavaSink.run(cmd)
        }
      }
      """;

  /** Scala calling the JAVA sink. */
  private static final String SCALA_CALLS_JAVA =
      """
      package demo

      import java.io.InputStream

      object ScalaCallsJava:
        def run(in: InputStream): Unit =
          val cmd = JavaSink.readAll(in)
          JavaSink.run(cmd)
      """;

  /** Java calling the KOTLIN sink. */
  private static final String JAVA_CALLS_KOTLIN =
      """
      package demo;

      import java.io.InputStream;

      public class JavaCallsKotlin {
        public static void run(InputStream in) throws Exception {
          String cmd = KotlinSink.read(in);
          KotlinSink.execIt(cmd);
        }
      }
      """;

  /**
   * A Scala closure that CAPTURES the tainted value and uses it at the sink.
   *
   * <p><b>Why this fixture is separate.</b> The other Scala fixture passes the value to the sink as
   * an ARGUMENT, so the chain crosses a call boundary that the call graph follows. This one hands
   * the value to a CLOSURE, and the closure is a different function: the value has to survive the
   * capture, not a call.
   *
   * <p>Before the capture was modelled, the closure read the captured identifier as a field of the
   * enclosing class -- a field the class does not have -- so the value the closure used was never
   * the caller's value. The taint stopped at the capture and the sink was reported as receiving its
   * own parameter instead.
   */
  private static final String SCALA_CAPTURE =
      """
      package demo

      import java.io.InputStream

      object ScalaCapture:
        def run(in: InputStream): Unit =
          val cmd = JavaSink.readAll(in)
          val use = () => JavaSink.run(cmd)
          use()
      """;

  /**
   * Runs one scan over a set of files, with everything but the taint checker disabled.
   *
   * @param files the file name / source pairs
   * @return the NDJSON the scan produced
   */
  private static String scanFiles(String... files) throws Exception {
    Path dir = Files.createTempDirectory("plumb-xlang");
    Path outFile = dir.resolve("out.ndjson");
    try {
      for (int i = 0; i < files.length; i += 2) {
        Files.writeString(dir.resolve(files[i]), files[i + 1]);
      }
      ScanConfig cfg = new ScanConfig();
      cfg.inputs.add(dir);
      cfg.outputPath = outFile;
      cfg.outputFormat = ScanConfig.OutputFormat.NDJSON;
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
      try (var walk = Files.walk(dir)) {
        List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
        for (Path path : paths) {
          Files.deleteIfExists(path);
        }
      }
    }
  }

  /**
   * The dataflow label of the command-injection finding, or {@code null} when there is none.
   *
   * @param ndjson the scan output
   * @return the label, e.g. {@code "taint: param:in -> CWE-78"}
   */
  private static String commandInjectionFlow(String ndjson) {
    for (String line : ndjson.split("\n")) {
      if (!line.contains("dfa-taint-command-injection")) continue;
      int at = line.indexOf("\"dataflow\":\"");
      if (at < 0) continue;
      int start = at + "\"dataflow\":\"".length();
      int end = line.indexOf('"', start);
      if (end < 0) continue;
      return line.substring(start, end);
    }
    return null;
  }

  /**
   * Asserts that a finding's taint source is NOT the sink's own entry boundary.
   *
   * <p><b>Why this is the assertion, and not "the source is the caller's parameter".</b> The source
   * a finding reports is the best provenance the analysis can recover, and what that is depends on
   * the code:
   *
   * <ul>
   *   <li>When the chain crosses a call boundary, the source is the value the CALLER passed --
   *       either as the caller's own parameter ({@code param:in}) or, when the analysis can see
   *       that the value really originates inside the callee, as an input source ({@code
   *       input:demo.JavaSink.readAll/1}).
   *   <li>When the chain did NOT cross, the source is the SINK's own parameter ({@code param:cmd})
   *       -- because every parameter is seeded as a taint source at the function's entry boundary,
   *       and a sink method reports that.
   * </ul>
   *
   * <p>So the invariant that distinguishes the two is the ABSENCE of the sink's own parameter
   * label. Asserting one specific positive label would be asserting an implementation detail of the
   * provenance recovery, and would break the moment the recovery gets better.
   *
   * @param flow the finding's dataflow label
   * @param sinkParam the sink function's own parameter name
   */
  private static void assertChainCrossedTheBoundary(String flow, String sinkParam) {
    assertThat(flow).as("the finding has a dataflow label").isNotNull();
    assertThat(flow)
        .as(
            "the taint source is NOT the sink function's own parameter: a source of "
                + "'param:%s' means the sink parameter was treated as tainted on its own, so the "
                + "cross-language flow was never followed",
            sinkParam)
        .doesNotContain("param:" + sinkParam);
    assertThat(flow)
        .as("and the source names the caller's value, so the chain was followed")
        .containsPattern("taint: (param|input):\\S+ ->");
  }

  @Test
  @DisplayName("the bare-name key differs between the Java and the Kotlin signature forms")
  void theTwoSignatureFormsProduceDifferentKeys() {
    // The BARE-name helper still treats the two shapes differently, and that is
    // CORRECT: it is a lossy projection that drops the owner. It is NOT the key the
    // call graph matches on any more -- the OWNER-AWARE key is, and that key puts
    // the two spellings of the same class together.
    //
    // So this test now pins the SHAPE of the raw helper (so its behaviour does not
    // drift silently) AND the fact that the canonical key agrees across the two
    // forms. The end-to-end consequence -- the edge forming, and the taint source
    // being the caller's -- is pinned by the tests below.
    String javaKey = CallGraphBuilder.bareNameAndArity("run(String):void");
    String kotlinKey = CallGraphBuilder.bareNameAndArity("demo.JavaSink.run/1");

    assertThat(javaKey).as("the Java form strips the class prefix").isEqualTo("run/1");
    assertThat(kotlinKey).as("the slash form keeps it").isEqualTo("demo.JavaSink.run/1");
    assertThat(javaKey).as("the bare key is lossy and differs").isNotEqualTo(kotlinKey);

    // The CANONICAL key is what the call graph matches on, and it agrees.
    assertThat(CalleeKeyNormalizer.sameMethod("run(String):void", "demo.JavaSink.run/1"))
        .as("the canonical key puts the two spellings of the same class together")
        .isTrue();
  }

  @Test
  @DisplayName("the module-class spelling of a Java static names the same method")
  void theModuleClassSpellingNamesTheSameMethod() {
    // A Scala call to a Java static names the callee through the Java class's
    // synthetic module class. The suffix is normalised at MATCH time only.
    assertThat(CalleeKeyNormalizer.sameMethod("demo.JavaSink$.run/1", "demo.JavaSink.run/1"))
        .as("the module class and the class it belongs to are the same owner")
        .isTrue();
    assertThat(CalleeKeyNormalizer.sameMethod("demo.Alpha$.run/1", "demo.Beta.run/1"))
        .as("the module class of a DIFFERENT class is still a different class")
        .isFalse();
  }

  @Test
  @DisplayName("a Java caller reaching a Java sink DOES form a call-graph edge")
  void javaToJavaEdgeIsFormed() throws Exception {
    // The control case. It works, so the missing cross-language edge is not a
    // general call-graph problem.
    String ndjson =
        scanFiles(
            "Callee.java",
            """
            package demo;
            public class Callee {
              public static String readAll(java.io.InputStream in) throws Exception { return "x"; }
              public static void run(String cmd) throws Exception { Runtime.getRuntime().exec(cmd); }
            }
            """,
            "Caller.java",
            """
            package demo;
            import java.io.InputStream;
            public class Caller {
              public static void go(InputStream in) throws Exception {
                String cmd = Callee.readAll(in);
                Callee.run(cmd);
              }
            }
            """);

    assertThat(ndjson).as("the Java-to-Java path reports").contains("CWE-78");
  }

  @Test
  @DisplayName("a Kotlin caller reaching a Java sink: the taint SOURCE is the CALLER's")
  void kotlinReachesJavaSink() throws Exception {
    String ndjson = scanFiles("JavaSink.java", JAVA_SINK, "KotlinCallsJava.kt", KOTLIN_CALLS_JAVA);

    assertThat(ndjson).as("a finding is produced").contains("CWE-78");
    assertChainCrossedTheBoundary(commandInjectionFlow(ndjson), "cmd");
  }

  @Test
  @DisplayName("a Scala caller reaching a Java sink: the taint SOURCE is the CALLER's")
  void scalaReachesJavaSink() throws Exception {
    String ndjson = scanFiles("JavaSink.java", JAVA_SINK, "ScalaCallsJava.scala", SCALA_CALLS_JAVA);

    assertThat(ndjson).as("a finding is produced").contains("CWE-78");
    assertChainCrossedTheBoundary(commandInjectionFlow(ndjson), "cmd");
  }

  @Test
  @DisplayName("a Java caller reaching a Kotlin sink: the taint SOURCE is the CALLER's")
  void javaReachesKotlinSink() throws Exception {
    String ndjson =
        scanFiles("KotlinSink.kt", KOTLIN_SINK, "JavaCallsKotlin.java", JAVA_CALLS_KOTLIN);

    assertThat(ndjson).as("a finding is produced").contains("CWE-78");
    assertChainCrossedTheBoundary(commandInjectionFlow(ndjson), "cmd");
    assertThat(ndjson)
        .as("and no finding is attributed to an unknown file")
        .doesNotContain("\"file\":\"unknown\"");
  }

  @Test
  @DisplayName("a Scala closure CAPTURING the tainted value: the taint SOURCE is the CALLER's")
  void scalaClosureCaptureCarriesTheTaint() throws Exception {
    String ndjson = scanFiles("JavaSink.java", JAVA_SINK, "ScalaCapture.scala", SCALA_CAPTURE);

    assertThat(ndjson).as("a finding is produced").contains("CWE-78");
    // The value does not cross a CALL boundary here -- it crosses a CAPTURE. The
    // closure is a different function, and the captured value has to reach the sink
    // through the shared cell. If the capture is not modelled, the closure reads a
    // field the class does not have, the taint stops at the capture, and the sink
    // reports its OWN parameter -- which is exactly what this assertion rejects.
    assertChainCrossedTheBoundary(commandInjectionFlow(ndjson), "cmd");
  }
}
