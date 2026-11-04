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
package org.plumb.tac.ir;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.translate.TacTranslator;

/**
 * An unknown value must be expressed as an unknown, not as a value.
 *
 * <p><b>The failure this guards against.</b> When the translator met an expression it could not
 * lower, it produced a {@code null} constant. A null constant is a <em>claim</em> -- "this value is
 * null" -- and the engine acted on it as one. The taint fact about the expression was gone, and
 * nothing in the IR said so: the function looked completely analysed.
 *
 * <p>The alternative was to degrade the whole function to {@code SOURCE_LOWERED}, which threw away
 * everything the translator <em>had</em> understood -- including the taint that had already reached
 * the function's parameters, and the sink checks in the rest of the body. One unreadable expression
 * cost the whole function's credibility.
 *
 * <p>A {@link TacInstruction.Kind#HAVOC} says exactly what is true: this value is unknown. The
 * precision loss is confined to the value that could not be modelled.
 */
class ExplicitUnknownSemanticsTest {

  private static TacProgram translate(String source) {
    ParsedSource parsed = new JavaSourceParser().parseFromString(Path.of("Unknown.java"), source);
    return new TacTranslator(parsed).translate();
  }

  private static boolean hasKind(TacProgram program, TacInstruction.Kind kind) {
    for (TacFunction fn : program.functions()) {
      for (TacInstruction ins : fn.instructions()) {
        if (ins.kind == kind) return true;
      }
    }
    return false;
  }

  /**
   * The IR must be able to say "this value is unknown".
   *
   * <p>The opcode exists; this asserts the IR can carry it, and that it renders in a way a human
   * reading a dump can interpret.
   */
  @Test
  @DisplayName("the IR has an explicit unknown opcode that renders readably")
  void theUnknownOpcodeRenders() {
    TacOperand.TacVar dest = new TacOperand.TacVar("v0", TacType.UNKNOWN, true);
    TacInstruction ins = TacInstruction.builder(TacInstruction.Kind.HAVOC).dest(dest).build();

    assertThat(ins.kind).isEqualTo(TacInstruction.Kind.HAVOC);
    assertThat(ins.toString())
        .as("a dump must say the value is unknown, not print an empty slot")
        .contains("<unknown>");
  }

  /**
   * A black-box call must be distinguishable from a call the engine understands.
   *
   * <p>Without this, "the engine could not see inside this call" and "this call does nothing
   * interesting" look identical in the IR. The first is a hole in the analysis; the second is a
   * fact.
   */
  @Test
  @DisplayName("the IR has an explicit uninterpreted-call opcode")
  void theUninterpretedOpcodeExists() {
    assertThat(TacInstruction.Kind.values())
        .as("an uninterpreted call must be expressible")
        .contains(TacInstruction.Kind.UNINTERPRETED);

    TacOperand.TacVar dest = new TacOperand.TacVar("v1", TacType.UNKNOWN, true);
    TacInstruction ins =
        TacInstruction.builder(TacInstruction.Kind.UNINTERPRETED)
            .dest(dest)
            .calleeSig("some.unknown.Api/1")
            .args(java.util.List.of(dest))
            .build();

    assertThat(ins.toString())
        .as("a dump must name the call it could not interpret")
        .contains("some.unknown.Api");
  }

  /**
   * The two opcodes must be distinct.
   *
   * <p>"The value is unknown" and "the call is a black box" are different facts. Collapsing them
   * would lose the distinction between a modelling gap in an expression and a modelling gap in a
   * call, and the two call for different repairs.
   */
  @Test
  @DisplayName("unknown-value and uninterpreted-call are different opcodes")
  void theTwoUnknownsAreDistinct() {
    assertThat(TacInstruction.Kind.HAVOC)
        .as("an unknown value is not an uninterpreted call")
        .isNotEqualTo(TacInstruction.Kind.UNINTERPRETED);
  }

  /**
   * A fully understood function must not carry an unknown.
   *
   * <p>This is the control. Without it, a test that asserts "a HAVOC appears" could pass because
   * HAVOCs are emitted everywhere.
   */
  @Test
  @DisplayName("a fully supported function carries no unknown")
  void aSupportedFunctionCarriesNoUnknown() {
    TacProgram program =
        translate(
            """
            class A {
              void go(String s) {
                int n = s.length();
                String t = s.trim();
                System.out.println(t + n);
              }
            }
            """);

    assertThat(hasKind(program, TacInstruction.Kind.HAVOC))
        .as("a supported function must not contain an unknown value")
        .isFalse();
    assertThat(hasKind(program, TacInstruction.Kind.UNINTERPRETED))
        .as("a supported function must not contain a black-box call")
        .isFalse();
  }
}
