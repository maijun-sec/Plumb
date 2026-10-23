package org.plumb.dfa.solver;

import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.IntExpr;
import com.microsoft.z3.IntNum;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import java.util.Map;
import java.util.Set;

/**
 * Real Z3 native bridge.
 *
 * <p>Wraps the {@code com.microsoft.z3} package (vendored as {@code tools.aqua:z3-turnkey:4.14.0})
 * to evaluate the same {@link SolverZ3Bridge} predicate DSL the {@link LocalSolverBridge} uses.
 * Production code can swap the bridge via {@link
 * org.plumb.dfa.taint.TaintedCheckerBase#setSolverBridge} without touching the checker logic.
 *
 * <h2>Why two bridges?</h2>
 *
 * <p>{@link LocalSolverBridge} is a pure-Java recursive-descent parser + interpreter. It always
 * works (no native lib required) but is bounded to the subset of expressions we explicitly handle
 * (numbers, strings, bool, basic arithmetic, comparisons). The Z3 bridge handles the same DSL by
 * re-using the {@link LocalSolverBridge}'s parser and feeding the resulting {@link BoolExpr} AST to
 * a real {@link com.microsoft.z3.Solver}. If the native Z3 lib is missing or the solver is
 * unreachable, the bridge falls back to {@link LocalSolverBridge}'s result so the caller never
 * blocks on a missing native lib.
 */
public final class Z3Bridge implements SolverZ3Bridge {

  private final SolverZ3Bridge fallback;

  public Z3Bridge() {
    this(new LocalSolverBridge());
  }

  public Z3Bridge(SolverZ3Bridge fallback) {
    this.fallback = fallback;
  }

  @Override
  public Result check(String predicate, Map<String, Object> bindings) {
    if (predicate == null || predicate.isBlank()) {
      return Result.SAT;
    }
    try (Context ctx = new Context()) {
      Solver solver = ctx.mkSolver();
      Z3AstBuilder builder = new Z3AstBuilder(ctx, bindings);
      BoolExpr ast;
      try {
        ast = builder.parse(predicate);
      } catch (Exception e) {
        // Malformed input — fall back
        // to the local solver.
        return fallback.check(predicate, bindings);
      }
      solver.add(ast);
      Status s = solver.check();
      switch (s) {
        case SATISFIABLE:
          return Result.SAT;
        case UNSATISFIABLE:
          return Result.UNSAT;
        case UNKNOWN:
          return Result.UNKNOWN;
        default:
          return Result.UNKNOWN;
      }
    } catch (Throwable t) {
      // Native Z3 not available
      // (ClassNotFoundException,
      // UnsatisfiedLinkError,
      // Z3Exception, etc.) — fall back
      // to the local solver so the
      // caller is never blocked.
      return fallback.check(predicate, bindings);
    }
  }

  @Override
  public Set<String> referencedVars(String predicate) {
    return fallback.referencedVars(predicate);
  }

  /**
   * Recursive-descent parser that emits Z3 expressions. Mirrors {@link LocalSolverBridge}'s grammar
   * exactly. {@code NUMBER} → Int, {@code STRING} → Int 0 (we don't model Z3's String theory here),
   * {@code BOOL} → Bool, {@code IDENT} → looked up in {@code bindings} (Int).
   */
  private static final class Z3AstBuilder {
    private final java.util.List<String> tokens = new java.util.ArrayList<>();
    private int pos;
    private final Context ctx;
    private final java.util.Map<String, Object> bindings;

    Z3AstBuilder(Context ctx, java.util.Map<String, Object> bindings) {
      this.ctx = ctx;
      this.bindings = bindings;
    }

    BoolExpr parse(String predicate) {
      tokens.clear();
      pos = 0;
      tokens.addAll(tokenize(predicate));
      return parseOr();
    }

    private BoolExpr parseOr() {
      BoolExpr left = parseAnd();
      while (pos < tokens.size() && tokens.get(pos).equals("||")) {
        pos++;
        BoolExpr right = parseAnd();
        left = ctx.mkOr(left, right);
      }
      return left;
    }

    private BoolExpr parseAnd() {
      BoolExpr left = parseNot();
      while (pos < tokens.size() && tokens.get(pos).equals("&&")) {
        pos++;
        BoolExpr right = parseNot();
        left = ctx.mkAnd(left, right);
      }
      return left;
    }

    private BoolExpr parseNot() {
      if (pos < tokens.size() && tokens.get(pos).equals("!")) {
        pos++;
        return ctx.mkNot(parseNot());
      }
      return parseCmp();
    }

