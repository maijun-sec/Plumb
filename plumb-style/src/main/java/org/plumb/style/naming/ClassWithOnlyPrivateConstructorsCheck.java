package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Class with only private constructors should be {@code final}. */
public final class ClassWithOnlyPrivateConstructorsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-class-only-private-ctors", "CLASS_ONLY_PRIVATE_CTORS"),
          "class with only private constructors should be final",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ClassWithOnlyPrivateConstructorsCheck",
          true,
          List.of("ast-naming-only-private-ctors", "class-only-private-ctors"));

  @Override
  public String name() {
    return "class-only-private-ctors";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    if (c.isFinal()) return;
    boolean hasPublic = false;
    int ctorCount = 0;
    for (ConstructorDeclaration ctor : c.getConstructors()) {
      ctorCount++;
      if (ctor.isPublic()) hasPublic = true;
    }
    if (ctorCount == 0 || hasPublic) return;
    int line = c.getBegin().map(p -> p.line).orElse(-1);
    int col = c.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class " + c.getNameAsString() + " has only private constructors; should be final",
        RuleDef.Severity.LOW,
        c.getNameAsString());
  }
}
