package org.plumb.style.performance;

import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** unnecessary cast. */
public final class UnnecessaryCastCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-unnecessary-cast", "UNNECESSARY_CAST"),
          "unnecessary cast",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnnecessaryCastCheck",
          true,
          List.of("ast-performance-unnecessary-cast", "unnecessary-cast")
      );

  @Override
  public String name() {
    return "unnecessary-cast";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CastExpr n, StyleContext ctx) {
    // The expression inside a cast is
    // n.getExpression(). The cast type is
    // n.getType(). The check fires when the
    // expression's name is the same as the
    // cast type's name (heuristic : the
    // symbol resolver would be needed for
    // a fully correct check).
    String castName =
        n.getType() instanceof PrimitiveType pt
            ? pt.asString()
            : n.getType() instanceof ClassOrInterfaceType ct ? ct.getNameAsString() : null;
    if (castName == null) return;
    String innerName =
        n.getExpression() instanceof com.github.javaparser.ast.expr.NameExpr ne
            ? ne.getNameAsString()
            : n.getExpression() instanceof CastExpr ic
                ? (ic.getType() instanceof ClassOrInterfaceType ct ? ct.getNameAsString() : null)
                : null;
    if (innerName == null) return;
    if (castName.equals(innerName)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Unnecessary cast to " + castName,
          RuleDef.Severity.LOW,
          castName);
    }
  }
}
