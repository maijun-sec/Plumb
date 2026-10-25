/*
 * nested loops have same condition.
 *
 * <p><b>pattern D rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.findAll(ForStmt)} (1 walk) + for each
 * {@code outer.getBody().findAll(ForStmt)} (1 walk per outer
 * loop). The new implementation uses
 * {@code visit(ForStmt)} dispatched by {@code AstDispatcher} +
 * a single body.walk on the outer loop's body. Total: 1 walk
 * per file (was 1 + N_outer walks).</p>
 */
package org.plumb.style.performance;

import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class LoopsWithSameConditionCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-loops-same-condition", "LOOPS_SAME_CONDITION"),
          "nested loops have same condition",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.LoopsWithSameConditionCheck",
          true,
          List.of("ast-performance-same-condition", "loops-same-condition"));

  @Override
  public String name() {
    return "loops-same-condition";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt outer, StyleContext ctx) {
    if (outer.getBody() == null) return;
    if (!outer.getCompare().isPresent()) return;
    String outerCond = outer.getCompare().get().toString();
    // Walk only the immediate body subtree for inner ForStmt.
    outer
        .getBody()
        .walk(
            ForStmt.class,
            inner -> {
              if (inner == outer) return;
              if (!inner.getCompare().isPresent()) return;
              if (!inner.getCompare().get().toString().equals(outerCond)) return;
              int line = inner.getBegin().map(p -> p.line).orElse(-1);
              int col = inner.getBegin().map(p -> p.column).orElse(-1);
              ctx.report(
                  line,
                  col,
                  DEFAULT_RULE,
                  "Nested loops have same condition: " + outerCond,
                  RuleDef.Severity.LOW,
                  inner.getCompare().get().toString());
            });
  }
}
