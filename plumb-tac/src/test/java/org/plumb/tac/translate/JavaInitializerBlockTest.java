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
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * A class's INITIALIZERS all run in one place, and they must not lose each other.
 *
 * <p><b>What was wrong.</b> The Java lowering gave the static field initialisers and the static
 * initialiser block TWO functions with the SAME name and the SAME signature ({@code <clinit>} /
 * {@code <clinit>()}), and the same on the instance side ({@code <init>} / {@code <init>()}).
 * Functions are indexed by signature and the last writer wins, so the two overwrote each other and
 * the loser's whole body disappeared. The loss was SILENT: no drop, no problem, and the coverage
 * report said nothing -- a sink inside an initialiser block simply stopped existing.
 *
 * <p><b>How it was measured.</b> A class with a static field initialiser AND a static initialiser
 * block reported nothing where the same class without the field initialiser reported the sink. The
 * block was the one that vanished.
 *
 * <p><b>What each test pins.</b>
 *
 * <ul>
 *   <li>the static side: the field initialiser and the block share ONE function, and BOTH bodies
 *       are in it;
 *   <li>the instance side: the same, for the synthetic {@code <init>};
 *   <li>the instance side with a constructor of its own: the block runs INSIDE that constructor,
 *       where Java runs it;
 *   <li>the ORDER: the initialisers run before the constructor's own body, and the initialisers run
 *       in the order they are written;
 *   <li>the reverse control: a class with only a block still has that block's body.
 * </ul>
 */
class JavaInitializerBlockTest {

  /** The call the initializer BLOCK makes: the marker of "this body is present". */
  private static final String BLOCK = "java.lang.Runtime.exec/1";

  /** The call the OTHER body makes: a different callee, so the two can be told apart. */
  private static final String OTHER = "java.lang.System.currentTimeMillis/0";

  private static TacProgram translate(String source) {
    ParsedSource parsed = new JavaSourceParser().parseFromString(Path.of("Init.java"), source);
    return new TacTranslator(parsed).translate();
  }

  /** The function with the given signature, or null. */
  private static TacFunction functionWithSignature(TacProgram program, String signature) {
    for (TacFunction fn : program.functions()) {
      if (fn.signature().equals(signature)) {
        return fn;
      }
    }
    return null;
  }

  /** The calls a function makes, in order. */
  private static List<String> callsOf(TacFunction fn) {
    return fn.instructions().stream()
        .filter(i -> i.kind == TacInstruction.Kind.CALL)
        .map(i -> i.calleeSig)
        .toList();
  }

  /** The fields a function writes. */
  private static List<String> fieldsWrittenBy(TacFunction fn) {
    return fn.instructions().stream()
        .filter(i -> i.kind == TacInstruction.Kind.FIELDSTORE)
        .map(i -> i.fieldName)
        .toList();
  }

  /** Whether a function moves a string constant into a variable of the given name. */
  private static boolean movesStringInto(TacFunction fn, String name, String value) {
    return fn.instructions().stream()
        .anyMatch(
            i ->
                i.kind == TacInstruction.Kind.MOVE
                    && i.dest instanceof TacOperand.TacVar v
                    && v.name().equals(name)
                    && i.lhs1 instanceof TacOperand.Const c
                    && c.kind() == TacOperand.Const.Kind.STRING
                    && value.equals(c.value()));
  }

  @Test
  @DisplayName("a static initializer block and a static field initializer share one function")
  void aStaticInitializerBlockAndAStaticFieldInitializerShareOneFunction() {
    TacProgram program =
        translate(
            "class C {\n"
                + "  static String a = \"x\";\n"
                + "  static { Runtime.getRuntime().exec(\"cmd\"); }\n"
                + "}\n");

    TacFunction clinit = functionWithSignature(program, "<clinit>()");
    assertThat(clinit).as("the class's static initialisers must be in the program").isNotNull();
    assertThat(movesStringInto(clinit, "a", "x"))
        .as("the static field's initializer must still be there")
        .isTrue();
    assertThat(callsOf(clinit))
        .as("the static initializer block's body must survive the shared key")
        .contains(BLOCK);
  }

