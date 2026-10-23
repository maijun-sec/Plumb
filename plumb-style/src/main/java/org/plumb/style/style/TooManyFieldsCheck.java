package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** class has too many fields (limit 15). */
public final class TooManyFieldsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-too-many-fields", "TOO_MANY_FIELDS"),
          "class has too many fields (limit 15)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.TooManyFieldsCheck",
          true,
          List.of("ast-style-many-fields", "too-many-fields")
      );

  @Override
  public String name() {
    return "too-many-fields";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (n.isInterface()) return;
    long count = n.getMembers().stream().filter(m -> m instanceof FieldDeclaration).count();
    if (count <= 15) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class has " + count + " fields (limit 15)",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}
