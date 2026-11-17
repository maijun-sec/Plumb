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
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * A range over a channel must keep its BODY.
 *
 * <p><b>Why this is a test of its own.</b> A range over a channel used to be lowered to a single
 * receive and nothing else -- no loop, no body. Every statement inside the loop was therefore
 * missing from the TAC, including a call to a sink. The scan reported success and the finding was
 * absent, so nothing but a test that looks for the body's call can catch it.
 *
 * <p><b>What the shape must be.</b> The receive happens at the HEAD of the loop, the body runs
 * under its own label, and the loop has a way out: a channel that is closed ends the range, and
 * that is the false edge of the receive's flag.
 */
@DisplayName("Go: a range over a channel")
class GoChannelRangeTest {

  private static final String SOURCE =
      """
      package demo

      func consume(ch chan int) {
      	for value := range ch {
      		sink(value)
      	}
      }

      func sink(value int) {
      }
      """;

  @Test
  @DisplayName("the body of a channel range is in the instruction stream")
  void theBodyOfAChannelRangeIsInTheInstructionStream(@TempDir Path directory) throws Exception {
    TacFunction function = translate(directory, SOURCE, "consume");

    assertThat(callTo(function, "example.com/demo.sink/1"))
        .as(
            "the loop body must be translated: a dropped body is a call to a sink that is "
                + "never seen, so the finding is absent while the scan reports success")
        .isNotNull();
    assertThat(callTo(function, "<go>.chanRecv/1"))
        .as("the loop must receive from the channel")
        .isNotNull();
  }

  @Test
  @DisplayName("a channel range has an edge that ends the loop")
  void aChannelRangeHasAnEdgeThatEndsTheLoop(@TempDir Path directory) throws Exception {
    TacFunction function = translate(directory, SOURCE, "consume");

    boolean hasBranch = false;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.BRANCH
          && instruction.trueLabel != null
          && instruction.falseLabel != null) {
        hasBranch = true;
      }
    }
    assertThat(hasBranch)
        .as(
            "the loop must have a condition: a range over a channel ends when the channel is "
                + "closed, and that is the false edge of the receive's flag")
        .isTrue();
  }

  /** Translates the Go module and returns one of its functions. */
  private static TacFunction translate(Path directory, String source, String name)
      throws Exception {
    Path module = directory.resolve("demo");
    Files.createDirectories(module);
    Files.writeString(module.resolve("go.mod"), "module example.com/demo\n\ngo 1.21\n");
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
      for (TacFunction function : frontend.parse(source, file).functions()) {
        if (name.equals(function.name())) {
          return function;
        }
      }
    }
    throw new AssertionError("the module declares no function named " + name);
  }

  /** The instruction that calls the given callee, or null. */
  private static TacInstruction callTo(TacFunction function, String calleeSig) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CALL && calleeSig.equals(instruction.calleeSig)) {
        return instruction;
      }
    }
    return null;
  }
}
