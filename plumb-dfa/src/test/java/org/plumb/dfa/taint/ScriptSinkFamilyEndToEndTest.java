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
package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.FormatStringChecker;
import org.plumb.dfa.checkers.taint.LogInjectionChecker;
import org.plumb.dfa.checkers.taint.RegexInjectionChecker;
import org.plumb.dfa.checkers.taint.XssInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.php.PhpSidecarClient;
import org.plumb.tac.frontend.php.PhpTacFrontend;
import org.plumb.tac.frontend.python.PythonTacFrontend;
import org.plumb.tac.frontend.sidecar.PythonRuntime;
import org.plumb.tac.frontend.ts.TsSidecarClient;
import org.plumb.tac.frontend.ts.TsTacFrontend;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;

/**
 * The script languages' own sinks, end to end: the real front end, the real engine and the real
 * catalogue.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>A catalogue test builds its TAC by hand, so it proves the catalogue matches the names the test
 * wrote down. A front end test proves the front end emits the names it was written to emit. Neither
 * notices when the two disagree -- and when they disagree, the symptom is no crash and no warning:
 * a scan that reports the program clean.
 *
 * <p>The command family has its own chain test. This one covers the three NAMING CONVENTIONS that
 * the command family does not exercise:
 *
 * <ol type="a">
 *   <li><b>The owner is a CLASS, not a module.</b> The owner of {@code document.write} is the
 *       {@code Document} TYPE -- the page's type, not the file's module. A catalogue that named
 *       modules only would never have found it.
 *   <li><b>The tainted value is the call's RESULT, not an argument.</b> {@code socket.recv} is a
 *       SOURCE: the bytes that arrived are what the call RETURNS, and the engine has to carry that
 *       taint forward into a later sink.
 *   <li><b>The owner is a NAMESPACE.</b> A PHP global function's owner is the namespace the name
 *       was qualified with, so the first user argument is {@code args[1]} there too.
 * </ol>
 *
 * <h2>Why it SKIPS</h2>
 *
 * <p>Each language needs its own runtime: Node and the TypeScript compiler for the script front
 * ends, Python for the Python one. A machine without them is missing an environment, not carrying a
 * broken product -- the same reason the Go front end's test skips when there is no Go toolchain.
 */
@DisplayName("Taint: the script languages reach their own sinks through the real front ends")
class ScriptSinkFamilyEndToEndTest {

  private static final String XSS_RULE = "dfa-taint-xss-injection";
  private static final String REGEX_RULE = "dfa-taint-regex-injection";
  private static final String FORMAT_RULE = "dfa-taint-format-string";
  private static final String LOG_RULE = "dfa-taint-log-injection";

  /**
   * A TypeScript program that writes a tainted value into the page.
   *
   * <p>The owner of {@code document.write} is the {@code Document} TYPE, and the text is the first
   * user argument.
   */
  private static final String TS_SOURCE =
      """
      export function handler(input: string): void {
        document.write(input);
      }
      """;

  /**
   * A Python program that compiles a pattern it read from the network.
   *
   * <p>Two conventions in one program: {@code socket.recv} is a source whose tainted value is the
   * call's RESULT, and {@code re.compile} is a sink whose owner is the module the function came
   * from.
   */
  private static final String PYTHON_SOURCE =
      """
      import re
      import socket


      def handler():
          data = socket.recv(1024)
          return re.compile(data)
      """;

  /** A PHP program that formats a tainted value. */
  private static final String PHP_SOURCE =
      """
      <?php
      function handler(string $name): void {
          sprintf($name, "x");
      }
      """;

  /**
   * A Python program in a file whose NAME is a module the catalogue knows.
   *
   * <p>The catalogue's Python pattern for the logging module is {@code
   * ^logging\.(debug|info|warning|error|critical|exception)\b}, and a receiver whose type the front
   * end could not prove used to fall back to the module the FILE belongs to. A file called {@code
   * logging.py} therefore produced the callee key {@code logging.error/1}, and the catalogue
   * claimed the program's own method call as the logging module's sink: a log-injection finding
   * against a program that never called the logging module at all.
   *
   * <p>The degraded owner is now {@code <unknown>}. No module path can contain an angle bracket,
   * and every sink pattern in the catalogue is anchored at the start of the callee key, so a key
   * that begins with one cannot be claimed by any of them.
   */
  private static final String PYTHON_FILE_NAMED_AFTER_A_MODULE =
      """
      import socket


      def handler(logger):
          data = socket.recv(1024)
          logger.error(data)
      """;

