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
/*
 * FieldOpClassifier: central
 * field-classification heuristic.
 *
 * <p>The field-taint rounds each have a
 * small piece of the
 * "is this TacVar a class? is this
 * String a class name? is this
 * TacType a user-arg value?"
 * logic. This class consolidates the
 * shared heuristics into a single
 * utility class so callers can use one
 * helper instead of four near-duplicate
 * private static methods scattered
 * across
 * {@link TaintedCheckerBase}.</p>
 *
 * <p>All helpers are
 * <b>stateless</b> and
 * <b>side-effect-free</b>; the
 * class exists purely to give
 * the heuristic a stable home and
 * a focused test surface.</p>
 */
package org.plumb.dfa.taint;

import java.util.Set;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

public final class FieldOpClassifier {

  private FieldOpClassifier() {
    // utility
  }

  /**
   * Heuristic: a string is likely a class name when it starts with an uppercase letter. Plumb's IR
   * builder encodes static field access (e.g. {@code Helper.config}) as a chained field access
   * {@code this.Helper}, and the Java "class names start with uppercase" convention is universally
   * followed in real code. Returns {@code false} for null/empty strings and for {@code "this"} /
   * receiver-style lowercase names.
   */
  public static boolean isLikelyClassName(String s) {
    if (s == null || s.isEmpty()) return false;
    return Character.isUpperCase(s.charAt(0));
  }

  /**
   * Extract a class name from a {@link TacType}. Returns the FQN-ish {@link TacType#toString()}
   * (which is what JavaParser returns for user classes) for {@link TacType.Kind#CLASS} types, and
   * {@code null} for non-class types (primitives, arrays, UNKNOWN). The result keys the per-class
   * instance-field tracker and the cross-fn {@code "ClassName.field"} map.
   */
  public static String classNameOfType(TacType t) {
    if (t == null) return null;
    if (t.kind() != TacType.Kind.CLASS) return null;
    return t.toString();
  }

  /**
   * Is this {@link TacType} likely a user-visible argument value type (e.g. {@code String}, a
   * primitive wrapper, an array)? The IR builder encodes {@code String u} as {@code
   * TacType.ofClass("java.lang.String")} (which is {@code Kind.CLASS} but a value type, not a class
   * to look up). This helper distinguishes "real" class-typed TacVars (e.g. {@code Helper}) from
   * value-typed TacVars encoded as CLASS for the IR's uniformity. Used by the cross-fn
   * receiver-name detection in {@code propagateTaintFromCalleeSummary} to decide whether {@code
   * args[0]} is a receiver (class type) or a same-class user arg (value type).
   *
   * <p>The check uses {@code java.*} / {@code javax.*} / {@code jdk.*} prefix matching. Any FQN
   * under one of the well-known packages is treated as a value type (e.g. {@code java.util.HashMap}
   * is a value type, not a class to look up).
   */
  public static boolean isLikelyUserArgType(TacType t) {
    if (t == null) return true;
    if (t.kind() != TacType.Kind.CLASS) return true;
    String fqn = t.className();
    if (fqn == null) return true;
    for (String prefix : USER_ARG_PACKAGE_PREFIXES) {
      if (fqn.startsWith(prefix)) return true;
    }
    return false;
  }

  /**
   * Resolve the receiver name of a field-access {@link TacOperand.TacVar}, handling the chained
   * {@code t = this.H; t.f = u} IR pattern. Returns:
   *
   * <ol>
   *   <li>the {@code className} from the class-ref map (set by {@link
   *       TaintedCheckerBase#recordClassRef} when a previous {@code this.H} FIELDLOAD was seen), if
   *       present;
   *   <li>otherwise the var's {@link TacOperand.TacVar#name()}.
   * </ol>
   *
   * Returns {@code null} for null/empty.
   */
  public static String resolveReceiverName(
      TacOperand.TacVar tv, java.util.Map<TacOperand.TacVar, String> classRefVars) {
    if (tv == null) return null;
    String n = tv.name();
    if (n == null) return null;
    if (classRefVars == null) return n;
    String className = classRefVars.get(tv);
    if (className != null) return className;
    return n;
  }

  /**
   * Common package prefixes for FQN class names that the IR builder encodes as {@code Kind.CLASS}
   * but are really value types (used as user-arg value types, not as receivers / class-typed
   * fields). Anything matching one of these prefixes is treated as a value type. Anything else with
   * {@code Kind.CLASS} is treated as a real class type.
   */
  private static final Set<String> USER_ARG_PACKAGE_PREFIXES =
      Set.of(
          "java.lang.",
          "java.util.",
          "java.io.",
          "java.nio.",
          "java.time.",
          "java.math.",
          "java.text.",
          "java.net.",
          "java.security.",
          "java.sql.",
          "javax.",
          "jdk.",
          "sun.",
          "com.sun.");
}
