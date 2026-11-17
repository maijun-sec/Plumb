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
import org.plumb.tac.ir.TacDrop;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * The Java lowering of the declaration surface: an enum's constants, an instance field's
 * initializer, and a static field's read.
 *
 * <p><b>What each test pins, and what used to be wrong.</b>
 *
 * <ul>
 *   <li>An enum constant's body was a bare {@code NOP} and its constructor arguments were never
 *       read, so {@code PLUS("+")} put nothing into the TAC.
 *   <li>The instance field initializers of a class that HAS a constructor were dropped entirely:
 *       the synthetic constructor that holds them was only built when the class had no constructor
 *       of its own. So {@code class C { String password = "literal"; C() {} }} had no evidence of
 *       the literal anywhere -- and the hardcoded-password rule, which reads exactly that
 *       constructor, reported nothing.
 *   <li>A static field read ({@code System.out}) was read as an INSTANCE field: the type name was
 *       translated as a value, which produced a ghost {@code FIELDLOAD} off {@code this} -- a field
 *       that exists in no program -- and the static field's owner was nowhere in the TAC.
 * </ul>
 */
class JavaDeclarationSurfaceTest {

  private static TacProgram translate(String source) {
    ParsedSource parsed = new JavaSourceParser().parseFromString(Path.of("Decl.java"), source);
    return new TacTranslator(parsed).translate();
  }

  /** The function whose name ends with the given suffix, or null. */
  private static TacFunction functionNamed(TacProgram program, String suffix) {
    for (TacFunction fn : program.functions()) {
      if (fn.name().endsWith(suffix)) {
        return fn;
      }
    }
    return null;
  }

  @Test
  @DisplayName("an enum constant carries its constructor arguments")
  void anEnumConstantCarriesItsConstructorArguments() {
    TacProgram program =
        translate("enum Op {\n  PLUS(\"+\"), MINUS(\"-\");\n  Op(String symbol) {}\n}\n");

    TacFunction plus = functionNamed(program, "PLUS");
    assertThat(plus).as("the constant must be in the program").isNotNull();
    TacInstruction allocation =
        plus.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.NEW)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the constant allocates nothing"));
    assertThat(allocation.args)
        .as("the constructor argument the source wrote must reach the TAC")
        .containsExactly(TacOperand.Const.ofString("+"));
  }

  @Test
  @DisplayName("an instance field initializer runs in the constructor the class declares")
  void anInstanceFieldInitializerRunsInTheConstructorTheClassDeclares() {
    TacProgram program = translate("class C {\n  String password = \"literal\";\n  C() {}\n}\n");

    TacFunction constructor = functionNamed(program, "<init>");
    assertThat(constructor).as("the constructor must be in the program").isNotNull();
    TacInstruction store =
        constructor.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.FIELDSTORE)
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "the instance field initializer is missing from the constructor"));
    assertThat(store.fieldName).isEqualTo("password");
    assertThat(store.dest)
        .as("the field is written through the local the initializer was evaluated into")
        .isEqualTo(new TacOperand.TacVar("password", org.plumb.tac.ir.TacType.STRING, false));
    assertThat(constructor.instructions())
        .as("the literal must reach the field, or the rule that reads it sees nothing")
        .anyMatch(
            i ->
                i.kind == TacInstruction.Kind.MOVE
                    && i.lhs1 instanceof TacOperand.Const c
                    && c.kind() == TacOperand.Const.Kind.STRING
                    && "literal".equals(c.value()));
  }

  @Test
  @DisplayName("a static field is read off its class, not off the instance")
  void aStaticFieldIsReadOffItsClassNotOffTheInstance() {
    TacProgram program =
        translate(
            "class D {\n"
                + "  void m() {\n"
                + "    java.io.PrintStream out = System.out;\n"
                + "  }\n"
                + "}\n");

    TacFunction method = functionNamed(program, "m");
    assertThat(method).as("the method must be in the program").isNotNull();
    assertThat(method.instructions())
        .as("a type name must never become an instance field read")
        .noneMatch(
            i ->
                i.kind == TacInstruction.Kind.FIELDLOAD
                    && ("System".equals(i.fieldName) || "java".equals(i.fieldName)));
    TacInstruction read =
        method.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.FIELDLOAD && "out".equals(i.fieldName))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the static field was not read at all"));
    assertThat(read.fieldName).isEqualTo("out");
    assertThat(read.ownerType)
        .as("the owner says whose field this is")
        .isEqualTo("java.lang.System");
  }

  @Test
  @DisplayName("a multi-dimensional array stores its rows")
  void aMultiDimensionalArrayStoresItsRows() {
    TacProgram program =
        translate("class W {\n  void m() {\n    int[][] g = new int[2][3];\n  }\n}\n");

    TacFunction method = functionNamed(program, "m");
    assertThat(method).as("the method must be in the program").isNotNull();
    List<TacInstruction> stores =
        method.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.ARRAYSTORE)
            .toList();
    assertThat(stores)
        .as("the row must reach the outer array, or every read of it is an unknown value")
        .hasSize(1);
    TacInstruction outerAllocation =
        method.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.NEWARRAY)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the outer array was not allocated"));
    assertThat(outerAllocation.ctorType)
        .as("the outer array holds ROWS, so its element type is an array")
        .isEqualTo(org.plumb.tac.ir.TacType.arrayOf(org.plumb.tac.ir.TacType.INT));
  }

  @Test
  @DisplayName("a variadic call names the callee's declared arity and packs the rest")
  void aVariadicCallNamesTheCalleesDeclaredArityAndPacksTheRest() {
    TacProgram program =
        translate(
            "class V {\n"
                + "  void m(String a, String b) {\n"
                + "    String s = String.format(\"%s %s\", a, b);\n"
                + "  }\n"
                + "}\n");

    TacFunction method = functionNamed(program, "m");
    assertThat(method).as("the method must be in the program").isNotNull();
    TacInstruction call =
        method.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.CALL)
            .findFirst()
            .orElseThrow(() -> new AssertionError("the call is not in the stream"));
    assertThat(call.calleeSig)
        .as("the signature must name the DECLARED arity, not the number of arguments")
        .isEqualTo("java.lang.String.format/2");
    assertThat(method.instructions())
        .as("the extra arguments must be packed into the array the callee declares")
        .anyMatch(i -> i.kind == TacInstruction.Kind.NEWARRAY)
        .anyMatch(i -> i.kind == TacInstruction.Kind.ARRAYSTORE);
  }

  @Test
  @DisplayName("a multi-dimensional array says that it is not fully materialised")
  void aMultiDimensionalArraySaysThatItIsNotFullyMaterialised() {
    TacProgram program =
        translate("class W {\n  void m() {\n    int[][] g = new int[2][3];\n  }\n}\n");

    TacFunction method = functionNamed(program, "m");
    assertThat(method).as("the method must be in the program").isNotNull();
    assertThat(method.drops())
        .as("an array whose rows are not all materialised must say so")
        .anyMatch(drop -> "java.array.multidim.rows".equals(drop.construct()))
        .anyMatch(drop -> drop.impact() == TacDrop.Impact.STRUCTURE);
  }
}
