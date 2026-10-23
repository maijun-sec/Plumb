package org.plumb.style.style;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** for-loop can be replaced with for-each. */
public final class ForLoopCanBeForeachCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-for-foreach", "FOR_FOREACH"),
          "for-loop can be replaced with for-each",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ForLoopCanBeForeachCheck",
          true,
          List.of("ast-style-loop-can-be-foreach", "for-foreach")
      );

  @Override
  public String name() {
    return "for-foreach";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    // Find the counter name.
    String counter = null;
    for (var init : n.getInitialization()) {
      if (init instanceof VariableDeclarationExpr vde) {
        for (VariableDeclarator v : vde.getVariables()) {
          counter = v.getNameAsString();
        }
      }
    }
    if (counter == null) return;
    // Check that the body has `list.get(counter)`.
    for (var m : n.findAll(MethodCallExpr.class)) {
      if (!"get".equals(m.getNameAsString())) continue;
      if (m.getArguments().size() != 1) continue;
      if (!(m.getArgument(0) instanceof NameExpr ne)) continue;
      if (!ne.getNameAsString().equals(counter)) continue;
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "for-loop can be replaced with for-each",
          RuleDef.Severity.LOW,
          "for");
      return;
    }
  }
}
