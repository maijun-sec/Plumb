package org.plumb.style.design;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Public method in non-{@code final} class should be {@code abstract}, {@code final}, or
 * {@code private} (DESIGN_FOR_EXTENSION).
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(ClassOrInterfaceDeclaration.class)} before. The inner per-method
 * walk stays as a local loop because we need to know the enclosing class is non-{@code final}.
 */
public final class DesignForExtensionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-design-for-extension", "DESIGN_FOR_EXTENSION"),
          "public method should be abstract, final, or private in non-final class",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DesignForExtensionCheck",
          true,
          List.of("ast-design-design-for-extension", "design-for-extension")
      );

  @Override
  public String name() {
    return "design-for-extension";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    if (c.isFinal()) return;
    for (MethodDeclaration m : c.getMethods()) {
      if (!m.isPublic()) continue;
      if (m.isAbstract() || m.isFinal()) continue;
      int line = m.getBegin().map(p -> p.line).orElse(-1);
      int col = m.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Public method in non-final class should be abstract, final, or private: "
              + m.getNameAsString(),
          RuleDef.Severity.LOW,
          m.getNameAsString());
    }
  }
}