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
package org.plumb.tac.frontend.scala;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The Scala front end's SYNTAX surface: the constructs it lowers into real TAC shapes.
 *
 * <p>Every test here pins one construct. The assertion is about the SHAPE -- an instruction of
 * such a kind -- and not about the exact position, because the position is an accident of the
 * lowering order and the shape is the contract.
 */
class ScalaSyntaxSurfaceTest {

  /** The shared session. */
  private static ScalaTestSupport support;

  @BeforeAll
  static void setUp() {
    support = new ScalaTestSupport();
  }

  // ------------------------------------------------------------------
  // match
  // ------------------------------------------------------------------

  @Test
  @DisplayName("A match on a type tests the scrutinee's type")
  void matchOnATypeIsAnInstanceOf() {
    TacFunction fn =
        parseSingle(
            ".f",
            "object Demo {",
            "  def f(x: Any): String = x match {",
            "    case s: String => s",
            "    case _ => \"other\"",
            "  }",
            "}");

    assertThat(kinds(fn)).as("the match's instructions").contains(TacInstruction.Kind.INSTANCEOF);
  }

  @Test
  @DisplayName("A match on a constant compares the scrutinee with it")
  void matchOnAConstantIsAComparison() {
    TacFunction fn =
        parseSingle(
            ".f",
            "object Demo {",
            "  def f(x: Int): String = x match {",
            "    case 1 => \"one\"",
            "    case _ => \"other\"",
            "  }",
            "}");

    assertThat(ofKind(fn, TacInstruction.Kind.BINOP))
        .as("the comparison")
        .anySatisfy(
            instruction ->
                assertThat(instruction.binop).as("the operator").isEqualTo(BinaryOperator.EQ));
  }

  @Test
  @DisplayName("Every case of a match is lowered; an unrecognised pattern does not swallow the rest")
  void matchDoesNotSwallowTheLaterCases() {
    TacFunction fn =
        parseSingle(
            ".f",
            "object Demo {",
            "  def f(x: Any): String = x match {",
            "    case s: String => \"a\"",
            "    case 1 => \"b\"",
            "    case _ => \"c\"",
            "  }",
            "}");

    // Three cases: a type test, a comparison, and the wildcard. The wildcard used to
    // be the fall-through for EVERY unrecognised pattern, which dropped the cases
    // after it.
    assertThat(ofKind(fn, TacInstruction.Kind.INSTANCEOF))
        .as("the type case")
        .isNotEmpty();
    assertThat(ofKind(fn, TacInstruction.Kind.BINOP))
        .as("the constant case")
        .isNotEmpty();
  }

  @Test
  @DisplayName("A case with a guard falls through to the next case when the guard fails")
  void matchGuardFallsThroughToTheNextCase() {
    TacFunction fn =
        parseSingle(
            ".f",
            "object Demo {",
            "  def f(x: Int): String = x match {",
            "    case n if n > 0 => \"positive\"",
            "    case _ => \"other\"",
            "  }",
            "}");

    List<TacInstruction> branches = ofKind(fn, TacInstruction.Kind.BRANCH);
    assertThat(branches).as("the guard is a branch").hasSize(1);
    TacInstruction guard = branches.get(0);
    assertThat(guard.falseLabel).as("the guard's false edge").isNotNull();
    // The false edge must land on the NEXT case's label -- that is what "falls
    // through" means. A guard whose false edge went nowhere would make the
    // second case unreachable, and the value it computes would be invisible.
    List<String> labels =
        ofKind(fn, TacInstruction.Kind.LABEL).stream()
            .map(instruction -> instruction.label)
            .toList();
    assertThat(labels)
        .as("the case the guard falls through to must exist")
        .contains(guard.falseLabel);
  }

  // ------------------------------------------------------------------
  // Expressions
  // ------------------------------------------------------------------

  @Test
  @DisplayName("An if used as an expression has a value")
  void ifAsAnExpressionHasAValue() {
    TacFunction fn =
        parseSingle(
            ".f",
            "object Demo {",
            "  def f(c: Boolean): String = if (c) \"yes\" else \"no\"",
            "}");

    assertThat(kinds(fn)).as("the if's instructions").contains(TacInstruction.Kind.MOVE);
    assertThat(kinds(fn)).as("the if's instructions").contains(TacInstruction.Kind.BRANCH);
  }

  @Test
  @DisplayName("A string interpolation is a concatenation")
  void stringInterpolationIsAConcatenation() {
    TacFunction fn =
        parseSingle(
            ".f",
            "object Demo {",
            "  def f(name: String): String = s\"hello $name\"",
            "}");

    assertThat(ofKind(fn, TacInstruction.Kind.BINOP))
        .as("the concatenation")
        .anySatisfy(
            instruction ->
                assertThat(instruction.binop).as("the operator").isEqualTo(BinaryOperator.ADD));
  }

  @Test
  @DisplayName("A logical and does not evaluate its right side on the short path")
  void logicalAndIsShortCircuit() {
    TacFunction fn =
        parseSingle(
            ".f",
            "object Demo {",
            "  def f(a: Boolean, b: Boolean): Boolean = a && b",
            "}");

    assertThat(kinds(fn)).as("the operator's instructions").contains(TacInstruction.Kind.BRANCH);
    assertThat(ofKind(fn, TacInstruction.Kind.BINOP))
        .as("a logical and is not a plain bitwise and")
        .noneSatisfy(
            instruction ->
                assertThat(instruction.binop)
                    .as("the operator")
                    .isEqualTo(BinaryOperator.LAND));
  }

