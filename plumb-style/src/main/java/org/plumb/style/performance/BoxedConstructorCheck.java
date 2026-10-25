package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Boxed constructor usage ({@code new Integer(...)} / {@code new Long(...)} / {@code new
 * Double(...)}). Use {@code Integer.valueOf} or autoboxing instead.
 */
public final class BoxedConstructorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-boxed-constructor", "BOXED_CONSTRUCTOR"),
          "Boxed constructor usage",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.BoxedConstructorCheck",
          true,
          List.of("ast-performance-constructor", "boxed-constructor"));

  @Override
  public String name() {
    return "boxed-constructor";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr o, StyleContext ctx) {
    String s = o.getType().getNameAsString();
    if (!"Integer".equals(s) && !"Long".equals(s) && !"Double".equals(s)) return;
    int line = o.getBegin().map(p -> p.line).orElse(-1);
    int col = o.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(line, col, DEFAULT_RULE, "Boxed constructor usage: " + s, RuleDef.Severity.LOW, s);
  }
}
