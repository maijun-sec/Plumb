package org.plumb.style.performance;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Return an empty array instead of {@code null} for array-returning methods.
 *
 * <p>M5 part-3 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(ReturnStmt.class)} before. The parent-method lookup is
 * preserved via {@code n.findAncestor(MethodDeclaration.class)} so we still skip non-array
 * returns.
 */
public final class PreferZeroLengthArraysCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-prefer-zero-length-arrays", "PREFER_ZERO_LENGTH_ARRAYS"),
          "return null instead of empty array",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.PreferZeroLengthArraysCheck",
          true,
          List.of("ast-performance-prefer-zero-length-arrays", "prefer-zero-length-arrays")
      );

  @Override
  public String name() {
    return "prefer-zero-length-arrays";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ReturnStmt r, StyleContext ctx) {
    if (!(r.getExpression().orElse(null) instanceof NullLiteralExpr)) return;
    // Walk ancestors to find the enclosing method
    // declaration; only flag if the method's declared
    // return type is an array.
    MethodDeclaration m = r.findAncestor(MethodDeclaration.class).orElse(null);
    if (m == null) return;
    if (!m.getTypeAsString().endsWith("[]")) return;
    int line = r.getBegin().map(p -> p.line).orElse(-1);
    int col = r.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Return empty array instead of null for array-returning method",
        RuleDef.Severity.MEDIUM,
        m.getNameAsString());
  }
}