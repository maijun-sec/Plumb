package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Array literal has heterogeneous types (EC_BAD_ARRAY_STORE). */
public final class EcBadArrayStoreCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-ec-bad-array-store", "EC_BAD_ARRAY_STORE"),
          "array literal has heterogeneous types (EC_BAD_ARRAY_STORE)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-704"),
          "org.plumb.style.EcBadArrayStoreCheck",
          true,
          List.of("ast-performance-bad-array-store", "ec-bad-array-store"));

  @Override
  public String name() {
    return "ec-bad-array-store";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ArrayCreationExpr arr, StyleContext ctx) {
    if (arr.getInitializer().isEmpty()) return;
    Expression initExpr = arr.getInitializer().get();
    if (!(initExpr instanceof ArrayInitializerExpr init)) return;
    String firstType = null;
    for (Expression el : init.getValues()) {
      String t = stamp(el);
      if (firstType == null) {
        firstType = t;
      } else if (!firstType.equals(t)) {
        int line = arr.getBegin().map(p -> p.line).orElse(-1);
        int col = arr.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Array literal has elements of mixed types",
            RuleDef.Severity.MEDIUM,
            arr.getElementType().asString());
        return;
      }
    }
  }

  private static String stamp(Expression e) {
    if (e instanceof IntegerLiteralExpr) return "int";
    if (e instanceof LongLiteralExpr) return "long";
    if (e instanceof DoubleLiteralExpr) return "double";
    if (e instanceof StringLiteralExpr) return "String";
    if (e instanceof NullLiteralExpr) return "null";
    if (e instanceof BooleanLiteralExpr) return "boolean";
    return "other";
  }
}
