package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Avoid escaped Unicode characters (use direct). */
public final class AvoidEscapedUnicodeCharactersCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-avoidescapedunicode", "AVOIDESCAPEDUNICODE"),
          "Avoid escaped Unicode characters (use direct)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AvoidEscapedUnicodeCharactersCheck",
          false,
          List.of("ast-style-escaped-unicode", "avoidescapedunicode")
      ); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832 baseline)

  @Override
  public String name() {
    return "avoidescapedunicode";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    for (String s : n.toString().split("\\n")) {
      if (s.contains("\\u")) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line, col, DEFAULT_RULE, "Avoid escaped Unicode characters", RuleDef.Severity.LOW, s);
      }
    }
  }
}
