package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** non-instantiable class should have a static method or field. */
public final class MissingStaticMethodInNonInstantiableClassCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(
              "ast-style-missing-static-in-non-instantiable", "MISSING_STATIC_IN_NON_INSTANTIABLE"),
          "non-instantiable class should have a static method or field",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.MissingStaticMethodInNonInstantiableClassCheck",
          true,
          List.of(
              "ast-style-missing-static-in-non-instantiable",
              "missing-static-in-non-instantiable"));

  @Override
  public String name() {
    return "missing-static-in-non-instantiable";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (n.isInterface()) return;
    // All constructors must be private.
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
    // Must have at least one static member.
    boolean hasStatic =
        n.getMembers().stream()
            .anyMatch(
                m ->
                    m instanceof com.github.javaparser.ast.nodeTypes.NodeWithModifiers
                        && ((com.github.javaparser.ast.nodeTypes.NodeWithModifiers<?>) m)
                            .getModifiers().stream()
                                .anyMatch(mod -> mod.getKeyword() == Modifier.Keyword.STATIC));
    if (hasStatic) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Non-instantiable class '"
            + n.getNameAsString()
            + "' has no static member : is this dead code?",
        RuleDef.Severity.MEDIUM,
        n.getNameAsString());
  }
}
