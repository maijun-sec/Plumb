package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** regex without groups : use String.matches. */
public final class RegexGroupCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-regex-group", "REGEX_GROUP"),
          "regex without groups : use String.matches",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.RegexGroupCheck",
          true,
          List.of("ast-style-group", "regex-group"));

  @Override
  public String name() {
    return "regex-group";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"compile".equals(n.getNameAsString())) return;
    if (n.getScope().isEmpty()) return;
    if (!"Pattern".equals(n.getScope().get().toString())) return;
    if (n.getArguments().isEmpty()) return;
    if (!(n.getArgument(0) instanceof StringLiteralExpr sl)) return;
    String pattern = sl.getValue();
    // Look for any capturing group.
    try {
      Pattern compiled = Pattern.compile(pattern);
      int groupCount = compiled.matcher("").groupCount();
      if (groupCount == 0) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Pattern.compile() without groups : use String.matches() instead",
            RuleDef.Severity.LOW,
            pattern);
      }
    } catch (PatternSyntaxException ex) {
      // Invalid regex : that's a different bug,
      // ignore here.
    }
  }
}
