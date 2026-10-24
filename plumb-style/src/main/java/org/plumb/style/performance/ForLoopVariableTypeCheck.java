package org.plumb.style.performance;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.type.PrimitiveType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** for-loop counter must not be float/double. */
public final class ForLoopVariableTypeCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-for-loop-variable-type", "FOR_LOOP_VARIABLE_TYPE"),
          "for-loop counter must not be float/double",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-682"),
          "org.plumb.style.ForLoopVariableTypeCheck",
          true,
          List.of("ast-performance-loop-float", "for-loop-variable-type"));

  @Override
  public String name() {
    return "for-loop-variable-type";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    // ForStmt.getInitialization() returns NodeList<Expression>;
    // iterate directly.
    for (Expression init : n.getInitialization()) {
      if (!(init instanceof VariableDeclarationExpr vde)) continue;
      for (VariableDeclarator v : vde.getVariables()) {
        if (v.getType() instanceof PrimitiveType pt) {
          String code = pt.asString();
          if ("double".equals(code) || "float".equals(code)) {
            int line = v.getBegin().map(p -> p.line).orElse(-1);
            int col = v.getBegin().map(p -> p.column).orElse(-1);
            ctx.report(
                line,
                col,
                DEFAULT_RULE,
                "For-loop counter '" + v.getNameAsString() + "' is " + code + " : use int / long",
                RuleDef.Severity.MEDIUM,
                v.getNameAsString());
          }
        }
      }
    }
  }
}
