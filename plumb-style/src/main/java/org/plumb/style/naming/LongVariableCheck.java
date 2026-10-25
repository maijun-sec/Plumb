package org.plumb.style.naming;

import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Long variable name ({@code > 20} chars, strict). */
public final class LongVariableCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-long-variable", "LONG_VARIABLE"),
          "long variable name (> 20 chars, strict)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LongVariableCheck",
          true,
          List.of("ast-naming-var-strict", "long-variable"));

  @Override
  public String name() {
    return "long-variable";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarator v, StyleContext ctx) {
    String name = v.getNameAsString();
    if (name.length() <= 20) return;
    int line = v.getBegin().map(p -> p.line).orElse(-1);
    int col = v.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Long variable name: " + name + " (" + name.length() + " chars)",
        RuleDef.Severity.LOW,
        name);
  }
}
