package org.plumb.style.naming;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Class with all-private constructors should be an enum. */
public final class SingletonClassCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-singleton-class", "SINGLETON_CLASS"),
          "Class with all-private constructors should be an enum",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SingletonClassCheck",
          true,
          List.of("ast-naming-singleton-class", "singleton-class"));

  @Override
  public String name() {
    return "singleton-class";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (n.isNestedType()) return;
    if (n.getConstructors().isEmpty()) return;
    // Every ctor must be private.
    for (ConstructorDeclaration c : n.getConstructors()) {
      boolean isPrivate =
          c.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.PRIVATE);
      if (!isPrivate) return;
    }
    // No non-static fields.
    for (FieldDeclaration f : n.getFields()) {
      boolean isStatic =
          f.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
      if (!isStatic) return;
    }
    // No non-static methods.
    for (MethodDeclaration m : n.getMethods()) {
      boolean isStatic =
          m.getModifiers().stream().anyMatch(m2 -> m2.getKeyword() == Modifier.Keyword.STATIC);
      if (!isStatic) return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class '"
            + n.getNameAsString()
            + "' has only private constructors and static members : "
            + "consider using an enum singleton",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}
