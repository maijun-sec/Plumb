package org.plumb.style.style;

import com.github.javaparser.ast.expr.LongLiteralExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** long literal should use upper-case L suffix. */
public final class NoLongLiteralCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-no-long-literal", "NO_LONG_LITERAL"),
          "long literal should use upper-case L suffix",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NoLongLiteralCheck",
          true,
          List.of("ast-style-literal-suffix", "no-long-literal")
      );

  @Override
  public String name() {
    return "no-long-literal";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(LongLiteralExpr n, StyleContext ctx) {
    String text = n.getValue();
    if (text.endsWith("l") && !text.endsWith("Ll") && !text.endsWith("LL")) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Long literal ends with 'l' : use 'L' to avoid confusion with '1'",
          RuleDef.Severity.LOW,
          text);
    }
  }
}
