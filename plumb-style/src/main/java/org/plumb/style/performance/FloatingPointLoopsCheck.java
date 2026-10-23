package org.plumb.style.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * {@code for} loop with a {@code float} / {@code double} counter.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(ForStmt.class)} before.
 */
public final class FloatingPointLoopsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-floating-point-loops", "FLOATING_POINT_LOOPS"),
          "for loop using floating-point counter",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.FloatingPointLoopsCheck",
          true,
          List.of("ast-performance-floating-point-loops", "floating-point-loops")
      );

  @Override
  public String name() {
    return "floating-point-loops";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt f, StyleContext ctx) {
    if (f.getInitialization().size() != 1) return;
    Node init = f.getInitialization().get(0);
    if (!(init instanceof VariableDeclarationExpr vde)) return;
    if (vde.getVariables().isEmpty()) return;
    VariableDeclarator v = vde.getVariable(0);
    String t = vde.getElementType().asString();
    if (!"float".equals(t) && !"double".equals(t)) return;
    int line = f.getBegin().map(p -> p.line).orElse(-1);
    int col = f.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Floating-point for loop counter; use int with division",
        RuleDef.Severity.MEDIUM,
        t);
  }
}