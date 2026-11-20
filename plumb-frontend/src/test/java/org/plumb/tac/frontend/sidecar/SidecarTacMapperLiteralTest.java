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
package org.plumb.tac.frontend.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.frontend.json.Json;
import org.plumb.tac.ir.TacOperand;

/**
 * The wire-to-IR mapping of a numeric literal.
 *
 * <h2>Why the literal's TEXT is the thing that is read</h2>
 *
 * <p>A sidecar carries a literal as the text the source wrote: {@code 0xFF}, {@code 0o17}, {@code
 * 0b1010}, {@code 1_000}. The reader on this side then has to read that text as a number, and it
 * used to read it as a DECIMAL number.
 *
 * <p>A decimal reader cannot read {@code 0xFF}. So the literal arrived, could not be read, and was
 * carried on as a STRING. The constant changed its TYPE without anybody being told: the TAC said
 * "text" where the source said "number", and every comparison against it was comparing a string.
 *
 * <p>Nothing reported it. The TAC looked complete, the scan reported success, and a rule that asks
 * "is this constant equal to 255?" no longer could.
 *
 * <h2>Why the text that already reads stays as it is</h2>
 *
 * <p>A literal that is already a decimal number is carried through unchanged. That is not a
 * detail: the change is meant to fix the literals that could not be read, and a literal that reads
 * today must not move.
 *
 * <p>The assertions are about SHAPES: the constant's KIND and the VALUE it spells. They do not pin
 * how the text was parsed.
 */
@DisplayName("the sidecar's numeric literals keep the number they spell")
class SidecarTacMapperLiteralTest {

  @Test
  @DisplayName("a hexadecimal literal is the number it spells")
  void aHexadecimalLiteralIsTheNumberItSpells() {
    TacOperand.Const constant = constant("INT", "0xFF");

    assertThat(constant.kind())
        .as("a literal that reads must not change its kind on the way")
        .isEqualTo(TacOperand.Const.Kind.INT);
    assertThat(constant.value()).isEqualTo(255);
  }

  @Test
  @DisplayName("a literal in another base is the number it spells")
  void aLiteralInAnotherBaseIsTheNumberItSpells() {
    assertThat(constant("INT", "0o17").value()).isEqualTo(15);
    assertThat(constant("INT", "0b1010").value()).isEqualTo(10);
  }

  @Test
  @DisplayName("a separator in a literal is not a digit")
  void aSeparatorInALiteralIsNotADigit() {
    assertThat(constant("INT", "1_000").value()).isEqualTo(1000);
  }

  @Test
  @DisplayName("a negative literal keeps its sign")
  void aNegativeLiteralKeepsItsSign() {
    assertThat(constant("INT", "-0x10").value()).isEqualTo(-16);
  }

  @Test
  @DisplayName("a decimal literal is what it was")
  void aDecimalLiteralIsWhatItWas() {
    TacOperand.Const constant = constant("INT", "42");

    assertThat(constant.kind()).isEqualTo(TacOperand.Const.Kind.INT);
    assertThat(constant.value()).isEqualTo(42);
  }

  @Test
  @DisplayName("a text that is not a number keeps its text")
  void aTextThatIsNotANumberKeepsItsText() {
    TacOperand.Const constant = constant("INT", "not a number");

    assertThat(constant.kind())
        .as("a value this build cannot read is unknown, and unknown is not zero")
        .isEqualTo(TacOperand.Const.Kind.STRING);
    assertThat(constant.value()).isEqualTo("not a number");
  }

  @Test
  @DisplayName("a long literal in another base is the number it spells")
  void aLongLiteralInAnotherBaseIsTheNumberItSpells() {
    TacOperand.Const constant = constant("LONG", "0x7FFFFFFFFFFFFFFF");

    assertThat(constant.kind()).isEqualTo(TacOperand.Const.Kind.LONG);
    assertThat(constant.value()).isEqualTo(9223372036854775807L);
  }

  @Test
  @DisplayName("a floating-point literal is the number it spells")
  void aFloatingPointLiteralIsTheNumberItSpells() {
    TacOperand.Const constant = constant("DOUBLE", "1.5");

    assertThat(constant.kind()).isEqualTo(TacOperand.Const.Kind.DOUBLE);
    assertThat(constant.value()).isEqualTo(1.5);
  }

  @Test
  @DisplayName("a prefixed literal in a floating-point field is the number it spells")
  void aPrefixedLiteralInAFloatingPointFieldIsTheNumberItSpells() {
    assertThat(constant("DOUBLE", "0x10").value()).isEqualTo(16.0);
  }

  // ---- the harness ---------------------------------------------------------------------------

  /** The constant the wire carries, mapped into the IR. */
  private static TacOperand.Const constant(String constKind, String value) {
    String wire =
        "{\"kind\":\"CONST\",\"constKind\":\"" + constKind + "\",\"value\":\"" + value + "\"}";
    return (TacOperand.Const) SidecarTacMapper.toConst(Json.parseObject(wire));
  }
}
