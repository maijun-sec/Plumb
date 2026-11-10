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

  /**
   * A Python construction: the key the catalogue has to name.
   *
   * <p><b>Why this is pinned here and not only in the sidecar's smoke test.</b> The sink catalogue
   * matches a callee signature, so a construction's key is what decides whether a catalogue entry
   * can ever fire. Python has no {@code new} keyword, so a construction is a CALL: its key is
   * {@code <module>.<Class>/<arity>} and its argument list keeps the receiver slot. A sidecar that
   * changed either would leave every Python construction entry matching nothing -- silently, and
   * with the scan still reporting success. The sidecar's own smoke test does assert these keys, but
   * nothing in the build runs it, so the fact had no automatic guard at all.
   *
   * <p><b>Why a class this module declares has no usable owner.</b> The owner of a call is the
   * module the name came from, and a class declared HERE comes from no module: the file's name is
   * arbitrary. A file called {@code subprocess.py} that declares {@code class Popen} would
   * otherwise produce {@code subprocess.Popen/1} and be claimed by the catalogue's entry for the
   * real {@code subprocess.Popen} -- the same false positive the {@code <unknown>} owner exists to
   * prevent. So the owner degrades to {@code <unknown>}: no module path can contain an angle
   * bracket, and every catalogue pattern is anchored at the start of the key.
   */
  @Test
  @DisplayName("a construction names the module the class came from")
  void aConstructionNamesTheModuleTheClassCameFrom(@TempDir Path directory) throws IOException {
    String source =
        """
        import subprocess


        class Greeter:
            def __init__(self, name):
                self.name = name


        def handle(request):
            proc = subprocess.Popen(request)
            greeter = Greeter(request)
            return proc
        """;
    Files.writeString(directory.resolve("pyproject.toml"), "[project]\nname = \"app\"\n");
    Path file = directory.resolve("app.py");
    Files.writeString(file, source);

    try (PythonTacFrontend frontend = new PythonTacFrontend()) {
      assumeTrue(
          sidecarAvailable(),
          "the Python front end needs a Python 3 interpreter and the sidecar's own script");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      assertThat(result.problems()).isEmpty();

      TacFunction handle = find(result, "handle");
      assertThat(handle).as("the module's function must be in the stream").isNotNull();

      // A class from another module: the owner is that module, and the key is
      // the one the catalogue's Python entries already use.
      TacInstruction construction =
          handle.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.CALL)
              .filter(i -> i.calleeSig != null && i.calleeSig.startsWith("subprocess.Popen/"))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "the construction of subprocess.Popen is not in the stream"));
      assertThat(construction.calleeSig)
          .as("the key is the module the class came from, with the arity of the call")
          .isEqualTo("subprocess.Popen/1");
      assertThat(construction.args)
          .as("a call keeps the receiver slot, so the argument is at args[1]")
          .hasSize(2);
      assertThat(construction.args.get(0))
          .as("the receiver slot holds the module's name")
          .isEqualTo(TacOperand.Const.ofString("subprocess"));

      // A class this module declares: no module to name, so the owner
      // degrades and no catalogue entry can claim the call.
      TacInstruction local =
          handle.instructions().stream()
              .filter(i -> i.calleeSig != null && i.calleeSig.endsWith(".Greeter/1"))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "the construction of the module's own class is not in the stream"));
      assertThat(local.calleeSig)
          .as(
              "the owner of a class this module declares is degraded: a file whose name looks like "
                  + "a framework module must not produce a key the catalogue can claim")
          .startsWith("<unknown>.");
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
