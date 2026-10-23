package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** class with only private constructors should be final. */
public final class IncompleteClassDeclarationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-incomplete-class-declaration", "INCOMPLETE_CLASS_DECLARATION"),
          "class with only private constructors should be final",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.IncompleteClassDeclarationCheck",
          true,
          List.of("ast-style-only-private-ctors-incomplete-class-declaration", "incomplete-class-declaration")
      );

  @Override
  public String name() {
    return "incomplete-class-declaration";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (n.isInterface() || n.isAbstract()) return;
    if (n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL)) return;
    // Find all constructors.
    var ctors =
        n.getMembers().stream()
            .filter(m -> m instanceof com.github.javaparser.ast.body.ConstructorDeclaration)
            .map(m -> (com.github.javaparser.ast.body.ConstructorDeclaration) m)
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
        "Class " + n.getNameAsString() + " has only private constructors; mark it final",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}
