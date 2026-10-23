package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Protected field in final class. */
public final class AvoidProtectedFieldInFinalClassCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-avoid-protected-field-in-final-class", "AVOID_PROTECTED_FIELD_IN_FINAL_CLASS"),
          "protected field in final class",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AvoidProtectedFieldInFinalClassCheck",
          true,
          List.of("ast-style-protected-field-final", "avoid-protected-field-in-final-class")
      );

  @Override
  public String name() {
    return "avoid-protected-field-in-final-class";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration f, StyleContext ctx) {
    if (!f.isProtected()) return;
    ClassOrInterfaceDeclaration c =
        f.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
    if (c == null || !c.isFinal()) return;
    int line = f.getBegin().map(p -> p.line).orElse(-1);
    int col = f.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Protected field in final class: " + c.getNameAsString(),
        RuleDef.Severity.LOW,
        c.getNameAsString());
  }
}