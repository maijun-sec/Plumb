package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use Iterator API instead of legacy Enumeration. */
public final class UseEnumerationInsteadOfIteratorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-use-enumeration-instead-of-iterator", "USE_ENUMERATION_INSTEAD_OF_ITERATOR"),
          "use Iterator API instead of legacy Enumeration",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UseEnumerationInsteadOfIteratorCheck",
          true,
          List.of("ast-style-enum-instead-of-iterator", "use-enumeration-instead-of-iterator")
      );

  @Override
  public String name() {
    return "use-enumeration-instead-of-iterator";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"hasMoreElements".equals(n.getNameAsString())
        && !"nextElement".equals(n.getNameAsString())) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use Iterator API instead of legacy Enumeration#" + n.getNameAsString(),
        RuleDef.Severity.LOW,
        "Enumeration");
  }
}
