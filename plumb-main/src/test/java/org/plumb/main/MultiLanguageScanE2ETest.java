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
 * The end-to-end proof that Kotlin and Scala files reach the ANALYSIS.
 *
 * <p><b>Why this test exists, and why it is not the frontend's own test.</b> The Kotlin and Scala
 * frontends were implemented and unit-tested, and the unit tests passed -- while a real scan could
 * not read a single Kotlin file, because {@code ScanRunner} filtered for {@code .java} and never
 * constructed a frontend. A frontend test proves the frontend works. It does NOT prove the frontend
 * is REACHED.
 *
 * <p>That distinction is the whole point. "The frontend produced no findings" and "the frontend
 * never ran" look identical from the outside: both are zero findings. A test that calls the
 * frontend directly cannot tell them apart. Only a test that goes through {@link ScanRunner} -- the
 * way a user does -- can.
 *
 * <p>So these tests run the real scan over a real {@code .kt} / {@code .scala} file and assert that
 * a KNOWN-BAD pattern produces a KNOWN CWE. The pattern is a textbook SQL injection: a request
 * parameter reaching {@code Statement.execute}. It is also a test of the whole chain:
 *
 * <ol>
 *   <li>the file is DISCOVERED (not filtered out as "not a .java file");
 *   <li>the right FRONTEND is selected for its extension;
 *   <li>the frontend lowers it to TAC;
 *   <li>the callee signature carries the OWNER, so the sink family matches;
 *   <li>the taint engine propagates the source to the sink;
 *   <li>the checker reports the CWE.
 * </ol>
 *
 * <p>Break any link and this test fails. That is the point.
 */
class MultiLanguageScanE2ETest {

  /** A Kotlin SQL injection: a request parameter reaches Statement.execute. */
  private static final String KOTLIN_SQLI =
      """
      import java.sql.Statement

      class Vulnerable {
        fun query(stmt: Statement, userInput: String) {
          stmt.execute(userInput)
        }
      }
      """;

  /** The same SQL injection in Scala 3. */
  private static final String SCALA_SQLI =
      """
      import java.sql.Statement

      class Vulnerable:
        def query(stmt: Statement, userInput: String): Unit =
          stmt.execute(userInput)
      """;

  /**
   * Runs a scan over one source file, with everything but the taint checker disabled.
   *
   * @param fileName the file's name -- its extension selects the frontend
   * @param source the source text
   * @return the NDJSON the scan produced
   */
  private static String scan(String fileName, String source) throws Exception {
    Path dir = Files.createTempDirectory("plumb-multilang-e2e");
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
  @DisplayName("a .kt file reaches the analysis and its SQL injection is reported")
  void kotlinFileIsScannedAndItsInjectionReported() throws Exception {
    String ndjson = scan("Vulnerable.kt", KOTLIN_SQLI);

    assertThat(ndjson)
        .as("the Kotlin file must reach the taint engine and be reported as CWE-89")
        .contains("CWE-89");
  }

  @Test
  @DisplayName("a .scala file reaches the analysis and its SQL injection is reported")
  void scalaFileIsScannedAndItsInjectionReported() throws Exception {
    String ndjson = scan("Vulnerable.scala", SCALA_SQLI);

    assertThat(ndjson)
        .as("the Scala file must reach the taint engine and be reported as CWE-89")
        .contains("CWE-89");
  }

  @Test
  @DisplayName("the file extension selects the frontend")
  void extensionSelectsTheFrontend() {
    assertThat(FrontendRegistry.languageOfExtension("java"))
        .isEqualTo(org.plumb.tac.ir.SourceLanguage.JAVA);
    assertThat(FrontendRegistry.languageOfExtension("kt"))
        .isEqualTo(org.plumb.tac.ir.SourceLanguage.KOTLIN);
    assertThat(FrontendRegistry.languageOfExtension("kts"))
        .isEqualTo(org.plumb.tac.ir.SourceLanguage.KOTLIN);
    assertThat(FrontendRegistry.languageOfExtension("scala"))
        .isEqualTo(org.plumb.tac.ir.SourceLanguage.SCALA);
    assertThat(FrontendRegistry.languageOfExtension("txt"))
        .isEqualTo(org.plumb.tac.ir.SourceLanguage.UNKNOWN);
  }

  @Test
  @DisplayName("a .kt and a .scala file are both recognised as source")
  void bothLanguagesAreRecognisedAsSource() {
    assertThat(FrontendRegistry.isSourceFile(Path.of("a", "b", "X.java"))).isTrue();
    assertThat(FrontendRegistry.isSourceFile(Path.of("a", "b", "X.kt"))).isTrue();
    assertThat(FrontendRegistry.isSourceFile(Path.of("a", "b", "X.scala"))).isTrue();
    assertThat(FrontendRegistry.isSourceFile(Path.of("a", "b", "X.txt"))).isFalse();
    assertThat(FrontendRegistry.isSourceFile(Path.of("a", "b", "Makefile"))).isFalse();
  }

  @Test
  @DisplayName("a Kotlin file that is not a source file is not scanned as Java")
  void kotlinIsNotMistakenForJava() {
    // The discovery filter has to accept .kt. If it only accepted .java, the
    // file would be skipped -- and a scan of Kotlin-only code would report
    // "no source files found", which is the original bug.
    assertThat(FrontendRegistry.knownExtensions()).contains("java", "kt", "scala");
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
