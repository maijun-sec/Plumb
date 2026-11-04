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
package org.plumb.dfa.solver;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.plumb.tac.ir.BinaryOperator;

/**
 * Turns the guard facts a function has proved into a path condition the solver can read.
 *
 * <p>The solver's grammar is a small arithmetic and boolean language (see {@link
 * LocalSolverBridge}): comparisons of a variable against a literal, combined with {@code &&} and
 * {@code ||}. A guard is exactly that shape -- "this variable is bounded by this literal" -- so the
 * translation is mostly about rejecting the guards the grammar cannot express.
 *
 * <h2>What this class refuses to translate, and why</h2>
 *
 * <ul>
 *   <li><b>An array-length bound</b> ({@code data < array.length}). The grammar has no arrays and
 *       no function calls, so the bound cannot be written down. Translating it as a number would
 *       apply a bound to the wrong array, so it is dropped.
 *   <li><b>A non-numeric literal</b> (a string constant, a class name). The grammar compares
 *       numbers and booleans; a string would be silently mis-read.
 *   <li><b>A variable name the grammar cannot spell.</b> The solver's identifiers are ASCII
 *       letters, digits and underscores; a TAC variable name may be any of a much wider set, so the
 *       name is rewritten into a safe form.
 * </ul>
 *
 * <p>Every refusal is conservative: a guard that cannot be expressed is <i>omitted</i>, never
 * approximated. An omitted guard makes the path condition weaker, which can only cause a finding to
 * be reported -- it can never suppress one that should be reported.
 *
 * <h2>The direction of the whole mechanism</h2>
 *
 * <p>A path condition here is used to ask one question: "is this source-to-sink path actually
 * reachable?". The solver answers UNSAT when the guards on the path contradict each other, and only
 * then is the finding suppressed. So a false path condition costs a false positive, and a missing
 * one costs nothing but a finding that stands.
 */
public final class GuardPredicateTranslator {

  /** The largest number of predicates one condition may hold. */
  private static final int MAX_PREDICATES = 64;

  private GuardPredicateTranslator() {}

  /**
   * Translate a guard into a solver predicate.
   *
   * @param boundedVar the variable the guard constrains, in its original form
   * @param op the comparison operator
   * @param literal the right-hand side, as the tracker recorded it
   * @return the predicate, or {@code null} when the guard cannot be expressed
   */
  public static String translate(
      String boundedVar, BinaryOperator op, String literal, boolean arrayLengthBound) {
    if (boundedVar == null || boundedVar.isBlank()) return null;
    if (arrayLengthBound) return null;
    if (op == null) return null;
    String rhs = normaliseLiteral(literal);
    if (rhs == null) return null;
    String lhs = safeIdentifier(boundedVar);
    if (lhs == null) return null;
    String symbol = operatorSymbol(op);
    if (symbol == null) return null;
    return lhs + " " + symbol + " " + rhs;
  }

