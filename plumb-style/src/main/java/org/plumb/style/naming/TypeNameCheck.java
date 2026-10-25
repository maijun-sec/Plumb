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
 * <p>A type name is reported when it does not match {@code [A-Z][A-Za-z0-9]*}. This rule is
 * disabled by default; enable it explicitly when the naming convention is part of the project's own
 * standard.
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
              "type-name")); // disabled by default: the naming convention is opt-in

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
