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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The Go frontend: a switch with no tag is a file, not a loss.
 *
 * <p><b>What used to happen.</b> A switch with no tag -- {@code switch { case cond: }} -- used to
 * take the whole file down: the Go sidecar died on it with a nil pointer dereference, and the
 * file produced no TAC at all. Not a degraded TAC: none. Every function in that file, and every
 * finding in it, was gone.
 *
 * <p><b>Why the loss was file-level.</b> The sidecar's answer to one file is one answer. A
 * construct it cannot lower is a problem in that function, and the other functions of the file are
 * still there. A crash is not: it ends the whole request.
 *
 * <p><b>Why this test is a frontend test.</b> The sidecar's own tests cover the lowering of the
 * switch itself; this test covers the other half of the trip -- that a file with such a switch
 * comes back through the wire with its functions in it.
 */
@DisplayName("Go: a switch with no tag does not take the file down")
class GoSwitchLoweringTest {

  private static final String SOURCE =
      """
      package demo

      func classify(cmd string) string {
      	switch {
      	case cmd != "":
      		return cmd
      	default:
      		return "empty"
      	}
      }

      func ordinary(value int) int {
      	return value + 1
      }
      """;

  @Test
  @DisplayName("a file with a switch with no tag is translated")
  void aFileWithASwitchWithNoTagIsTranslated(@TempDir Path directory) throws Exception {
    List<TacFunction> functions = translate(directory, SOURCE);

    assertThat(functions)
        .as(
            "a switch with no tag used to take the WHOLE file down: the sidecar died on it, so"
                + " the file produced no TAC at all -- and the loss was file-level, not"
                + " function-level")
        .extracting(TacFunction::name)
        .contains("classify", "ordinary");

    TacFunction classify = functionNamed(functions, "classify");
    TacInstruction branch = branchOf(classify);
    TacInstruction comparison = comparisonOf(classify);

    assertThat(branch)
        .as("the case's condition is the branch's condition: the switch has no tag to compare it with")
        .isNotNull();
    assertThat(comparison).as("the case's own comparison is in the instruction stream").isNotNull();
    assertThat(comparison.binop)
        .as("the case's condition is its own comparison, not a comparison against a tag")
        .isEqualTo(BinaryOperator.NE);
    assertThat(comparison.dest)
        .as("the comparison has a result to branch on")
        .isInstanceOf(TacOperand.TacVar.class);
    assertThat(branch.lhs1)
        .as("the branch has a condition")
        .isInstanceOf(TacOperand.TacVar.class);
    assertThat(((TacOperand.TacVar) branch.lhs1).name())
        .as("the branch is on the comparison's own result, and the wire has to carry that name")
        .isEqualTo(((TacOperand.TacVar) comparison.dest).name());
  }

  /** The function of the given name. */
  private static TacFunction functionNamed(List<TacFunction> functions, String name) {
    for (TacFunction function : functions) {
      if (name.equals(function.name())) {
        return function;
      }
    }
    throw new AssertionError("the translation has no function named " + name);
  }

  /** The branch instruction of a function, or null when it has none. */
  private static TacInstruction branchOf(TacFunction function) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.BRANCH) {
        return instruction;
      }
    }
    return null;
  }

  /** The comparison instruction of a function, or null when it has none. */
  private static TacInstruction comparisonOf(TacFunction function) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.BINOP) {
        return instruction;
      }
    }
    return null;
  }

  /** Translates the Go module and returns its functions. */
  private static List<TacFunction> translate(Path directory, String source) throws Exception {
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
      return frontend.parse(source, file).functions();
    }
  }
}