  /**
   * A TypeScript program in a file whose NAME is a module the catalogue knows.
   *
   * <p>The catalogue's TypeScript pattern for the format helper is {@code
   * ^(?:node:)?util\.format\b}, and the degraded owner used to be the module the FILE belongs to. A
   * file called {@code util.ts} therefore produced the callee key {@code util.format/1}, and the
   * catalogue claimed the program's own method call as the format helper's sink.
   *
   * <p>The degraded owner is now {@code <unknown>}, for the same reason as in Python: no module
   * path can contain an angle bracket, and every sink pattern is anchored at the start of the
   * callee key.
   */
  private static final String TS_FILE_NAMED_AFTER_A_MODULE =
      """
      export function handler(): void {
        const value = process.env["SECRET"];
        obj.format(value);
      }
      """;

  @Test
  @DisplayName("a TypeScript page write reaches the XSS rule")
  void aTypeScriptPageWriteReachesTheXssRule(@TempDir Path directory) throws IOException {
    assumeTrue(tsAvailable(), "the TypeScript front end needs Node and its compiler dependency");

    Path file = directory.resolve("app.ts");
    Files.writeString(file, TS_SOURCE);

    List<Finding> findings =
        scan(
            new TsTacFrontend(SourceLanguage.TYPESCRIPT),
            TS_SOURCE,
            file,
            new XssInjectionChecker());

    assertThat(hasFinding(findings, XSS_RULE, "Document.write"))
        .as("the owner of the write is the Document type, and the text is the first argument")
        .isTrue();
  }

  @Test
  @DisplayName("a Python pattern compiled from the network reaches the regex rule")
  void aPythonPatternCompiledFromTheNetworkReachesTheRegexRule(@TempDir Path directory)
      throws IOException {
    assumeTrue(pythonAvailable(), "the Python front end needs a Python 3 runtime");

    Path file = directory.resolve("app.py");
    Files.writeString(file, PYTHON_SOURCE);

    List<Finding> findings =
        scan(new PythonTacFrontend(), PYTHON_SOURCE, file, new RegexInjectionChecker());

    assertThat(hasFinding(findings, REGEX_RULE, "re.compile"))
        .as("the bytes that arrived are the call's result, and they reach the pattern")
        .isTrue();
  }

  @Test
  @DisplayName("a PHP format string reaches the format rule")
  void aPhpFormatStringReachesTheFormatRule(@TempDir Path directory) throws IOException {
    assumeTrue(phpAvailable(), "the PHP front end needs Node and its parser dependency");

    Path file = directory.resolve("app.php");
    Files.writeString(file, PHP_SOURCE);

    List<Finding> findings =
        scan(new PhpTacFrontend(), PHP_SOURCE, file, new FormatStringChecker());

    assertThat(hasFinding(findings, FORMAT_RULE, "\\sprintf"))
        .as("the format string is the first user argument of a PHP global function")
        .isTrue();
  }

  // ------------------------------------------------------------------ the chain

  /**
   * A file named after a module the catalogue knows is not that module.
   *
   * <p>This is the false positive that the degraded owner used to cause, end to end: the real front
   * end, the real engine and the real catalogue, on a program whose own file is called {@code
   * logging.py}.
   */
  @Test
  @DisplayName("a file named after a module the catalogue knows is not that module")
  void aFileNamedAfterAModuleTheCatalogueKnowsIsNotThatModule(@TempDir Path directory)
      throws IOException {
    assumeTrue(pythonAvailable(), "the Python front end needs a Python 3 runtime");

    Path file = directory.resolve("logging.py");
    Files.writeString(file, PYTHON_FILE_NAMED_AFTER_A_MODULE);

    TacFrontend.ParseResult result =
        new PythonTacFrontend().parse(PYTHON_FILE_NAMED_AFTER_A_MODULE, file, null);

    List<Finding> findings = scan(result, file, new LogInjectionChecker());

    assertThat(hasFinding(findings, LOG_RULE, "error"))
        .as("the program's own file is not the logging module, so the catalogue must not claim it")
        .isFalse();

    assertThat(calleeSignatures(result))
        .as("the receiver's type could not be proven, so the owner is unknown")
        .contains("<unknown>.error/1")
        .doesNotContain("logging.error/1");
  }

