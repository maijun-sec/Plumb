package org.plumb.style.naming;

import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Numeric literal should use underscore separators for readability.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `minDigits`
 * | INT | `4` | Minimum consecutive digits to require underscores | | `includeFloat` | BOOLEAN |
 * `true` | Also check float/double literals |
 */
// R-AST-FIX-2026-10-18 (Pass B): removed duplicate paramInt helper.
public final class UseUnderscoresInNumericLiteralsCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "minDigits",
        "Minimum consecutive digits to require underscores",
        RuleParameter.Type.INT,
        "4"),
    new RuleParameter(
        "includeFloat", "Also check float/double literals", RuleParameter.Type.BOOLEAN, "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-use-underscores-in-numeric-literals", "USE_UNDERSCORES_IN_NUMERIC_LITERALS"),
          "Numeric literal should use underscore separators for readability",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UseUnderscoresInNumericLiteralsCheck",
          true,List.of("ast-naming-use-underscores-in-numeric-literals", "use-underscores-in-numeric-literals"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "use-underscores-in-numeric-literals";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IntegerLiteralExpr n, StyleContext ctx) {
    checkInteger(n, n.getValue(), ctx);
  }

  @Override
  public void visit(LongLiteralExpr n, StyleContext ctx) {
    checkInteger(n, n.getValue(), ctx);
  }

  @Override
  public void visit(DoubleLiteralExpr n, StyleContext ctx) {
    if (!paramBool(ctx, "includeFloat", true)) return;
    checkInteger(n, n.getValue(), ctx);
  }

  private void checkInteger(LiteralExpr n, String value, StyleContext ctx) {
    Integer _o = ctx.paramOverride("minDigits", Integer.class);
    int minDigits = _o != null ? _o : 4;
    // Strip trailing L/F/D etc. and any leading 0x.
    String digits = value.replaceAll("[lLfFdD]", "");
    digits = digits.replaceFirst("^0[xX]", "");
    // Check the longest run of digits (without underscores) : but
    // JavaParser's `getValue()` returns the literal as written, so
    // we can just measure the longest run.
    StringBuilder run = new StringBuilder();
    int maxRun = 0;
    for (int i = 0; i < digits.length(); i++) {
      char c = digits.charAt(i);
      if (Character.isDigit(c)) {
        run.append(c);
        maxRun = Math.max(maxRun, run.length());
      } else {
        run.setLength(0);
      }
    }
    if (maxRun >= minDigits) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Numeric literal "
              + value
              + " has "
              + maxRun
              + " consecutive digits : consider using '_' separators",
          RuleDef.Severity.LOW,
          value);
    }
  }

  private static boolean paramBool(StyleContext ctx, String name, boolean def) {
    Boolean override = ctx.paramOverride(name, Boolean.class);
    if (override != null) return override;
    for (RuleParameter p : PARAMS) {
      if (p.name().equals(name)) return p.boolDefault();
    }
    return def;
  }
}
