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

import java.util.LinkedHashSet;
import java.util.Set;
import org.plumb.tac.ir.TacType;

/**
 * The types a value may hold, as the analysis has seen them.
 *
 * <p><b>Why a SET and not a type.</b> The IR identifies a variable by its NAME, and the type is not
 * part of that identity ({@code TacVar.equals} compares the name and the temporary flag and nothing
 * else). So one variable has one entry here, and that entry has to be able to say "an int here, or
 * a string there" -- which is what a set is for.
 *
 * <p><b>Why the set only grows.</b> The analysis is path-insensitive, so a value written on one
 * path may still be the other one after the paths join. A type observed once is therefore never
 * taken back, and a variable that is an int on one branch and a string on another is a value that
 * may hold either. The same discipline the taint state follows.
 *
 * <p><b>The three answers it gives.</b>
 *
 * <ul>
 *   <li>EMPTY -- the analysis has seen no type at all: nothing is known, and nothing is proven.
 *   <li>a set of concrete kinds -- the value may hold any of them. It is provably NOT something
 *       else only when every member is incompatible with what is expected.
 * </ul>
 *
 * <p><b>Why "definitely" is the word that matters.</b> A finding that is suppressed on a
 * probability is a finding that is lost. So the question is never "is this value probably the wrong
 * type" but "is this value PROVABLY the wrong type", and one compatible member of the set is enough
 * to keep the finding.
 *
 * <p><b>Why an array is never the wrong type.</b> The compatibility rule accepts every
 * reference-to-reference pair, because this IR has no subtype lattice. An array is a reference, so
 * an array is "compatible" with a string and a tainted array is never suppressed -- which is what
 * keeps the finding on {@code exec(new String[]{userInput})} alive, where the tainted element is
 * the payload.
 */
public final class TypeSet {

  private final Set<TacType.Kind> kinds = new LinkedHashSet<>();
  private final Set<String> classNames = new LinkedHashSet<>();

  private TypeSet() {}

  /**
   * A set holding one type.
   *
   * @param type the type, or null for none
   * @return the set
   */
  public static TypeSet of(TacType type) {
    TypeSet set = new TypeSet();
    set.add(type);
    return set;
  }

  /**
   * An empty set.
   *
   * @return the set
   */
  public static TypeSet empty() {
    return new TypeSet();
  }

  /**
   * Add a type to the set.
   *
   * @param type the type, or null for none
   */
  public void add(TacType type) {
    if (type == null) return;
    kinds.add(type.kind());
    if (type.kind() == TacType.Kind.CLASS && type.className() != null) {
      classNames.add(type.className());
    }
  }

  /**
   * Add every type of another set to this one.
   *
   * @param other the other set, or null for none
   */
  public void addAll(TypeSet other) {
    if (other == null) return;
    kinds.addAll(other.kinds);
    classNames.addAll(other.classNames);
  }

  /**
   * How many kinds this set holds.
   *
   * <p>The size is what tells a caller that a round of the type scan learned something new: the set
   * only ever grows, so a change in size is a change in what is known.
   *
   * @return the number of kinds
   */
  public int size() {
    return kinds.size();
  }

  /**
   * Whether the analysis has seen no type at all.
   *
   * @return whether the set is empty
   */
  public boolean isEmpty() {
    return kinds.isEmpty();
  }

  /**
   * Whether one of the types seen was not known.
   *
   * @return whether the set holds the unknown kind
   */
  public boolean containsUnknown() {
    return kinds.contains(TacType.Kind.UNKNOWN);
  }

  /**
   * The kinds this set holds.
   *
   * @return the kinds, in the order they were seen
   */
  public Set<TacType.Kind> kinds() {
    return Set.copyOf(kinds);
  }

  /**
   * Whether this value is PROVABLY not of the expected type.
   *
   * <p>The answer is {@code true} only when every type the value may hold is incompatible with the
   * expected one. A single compatible member keeps the answer {@code false}, because a finding that
   * is suppressed on a probability is a finding that is lost.
   *
   * @param expected the type the sink needs, or null for none
   * @return whether the value cannot be of that type
   */
  public boolean isDefinitelyIncompatibleWith(TacType expected) {
    if (expected == null || kinds.isEmpty()) return false;
    if (containsUnknown()) return false;
    for (TacType.Kind kind : kinds) {
      if (TypeCompatibility.isAssignable(typeOfKind(kind), expected)) {
        return false;
      }
    }
    return true;
  }

  /**
   * A human-readable description of what the value may hold.
   *
   * @return the description
   */
  public String describe() {
    if (kinds.isEmpty()) return "no type";
    StringBuilder out = new StringBuilder();
    for (TacType.Kind kind : kinds) {
      if (out.length() > 0) out.append("|");
      out.append(kind.name().toLowerCase(java.util.Locale.ROOT));
      if (kind == TacType.Kind.CLASS && !classNames.isEmpty()) {
        out.append('(').append(String.join(",", classNames)).append(')');
      }
    }
    return out.toString();
  }

  /**
   * The type a kind stands for, when the kind is all that matters.
   *
   * <p>A CLASS kind keeps its name in {@link #classNames}; the compatibility rule does not look at
   * names (this IR has no subtype lattice), so the bare kind is enough here.
   *
   * @param kind the kind
   * @return the type
   */
  private static TacType typeOfKind(TacType.Kind kind) {
    return switch (kind) {
      case INT -> TacType.INT;
      case LONG -> TacType.LONG;
      case FLOAT -> TacType.FLOAT;
      case DOUBLE -> TacType.DOUBLE;
      case DECIMAL -> TacType.DECIMAL;
      case BOOLEAN -> TacType.BOOLEAN;
      case BYTE -> TacType.BYTE;
      case SHORT -> TacType.SHORT;
      case CHAR -> TacType.CHAR;
      case STRING -> TacType.STRING;
      case ARRAY -> TacType.arrayOf(TacType.UNKNOWN);
      case NULL -> TacType.NULL;
      case VOID -> TacType.VOID;
      case CLASS -> TacType.ofClass("java.lang.Object");
      default -> TacType.UNKNOWN;
    };
  }

  @Override
  public String toString() {
    return describe();
  }
}
