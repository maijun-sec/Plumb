package org.plumb.style.naming;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.TypeExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** compare Class references with equals() not ==. */
public final class CompareClassNamesCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-compare-class-names", "COMPARE_CLASS_NAMES"),
          "compare Class references with equals() not ==",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-697"),
          "org.plumb.style.CompareClassNamesCheck",
          true,
          List.of("ast-naming-compare-classes", "compare-class-names"));

  @Override
  public String name() {
    return "compare-class-names";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.EQUALS
        && n.getOperator() != BinaryExpr.Operator.NOT_EQUALS) return;
    if (!isClassRef(n.getLeft()) || !isClassRef(n.getRight())) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Compare Class references with.equals() not ==",
        RuleDef.Severity.MEDIUM,
        "compare");
  }

  private boolean isClassRef(com.github.javaparser.ast.expr.Expression e) {
    if (e instanceof FieldAccessExpr fa) {
      return "class".equals(fa.getNameAsString());
    }
    if (e instanceof NameExpr ne) {
      // Heuristic: identifier that ends with "Class" or "Type"
      // is a typical Class reference (e.g. MyClass.class).
      return false; // bare name like `x == y` not detected
    }
    if (e instanceof TypeExpr) return true;
    return false;
  }
}
