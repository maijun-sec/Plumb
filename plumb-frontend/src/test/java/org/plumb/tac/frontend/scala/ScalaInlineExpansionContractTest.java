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
import org.plumb.tac.ir.TacInstruction;

/**
 * The Scala frontend: what an {@code inline} method's call site says about itself.
 *
 * <h2>Why the Scala side is an ACCOUNT and not an expansion</h2>
 *
 * <p>This frontend reads the typed tree <i>before</i> dotc's inliner runs, so the tree holds the
 * call and not the compiler's copy of the body. The copy's control flow -- and the non-local return
 * that only the copy can express -- is therefore not in the tree this frontend reads.
 *
 * <p>Producing the copy would mean writing an inliner over dotc's trees, which is a different and
 * much larger piece of work than the Kotlin side's expansion. What this side does instead is SAY
 * SO: the call site records a receipt, so the function that carries it declares how far it may be
 * trusted instead of looking complete.
 *
 * <p>What the Scala side already gets right is the non-local return itself: a by-name argument's
 * body is in the caller's flow, so a {@code return} inside it already leaves the calling method.
 */
@DisplayName("Scala: what an inline method's call site says about itself")
class ScalaInlineExpansionContractTest {

  /** The shared session. */
  private static ScalaTestSupport support;

  @BeforeAll
  static void setUp() {
    support = new ScalaTestSupport();
  }

  /** An inline method with a by-name parameter. */
  private static final String INLINE_METHOD =
      String.join(
          "\n",
          "package demo",
          "",
          "inline def runIt(block: => String): String = block",
          "",
          "def use(input: String): String = runIt {",
          "  if (input.length == 0) return \"early\"",
          "  input",
          "}");

  /** The same shape, but the method is NOT inline. */
  private static final String ORDINARY_METHOD =
      String.join(
          "\n",
          "package demo",
          "",
          "def runIt(block: => String): String = block",
          "",
          "def use(input: String): String = runIt {",
          "  if (input.length == 0) return \"early\"",
          "  input",
          "}");

  @Test
  @DisplayName("an inline call says that the compiler's copy of the body is not in the TAC")
  void anInlineCallSaysThatTheCompilersCopyIsNotInTheTac() {
    TacFrontend.ParseResult result = support.parse(INLINE_METHOD);

    TacFunction caller = functionNamed(result, ".use");

    assertThat(caller.drops())
        .as("the copy's control flow is missing, and the receipt says so")
        .anySatisfy(
            drop -> {
              assertThat(drop.construct()).isEqualTo("inline.notExpanded");
              assertThat(drop.impact())
                  .as("the function does not compute what the source says it computes")
                  .isEqualTo(TacDrop.Impact.SEMANTICS);
            });
    assertThat(caller.irPrecision())
        .as("and the precision follows the receipt")
        .isEqualTo(IrPrecision.SOURCE_DEGRADED);
  }

  @Test
  @DisplayName("a call to an ordinary method records no drop")
  void aCallToAnOrdinaryMethodRecordsNoDrop() {
    TacFrontend.ParseResult result = support.parse(ORDINARY_METHOD);

    TacFunction caller = functionNamed(result, ".use");

    assertThat(caller.drops())
        .as("an ordinary method has its own body in the program, so nothing was lost")
        .noneSatisfy(
            drop ->
                assertThat(drop.construct())
                    .as("the construct that was lost")
                    .isEqualTo("inline.notExpanded"));
    assertThat(caller.irPrecision())
        .as("and the precision stays where it was")
        .isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("a by-name argument's non-local return already leaves the calling method")
  void aByNameArgumentsNonLocalReturnAlreadyLeavesTheCallingMethod() {
    TacFrontend.ParseResult result = support.parse(INLINE_METHOD);

    TacFunction caller = functionNamed(result, ".use");

    assertThat(returnsOf(caller))
        .as("the return inside the by-name argument leaves the CALLER")
        .anySatisfy(
            value ->
                assertThat(value)
                    .as("the value the non-local return carries")
                    .isEqualTo("\"early\""));
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

  /** The values a function returns. */
  private static List<String> returnsOf(TacFunction function) {
    return function.instructions().stream()
        .filter(instruction -> instruction.kind == TacInstruction.Kind.RETURN)
        .map(instruction -> instruction.lhs1 == null ? "" : instruction.lhs1.toString())
        .toList();
  }
}
