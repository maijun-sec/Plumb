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
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The Kotlin frontend: what an {@code inline} function's call site becomes.
 *
 * <h2>Why the expansion is the whole point</h2>
 *
 * <p>An {@code inline} function has no run-time counterpart. The compiler copies its body into
 * every call site, and what the call site really runs is the COPY. Lowering the call as an ordinary
 * {@code CALL} therefore loses the copy -- and with it the control flow that exists only in the
 * copy.
 *
 * <p>The sharpest case is a {@code return} inside a lambda passed to an inline function. After the
 * copy, that {@code return} leaves the function that CALLED the inline one. Lowered as an ordinary
 * call it leaves the lambda instead: a different function, with a different result.
 *
 * <p>And the engine cannot be asked to model the jump. A non-local return is a jump that leaves
 * another function; the IR's label references have no such kind, and the engine does not read label
 * references at all. So the only correct expression of a non-local return is to have the code in
 * ONE function -- which is what the expansion does.
 *
 * <h2>What the receipt says</h2>
 *
 * <p>An inline call that could NOT be expanded records a receipt, and the receipt's grade says what
 * the lost copy cost: a copy that carried a return, a throw, a branch or a loop costs the call site
 * its control flow, and a copy that was straight-line costs one value.
 */
@DisplayName("Kotlin: what an inline function's call site becomes")
class KotlinInlineExpansionContractTest {

  /** The shared session. */
  private static KotlinTestSupport support;

  @BeforeAll
  static void setUp() {
    support = new KotlinTestSupport();
  }

  /**
   * An inline function whose body calls a lambda parameter.
   *
   * <p>The lambda's {@code return} is the non-local return: after the expansion it leaves the
   * function that called {@code runIt}.
   */
  private static final String INLINE_WITH_NON_LOCAL_RETURN =
      String.join(
          "\n",
          "package demo",
          "",
          "inline fun runIt(block: () -> String): String {",
          "    return block()",
          "}",
          "",
          "fun use(input: String): String {",
          "    return runIt {",
          "        if (input.length == 0) return \"early\"",
          "        input",
          "    }",
          "}");

  /** The same shape, but the function is NOT inline: nothing is expanded. */
  private static final String ORDINARY_FUNCTION =
      String.join(
          "\n",
          "package demo",
          "",
          "fun runIt(block: () -> String): String {",
          "    return block()",
          "}",
          "",
          "fun use(input: String): String {",
          "    return runIt {",
          "        if (input.length == 0) return \"early\"",
          "        input",
          "    }",
          "}");

  /** An inline function from the STANDARD LIBRARY whose body is not in the analysed sources. */
  private static final String LIBRARY_INLINE =
      String.join(
          "\n",
          "package demo",
          "",
          "fun useRequire(cmd: String) {",
          "    require(cmd.isNotEmpty())",
          "}");

  /** A library inline call whose argument is a lambda that RETURNS. */
  private static final String LIBRARY_INLINE_WITH_A_RETURNING_LAMBDA =
      String.join(
          "\n",
          "package demo",
          "",
          "fun useLet(cmd: String): String {",
          "    return cmd.let {",
          "        if (it.length == 0) return \"early\"",
          "        it",
          "    }",
          "}");

  /** An inline function with a reified type parameter. */
  private static final String REIFIED =
      String.join(
          "\n",
          "package demo",
          "",
          "inline fun <reified T> cast(value: Any): T = value as T",
          "",
          "fun use(value: Any): String {",
          "    return cast<String>(value).toString()",
          "}");

  /** A library inline call whose argument is a lambda that only BRANCHES. */
  private static final String LIBRARY_INLINE_WITH_A_BRANCHING_LAMBDA =
      String.join(
          "\n",
          "package demo",
          "",
          "fun useLet(cmd: String): String {",
          "    return cmd.let {",
          "        if (it.length == 0) \"empty\" else it",
          "    }",
          "}");

  /** An inline MEMBER function: the call is qualified, so the copy cannot be made here. */
  private static final String INLINE_MEMBER =
      String.join(
          "\n",
          "package demo",
          "",
          "class Guard {",
          "    inline fun twice(value: String): String {",
          "        val doubled = value + value",
          "        return doubled",
          "    }",
          "}",
          "",
          "fun use(value: String): String {",
          "    val guard = Guard()",
          "    return guard.twice(value)",
          "}");

  /** The caller and the callee both declare a local of the same name. */
  private static final String SHARED_LOCAL_NAME =
      String.join(
          "\n",
          "package demo",
          "",
          "inline fun twice(value: String): String {",
          "    val doubled = value + value",
          "    return doubled",
          "}",
          "",
          "fun use(value: String): String {",
          "    val doubled = value + \"!\"",
          "    return twice(value) + doubled",
          "}");

