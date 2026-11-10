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
package org.plumb.dfa.checkers.controlflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.frontend.go.GoTacFrontend;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;

/**
 * The rule, on real Go code, all the way from the source to the finding.
 *
 * <p><b>Why this test exists when the checker has its own.</b> The checker's test builds the TAC by
 * hand, so it measures the RULE. It cannot measure the chain: that the Go front end lowers a {@code
 * defer} into a call the engine can see, that the deferred call carries the marker, and that the
 * marker survives the wire. A rule that is correct on a hand-built TAC and silent on real Go code
 * is a rule that does not ship.
 *
 * <p><b>Why it skips.</b> The Go front end needs the sidecar executable and a Go toolchain to
 * resolve the project's types. Without them there is no translation to check, so the test SKIPS
 * rather than failing.
 */
@DisplayName("CWE-772: the rule fires on real Go code")
class GoDeferredReleaseEndToEndTest {

  private static final String RULE_ID = "plumb.dfa-controlflow-deferred-release-in-loop";

  /** A defer inside a loop: every iteration holds the file until the function returns. */
  private static final String DEFER_IN_LOOP =
      """
      package demo

      import "os"

      func use(names []string) {
      	for _, name := range names {
      		f, err := os.Open(name)
      		if err != nil {
      			return
      		}
      		defer f.Close()
      	}
      }
      """;

  /**
   * The same shape with the defer outside the loop: the file is released at the exit, as intended.
   */
  private static final String DEFER_OUTSIDE_LOOP =
      """
      package demo

      import "os"

      func use(names []string) {
      	for _, name := range names {
      		f, err := os.Open(name)
      		if err != nil {
      			return
      		}
      		process(f)
      	}
      	f, err := os.Open("last")
      	if err != nil {
      		return
      	}
      	defer f.Close()
      }

      func process(f *os.File) {
      }
      """;

  @Test
  @DisplayName("a defer inside a loop is reported")
  void aDeferInsideALoopIsReported(@TempDir Path directory) throws Exception {
    List<Finding> findings = runOn(directory, "inloop", DEFER_IN_LOOP);

    assertThat(findings)
        .as("the release runs when the function returns, so every iteration holds the file")
        .hasSize(1);
    assertThat(findings.get(0).cwe()).isEqualTo("CWE-772");
  }

  @Test
  @DisplayName("a defer outside a loop is not reported")
  void aDeferOutsideALoopIsNotReported(@TempDir Path directory) throws Exception {
    List<Finding> findings = runOn(directory, "outside", DEFER_OUTSIDE_LOOP);

    assertThat(findings)
        .as("a defer outside a loop runs at the exit, which is what it is for")
        .isEmpty();
  }

  /** Translates the Go module and runs the checker over it. */
  private List<Finding> runOn(Path directory, String name, String source) throws Exception {
    Path module = directory.resolve("demo");
    Files.createDirectories(module);
    Files.writeString(module.resolve("go.mod"), "module example.com/demo\n\ngo 1.21\n");
    Path file = module.resolve("main.go");
    Files.writeString(file, source);

    TacProgram program = new TacProgram(file, "demo");
    try (GoTacFrontend frontend = new GoTacFrontend()) {
      assumeTrue(
          frontend.isToolchainAvailable(),
          "the Go front end needs the sidecar executable to reach the project's types");
      assumeTrue(
          goToolchainOnPath(),
          "the Go front end needs a Go toolchain to resolve the project's types");
      for (TacFunction function : frontend.parse(source, file).functions()) {
        program.addFunction(function);
      }
    }

    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new DeferredReleaseInLoopChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (RULE_ID.equals(f.ruleId())) {
        out.add(f);
      }
    }
    return out;
  }

  /** Whether a Go toolchain is reachable. */
  private static boolean goToolchainOnPath() {
    String path = System.getenv("PATH");
    if (path == null || path.isEmpty()) {
      return false;
    }
    for (String dir : path.split(java.io.File.pathSeparator)) {
      if (dir.isEmpty()) {
        continue;
      }
      if (Files.isRegularFile(Path.of(dir, "go.exe")) || Files.isRegularFile(Path.of(dir, "go"))) {
        return true;
      }
    }
    return false;
  }
}
