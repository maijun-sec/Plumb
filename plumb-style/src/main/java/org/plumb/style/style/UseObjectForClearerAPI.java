package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Use object for clearer API. */
public final class UseObjectForClearerAPI implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-use-object-for-clearer-api", "USE_OBJECT_FOR_CLEARER_API"),
          "Use object for clearer API",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UseObjectForClearerAPI",
          true,
          List.of("ast-style-use-object-clearer-api", "use-object-for-clearer-api")
      );

  @Override
  public String name() {
    return "use-object-for-clearer-api";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    String s = n.getNameAsString();
    if (s.contains("UseObjectForClearerA")) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line, col, DEFAULT_RULE, "UseObjectForClearerAPI detection", RuleDef.Severity.LOW, s);
    }
  }
}
