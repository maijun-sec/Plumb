package org.plumb.style.style;

import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Whitespace after token. */
public final class WhitespaceAfterCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-whitespaceafter", "WHITESPACEAFTER"),
          "Whitespace after token",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.WhitespaceAfterCheck",
          true,
          List.of("ast-style-whitespace-after", "whitespaceafter"));

  @Override
  public String name() {
    return "whitespaceafter";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt f, StyleContext ctx) {
    String s = f.toString();
    if (s.contains("for( ")) {
      int line = f.getBegin().map(p -> p.line).orElse(-1);
      int col = f.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(line, col, DEFAULT_RULE, "Whitespace after 'for'", RuleDef.Severity.LOW, "for");
    }
  }
}
