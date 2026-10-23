package org.plumb.style.bug;

import com.github.javaparser.ast.expr.*;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Compare against boolean literal directly (use 'x' or '!x').
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `ignoreEnclosed` | BOOLEAN | `true` | Skip == true inside parentheses (common in ternaries) |
 */
public final class IncorrectReturnValueCheckChecker implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "ignoreEnclosed",
        "Skip == true inside parentheses (common in ternaries)",
        RuleParameter.Type.BOOLEAN,
        "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-incorrect-return-check", "INCORRECT_RETURN_CHECK"),
          "Compare against boolean literal directly (use 'x' or '!x')",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of("CWE-253"),
          "org.plumb.style.IncorrectReturnValueCheckChecker",
          true,List.of("ast-bug-incorrect-return-check", "incorrect-return-check"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "incorrect-return-check";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.EQUALS
        && n.getOperator() != BinaryExpr.Operator.NOT_EQUALS) return;
    if (paramBool(ctx, "ignoreEnclosed", true)) {
      // Only fire when the boolean literal is at the
      // top-level (not wrapped in a parenthesised
      // expression that is itself part of a larger
      // ternary).
      if (n.getParentNode().orElse(null) instanceof EnclosedExpr) {
        var grand = n.getParentNode().get().getParentNode().orElse(null);
        if (grand instanceof ConditionalExpr) return;
      }
    }
    boolean leftIsBool = n.getLeft() instanceof BooleanLiteralExpr;
    boolean rightIsBool = n.getRight() instanceof BooleanLiteralExpr;
    if (!leftIsBool && !rightIsBool) return;
    Expression other = leftIsBool ? n.getRight() : n.getLeft();
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String op = n.getOperator() == BinaryExpr.Operator.EQUALS ? "==" : "!=";
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Comparison '"
            + other
            + " "
            + op
            + " "
            + n.getLeft()
            + "' : use '"
            + (n.getOperator() == BinaryExpr.Operator.NOT_EQUALS ? "!" : "")
            + other
            + "' directly",
        RuleDef.Severity.LOW,
        op);
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
