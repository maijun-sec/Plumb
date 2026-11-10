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
import org.plumb.tac.ir.TacType;

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
 *
 * <p><b>The skip is not a pass, and it says what to do about it.</b> The compiler the sidecar
 * imports lives in {@code node_modules}, which is not in version control, so a fresh checkout has
 * nothing to run: the test skips, the build stays green, and the ArkTS front end is quietly
 * unexercised. The message names the command that fixes it, so a skip is a to-do and not a shrug.
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
          "the ArkTS front end needs Node and the sidecar's own compiler dependency: run npm ci in"
              + " plumb-frontend/arkts-sidecar");

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

  /**
   * A method call names the class that owns it.
   *
   * <p><b>Why this is pinned here.</b> The sink catalogue matches a callee signature, and the owner
   * of a method call is the CLASS the method belongs to -- {@code Greeter.greet/0}, not {@code
   * greet/0} and not {@code page.greet/0}. So when the sidecar cannot name the class, every
   * catalogue entry that names a class is dead in this language, while an entry that names a module
   * claims calls it was never written for. The name has to be right for the catalogue to mean
   * anything, and the catalogue is the other side of this boundary.
   *
   * <p><b>Why it used to be wrong here and not in the sibling sidecar.</b> The name is read off the
   * compiler's type, and the two sidecars sit on two different compiler builds. The ArkTS build
   * does not implement the accessor the read asks for, so asking for it threw; the throw was
   * swallowed, and EVERY class in the language came out as the base type -- so a method call on an
   * instance was keyed by the FILE's module. Measured against the real sidecar before the fix:
   * {@code Greeter.greet/0} came out as {@code page.greet/0}, and a construction carried the base
   * type instead of the class it built.
   *
   * <p><b>Why the construction's type is the same defect seen from the other side.</b> It is what
   * the engine propagates a taint by, so a base type there loses the class the taint belongs to.
   */
  @Test
  @DisplayName("a method call names the class that owns it")
  void aMethodCallNamesTheClassThatOwnsIt(@TempDir Path directory) throws IOException {
    String source =
        """
        export class Greeter {
          name: string;
          constructor(name: string) { this.name = name; }
          greet(): string { return this.name; }
        }

        export function handle(input: string): string {
          const greeter = new Greeter(input);
          return greeter.greet();
        }
        """;
    Files.writeString(directory.resolve("build-profile.json5"), "{}\n");
    Path file = directory.resolve("page.ets");
    Files.writeString(file, source);

    try (ArktsTacFrontend frontend = new ArktsTacFrontend(SourceLanguage.ARKTS)) {
      assumeTrue(
          sidecarAvailable(),
          "the ArkTS front end needs Node and the sidecar's own compiler dependency: run npm ci in"
              + " plumb-frontend/arkts-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      assertThat(result.problems()).isEmpty();

      TacFunction handle = find(result, "handle");
      assertThat(handle).as("the module's function must be in the stream").isNotNull();

      TacInstruction construction =
          handle.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.NEW)
              .findFirst()
              .orElseThrow(() -> new AssertionError("the construction is not in the stream"));
      assertThat(construction.calleeSig)
          .as("the construction names the class it constructs")
          .isEqualTo("Greeter.<init>/1");
      assertThat(construction.ctorType)
          .as("the construction must carry the type it built")
          .isNotNull();
      assertThat(construction.ctorType.kind()).isEqualTo(TacType.Kind.CLASS);
      assertThat(construction.ctorType.className())
          .as("a construction carries the class it built, not the base type")
          .isEqualTo("Greeter");

      TacInstruction call =
          handle.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.CALL)
              .filter(i -> i.calleeSig != null && i.calleeSig.contains(".greet/"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("the method call is not in the stream"));
      assertThat(call.calleeSig)
          .as("the owner of a method call is the class that owns the method, not the file's module")
          .isEqualTo("Greeter.greet/0");
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
