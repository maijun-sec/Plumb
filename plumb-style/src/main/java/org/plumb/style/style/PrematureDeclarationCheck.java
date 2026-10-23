package org.plumb.style.style;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** variable declared without initializer. */
public final class PrematureDeclarationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-premature-declaration", "PREMATURE_DECLARATION"),
          "variable declared without initializer",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.PrematureDeclarationCheck",
          true,
          List.of("ast-style-premature-declaration", "premature-declaration")
      );

  @Override
  public String name() {
    return "premature-declaration";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarationExpr n, StyleContext ctx) {
    for (VariableDeclarator v : n.getVariables()) {
      if (v.getInitializer().isPresent()) continue;
      int line = v.getBegin().map(p -> p.line).orElse(-1);
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Variable '" + v.getNameAsString() + "' declared without initializer",
          RuleDef.Severity.LOW,
          v.getNameAsString());
    }
  }
}
