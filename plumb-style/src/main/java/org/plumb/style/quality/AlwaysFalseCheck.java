/*
 * R-813: AST-style CWE-570 (Expression is Always False) checker.
 *
 * <p>Fires on expressions that are provably always false:</p>
 * <ul>
 *   <li>{@code if (false)} / {@code while (false)}</li>
 *   <li>{@code 0 == 1} / {@code 1 == 0}</li>
 *   <li>{@code X == X + N} / {@code X != X + N} (X is identical)</li>
 *   <li>{@code X != X} (always false)</li>
 * </ul>
 *
 * <p>Replaces the plumb-dfa {@code ClassSourceChecker.AlwaysFalseChecker}
 * inner class (R-812 audit cleanup 2026-10-14).</p>
 *
 * <p><b>R-816 (2026-10-14): pattern D rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.findAll(IfStmt)} + {@code n.findAll(WhileStmt)} +
 * {@code n.findAll(BinaryExpr)} (3 AST walks per file). The new
 * implementation uses three per-node visit overloads dispatched
 * by the {@code AstDispatcher} in a single AST walk. 0 redundant
 * walks.</p>
 */
package org.plumb.style.quality;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class AlwaysFalseCheck implements AstChecker {

  private static final RuleDef RULE =
      new RuleDef(
          new RuleId("ast-quality-always-false", "ALWAYS_FALSE"),
          "Expression is always false (CWE-570)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-570"),
          "org.plumb.style.quality.AlwaysFalseCheck",
          true,
          List.of("ast-quality-always-false", "always-false")
      );

  @Override
  public String name() {
    return "always-false";
  }

  /** Per-file dedup set; reset in {@link #init(StyleContext)}. */
  private final Set<String> fired = new HashSet<>();

  @Override
  public void init(StyleContext ctx) {
    fired.clear();
  }

  @Override
  public void visit(IfStmt s, StyleContext ctx) {
    if (!isFalseLiteral(s.getCondition())) return;
    int line = s.getBegin().map(p -> p.line).orElse(-1);
    if (!fired.add(line + "|if-false")) return;
    ctx.report(
        line, -1, RULE, "if (false) is unreachable (CWE-570)", RuleDef.Severity.LOW, "if (false)");
  }

  @Override
  public void visit(WhileStmt s, StyleContext ctx) {
    if (!isFalseLiteral(s.getCondition())) return;
    int line = s.getBegin().map(p -> p.line).orElse(-1);
    if (!fired.add(line + "|while-false")) return;
    ctx.report(
        line,
        -1,
        RULE,
        "while (false) body never executes (CWE-570)",
        RuleDef.Severity.LOW,
        "while (false)");
  }

  @Override
  public void visit(BinaryExpr e, StyleContext ctx) {
    if (!isLiteralFalse(e)) return;
    int line = e.getBegin().map(p -> p.line).orElse(-1);
    if (!fired.add(line + "|" + e.toString())) return;
    ctx.report(
        line,
        -1,
        RULE,
        "Expression `" + e.toString() + "` is always false (CWE-570).",
        RuleDef.Severity.LOW,
        e.toString());
  }

  private static boolean isFalseLiteral(Expression e) {
    return e instanceof BooleanLiteralExpr ble && !ble.getValue();
  }

  private static boolean isLiteralFalse(BinaryExpr e) {
    BinaryExpr.Operator op = e.getOperator();
    if (op != BinaryExpr.Operator.EQUALS && op != BinaryExpr.Operator.NOT_EQUALS) {
      return false;
    }
    // 0 == 1 / 1 == 0
    if (isInt(e.getLeft(), 0) && isInt(e.getRight(), 1)) return true;
    if (isInt(e.getLeft(), 1) && isInt(e.getRight(), 0)) return true;
    // X == X + N (with N any int) / X != X
    return isSelfRef(e.getLeft(), e.getRight()) || isSelfRef(e.getRight(), e.getLeft());
  }

  private static boolean isSelfRef(Expression lhs, Expression rhs) {
    String l = bareName(lhs);
    if (l == null) return false;
    if (rhs instanceof BinaryExpr inner) {
      // X + N or X - N
      if (inner.getOperator() != BinaryExpr.Operator.PLUS
          && inner.getOperator() != BinaryExpr.Operator.MINUS) {
        return false;
      }
      if (l.equals(bareName(inner.getLeft())) && inner.getRight() instanceof IntegerLiteralExpr) {
        return true;
      }
      if (l.equals(bareName(inner.getRight())) && inner.getLeft() instanceof IntegerLiteralExpr) {
        return true;
      }
      return false;
    }
    if (rhs instanceof EnclosedExpr enc) {
      return isSelfRef(lhs, enc.getInner());
    }
    if (rhs instanceof UnaryExpr u && u.getOperator() == UnaryExpr.Operator.MINUS) {
      return isSelfRef(lhs, u.getExpression());
    }
    // X != X  (X is bare name on both sides, no op)
    return l.equals(bareName(rhs));
  }

  private static boolean isInt(Expression e, int v) {
    if (e instanceof IntegerLiteralExpr ili) {
      try {
        return Integer.parseInt(ili.getValue()) == v;
      } catch (NumberFormatException ex) {
        return false;
      }
    }
    if (e instanceof UnaryExpr u && u.getOperator() == UnaryExpr.Operator.MINUS) {
      return isInt(u.getExpression(), -v);
    }
    return false;
  }

  private static String bareName(Expression e) {
    if (e instanceof NameExpr ne) return ne.getNameAsString();
    return null;
  }
}
