package org.plumb.style.style;

import com.github.javaparser.Position;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Anonymous inner class is too long.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `max` | INT
 * | `20` | Maximum allowed lines for an anonymous class |
 */
// R-AST-FIX-2026-10-18 (Pass B): removed duplicate paramInt helper.
public final class AnonInnerLengthCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "max", "Maximum allowed lines for an anonymous class", RuleParameter.Type.INT, "20"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-anon-inner-length", "ANON_INNER_LENGTH"),
          "Anonymous inner class is too long",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AnonInnerLengthCheck",
          true,
          List.of("ast-style-inner-length", "anon-inner-length"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "anon-inner-length";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    if (!n.getAnonymousClassBody().isPresent()) return;
    Integer _o = ctx.paramOverride("max", Integer.class);
    int max = _o != null ? _o : 20;
    // Measure between the begin/end of the whole
    // ObjectCreationExpr : that covers the `new T() {... }`
    // span which is what the developer sees.
    Position beg = n.getBegin().orElse(null);
    Position end = n.getEnd().orElse(null);
    if (beg == null || end == null) return;
    int lines = end.line - beg.line + 1;
    if (lines <= max) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Anonymous inner class is " + lines + " lines (max " + max + ")",
        RuleDef.Severity.LOW,
        "anon-class");
  }
}
