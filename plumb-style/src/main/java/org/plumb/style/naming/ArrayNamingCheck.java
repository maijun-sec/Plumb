package org.plumb.style.naming;

import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Array variable name should be plural or end with {@code s}.
 *
 * <p>M5 part-3 migration (2026-10-17): the previous implementation used {@code
 * visit(CompilationUnit) + findAll(VariableDeclarator.class)} (1 walk per file). This rewrite uses
 * the per-node-type callback directly; the dispatcher hands each {@code VariableDeclarator} to us
 * with no extra walking.
 */
public final class ArrayNamingCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-arraynaming", "ARRAYNAMING"),
          "array name should be plural or end with 's'",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ArrayNamingCheck",
          true,
          List.of("ast-naming-naming-strict", "arraynamingcheck"));

  @Override
  public String name() {
    return "arraynaming";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarator v, StyleContext ctx) {
    String type = v.getTypeAsString();
    if (!type.endsWith("[]") && !type.endsWith("...")) return;
    String name = v.getNameAsString();
    if (name.endsWith("s")
        || name.endsWith("List")
        || name.endsWith("Array")
        || name.endsWith("Set")) {
      return;
    }
    int line = v.getBegin().map(p -> p.line).orElse(-1);
    int col = v.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Array name should be plural: " + name,
        RuleDef.Severity.LOW,
        name);
  }
}
