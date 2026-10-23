package org.plumb.style.style;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Nested if can be combined with the parent if.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `ignoreElseBranch` | BOOLEAN | `true` | Skip when the parent if has an else branch |
 */
public final class CollapsibleIfStatementsCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "ignoreElseBranch",
        "Skip when the parent if has an else branch",
        RuleParameter.Type.BOOLEAN,
        "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-collapsible-if-statements", "COLLAPSIBLE_IF_STATEMENTS"),
          "Nested if can be combined with the parent if",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CollapsibleIfStatementsCheck",
          true,List.of("ast-style-collapsible-if-statements", "collapsible-if-statements"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "collapsible-if-statements";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt outer, StyleContext ctx) {
    boolean ignoreElse = paramBool(ctx, "ignoreElseBranch", true);
    if (ignoreElse && outer.getElseStmt().isPresent()) return;
    Statement then = outer.getThenStmt();
    IfStmt inner = findSingleNestedIf(then);
    if (inner == null) return;
    int line = inner.getBegin().map(p -> p.line).orElse(-1);
    int col = inner.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Nested if statement can be combined with outer if using '&&'",
        RuleDef.Severity.LOW,
        "collapsible-if");
  }

  /**
   * Returns the nested exactly a block containing a single no else), or is itself a bare an else.
   */
  private static IfStmt findSingleNestedIf(Statement then) {
    if (then instanceof IfStmt inner && !inner.getElseStmt().isPresent()) {
      return inner;
    }
    if (then instanceof BlockStmt block) {
      if (block.getStatements().size() != 1) return null;
      Statement s = block.getStatements().get(0);
      if (s instanceof IfStmt inner && !inner.getElseStmt().isPresent()) {
        return inner;
      }
    }
    return null;
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
