package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * @Test method has no assertion : it always passes.
 */
public final class JUnitTestsShouldIncludeAssertCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-junit-no-assert", "JUNIT_NO_ASSERT"),
          "@Test method has no assertion : it always passes",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.JUnitTestsShouldIncludeAssertCheck",
          true,
          List.of("ast-style-tests-should-include-assert", "junit-no-assert")
      );

  @Override
  public String name() {
    return "junit-no-assert";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (!n.getAnnotations().stream().map(AnnotationExpr::getNameAsString).anyMatch("Test"::equals))
      return;
    // Heuristic: look for assert* / fail* / verify* calls.
    boolean hasAssert =
        !n.findAll(MethodCallExpr.class).stream()
            .anyMatch(
                m -> {
                  String name = m.getNameAsString();
                  return name.startsWith("assert")
                      || name.startsWith("fail")
                      || name.startsWith("verify")
                      || name.startsWith("expect");
                });
    if (hasAssert) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "@Test method has no assertion",
          RuleDef.Severity.MEDIUM,
          n.getNameAsString());
    }
  }
}
