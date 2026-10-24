package org.plumb.style.performance;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.type.ArrayType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** array used as Map/Set key : use List/Set. */
public final class ArrayAsKeyOfSetOrMapCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-array-key", "ARRAY_KEY"),
          "array used as Map/Set key : use List/Set",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-697"),
          "org.plumb.style.ArrayAsKeyOfSetOrMapCheck",
          true,
          List.of("ast-performance-as-key", "array-key"));

  @Override
  public String name() {
    return "array-key";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarationExpr n, StyleContext ctx) {
    for (VariableDeclarator v : n.getVariables()) {
      if (!(v.getType() instanceof ArrayType)) continue;
      int line = v.getBegin().map(p -> p.line).orElse(-1);
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Array variable : using as Map/Set key is wrong",
          RuleDef.Severity.MEDIUM,
          v.getNameAsString());
    }
  }
}
