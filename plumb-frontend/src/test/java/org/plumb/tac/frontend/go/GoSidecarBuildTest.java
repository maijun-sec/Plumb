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
package org.plumb.tac.frontend.go;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Go sidecar is measured by two things, and neither of them was in the build.
 *
 * <p><b>What used to happen.</b> The Go sidecar has its own test suite: twelve {@code _test.go}
 * files that lower Go constructs and assert what the lowering looks like. Nothing in the build ever
 * ran them. A grep for {@code go test} over the build files finds it in exactly two places, and
 * both of them are scripts a developer runs by hand: the build script and the smoketest. So the Go
 * half of the front end had <em>no</em> coverage from the regression: a change to {@code
 * translator.go} was measured by seven Java integration tests, and by nothing else.
 *
 * <p><b>And those seven measured a binary.</b> The sidecar is a native program, so it is built by
 * its own toolchain rather than by Maven, and the result is not in version control. The Java side
 * runs whatever binary it finds. That is fine as long as the binary is current, and nothing checked
 * that it was: the file that holds the warning in the ledger is a note from a round in November,
 * and a note is not a mechanism. On a machine where the build output is stale -- the output
 * directory is not in version control either, so it survives whatever the developer last built
 * there -- the Java tests measure the old code and report green.
 *
 * <p><b>Why the two checks are here and not somewhere else.</b> The first one belongs in the build
 * because it is the build's own business: the regression has to say what it measured, and a front
 * end that is half of the front end is not measured. The second one is a check on the instrument
 * rather than on the program: it asks whether the thing the Java side runs was built from the
 * source that is in the tree.
 *
 * <p><b>What a skip means here.</b> Both checks need something this machine may not have: the first
 * needs a Go toolchain on the PATH, the second needs a built binary. When it is missing, the check
 * is skipped -- and a skip is not a pass. The build says so in its skip count, and the count is
 * part of what the regression compares.
 */
@DisplayName("Go: the sidecar's own tests are in the build, and its binary is not stale")
class GoSidecarBuildTest {

  @Test
  @DisplayName("the Go sidecar's own test suite passes")
  void theGoSidecarsOwnTestSuitePasses() throws Exception {
    Path toolchain = GoProjectDiscovery.toolchainRootOf();
    assumeTrue(
        toolchain != null,
        "the Go toolchain is not on the PATH, so the Go sidecar's own tests cannot run: the Go"
            + " half of the front end is NOT measured by this build");
    Path source = sidecarSourceDirectory();
    assumeTrue(source != null, "the Go sidecar's source directory was not found");

    Path output = Files.createTempFile("plumb-go-sidecar-test", ".log");
    try {
      int exit = runGoTest(toolchain, source, output);
      String log = Files.readString(output, StandardCharsets.UTF_8);
      assertThat(exit)
          .as(
              "the Go sidecar's own test suite failed, and nothing in the build ran it before:"
                  + " the Go half of the front end is measured only by this suite. The output"
                  + " was:%n%s",
              log)
          .isZero();
      assertThat(log)
          .as("the Go sidecar's own suite reported something other than a passing package")
          .contains("ok");
    } finally {
      Files.deleteIfExists(output);
    }
  }

  @Test
  @DisplayName("the sidecar binary is not older than the source it was built from")
  void theSidecarBinaryIsNotOlderThanItsSource() {
    Path executable = GoSidecarClient.locateExecutable(null);
    assumeTrue(
        executable != null,
        "no sidecar binary was found, so the Java side's Go tests skip: build it with"
            + " plumb-frontend/go-sidecar/build.ps1");
    Path source = sidecarSourceDirectory();
    assumeTrue(source != null, "the Go sidecar's source directory was not found");
    assumeTrue(
        executable.startsWith(source),
        "the sidecar in use is not this repository's build output, so its age says nothing about"
            + " the source in this tree");

    Path newest = newestSourceFile(source);
    assertThat(newest)
        .as("the Go sidecar's source directory holds no .go file, so there is nothing to compare")
        .isNotNull();
    long sourceTime = newest.toFile().lastModified();
    long binaryTime = executable.toFile().lastModified();
    assertThat(binaryTime)
        .as(
            "the sidecar binary is OLDER than the source it was built from: the Java side's Go"
                + " tests would measure the old code and report green, which is a green lie."
                + " Rebuild it with plumb-frontend/go-sidecar/build.ps1")
        .isGreaterThanOrEqualTo(sourceTime);
  }

  /**
   * Runs {@code go test ./...} in the sidecar's source directory.
   *
   * <p>The build cache and the module cache go OUTSIDE the repository: a cache inside the working
   * tree is a large pile of generated files that has no business in version control.
   *
   * @param toolchain the Go toolchain's root
   * @param source the sidecar's source directory
   * @param output the file the combined output goes to
   * @return the process's exit code
   */
  private static int runGoTest(Path toolchain, Path source, Path output)
      throws IOException, InterruptedException {
    Path cache = Path.of(System.getProperty("java.io.tmpdir")).resolve("plumb-go-sidecar-build");
    Path go = toolchain.resolve("bin").resolve(isWindows() ? "go.exe" : "go");
    ProcessBuilder builder =
        new ProcessBuilder(go.toString(), "test", "./...")
            .directory(source.toFile())
            .redirectOutput(output.toFile())
            .redirectErrorStream(true);
    builder.environment().put("GOROOT", toolchain.toString());
    builder.environment().put("GOCACHE", cache.resolve("cache").toString());
    builder.environment().put("GOMODCACHE", cache.resolve("modules").toString());
    builder.environment().put("GOFLAGS", "-mod=mod");
    builder.environment().put("GOPROXY", "off");
    Process process = builder.start();
    return process.waitFor();
  }

  /** The Go sidecar's source directory, or null when it cannot be found. */
  private static Path sidecarSourceDirectory() {
    Path current = Path.of("").toAbsolutePath().normalize();
    for (int i = 0; i < 8 && current != null; i++) {
      Path candidate = current.resolve("go-sidecar");
      if (Files.isRegularFile(candidate.resolve("go.mod"))) {
        return candidate;
      }
      current = current.getParent();
    }
    return null;
  }

  /** The most recently changed Go source file of the sidecar, or null when there is none. */
  private static Path newestSourceFile(Path source) {
    try (Stream<Path> files = Files.walk(source)) {
      return files
          .filter(Files::isRegularFile)
          .filter(GoSidecarBuildTest::isCompiledInput)
          .max(Comparator.comparingLong(file -> file.toFile().lastModified()))
          .orElse(null);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * Whether a file is one the sidecar binary is built from.
   *
   * <p>The module file counts: a change to the declared Go version, or to the dependencies, changes
   * the binary, so a binary older than {@code go.mod} is exactly as stale as one older than a
   * source file. The test files do not count -- they are not compiled into the binary.
   *
   * @param file the file
   * @return whether the binary is built from it
   */
  private static boolean isCompiledInput(Path file) {
    String name = file.getFileName().toString();
    return name.equals("go.mod") || (name.endsWith(".go") && !name.endsWith("_test.go"));
  }

  /** Whether this JVM runs on Windows. */
  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }
}
