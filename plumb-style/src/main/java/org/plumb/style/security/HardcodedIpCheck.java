package org.plumb.style.security;

import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import java.util.regex.Pattern;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Hardcoded IP address in source.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(StringLiteralExpr.class)} before.
 */
public final class HardcodedIpCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-hardcoded-ip", "HARDCODED_IP"),
          "hardcoded IP address",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-547"),
          "org.plumb.style.HardcodedIpCheck",
          true,
          List.of("ast-security-hardcoded-ip", "hardcoded-ip")
      );

  private static final Pattern IP =
      Pattern.compile(".*\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b.*");

  @Override
  public String name() {
    return "hardcoded-ip";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(StringLiteralExpr s, StyleContext ctx) {
    String val = s.getValue();
    if (!IP.matcher(val).find()) return;
    int line = s.getBegin().map(p -> p.line).orElse(-1);
    int col = s.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line, col, DEFAULT_RULE, "Hardcoded IP address: " + val, RuleDef.Severity.MEDIUM, val);
  }
}