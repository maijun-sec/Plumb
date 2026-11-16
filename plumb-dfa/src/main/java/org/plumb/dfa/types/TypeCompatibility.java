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
package org.plumb.dfa.types;

import java.util.Locale;
import org.plumb.tac.ir.TacType;

/**
 * The one rule that decides whether a value can be a value of another type.
 *
 * <p><b>Why this rule lives in one place.</b> Two places need it: the call graph, which uses it to
 * decide whether a call can reach a candidate, and the type environment, which uses it to decide
 * whether a value is provably NOT the type a sink needs. If the two disagreed, a call could be
 * rejected by one and accepted by the other, and the difference would be invisible in a report. So
 * the rule is written once, here, and both call it.
 *
 * <p><b>What "assignable" means here.</b> The IR exposes no subtype lattice: a type is a primitive
 * kind, a tagged class reference, an array, or one of the sentinels. The rule is therefore
 * conservative in the direction that keeps findings: it REJECTS only the pairs that cannot be
 * legal, and accepts everything it cannot prove illegal.
 *
 * <ul>
 *   <li>UNKNOWN, NULL and VOID on either side: accepted. An unknown value might be anything, and a
 *       null is assignable to every reference type.
 *   <li>Two primitives: accepted only when they are the same kind. A primitive is never a different
 *       primitive.
 *   <li>A primitive on exactly one side: accepted only when the other side is an ARRAY, because an
 *       array accepts any element expression.
 *   <li>Two references: accepted. {@code String -> CharSequence} and {@code ArrayList -> List} are
 *       both legal, and this IR cannot tell them apart from an illegal widening.
 * </ul>
 *
 * <p><b>What "provably not" means.</b> A caller that wants to suppress a finding needs the opposite
 * question: is this value definitely NOT of the expected type? {@link
 * TypeSet#isDefinitelyIncompatibleWith} answers it from the same rule, and answers it only when
 * every type the value may hold is incompatible. One compatible member is enough to keep the
 * finding.
 */
public final class TypeCompatibility {

  private TypeCompatibility() {}

  /**
   * Whether a value of type {@code from} can be used where {@code to} is expected.
   *
   * <p>Returns {@code true} whenever either side is unknown, so imprecision only ever widens what
   * is accepted.
   *
   * @param from the type of the value offered
   * @param to the type expected
   * @return whether the offer is legal
   */
  public static boolean isAssignable(TacType from, TacType to) {
    if (from == null || to == null) return true;
    TacType.Kind fk = from.kind();
    TacType.Kind tk = to.kind();
    if (fk == TacType.Kind.UNKNOWN
        || tk == TacType.Kind.UNKNOWN
        || fk == TacType.Kind.NULL
        || tk == TacType.Kind.NULL
        || fk == TacType.Kind.VOID
        || tk == TacType.Kind.VOID) {
      return true;
    }
    boolean fromPrim = isPrimitiveKind(fk);
    boolean toPrim = isPrimitiveKind(tk);
    if (fromPrim && toPrim) return fk == tk;
    // A primitive on exactly one side is only legal when the other side is a boxed/unknown
    // reference we cannot reason about. STRING and CLASS are concrete reference kinds, and a
    // primitive is never assignable to them without a boxing conversion the IR does not model.
    if (fromPrim != toPrim) {
      TacType.Kind other = fromPrim ? tk : fk;
      return other == TacType.Kind.ARRAY; // arrays accept any element expression
    }
    return true; // reference -> reference
  }

  /**
   * Whether a kind is a primitive.
   *
   * <p>The set is the one the call graph has always used, deliberately unchanged: adding a kind to
   * it would change which calls resolve, and that is a different decision from the one this class
   * exists for.
   *
   * @param kind the kind to classify
   * @return whether the kind is a primitive
   */
  public static boolean isPrimitiveKind(TacType.Kind kind) {
    if (kind == null) return false;
    return switch (kind) {
      case INT, LONG, DOUBLE, FLOAT, SHORT, BYTE, CHAR, BOOLEAN -> true;
      default -> false;
    };
  }

  /**
   * The type a catalogue entry names, in the words the catalogue uses.
   *
   * <p>The catalogue is language-neutral, so it names a kind in lower case rather than a class:
   * {@code string} is the string of every language, not {@code java.lang.String}.
   *
   * @param name the word the catalogue used, or null
   * @return the type, or null when the word names none
   */
  public static TacType expectedType(String name) {
    if (name == null) return null;
    switch (name.trim().toLowerCase(Locale.ROOT)) {
      case "string":
        return TacType.STRING;
      case "int":
      case "integer":
        return TacType.INT;
      case "long":
        return TacType.LONG;
      case "float":
        return TacType.FLOAT;
      case "double":
        return TacType.DOUBLE;
      case "decimal":
        return TacType.DECIMAL;
      case "boolean":
      case "bool":
        return TacType.BOOLEAN;
      case "byte":
        return TacType.BYTE;
      case "short":
        return TacType.SHORT;
      case "char":
        return TacType.CHAR;
      default:
        return null;
    }
  }

  /**
   * The words {@link #expectedType} understands.
   *
   * <p>The catalogue gate checks every declared word against this list, so a typo fails the build
   * instead of silently disabling the gate it was meant to switch on.
   *
   * @return the words, in the order they are documented
   */
  public static java.util.List<String> expectedTypeWords() {
    return java.util.List.of(
        "string", "int", "long", "float", "double", "decimal", "boolean", "byte", "short", "char");
  }
}
