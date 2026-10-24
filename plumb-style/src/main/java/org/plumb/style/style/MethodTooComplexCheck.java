/*
 * method cyclomatic complexity > 10.
 *
 * <p><b>R-817 (2026-10-14): pattern A rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.findAll(MethodDeclaration)} (1 walk) + a per-method
 * {@code complexity(m)} helper that called 10 separate
 * {@code m.findAll(X.class)} per method (10 walks per method).
 * Worst case: 1 + 10·M walks per file. The new implementation
 * uses {@code visit(CompilationUnit)} + a single
 * {@code m.findAll(Node.class)} per method, with an
 * {@code instanceof} dispatch on the same visitor callback
 * (1 walk per method). Nested anonymous-class methods are
 * independently counted by the outer MethodDeclaration.findAll
 * (because they appear as children of the outer method's
 * body) : this matches the original behavior, where each
 * m.findAll(X.class) returned ALL X in m's subtree, including
 * those inside anonymous inner classes. So semantic equivalence
 * is preserved. Total: 1 + 1·M walks per file. Fire per-method
 * during the visit(CU) loop (no per-node state needed).</p>
 */
package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class MethodTooComplexCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-method-too-complex", "METHOD_TOO_COMPLEX"),
          "method cyclomatic complexity > 10",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.MethodTooComplexCheck",
          true,
          List.of("ast-style-method-too-complex", "method-too-complex"));

  @Override
  public String name() {
    return "method-too-complex";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    int complexity = 1; // base
    // One walk per method (vs. 10 in the original).
    for (Node node : m.findAll(Node.class)) {
      if (node instanceof IfStmt
          || node instanceof ForStmt
          || node instanceof ForEachStmt
          || node instanceof WhileStmt
          || node instanceof DoStmt
          || node instanceof CatchClause) {
        complexity++;
      } else if (node instanceof SwitchEntry se && !se.getLabels().isEmpty()) {
        complexity++;
      } else if (node instanceof BinaryExpr be
          && (be.getOperator() == BinaryExpr.Operator.AND
              || be.getOperator() == BinaryExpr.Operator.OR)) {
        complexity++;
      } else if (node instanceof ConditionalExpr) {
        complexity++;
      }
    }
    if (complexity > 10) {
      int line = m.getBegin().map(p -> p.line).orElse(-1);
      int col = m.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Method "
              + m.getNameAsString()
              + " has cyclomatic complexity "
              + complexity
              + " > 10; refactor",
          RuleDef.Severity.MEDIUM,
          m.getNameAsString());
    }
  }
}
