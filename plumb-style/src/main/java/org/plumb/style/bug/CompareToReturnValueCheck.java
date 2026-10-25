/*
 * compareTo must return -1/0/1.
 *
 * <p><b>pattern D rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.findAll(MethodDeclaration)} (1 walk) + for each
 * method {@code m.findAll(ReturnStmt)} (1 walk per method).
 * The new implementation uses
 * {@code visit(MethodDeclaration)} dispatched by
 * {@code AstDispatcher} + a single
 * {@code m.walk(Node.class, ...)} walk. Total: 1 walk per
 * file (was 1 + M walks).</p>
 */
package org.plumb.style.bug;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class CompareToReturnValueCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-compareto-return-value", "COMPARETO_RETURN_VALUE"),
          "compareTo must return -1/0/1",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CompareToReturnValueCheck",
          true,
          List.of("ast-bug-compareto-return-value", "compareto-return-value"));

  @Override
  public String name() {
    return "compareto-return-value";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!m.getNameAsString().equals("compareTo")) return;
    m.walk(
        com.github.javaparser.ast.Node.class,
        node -> {
          if (node instanceof ReturnStmt ret
              && ret.getExpression().isPresent()
              && ret.getExpression().get() instanceof IntegerLiteralExpr) {
            int v = ((IntegerLiteralExpr) ret.getExpression().get()).asNumber().intValue();
            if (v < -1 || v > 1) {
              int line = ret.getBegin().map(p -> p.line).orElse(-1);
              int col = ret.getBegin().map(p -> p.column).orElse(-1);
              ctx.report(
                  line,
                  col,
                  DEFAULT_RULE,
                  "compareTo should return -1, 0, or 1 — got " + v,
                  RuleDef.Severity.LOW,
                  "compareTo");
            }
          }
        });
  }
}
