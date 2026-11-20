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
import org.plumb.tac.ir.TacOperand;

/**
 * The PHP frontend: a property the constructor promotes.
 *
 * <h2>What used to happen</h2>
 *
 * <p>PHP 8.0 lets the constructor's parameter list declare a property: {@code private string $name}
 * in the parameter list IS a property, and the constructor assigns it before its body runs. The
 * sidecar did not lower that at all: the constructor produced no instructions, so the property was
 * never written, and whatever read it later saw an unknown value -- the value the CALLER passed
 * never reached the code that uses the property.
 *
 * <p>Nothing said so either: no drop, no problem, no loss of precision. A scan of such a program
 * found nothing, and looked exactly like a scan of a program that had nothing to find.
 *
 * <h2>Why the PLACE is the thing to pin</h2>
 *
 * <p>The write and the read have to ask for the same place, and a place has three parts: the object
 * it is on, the owner it belongs to, and the key inside it. A write that names the class and a read
 * that names it differently are two different places, and so are a write on one object and a read
 * on another -- the value still cannot get from one to the other. So the assertion is about the
 * pair, not about one of them, and it says all three parts.
 *
 * <h2>Why this test is a frontend test</h2>
 *
 * <p>The parser's facts are the fact here: the visibility on a parameter IS the promotion, and only
 * the real parser produces it. A test that checked the mapping would pass if the sidecar stopped
 * asking.
 */
@DisplayName("PHP: a property the constructor promotes is written")
class PhpPromotedPropertyTest {

  /** A class whose constructor promotes one property and takes one ordinary parameter. */
  private static final String PROMOTED =
      """
      <?php
      namespace App;

      class User {
          public function __construct(private string $name, string $plain) {
          }

          public function name(): string {
              return $this->name;
          }
      }
      """;

  /** A class whose promoted property has a default. */
  private static final String WITH_DEFAULT =
      """
      <?php
      namespace App;

      class User {
          public function __construct(private string $name = "anonymous") {
          }
      }
      """;

  @Test
  @DisplayName("the constructor writes the promoted property")
  void theConstructorWritesThePromotedProperty(@TempDir Path directory) throws IOException {
    TacFrontend.ParseResult result = translate(PROMOTED, directory, "app.php");

    TacFunction constructor = find(result, "<init>");
    assertThat(constructor).as("the constructor must be in the stream").isNotNull();

    TacInstruction store = storeWithKey(constructor, "name");
    assertThat(store)
        .as(
            "a promoted property is a property, and the constructor assigns it -- without the"
                + " assignment the property is never written, and the value the caller passed never"
                + " reaches the code that reads it")
        .isNotNull();
    assertThat(store.ownerType)
        .as("the write names the class the property belongs to")
        .isEqualTo("App\\User");
    assertThat(store.dest)
        .as("the property takes the value the caller passed")
        .isInstanceOf(TacOperand.TacVar.class);
    assertThat(((TacOperand.TacVar) store.dest).name())
        .as("that value is the constructor's own parameter")
        .isEqualTo("name");
  }

  @Test
  @DisplayName("the write and the read ask for the same place")
  void theWriteAndTheReadAskForTheSamePlace(@TempDir Path directory) throws IOException {
    TacFrontend.ParseResult result = translate(PROMOTED, directory, "app.php");

    TacInstruction store = storeWithKey(find(result, "<init>"), "name");
    TacInstruction load = loadWithKey(find(result, "name"), "name");

    assertThat(store).as("the constructor writes the property").isNotNull();
    assertThat(load).as("the method reads the property").isNotNull();
    assertThat(store.ownerType)
        .as(
            "the write and the read have to name the same owner: two owners are two places, and the"
                + " value cannot get from one to the other")
        .isEqualTo(load.ownerType);
    assertThat(keyOf(store)).as("and the same key").isEqualTo(keyOf(load));
    assertThat(receiverNameOf(store))
        .as(
            "and they have to be on the same object: a write on one object and a read on another"
                + " are two places too, and the value cannot get from one to the other")
        .isEqualTo(receiverNameOf(load));
  }

  @Test
  @DisplayName("an ordinary parameter is not promoted")
  void anOrdinaryParameterIsNotPromoted(@TempDir Path directory) throws IOException {
    TacFrontend.ParseResult result = translate(PROMOTED, directory, "app.php");

    TacFunction constructor = find(result, "<init>");

    assertThat(storeWithKey(constructor, "plain"))
        .as("a parameter without a visibility is not a property")
        .isNull();
    assertThat(
            constructor.instructions().stream()
                .filter(instruction -> instruction.kind == TacInstruction.Kind.ARRAYSTORE)
                .count())
        .as("the constructor writes one property, and it is the promoted one")
        .isEqualTo(1L);
  }

  @Test
  @DisplayName("the default of a promoted property is recorded")
  void theDefaultOfAPromotedPropertyIsRecorded(@TempDir Path directory) throws IOException {
    TacFrontend.ParseResult result = translate(WITH_DEFAULT, directory, "app.php");

    TacFunction constructor = find(result, "<init>");
    assertThat(constructor).as("the constructor must be in the stream").isNotNull();

    assertThat(constructor.drops())
        .as(
            "the property takes the value the caller passed, and its default is used when the"
                + " caller passes none -- the TAC cannot say that, so the loss is recorded")
        .extracting(TacDrop::construct)
        .contains("value.promoted.default");
    assertThat(constructor.irPrecision())
        .as("a VALUE loss is not a loss of the structure")
        .isEqualTo(IrPrecision.SOURCE_FULL);
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

  /** The load of the given key, or null when the function has none. */
  private static TacInstruction loadWithKey(TacFunction function, String key) {
    if (function == null) {
      return null;
    }
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.ARRAYLOAD && key.equals(keyOf(instruction))) {
        return instruction;
      }
    }
    return null;
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

  /** The name of the object a keyed access is made on, or null when it is not a variable. */
  private static String receiverNameOf(TacInstruction instruction) {
    if (instruction == null) {
      return null;
    }
    if (!(instruction.lhs1 instanceof TacOperand.TacVar receiver)) {
      return null;
    }
    return receiver.name();
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
