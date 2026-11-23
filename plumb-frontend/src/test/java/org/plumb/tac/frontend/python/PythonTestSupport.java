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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The harness the Python lowering tests share.
 *
 * <p><b>Why the sidecar's absence is an explicit skip.</b> The Python frontend needs a Python 3
 * interpreter on the PATH, and not every machine that builds Plumb has one. A test that silently
 * passed because it analysed nothing would be worse than no test at all: it would report "the
 * Python frontend works" on a machine where it was never exercised. So the absence is a skip with a
 * stated reason, which the test report shows.
 *
 * <p><b>Why the helpers look at SHAPES and not at positions.</b> The instructions move whenever
 * anything above them changes. What has to stay true is the RELATION between them: which stream a
 * call is in, which location a name is written to, which key an entry is read under.
 */
final class PythonTestSupport {

  private PythonTestSupport() {}

  /**
   * Translate the source and return every function it produced.
   *
   * @param directory where the source file is written
   * @param source the Python source
   * @return the functions the file declares
   * @throws IOException when the source file could not be written
   */
  static List<TacFunction> translateAll(Path directory, String source) throws IOException {
    Files.writeString(directory.resolve("pyproject.toml"), "[project]\nname = \"app\"\n");
    Path file = directory.resolve("app.py");
    Files.writeString(file, source);

    try (PythonTacFrontend frontend = new PythonTacFrontend()) {
      assumeTrue(
          sidecarAvailable(),
          "the Python front end needs a Python 3 interpreter on the PATH (the sidecar imports only"
              + " the standard library, so there is nothing to install into it)");
      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      return result.functions();
    }
  }

  /**
   * The function with that name, or null when there is none.
   *
   * @param functions the functions the file declares
   * @param name the function's name
   * @return the function, or null
   */
  static TacFunction functionNamed(List<TacFunction> functions, String name) {
    for (TacFunction function : functions) {
      if (name.equals(function.name())) {
        return function;
      }
    }
    return null;
  }

