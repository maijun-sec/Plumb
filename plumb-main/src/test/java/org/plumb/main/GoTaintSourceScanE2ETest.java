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
import org.plumb.tac.frontend.go.GoProjectDiscovery;

/**
 * The end-to-end proof that a Go run has a SOURCE, and that the Go containers carry it.
 *
 * <p><b>Why this is its own test.</b> A sink catalogue without a source family produces a scan that
 * looks perfect and is empty: the value reaching the sink is untainted because nothing ever tainted
 * it, so the checker has nothing to report and the scan reports success. "The code is clean" and
 * "the engine never saw untrusted data" are the same output.
 *
 * <p>The Go scope had a sink catalogue but almost no source family, and the source it had was never
 * measured against the shape the Go front end emits. So this pins three things shut:
 *
 * <ol>
 *   <li>the ENVIRONMENT is a Go source, and the value reaches the command sink;
 *   <li>the value survives a MAP write and a MAP read -- the map's state, not an expression edge;
 *   <li>the value survives a CHANNEL send and a receive.
 * </ol>
 *
 * <p><b>Why the negative case matters.</b> Without it, a front end that marked EVERY value as
 * tainted would pass the positive cases. The negative case reads nothing: a literal reaches the
 * sink, and a literal is clean, so there must be no finding.
 *
 * <p><b>Why the test needs a toolchain.</b> The Go front end resolves the project's types with the
 * Go toolchain's own source, so a machine without one cannot produce a Go TAC at all. The test then
 * has nothing to assert, and it SKIPS rather than failing: a missing toolchain is a missing
 * environment, not a broken product. The front end's own shape -- the catalogue, the argument
 * index, the container propagation -- is pinned by the front end's tests, which need no toolchain.
 */
class GoTaintSourceScanE2ETest {

  /** The command-injection sink the Go table names. */
  private static final String COMMAND_CWE = "CWE-78";

  /** A value read from the environment, reaching the command sink. */
  private static final String FROM_ENVIRONMENT =
      """
      package main

      import (
      \t"os"
      \t"os/exec"
      )

      func main() {
      \tname := os.Getenv("TOOL")
      \tcmd := exec.Command(name, "--version")
      \t_ = cmd.Run()
      }
      """;

  /** The same value, carried through a map and then into the command sink. */
  private static final String THROUGH_A_MAP =
      """
      package main

      import (
      \t"os"
      \t"os/exec"
      )

      func main() {
      \tinputs := map[string]string{}
      \tinputs["tool"] = os.Getenv("TOOL")
      \tname := inputs["tool"]
      \tcmd := exec.Command(name, "--version")
      \t_ = cmd.Run()
      }
      """;

  /** The same value, carried through a channel and then into the command sink. */
  private static final String THROUGH_A_CHANNEL =
      """
      package main

      import (
      \t"os"
      \t"os/exec"
      )

      func main() {
      \ttools := make(chan string, 1)
      \ttools <- os.Getenv("TOOL")
      \tname := <-tools
      \tcmd := exec.Command(name, "--version")
      \t_ = cmd.Run()
      }
      """;

  /** A value that comes from nowhere: a literal is clean. */
  private static final String FROM_A_LITERAL =
      """
      package main

      import (
      \t"os/exec"
      )

      func main() {
      \tname := "not-a-command"
      \tcmd := exec.Command(name, "--version")
      \t_ = cmd.Run()
      }
      """;

  /**
   * Runs a scan over one Go module, with everything but the taint checker disabled.
   *
   * @param source the Go source
   * @return the NDJSON the scan produced
   */
  private static String scan(String source) throws Exception {
    Path dir = Files.createTempDirectory("plumb-go-source-e2e");
    Path module = dir.resolve("demo");
    Files.createDirectories(module);
    // A Go project is a MODULE: the discovery walks up to the nearest go.mod and
    // takes every package inside it.
    Files.writeString(module.resolve("go.mod"), "module example.com/demo\n\ngo 1.21\n");
    Path input = module.resolve("main.go");
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
  @DisplayName("a Go environment read reaches the command sink")
  void aGoEnvironmentReadReachesTheCommandSink() throws Exception {
    assumeTrue(
        GoProjectDiscovery.toolchainRootOf() != null,
        "the Go front end needs a Go toolchain to resolve the project's types: install Go and put "
            + "`go` on the PATH");

    String ndjson = scan(FROM_ENVIRONMENT);

    assertThat(ndjson)
        .as("the Go file must reach the taint engine and be reported as CWE-78")
        .contains(COMMAND_CWE);
  }

  @Test
  @DisplayName("a Go value survives a map write and a map read")
  void aGoValueSurvivesAMap() throws Exception {
    assumeTrue(
        GoProjectDiscovery.toolchainRootOf() != null,
        "the Go front end needs a Go toolchain to resolve the project's types: install Go and put "
            + "`go` on the PATH");

    String ndjson = scan(THROUGH_A_MAP);

    assertThat(ndjson)
        .as("the map carries the taint of what was written into it, in both directions")
        .contains(COMMAND_CWE);
  }

  @Test
  @DisplayName("a Go value survives a channel send and a receive")
  void aGoValueSurvivesAChannel() throws Exception {
    assumeTrue(
        GoProjectDiscovery.toolchainRootOf() != null,
        "the Go front end needs a Go toolchain to resolve the project's types: install Go and put "
            + "`go` on the PATH");

    String ndjson = scan(THROUGH_A_CHANNEL);

    assertThat(ndjson)
        .as("the channel carries the taint of what was sent into it, in both directions")
        .contains(COMMAND_CWE);
  }

  @Test
  @DisplayName("a Go literal is clean: nothing is reported")
  void aGoLiteralIsClean() throws Exception {
    assumeTrue(
        GoProjectDiscovery.toolchainRootOf() != null,
        "the Go front end needs a Go toolchain to resolve the project's types: install Go and put "
            + "`go` on the PATH");

    String ndjson = scan(FROM_A_LITERAL);

    assertThat(ndjson)
        .as("a literal is clean, so the command sink must not be reported")
        .doesNotContain(COMMAND_CWE);
  }

  /**
   * Deletes a directory tree.
   *
   * @param dir the root
   */
  private static void deleteRecursively(Path dir) throws Exception {
    if (!Files.exists(dir)) {
      return;
    }
    try (var stream = Files.walk(dir)) {
      stream
          .sorted((a, b) -> b.getNameCount() - a.getNameCount())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (Exception ignored) {
                  // A file that cannot be deleted must not fail the test: the
                  // assertions have already run.
                }
              });
    }
  }
}
