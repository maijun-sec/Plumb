package org.plumb.dfa.solver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java fallback implementation of {@link SolverZ3Bridge}.
 *
 * <p>Handles a small arithmetic / boolean grammar that's enough for the common "is this path
 * reachable?" questions a path-condition validation step needs:
 *
 * <pre>
 *   expr  := orExpr
 *   orExpr  := andExpr ('||' andExpr)*
 *   andExpr := cmpExpr ('&amp;&amp;' cmpExpr)*
 *   cmpExpr := addExpr (CMP addExpr)?
 *   CMP := '==' | '!=' | '&lt;' | '&lt;=' | '&gt;' | '&gt;='
 *   addExpr := mulExpr (('+' | '-') mulExpr)*
 *   mulExpr := atom (('*' | '/') atom)*
 *   atom := NUMBER | BOOL | STRING | IDENT | '(' expr ')' | '!' atom
 * </pre>
 *
 * <p>Where:
 *
 * <ul>
 *   <li>{@code NUMBER} is a Java long literal.
 *   <li>{@code BOOL} is {@code true} or {@code false}.
 *   <li>{@code STRING} is a Java string literal.
 *   <li>{@code IDENT} is a Java identifier; its value is looked up in {@code bindings}.
 * </ul>
 *
 * <p>Variables referenced in the predicate but not bound are treated as <em>free</em>: the result
 * is {@link Result#SAT} (the path might be reachable). The bridge is intentionally conservative —
 * under-approximating satisfiability (saying "UNSAT" when the path is actually reachable) would
 * suppress true findings.
 *
 * <h2>Limitations</h2>
 *
 * <p>The local solver does NOT support quantifiers, arrays, or user-defined functions. For those,
 * the caller should use the future Z3 bridge. The local solver sticks to the arithmetic subset
 * because every path condition we currently generate (the mirror checkers' summaries) is in that
 * subset.
 */
public final class LocalSolverBridge implements SolverZ3Bridge {

  // Operators (longest first to avoid prefix collisions).
  private static final String[] OPS = {
    "==", "!=", "<=", ">=", "<", ">", "&&", "||", "+", "-", "*", "/"
  };

  @Override
  public Result check(String predicate, Map<String, Object> bindings) {
    if (predicate == null || predicate.isBlank()) {
      return Result.SAT;
    }
    Map<String, Object> env =
        bindings == null ? new LinkedHashMap<>() : new LinkedHashMap<>(bindings);
    try {
      Object v = parseOr(new ParserState(predicate.trim()), env);
      if (v instanceof Boolean b) {
        return b ? Result.SAT : Result.UNSAT;
      }
      // Non-boolean value (e.g. arithmetic
      // expression that didn't get
      // compared): treat as "truthy" → SAT.
      return Result.SAT;
    } catch (UnboundVariableException uve) {
      // Predicate references a variable
      // we can't bind. Conservative SAT —
      // the path might be reachable.
      return Result.SAT;
    } catch (UnsupportedOperationException uoe) {
      return Result.UNKNOWN;
    } catch (RuntimeException ex) {
      // Malformed predicate: conservative
      // SAT.
      return Result.SAT;
    }
  }

  /**
   * Sentinel value for an unbound variable. The parser substitutes this for any identifier that
   * isn't in the bindings map. Comparisons involving the sentinel throw {@link
   * UnboundVariableException}, which {@link #check} translates to {@link Result#SAT} (the
   * conservative "path might be reachable" answer).
   */
  private static final Object UNBOUND =
      new Object() {
        @Override
        public String toString() {
          return "<unbound>";
        }
      };

  /**
   * Internal exception signalling "a comparison hit an unbound variable". The bridge's public
   * {@link #check} catches this and returns SAT, never propagates it to callers.
   */
  private static final class UnboundVariableException extends RuntimeException {
    UnboundVariableException() {
      super(null, null, false, false);
    }
  }

  @Override
  public Set<String> referencedVars(String predicate) {
    if (predicate == null) return Set.of();
    java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
    Matcher m = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(predicate);
    while (m.find()) {
      String name = m.group();
      // Filter out Java keywords and
      // boolean literals that aren't
      // really variable names.
      if (name.equals("true") || name.equals("false") || name.equals("null")) {
        continue;
      }
      out.add(name);
    }
    return out;
  }

  // ─── Recursive descent parser ─────────────────

  private static Object parseOr(ParserState s, Map<String, Object> env) {
    Object left = parseAnd(s, env);
    while (true) {
      s.skipWs();
      if (s.peek().equals("||")) {
        s.advance(2);
        Object right = parseAnd(s, env);
        left = truthy(left) || truthy(right);
      } else {
        return left;
      }
    }
  }

  private static Object parseAnd(ParserState s, Map<String, Object> env) {
    Object left = parseCmp(s, env);
    while (true) {
      s.skipWs();
      if (s.peek().equals("&&")) {
        s.advance(2);
        Object right = parseCmp(s, env);
        left = truthy(left) && truthy(right);
      } else {
        return left;
      }
    }
  }

  private static Object parseCmp(ParserState s, Map<String, Object> env) {
    Object left = parseAdd(s, env);
    s.skipWs();
    for (String op : new String[] {"==", "!=", "<=", ">=", "<", ">"}) {
      if (s.peek().equals(op)) {
        s.advance(op.length());
        Object right = parseAdd(s, env);
        return compare(left, op, right);
      }
    }
    return left;
  }

  private static Object parseAdd(ParserState s, Map<String, Object> env) {
    Object left = parseMul(s, env);
    while (true) {
      s.skipWs();
      String p = s.peek();
      if (p.equals("+") || p.equals("-")) {
        s.advance(1);
        Object right = parseMul(s, env);
        if (left instanceof Long ll && right instanceof Long rr) {
          left = p.equals("+") ? ll + rr : ll - rr;
        } else if (left instanceof Double || right instanceof Double) {
          left = toDouble(left) + (p.equals("+") ? toDouble(right) : -toDouble(right));
        } else {
          left = toString(left) + (p.equals("+") ? toString(right) : "");
        }
      } else {
        return left;
      }
    }
  }

  private static Object parseMul(ParserState s, Map<String, Object> env) {
    Object left = parseAtom(s, env);
    while (true) {
      s.skipWs();
      String p = s.peek();
      if (p.equals("*") || p.equals("/")) {
        s.advance(1);
        Object right = parseAtom(s, env);
        if (left instanceof Long ll && right instanceof Long rr) {
          if (p.equals("*")) {
            left = ll * rr;
          } else {
            if (rr == 0L) {
              // Division by zero: not
              // representable, return
              // UNKNOWN from the caller.
              throw new UnsupportedOperationException("division by zero");
            }
            left = ll / rr;
          }
        } else {
          double ld = toDouble(left);
          double rd = toDouble(right);
          left = p.equals("*") ? ld * rd : ld / rd;
        }
      } else {
        return left;
      }
    }
  }

  private static Object parseAtom(ParserState s, Map<String, Object> env) {
    s.skipWs();
    if (s.eof()) {
      throw new RuntimeException("unexpected end of predicate");
    }
    String p = s.peek();
    if (p.equals("(")) {
      s.advance(1);
      Object v = parseOr(s, env);
      s.skipWs();
      if (s.eof() || !s.peek().equals(")")) {
        throw new RuntimeException("expected ')'");
      }
      s.advance(1);
      return v;
    }
    if (p.equals("!")) {
      s.advance(1);
      return !truthy(parseAtom(s, env));
    }
    if (isNumberStart(p)) {
      return s.readNumber();
    }
    if (p.equals("true") || p.equals("false")) {
      s.advance(p.length());
      return Boolean.parseBoolean(p);
    }
    if (p.equals("null")) {
      s.advance(4);
      return null;
    }
    if (p.startsWith("\"") || p.startsWith("'")) {
      return s.readString();
    }
    // Identifier: look up in env.
    String ident = s.readIdent();
    if (env.containsKey(ident)) {
      return env.get(ident);
    }
    // Unbound variable: substitute the
    // UNBOUND sentinel. Comparisons
    // involving the sentinel throw
    // UnboundVariableException, which the
    // bridge catches and returns SAT for.
    // The sentinel is unique per JVM
    // (private static) so the comparison
    // dispatch is safe.
    return UNBOUND;
  }

  // ─── Helpers ─────────────────────────────────

  private static boolean isNumberStart(String s) {
    if (s == null || s.isEmpty()) return false;
    char c = s.charAt(0);
    return Character.isDigit(c) || c == '-' || c == '+';
  }

  private static boolean truthy(Object o) {
    if (o instanceof Boolean b) return b;
    if (o == null) return false;
    if (o instanceof Long l) return l != 0L;
    if (o instanceof Double d) return d != 0.0;
    if (o instanceof String s) return !s.isEmpty();
    return true;
  }

  private static double toDouble(Object o) {
    if (o instanceof Long l) return l.doubleValue();
    if (o instanceof Double d) return d;
    if (o instanceof String s) {
      try {
        return Double.parseDouble(s);
      } catch (NumberFormatException nfe) {
        return 0.0;
      }
    }
    if (o instanceof Boolean b) return b ? 1.0 : 0.0;
    return 0.0;
  }

  private static String toString(Object o) {
    return o == null ? "" : o.toString();
  }

  private static Object compare(Object left, String op, Object right) {
    // Equality / inequality: handle nulls
    // and mixed types conservatively.
    if (op.equals("==")) {
      return eq(left, right);
    }
    if (op.equals("!=")) {
      return !eq(left, right);
    }
    // Ordering comparisons: both sides
    // must be numeric.
    if (!(left instanceof Number) || !(right instanceof Number)) {
      // Mixed-type comparison: if EITHER
      // side is an unbound placeholder
      // (sentinel — see parseAtom), we
      // can't decide, so the bridge's
      // catch-all returns SAT. Otherwise
      // fall back to String compare.
      if (left == UNBOUND || right == UNBOUND) {
        throw new UnboundVariableException();
      }
      int cmp = toString(left).compareTo(toString(right));
      return applyOrder(op, cmp);
    }
    if (left == UNBOUND || right == UNBOUND) {
      throw new UnboundVariableException();
    }
    double l = toDouble(left);
    double r = toDouble(right);
    int cmp = Double.compare(l, r);
    return applyOrder(op, cmp);
  }

  private static boolean applyOrder(String op, int cmp) {
    return switch (op) {
      case "<" -> cmp < 0;
      case "<=" -> cmp <= 0;
      case ">" -> cmp > 0;
      case ">=" -> cmp >= 0;
      default -> throw new RuntimeException("not an order op: " + op);
    };
  }

  private static boolean eq(Object a, Object b) {
    if (a == UNBOUND || b == UNBOUND) {
      throw new UnboundVariableException();
    }
    if (a == b) return true;
    if (a == null || b == null) return false;
    if (a instanceof Number && b instanceof Number) {
      return toDouble(a) == toDouble(b);
    }
    return Objects.equals(toString(a), toString(b));
  }

  // ─── Tiny parser-state helper ───────────────

  private static final class ParserState {
    final String src;
    int pos;

    ParserState(String s) {
      this.src = s;
      this.pos = 0;
    }

    boolean eof() {
      return pos >= src.length();
    }

    void skipWs() {
      while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
        pos++;
      }
    }

    String peek() {
      skipWs();
      if (eof()) return "";
      // Return the longest matching
      // operator that starts at pos, so
      // multi-char ops win over single.
      for (String op : OPS) {
        if (src.startsWith(op, pos)) return op;
      }
      return src.substring(pos, pos + 1);
    }

    void advance(int n) {
      pos += n;
    }

    String readIdent() {
      skipWs();
      int start = pos;
      while (pos < src.length()
          && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
        pos++;
      }
      return src.substring(start, pos);
    }

    Object readNumber() {
      skipWs();
      int start = pos;
      if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) {
        pos++;
      }
      while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
        pos++;
      }
      if (pos < src.length() && src.charAt(pos) == '.') {
        pos++;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
          pos++;
        }
        return Double.parseDouble(src.substring(start, pos));
      }
      return Long.parseLong(src.substring(start, pos));
    }

    Object readString() {
      skipWs();
      char quote = src.charAt(pos);
      pos++;
      int start = pos;
      while (pos < src.length() && src.charAt(pos) != quote) {
        pos++;
      }
      String s = src.substring(start, pos);
      if (pos < src.length()) pos++; // skip closing quote
      return s;
    }
  }
}