  /**
   * The calls whose callee names that method.
   *
   * @param function the function to read
   * @param methodName the method the callee names
   * @return the matching calls, in the order they appear
   */
  static List<TacInstruction> callsTo(TacFunction function, String methodName) {
    List<TacInstruction> out = new ArrayList<>();
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind != TacInstruction.Kind.CALL) {
        continue;
      }
      String callee = instruction.calleeSig;
      if (callee == null) {
        continue;
      }
      int slash = callee.lastIndexOf('/');
      String qualified = slash < 0 ? callee : callee.substring(0, slash);
      int dot = qualified.lastIndexOf('.');
      String name = dot < 0 ? qualified : qualified.substring(dot + 1);
      if (methodName.equals(name)) {
        out.add(instruction);
      }
    }
    return out;
  }

  /**
   * The store that writes the entry of that name, or null.
   *
   * @param function the function to read
   * @param key the name of the entry
   * @return the matching store, or null
   */
  static TacInstruction storeOfKey(TacFunction function, String key) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.ARRAYSTORE && key.equals(keyOf(instruction))) {
        return instruction;
      }
    }
    return null;
  }

  /**
   * The load that reads the entry of that name, or null.
   *
   * @param function the function to read
   * @param key the name of the entry
   * @return the matching load, or null
   */
  static TacInstruction loadOfKey(TacFunction function, String key) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.ARRAYLOAD && key.equals(keyOf(instruction))) {
        return instruction;
      }
    }
    return null;
  }

  /**
   * The store that writes the slot of that name, or null.
   *
   * <p>A SLOT is a name that lives in a scope: the module's own, a class body's, or the scope of a
   * function a nested function writes into. The TAC has no scope of its own, so a slot is a FIELD
   * access on a synthetic owner, and the engine's cross-function field tracker is what carries the
   * value from one function to another.
   *
   * @param function the function to read
   * @param field the name of the slot
   * @return the matching store, or null
   */
  static TacInstruction fieldStoreOf(TacFunction function, String field) {
    if (function == null) {
      return null;
    }
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.FIELDSTORE
          && field.equals(instruction.fieldName)) {
        return instruction;
      }
    }
    return null;
  }

  /**
   * The load that reads the slot of that name, or null.
   *
   * @param function the function to read
   * @param field the name of the slot
   * @return the matching load, or null
   */
  static TacInstruction fieldLoadOf(TacFunction function, String field) {
    if (function == null) {
      return null;
    }
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.FIELDLOAD
          && field.equals(instruction.fieldName)) {
        return instruction;
      }
    }
    return null;
  }

  /**
   * The owner a scope access names, or null.
   *
   * @param instruction the instruction to read
   * @return the owner's variable, or null
   */
  static TacOperand.TacVar ownerOf(TacInstruction instruction) {
    if (instruction == null) {
      return null;
    }
    return instruction.lhs1 instanceof TacOperand.TacVar owner ? owner : null;
  }

  /**
   * The name of the entry a keyed access reads or writes, or null.
   *
   * @param instruction the instruction to read
   * @return the entry's name, or null when the access is not keyed by a name
   */
  static String keyOf(TacInstruction instruction) {
    if (instruction.lhs2 instanceof TacOperand.Const constant
        && constant.kind() == TacOperand.Const.Kind.STRING) {
      Object value = constant.value();
      return value instanceof String ? (String) value : null;
    }
    return null;
  }

  /**
   * The position an indexed access reads, or null when it is not an integer position.
   *
   * @param instruction the instruction to read
   * @return the position, or null
   */
  static Long positionOf(TacInstruction instruction) {
    if (instruction.lhs2 instanceof TacOperand.Const constant
        && constant.kind() == TacOperand.Const.Kind.INT) {
      Object value = constant.value();
      return value instanceof Integer number ? number.longValue() : null;
    }
    return null;
  }

  /**
   * The first call whose callee names that method, or null.
   *
   * @param function the function to read
   * @param methodName the method the callee names
   * @return the first matching call, or null
   */
  static TacInstruction firstCallTo(TacFunction function, String methodName) {
    List<TacInstruction> calls = callsTo(function, methodName);
    return calls.isEmpty() ? null : calls.get(0);
  }

  /**
   * The names of the functions that call that method.
   *
   * @param functions the functions the file declares
   * @param methodName the method the callee names
   * @return the names of the functions that make such a call
   */
  static List<String> functionsCalling(List<TacFunction> functions, String methodName) {
    List<String> out = new ArrayList<>();
    for (TacFunction function : functions) {
      if (!callsTo(function, methodName).isEmpty()) {
        out.add(function.name());
      }
    }
    return out;
  }

  /**
   * The load that reads the element at that position, or null.
   *
   * @param function the function to read
   * @param position the position of the element
   * @return the matching load, or null
   */
  static TacInstruction loadAtPosition(TacFunction function, long position) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind != TacInstruction.Kind.ARRAYLOAD) {
        continue;
      }
      Long at = positionOf(instruction);
      if (at != null && at == position) {
        return instruction;
      }
    }
    return null;
  }

  /**
   * The value the match tested, which is the value its patterns capture.
   *
   * @param function the function to read
   * @return the matched value, or null when the function has no branch
   */
  static TacOperand subjectOf(TacFunction function) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.BRANCH) {
        return instruction.lhs1;
      }
    }
    return null;
  }

  /**
   * The name of the variable an instruction writes to, or null.
   *
   * @param instruction the instruction to read
   * @return the variable's name, or null
   */
  static String writtenName(TacInstruction instruction) {
    if (instruction.dest instanceof TacOperand.TacVar variable) {
      return variable.name();
    }
    return null;
  }

  /**
   * The move that writes that variable, or null.
   *
   * @param function the function to read
   * @param name the variable's name
   * @return the matching move, or null
   */
  static TacInstruction moveTo(TacFunction function, String name) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.MOVE && name.equals(writtenName(instruction))) {
        return instruction;
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
   *
   * @return whether the sidecar can run
   */
  static boolean sidecarAvailable() {
    if (PythonSidecarClient.locatePython() == null) {
      return false;
    }
    return PythonSidecarClient.locateScript(null) != null;
  }
}