    private BoolExpr parseCmp() {
      Expr left = parseAddSub();
      if (pos >= tokens.size()) {
        return ctx.mkEq(asBool(left), ctx.mkTrue());
      }
      String op = tokens.get(pos);
      if (op.equals("==")
          || op.equals("!=")
          || op.equals("<")
          || op.equals("<=")
          || op.equals(">")
          || op.equals(">=")) {
        pos++;
        Expr right = parseAddSub();
        if (op.equals("==")) {
          return ctx.mkEq(left, right);
        } else if (op.equals("!=")) {
          return ctx.mkNot(ctx.mkEq(left, right));
        } else if (op.equals("<")) {
          return ctx.mkLt(asInt(left), asInt(right));
        } else if (op.equals("<=")) {
          return ctx.mkLe(asInt(left), asInt(right));
        } else if (op.equals(">")) {
          return ctx.mkGt(asInt(left), asInt(right));
        } else {
          return ctx.mkGe(asInt(left), asInt(right));
        }
      }
      return ctx.mkEq(asBool(left), ctx.mkTrue());
    }

    private Expr parseAddSub() {
      Expr left = parseMulDiv();
      while (pos < tokens.size() && (tokens.get(pos).equals("+") || tokens.get(pos).equals("-"))) {
        String op = tokens.get(pos);
        pos++;
        Expr right = parseMulDiv();
        if (op.equals("+")) {
          left = ctx.mkAdd(asInt(left), asInt(right));
        } else {
          left = ctx.mkSub(asInt(left), asInt(right));
        }
      }
      return left;
    }

    private Expr parseMulDiv() {
      Expr left = parseAtom();
      while (pos < tokens.size() && (tokens.get(pos).equals("*") || tokens.get(pos).equals("/"))) {
        String op = tokens.get(pos);
        pos++;
        Expr right = parseAtom();
        if (op.equals("*")) {
          left = ctx.mkMul(asInt(left), asInt(right));
        } else {
          left = ctx.mkDiv(asInt(left), asInt(right));
        }
      }
      return left;
    }

    private Expr parseAtom() {
      if (pos >= tokens.size()) {
        return ctx.mkInt(0);
      }
      String tok = tokens.get(pos++);
      if (tok.equals("(")) {
        Expr e = parseAddSub();
        if (pos < tokens.size() && tokens.get(pos).equals(")")) {
          pos++;
        }
        return e;
      }
      if (tok.equals("true")) return ctx.mkTrue();
      if (tok.equals("false")) return ctx.mkFalse();
      // String literal.
      if (tok.startsWith("\"")) {
        return ctx.mkInt(tok.length());
      }
      try {
        long v = Long.parseLong(tok);
        return ctx.mkInt(v);
      } catch (NumberFormatException ignore) {
        Object v = bindings.get(tok);
        if (v instanceof Long lv) {
          return ctx.mkInt(lv);
        }
        if (v instanceof Number nv) {
          return ctx.mkInt(nv.longValue());
        }
        if (v instanceof Boolean bv) {
          return bv ? ctx.mkTrue() : ctx.mkFalse();
        }
        if (v instanceof String sv) {
          return ctx.mkInt(sv.length());
        }
        return ctx.mkIntConst(tok);
      }
    }

    private IntExpr asInt(Expr e) {
      if (e instanceof IntExpr) return (IntExpr) e;
      if (e instanceof IntNum) return (IntNum) e;
      if (e instanceof BoolExpr) {
        return (IntExpr) ctx.mkITE((BoolExpr) e, ctx.mkInt(1), ctx.mkInt(0));
      }
      if (e instanceof ArithExpr) {
        // Real / etc. — fall back to 0.
        return ctx.mkInt(0);
      }
      return ctx.mkInt(0);
    }

    private BoolExpr asBool(Expr e) {
      if (e instanceof BoolExpr) return (BoolExpr) e;
      if (e instanceof IntNum) {
        long v = ((IntNum) e).getInt();
        return v != 0 ? ctx.mkTrue() : ctx.mkFalse();
      }
      if (e instanceof IntExpr) {
        return ctx.mkEq(e, ctx.mkInt(0));
      }
      if (e instanceof ArithExpr) {
        return ctx.mkEq(e, ctx.mkInt(0));
      }
      return ctx.mkFalse();
    }
  }

  private static java.util.List<String> tokenize(String s) {
    java.util.List<String> out = new java.util.ArrayList<>();
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
        continue;
      }
      if (i + 1 < s.length()) {
        String two = s.substring(i, i + 2);
        if (two.equals("||")
            || two.equals("&&")
            || two.equals("==")
            || two.equals("!=")
            || two.equals("<=")
            || two.equals(">=")) {
          out.add(two);
          i += 2;
          continue;
        }
      }
      if ("+-*/()!<>".indexOf(c) >= 0) {
        out.add(String.valueOf(c));
        i++;
        continue;
      }
      if (c == '"' || c == '\'') {
        char quote = c;
        int j = i + 1;
        StringBuilder sb = new StringBuilder();
        while (j < s.length() && s.charAt(j) != quote) {
          sb.append(s.charAt(j));
          j++;
        }
        out.add("\"" + sb + "\"");
        i = Math.min(j + 1, s.length());
        continue;
      }
      int j = i;
      while (j < s.length()) {
        char cj = s.charAt(j);
        if (Character.isWhitespace(cj) || "+-*/()!<>\"'".indexOf(cj) >= 0) {
          break;
        }
        j++;
      }
      out.add(s.substring(i, j));
      i = j;
    }
    return out;
  }
}
