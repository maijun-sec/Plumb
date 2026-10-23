package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** class has too many methods (limit 30). */
public final class TooManyMethodsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-too-many-methods", "TOO_MANY_METHODS"),
          "class has too many methods (limit 30)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.TooManyMethodsCheck",
          true,
          List.of("ast-style-many-methods", "too-many-methods")
      );

  @Override
  public String name() {
    return "too-many-methods";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (n.isInterface()) return;
    long count = n.getMembers().stream().filter(m -> m instanceof MethodDeclaration).count();
    if (count <= 30) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class has " + count + " methods (limit 30)",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}
