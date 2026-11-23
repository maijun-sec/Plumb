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
import org.plumb.tac.ir.TacDrop;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The PHP frontend: a {@code throw} in an EXPRESSION position.
 *
 * <h2>What PHP 8 changed</h2>
 *
 * <p>{@code throw} used to be a statement. Since PHP 8 it is an expression too, so it appears
 * inside other expressions: {@code $x = throw new E();}, {@code f(throw new E());}, {@code $x ??
 * throw new E();}.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The expression switch had no case for it, so the whole expression fell through to the
 * fallback, which threw. The fallback's own error became a lowering PROBLEM and the scan went on --
 * but the THROW was never emitted, and neither was the CONSTRUCTION of the exception that the
 * {@code throw} names. So {@code throw new RuntimeException($userInput)} lost the tainted value
 * that rode into the exception, and the function lost the fact that it throws.
 *
 * <p>So the assertions are about the three facts a reader needs: the THROW is in the stream, the
 * exception's construction is in the stream, and the value that goes into the exception is an
 * argument of that construction.
 */
@DisplayName("PHP: a throw in an expression position")
class PhpThrowExpressionLoweringTest {

  /** A throw used as an expression: the value the exception carries matters. */
  private static final String EXPRESSION =
      String.join(
          "\n",
          "<?php",
          "namespace App;",
          "",
          "function handle(string $input): string {",
          "    $value = throw new \\RuntimeException($input);",
          "    return $value;",
          "}");

  /** The same shape as a statement: it has to keep working. */
  private static final String STATEMENT =
      String.join(
          "\n",
          "<?php",
          "namespace App;",
          "",
          "function handle(string $input): string {",
          "    throw new \\RuntimeException($input);",
          "}");

