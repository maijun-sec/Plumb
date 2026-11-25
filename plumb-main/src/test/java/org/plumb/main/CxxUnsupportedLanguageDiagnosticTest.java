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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.ir.SourceLanguage;
import org.slf4j.LoggerFactory;

/**
 * C and C++ have no frontend, so Plumb does not read them -- and it has to SAY so.
 *
 * <h2>What was broken</h2>
 *
 * <p>A C file is not a source file Plumb can read, so the file discovery dropped it without a word.
 * A project of a thousand {@code .c} files therefore produced ZERO findings, ZERO problems and a
 * normal exit code: the scan looked like a clean bill of health for code nobody ever read. "The
 * tool found nothing" and "the tool cannot read this" have to be different statements.
 *
 * <h2>What this pins</h2>
 *
 * <ul>
 *   <li>the <b>gap is declared</b> -- a C/C++ file produces the same {@code
 *       FRONTEND_NOT_IMPLEMENTED} problem every other language gap produces, and the message names
 *       C/C++ rather than saying "unknown";
 *   <li>the <b>declaration is deduplicated</b> -- one line for the scan, not one per file: a
 *       thousand C files say it once;
 *   <li>the <b>gap is not a capability</b> -- C/C++ is not in the recognised-extension list, so
 *       Plumb never claims to read it;
 *   <li>the <b>verdict stays honest</b> -- a project with nothing but C/C++ still ends in "no
 *       source files found" and a non-zero exit code, and the message now says WHY;
 *   <li>the <b>diagnostic is not a finding</b> -- it adds no finding to the report.
 * </ul>
 */
class CxxUnsupportedLanguageDiagnosticTest {

  /** A clean Java file: the scan has to read this one, and it has to find nothing in it. */
  private static final String CLEAN_JAVA =
      """
      package demo;
      class Clean {
          void m() {
              int x = 1;
              if (x > 0) {
                  x = x + 1;
              }
          }
      }
      """;

  @Test
  @DisplayName("a C/C++ file is not a source file Plumb can read")
  void cxxFilesAreNotSourceFiles() {
    assertThat(FrontendRegistry.isSourceFile(Path.of("app.c"))).isFalse();
    assertThat(FrontendRegistry.isSourceFile(Path.of("app.cpp"))).isFalse();
    assertThat(FrontendRegistry.isSourceFile(Path.of("app.cc"))).isFalse();
    assertThat(FrontendRegistry.isSourceFile(Path.of("app.h"))).isFalse();
    assertThat(FrontendRegistry.isSourceFile(Path.of("app.hpp"))).isFalse();
  }

  @Test
  @DisplayName("C/C++ is not in the list of languages Plumb reads")
  void cxxIsNotARecognisedLanguage() {
    // The recognised list is rendered to the user as "recognised: .java, .kt, ...".
    // C/C++ in that list would claim a capability Plumb does not have.
    assertThat(FrontendRegistry.knownExtensions())
        .as("Plumb does not read C/C++, so it must not claim to")
        .doesNotContain("c", "cpp", "cc", "h", "hpp");
    assertThat(FrontendRegistry.languageOfExtension("c")).isEqualTo(SourceLanguage.UNKNOWN);
    assertThat(FrontendRegistry.languageOfExtension("cpp")).isEqualTo(SourceLanguage.UNKNOWN);
  }

  @Test
  @DisplayName("a C/C++ file declares that its language has no frontend")
  void aCxxFileDeclaresTheMissingFrontend(@TempDir Path directory) throws Exception {
    Path file = directory.resolve("app.c");
    Files.writeString(file, "int main(void) { return 0; }\n", StandardCharsets.UTF_8);

    try (FrontendRegistry registry = new FrontendRegistry(null, List.of(directory), List.of())) {
      FrontendRegistry.ParsedSourceFile parsed = registry.parseFile(file);

      assertThat(parsed.functions()).isEmpty();
      assertThat(parsed.problems())
          .as("a file that was dropped without a word looks like a clean file")
          .hasSize(1);
      assertThat(parsed.problems().get(0).kind())
          .as("it is the same gap every other language gap is")
          .isEqualTo("FRONTEND_NOT_IMPLEMENTED");
      assertThat(parsed.problems().get(0).message())
          .contains("C/C++")
          .contains("no C/C++ frontend")
          .contains("not analysed")
          .doesNotContain("recognised");
    }
  }

  @Test
  @DisplayName("a project with many C/C++ files says it once, not once per file")
  void manyCxxFilesSayItOnce(@TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve("Clean.java"), CLEAN_JAVA, StandardCharsets.UTF_8);
    Files.writeString(
        directory.resolve("a.c"), "int a(void) { return 0; }\n", StandardCharsets.UTF_8);
    Files.writeString(
        directory.resolve("b.cpp"), "int b() { return 0; }\n", StandardCharsets.UTF_8);
    Files.writeString(directory.resolve("c.h"), "int c(void);\n", StandardCharsets.UTF_8);

