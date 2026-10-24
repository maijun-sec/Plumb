package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** public method should have a Javadoc comment. */
public final class JavadocMethodCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-javadoc-method", "JAVADOC_METHOD"),
          "public method should have a Javadoc comment",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.JavadocMethodCheck",
          true,
          List.of("ast-style-javadoc-method", "javadoc-method"));

  @Override
  public String name() {
    return "javadoc-method";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    for (TypeDeclaration<?> td : n.getTypes()) {
      for (var m : td.getMembers()) {
        if (m instanceof MethodDeclaration md) {
          check(md, ctx);
        }
      }
    }
  }

  private void check(MethodDeclaration n, StyleContext ctx) {
    boolean isPublic =
        n.getModifiers().stream()
            .anyMatch(m -> m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.PUBLIC);
    if (!isPublic) return;
    boolean isOverride =
        n.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("Override"));
    if (isOverride) return;
    // Has a Javadoc comment?
    if (n.getComment().isPresent() && n.getComment().get().toString().trim().startsWith("/**")) {
      return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Public method '" + n.getNameAsString() + "' is missing a Javadoc comment",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}
