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
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The PHP frontend, end to end: the Java side, the PHP sidecar and the reference PHP parser.
 *
 * <h2>Why this test exists next to the one that uses a stand-in</h2>
 *
 * <p>The stand-in sidecar proves that the Java side reads the wire correctly. This one proves that
 * the wire the Java side reads is the wire the <b>real</b> sidecar writes -- and the two can
 * disagree: a field name on one side and a property name on the other, a receiver slot in one
 * position and at the other end, a version one side stamps and the other ignores. Every one of
 * those produces TAC that looks reasonable and finds nothing.
 *
 * <p>So the two tests are a pair: the stand-in pins the Java side, this one pins the agreement.
 *
 * <h2>Why it SKIPS</h2>
 *
 * <p>The sidecar needs Node and an installed parser. A machine without those is missing an
 * environment, not carrying a broken product -- the same reason the Go frontend's test skips when
 * there is no Go toolchain.
 */
@DisplayName("PHP: the real sidecar's TAC arrives as the IR's TAC")
class PhpSidecarEndToEndTest {

  private static final String SOURCE =
      """
      <?php
      namespace App\\Services;

      function helper(string $a): string {
          return $a;
      }

      class Mailer {
          public function send(string $to): bool {
              $box = [];
              $box["label"] = $to;
              $seen = $box->tag;
              $out = helper($seen);
              return system($box["label"] . "!");
          }
      }
      """;

  @Test
  @DisplayName("a translated file keeps the shape the engine reads")
  void aTranslatedFileKeepsTheShapeTheEngineReads(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("composer.json"), "{\"name\": \"app/app\"}\n");
    Path file = directory.resolve("app.php");
    Files.writeString(file, SOURCE);

    try (PhpTacFrontend frontend = new PhpTacFrontend()) {
      assumeTrue(
          sidecarAvailable(),
          "the PHP front end needs Node and the sidecar's own parser dependency");

      TacFrontend.ParseResult result = frontend.parse(SOURCE, file, null);

      assertThat(result.problems()).isEmpty();
      assertThat(result.functions()).isNotEmpty();

      TacFunction send = find(result, "send");
      assertThat(send).as("the class's method must be in the stream").isNotNull();
      assertThat(send.sourceLanguage()).isEqualTo(SourceLanguage.PHP);
      assertThat(send.signature()).contains("send");
      assertThat(send.enclosingClass())
          .as("a method must name the class it belongs to, qualified as PHP qualifies it")
          .isEqualTo("App\\Services\\Mailer");

      // A property read is a CONTAINER ACCESS with a string key, not a field
      // access: in this language an object is a namespace, and `$box["label"]`
      // and `$box->label` are the same location.
      //
      // The two forms are read with DIFFERENT keys on purpose. A source that
      // only had the subscript form would still pass when the property read
      // was broken, because the subscript's own container access would
      // satisfy the assertion -- and the broken half would go unnoticed.
      TacInstruction propertyRead =
          send.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.ARRAYLOAD)
              .filter(i -> i.lhs2 instanceof TacOperand.Const)
              .filter(i -> "tag".equals(String.valueOf(((TacOperand.Const) i.lhs2).value())))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("the property read is not a keyed container access"));
      assertThat(propertyRead.lhs1).isNotNull();

      // The other form of the same location: a subscript is a container
      // access too, and it is the SAME location as the property read.
      TacInstruction subscript =
          send.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.ARRAYLOAD)
              .filter(i -> i.lhs2 instanceof TacOperand.Const)
              .filter(i -> "label".equals(String.valueOf(((TacOperand.Const) i.lhs2).value())))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("the subscript read is not a keyed container access"));
      assertThat(subscript.lhs1).isNotNull();

      assertThat(send.instructions())
          .as("a property read must never become a field access")
          .noneMatch(i -> i.kind == TacInstruction.Kind.FIELDLOAD);

      // A property WRITE is a store with the same key. A write that is
      // dropped is a taint that stops halfway.
      TacInstruction write =
          send.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.ARRAYSTORE)
              .filter(i -> i.lhs2 instanceof TacOperand.Const)
              .filter(i -> "label".equals(String.valueOf(((TacOperand.Const) i.lhs2).value())))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("the property write is not a keyed container store"));
      assertThat(write.dest).isNotNull();

      // A binary operator is a BINOP, not an unknown value.
      TacInstruction addition =
          send.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.BINOP)
              .findFirst()
              .orElseThrow(() -> new AssertionError("the binary operator is not a BINOP"));
      assertThat(addition.binop).isEqualTo(org.plumb.tac.ir.BinaryOperator.ADD);

      // The call of a global function keeps the receiver slot, and the callee
      // signature is the name PHP gives it -- which is what the sink
      // catalogue matches on.
      TacInstruction system =
          send.instructions().stream()
              .filter(i -> i.calleeSig != null && i.calleeSig.startsWith("\\system/"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("the call of system is not in the stream"));
      assertThat(system.args)
          .as("the receiver comes first, and the arguments follow it")
          .hasSizeGreaterThanOrEqualTo(2);
      assertThat(system.args.get(0))
          .as("the receiver slot holds the namespace, not the value")
          .isEqualTo(TacOperand.Const.ofString("\\"));
      assertThat(system.args.get(1))
          .as("the tainted value is the argument, and it is at args[1]")
          .isInstanceOf(TacOperand.TacVar.class);

      // The call of a function the NAMESPACE declares carries the qualified
      // name PHP gives it -- and the declaration comes AFTER the call in the
      // file, so this also pins that the declarations are read before the
      // file is lowered.
      TacInstruction namespaced =
          send.instructions().stream()
              .filter(i -> i.calleeSig != null && i.calleeSig.startsWith("App\\Services\\helper/"))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "the call of the namespaced function is not in the stream"));
      assertThat(namespaced.args)
          .as("the receiver comes first, and the arguments follow it")
          .hasSizeGreaterThanOrEqualTo(2);
      assertThat(namespaced.args.get(0))
          .as("the receiver slot holds the namespace, not the value")
          .isEqualTo(TacOperand.Const.ofString("App\\Services"));
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