    Path out = directory.resolve("out.ndjson");
    List<String> log = new ArrayList<>();
    int rc;
    Logger logger = (Logger) LoggerFactory.getLogger(ScanRunner.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      rc = new ScanRunner(config(directory, out)).run();
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
    for (ILoggingEvent event : appender.list) {
      log.add(event.getFormattedMessage());
    }

    assertThat(rc).as("the scan completes").isEqualTo(0);
    assertThat(log.stream().filter(line -> line.contains("[FRONTEND_NOT_IMPLEMENTED]")).count())
        .as("one line for the scan, not one per C/C++ file")
        .isEqualTo(1L);
    assertThat(log.stream().filter(line -> line.contains("[FRONTEND_NOT_IMPLEMENTED]")).toList())
        .allSatisfy(
            line ->
                assertThat(line)
                    .contains("C/C++")
                    .contains("not analysed")
                    .doesNotContain("recognised"));
  }

  @Test
  @DisplayName("the C/C++ diagnostic adds no finding")
  void theDiagnosticAddsNoFinding(@TempDir Path directory) throws Exception {
    Path java = directory.resolve("Clean.java");
    Files.writeString(java, CLEAN_JAVA, StandardCharsets.UTF_8);

    Path before = directory.resolve("before.ndjson");
    int beforeRc = new ScanRunner(config(directory, before)).run();
    List<String> beforeFindings = findings(before);

    Files.writeString(
        directory.resolve("a.c"), "int a(void) { return 0; }\n", StandardCharsets.UTF_8);
    Files.writeString(
        directory.resolve("b.cpp"), "int b() { return 0; }\n", StandardCharsets.UTF_8);
    Path after = directory.resolve("after.ndjson");
    int afterRc = new ScanRunner(config(directory, after)).run();
    List<String> afterFindings = findings(after);

    assertThat(beforeRc).isEqualTo(0);
    assertThat(afterRc).isEqualTo(0);
    assertThat(afterFindings)
        .as("adding C/C++ files to the project must not change the findings")
        .hasSameSizeAs(beforeFindings);
    assertThat(afterFindings)
        .as("a diagnostic is not a finding: it never becomes one")
        .noneMatch(
            line -> line.contains("a.c") || line.contains("b.cpp") || line.contains("C/C++"));
  }

  @Test
  @DisplayName("a project with nothing but C/C++ still says no source files found")
  void aCxxOnlyProjectStillSaysNoSourceFilesFound(@TempDir Path directory) throws Exception {
    Files.writeString(
        directory.resolve("a.c"), "int a(void) { return 0; }\n", StandardCharsets.UTF_8);
    Files.writeString(
        directory.resolve("b.cpp"), "int b() { return 0; }\n", StandardCharsets.UTF_8);

    Path out = directory.resolve("out.ndjson");
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    PrintStream original = System.err;
    int rc;
    try {
      System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
      rc = new ScanRunner(config(directory, out)).run();
    } finally {
      System.setErr(original);
    }
    String message = stderr.toString(StandardCharsets.UTF_8);

    assertThat(rc).as("a scan that read nothing must not look like a successful scan").isEqualTo(2);
    assertThat(message)
        .as("the verdict stays the pre-existing one")
        .contains("no source files found");
    assertThat(message)
        .as("and it says WHY the input produced nothing")
        .contains("no C/C++ frontend")
        .contains("not analysed");
    assertThat(recognisedExtensions(message))
        .as("C/C++ must not appear in the list of languages Plumb reads")
        .doesNotContain(".c", ".cpp", ".cc", ".h", ".hpp");
  }

  /**
   * The extensions the "no source files found" message renders as the recognised list.
   *
   * @param message the stderr message
   * @return the recognised extensions, each with its dot
   */
  private static List<String> recognisedExtensions(String message) {
    int start = message.indexOf("recognised: ");
    if (start < 0) {
      return List.of();
    }
    int end = message.indexOf(';', start);
    String list = message.substring(start + "recognised: ".length(), end);
    return List.of(list.split(", "));
  }

  /**
   * The findings the scan wrote.
   *
   * @param file the NDJSON file
   * @return the non-empty lines
   */
  private static List<String> findings(Path file) throws Exception {
    if (!Files.exists(file)) {
      return List.of();
    }
    List<String> lines = new ArrayList<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (!line.isBlank()) {
        lines.add(line);
      }
    }
    return lines;
  }

  /**
   * A scan configuration with every checker off.
   *
   * <p>This test is about the DIAGNOSTIC, so a finding would be noise: the checker that produced it
   * would be a different test's business.
   *
   * @param input the input directory
   * @param out the NDJSON output file
   * @return the configuration
   */
  private static ScanConfig config(Path input, Path out) {
    ScanConfig cfg = new ScanConfig();
    cfg.inputs.add(input);
    cfg.outputPath = out;
    cfg.outputFormat = ScanConfig.OutputFormat.NDJSON;
    cfg.runTaint = false;
    cfg.runStyle = false;
    cfg.runDivByZero = false;
    cfg.runResourceLeak = false;
    cfg.runNullDeref = false;
    cfg.runHardcodedCred = false;
    cfg.runIntOverflow = false;
    cfg.runIntUnderflow = false;
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
    cfg.runIteratorConcurrentModification = false;
    cfg.runCollectionSelfReference = false;
    cfg.runUncaughtExceptionPropagator = false;
    cfg.runUncheckedLoopCondition = false;
    cfg.runResourceExhaustion = false;
    cfg.runUncontrolledMemAlloc = false;
    cfg.runNumericTruncation = false;
    cfg.runBigDecimalDoubleConstructor = false;
    cfg.runDefaultEncoding = false;
    return cfg;
  }
}
