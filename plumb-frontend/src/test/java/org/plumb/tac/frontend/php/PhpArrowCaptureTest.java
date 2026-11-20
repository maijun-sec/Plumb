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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The PHP frontend: the variable an arrow function captures.
 *
 * <h2>What used to happen</h2>
 *
 * <p>An arrow function captures by value the outer variables its body uses, and the parser does not
 * say which those are: an {@code arrowfunc} has no {@code use} list, while a closure has one. The
 * sidecar therefore captured nothing, and the captured name was a free variable in the body -- a
 * name the analysis cannot bind to anything.
 *
 * <p>The sink was in the TAC the whole time, and it was still not reported: the value it was given
 * was the value of a name that came from nowhere. A scan of such a program found nothing, and
 * looked exactly like a scan of a program that had nothing to find.
 *
 * <h2>Why the CAPTURE has to reach the body</h2>
 *
 * <p>The capture is a copy of the outer value, and it reaches the body as the closure's LEADING
 * parameter -- which is what an explicit {@code use} capture is, and what the other languages'
 * captures are. The assertion is about the pair: the body takes the variable, and the call hands it
 * over.
 *
 * <h2>Why this test is a frontend test</h2>
 *
 * <p>The parser's facts are the fact here: which names the body reads, which it binds, and which
 * are in scope everywhere, are all the parser's. A test that checked the mapping would pass if the
 * sidecar stopped asking.
 */
@DisplayName("PHP: the variable an arrow function captures")
class PhpArrowCaptureTest {

  /** An arrow function whose body uses an outer variable. */
  private static final String CAPTURES =
      """
      <?php
      namespace App;

      function arrowCaptures(string $cmd): void {
          $runner = fn() => system($cmd);
          $runner();
      }
      """;

  /** An arrow function that declares a parameter of its own. */
  private static final String WITH_PARAMETER =
      """
      <?php
      namespace App;

      function arrowWithParameter(int $n): int {
          $doubler = fn(int $x): int => $x + $n;
          return $doubler(3);
      }
      """;

  /** An arrow function that assigns to a name of its own. */
  private static final String ASSIGNS =
      """
      <?php
      namespace App;

      function arrowAssigns(int $n): int {
          $f = fn() => $local = $n;
          return $f();
      }
      """;

  /** An arrow function inside a method, so that it reads the object. */
  private static final String READS_THE_OBJECT =
      """
      <?php
      namespace App;

      class Holder {
          private string $label = "x";

          public function read(): string {
              $f = fn() => $this->label;
              return $f();
          }
      }
      """;

  /** An arrow function that reads a superglobal. */
  private static final String READS_A_SUPERGLOBAL =
      """
      <?php
      namespace App;

      function arrowReadsSuperglobal(): void {
          $f = fn() => system($_GET['cmd']);
          $f();
      }
      """;

  @Test
  @DisplayName("the arrow function's own function takes the captured variable")
  void theArrowFunctionsOwnFunctionTakesTheCapturedVariable(@TempDir Path directory)
      throws IOException {
    TacFrontend.ParseResult result = translate(CAPTURES, directory, "app.php");

    TacFunction arrow = arrowFunction(result);
    assertThat(arrow).as("the arrow function must be in the stream").isNotNull();

    assertThat(parameterNames(arrow))
        .as(
            "the capture reaches the body as the closure's leading parameter: without it the"
                + " name is free, and a value that comes from nowhere carries nothing")
        .containsExactly("cmd");
  }

  @Test
  @DisplayName("the capture is copied into the closure object and handed over at the call")
  void theCaptureIsCopiedIntoTheClosureObjectAndHandedOverAtTheCall(@TempDir Path directory)
      throws IOException {
    TacFrontend.ParseResult result = translate(CAPTURES, directory, "app.php");

    TacFunction outer = find(result, "arrowCaptures");
    assertThat(outer).as("the enclosing function must be in the stream").isNotNull();

    TacInstruction store = storeWithKey(outer, "cmd");
    assertThat(store)
        .as(
            "the closure object holds the captured value, the way an explicit capture's does: the"
                + " arrow function's capture is a copy, and the copy is what the body sees")
        .isNotNull();

    TacInstruction call = callOf(outer);
    assertThat(call).as("the arrow function must be called").isNotNull();
    assertThat(argumentNames(call))
        .as(
            "and the call hands the capture over: the body reads the leading parameter, so the"
                + " value has to arrive there")
        .contains("cmd");
  }

