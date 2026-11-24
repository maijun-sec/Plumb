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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.frontend.go.GoProjectDiscovery;

/**
 * A Go function that returns two MEANINGFUL values hands both of them back.
 *
 * <h2>The shape this is about</h2>
 *
 * <p>Go is the one language here whose functions return several values: {@code func f() (int,
 * string)}. The caller binds each of them to a variable of its own -- {@code v, s := f()} -- so the
 * second value is as much a result as the first one.
 *
 * <p>The front end used to lower such a function to its FIRST result and call the rest a loss. The
 * loss was real in the report: a function that returns two meaningful values was counted as <b>not
 * fully understood</b>, because the TAC's RETURN was read as carrying one value.
 *
 * <p>And it cost the analysis more than a number: the callee's IR did not hold the second value at
 * all, so there was nothing for the summary to carry to the call site. The caller's variable for
 * that result was <b>a variable nothing writes</b>, and a variable nothing writes is
 * indistinguishable from a clean one. A value that reached the sink through the second result was
 * never reported.
 *
 * <h2>What the three tests pin</h2>
 *
 * <ul>
 *   <li>the taint in the second result reaches the sink -- the finding that was missed;
 *   <li>a CLEAN second result stays clean -- a binding that taints every result of a call would
 *       pass the first test and be a false positive on every Go program whose second value is a
 *       constant;
 *   <li>the coverage report counts such a function at full precision -- the number the first test
 *       does not see.
 * </ul>
 *
 * <h2>Why it walks the whole chain</h2>
 *
 * <p>The side car has to carry every result, the wire has to carry them, the front end has to read
 * them, the engine has to put them in the summary, and the call site has to bind them by index.
 * Every other test of this change pins one link. This one pins the chain: a link that is missing
 * anywhere shows up as a scan that reports nothing.
 */
@DisplayName("Scan: a Go function that returns two meaningful values hands both of them back")
class GoSecondResultScanE2ETest {

  /** The command-injection sink the Go table names. */
  private static final String COMMAND_CWE = "CWE-78";

  /** The source is the SECOND of the callee's two results. */
  private static final String SOURCE_IN_THE_SECOND_RESULT =
      """
      package demo

      import (
      \t"os"
      \t"os/exec"
      )

      func main() {
      \t_, name := pairWithSource()
      \tcmd := exec.Command(name, "--version")
      \t_ = cmd.Run()
      }

      func pairWithSource() (int, string) {
      \treturn 0, os.Getenv("TOOL")
      }
      """;

  /** The same shape, but the second result is a constant. */
  private static final String CLEAN_SECOND_RESULT =
      """
      package demo

      import (
      \t"os/exec"
      )

      func main() {
      \t_, name := cleanPair()
      \tcmd := exec.Command(name, "--version")
      \t_ = cmd.Run()
      }

      func cleanPair() (int, string) {
      \treturn 0, "clean"
      }
      """;

  @Test
  @DisplayName("a value that reaches the sink through the second of two results is reported")
  void aValueThatReachesTheSinkThroughTheSecondOfTwoResultsIsReported(@TempDir Path directory)
      throws Exception {
    Scan scan = scan(directory, SOURCE_IN_THE_SECOND_RESULT);

    assertThat(scan.findings)
        .as(
            "the callee's second result is bound to the caller's own variable, so a sink that"
                + " consumes that variable must see the taint. Before the fix the callee's IR held"
                + " only the first result, so the caller's variable was a variable nothing writes")
        .contains(COMMAND_CWE);
  }

  @Test
  @DisplayName("a clean second result stays clean")
  void aCleanSecondResultStaysClean(@TempDir Path directory) throws Exception {
    Scan scan = scan(directory, CLEAN_SECOND_RESULT);

    assertThat(scan.findings)
        .as(
            "the callee's second result is a constant, so nothing may reach the sink through it. A"
                + " binding that taints every result of a call would report this one")
        .doesNotContain(COMMAND_CWE);
  }

  @Test
  @DisplayName("a function that returns two meaningful results is counted at full precision")
  void aFunctionThatReturnsTwoMeaningfulResultsIsCountedAtFullPrecision(@TempDir Path directory)
      throws Exception {
    Scan scan = scan(directory, SOURCE_IN_THE_SECOND_RESULT);

    assertThat(scan.coverage)
        .as(
            "the lowering is faithful now -- every result is carried -- so the function is fully"
                + " understood and the coverage report has to say so")
        .contains("\"functionsAtFullPrecision\":2")
        .contains("\"functionsBelowFullPrecision\":0");
  }

  // ------------------------------------------------------------------ the harness

  /** What a scan produced: the findings it wrote and the coverage record beside them. */
  private record Scan(String findings, String coverage) {}

  /**
   * Scans a Go module the way a user scans it, with the taint checker on.
   *
   * @param directory the directory to scan in
   * @param source the Go source of the module
   * @return what the scan produced
   */
  private static Scan scan(Path directory, String source) throws Exception {
    Path module = directory.resolve("demo");
    Files.createDirectories(module);
    Files.writeString(module.resolve("go.mod"), "module example.com/demo\n\ngo 1.21\n");
    Path file = module.resolve("main.go");
    Files.writeString(file, source);

    assumeTrue(
        GoProjectDiscovery.toolchainRootOf() != null,
        "the Go front end needs a Go toolchain to resolve the project's types: install Go"
            + " (https://go.dev/dl) and put `go` on the PATH");

    Path outFile = directory.resolve("out.ndjson");
    ScanConfig cfg = new ScanConfig();
    cfg.inputs.add(file);
    cfg.outputPath = outFile;
    cfg.outputFormat = ScanConfig.OutputFormat.NDJSON;

    int rc = new ScanRunner(cfg).run();
    assertThat(rc).as("the scan must succeed").isEqualTo(0);

    Path coverageFile = directory.resolve("out.coverage.json");
    assertThat(coverageFile).as("the scan writes the coverage beside the findings").exists();
    String findings = Files.exists(outFile) ? Files.readString(outFile) : "";
    return new Scan(findings, Files.readString(coverageFile));
  }
}