  /**
   * The same false positive, in TypeScript.
   *
   * <p>Two front ends lower the same convention, so the convention is pinned twice: a fix in one of
   * them that leaves the other alone would still let the catalogue claim the program's own file.
   */
  @Test
  @DisplayName("a TypeScript file named after a module the catalogue knows is not that module")
  void aTypeScriptFileNamedAfterAModuleTheCatalogueKnowsIsNotThatModule(@TempDir Path directory)
      throws IOException {
    assumeTrue(tsAvailable(), "the TypeScript front end needs Node and its compiler dependency");

    Path file = directory.resolve("util.ts");
    Files.writeString(file, TS_FILE_NAMED_AFTER_A_MODULE);

    TacFrontend.ParseResult result =
        new TsTacFrontend(SourceLanguage.TYPESCRIPT)
            .parse(TS_FILE_NAMED_AFTER_A_MODULE, file, null);

    List<Finding> findings = scan(result, file, new FormatStringChecker());

    assertThat(hasFinding(findings, FORMAT_RULE, "format"))
        .as("the program's own file is not the util module, so the catalogue must not claim it")
        .isFalse();

    assertThat(calleeSignatures(result))
        .as("the receiver's type could not be proven, so the owner is unknown")
        .contains("<unknown>.format/0")
        .doesNotContain("util.format/0");
  }

  /**
   * Run the parsed functions through the engine.
   *
   * <p>Unlike {@link #scan(TacFrontend, String, Path, DfaChecker)}, this does not require the front
   * end to have reported nothing. The program under test is one whose receiver the front end could
   * not type, and that is reported as a lowered precision -- which is the very thing the test is
   * about.
   */
  private static List<Finding> scan(TacFrontend.ParseResult result, Path file, DfaChecker checker) {
    TacProgram program = new TacProgram(file, "app");
    for (TacFunction function : result.functions()) {
      program.addFunction(function);
    }

    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(checker), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> findings = new ArrayList<>();
    for (Finding finding : reporter.all()) {
      if (finding.ruleId().startsWith("dfa-taint-")) {
        findings.add(finding);
      }
    }
    return findings;
  }

  /** Every callee key the front end emitted, in the order it emitted them. */
  private static List<String> calleeSignatures(TacFrontend.ParseResult result) {
    List<String> signatures = new ArrayList<>();
    for (TacFunction function : result.functions()) {
      for (TacInstruction instruction : function.instructions()) {
        if (instruction.calleeSig != null) {
          signatures.add(instruction.calleeSig);
        }
      }
    }
    return signatures;
  }

  /**
   * Run the source through the front end, then through the engine.
   *
   * @param frontend the front end that lowers the source
   * @param source the source text
   * @param file the file the source came from
   * @param checker the rule under test
   * @return the findings the engine reported
   */
  private static List<Finding> scan(
      TacFrontend frontend, String source, Path file, DfaChecker checker) {
    TacFrontend.ParseResult result = frontend.parse(source, file, null);
    assertThat(result.problems()).as("the source must lower without a problem").isEmpty();

    TacProgram program = new TacProgram(file, "app");
    for (TacFunction function : result.functions()) {
      program.addFunction(function);
    }

    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(checker), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> findings = new ArrayList<>();
    for (Finding finding : reporter.all()) {
      if (finding.ruleId().startsWith("dfa-taint-")) {
        findings.add(finding);
      }
    }
    return findings;
  }

  /** Whether a finding of the given rule names the given callee. */
  private static boolean hasFinding(List<Finding> findings, String rule, String callee) {
    for (Finding finding : findings) {
      if (!rule.equals(finding.ruleId())) {
        continue;
      }
      if (finding.message().contains(callee)) {
        return true;
      }
    }
    return false;
  }

  // ------------------------------------------------------------------ the environment

  /** Whether the script front end can run here. */
  private static boolean tsAvailable() {
    return nodeSidecarAvailable(
        TsSidecarClient.locateNode(), TsSidecarClient.locateScript(null), "typescript");
  }

  /** Whether the PHP front end can run here. */
  private static boolean phpAvailable() {
    return nodeSidecarAvailable(
        PhpSidecarClient.locateNode(), PhpSidecarClient.locateScript(null), "php-parser");
  }

  /**
   * Whether a Node sidecar can run here.
   *
   * <p>Three things have to be there: Node, the sidecar's script, and the package the script
   * imports. The third is the one that is easy to miss, and its absence would make the sidecar die
   * on its first request -- which would look like a broken product rather than a missing
   * dependency.
   */
  private static boolean nodeSidecarAvailable(Path node, Path script, String dependency) {
    if (node == null || script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null && Files.isDirectory(root.resolve("node_modules").resolve(dependency));
  }

  /** Whether the Python front end can run here. */
  private static boolean pythonAvailable() {
    return PythonRuntime.locate() != null;
  }
}
