/*
 * iterator.next() without hasNext() check.
 *
 * <p><b>R-818 (2026-10-14): pattern A rewrite.</b> The previous
 * implementation called {@code n.findAll(MethodCallExpr)} +
 * {@code n.findAll(StringLiteralExpr)} (2 walks). The new
 * implementation uses a single
 * {@code n.walk(Node.class, ...)} with instanceof dispatch.
 * Total: 1 walk per file.</p>
 */
package org.plumb.style.error;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class IteratorNextExceptionCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-iterator-next-exception", "ITERATOR_NEXT_EXCEPTION"),
          "iterator.next() without hasNext() check",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of("CWE-754"),
          "org.plumb.style.IteratorNextExceptionCheck",
          true,
          List.of("ast-error-iterator-next-exception", "iterator-next-exception")
      );

  @Override
  public String name() {
    return "iterator-next-exception";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(com.github.javaparser.ast.CompilationUnit n, StyleContext ctx) {
    n.walk(
        com.github.javaparser.ast.Node.class,
        node -> {
          if (node instanceof MethodCallExpr mc) {
            if (!mc.getNameAsString().equals("next")) return;
            if (!mc.getScope().isPresent()) return;
            String scope = mc.getScope().get().toString();
            if (scope.endsWith(".iterator()")) {
              int line = mc.getBegin().map(p -> p.line).orElse(-1);
              int col = mc.getBegin().map(p -> p.column).orElse(-1);
              ctx.report(
                  line,
                  col,
                  DEFAULT_RULE,
                  ".iterator().next() called without hasNext() check",
                  RuleDef.Severity.LOW,
                  mc.toString());
            }
          } else if (node instanceof StringLiteralExpr s) {
            if (s.getValue().contains(".iterator().next()")) {
              int line = s.getBegin().map(p -> p.line).orElse(-1);
              int col = s.getBegin().map(p -> p.column).orElse(-1);
              ctx.report(
                  line,
                  col,
                  DEFAULT_RULE,
                  ".iterator().next() chained call without hasNext()",
                  RuleDef.Severity.LOW,
                  s.getValue());
            }
          }
        });
  }
}