  @Test
  @DisplayName("a declared parameter is not captured")
  void aDeclaredParameterIsNotCaptured(@TempDir Path directory) throws IOException {
    TacFrontend.ParseResult result = translate(WITH_PARAMETER, directory, "app.php");

    TacFunction arrow = arrowFunction(result);
    assertThat(arrow).as("the arrow function must be in the stream").isNotNull();

    assertThat(parameterNames(arrow))
        .as(
            "the arrow function's own parameter is its own: capturing it would copy a name that"
                + " the body reads from the arrow function's own scope")
        .containsExactly("n", "x");
  }

  @Test
  @DisplayName("a variable the body assigns is not captured")
  void aVariableTheBodyAssignsIsNotCaptured(@TempDir Path directory) throws IOException {
    TacFrontend.ParseResult result = translate(ASSIGNS, directory, "app.php");

    TacFunction arrow = arrowFunction(result);
    assertThat(arrow).as("the arrow function must be in the stream").isNotNull();

    assertThat(parameterNames(arrow))
        .as(
            "the capture is by value, so a name the body assigns is the arrow function's own: it"
                + " does not reach the outer variable, and the outer one is not captured")
        .containsExactly("n");
  }

  @Test
  @DisplayName("the object is not captured")
  void theObjectIsNotCaptured(@TempDir Path directory) throws IOException {
    TacFrontend.ParseResult result = translate(READS_THE_OBJECT, directory, "app.php");

    TacFunction arrow = arrowFunction(result);
    assertThat(arrow).as("the arrow function must be in the stream").isNotNull();

    assertThat(parameterNames(arrow))
        .as(
            "the object is bound to the method the arrow function is made in: it is not a"
                + " variable an arrow function copies")
        .isEmpty();
  }

  @Test
  @DisplayName("a superglobal is not captured")
  void aSuperglobalIsNotCaptured(@TempDir Path directory) throws IOException {
    TacFrontend.ParseResult result = translate(READS_A_SUPERGLOBAL, directory, "app.php");

    TacFunction arrow = arrowFunction(result);
    assertThat(arrow).as("the arrow function must be in the stream").isNotNull();

    assertThat(parameterNames(arrow))
        .as(
            "a superglobal is in every scope: it is not a variable the arrow function copies, and"
                + " the engine seeds it wherever the function is")
        .isEmpty();
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
   * The arrow function's own function.
   *
   * <p>The sidecar names a closure after the order it met one, so the name is a counter -- it is
   * not something a test should assert on. What is stable is that it is a function of its own, and
   * that it is not the enclosing one.
   */
  private static TacFunction arrowFunction(TacFrontend.ParseResult result) {
    for (TacFunction function : result.functions()) {
      if (function.name() != null && function.name().startsWith("closure$")) {
        return function;
      }
    }
    return null;
  }

  /** The names of a function's parameters, in order. */
  private static List<String> parameterNames(TacFunction function) {
    if (function == null) {
      return List.of();
    }
    return function.params().stream().map(TacOperand.TacVar::name).toList();
  }

  /** The store of the given key, or null when the function has none. */
  private static TacInstruction storeWithKey(TacFunction function, String key) {
    if (function == null) {
      return null;
    }
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.ARRAYSTORE && key.equals(keyOf(instruction))) {
        return instruction;
      }
    }
    return null;
  }

  /** The call of the closure, or null when the function has none. */
  private static TacInstruction callOf(TacFunction function) {
    if (function == null) {
      return null;
    }
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CALL
          && instruction.calleeSig != null
          && instruction.calleeSig.contains("closure$")) {
        return instruction;
      }
    }
    return null;
  }

  /** The names of a call's arguments, in order. */
  private static List<String> argumentNames(TacInstruction instruction) {
    if (instruction == null || instruction.args == null) {
      return List.of();
    }
    return instruction.args.stream()
        .filter(TacOperand.TacVar.class::isInstance)
        .map(argument -> ((TacOperand.TacVar) argument).name())
        .toList();
  }

  /** The key of a keyed access, or null when the access has no constant key. */
  private static String keyOf(TacInstruction instruction) {
    if (instruction == null) {
      return null;
    }
    if (!(instruction.lhs2 instanceof TacOperand.Const constant)) {
      return null;
    }
    if (constant.kind() != TacOperand.Const.Kind.STRING) {
      return null;
    }
    return String.valueOf(constant.value());
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
