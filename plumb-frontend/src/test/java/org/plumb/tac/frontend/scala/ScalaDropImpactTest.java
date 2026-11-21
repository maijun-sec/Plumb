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
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.TacDrop;
import org.plumb.tac.ir.TacFunction;

/**
 * The Scala frontend: what each lost construct says it lost.
 *
 * <h2>Why the grade is a question of its own</h2>
 *
 * <p>A drop is a receipt. It says a construct did not survive the lowering, and the engine reads
 * <b>how badly</b> off it: a loss of meaning makes the function {@code SOURCE_DEGRADED}, a loss of
 * shape makes it {@code SOURCE_LOWERED}, and a loss of one value leaves the precision alone.
 *
 * <p>The two Scala shapes below are the pair that tells the two apart: a {@code match} that became
 * a branch chain still computes the same value, and only the fact that it <i>was</i> a match is
 * gone -- while a constructor whose body the compiler generates does not do what the source says at
 * all, because the field writes it performs are not in the TAC.
 */
@DisplayName("Scala: what each lost construct says it lost")
class ScalaDropImpactTest {

  /** The shared scaffolding. */
  private static ScalaTestSupport support;

  @BeforeAll
  static void setUp() {
    support = new ScalaTestSupport();
  }

  /** A match, which the front end lowers into a branch chain. */
  private static final String MATCH =
      String.join(
          "\n",
          "package demo",
          "",
          "object Demo {",
          "  def pick(x: Int): Int = x match {",
          "    case 1 => 1",
          "    case _ => 0",
          "  }",
          "}");

  /** A class whose constructor takes parameters: the compiler generates the field writes. */
  private static final String CONSTRUCTOR =
      String.join("\n", "package demo", "", "class Point(x: Int, y: Int)");

  /** A definition that lowers whole. */
  private static final String CLEAN =
      String.join("\n", "package demo", "", "def add(a: Int, b: Int): Int = a + b");

  @Test
  @DisplayName("a match loses its shape and not its meaning")
  void aMatchLosesItsShapeAndNotItsMeaning() {
    TacFrontend.ParseResult result = support.parse(MATCH);

    TacFunction fn = functionNamed(result, ".pick");

    assertThat(fn.drops())
        .as("the receipt names the construct and says what it lost")
        .anySatisfy(
            drop -> {
              assertThat(drop.construct()).isEqualTo("scala.match.shape");
              assertThat(drop.impact())
                  .as(
                      "the bindings, the guards and the extractors are all lowered, and the value"
                          + " is the same; what is gone is the fact that it was a match")
                  .isEqualTo(TacDrop.Impact.STRUCTURE);
            });
    assertThat(fn.irPrecision())
        .as("a loss of shape is SOURCE_LOWERED, not SOURCE_DEGRADED")
        .isEqualTo(IrPrecision.SOURCE_LOWERED);
  }

  @Test
  @DisplayName("a constructor whose body the compiler generates loses its meaning")
  void aConstructorWhoseBodyTheCompilerGeneratesLosesItsMeaning() {
    TacFrontend.ParseResult result = support.parse(CONSTRUCTOR);

    TacFunction ctor = functionNamed(result, "Point.<init>");

    assertThat(ctor.drops())
        .as("the receipt names the construct and says what it lost")
        .anySatisfy(
            drop -> {
              assertThat(drop.construct()).isEqualTo("scala.body.unmodelled");
              assertThat(drop.impact())
                  .as(
                      "the field writes the compiler synthesizes are not in the TAC, so what the"
                          + " constructor does is not there")
                  .isEqualTo(TacDrop.Impact.SEMANTICS);
            });
    assertThat(ctor.irPrecision())
        .as("and the precision follows the receipt")
        .isEqualTo(IrPrecision.SOURCE_DEGRADED);
  }

  @Test
  @DisplayName("a function that lost nothing keeps full precision")
  void aFunctionThatLostNothingKeepsFullPrecision() {
    TacFrontend.ParseResult result = support.parse(CLEAN);

    TacFunction fn = functionNamed(result, ".add");

    assertThat(fn.drops()).as("nothing was lost, so there is nothing to record").isEmpty();
    assertThat(fn.irPrecision()).isEqualTo(IrPrecision.SOURCE_FULL);
    assertThat(result.precision()).isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("the file takes the worst of its functions")
  void theFileTakesTheWorstOfItsFunctions() {
    TacFrontend.ParseResult result = support.parse(CONSTRUCTOR);

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
