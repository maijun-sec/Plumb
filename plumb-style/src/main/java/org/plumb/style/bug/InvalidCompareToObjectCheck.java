/*
 * compareTo() comparing against Object.
 *
 * <p><b>R-818 (2026-10-14): pattern D rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.findAll(MethodDeclaration)} (1 walk) + for each
 * method {@code m.findAll(BinaryExpr)} (1 walk per method). The
 * new implementation uses {@code visit(MethodDeclaration)} +
 * a single walk with instanceof dispatch. Total: 1 walk per
 * file (was 1 + M walks).</p>
 */
package org.plumb.style.bug;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class InvalidCompareToObjectCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-invalidcomparetoobject", "INVALIDCOMPARETOOBJECT"),
          "compareTo() comparing against Object",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.InvalidCompareToObjectCheck",
          true,
          List.of("ast-bug-invalid-compareto-object", "invalidcomparetoobjectcheck")
      );

  @Override
  public String name() {
    return "invalidcomparetoobject";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!m.getNameAsString().equals("compareTo")) return;
    m.walk(
        com.github.javaparser.ast.Node.class,
        node -> {
          if (node instanceof BinaryExpr be
              && be.getOperator() == BinaryExpr.Operator.EQUALS
              && be.getRight().toString().equals("null")) {
            int line = be.getBegin().map(p -> p.line).orElse(-1);
            int col = be.getBegin().map(p -> p.column).orElse(-1);
            ctx.report(
                line,
                col,
                DEFAULT_RULE,
                "compareTo() argument should not be compared to null",
                RuleDef.Severity.LOW,
                "compareTo");
          }
        });
  }
}
