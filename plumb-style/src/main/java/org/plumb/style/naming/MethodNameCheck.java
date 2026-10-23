package org.plumb.style.naming;

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import java.util.regex.Pattern;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Method name should be camelCase.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(MethodDeclaration.class)} before.
 */
public final class MethodNameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-method-name", "METHOD_NAME"),
          "method name should match pattern (camelCase)",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.MethodNameCheck",
          true,
          List.of("ast-naming-method-name", "method-name")
      );

  private static final Pattern CAMEL = Pattern.compile("^[a-z][a-zA-Z0-9_]*$");

  @Override
  public String name() {
    return "method-name";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    String name = m.getNameAsString();
    if (CAMEL.matcher(name).matches()) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Method name should be camelCase: " + name,
        RuleDef.Severity.LOW,
        name);
  }
}