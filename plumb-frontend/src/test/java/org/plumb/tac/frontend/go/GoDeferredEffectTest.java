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
 * The calls that do not run where they are written must say so in the IR.
 *
 * <p><b>Why the marker has to survive the trip.</b> A {@code defer} runs the call when the function
 * returns; a {@code go} statement starts a goroutine. Both are emitted as ordinary CALL
 * instructions, so without a marker the engine cannot tell either of them from a plain call. A
 * checker then cannot see that a deferred release inside a loop is not released until the function
 * exits -- and the rule that would say so has nothing to look at.
 *
 * <p><b>Why this is a frontend test.</b> The marker is set where the language's primitive is known:
 * the sidecar that lowers Go. The sidecar's own tests cover the lowering; this test covers the
 * other half of the trip -- that the marker survives the wire and arrives in the TAC.
 */
@DisplayName("Go: the calls that do not run where they are written are marked")
class GoDeferredEffectTest {

  private static final String SOURCE =
      """
      package demo

      type File struct{}

      func (f *File) Close() error {
      	return nil
      }

      func work() {
      }

      func deferredRelease(file *File) {
      	defer file.Close()
      }

      func startsAGoroutine() {
      	go work()
      }

      func ordinaryCall(file *File) {
      	file.Close()
      }
      """;

  @Test
  @DisplayName("a deferred call is marked as deferred")
  void aDeferredCallIsMarkedAsDeferred(@TempDir Path directory) throws Exception {
    TacFunction function = translate(directory, SOURCE, "deferredRelease");

    TacInstruction call = callTo(function, "example.com/demo.File.Close/0");
    assertThat(call).as("the deferred call must be in the instruction stream").isNotNull();
    assertThat(call.isDeferred()).as("the call runs at the function's exit").isTrue();
    assertThat(call.isConcurrent()).as("a defer is not concurrency").isFalse();
  }

  @Test
  @DisplayName("a goroutine's call is marked as concurrent")
  void aGoroutinesCallIsMarkedAsConcurrent(@TempDir Path directory) throws Exception {
    TacFunction function = translate(directory, SOURCE, "startsAGoroutine");

    TacInstruction call = callTo(function, "example.com/demo.work/0");
    assertThat(call).as("the goroutine's call must be in the instruction stream").isNotNull();
    assertThat(call.isConcurrent()).as("the call runs on another goroutine").isTrue();
    assertThat(call.isDeferred()).as("a goroutine is not a defer").isFalse();
  }

  @Test
  @DisplayName("an ordinary call carries no marker")
  void anOrdinaryCallCarriesNoMarker(@TempDir Path directory) throws Exception {
    TacFunction function = translate(directory, SOURCE, "ordinaryCall");

    TacInstruction call = callTo(function, "example.com/demo.File.Close/0");
    assertThat(call).as("the call must be in the instruction stream").isNotNull();
    assertThat(call.isDeferred()).as("an ordinary call runs here, in this order").isFalse();
    assertThat(call.isConcurrent()).as("an ordinary call runs here, in this order").isFalse();
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
