package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Bad syntax in regular expression. */
public final class BadSyntaxInRegexCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-badsyntaxinregex", "BADSYNTAXINREGEX"),
          "Bad syntax in regular expression",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.BadSyntaxInRegexCheck",
          true,
          List.of("ast-style-syntax-in-regex", "badsyntaxinregex")
      );

  @Override
  public String name() {
    return "badsyntaxinregex";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    for (String s : n.toString().split("\\n")) {
      if (s.contains("Pattern.compile") && s.contains("[a-z")) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Bad syntax in regular expression",
            RuleDef.Severity.MEDIUM,
            "regex");
      }
    }
  }
}
