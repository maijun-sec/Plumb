package org.plumb.style.performance;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Loop incrementer is not the standard form.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `allowDecrement` | BOOLEAN | `true` | Allow i-- as a standard increment |
 */
public final class JumbledIncrementerLoopCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "allowDecrement", "Allow i-- as a standard increment", RuleParameter.Type.BOOLEAN, "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-jumbled-incrementer", "JUMBLED_INCREMENTER_2"),
          "Loop incrementer is not the standard form",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-190", "CWE-1284"),
          "org.plumb.style.JumbledIncrementerLoopCheck",
          true,
          List.of("ast-performance-jumbled-incrementer-loop", "jumbled-incrementer-loop"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "jumbled-incrementer-loop";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    if (n.getUpdate().isEmpty()) return;
    boolean allowDecrement = paramBool(ctx, "allowDecrement", true);
    for (Expression upd : n.getUpdate()) {
      // Standard form: i++ / ++i / i-- / --i
      if (upd instanceof UnaryExpr ue) {
        switch (ue.getOperator()) {
          case POSTFIX_INCREMENT:
          case PREFIX_INCREMENT:
            continue; // i++ or ++i
          case POSTFIX_DECREMENT:
          case PREFIX_DECREMENT:
            if (allowDecrement) continue;
            break;
          default:
            break;
        }
      }
      // Anything else (i += 2, i = i + 2, i *= 2, f(),...) is
      // suspicious : fire.
      int line = upd.getBegin().map(p -> p.line).orElse(-1);
      int col = upd.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Loop incrementer is not the simple i++ / i-- form: " + upd,
          RuleDef.Severity.MEDIUM,
          upd.toString());
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
