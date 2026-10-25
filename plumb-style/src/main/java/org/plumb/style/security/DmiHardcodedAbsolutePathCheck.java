package org.plumb.style.security;

import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import java.util.regex.Pattern;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Hard-coded absolute path (DMI_HARDCODED_ABSOLUTE_PATH, CWE-22). */
public final class DmiHardcodedAbsolutePathCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-hardcoded-absolute-path", "HARDCODED_ABSOLUTE_PATH"),
          "hard-coded absolute path (DMI_HARDCODED_ABSOLUTE_PATH)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-22"),
          "org.plumb.style.DmiHardcodedAbsolutePathCheck",
          true,
          List.of("ast-security-hardcoded-absolute-path", "hardcoded-absolute-path"));

  private static final Pattern ABS =
      Pattern.compile("^(/usr/|/etc/|/var/|/opt/|/home/|C:\\\\|D:\\\\)");

  @Override
  public String name() {
    return "hardcoded-absolute-path";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(StringLiteralExpr s, StyleContext ctx) {
    if (!ABS.matcher(s.getValue()).find()) return;
    int line = s.getBegin().map(p -> p.line).orElse(-1);
    int col = s.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Hard-coded absolute path '" + s.getValue() + "'",
        RuleDef.Severity.MEDIUM,
        s.getValue());
  }
}
