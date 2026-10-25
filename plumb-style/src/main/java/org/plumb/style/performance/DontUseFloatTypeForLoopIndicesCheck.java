package org.plumb.style.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Don't use {@code float} / {@code double} as a {@code for}-loop counter. */
public final class DontUseFloatTypeForLoopIndicesCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-no-float-loop-index", "NO_FLOAT_LOOP_INDEX"),
          "float/double for loop counter (strict)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DontUseFloatTypeForLoopIndicesCheck",
          true,
          List.of("ast-performance-loop-index-strict", "no-float-loop-index"));

  @Override
  public String name() {
    return "no-float-loop-index";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt f, StyleContext ctx) {
    for (Node init : f.getInitialization()) {
      if (!(init instanceof VariableDeclarationExpr vde)) continue;
      String type = vde.getElementType().asString();
      if (!"float".equals(type) && !"double".equals(type)) continue;
      int line = f.getBegin().map(p -> p.line).orElse(-1);
      int col = f.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line, col, DEFAULT_RULE, "Float/double for loop counter", RuleDef.Severity.LOW, type);
    }
  }
}
