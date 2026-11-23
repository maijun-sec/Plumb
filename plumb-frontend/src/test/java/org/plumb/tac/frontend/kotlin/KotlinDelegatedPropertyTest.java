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
 * The Kotlin frontend: a DELEGATED property.
 *
 * <h2>What a delegated property is</h2>
 *
 * <p>{@code val config by lazy { load() }} has no initializer. The value comes from the delegate's
 * {@code getValue}, which the TAC does not call -- but the DELEGATE EXPRESSION itself runs when the
 * property is created, and it is usually a lambda whose body is user code.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Before this, the whole delegate was skipped: the declaration produced no instruction at all,
 * the lambda's body was never lowered, and every sink inside it was invisible to the analysis --
 * while the function still claimed {@code SOURCE_FULL}. A silent loss that the precision does not
 * admit is the worst kind of loss: nothing in the report says that anything is missing.
 *
 * <p>So the assertions are about the three facts a reader needs: the delegate's body is in the
 * stream, the delegate's own call is in the stream, and the property's own value is NOT the
 * delegate's value.
 */
@DisplayName("Kotlin: a delegated property")
class KotlinDelegatedPropertyTest {

  /** The shared session. */
  private static KotlinTestSupport support;

  @BeforeAll
  static void setUp() {
    support = new KotlinTestSupport();
  }

  /** A local delegated property: the delegate is a lambda that calls a function of the source. */
  private static final String LOCAL =
      String.join(
          "\n",
          "package demo",
          "",
          "fun loadConfig(v: String): String = v",
          "",
          "fun handle(input: String): String {",
          "    val config by lazy { loadConfig(input) }",
          "    return config",
          "}");

  /** A member delegated property: the delegate runs when the object is created. */
  private static final String MEMBER =
      String.join(
          "\n",
          "package demo",
          "",
          "fun loadConfig(v: String): String = v",
          "",
          "class Holder {",
          "    val member by lazy { loadConfig(\"x\") }",
          "}");

  /** A property with an initializer: not a delegated one. */
  private static final String ORDINARY =
      String.join(
          "\n",
          "package demo",
          "",
          "fun handle(input: String): String {",
          "    val config = input",
          "    return config",
          "}");

  @Test
  @DisplayName("the delegate's body is a function of its own, and it is in the stream")
  void theDelegatesBodyIsAFunctionOfItsOwn() {
    TacFrontend.ParseResult result = support.parse(LOCAL);

    TacFunction body = functionNamed(result, "handle$L$0");

    assertThat(body)
        .as(
            "the delegate's body is user code; a sink inside it is a finding in that function, and"
                + " the function has to exist for the finding to have somewhere to live")
        .isNotNull();
    assertThat(calleeSignatures(body))
        .as("the body's own call is in the body")
        .contains("loadConfig/1");
  }

  @Test
  @DisplayName("the delegate's own call is in the stream of the function that declares it")
  void theDelegatesOwnCallIsInTheDeclaringFunctionsStream() {
    TacFrontend.ParseResult result = support.parse(LOCAL);

    TacFunction handle = functionNamed(result, "handle");

    assertThat(calleeSignatures(handle))
        .as("the delegate is evaluated where the property is declared, so its call is there")
        .anySatisfy(signature -> assertThat(signature).startsWith("lazy"));
  }

  @Test
  @DisplayName("the property holds no value of its own: the delegate's value is not the property's")
  void thePropertyHoldsNoValueOfItsOwn() {
    TacFrontend.ParseResult result = support.parse(LOCAL);

    TacFunction handle = functionNamed(result, "handle");

    assertThat(handle.instructions())
        .as(
            "the delegate's value is the delegate object, and the property's value is what the"
                + " delegate's getValue returns; writing the first into the second would be a lie")
        .noneMatch(
            instruction ->
                instruction.kind == TacInstruction.Kind.MOVE
                    && instruction.dest instanceof TacOperand.TacVar dest
                    && "config".equals(dest.name()));
  }

  @Test
  @DisplayName("the lost value is recorded, and it costs one value and nothing else")
  void theLostValueIsRecorded() {
    TacFrontend.ParseResult result = support.parse(LOCAL);

    TacFunction handle = functionNamed(result, "handle");

    assertThat(handle.drops())
        .as("the receipt names the construct and says what it lost")
        .anySatisfy(
            drop -> {
              assertThat(drop.construct()).isEqualTo("kotlin.property.delegate");
              assertThat(drop.impact())
                  .as(
                      "the function still runs the same statements; what is missing is the value"
                          + " the delegate's getValue would have produced")
                  .isEqualTo(TacDrop.Impact.VALUE);
            });
    assertThat(handle.irPrecision())
        .as("one unknown value does not lower the precision")
        .isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("a member delegated property lowers its delegate in the constructor")
  void aMemberDelegatedPropertyLowersItsDelegateInTheConstructor() {
    TacFrontend.ParseResult result = support.parse(MEMBER);

    TacFunction constructor = functionNamed(result, "Holder.<init>");

    assertThat(calleeSignatures(constructor))
        .as(
            "the delegate is evaluated when the object is created, so it belongs to the"
                + " constructor")
        .anySatisfy(signature -> assertThat(signature).startsWith("lazy"));
  }

  @Test
  @DisplayName("the receipt of a member delegated property is on the constructor")
  void theReceiptOfAMemberDelegatedPropertyIsOnTheConstructor() {
    TacFrontend.ParseResult result = support.parse(MEMBER);

    TacFunction constructor = functionNamed(result, "Holder.<init>");

    assertThat(constructor.drops())
        .as("the receipt is on the constructor, because that is where the loss happened")
        .anySatisfy(drop -> assertThat(drop.construct()).isEqualTo("kotlin.property.delegate"));
  }

  @Test
  @DisplayName("a property with an initializer is not a delegated one")
  void aPropertyWithAnInitializerIsNotADelegatedOne() {
    TacFrontend.ParseResult result = support.parse(ORDINARY);

    TacFunction handle = functionNamed(result, "handle");

    assertThat(handle.drops())
        .as("an ordinary property loses nothing, so it has no receipt")
        .noneMatch(drop -> drop.construct().equals("kotlin.property.delegate"));
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

  /** The callee signatures of a function's calls, in source order. */
  private static List<String> calleeSignatures(TacFunction function) {
    return function.instructions().stream()
        .filter(instruction -> instruction.kind == TacInstruction.Kind.CALL)
        .map(instruction -> instruction.calleeSig)
        .filter(signature -> signature != null)
        .toList();
  }
}
