package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Should use single space separator. */
public final class SingleSpaceSeparatorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-singlespaceseparator", "SINGLESPACESEPARATOR"),
          "Should use single space separator",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SingleSpaceSeparatorCheck",
          true,
          List.of("ast-style-single-space-separator", "singlespaceseparator"));

  @Override
  public String name() {
    return "singlespaceseparator";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    String s = m.toString();
    if (s.contains(",,")) {
      int line = m.getBegin().map(p -> p.line).orElse(-1);
      int col = m.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Should use single space separator",
          RuleDef.Severity.LOW,
          m.getNameAsString());
    }
  }
}
