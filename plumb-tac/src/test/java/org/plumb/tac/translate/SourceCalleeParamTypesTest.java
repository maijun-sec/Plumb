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
package org.plumb.tac.translate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;

/**
 * A call names the method and the NUMBER of its parameters, and two overloads share both.
 *
 * <p>{@code append(Object)} and {@code append(String)} are the same call by that description, so a
 * consumer that has to choose between them has nothing to choose by. The call site is the one place
 * that knows which one it selected: the resolved declaration says so, and the bytecode lane has
 * always been able to say it (the constant pool names the overload it chose).
 *
 * <p>These tests pin that the source lane says it too, and that it stays silent rather than
 * guessing when it cannot resolve the callee -- a class declared in the scanned source is not on
 * the reflection type solver's classpath, and a guessed parameter list would send a consumer to the
 * WRONG overload, which is worse than the ambiguity it replaces.
 */
class SourceCalleeParamTypesTest {

  @Test
  void aCallSaysWhichOverloadItSelected() {
    String src =
        "class Use {\n"
            + "  void go() {\n"
            + "    StringBuilder sb = new StringBuilder();\n"
            + "    sb.append(\"text\");\n"
            + "    sb.append((Object) \"text\");\n"
            + "  }\n"
            + "}\n";

    List<List<String>> recorded = paramTypesOfCallsTo(src, "append");

    assertThat(recorded)
        .as("the two appends are two different overloads, and the call site knows which")
        .containsExactlyInAnyOrder(List.of("java.lang.String"), List.of("java.lang.Object"));
  }

  @Test
  void aConstructionSaysWhichConstructorItSelected() {
    String src =
        "class Use {\n"
            + "  void go() {\n"
            + "    StringBuilder sb = new StringBuilder(\"text\");\n"
            + "  }\n"
            + "}\n";

    List<List<String>> recorded = paramTypesOfConstructions(src);

    assertThat(recorded)
        .as("the construction names the constructor it selected")
        .containsExactly(List.of("java.lang.String"));
  }

  @Test
  void aCallToAnOverloadInTheScannedSourceSaysWhichOverloadItSelected() {
    String src =
        "class Helper {\n"
            + "  void pick(Object o) { }\n"
            + "  void pick(String s) { }\n"
            + "}\n"
            + "class Use {\n"
            + "  void go(Helper h) {\n"
            + "    h.pick(\"text\");\n"
            + "    h.pick((Object) \"text\");\n"
            + "  }\n"
            + "}\n";

    List<List<String>> recorded = paramTypesOfCallsTo(src, "pick");

    assertThat(recorded)
        .as("the two picks are two different overloads, and the call site knows which")
        .containsExactlyInAnyOrder(List.of("java.lang.String"), List.of("java.lang.Object"));
  }

  @Test
  void aCallThatCannotBeResolvedCarriesNoParameterTypes() {
    String src =
        "class Use {\n"
            + "  void go(com.example.NotOnTheClasspath h) {\n"
            + "    h.pick(\"text\");\n"
            + "  }\n"
            + "}\n";

    List<List<String>> recorded = allCallParamTypes(src);

    assertThat(recorded).as("the call is in the IR").isNotEmpty();
    assertThat(recorded)
        .as("a callee the solver does not know must not be guessed at")
        .allSatisfy(types -> assertThat(types).isEmpty());
  }

  private static List<List<String>> allCallParamTypes(String src) {
    List<List<String>> out = new ArrayList<>();
    for (TacFunction f : translate(src).functions()) {
      for (TacInstruction ins : f.instructions()) {
        if (ins.kind == TacInstruction.Kind.CALL) {
          out.add(ins.knownCalleeParamTypes());
        }
      }
    }
    return out;
  }

  private static List<List<String>> paramTypesOfCallsTo(String src, String methodName) {
    List<List<String>> out = new ArrayList<>();
    for (TacFunction f : translate(src).functions()) {
      for (TacInstruction ins : f.instructions()) {
        if (ins.kind == TacInstruction.Kind.CALL
            && ins.calleeSig != null
            && ins.calleeSig.contains("." + methodName + "/")) {
          out.add(ins.knownCalleeParamTypes());
        }
      }
    }
    return out;
  }

  private static List<List<String>> paramTypesOfConstructions(String src) {
    List<List<String>> out = new ArrayList<>();
    for (TacFunction f : translate(src).functions()) {
      for (TacInstruction ins : f.instructions()) {
        if (ins.kind == TacInstruction.Kind.NEW && ins.ctorType != null) {
          out.add(ins.knownCalleeParamTypes());
        }
      }
    }
    return out;
  }

  private static TacProgram translate(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("T.java"), src);
    return new TacTranslator(ps).translate();
  }
}
