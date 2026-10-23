package org.plumb.style.security;

import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** hard-coded URI : use a config value. */
public final class HardcodedURICheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-hardcoded-uri", "HARDCODED_URI"),
          "hard-coded URI : use a config value",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.HardcodedURICheck",
          true,
          List.of("ast-security-hardcoded-uri", "hardcoded-uri")
      );

  @Override
  public String name() {
    return "hardcoded-uri";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(StringLiteralExpr n, StyleContext ctx) {
    String v = n.getValue();
    if (v.startsWith("http://")
        || v.startsWith("https://")
        || v.startsWith("ftp://")
        || v.startsWith("file://")) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(line, col, DEFAULT_RULE, "Hard-coded URI '" + v + "'", RuleDef.Severity.MEDIUM, v);
    }
  }
}
