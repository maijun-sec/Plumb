package org.plumb.style.security;

import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Hard-coded IP address in code.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code visit(CompilationUnit) +
 * findAll(StringLiteralExpr.class)} before.
 */
public final class AvoidUsingHardCodedIPCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-avoid-coded-ip", "AVOID_CODED_IP"),
          "hard-coded IP address in code",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AvoidUsingHardCodedIPCheck",
          true,
          List.of("ast-security-coded-ip", "avoid-coded-ip"));

  @Override
  public String name() {
    return "avoid-coded-ip";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(StringLiteralExpr s, StyleContext ctx) {
    String val = s.getValue();
    if (!val.matches(".*\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}.*")) return;
    int line = s.getBegin().map(p -> p.line).orElse(-1);
    int col = s.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(line, col, DEFAULT_RULE, "Hard-coded IP: " + val, RuleDef.Severity.MEDIUM, val);
  }
}