  /**
   * Build the conjunction of every guard in {@code guards}.
   *
   * <p>The result is the empty string when no guard could be expressed, which the caller reads as
   * "no path condition" -- and a caller with no path condition reports every finding.
   *
   * @param guards the guards, in the form {@code (var, op, literal, arrayLengthBound)}
   * @return the conjunction, or the empty string
   */
  public static String conjunction(List<GuardFact> guards) {
    if (guards == null || guards.isEmpty()) return "";
    // A set, because the same guard can be reached by two routes and repeating it buys
    // nothing but a longer query.
    Set<String> unique = new LinkedHashSet<>();
    for (GuardFact g : guards) {
      if (g == null) continue;
      String p = translate(g.variable(), g.operator(), g.literal(), g.arrayLengthBound());
      if (p != null) unique.add(p);
      if (unique.size() >= MAX_PREDICATES) break;
    }
    if (unique.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    for (String p : unique) {
      if (sb.length() > 0) sb.append(" && ");
      sb.append("(").append(p).append(")");
    }
    return sb.toString();
  }

  /**
   * One guard, in the shape the translator needs.
   *
   * @param variable the guarded variable's name
   * @param operator the comparison operator
   * @param literal the right-hand side, as the tracker recorded it
   * @param arrayLengthBound whether the bound is an array's length
   */
  public record GuardFact(
      String variable, BinaryOperator operator, String literal, boolean arrayLengthBound) {

    public GuardFact(String variable, BinaryOperator operator, String literal) {
      this(variable, operator, literal, false);
    }
  }

  /**
   * The solver's symbol for a comparison operator, or {@code null} when the operator is not a
   * comparison the grammar has.
   */
  static String operatorSymbol(BinaryOperator op) {
    switch (op) {
      case LT:
        return "<";
      case LE:
        return "<=";
      case GT:
        return ">";
      case GE:
        return ">=";
      case EQ:
        return "==";
      case NE:
        return "!=";
      default:
        return null;
    }
  }

  /**
   * Normalise a literal into something the solver can read, or {@code null} when it cannot.
   *
   * <p>Numbers pass through as they are. {@code null} and {@code true}/{@code false} become the
   * solver's own spellings. Anything else -- a string constant, a class name, an expression -- is
   * refused, because the grammar would read it as an unbound identifier and the query would be
   * about something else entirely.
   */
  static String normaliseLiteral(String literal) {
    if (literal == null) return null;
    String s = literal.trim();
    if (s.isEmpty()) return null;
    if (s.equalsIgnoreCase("null")) return "null";
    if (s.equalsIgnoreCase("true")) return "true";
    if (s.equalsIgnoreCase("false")) return "false";
    // A number, possibly signed, possibly with a decimal point.
    if (s.matches("[-+]?\\d+")) return s;
    if (s.matches("[-+]?\\d*\\.\\d+")) return s;
    return null;
  }

  /**
   * Rewrite a variable name into an identifier the solver's grammar accepts.
   *
   * <p>The grammar's identifiers are ASCII letters, digits and underscores. A TAC variable name can
   * be anything, so the name is transliterated: every character outside the safe set becomes an
   * underscore, and a leading digit is prefixed. Two different names can therefore collide, which
   * is why {@link #conjunction} keeps the guards in a set rather than merging them -- a collision
   * would weaken the condition, not strengthen it.
   *
   * @return the safe identifier, or {@code null} when the name cannot be used at all
   */
  static String safeIdentifier(String name) {
    if (name == null) return null;
    String s = name.trim();
    if (s.isEmpty()) return null;
    StringBuilder sb = new StringBuilder(s.length() + 1);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean safe =
          (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
      sb.append(safe ? c : '_');
    }
    if (sb.charAt(0) >= '0' && sb.charAt(0) <= '9') {
      sb.insert(0, '_');
    }
    String out = sb.toString();
    // A name made entirely of replacement characters carries no information, and the solver
    // would treat it as a free variable that is constrained by nothing.
    if (out.chars().allMatch(c -> c == '_')) return null;
    return out.toLowerCase(Locale.ROOT);
  }

  /** The predicates of a guard list, for the callers that want them one at a time. */
  public static List<String> predicates(List<GuardFact> guards) {
    List<String> out = new ArrayList<>();
    if (guards == null) return out;
    for (GuardFact g : guards) {
      if (g == null) continue;
      String p = translate(g.variable(), g.operator(), g.literal(), g.arrayLengthBound());
      if (p != null) out.add(p);
      if (out.size() >= MAX_PREDICATES) break;
    }
    return out;
  }

  /**
   * Find a pair of guards on the SAME variable whose bounds cannot both hold.
   *
   * <p>This is the one contradiction the engine can prove without a solver, and it is the one that
   * matters: {@code if (n > 5) { ... } if (n < 3) { ... }} means one of the two branches is
   * unreachable, so a source-to-sink flow that needs both is not a real flow.
   *
   * <p>It is deliberately narrow. It does not combine guards across variables, and it does not use
   * the full conjunction of the function's guards:
   *
   * <ul>
   *   <li>Combining across variables would be a claim about the joint state, which the
   *       flow-insensitive analysis does not have.
   *   <li>Using every guard ever seen would make the condition stronger than the path the finding
   *       actually took, and a stronger condition that happens to be UNSAT would suppress a real
   *       finding. Two guards recorded at different points in the function are not both in force at
   *       the sink.
   * </ul>
   *
   * <p>So the result is a contradiction <i>on one variable</i>, which is sound regardless of the
   * order the guards were recorded in.
   *
   * @param guards the guards to search
   * @return the conflicting pair, or {@code null} when no pair conflicts
   */
  public static GuardFact[] findContradiction(List<GuardFact> guards) {
    if (guards == null || guards.size() < 2) return null;
    for (int i = 0; i < guards.size(); i++) {
      GuardFact a = guards.get(i);
      if (a == null || a.arrayLengthBound()) continue;
      Long aLow = lowerBoundOf(a);
      Long aHigh = upperBoundOf(a);
      if (aLow == null && aHigh == null) continue;
      for (int j = i + 1; j < guards.size(); j++) {
        GuardFact b = guards.get(j);
        if (b == null || b.arrayLengthBound()) continue;
        if (!sameVariable(a, b)) continue;
        Long bLow = lowerBoundOf(b);
        Long bHigh = upperBoundOf(b);
        if (bLow == null && bHigh == null) continue;
        if (intervalsDisjoint(aLow, aHigh, bLow, bHigh)) {
          return new GuardFact[] {a, b};
        }
      }
    }
    return null;
  }

  /** The inclusive lower bound a guard implies, or {@code null} when it implies none. */
  private static Long lowerBoundOf(GuardFact g) {
    Long v = numericLiteral(g.literal());
    if (v == null) return null;
    switch (g.operator()) {
      case GT:
        return v + 1;
      case GE:
        return v;
      case EQ:
        return v;
      default:
        return null;
    }
  }

  /** The inclusive upper bound a guard implies, or {@code null} when it implies none. */
  private static Long upperBoundOf(GuardFact g) {
    Long v = numericLiteral(g.literal());
    if (v == null) return null;
    switch (g.operator()) {
      case LT:
        return v - 1;
      case LE:
        return v;
      case EQ:
        return v;
      default:
        return null;
    }
  }

  /**
   * Do the two guards' bounds leave no value that satisfies both?
   *
   * <p>A guard need not bound a variable on both sides. {@code n > 5} says nothing about how large
   * {@code n} may be, and {@code n < 3} says nothing about how small it may be. The two still
   * contradict each other when the LOWER bound of one is above the UPPER bound of the other,
   * because then no value satisfies both.
   *
   * <p>So the test is on the bounds that are known, not on the intervals being closed. A pair where
   * neither side has an upper bound cannot contradict: {@code n > 5} and {@code m > 3} say nothing
   * about each other, and {@code n > 5} against {@code n > 3} is satisfied by every {@code n} above
   * 5.
   */
  private static boolean intervalsDisjoint(Long aLow, Long aHigh, Long bLow, Long bHigh) {
    if (aLow != null && bHigh != null && aLow > bHigh) return true;
    if (bLow != null && aHigh != null && bLow > aHigh) return true;
    return false;
  }

  /** Do the two guards constrain the same variable? */
  private static boolean sameVariable(GuardFact a, GuardFact b) {
    String sa = safeIdentifier(a.variable());
    String sb = safeIdentifier(b.variable());
    return sa != null && sa.equals(sb);
  }

  /** The numeric value of a literal, or {@code null} when it is not a number. */
  private static Long numericLiteral(String literal) {
    if (literal == null) return null;
    String s = literal.trim();
    if (!s.matches("[-+]?\\d+")) return null;
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