  @Test
  @DisplayName("an inline call is expanded at the call site instead of calling the function")
  void anInlineCallIsExpandedAtTheCallSite() {
    TacFrontend.ParseResult result = support.parse(INLINE_WITH_NON_LOCAL_RETURN);

    TacFunction caller = functionNamed(result, "use");

    assertThat(calleeSignatures(caller))
        .as("the call site no longer calls the inline function: the body is right there")
        .noneSatisfy(
            signature ->
                assertThat(signature).as("a call to the inline function").contains("runIt"));
    assertThat(caller.instructions())
        .as("the expanded body's own call to the lambda parameter is not a call either")
        .filteredOn(instruction -> instruction.kind == TacInstruction.Kind.CALL)
        .noneSatisfy(
            instruction ->
                assertThat(instruction.calleeSig).as("a call to the lambda").contains("block"));
    assertThat(result.functions())
        .as("the inline function is still a function of its own in the program")
        .anySatisfy(
            function -> assertThat(function.name()).as("the inline function").endsWith("runIt"));
  }

  @Test
  @DisplayName("a non-local return inside an inline lambda leaves the calling function")
  void aNonLocalReturnInsideAnInlineLambdaLeavesTheCallingFunction() {
    TacFrontend.ParseResult result = support.parse(INLINE_WITH_NON_LOCAL_RETURN);

    TacFunction caller = functionNamed(result, "use");

    assertThat(returnsOf(caller))
        .as("the lambda's return leaves the CALLER, not the lambda")
        .anySatisfy(
            value ->
                assertThat(value)
                    .as("the value the non-local return carries")
                    .isEqualTo("\"early\""));
    assertThat(result.functions())
        .as("the lambda is not a function any more: it was copied into the caller")
        .noneSatisfy(
            function ->
                assertThat(function.name()).as("the lambda's own function").contains("$L$"));
  }

