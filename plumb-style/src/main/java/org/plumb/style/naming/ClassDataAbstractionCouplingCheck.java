package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Class has too many instance fields (data abstraction coupling). */
public final class ClassDataAbstractionCouplingCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(
              "ast-naming-class-data-abstraction-coupling", "CLASS_DATA_ABSTRACTION_COUPLING"),
          "class has too many instance fields (data abstraction coupling)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ClassDataAbstractionCouplingCheck",
          true,
          List.of("ast-naming-data-abstraction-coupling", "class-data-abstraction-coupling"));

  @Override
  public String name() {
    return "class-data-abstraction-coupling";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    int fieldCount = 0;
    for (FieldDeclaration f : c.getFields()) {
      if (!f.isStatic()) fieldCount++;
    }
    if (fieldCount <= 7) return;
    int line = c.getBegin().map(p -> p.line).orElse(-1);
    int col = c.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class " + c.getNameAsString() + " has " + fieldCount + " instance fields",
        RuleDef.Severity.LOW,
        c.getNameAsString());
  }
}
