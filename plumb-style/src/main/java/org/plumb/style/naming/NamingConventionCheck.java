package org.plumb.style.naming;

import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Local variable starting with uppercase letter (non-{@code String} types) ? should be
 * camelCase.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(VariableDeclarator.class)} before.
 */
public final class NamingConventionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-naming-convention", "NAMING_CONVENTION"),
          "Naming convention",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NamingConventionCheck",
          true,
          List.of("ast-naming-naming-convention-alpha", "naming-convention")
      );

  @Override
  public String name() {
    return "naming-convention";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarator v, StyleContext ctx) {
    String name = v.getNameAsString();
    if (name.isEmpty()) return;
    if (!Character.isUpperCase(name.charAt(0))) return;
    if ("String".equals(v.getTypeAsString())) return;
    int line = v.getBegin().map(p -> p.line).orElse(-1);
    int col = v.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line, col, DEFAULT_RULE, "Naming convention: " + name, RuleDef.Severity.LOW, name);
  }
}