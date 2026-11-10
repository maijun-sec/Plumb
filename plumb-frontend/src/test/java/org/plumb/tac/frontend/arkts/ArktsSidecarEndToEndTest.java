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
package org.plumb.tac.frontend.arkts;

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
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The ArkTS frontend, end to end: the Java side, the Node sidecar and the compiler itself.
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
 * <p>The sidecar needs Node and the compiler, which npm installs. A machine without them is missing
 * an environment, not carrying a broken product -- the same reason the Go frontend's test skips
 * when there is no Go toolchain.
 */
@DisplayName("ArkTS: the real sidecar's TAC arrives as the IR's TAC")
class ArktsSidecarEndToEndTest {

  private static final String SOURCE =
      """
      @Entry
      @Component
      struct ProfilePage {
        @State message: string = 'hello'
        private secret: string = 'hidden'

        build() {
          Column() {
            Text(this.message)
          }
        }

        render(input: string): string {
          this.message = input;
          return this.message;
        }
      }
      """;

  @Test
  @DisplayName("a translated file keeps the shape the engine reads")
  void aTranslatedFileKeepsTheShapeTheEngineReads(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("build-profile.json5"), "{}\n");
    Path file = directory.resolve("Page.ets");
    Files.writeString(file, SOURCE);

    try (ArktsTacFrontend frontend = new ArktsTacFrontend(SourceLanguage.ARKTS)) {
      assumeTrue(
          sidecarAvailable(),
          "the ArkTS front end needs Node and the sidecar's own compiler dependency");

      TacFrontend.ParseResult result = frontend.parse(SOURCE, file, null);

      assertThat(result.problems()).isEmpty();
      assertThat(result.functions()).isNotEmpty();

      TacFunction build = find(result, "build");
      assertThat(build).as("the struct's build method must be in the stream").isNotNull();
      assertThat(build.sourceLanguage()).isEqualTo(SourceLanguage.ARKTS);
      assertThat(build.enclosingClass())
          .as("a method must name its struct")
          .isEqualTo("ProfilePage");

      TacFunction render = find(result, "render");
      assertThat(render).as("the struct's other method must be in the stream").isNotNull();
      assertThat(render.signature()).contains("render");

      // A property read is a CONTAINER ACCESS with a string key, not a field
      // access: in this language an object is a namespace, and `this.message`
      // and `this["message"]` are the same location.
      TacInstruction read =
          render.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.ARRAYLOAD)
              .filter(i -> i.lhs2 instanceof TacOperand.Const)
              .filter(i -> "message".equals(String.valueOf(((TacOperand.Const) i.lhs2).value())))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("the property read is not a keyed container access"));
      assertThat(read.lhs1).isNotNull();
      assertThat(render.instructions())
          .as("a property read must never become a field access")
          .noneMatch(i -> i.kind == TacInstruction.Kind.FIELDLOAD);

      // A property WRITE is a store with the same key. A write that is
      // dropped is a taint that stops halfway.
      TacInstruction write =
          render.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.ARRAYSTORE)
              .filter(i -> i.lhs2 instanceof TacOperand.Const)
              .filter(i -> "message".equals(String.valueOf(((TacOperand.Const) i.lhs2).value())))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("the property write is not a keyed container store"));
      assertThat(write.dest).isNotNull();

      // The UI DSL is a component call, and the property it renders is read
      // into its argument -- which is where a taint reaches the component.
      TacInstruction component =
          build.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.CALL)
              .filter(i -> i.calleeSig != null && i.calleeSig.contains(".Text/"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("the component call is not in the stream"));
      assertThat(component.args)
          .as("the receiver comes first, and the arguments follow it")
          .hasSizeGreaterThanOrEqualTo(2);
      assertThat(component.args.get(0))
          .as("the receiver slot holds the module's name, not the value")
          .isInstanceOf(TacOperand.Const.class);
      assertThat(component.args.get(1))
          .as("the rendered property is the argument, and it is at args[1]")
          .isInstanceOf(TacOperand.TacVar.class);

      // A function with no gap in it claims full precision, and a function
      // with a gap does not.
      if (result.problems().isEmpty()) {
        assertThat(result.precision()).isEqualTo(IrPrecision.SOURCE_FULL);
      }
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
   * <p>Three things have to be there: Node, the sidecar's script, and the compiler the script
   * imports. The third is the one that is easy to miss, and its absence would make the sidecar die
   * on its first request -- which would look like a broken product rather than a missing
   * dependency.
   */
  private static boolean sidecarAvailable() {
    if (ArktsSidecarClient.locateNode() == null) {
      return false;
    }
    Path script = ArktsSidecarClient.locateScript(null);
    if (script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null
        && Files.isDirectory(root.resolve("node_modules").resolve("ohos-typescript"));
  }
}
