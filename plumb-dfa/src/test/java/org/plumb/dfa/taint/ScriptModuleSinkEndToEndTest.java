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
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
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
import org.plumb.tac.ir.TacProgram;

/**
 * The script languages, end to end: the real front end, the real engine and the real catalogue.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The sink catalogue and the front ends agree on a CONTRACT, and the contract is a naming
 * convention: the owner of a call to a module-level function is the MODULE the function was
 * imported from. The catalogue says {@code child_process.exec}, {@code os.system} and {@code
 * \system}, and the front ends have to produce exactly those names.
 *
 * <p>Nothing checks that agreement on its own. A catalogue test builds its TAC by hand, so it
 * proves the catalogue matches the names the test wrote down. A front end test proves the front end
 * emits the names it was written to emit. Neither of them notices when the two disagree -- and they
 * CAN disagree, because the naming convention has to be implemented on both sides.
 *
 * <p>When they disagree, the symptom is the worst kind: no crash, no warning, a scan that reports
 * the program clean. A program that runs a tainted command looks exactly like a program that does
 * not, and the difference is a string that does not match.
 *
 * <p>So this test goes through the whole chain: source text in, front end, TAC, engine, catalogue,
 * finding out. The assertion is on the FINDING, not on the intermediate TAC, because the finding is
 * the product -- the TAC is only a means to it.
 *
 * <h2>Why it SKIPS</h2>
 *
 * <p>Each language needs its own runtime: Node and the TypeScript compiler for the script front
 * ends, Python for the Python one. A machine without them is missing an environment, not carrying a
 * broken product -- the same reason the Go front end's test skips when there is no Go toolchain.
 *
 * <p><b>Skip is not pass.</b> A skipped test is a test that did not run, and the proof that the
 * script front ends reach the catalogue only exists when the runtimes are there. So the skip
 * message names the command that provides them: {@code npm ci} in {@code plumb-frontend/ts-sidecar}
 * and in {@code plumb-frontend/php-sidecar}, and a Python 3 on the PATH.
 */
@DisplayName("Taint: the script languages reach the catalogue through the real front ends")
class ScriptModuleSinkEndToEndTest {

  private static final String COMMAND_RULE = "dfa-taint-command-injection";

  /**
   * A TypeScript program that runs a tainted command through a module-level function.
   *
   * <p>The owner of the call is the MODULE the function was imported from, and the sink catalogue
   * names that module.
   */
  private static final String TS_SOURCE =
      """
      import { exec } from "child_process";

      export function handler(cmd: string): void {
        exec(cmd);
      }
      """;

  /**
   * A Python program that runs a tainted command through an imported function.
   *
   * <p>The alias is resolved away: the function is {@code os.system}, whatever the program called
   * it.
   */
  private static final String PYTHON_SOURCE =
      """
      from os import system


      def handler(cmd):
          system(cmd)
      """;

  /** A PHP program that runs a tainted command through a global function. */
  private static final String PHP_SOURCE =
      """
      <?php
      function handler(string $cmd): void {
          system($cmd);
      }
      """;

  @Test
  @DisplayName("a TypeScript command call reaches the command rule")
  void aTypeScriptCommandCallReachesTheCommandRule(@TempDir Path directory) throws IOException {
    assumeTrue(
        tsAvailable(),
        "the TypeScript front end needs Node and its compiler dependency: run npm ci in"
            + " plumb-frontend/ts-sidecar");

    Path file = directory.resolve("app.ts");
    Files.writeString(file, TS_SOURCE);

    List<Finding> findings = scan(new TsTacFrontend(SourceLanguage.TYPESCRIPT), TS_SOURCE, file);

    assertThat(hasCommandFinding(findings, "child_process.exec"))
        .as("the command call must be recognised through the module it came from")
        .isTrue();
  }

  @Test
  @DisplayName("a JavaScript command call reaches the command rule")
  void aJavaScriptCommandCallReachesTheCommandRule(@TempDir Path directory) throws IOException {
    assumeTrue(
        tsAvailable(),
        "the script front end needs Node and its compiler dependency: run npm ci in"
            + " plumb-frontend/ts-sidecar");

    Path file = directory.resolve("app.js");
    Files.writeString(file, TS_SOURCE);

    List<Finding> findings = scan(new TsTacFrontend(SourceLanguage.JAVASCRIPT), TS_SOURCE, file);

    assertThat(hasCommandFinding(findings, "child_process.exec"))
        .as("the same rule serves JavaScript")
        .isTrue();
  }

  @Test
  @DisplayName("a Python command call reaches the command rule")
  void aPythonCommandCallReachesTheCommandRule(@TempDir Path directory) throws IOException {
    assumeTrue(pythonAvailable(), "the Python front end needs a Python 3 runtime on the PATH");

    Path file = directory.resolve("app.py");
    Files.writeString(file, PYTHON_SOURCE);

    List<Finding> findings = scan(new PythonTacFrontend(), PYTHON_SOURCE, file);

    assertThat(hasCommandFinding(findings, "os.system"))
        .as("the imported function must be reached under the name it was exported under")
        .isTrue();
  }

  @Test
  @DisplayName("a PHP command call reaches the command rule")
  void aPhpCommandCallReachesTheCommandRule(@TempDir Path directory) throws IOException {
    assumeTrue(
        phpAvailable(),
        "the PHP front end needs Node and its parser dependency: run npm ci in"
            + " plumb-frontend/php-sidecar");

    Path file = directory.resolve("app.php");
    Files.writeString(file, PHP_SOURCE);

    List<Finding> findings = scan(new PhpTacFrontend(), PHP_SOURCE, file);

    assertThat(hasCommandFinding(findings, "\\system/"))
        .as("the global function must be reached under the name PHP gives it")
        .isTrue();
  }

  // ------------------------------------------------------------------ the chain

  /**
   * Run the source through the front end, then through the engine.
   *
   * @param frontend the front end that lowers the source
   * @param source the source text
   * @param file the file the source came from
   * @return the findings the engine reported
   */
  private static List<Finding> scan(TacFrontend frontend, String source, Path file) {
    TacFrontend.ParseResult result = frontend.parse(source, file, null);
    assertThat(result.problems()).as("the source must lower without a problem").isEmpty();

    TacProgram program = new TacProgram(file, "app");
    for (TacFunction function : result.functions()) {
      program.addFunction(function);
    }

    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new CommandInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> findings = new ArrayList<>();
    for (Finding finding : reporter.all()) {
      if (finding.ruleId().startsWith("dfa-taint-")) {
        findings.add(finding);
      }
    }
    return findings;
  }

  /** Whether a command finding names the given callee. */
  private static boolean hasCommandFinding(List<Finding> findings, String callee) {
    for (Finding finding : findings) {
      if (!COMMAND_RULE.equals(finding.ruleId())) {
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
