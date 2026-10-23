package org.plumb.style.performance;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** "use StringBuilder in loops instead of string. */
public final class UseStringBufferForStringAppendsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-use-stringbuffer", "USE_STRINGBUFFER"),
          "use StringBuilder in loops instead of string +",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.PERFORMANCE,
          List.of(),
          "org.plumb.style.UseStringBufferForStringAppendsCheck",
          true,
          List.of("ast-performance-stringbuffer-for-string-appends", "use-stringbuffer")
      );

  @Override
  public String name() {
    return "use-stringbuffer";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.PLUS) return;
    if (!isStringContext(n.getLeft()) && !isStringContext(n.getRight())) return;
    // Check if we're inside a loop.
    if (n.findAncestor(com.github.javaparser.ast.stmt.ForStmt.class).isEmpty()
        && n.findAncestor(com.github.javaparser.ast.stmt.ForEachStmt.class).isEmpty()
        && n.findAncestor(com.github.javaparser.ast.stmt.WhileStmt.class).isEmpty()) {
      return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use StringBuilder in loops : string + is O(n^2)",
        RuleDef.Severity.MEDIUM,
        "+");
  }

  private static boolean isStringContext(Expression e) {
    if (e instanceof StringLiteralExpr) return true;
    if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
      return isStringContext(b.getLeft()) || isStringContext(b.getRight());
    }
    return false;
  }
}
