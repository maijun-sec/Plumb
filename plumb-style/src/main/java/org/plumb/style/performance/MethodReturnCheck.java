package org.plumb.style.performance;

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Non-{@code void} method with empty body ? should return a value.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(MethodDeclaration.class)} before.
 */
public final class MethodReturnCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-method-return", "METHOD_RETURN"),
          "Method should return value",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MethodReturnCheck",
          true,
          List.of("ast-performance-method-return-check", "method-return")
      );

  @Override
  public String name() {
    return "method-return";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    String s = m.getType().asString();
    if ("void".equals(s)) return;
    if (m.getBody().isEmpty()) return;
    if (!m.getBody().get().getStatements().isEmpty()) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Method should return value: " + m.getNameAsString(),
        RuleDef.Severity.MEDIUM,
        m.getNameAsString());
  }
}