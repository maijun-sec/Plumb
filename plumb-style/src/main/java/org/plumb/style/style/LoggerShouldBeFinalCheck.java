package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Logger field should be static final. */
public final class LoggerShouldBeFinalCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-logger-final", "LOGGER_FINAL"),
          "Logger field should be static final",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.LoggerShouldBeFinalCheck",
          true,
          List.of("ast-style-logger-should-be-final", "logger-final")
      );

  @Override
  public String name() {
    return "logger-final";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    if (n.getElementType() instanceof ClassOrInterfaceType t) {
      String name = t.getNameAsString();
      if (!"Logger".equals(name)
          && !"org.slf4j.Logger".equals(name)
          && !"java.util.logging.Logger".equals(name)
          && !"Log".equals(name)
          && !"Log4j".equals(name)) {
        return;
      }
    } else {
      return;
    }
    boolean isFinal =
        n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
    if (isFinal) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Logger field should be static final",
        RuleDef.Severity.MEDIUM,
        "Logger");
  }
}
