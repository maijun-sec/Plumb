package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** class with only private constructors should be final. */
public final class ClassWithOnlyPrivateConstructorsShouldBeFinalCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-private-ctors-final", "PRIVATE_CTORS_FINAL"),
          "class with only private constructors should be final",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ClassWithOnlyPrivateConstructorsShouldBeFinalCheck",
          true,
          List.of("ast-style-with-only-private-constructors-should-be-final", "private-ctors-final")
      );

  @Override
  public String name() {
    return "private-ctors-final";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (n.isInterface()) return;
    if (n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL)) return;
    var ctors =
        n.getMembers().stream()
            .filter(m -> m instanceof ConstructorDeclaration)
            .map(m -> (ConstructorDeclaration) m)
            .toList();
    if (ctors.isEmpty()) return;
    boolean allPrivate =
        ctors.stream()
            .allMatch(
                c ->
                    c.getModifiers().stream()
                        .anyMatch(m -> m.getKeyword() == Modifier.Keyword.PRIVATE));
    if (!allPrivate) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class with only private constructors should be final",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}