  @Test
  @DisplayName("an expanded inline call records no drop and keeps the function at full precision")
  void anExpandedInlineCallRecordsNoDrop() {
    TacFrontend.ParseResult result = support.parse(INLINE_WITH_NON_LOCAL_RETURN);

    TacFunction caller = functionNamed(result, "use");

    assertThat(caller.drops())
        .as("the body was expanded, so nothing was lost at the call site")
        .isEmpty();
    assertThat(caller.irPrecision())
        .as("a function whose inline call was expanded means what the source says it means")
        .isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("a call to an ordinary function is not expanded and records no drop")
  void aCallToAnOrdinaryFunctionIsNotExpanded() {
    TacFrontend.ParseResult result = support.parse(ORDINARY_FUNCTION);

    TacFunction caller = functionNamed(result, "use");

    assertThat(calleeSignatures(caller))
        .as("an ordinary function is called, not copied")
        .anySatisfy(
            signature ->
                assertThat(signature).as("the call to the ordinary function").contains("runIt"));
    assertThat(caller.drops())
        .as("nothing was lost: the ordinary function has its own body in the program")
        .noneSatisfy(
            drop ->
                assertThat(drop.construct())
                    .as("the construct that was lost")
                    .isEqualTo("inline.notExpanded"));
    assertThat(result.functions())
        .as("the lambda stays a function of its own")
        .anySatisfy(
            function ->
                assertThat(function.name()).as("the lambda's own function").contains("$L$"));
  }

  @Test
  @DisplayName("a library inline call loses one value and nothing else")
  void aLibraryInlineCallLosesOneValueAndNothingElse() {
    TacFrontend.ParseResult result = support.parse(LIBRARY_INLINE);

    TacFunction caller = functionNamed(result, "useRequire");

    assertThat(caller.drops())
        .as("the library's inline body is not in the analysed sources, and that is one value")
        .anySatisfy(
            drop -> {
              assertThat(drop.construct()).isEqualTo("inline.notExpanded");
              assertThat(drop.impact())
                  .as(
                      "the copy was straight-line: what is missing is the value it would produce,"
                          + " not the call site's control flow")
                  .isEqualTo(TacDrop.Impact.VALUE);
            });
    assertThat(caller.irPrecision())
        .as("one unknown value does not lower the precision")
        .isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("a library inline call whose lambda returns says the control flow is missing")
  void aLibraryInlineCallWhoseLambdaReturnsSaysTheControlFlowIsMissing() {
    TacFrontend.ParseResult result = support.parse(LIBRARY_INLINE_WITH_A_RETURNING_LAMBDA);

    TacFunction caller = functionNamed(result, "useLet");

    assertThat(caller.drops())
        .as("the lambda's return is a non-local return the copy would have carried")
        .anySatisfy(
            drop -> {
              assertThat(drop.construct()).isEqualTo("inline.notExpanded");
              assertThat(drop.impact())
                  .as(
                      "the copy would have carried a return to the call site, so the function no"
                          + " longer means what the source says")
                  .isEqualTo(TacDrop.Impact.SEMANTICS);
            });
    assertThat(caller.irPrecision())
        .as("and the precision follows the receipt")
        .isEqualTo(IrPrecision.SOURCE_DEGRADED);
  }

  @Test
  @DisplayName("a reified type argument loss records a VALUE drop")
  void aReifiedTypeArgumentLossRecordsAValueDrop() {
    TacFrontend.ParseResult result = support.parse(REIFIED);

    TacFunction caller = functionNamed(result, "use");

    assertThat(caller.drops())
        .as("the type argument is erased in the copy, and that is one value")
        .anySatisfy(
            drop -> {
              assertThat(drop.construct()).isEqualTo("inline.reifiedTypeArgument");
              assertThat(drop.impact())
                  .as("the control flow is intact; only the type is gone")
                  .isEqualTo(TacDrop.Impact.VALUE);
            });
    assertThat(caller.irPrecision())
        .as("one unknown value does not lower the precision")
        .isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("a library inline call whose lambda only branches loses one value")
  void aLibraryInlineCallWhoseLambdaOnlyBranchesLosesOneValue() {
    TacFrontend.ParseResult result = support.parse(LIBRARY_INLINE_WITH_A_BRANCHING_LAMBDA);

    TacFunction caller = functionNamed(result, "useLet");

    assertThat(caller.drops())
        .as("the copy's branch is missing, and what is missing is the value it produces")
        .anySatisfy(
            drop -> {
              assertThat(drop.construct()).isEqualTo("inline.notExpanded");
              assertThat(drop.impact())
                  .as(
                      "the lambda's branch stays inside the lambda, so the call site's control"
                          + " flow is what the source says")
                  .isEqualTo(TacDrop.Impact.VALUE);
            });
    assertThat(caller.irPrecision())
        .as("one unknown value does not lower the precision")
        .isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("a call to an inline member function says that the copy is missing")
  void aCallToAnInlineMemberFunctionSaysThatTheCopyIsMissing() {
    TacFrontend.ParseResult result = support.parse(INLINE_MEMBER);

    TacFunction caller = functionNamed(result, "use");

    assertThat(caller.drops())
        .as("the member's body is copied into the call site, and the copy is not here")
        .anySatisfy(
            drop -> {
              assertThat(drop.construct()).isEqualTo("inline.notExpanded");
              assertThat(drop.impact())
                  .as("the copy's return leaves the CALLER, so the control flow is missing")
                  .isEqualTo(TacDrop.Impact.SEMANTICS);
            });
    assertThat(caller.irPrecision())
        .as("and the precision follows the receipt")
        .isEqualTo(IrPrecision.SOURCE_DEGRADED);
  }

  @Test
  @DisplayName("the expanded body's locals do not collide with the caller's")
  void theExpandedBodysLocalsDoNotCollideWithTheCallers() {
    TacFrontend.ParseResult result = support.parse(SHARED_LOCAL_NAME);

    TacFunction caller = functionNamed(result, "use");

    List<String> written = writtenVariableNames(caller);
    assertThat(written)
        .as(
            "the caller's local and the expanded body's local are two storage locations: the"
                + " caller's value must not be overwritten by the callee's")
        .doesNotHaveDuplicates();
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

  /** The callee signatures of a function's calls. */
  private static List<String> calleeSignatures(TacFunction function) {
    return function.instructions().stream()
        .filter(instruction -> instruction.kind == TacInstruction.Kind.CALL)
        .map(instruction -> instruction.calleeSig)
        .filter(signature -> signature != null)
        .toList();
  }

  /** The values a function returns. */
  private static List<String> returnsOf(TacFunction function) {
    return function.instructions().stream()
        .filter(instruction -> instruction.kind == TacInstruction.Kind.RETURN)
        .map(instruction -> instruction.lhs1 == null ? "" : instruction.lhs1.toString())
        .toList();
  }

  /** The names of the variables the function writes to. */
  private static List<String> writtenVariableNames(TacFunction function) {
    return function.instructions().stream()
        .map(instruction -> instruction.dest)
        .filter(dest -> dest instanceof TacOperand.TacVar)
        .map(dest -> ((TacOperand.TacVar) dest).name())
        .toList();
  }
}
