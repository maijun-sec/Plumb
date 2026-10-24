package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** File should not contain code. */
public final class NoCodeInFileCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-nocodeinfile", "NOCODEINFILE"),
          "File should not contain code",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NoCodeInFileCheck",
          true,
          List.of("ast-style-code-in-file", "nocodeinfile"));

  @Override
  public String name() {
    return "nocodeinfile";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    if (n.getTypes().isEmpty() && !n.toString().trim().isEmpty()) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line, col, DEFAULT_RULE, "File should not contain code", RuleDef.Severity.LOW, "no_code");
    }
  }
}
