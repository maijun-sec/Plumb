package org.plumb.style.style;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Logger field should be named LOG/LOGGER/log. */
public final class ProperLoggerCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-proper-logger", "PROPER_LOGGER"),
          "Logger field should be named LOG/LOGGER/log",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.ProperLoggerCheck",
          true,
          List.of("ast-style-proper-logger", "proper-logger")
      );

  @Override
  public String name() {
    return "proper-logger";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    if (!(n.getElementType() instanceof ClassOrInterfaceType t)) return;
    String typeName = t.getNameAsString();
    if (!"Logger".equals(typeName)
        && !"org.slf4j.Logger".equals(typeName)
        && !"java.util.logging.Logger".equals(typeName)) {
      return;
    }
    n.getVariables()
        .forEach(
            v -> {
              String name = v.getNameAsString();
              if ("LOG".equals(name) || "LOGGER".equals(name) || "log".equals(name)) return;
              int line = v.getBegin().map(p -> p.line).orElse(-1);
              int col = v.getBegin().map(p -> p.column).orElse(-1);
              ctx.report(
                  line,
                  col,
                  DEFAULT_RULE,
                  "Logger should be named LOG / LOGGER / log, got '" + name + "'",
                  RuleDef.Severity.LOW,
                  name);
            });
  }
}
