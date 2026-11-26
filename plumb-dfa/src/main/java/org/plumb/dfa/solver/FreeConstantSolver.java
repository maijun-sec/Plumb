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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a predicate is satisfiable when every identifier in it is a <em>free integer
 * constant</em>.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A path condition asks "can this source reach this sink?", and the answer is a question about
 * satisfiability: the identifiers in it stand for values the program has not pinned down, so the
 * condition is satisfiable when SOME assignment of those values satisfies it. The real Z3 bridge
 * answers that question by handing the formula to Z3 with the identifiers as free constants, which
 * is why it can say UNSAT about {@code x > 0 && x <= 0}.
 *
 * <p>The pure-Java bridge could not: it <em>evaluates</em> a predicate against concrete bindings,
 * and an identifier it was not given is a value it cannot produce, so it answered "satisfiable" for
 * every formula. The two bridges therefore answered <em>different questions</em> under the same
 * method name, and the engine's behaviour depended on whether the machine had the native library.
 *
 * <p>This class is the pure-Java answer to the satisfiability question, so that the two bridges can
 * be made to agree.
 *
 * <h2>What it decides, and what it refuses</h2>
 *
 * <p>The decision procedure is exact for the shape the engine actually produces: a conjunction of
 * comparisons between one identifier and one numeric literal, optionally under a disjunction.
 *
 * <ul>
 *   <li>Each identifier's constraints are collected into an inclusive lower bound, an inclusive
 *       upper bound, and a set of excluded values ({@code !=}).
 *   <li>The identifier's constraints are satisfiable when the interval is not empty and at least
 *       one value in it is not excluded.
 *   <li>Identifiers are independent, so a conjunction is satisfiable when every identifier's
 *       constraints are, and a disjunction is satisfiable when one of its sides is.
 * </ul>
 *
 * <p>Anything outside that shape -- arithmetic on identifiers, comparisons between two identifiers,
 * a non-numeric literal -- yields {@link Outcome#UNKNOWN}, and the caller reads UNKNOWN as
 * "satisfiable" because the path might be reachable. A comparison against a non-numeric literal
 * ({@code x != null}) is <em>ignored</em> rather than refused: it says something about nullness and
 * nothing about the number, so it cannot make the numeric question harder.
 *
 * <h2>The direction of the whole thing</h2>
 *
 * <p>This class must never say UNSAT for a formula that IS satisfiable: that would suppress a
 * finding on a path that exists. Every refusal is therefore in the direction of "cannot decide",
 * never "cannot be satisfied".
 */
public final class FreeConstantSolver {

  /** The answer. */
  public enum Outcome {
    /** Some assignment of the free constants satisfies the predicate. */
    SAT,
    /** No assignment satisfies the predicate. */
    UNSAT,
    /** The predicate is outside the shape this class decides. */
    UNKNOWN
  }

  /** The most disjuncts the DNF may hold before the answer becomes "cannot decide". */
  private static final int MAX_DISJUNCTS = 512;

  private FreeConstantSolver() {}

  /**
   * Decide whether {@code predicate} is satisfiable over free integer constants.
   *
   * @param predicate the predicate, in the {@link LocalSolverBridge} grammar
   * @return the answer; {@link Outcome#UNKNOWN} when the predicate is outside the decided shape
   */
  public static Outcome check(String predicate) {
    if (predicate == null || predicate.isBlank()) return Outcome.SAT;
    try {
      List<String> tokens = tokenize(predicate);
      if (tokens == null) return Outcome.UNKNOWN;
      List<List<Atom>> dnf = new Parser(tokens).parse();
      if (dnf == null) return Outcome.UNKNOWN;
      if (dnf.isEmpty()) return Outcome.UNSAT;
      for (List<Atom> conjunct : dnf) {
        if (satisfiable(conjunct)) return Outcome.SAT;
      }
      return Outcome.UNSAT;
    } catch (RuntimeException ex) {
      // A predicate this class cannot parse is one it cannot decide.
      return Outcome.UNKNOWN;
    }
  }

  /**
   * Are these constraints satisfiable at once?
   *
   * <p>Every identifier is independent of the others, so each one is checked on its own.
   */
  private static boolean satisfiable(List<Atom> conjunct) {
    Map<String, Bounds> byVariable = new HashMap<>();
    for (Atom atom : conjunct) {
      Bounds bounds = byVariable.computeIfAbsent(atom.variable(), k -> new Bounds());
      bounds.apply(atom.operator(), atom.literal());
    }
    for (Bounds bounds : byVariable.values()) {
      if (!bounds.satisfiable()) return false;
    }
    return true;
  }

  // ─── one identifier's constraints ────────────────────────────────────

  /** The constraints one identifier carries: a range, and the values ruled out of it. */
  private static final class Bounds {
    private Long lower;
    private Long upper;
    private final Set<Long> excluded = new HashSet<>();

    void apply(String operator, long literal) {
      switch (operator) {
        case ">" -> lower = max(lower, step(literal, 1));
        case ">=" -> lower = max(lower, literal);
        case "<" -> upper = min(upper, step(literal, -1));
        case "<=" -> upper = min(upper, literal);
        case "==" -> {
          lower = max(lower, literal);
          upper = min(upper, literal);
        }
        case "!=" -> excluded.add(literal);
        default -> {
          // An operator this class does not model cannot tighten the bounds.
        }
      }
    }

    /**
     * The literal moved by one step, or {@code null} when the move overflows.
     *
     * <p>An overflowed bound would be a WRONG bound, and a wrong bound can make a satisfiable
     * constraint set look unsatisfiable — which would suppress a finding on a path that exists. So
     * an overflow drops the bound instead of inventing one.
     */
    private static Long step(long literal, int delta) {
      try {
        return Math.addExact(literal, delta);
      } catch (ArithmeticException overflow) {
        return null;
      }
    }

    boolean satisfiable() {
      if (lower != null && upper != null) {
        if (lower > upper) return false;
        long span;
        try {
          span = Math.subtractExact(upper, lower);
        } catch (ArithmeticException overflow) {
          // The interval is wider than a long can count, so it certainly holds a
          // value that is not excluded.
          return true;
        }
        int inside = 0;
        for (long value : excluded) {
          if (value >= lower && value <= upper) inside++;
        }
        // The interval holds span + 1 values; if every one of them is ruled out,
        // no value satisfies the constraints.
        return inside <= span;
      }
      // At least one end is unbounded, so the range holds more values than the
      // finite set of exclusions can cover.
      return true;
    }

    private static Long max(Long current, Long candidate) {
      if (candidate == null) return current;
      return current == null || candidate > current ? candidate : current;
    }

    private static Long min(Long current, Long candidate) {
      if (candidate == null) return current;
      return current == null || candidate < current ? candidate : current;
    }
  }

  // ─── the formula ─────────────────────────────────────────────────────

  /** One comparison: {@code variable operator literal}. */
  private record Atom(String variable, String operator, long literal) {}

  /** A node of the parsed formula. */
  private sealed interface Node permits Const, Compare, Not, And, Or {}

  /** A literal truth value. */
  private record Const(boolean value) implements Node {}

  /** A comparison between an identifier and a number. */
  private record Compare(String variable, String operator, long literal) implements Node {}

  /** A negation. */
  private record Not(Node inner) implements Node {}

  /** A conjunction. */
  private record And(Node left, Node right) implements Node {}

  /** A disjunction. */
  private record Or(Node left, Node right) implements Node {}

  // ─── the parser ──────────────────────────────────────────────────────

  /**
   * Parses the predicate into a disjunction of conjunctions.
   *
   * <p>The grammar accepted here is deliberately narrower than {@link LocalSolverBridge}'s: only a
   * comparison between one identifier and one number, and the boolean operators over them. Anything
   * else makes the parse return {@code null}, which the caller reads as "cannot decide".
   */
  private static final class Parser {
    private final List<String> tokens;
    private int pos;

    Parser(List<String> tokens) {
      this.tokens = tokens;
    }

    /** The formula as a disjunction of conjunctions, or {@code null} when it is not decidable. */
    List<List<Atom>> parse() {
      Node node = parseOr();
      if (node == null) return null;
      if (!atEnd()) return null;
      List<List<Atom>> dnf = toDnf(node, false);
      if (dnf == null) return null;
      if (dnf.size() > MAX_DISJUNCTS) return null;
      return dnf;
    }

    private Node parseOr() {
      Node left = parseAnd();
      if (left == null) return null;
      while (peekIs("||")) {
        pos++;
        Node right = parseAnd();
        if (right == null) return null;
        left = new Or(left, right);
      }
      return left;
    }

    private Node parseAnd() {
      Node left = parseUnary();
      if (left == null) return null;
      while (peekIs("&&")) {
        pos++;
        Node right = parseUnary();
        if (right == null) return null;
        left = new And(left, right);
      }
      return left;
    }

    private Node parseUnary() {
      if (peekIs("!")) {
        pos++;
        Node inner = parseUnary();
        return inner == null ? null : new Not(inner);
      }
      if (peekIs("(")) {
        pos++;
        Node inner = parseOr();
        if (inner == null) return null;
        if (!peekIs(")")) return null;
        pos++;
        return inner;
      }
      return parseComparison();
    }

    private Node parseComparison() {
      String left = nextToken();
      if (left == null) return null;
      String operator = nextToken();
      if (operator == null) return null;
      if (!isComparison(operator)) return null;
      String right = nextToken();
      if (right == null) return null;

      boolean leftIsIdentifier = isIdentifier(left);
      boolean rightIsIdentifier = isIdentifier(right);
      Long leftNumber = asNumber(left);
      Long rightNumber = asNumber(right);

      if (leftIsIdentifier && rightNumber != null) {
        return new Compare(left, operator, rightNumber);
      }
      if (rightIsIdentifier && leftNumber != null) {
        // The literal is on the left: `5 < x` says `x > 5`.
        return new Compare(right, flip(operator), leftNumber);
      }
      if (!leftIsIdentifier && !rightIsIdentifier && leftNumber != null && rightNumber != null) {
        // A comparison of two numbers is a fact about the numbers, not about a
        // free constant: it is either true or false on its own.
        boolean holds = holds(operator, leftNumber, rightNumber);
        return new Const(holds);
      }
      // Anything else -- two identifiers, a boolean literal, a string -- is
      // outside the shape this class decides.
      return null;
    }

    private String nextToken() {
      if (pos >= tokens.size()) return null;
      return tokens.get(pos++);
    }

    private boolean peekIs(String token) {
      return pos < tokens.size() && tokens.get(pos).equals(token);
    }

    private boolean atEnd() {
      return pos >= tokens.size();
    }
  }

  /**
   * The formula as a disjunction of conjunctions.
   *
   * <p>A negation is pushed inward as the tree is walked (De Morgan), so the result is a list of
   * conjuncts, each a list of atoms.
   *
   * @param negated whether the node is under an odd number of negations
   * @return the disjuncts, or {@code null} when the expansion grew past the cap
   */
  private static List<List<Atom>> toDnf(Node node, boolean negated) {
    if (node instanceof Const c) {
      boolean value = c.value() ^ negated;
      // A true formula is satisfied by the empty conjunction; a false one by none.
      return value ? List.of(List.of()) : List.of();
    }
    if (node instanceof Compare cmp) {
      String operator = negated ? negate(cmp.operator()) : cmp.operator();
      return List.of(List.of(new Atom(cmp.variable(), operator, cmp.literal())));
    }
    if (node instanceof Not not) {
      return toDnf(not.inner(), !negated);
    }
    if (node instanceof And and) {
      if (negated) {
        // NOT (a AND b) == (NOT a) OR (NOT b)
        List<List<Atom>> left = toDnf(and.left(), true);
        List<List<Atom>> right = toDnf(and.right(), true);
        if (left == null || right == null) return null;
        List<List<Atom>> out = new ArrayList<>(left);
        out.addAll(right);
        return out;
      }
      List<List<Atom>> left = toDnf(and.left(), false);
      List<List<Atom>> right = toDnf(and.right(), false);
      if (left == null || right == null) return null;
      List<List<Atom>> out = new ArrayList<>(left.size() * right.size());
      for (List<Atom> a : left) {
        for (List<Atom> b : right) {
          List<Atom> merged = new ArrayList<>(a);
          merged.addAll(b);
          out.add(merged);
        }
      }
      return out;
    }
    if (node instanceof Or or) {
      if (negated) {
        // NOT (a OR b) == (NOT a) AND (NOT b)
        List<List<Atom>> left = toDnf(or.left(), true);
        List<List<Atom>> right = toDnf(or.right(), true);
        if (left == null || right == null) return null;
        List<List<Atom>> out = new ArrayList<>(left.size() * right.size());
        for (List<Atom> a : left) {
          for (List<Atom> b : right) {
            List<Atom> merged = new ArrayList<>(a);
            merged.addAll(b);
            out.add(merged);
          }
        }
        return out;
      }
      List<List<Atom>> left = toDnf(or.left(), false);
      List<List<Atom>> right = toDnf(or.right(), false);
      if (left == null || right == null) return null;
      List<List<Atom>> out = new ArrayList<>(left);
      out.addAll(right);
      return out;
    }
    return null;
  }

  /** The comparison that is the negation of {@code operator}. */
  private static String negate(String operator) {
    switch (operator) {
      case "<":
        return ">=";
      case "<=":
        return ">";
      case ">":
        return "<=";
      case ">=":
        return "<";
      case "==":
        return "!=";
      case "!=":
        return "==";
      default:
        return operator;
    }
  }

  /** The comparison that means the same thing when the sides are swapped. */
  private static String flip(String operator) {
    switch (operator) {
      case "<":
        return ">";
      case "<=":
        return ">=";
      case ">":
        return "<";
      case ">=":
        return "<=";
      default:
        return operator;
    }
  }

  private static boolean holds(String operator, long left, long right) {
    switch (operator) {
      case "==":
        return left == right;
      case "!=":
        return left != right;
      case "<":
        return left < right;
      case "<=":
        return left <= right;
      case ">":
        return left > right;
      case ">=":
        return left >= right;
      default:
        return false;
    }
  }

  private static boolean isComparison(String token) {
    switch (token) {
      case "==":
      case "!=":
      case "<":
      case "<=":
      case ">":
      case ">=":
        return true;
      default:
        return false;
    }
  }

  private static boolean isIdentifier(String token) {
    if (token == null || token.isEmpty()) return false;
    char first = token.charAt(0);
    if (!(Character.isLetter(first) || first == '_')) return false;
    for (int i = 1; i < token.length(); i++) {
      char c = token.charAt(i);
      if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
    }
    return true;
  }

  private static Long asNumber(String token) {
    if (token == null) return null;
    try {
      return Long.parseLong(token.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Split the predicate into tokens.
   *
   * <p>Two-character operators come first, so {@code <=} is not read as {@code <} followed by
   * {@code =}.
   */
  private static List<String> tokenize(String source) {
    List<String> out = new ArrayList<>();
    int i = 0;
    int n = source.length();
    while (i < n) {
      char c = source.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (i + 1 < n) {
        String two = source.substring(i, i + 2);
        if (two.equals("&&")
            || two.equals("||")
            || two.equals("==")
            || two.equals("!=")
            || two.equals("<=")
            || two.equals(">=")) {
          out.add(two);
          i += 2;
          continue;
        }
      }
      if ("()!<>".indexOf(c) >= 0) {
        out.add(String.valueOf(c));
        i++;
        continue;
      }
      if (Character.isDigit(c)
          || (c == '-' && i + 1 < n && Character.isDigit(source.charAt(i + 1)))) {
        int start = i;
        if (c == '-') i++;
        while (i < n && Character.isDigit(source.charAt(i))) i++;
        out.add(source.substring(start, i));
        continue;
      }
      if (Character.isLetter(c) || c == '_') {
        int start = i;
        while (i < n) {
          char cj = source.charAt(i);
          if (!Character.isLetterOrDigit(cj) && cj != '_') break;
          i++;
        }
        out.add(source.substring(start, i).toLowerCase(Locale.ROOT));
        continue;
      }
      // A character this grammar does not use makes the predicate undecidable.
      return null;
    }
    return out;
  }
}
