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
package org.plumb.tac.frontend.kotlin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.TacDrop;
import org.plumb.tac.ir.TacFunction;

/**
 * The Kotlin frontend: what each lost construct says it lost.
 *
 * <h2>Why the grade is a question of its own</h2>
 *
 * <p>A drop is a receipt. It says a construct did not survive the lowering, and the engine reads
 * <b>how badly</b> off it: a loss of meaning makes the function {@code SOURCE_DEGRADED}, a loss of
 * shape makes it {@code SOURCE_LOWERED}, and a loss of one value leaves the precision alone.
 *
 * <p>So the grade is not bookkeeping. A drop that claims "only the shape was lost" for a construct
 * whose <b>effect</b> is gone tells the engine the function still means what the source says, and
 * the engine believes it.
 *
 * <h2>What these assertions are about</h2>
 *
 * <p>They are about the receipt and the precision that follows from it. They do not pin where the
 * instructions are, and they do not pin the message text.
 */
@DisplayName("Kotlin: what each lost construct says it lost")
class KotlinDropImpactTest {

  /** The shared session. */
  private static KotlinTestSupport support;

  @BeforeAll
  static void setUp() {
    support = new KotlinTestSupport();
  }

  /** A {@code when} whose range branch this front end cannot read. */
  private static final String UNREADABLE_BRANCH =
      String.join(
          "\n",
          "package demo",
          "",
          "fun pick(x: Int): Int {",
          "    when (x) {",
          "        in 1..5 -> 1",
          "        else -> 0",
          "    }",
          "    return 0",
          "}");

  /** A function that lowers whole. */
  private static final String CLEAN =
      String.join(
          "\n", "package demo", "", "fun clean(name: String): String {", "    return name", "}");

  /** An abstract member: there is no body to lower. */
  private static final String NO_BODY =
      String.join(
          "\n",
          "package demo",
          "",
          "abstract class Shape {",
          "    abstract fun area(): Double",
          "}");

  /** A callable reference used as a value: the function object has no representation. */
  private static final String CALLABLE_REFERENCE =
      String.join(
          "\n",
          "package demo",
          "",
          "fun helper(x: Int): Int = x + 1",
          "",
          "fun use(): Any = ::helper");

  @Test
  @DisplayName("a branch that is not lowered says it lost its meaning")
  void aBranchThatIsNotLoweredSaysItLostItsMeaning() {
    TacFrontend.ParseResult result = support.parse(UNREADABLE_BRANCH);

    TacFunction fn = functionNamed(result, "pick");

    assertThat(fn.drops())
        .as("the receipt names the construct and says what it lost")
        .anySatisfy(
            drop -> {
              assertThat(drop.construct()).isEqualTo("kotlin.when.isBranch");
              assertThat(drop.impact())
                  .as(
                      "the branch is skipped outright: the function no longer computes what the"
                          + " source says it computes")
                  .isEqualTo(TacDrop.Impact.SEMANTICS);
            });
    assertThat(fn.irPrecision())
        .as("and the precision follows the receipt, because the engine derives it from the drops")
        .isEqualTo(IrPrecision.SOURCE_DEGRADED);
  }

  @Test
  @DisplayName("a function with no body says so")
  void aFunctionWithNoBodySaysSo() {
    TacFrontend.ParseResult result = support.parse(NO_BODY);

    TacFunction fn = functionNamed(result, "area");

    assertThat(fn.drops())
        .as("a function with no body has no receipt today, and that is the gap this closes")
        .anySatisfy(
            drop -> {
              assertThat(drop.construct()).isEqualTo("kotlin.body.absent");
              assertThat(drop.impact())
                  .as("nothing was lowered at all, so nothing about the body is known")
                  .isEqualTo(TacDrop.Impact.SEMANTICS);
            });
    assertThat(fn.irPrecision())
        .as("a function with no body is the worst case, not the mildest one")
        .isEqualTo(IrPrecision.SOURCE_DEGRADED);
  }

  @Test
  @DisplayName("a callable reference used as a value loses one value and nothing else")
  void aCallableReferenceUsedAsAValueLosesOneValueAndNothingElse() {
    TacFrontend.ParseResult result = support.parse(CALLABLE_REFERENCE);

    TacFunction fn = functionNamed(result, "use");

    assertThat(fn.drops())
        .as("the function object has no representation, and that is one value")
        .anySatisfy(
            drop -> {
              assertThat(drop.construct()).isEqualTo("kotlin.callableReference.asValue");
              assertThat(drop.impact())
                  .as(
                      "the call is still in the TAC, so the data flow is visible; what is missing"
                          + " is the value")
                  .isEqualTo(TacDrop.Impact.VALUE);
            });
    assertThat(fn.irPrecision())
        .as("one unknown value does not lower the precision")
        .isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("a function that lost nothing keeps full precision")
  void aFunctionThatLostNothingKeepsFullPrecision() {
    TacFrontend.ParseResult result = support.parse(CLEAN);

    TacFunction fn = functionNamed(result, "clean");

    assertThat(fn.drops()).as("nothing was lost, so there is nothing to record").isEmpty();
    assertThat(fn.irPrecision()).isEqualTo(IrPrecision.SOURCE_FULL);
    assertThat(result.precision()).isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("the file takes the worst of its functions")
  void theFileTakesTheWorstOfItsFunctions() {
    TacFrontend.ParseResult result = support.parse(UNREADABLE_BRANCH);

    assertThat(result.precision())
        .as(
            "the file's precision is the worst of its functions'; looking for one level instead"
                + " of ranking them would report this file as fully understood")
        .isEqualTo(IrPrecision.SOURCE_DEGRADED);
  }

  // ---- the harness ---------------------------------------------------------------------------

  /** The function whose name ends with the given text. */
  private static TacFunction functionNamed(TacFrontend.ParseResult result, String suffix) {
    List<TacFunction> functions = result.functions();
    return functions.stream()
        .filter(function -> function.name().endsWith(suffix))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "no function ending in '"
                        + suffix
                        + "' is in the stream: "
                        + functions.stream().map(TacFunction::name).toList()));
  }
}
