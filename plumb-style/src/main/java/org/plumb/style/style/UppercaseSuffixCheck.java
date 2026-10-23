package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.expr.LongLiteralExpr;
import java.util.List;
import java.util.regex.Pattern;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** long literal should use uppercase L. */
public final class UppercaseSuffixCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-uppercase-suffix", "UPPERCASE_SUFFIX"),
          "long literal should use uppercase L",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UppercaseSuffixCheck",
          true,
          List.of("ast-style-uppercase-suffix", "uppercase-suffix")
      );
  private static final Pattern LOWER_L = Pattern.compile(".*\\d+l$");

  @Override
  public String name() {
    return "uppercase-suffix";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(LongLiteralExpr l, StyleContext ctx) {
    String lit = l.toString();
    if (LOWER_L.matcher(lit).matches()) {
      int line = l.getBegin().map(p -> p.line).orElse(-1);
      int col = l.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Long literal " + lit + " uses lowercase 'l'; use 'L' to avoid confusion with '1'",
          RuleDef.Severity.LOW,
          lit);
    }
  }
}
