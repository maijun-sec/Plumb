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
package org.plumb.tac.frontend.php;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.TacDrop;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * The PHP frontend: a bare constant, and a capture by reference.
 *
 * <h2>Why a bare constant needs its own test</h2>
 *
 * <p>A constant is not an identifier. {@code PHP_EOL} and {@code DEBUG} are names the language
 * resolves, and the parser gives them a node of their own -- so the expression lowering had no
 * branch for them, and the fallback THREW.
 *
 * <p>A throw inside a statement's lowering ends that statement. So {@code if (DEBUG) { system($cmd);
 * }} was lowered to nothing: not the branch, not the sink, not even an honest report of what was
 * lost. The function around it reported a loss, which is true, but the sink that the loss hid was
 * simply gone -- and a scan that finds nothing looks exactly like a scan of a program that has
 * nothing to find.
 *
 * <h2>Why a capture by reference needs its own test</h2>
 *
 * <p>The parser writes {@code byref} on the entry of a {@code use} list. The check read {@code
 * byRef}, which the parser never writes, so the test was always false and the capture by reference
 * was silently treated as a capture by value.
 *
 * <p>That is the worst kind of wrong: the TAC carried the captured value, the reader believed the
 * copy was the whole story, and the difference between a copy and a shared location -- which is
 * exactly what the language's by-reference semantics turn on -- was gone without a word.
 *
 * <p>The assertions are about SHAPES: the statement's code is in the TAC, and the loss is recorded
 * on the function that has it. They do not pin where the instructions are.
 */
@DisplayName("PHP: a bare constant, and a capture by reference")
class PhpBareConstantTest {

  /** A statement whose condition is a bare constant, and whose body reaches a sink. */
  private static final String BARE_CONSTANT =
      """
      <?php
      function handle(string $cmd): void {
          if (DEBUG) {
              system($cmd);
          }
      }
      """;

  /** A closure that captures a variable by reference. */
  private static final String BY_REFERENCE =
      """
      <?php
      function handle(string $cmd): void {
          $box = $cmd;
          $runner = function () use (&$box) {
              return $box;
          };
      }
      """;

  @Test
  @DisplayName("a bare constant does not cost the statement its code")
  void aBareConstantDoesNotCostTheStatementItsCode(@TempDir Path directory) throws IOException {
    TacFrontend.ParseResult result = translate(BARE_CONSTANT, directory, "app.php");

    assertThat(result.problems())
        .as("a constant is a name the language resolves, not an unknown expression")
        .isEmpty();

    TacFunction handle = find(result, "handle");
    assertThat(handle).as("the function must be in the stream").isNotNull();
    assertThat(handle.instructions())
        .as("the condition is a value, so the statement branches")
        .anyMatch(instruction -> instruction.kind == TacInstruction.Kind.BRANCH);
    assertThat(handle.instructions().stream().map(instruction -> instruction.calleeSig))
        .as("the sink inside the statement must be in the stream")
        .anyMatch(signature -> signature != null && signature.contains("system"));
  }

  @Test
  @DisplayName("a capture by reference is recorded")
  void aCaptureByReferenceIsRecorded(@TempDir Path directory) throws IOException {
    TacFrontend.ParseResult result = translate(BY_REFERENCE, directory, "app.php");

    TacFunction handle = find(result, "handle");
    assertThat(handle).as("the function must be in the stream").isNotNull();
    assertThat(handle.drops())
        .as("a capture by reference is not a capture by value, and the difference is a loss")
        .extracting(TacDrop::construct)
        .contains("op.assign.byref");
    assertThat(handle.irPrecision())
        .as("a recorded loss must not be reported as full precision")
        .isNotEqualTo(IrPrecision.SOURCE_FULL);
  }

  // ---- the harness ---------------------------------------------------------------------------

  /** Parse one file through the real sidecar. */
  private static TacFrontend.ParseResult translate(String source, Path directory, String fileName)
      throws IOException {
    Files.writeString(directory.resolve("composer.json"), "{\"name\": \"app/app\"}\n");
    Path file = directory.resolve(fileName);
    Files.writeString(file, source);

    try (PhpTacFrontend frontend = new PhpTacFrontend()) {
      assumeTrue(
          sidecarAvailable(),
          "the PHP front end needs Node and the sidecar's own parser dependency: run npm ci in"
              + " plumb-frontend/php-sidecar");

      return frontend.parse(source, file, null);
    }
  }

  /** The function with the given name, or null. */
  private static TacFunction find(TacFrontend.ParseResult result, String name) {
    for (TacFunction function : result.functions()) {
      if (name.equals(function.name())) {
        return function;
      }
    }
    return null;
  }

  /**
   * Whether the sidecar can run here.
   *
   * <p>Three things have to be there: Node, the sidecar's script, and the parser the script
   * imports. The third is the one that is easy to miss, and its absence would make the sidecar die
   * on its first request -- which would look like a broken product rather than a missing
   * dependency.
   */
  private static boolean sidecarAvailable() {
    if (PhpSidecarClient.locateNode() == null) {
      return false;
    }
    Path script = PhpSidecarClient.locateScript(null);
    if (script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null && Files.isDirectory(root.resolve("node_modules").resolve("php-parser"));
  }
}
