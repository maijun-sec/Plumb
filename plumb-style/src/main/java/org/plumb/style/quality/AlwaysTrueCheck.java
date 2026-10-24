/*
 * R-813: AST-style CWE-571 (Expression is Always True) checker.
 *
 * <p>Fires on expressions that are provably always true:</p>
 * <ul>
 *   <li>{@code if (true)} / {@code while (true)}</li>
 *   <li>{@code 1 == 1} / {@code 0 == 0}</li>
 *   <li>{@code X == X} (always true)</li>
 * </ul>
 *
 * <p>Replaces the plumb-dfa {@code ClassSourceChecker.AlwaysTrueChecker}
 * inner class (R-812 audit cleanup 2026-10-14).</p>
 *
 * <p><b>R-816 (2026-10-14): pattern D rewrite.</b> Same rewrite as
 * {@link AlwaysFalseCheck}: per-node visit overloads
 * ({@code IfStmt} / {@code WhileStmt} / {@code BinaryExpr}) instead
 * of three {@code findAll} calls in {@code visit(CompilationUnit)}.
 * 3 walks per file → 0 redundant walks.</p>
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
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class AlwaysTrueCheck implements AstChecker {

  private static final RuleDef RULE =
      new RuleDef(
          new RuleId("ast-quality-always-true", "ALWAYS_TRUE"),
          "Expression is always true (CWE-571)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-571"),
          "org.plumb.style.quality.AlwaysTrueCheck",
          true,
          List.of("ast-quality-always-true", "always-true"));

  @Override
  public String name() {
    return "always-true";
  }

  /** Per-file dedup set; reset in {@link #init(StyleContext)}. */
  private final Set<String> fired = new HashSet<>();

  @Override
  public void init(StyleContext ctx) {
    fired.clear();
  }

  @Override
  public void visit(IfStmt s, StyleContext ctx) {
    if (!isTrueLiteral(s.getCondition())) return;
    int line = s.getBegin().map(p -> p.line).orElse(-1);
    if (!fired.add(line + "|if-true")) return;
    ctx.report(
        line, -1, RULE, "if (true) is unconditional (CWE-571)", RuleDef.Severity.LOW, "if (true)");
  }

  @Override
  public void visit(WhileStmt s, StyleContext ctx) {
    if (!isTrueLiteral(s.getCondition())) return;
    int line = s.getBegin().map(p -> p.line).orElse(-1);
    if (!fired.add(line + "|while-true")) return;
    ctx.report(
        line,
        -1,
        RULE,
        "while (true) is unconditional infinite loop (CWE-571)",
        RuleDef.Severity.LOW,
        "while (true)");
  }

  @Override
  public void visit(BinaryExpr e, StyleContext ctx) {
    if (!isLiteralTrue(e)) return;
    int line = e.getBegin().map(p -> p.line).orElse(-1);
    if (!fired.add(line + "|" + e.toString())) return;
    ctx.report(
        line,
        -1,
        RULE,
        "Expression `" + e.toString() + "` is always true (CWE-571).",
        RuleDef.Severity.LOW,
        e.toString());
  }

  private static boolean isTrueLiteral(Expression e) {
    return e instanceof BooleanLiteralExpr ble && ble.getValue();
  }

  private static boolean isLiteralTrue(BinaryExpr e) {
    BinaryExpr.Operator op = e.getOperator();
    if (op != BinaryExpr.Operator.EQUALS && op != BinaryExpr.Operator.NOT_EQUALS) {
      return false;
    }
    // 1 == 1 / 0 == 0
    if (isInt(e.getLeft(), 1) && isInt(e.getRight(), 1)) return true;
    if (isInt(e.getLeft(), 0) && isInt(e.getRight(), 0)) return true;
    // X == X  (X is bare name on both sides, no inner binop)
    String l = bareName(e.getLeft());
    if (l == null) return false;
    if (e.getRight() instanceof EnclosedExpr enc) {
      return isSelfRef(l, enc.getInner());
    }
    return l.equals(bareName(e.getRight()));
  }

  private static boolean isSelfRef(String leftName, Expression rhs) {
    if (rhs instanceof BinaryExpr inner) {
      if (inner.getOperator() != BinaryExpr.Operator.PLUS
          && inner.getOperator() != BinaryExpr.Operator.MINUS) {
        return false;
      }
      if (leftName.equals(bareName(inner.getLeft()))
          && inner.getRight() instanceof IntegerLiteralExpr) {
        return true;
      }
      if (leftName.equals(bareName(inner.getRight()))
          && inner.getLeft() instanceof IntegerLiteralExpr) {
        return true;
      }
      return false;
    }
    if (rhs instanceof UnaryExpr u && u.getOperator() == UnaryExpr.Operator.MINUS) {
      return isSelfRef(leftName, u.getExpression());
    }
    return leftName.equals(bareName(rhs));
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