  @Test
  @DisplayName("A super call carries the super receiver")
  void superCallCarriesTheReceiver() {
    TacFunction fn =
        parseSingle(
            "Child.greet",
            "class Base {",
            "  def greet(): String = \"base\"",
            "}",
            "class Child extends Base {",
            "  override def greet(): String = super.greet()",
            "}");

    List<TacInstruction> calls = ofKind(fn, TacInstruction.Kind.CALL);
    assertThat(calls).as("the super call").isNotEmpty();
    assertThat(calls)
        .as("every call carries a receiver in its first argument")
        .allSatisfy(call -> assertThat(call.args).as("the arguments").isNotEmpty());
  }

  @Test
  @DisplayName("A tuple literal is a call to the tuple's own constructor")
  void tupleLiteralIsAConstruction() {
    TacFunction fn =
        parseSingle(
            ".f",
            "object Demo {",
            "  def f(a: Int, b: Int): Any = (a, b)",
            "}");

    // A Scala tuple is a PRODUCT: the literal is the companion's `apply`, which
    // is what builds it. The TAC's NEW is for a class constructor, and a tuple
    // has none of its own -- so the call is the honest shape.
    assertThat(calleeSignatures(fn))
        .as("the literal's call")
        .anySatisfy(signature -> assertThat(signature).as("the tuple's constructor").contains("Tuple2"));
  }

  @Test
  @DisplayName("A named argument is passed, in the order the callee declares")
  void namedArgumentIsPassed() {
    TacFunction fn =
        parseSingle(
            ".f",
            "object Demo {",
            "  def g(x: Int, y: Int): Int = x + y",
            "  def f(): Int = g(y = 3, x = 1)",
            "}");

    TacInstruction call =
        ofKind(fn, TacInstruction.Kind.CALL).stream()
            .filter(instruction -> instruction.calleeSig != null && instruction.calleeSig.endsWith(".g/2"))
            .findFirst()
            .orElse(null);
    assertThat(call).as("the call to g").isNotNull();
    assertThat(call.args)
        .as("the receiver slot and the two arguments")
        .hasSize(3);
    // The values are in the order the CALLEE declares them -- x first, y second
    // -- even though the source wrote them the other way round. A call that kept
    // the source order would hand the callee (3, 1) for a callee that means
    // (x, y).
    assertThat(constantOf(call.args.get(1))).as("the first declared argument").isEqualTo(1);
    assertThat(constantOf(call.args.get(2))).as("the second declared argument").isEqualTo(3);
  }

  @Test
  @DisplayName("Every parameter of every parameter list is a parameter")
  void everyParameterListIsFlattened() {
    TacFunction fn =
        parseSingle(
            ".f",
            "object Demo {",
            "  def f(a: Int)(b: Int): Int = a + b",
            "}");

    assertThat(fn.params()).as("both parameter lists").hasSize(2);
  }

  // ------------------------------------------------------------------
  // Declarations
  // ------------------------------------------------------------------

  @Test
  @DisplayName("A field of a class is a field initializer")
  void classFieldIsAFieldInitializer() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "class Holder(input: String) {",
                "  val value: String = input",
                "}"));

    assertThat(result.functions())
        .as("the class's fields became functions")
        .anySatisfy(
            fn ->
                assertThat(fn.memberKind())
                    .as("a field initializer")
                    .isEqualTo(org.plumb.tac.ir.TacMemberKind.FIELD_INITIALIZER));
  }

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  /** Parses a source and returns the one function whose name ends with the given suffix. */
  private static TacFunction parseSingle(String nameSuffix, String... lines) {
    TacFrontend.ParseResult result = support.parse(String.join("\n", lines));
    List<TacFunction> matches =
        result.functions().stream().filter(fn -> fn.name().endsWith(nameSuffix)).toList();
    assertThat(matches).as("the function named " + nameSuffix).hasSize(1);
    return matches.get(0);
  }

  /** The kinds of a function's instructions, in order. */
  private static List<TacInstruction.Kind> kinds(TacFunction fn) {
    return fn.instructions().stream().map(instruction -> instruction.kind).toList();
  }

  /** The instructions of a kind. */
  private static List<TacInstruction> ofKind(TacFunction fn, TacInstruction.Kind kind) {
    return fn.instructions().stream().filter(instruction -> instruction.kind == kind).toList();
  }

  /** The callee signatures of a function's calls. */
  private static List<String> calleeSignatures(TacFunction fn) {
    return ofKind(fn, TacInstruction.Kind.CALL).stream()
        .map(instruction -> instruction.calleeSig)
        .filter(signature -> signature != null)
        .toList();
  }

  /** The value of a constant operand. */
  private static Object constantOf(TacOperand operand) {
    assertThat(operand).as("a constant operand").isInstanceOf(TacOperand.Const.class);
    return ((TacOperand.Const) operand).value();
  }
}
