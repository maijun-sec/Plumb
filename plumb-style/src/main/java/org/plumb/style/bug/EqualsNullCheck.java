package org.plumb.style.bug;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Comparing with null using {@code .equals(null)} ? should be {@code ==}.
 *
 * <p>M5 part-2 migration (2026-10-17): the previous implementation used {@code
 * visit(CompilationUnit) + findAll(MethodCallExpr.class)}, which costs one full-AST walk per file
 * just to find {@code MethodCallExpr} nodes ? wasted work because the dispatcher already gives us a
 * {@code visit(MethodCallExpr)} callback that fires once per {@code MethodCallExpr} with no extra
 * walking. This rewrite uses the per-node-type callback directly; behaviour is unchanged.
 */
public final class EqualsNullCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-equals-null", "EQUALS_NULL"),
          "comparing with null using .equals() (should use ==)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EqualsNullCheck",
          true,
          List.of("ast-bug-equals-null", "equals-null"));

  @Override
  public String name() {
    return "equals-null";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr mc, StyleContext ctx) {
    if (!"equals".equals(mc.getNameAsString())) return;
    if (mc.getArguments().size() != 1) return;
    if (!(mc.getArgument(0) instanceof NullLiteralExpr)) return;
    int line = mc.getBegin().map(p -> p.line).orElse(-1);
    int col = mc.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use == for null comparison; .equals(null) is always false",
        RuleDef.Severity.LOW,
        mc.toString());
  }
}
