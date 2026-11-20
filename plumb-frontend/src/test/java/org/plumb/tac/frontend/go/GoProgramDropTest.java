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

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.TacDrop;
import org.plumb.tac.ir.TacFunction;

/**
 * The Go sidecar's program-level drops must reach the Java side.
 *
 * <h2>Why this is worth pinning</h2>
 *
 * <p>The Go sidecar records the losses that belong to the WHOLE PROGRAM in the response's top-level
 * array, beside the functions rather than inside one of them. The Java side read each function's
 * own drop array and stopped there, so the program's losses were thrown away on the way in. Nothing
 * said so: a loss dropped on the way in looks exactly like a loss that never happened.
 *
 * <p><b>Why this drives the real sidecar.</b> The point is the TRIP, and the trip has three links:
 * the sidecar has to record the loss, the wire has to carry it, and the Java side has to read it. A
 * test that only checked the mapping would still pass if the sidecar stopped recording the loss --
 * and that is exactly the failure this change is about. So the test writes a Go module that loses
 * something, translates it with the sidecar, and reads what came back.
 *
 * <p>The loss it uses is an embedded resource: the content of an embedded file is a build-time
 * fact, and the sidecar reads the source and not the build -- so the bytes are nowhere in the TAC.
 * It belongs to the program, because the directive sits on a package-level declaration and no
 * function owns the resource.
 */
@DisplayName("Go: the losses that belong to the whole program reach the Java side")
class GoProgramDropTest {

  private static final String SOURCE =
      """
      package demo

      import _ "embed"

      //go:embed greeting.txt
      var greeting string

      func work() {
      }
      """;

  @Test
  @DisplayName("an embedded resource's loss arrives as a program-level drop")
  void anEmbeddedResourcesLossArrivesAsAProgramLevelDrop(@TempDir Path directory) throws Exception {
    TacFrontend.ParseResult result = translate(directory, SOURCE);

    assertThat(result.programDrops())
        .as("the sidecar records the embedded content as a loss of the whole program")
        .extracting(TacDrop::construct)
        .contains("go.embed.value");
    assertThat(result.programDrops())
        .as("a drop that arrived in the program channel is a program-level drop")
        .allMatch(drop -> drop.scope() == TacDrop.Scope.PROGRAM);
  }

  @Test
  @DisplayName("a program-level loss is not charged to a function")
  void aProgramLevelLossIsNotChargedToAFunction(@TempDir Path directory) throws Exception {
    TacFrontend.ParseResult result = translate(directory, SOURCE);

    for (TacFunction function : result.functions()) {
      assertThat(function.drops())
          .as("the loss belongs to the program, so no function may carry it")
          .extracting(TacDrop::construct)
          .doesNotContain("go.embed.value");
    }
  }

  @Test
  @DisplayName("the project's own losses reach the file that is translated")
  void theProjectsOwnLossesReachTheFileThatIsTranslated(@TempDir Path directory) throws Exception {
    // The Go version is a fact about the PROJECT, so the sidecar records it when the
    // project is opened rather than when a file is translated. The loss still has to
    // reach the file's result: a fact that is recorded and then dropped on the way in
    // is a fact the report cannot mention.
    //
    // The module below says nothing about its Go version, so the version is unknown and
    // the loss is real.
    TacFrontend.ParseResult result = translate(directory, SOURCE, "module example.com/demo\n");

    assertThat(result.programDrops())
        .as("the unknown Go version is a loss of the whole project, and it must arrive")
        .extracting(TacDrop::construct)
        .contains("go.version");
  }

  /** Translates the Go module and returns the result. */
  private static TacFrontend.ParseResult translate(Path directory, String source) throws Exception {
    return translate(directory, source, "module example.com/demo\n\ngo 1.21\n");
  }

  /** Translates the Go module and returns the result. */
  private static TacFrontend.ParseResult translate(Path directory, String source, String goMod)
      throws Exception {
    Path module = directory.resolve("demo");
    Files.createDirectories(module);
    Files.writeString(module.resolve("go.mod"), goMod);
    Files.writeString(module.resolve("greeting.txt"), "hello\n");
    Path file = module.resolve("main.go");
    Files.writeString(file, source);
    try (GoTacFrontend frontend = new GoTacFrontend()) {
      assumeTrue(
          frontend.isToolchainAvailable(),
          "the Go front end needs the sidecar executable to reach the project's types: build it"
              + " with plumb-frontend/go-sidecar/build.ps1");
      assumeTrue(
          GoProjectDiscovery.toolchainRootOf() != null,
          "the Go front end needs a Go toolchain to resolve the project's types: install Go"
              + " (https://go.dev/dl) and put `go` on the PATH");
      return frontend.parse(source, file);
    }
  }
}