  @Test
  @DisplayName("the throw is in the stream")
  void theThrowIsInTheStream(@TempDir Path directory) throws IOException {
    String source = EXPRESSION;
    Files.writeString(directory.resolve("composer.json"), "{\"name\": \"app/app\"}\n");
    Path file = directory.resolve("app.php");
    Files.writeString(file, source);

    try (PhpTacFrontend frontend = new PhpTacFrontend()) {
      assumeTrue(
          sidecarAvailable(),
          "the PHP front end needs Node and the sidecar's own parser dependency: run npm ci in"
              + " plumb-frontend/php-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      TacFunction handle = find(result, "handle");
      assertThat(handle).as("the function must be in the stream").isNotNull();

      assertThat(handle.instructions())
          .as("the function ends in an exception, and the TAC has to say so")
          .anyMatch(instruction -> instruction.kind == TacInstruction.Kind.THROW);
    }
  }

  @Test
  @DisplayName("the exception's construction is in the stream")
  void theExceptionsConstructionIsInTheStream(@TempDir Path directory) throws IOException {
    String source = EXPRESSION;
    Files.writeString(directory.resolve("composer.json"), "{\"name\": \"app/app\"}\n");
    Path file = directory.resolve("app.php");
    Files.writeString(file, source);

    try (PhpTacFrontend frontend = new PhpTacFrontend()) {
      assumeTrue(
          sidecarAvailable(),
          "the PHP front end needs Node and the sidecar's own parser dependency: run npm ci in"
              + " plumb-frontend/php-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      TacFunction handle = find(result, "handle");
      assertThat(handle).as("the function must be in the stream").isNotNull();

      assertThat(handle.instructions())
          .as("the exception is constructed, and the construction is in the stream")
          .anyMatch(
              instruction ->
                  instruction.kind == TacInstruction.Kind.NEW
                      && instruction.calleeSig != null
                      && instruction.calleeSig.contains("RuntimeException.<init>/"));
    }
  }

  @Test
  @DisplayName("the value that goes into the exception is an argument of its construction")
  void theValueThatGoesIntoTheExceptionIsAnArgument(@TempDir Path directory) throws IOException {
    String source = EXPRESSION;
    Files.writeString(directory.resolve("composer.json"), "{\"name\": \"app/app\"}\n");
    Path file = directory.resolve("app.php");
    Files.writeString(file, source);

    try (PhpTacFrontend frontend = new PhpTacFrontend()) {
      assumeTrue(
          sidecarAvailable(),
          "the PHP front end needs Node and the sidecar's own parser dependency: run npm ci in"
              + " plumb-frontend/php-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      TacFunction handle = find(result, "handle");
      assertThat(handle).as("the function must be in the stream").isNotNull();

      TacInstruction construction =
          handle.instructions().stream()
              .filter(
                  instruction ->
                      instruction.kind == TacInstruction.Kind.NEW
                          && instruction.calleeSig != null
                          && instruction.calleeSig.contains("RuntimeException.<init>/"))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("the exception's construction is not in the stream"));

      assertThat(construction.args)
          .as("a construction has no receiver slot, so the argument is at args[0]")
          .hasSizeGreaterThanOrEqualTo(1);
      assertThat(construction.args.get(0))
          .as("the value the source hands to the exception is the argument of the construction")
          .isInstanceOf(TacOperand.TacVar.class);
    }
  }

  @Test
  @DisplayName("the expression is lowered, so the function no longer reports a problem")
  void theExpressionIsLowered(@TempDir Path directory) throws IOException {
    String source = EXPRESSION;
    Files.writeString(directory.resolve("composer.json"), "{\"name\": \"app/app\"}\n");
    Path file = directory.resolve("app.php");
    Files.writeString(file, source);

    try (PhpTacFrontend frontend = new PhpTacFrontend()) {
      assumeTrue(
          sidecarAvailable(),
          "the PHP front end needs Node and the sidecar's own parser dependency: run npm ci in"
              + " plumb-frontend/php-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);

      assertThat(result.problems())
          .as("the expression has a lowering, so there is nothing to report")
          .isEmpty();
    }
  }

  @Test
  @DisplayName("the loss of the expression's value is recorded")
  void theLossOfTheExpressionsValueIsRecorded(@TempDir Path directory) throws IOException {
    String source = EXPRESSION;
    Files.writeString(directory.resolve("composer.json"), "{\"name\": \"app/app\"}\n");
    Path file = directory.resolve("app.php");
    Files.writeString(file, source);

    try (PhpTacFrontend frontend = new PhpTacFrontend()) {
      assumeTrue(
          sidecarAvailable(),
          "the PHP front end needs Node and the sidecar's own parser dependency: run npm ci in"
              + " plumb-frontend/php-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      TacFunction handle = find(result, "handle");
      assertThat(handle).as("the function must be in the stream").isNotNull();

      assertThat(handle.drops())
          .as("the receipt names the construct and says what it lost")
          .anySatisfy(
              drop -> {
                assertThat(drop.construct()).isEqualTo("expr.throw.value");
                assertThat(drop.impact())
                    .as(
                        "the path ends at the throw, so what is missing is the value the"
                            + " expression would have produced")
                    .isEqualTo(TacDrop.Impact.VALUE);
              });
    }
  }

  @Test
  @DisplayName("a throw used as a statement is still a throw")
  void aThrowUsedAsAStatementIsStillAThrow(@TempDir Path directory) throws IOException {
    String source = STATEMENT;
    Files.writeString(directory.resolve("composer.json"), "{\"name\": \"app/app\"}\n");
    Path file = directory.resolve("app.php");
    Files.writeString(file, source);

    try (PhpTacFrontend frontend = new PhpTacFrontend()) {
      assumeTrue(
          sidecarAvailable(),
          "the PHP front end needs Node and the sidecar's own parser dependency: run npm ci in"
              + " plumb-frontend/php-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      TacFunction handle = find(result, "handle");
      assertThat(handle).as("the function must be in the stream").isNotNull();

      assertThat(handle.instructions())
          .as("the function ends in an exception, and the TAC has to say so")
          .anyMatch(instruction -> instruction.kind == TacInstruction.Kind.THROW);
    }
  }

  @Test
  @DisplayName("a throw used as a statement loses no value")
  void aThrowUsedAsAStatementLosesNoValue(@TempDir Path directory) throws IOException {
    String source = STATEMENT;
    Files.writeString(directory.resolve("composer.json"), "{\"name\": \"app/app\"}\n");
    Path file = directory.resolve("app.php");
    Files.writeString(file, source);

    try (PhpTacFrontend frontend = new PhpTacFrontend()) {
      assumeTrue(
          sidecarAvailable(),
          "the PHP front end needs Node and the sidecar's own parser dependency: run npm ci in"
              + " plumb-frontend/php-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      TacFunction handle = find(result, "handle");
      assertThat(handle).as("the function must be in the stream").isNotNull();

      assertThat(handle.drops())
          .as("a statement produces no value, so there is no value to lose")
          .noneMatch(drop -> drop.construct().equals("expr.throw.value"));
    }
  }

  // ---- the harness ---------------------------------------------------------------------------

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