  @Test
  @DisplayName("an instance initializer block and an instance field initializer share one function")
  void anInstanceInitializerBlockAndAnInstanceFieldInitializerShareOneFunction() {
    TacProgram program =
        translate(
            "class C {\n"
                + "  String a = \"x\";\n"
                + "  { Runtime.getRuntime().exec(\"cmd\"); }\n"
                + "}\n");

    TacFunction init = functionWithSignature(program, "<init>()");
    assertThat(init).as("the class's instance initialisers must be in the program").isNotNull();
    assertThat(fieldsWrittenBy(init))
        .as("the instance field's initializer must still write its field")
        .contains("a");
    assertThat(callsOf(init))
        .as("the instance initializer block's body must survive the shared key")
        .contains(BLOCK);
  }

  @Test
  @DisplayName("an instance initializer block runs inside the constructor the class declares")
  void anInstanceInitializerBlockRunsInsideTheConstructorTheClassDeclares() {
    TacProgram program =
        translate(
            "class C {\n"
                + "  String a = \"x\";\n"
                + "  { Runtime.getRuntime().exec(\"cmd\"); }\n"
                + "  C() {}\n"
                + "}\n");

    TacFunction constructor = functionWithSignature(program, "C()");
    assertThat(constructor).as("the constructor must be in the program").isNotNull();
    assertThat(callsOf(constructor))
        .as("the initializer block runs inside the constructor, where Java runs it")
        .contains(BLOCK);
    assertThat(fieldsWrittenBy(constructor))
        .as("the instance field's initializer runs there too")
        .contains("a");
  }

  @Test
  @DisplayName("the initializers run before the constructor's own body")
  void theInitializersRunBeforeTheConstructorsOwnBody() {
    TacProgram program =
        translate(
            "class C {\n"
                + "  { Runtime.getRuntime().exec(\"cmd\"); }\n"
                + "  C() { System.currentTimeMillis(); }\n"
                + "}\n");

    TacFunction constructor = functionWithSignature(program, "C()");
    assertThat(constructor).as("the constructor must be in the program").isNotNull();
    List<String> calls = callsOf(constructor);
    assertThat(calls).as("both bodies must be in the constructor").contains(BLOCK, OTHER);
    assertThat(calls.indexOf(BLOCK))
        .as("the initializer block runs before the constructor's own body")
        .isLessThan(calls.indexOf(OTHER));
  }

  @Test
  @DisplayName("the initializers run in the order they are written")
  void theInitializersRunInTheOrderTheyAreWritten() {
    TacProgram program =
        translate(
            "class C {\n"
                + "  static { Runtime.getRuntime().exec(\"first\"); }\n"
                + "  static String a = \"x\";\n"
                + "  static { System.currentTimeMillis(); }\n"
                + "}\n");

    TacFunction clinit = functionWithSignature(program, "<clinit>()");
    assertThat(clinit).as("the class's static initialisers must be in the program").isNotNull();
    List<String> calls = callsOf(clinit);
    assertThat(calls).as("both blocks must be present").contains(BLOCK, OTHER);
    assertThat(calls.indexOf(BLOCK))
        .as("the initializers run in the order they are written")
        .isLessThan(calls.indexOf(OTHER));
    assertThat(movesStringInto(clinit, "a", "x"))
        .as("the field initializer must be present as well")
        .isTrue();
  }

  @Test
  @DisplayName("an initializer block on its own still has its body")
  void anInitializerBlockOnItsOwnStillHasItsBody() {
    TacProgram program =
        translate("class C {\n  static { Runtime.getRuntime().exec(\"cmd\"); }\n}\n");

    TacFunction clinit = functionWithSignature(program, "<clinit>()");
    assertThat(clinit).as("the static initializer block must be in the program").isNotNull();
    assertThat(callsOf(clinit)).as("the block's body must be there").contains(BLOCK);
  }
}
