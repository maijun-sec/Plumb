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
package org.plumb.tac.frontend.csharp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * A C# literal must keep its KIND in the TAC, not just its value.
 *
 * <p><b>Why this needs its own test.</b> The lowering used to switch on the literal's TOKEN kind
 * ({@code NumericLiteralToken}, {@code TrueKeyword}, ...) while the cases were written against the
 * SYNTAX NODE kind ({@code NumericLiteralExpression}, {@code TrueLiteralExpression}, ...). Every
 * literal therefore fell through to the STRING default.
 *
 * <p><b>Why the failure was invisible.</b> The constant still carried the right VALUE, so the TAC
 * looked plausible and every test that only checked values passed. What was lost was the KIND: an
 * integer range check that reads the constant's kind found a string, and a boolean test found a
 * string.
 */
@DisplayName("C#: the kind of a literal")
class CSharpLiteralKindTest {

  private static CSharpTestSupport support;

  @BeforeAll
  static void setUp() {
    support = new CSharpTestSupport();
  }

  @AfterAll
  static void tearDown() {
    if (support != null) {
      support.close();
    }
  }

  /** One local per literal kind. */
  private static final String SOURCE =
      """
      namespace Demo;

      public class Literals
      {
          public void Declare()
          {
              var i = 0;
              int j = 0;
              long k = 0;
              double d = 0;
              float f = 0;
              decimal m = 0;
              bool b = true;
              string s = "x";
              char c = 'y';
              object nothing = null;
          }
      }
      """;

  @Test
  @DisplayName("an integer literal is an INT, not a STRING")
  void anIntegerLiteralIsAnInt() {
    assertThat(constantKindOf("j")).isEqualTo("INT");
  }

  @Test
  @DisplayName("a long literal is a LONG")
  void aLongLiteralIsALong() {
    assertThat(constantKindOf("k")).isEqualTo("LONG");
  }

  @Test
  @DisplayName("a double literal is a DOUBLE")
  void aDoubleLiteralIsADouble() {
    assertThat(constantKindOf("d")).isEqualTo("DOUBLE");
  }

  @Test
  @DisplayName("a float literal is a FLOAT")
  void aFloatLiteralIsAFloat() {
    assertThat(constantKindOf("f")).isEqualTo("FLOAT");
  }

  @Test
  @DisplayName("a decimal literal is a DECIMAL")
  void aDecimalLiteralIsADecimal() {
    assertThat(constantKindOf("m")).isEqualTo("DECIMAL");
  }

  @Test
  @DisplayName("a boolean literal is a BOOL")
  void aBooleanLiteralIsABool() {
    assertThat(constantKindOf("b")).isEqualTo("BOOL");
  }

  @Test
  @DisplayName("a character literal is a CHAR")
  void aCharacterLiteralIsAChar() {
    assertThat(constantKindOf("c")).isEqualTo("CHAR");
  }

  @Test
  @DisplayName("a string literal is a STRING")
  void aStringLiteralIsAString() {
    assertThat(constantKindOf("s")).isEqualTo("STRING");
  }

  @Test
  @DisplayName("a null literal is a NULL")
  void aNullLiteralIsANull() {
    assertThat(constantKindOf("nothing")).isEqualTo("NULL");
  }

  @Test
  @DisplayName("no literal in the file is a STRING unless it is a string")
  void noLiteralIsAStringUnlessItIsOne() {
    // The general shape of the bug: EVERY literal was a STRING. This asserts
    // the general property, so a new literal kind cannot slip through either.
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Declare");

    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind != TacInstruction.Kind.MOVE) {
        continue;
      }
      TacOperand operand = instruction.lhs1;
      if (!(operand instanceof TacOperand.Const constant)) {
        continue;
      }
      String variable = ((TacOperand.TacVar) instruction.dest).name();
      if ("s".equals(variable)) {
        continue;
      }
      assertThat(constant.kind())
          .as("the literal assigned to %s must keep its own kind", variable)
          .isNotEqualTo("STRING");
    }
  }

  /** The kind of the constant assigned to the named local. */
  private static String constantKindOf(String variable) {
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Declare");
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind != TacInstruction.Kind.MOVE) {
        continue;
      }
      if (!(instruction.dest instanceof TacOperand.TacVar dest)) {
        continue;
      }
      if (!variable.equals(dest.name())) {
        continue;
      }
      if (instruction.lhs1 instanceof TacOperand.Const constant) {
        return constant.kind().name();
      }
    }
    throw new AssertionError("no constant assigned to " + variable);
  }

  /** The function with the given name. */
  private static TacFunction functionNamed(List<TacFunction> functions, String name) {
    for (TacFunction function : functions) {
      if (name.equals(function.name())) {
        return function;
      }
    }
    throw new AssertionError("no function named " + name + " in " + functions);
  }
}
