package org.plumb.style.naming;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import java.util.regex.Pattern;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Static final field should be {@code UPPER_SNAKE_CASE}.
 *
 * <p>M5 part-3 migration (2026-10-17): per-node-type callback directly; was {@code
 * visit(CompilationUnit) + findAll(FieldDeclaration.class)} before.
 */
public final class ConstantNameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-constant-name", "CONSTANT_NAME"),
          "static final field should be UPPER_SNAKE",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ConstantNameCheck",
          true,
          List.of("ast-naming-constant-name", "constant-name"));

  private static final Pattern UPPER = Pattern.compile("^[A-Z][A-Z0-9_]*$");

  @Override
  public String name() {
    return "constant-name";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration f, StyleContext ctx) {
    if (!f.isStatic() || !f.isFinal()) return;
    for (VariableDeclarator v : f.getVariables()) {
      String name = v.getNameAsString();
      if (UPPER.matcher(name).matches()) continue;
      int line = v.getBegin().map(p -> p.line).orElse(-1);
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Constant name should be UPPER_SNAKE_CASE: " + name,
          RuleDef.Severity.LOW,
          name);
    }
  }
}
