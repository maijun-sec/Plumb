package org.plumb.style.design;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** data class : mostly fields, expose behavior. */
public final class DataClassCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-data-class", "DATA_CLASS"),
          "data class : mostly fields, expose behavior",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DataClassCheck",
          true,
          List.of("ast-design-class", "data-class"));

  @Override
  public String name() {
    return "data-class";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (n.isInterface()) return;
    long fieldCount = n.getMembers().stream().filter(m -> m instanceof FieldDeclaration).count();
    long methodCount = n.getMembers().stream().filter(m -> m instanceof MethodDeclaration).count();
    // Heuristic: > 5 fields and < 3 methods.
    if (fieldCount < 5 || methodCount >= 3) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Data class : " + fieldCount + " fields but only " + methodCount + " methods",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}
