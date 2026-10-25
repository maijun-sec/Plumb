package org.plumb.style.design;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Class has too many methods (default threshold 100). */
public final class MethodCountCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-method-count", "METHOD_COUNT"),
          "too many methods in class (default 100)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MethodCountCheck",
          true,
          List.of("ast-design-method-count", "method-count"));

  @Override
  public String name() {
    return "method-count";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    int methodCount = c.getMethods().size();
    if (methodCount <= 100) return;
    int line = c.getBegin().map(p -> p.line).orElse(-1);
    int col = c.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class " + c.getNameAsString() + " has too many methods: " + methodCount,
        RuleDef.Severity.LOW,
        c.getNameAsString());
  }
}
