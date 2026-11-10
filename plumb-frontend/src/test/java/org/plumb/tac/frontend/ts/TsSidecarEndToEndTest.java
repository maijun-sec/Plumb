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
package org.plumb.tac.frontend.ts;

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
 * The TypeScript frontend, end to end: the Java side, the Node sidecar and the compiler itself.
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
 * nothing to run: the test skips, the build stays green, and the TypeScript front end is quietly
 * unexercised. The message names the command that fixes it, so a skip is a to-do and not a shrug.
 */
@DisplayName("TypeScript: the real sidecar's TAC arrives as the IR's TAC")
class TsSidecarEndToEndTest {

  private static final String SOURCE =
      """
      export class UserStore {
        find(id: string): string {
          return id;
        }
      }

      export function handle(req: { query: { userId: string } }): string {
        const store = new UserStore();
        const id = req.query.userId;
        return store.find(id);
      }
      """;

  @Test
  @DisplayName("a translated file keeps the shape the engine reads")
  void aTranslatedFileKeepsTheShapeTheEngineReads(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("app.ts");
    Files.writeString(file, SOURCE);

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT)) {
      assumeTrue(
          sidecarAvailable(),
          "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm"
              + " ci in plumb-frontend/ts-sidecar");

      TacFrontend.ParseResult result = frontend.parse(SOURCE, file, null);

      assertThat(result.problems()).isEmpty();
      assertThat(result.functions()).hasSizeGreaterThanOrEqualTo(2);

      TacFunction handle = find(result, "handle");
      assertThat(handle).as("the exported function must be in the stream").isNotNull();
      assertThat(handle.sourceLanguage()).isEqualTo(SourceLanguage.TYPESCRIPT);
      assertThat(handle.signature()).contains("handle");

      TacFunction find = find(result, "find");
      assertThat(find).as("the class method must be in the stream").isNotNull();
      assertThat(find.enclosingClass()).as("a method must name its class").isEqualTo("UserStore");

      // A property read is a CONTAINER ACCESS with a string key, not a field
      // access: in this language an object is a namespace, and `obj.userId`
      // and `obj["userId"]` are the same location.
      TacInstruction read =
          handle.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.ARRAYLOAD)
              .filter(i -> i.lhs2 instanceof TacOperand.Const)
              .filter(i -> "userId".equals(String.valueOf(((TacOperand.Const) i.lhs2).value())))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("the property read is not a keyed container access"));
      assertThat(read.lhs1).isNotNull();
      assertThat(handle.instructions())
          .as("a property read must never become a field access")
          .noneMatch(i -> i.kind == TacInstruction.Kind.FIELDLOAD);

      // The call keeps the receiver slot: args[0] is the receiver, args[1..]
      // are the arguments. A call that put the value at args[0] would look
      // right and match nothing.
      TacInstruction call =
          handle.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.CALL)
              .findFirst()
              .orElseThrow(() -> new AssertionError("the call is not in the stream"));
      assertThat(call.calleeSig)
          .as(
              "the owner of a method call is the class that owns the method, so a catalogue can"
                  + " name it")
          .isEqualTo("UserStore.find/1");
      assertThat(call.args).hasSizeGreaterThanOrEqualTo(2);
      assertThat(((TacOperand.TacVar) call.args.get(0)).name())
          .as("the receiver comes first")
          .isEqualTo("store");

      // A function with no gap in it claims full precision, and a function
      // with a gap does not.
      if (result.problems().isEmpty()) {
        assertThat(result.precision()).isEqualTo(IrPrecision.SOURCE_FULL);
      }
    }
  }

  /**
   * The arity in the key is the DECLARATION, not the call.
   *
   * <p><b>Why the catalogue must not read the arity as an argument count.</b> A pattern may name
   * the arity, and the number in the key is NOT the number of arguments the call passed: it is the
   * number of parameters the callee DECLARES. Two calls of the same function therefore carry the
   * same key, however many arguments they pass.
   *
   * <p>Measured against the real side car: {@code two("x")} and {@code two("x", "y")} are both
   * {@code app.two/2}, where {@code two} declares two parameters. The same holds for a call the
   * side car can only see the declaration of: {@code console.log(x)} and {@code
   * console.log("prefix", x)} are both {@code Console.log/1}.
   *
   * <p><b>What breaks if it changes.</b> A catalogue entry that named the arity would claim one
   * spelling of a call and not the other, and the finding it was written for would disappear for
   * half the call sites -- silently, with the scan still reporting success. The argument LIST is
   * where the tainted value's position lives; the arity in the key is only a name the catalogue
   * matches on.
   */
  @Test
  @DisplayName("the arity in the key is the declaration, not the number of arguments")
  void theArityInTheKeyIsTheDeclaration(@TempDir Path directory) throws IOException {
    String source =
        """
        function two(a: string, b: string): string {
          return a + b;
        }

        export function run(x: string): void {
          two("x");
          two("x", "y");
        }
        """;
    Path file = directory.resolve("app.ts");
    Files.writeString(file, source);

    try (TsTacFrontend frontend = new TsTacFrontend(SourceLanguage.TYPESCRIPT)) {
      assumeTrue(
          sidecarAvailable(),
          "the TypeScript front end needs Node and the sidecar's own compiler dependency: run npm"
              + " ci in plumb-frontend/ts-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file);
      assertThat(result.problems()).isEmpty();

      TacFunction run = find(result, "run");
      assertThat(run).as("the exported function must be in the stream").isNotNull();

      assertThat(run.instructions())
          .filteredOn(i -> i.kind == TacInstruction.Kind.CALL)
          .extracting(i -> i.calleeSig)
          .as(
              "both calls reach the same function, so both carry the same key: the arity is the"
                  + " number of parameters the function DECLARES, not the number of arguments the"
                  + " call passed")
          .containsExactly("app.two/2", "app.two/2");
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
    if (TsSidecarClient.locateNode() == null) {
      return false;
    }
    Path script = TsSidecarClient.locateScript(null);
    if (script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null && Files.isDirectory(root.resolve("node_modules").resolve("typescript"));
  }
}
