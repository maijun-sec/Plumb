package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Type name should be PascalCase.
 *
 * <p>Disabled by default (R-836 step_f) ? 0 TP / 100% FP on Juliet R-832 baseline. M5 part-4
 * migration (2026-10-17): per-node-type callback; was {@code visit(CompilationUnit) +
 * findAll(ClassOrInterfaceDeclaration.class)} before.
 */
public final class TypeNameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-type-name", "TYPE_NAME"),
          "type name should be PascalCase",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.TypeNameCheck",
          false,
          List.of(
              "ast-naming-name",
              "type-name")); // R-836 step_f: disabled by default (0 TP / 100% FP on Juliet R-832

  // baseline)

  @Override
  public String name() {
    return "type-name";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    String name = c.getNameAsString();
    if (name.matches("[A-Z][A-Za-z0-9]*")) return;
    int line = c.getBegin().map(p -> p.line).orElse(-1);
    int col = c.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Type name should be PascalCase: " + name,
        RuleDef.Severity.LOW,
        name);
  }
}
