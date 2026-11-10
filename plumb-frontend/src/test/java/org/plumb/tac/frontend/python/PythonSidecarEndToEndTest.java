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
package org.plumb.tac.frontend.python;

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
 * The Python frontend, end to end: the Java side, the Python sidecar and CPython's own parser.
 *
 * <h2>Why this test exists next to the one that uses a stand-in</h2>
 *
 * <p>The stand-in sidecar proves that the Java side reads the wire correctly. This one proves that
 * the wire the Java side reads is the wire the <b>real</b> sidecar writes -- and the two can
 * disagree: a field name on one side and an attribute name on the other, a receiver slot in one
 * position and at the other end, a version one side stamps and the other ignores. Every one of
 * those produces TAC that looks reasonable and finds nothing.
 *
 * <p>So the two tests are a pair: the stand-in pins the Java side, this one pins the agreement.
 *
 * <h2>Why it SKIPS</h2>
 *
 * <p>The sidecar needs a Python interpreter. A machine without one is missing an environment, not
 * carrying a broken product -- the same reason the Go frontend's test skips when there is no Go
 * toolchain.
 */
@DisplayName("Python: the real sidecar's TAC arrives as the IR's TAC")
class PythonSidecarEndToEndTest {

  private static final String SOURCE =
      """
      import os


      class UserStore:
          def find(self, key):
              return self.data.get(key, "none")


      def handle(request):
          store = UserStore()
          key = request["user_id"]
          out = store.find(key)
          box = {"label": out}
          box["label"] = key + "!"
          os.system(box.label)
          return box.label
      """;

  @Test
  @DisplayName("a translated file keeps the shape the engine reads")
  void aTranslatedFileKeepsTheShapeTheEngineReads(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("pyproject.toml"), "[project]\nname = \"app\"\n");
    Path file = directory.resolve("app.py");
    Files.writeString(file, SOURCE);

    try (PythonTacFrontend frontend = new PythonTacFrontend()) {
      assumeTrue(
          sidecarAvailable(),
          "the Python front end needs a Python 3 interpreter and the sidecar's own script");

      TacFrontend.ParseResult result = frontend.parse(SOURCE, file, null);

      assertThat(result.problems()).isEmpty();
      assertThat(result.functions()).isNotEmpty();

      TacFunction handle = find(result, "handle");
      assertThat(handle).as("the module's function must be in the stream").isNotNull();
      assertThat(handle.sourceLanguage()).isEqualTo(SourceLanguage.PYTHON3);
      assertThat(handle.signature()).contains("handle");

      TacFunction find = find(result, "find");
      assertThat(find).as("the class's method must be in the stream").isNotNull();
      assertThat(find.enclosingClass())
          .as("a method must name the class it belongs to")
          .isEqualTo("UserStore");

      // An attribute read is a CONTAINER ACCESS with a string key, not a field
      // access: in this language an object is a namespace, and `box["label"]`
      // and `box.label` are the same location.
      TacInstruction read =
          handle.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.ARRAYLOAD)
              .filter(i -> i.lhs2 instanceof TacOperand.Const)
              .filter(i -> "label".equals(String.valueOf(((TacOperand.Const) i.lhs2).value())))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("the attribute read is not a keyed container access"));
      assertThat(read.lhs1).isNotNull();
      assertThat(handle.instructions())
          .as("an attribute read must never become a field access")
          .noneMatch(i -> i.kind == TacInstruction.Kind.FIELDLOAD);

      // The other form of the same location: a subscript is a container
      // access too, and it is the SAME location as the attribute read.
      TacInstruction subscript =
          handle.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.ARRAYLOAD)
              .filter(i -> i.lhs2 instanceof TacOperand.Const)
              .filter(i -> "user_id".equals(String.valueOf(((TacOperand.Const) i.lhs2).value())))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("the subscript read is not a keyed container access"));
      assertThat(subscript.lhs1).isNotNull();

      // An attribute WRITE is a store with the same key. A write that is
      // dropped is a taint that stops halfway.
      TacInstruction write =
          handle.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.ARRAYSTORE)
              .filter(i -> i.lhs2 instanceof TacOperand.Const)
              .filter(i -> "label".equals(String.valueOf(((TacOperand.Const) i.lhs2).value())))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("the attribute write is not a keyed container store"));
      assertThat(write.dest).isNotNull();

      // A binary operator is a BINOP, not an unknown value.
      TacInstruction addition =
          handle.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.BINOP)
              .findFirst()
              .orElseThrow(() -> new AssertionError("the binary operator is not a BINOP"));
      assertThat(addition.binop).isEqualTo(org.plumb.tac.ir.BinaryOperator.ADD);

      // The call of a MODULE's function keeps the receiver slot, and the
      // callee signature is the qualified name -- which is what the sink
      // catalogue matches on.
      TacInstruction system =
          handle.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.CALL)
              .filter(i -> i.calleeSig != null && i.calleeSig.startsWith("os.system/"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("the call of os.system is not in the stream"));
      assertThat(system.args)
          .as("the receiver comes first, and the arguments follow it")
          .hasSizeGreaterThanOrEqualTo(2);
      assertThat(system.args.get(0))
          .as("the receiver slot holds the module's name, not the value")
          .isEqualTo(TacOperand.Const.ofString("os"));
      assertThat(system.args.get(1))
          .as("the tainted value is the argument, and it is at args[1]")
          .isInstanceOf(TacOperand.TacVar.class);

      // The call of a METHOD names the class of the receiver.
      TacInstruction method =
          handle.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.CALL)
              .filter(i -> i.calleeSig != null && i.calleeSig.startsWith("UserStore.find/"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("the call of the method is not in the stream"));
      assertThat(method.args.get(0))
          .as("the receiver of a method is the object it is called on")
          .isInstanceOf(TacOperand.TacVar.class);
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
   * <p>Two things have to be there: a Python interpreter and the sidecar's own script. The script
   * has no dependency to install -- {@code ast} is in the standard library -- so the interpreter is
   * the only thing that can be missing.
   */
  private static boolean sidecarAvailable() {
    if (PythonSidecarClient.locatePython() == null) {
      return false;
    }
    return PythonSidecarClient.locateScript(null) != null;
  }
}
