package org.plumb.style.style;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** new SimpleDateFormat() needs explicit Locale. */
public final class SimpleDateFormatNeedsLocaleCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-simplifiedateformat-locale", "SIMPLIFIEDATEFORMAT_LOCALE"),
          "new SimpleDateFormat() needs explicit Locale",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-18"),
          "org.plumb.style.SimpleDateFormatNeedsLocaleCheck",
          true,
          List.of("ast-style-simpledateformat-locale", "simplifiedateformat-locale")
      );

  @Override
  public String name() {
    return "simplifiedateformat-locale";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    if (!"SimpleDateFormat".equals(n.getType().getNameAsString())
        && !"java.text.SimpleDateFormat".equals(n.getType().asString())) {
      return;
    }
    // Fire if there's only 1 argument (the pattern).
    if (n.getArguments().size() == 1) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "new SimpleDateFormat() should specify an explicit Locale",
          RuleDef.Severity.MEDIUM,
          "SimpleDateFormat");
    }
  }
}
