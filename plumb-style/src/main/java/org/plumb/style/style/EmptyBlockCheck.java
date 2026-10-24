package org.plumb.style.style;

import com.github.javaparser.ast.stmt.*;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Empty block statement.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `allowEmptyCatch` | BOOLEAN | `false` | Skip empty catch blocks | | `allowEmptyLoop` | BOOLEAN |
 * `false` | Skip empty for/while/do loops | | `allowEmptyIf` | BOOLEAN | `false` | Skip empty if
 * blocks |
 */
public final class EmptyBlockCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "allowEmptyCatch", "Skip empty catch blocks", RuleParameter.Type.BOOLEAN, "false"),
    new RuleParameter(
        "allowEmptyLoop", "Skip empty for/while/do loops", RuleParameter.Type.BOOLEAN, "false"),
    new RuleParameter("allowEmptyIf", "Skip empty if blocks", RuleParameter.Type.BOOLEAN, "false"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-empty-block", "EMPTY_BLOCK"),
          "Empty block statement",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EmptyBlockCheck",
          true,
          List.of("ast-style-empty-block", "empty-block"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "empty-block";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BlockStmt n, StyleContext ctx) {
    if (!n.getStatements().isEmpty()) return;
    // Walk parents to find the syntactic owner.
    var parent = n.getParentNode().orElse(null);
    if (parent == null) return;
    // Skip method bodies : those are different rules.
    if (parent instanceof com.github.javaparser.ast.body.MethodDeclaration
        || parent instanceof com.github.javaparser.ast.body.ConstructorDeclaration
        || parent instanceof com.github.javaparser.ast.body.InitializerDeclaration) {
      return;
    }
    if (parent instanceof CatchClause) {
      if (paramBool(ctx, "allowEmptyCatch", false)) return;
    } else if (parent instanceof IfStmt) {
      if (paramBool(ctx, "allowEmptyIf", false)) return;
    } else if (parent instanceof ForStmt
        || parent instanceof ForEachStmt
        || parent instanceof WhileStmt
        || parent instanceof DoStmt) {
      if (paramBool(ctx, "allowEmptyLoop", false)) return;
    } else if (parent instanceof SynchronizedStmt || parent instanceof TryStmt) {
      // always report
    } else {
      return; // unknown owner : don't fire
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Empty block : looks like forgotten code",
        RuleDef.Severity.LOW,
        "empty-block");
  }

  private static boolean paramBool(StyleContext ctx, String name, boolean def) {
    // R-825 (2026-10-14): the per-rule fallback is now
    // provided by StyleContext.paramOverride() itself,
    // which falls back to the rule's declared
    // DEFAULT_RULE.parameters[] default (R-822).
    // The {@code def} argument is kept as a final
    // backstop for tests / tool callers that have no
    // StyleContext + RuleRegistry pair attached.
    Boolean override = ctx.paramOverride(name, Boolean.class);
    if (override != null) return override;
    return def;
  }
}
