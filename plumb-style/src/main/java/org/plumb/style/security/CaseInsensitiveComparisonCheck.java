package org.plumb.style.security;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Case-insensitive {@code String} comparison should use {@code equalsIgnoreCase} rather than {@code
 * toLowerCase()/toUpperCase() + equals()}.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code visit(CompilationUnit) +
 * findAll(MethodCallExpr.class)} before.
 */
public final class CaseInsensitiveComparisonCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-case-insensitive-comparison", "CASE_INSENSITIVE_COMPARISON"),
          "case-insensitive String comparison should use equalsIgnoreCase",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CaseInsensitiveComparisonCheck",
          true,
          List.of("ast-security-insensitive-comparison", "case-insensitive-comparison"));

  @Override
  public String name() {
    return "case-insensitive-comparison";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr mc, StyleContext ctx) {
    if ("equalsIgnoreCase".equals(mc.getNameAsString())) return;
    if (!"equals".equals(mc.getNameAsString())) return;
    if (mc.getScope().isEmpty()) return;
    String scope = mc.getScope().get().toString();
    if (!scope.endsWith(".toLowerCase()") && !scope.endsWith(".toUpperCase()")) return;
    int line = mc.getBegin().map(p -> p.line).orElse(-1);
    int col = mc.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use equalsIgnoreCase instead of toLowerCase/toUpperCase + equals",
        RuleDef.Severity.LOW,
        mc.toString());
  }
}
