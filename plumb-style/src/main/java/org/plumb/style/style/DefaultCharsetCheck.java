package org.plumb.style.style;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** {@code new String()} — relies on platform default charset. */
public final class DefaultCharsetCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-default-charset", "DEFAULT_CHARSET"),
          "Default charset usage",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DefaultCharsetCheck",
          true,
          List.of("ast-style-default-charset", "default-charset"));

  @Override
  public String name() {
    return "default-charset";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr o, StyleContext ctx) {
    String s = o.getType().getNameAsString();
    if (!"String".equals(s)) return;
    if (!o.getArguments().isEmpty()) return;
    int line = o.getBegin().map(p -> p.line).orElse(-1);
    int col = o.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(line, col, DEFAULT_RULE, "Default charset usage: " + s, RuleDef.Severity.MEDIUM, s);
  }
}
