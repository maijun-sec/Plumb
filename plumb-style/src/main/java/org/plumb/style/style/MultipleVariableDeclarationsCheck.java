package org.plumb.style.style;

import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** multiple variables in one declaration. */
public final class MultipleVariableDeclarationsCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-multiple-var-decls", "MULTIPLE_VAR_DECLS"),
          "multiple variables in one declaration",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MultipleVariableDeclarationsCheck",
          true,
          List.of("ast-style-multiple-variable-declarations", "multiple-var-decls"));

  @Override
  public String name() {
    return "multiple-var-decls";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarationExpr v, StyleContext ctx) {
    if (v.getVariables().size() > 1) {
      int line = v.getBegin().map(p -> p.line).orElse(-1);
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Declare one variable per statement: " + v.getVariables().size() + " vars",
          RuleDef.Severity.LOW,
          v.toString());
    }
  }
}
