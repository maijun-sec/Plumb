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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * A Go call through an INTERFACE must name the type that may receive it.
 *
 * <h2>Why the interface's own name is not enough</h2>
 *
 * <p>Go's interface satisfaction is implicit: nothing in the source says which type a call through
 * an interface dispatches to, and the type checker checks it rather than the programmer stating it.
 * So the relation has to be worked out from the method sets -- and when it is not, the call names
 * the INTERFACE, and a program has no function for an interface.
 *
 * <p>That is the shape this test pins: the call carries the interface's key AND the key of every
 * type that satisfies the interface.
 *
 * <h2>Why this is a frontend test and not an end-to-end one</h2>
 *
 * <p>An end-to-end test cannot tell the two apart. The engine has a conservative fallback that
 * hands a callee's taint to the caller's result whenever the callee has a body, so a scan reports
 * the finding whether or not the dispatch was resolved -- the finding is there for a different
 * reason, and the test would pass for the wrong one. The TAC is where the relation is actually
 * carried, so that is where it is measured.
 */
class GoInterfaceDispatchTest {

  /** An interface, and the type that satisfies it. */
  private static final String SOURCE =
      """
      package demo

      type Runner interface {
      	Run(command string) string
      }

      type shellRunner struct{}

      func (s *shellRunner) Run(command string) string {
      	return "sh -c " + command
      }

      func use(runner Runner) string {
      	return runner.Run("echo")
      }
      """;

  /**
   * A type with a method of the same NAME and the same arity, and a different SIGNATURE.
   *
   * <p>It does not satisfy the interface: Go compares signatures, not names. A type whose method
   * took the same parameter would satisfy the interface, and naming it would be right.
   */
  private static final String UNRELATED =
      """
      package demo

      type formatter struct{}

      func (f *formatter) Run(code int) string {
      	return "clean"
      }

      type shellRunner struct{}

      func (s *shellRunner) Run(command string) string {
      	return "sh -c " + command
      }

      func use(runner Runner) string {
      	return runner.Run("echo")
      }
      """;

  /** The interface the two sources share. */
  private static final String INTERFACE =
      """
      package demo

      type Runner interface {
      	Run(command string) string
      }
      """;

  @Test
  @DisplayName("a call through an interface names the type that satisfies it")
  void aCallThroughAnInterfaceNamesTheTypeThatSatisfiesIt(@TempDir Path directory)
      throws Exception {
    List<TacFunction> functions = translate(directory, SOURCE);

    List<String> callees = calleesOf(functions, "use");
    assertThat(callees)
        .as("the calls the function makes: %s", callees)
        .contains("example.com/demo.Runner.Run/1");
    assertThat(callees)
        .as("the call must also name the type that satisfies the interface: %s", callees)
        .contains("example.com/demo.shellRunner.Run/1");
  }

  @Test
  @DisplayName("a type that does not satisfy the interface is not named")
  void aTypeThatDoesNotSatisfyTheInterfaceIsNotNamed(@TempDir Path directory) throws Exception {
    List<TacFunction> functions = translate(directory, UNRELATED);

    List<String> callees = calleesOf(functions, "use");
    assertThat(callees)
        .as("the calls the function makes: %s", callees)
        .contains("example.com/demo.shellRunner.Run/1");
    assertThat(callees)
        .as(
            "a method that only shares the NAME and the arity is not an implementation, and the"
                + " relation comes from the method sets rather than from the name: %s",
            callees)
        .doesNotContain("example.com/demo.formatter.Run/1");
  }

  /**
   * Translates a Go module and returns the functions it declares.
   *
   * @param directory the temporary directory the module is written to
   * @param source the main file's source
   * @return the functions
   */
  private static List<TacFunction> translate(Path directory, String source) throws Exception {
    Path module = directory.resolve("demo");
    Files.createDirectories(module);
    Files.writeString(module.resolve("go.mod"), "module example.com/demo\n\ngo 1.21\n");
    Path file = module.resolve("main.go");
    Files.writeString(file, source);
    // The interface lives in its own file, so the two sources above can be
    // written one after the other without one replacing the other.
    if (!source.contains("type Runner interface")) {
      Files.writeString(module.resolve("runner.go"), INTERFACE);
    }
    try (GoTacFrontend frontend = new GoTacFrontend()) {
      assumeTrue(
          frontend.isToolchainAvailable(),
          "the Go front end needs the sidecar executable to reach the project's types: build it"
              + " with plumb-frontend/go-sidecar/build.ps1");
      assumeTrue(
          GoProjectDiscovery.toolchainRootOf() != null,
          "the Go front end needs a Go toolchain to resolve the project's types: install Go"
              + " (https://go.dev/dl) and put `go` on the PATH");
      return frontend.parse(source, file).functions();
    }
  }

  /**
   * The callee keys the function's calls carry.
   *
   * @param functions the translated functions
   * @param name the function's name
   * @return the callee keys
   */
  private static List<String> calleesOf(List<TacFunction> functions, String name) {
    List<String> callees = new ArrayList<>();
    for (TacFunction function : functions) {
      if (!name.equals(function.name())) continue;
      for (TacInstruction instruction : function.instructions()) {
        if (instruction.kind == TacInstruction.Kind.CALL && instruction.calleeSig != null) {
          callees.add(instruction.calleeSig);
        }
      }
    }
    return callees;
  }
}
