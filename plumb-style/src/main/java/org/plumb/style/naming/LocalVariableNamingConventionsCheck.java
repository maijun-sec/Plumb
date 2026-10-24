package org.plumb.style.naming;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.List;
import java.util.regex.Pattern;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** local variable should be camelCase. */
public final class LocalVariableNamingConventionsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-local-var-naming", "LOCAL_VAR_NAMING"),
          "local variable should be camelCase",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.LocalVariableNamingConventionsCheck",
          true,
          List.of("ast-naming-variable-naming-conventions", "local-var-naming"));

  private static final Pattern CAMEL_CASE = Pattern.compile("^[a-z][a-zA-Z0-9]*$");

  @Override
  public String name() {
    return "local-var-naming";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarationExpr n, StyleContext ctx) {
    if (n.findAncestor(BlockStmt.class).isEmpty()) return;
    for (VariableDeclarator v : n.getVariables()) {
      String name = v.getNameAsString();
      if (CAMEL_CASE.matcher(name).matches()) continue;
      // Skip names that look like ALL_CAPS constants.
      if (name.matches("^[A-Z_]+$")) continue;
      int line = v.getBegin().map(p -> p.line).orElse(-1);
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Local variable '" + name + "' should be camelCase",
          RuleDef.Severity.LOW,
          name);
    }
  }
}
