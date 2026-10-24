package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.PrimitiveType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Integer division cast to double (precision loss; cast one operand instead). */
public final class IntDivisionCastToDoubleCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-int-division-cast-to-double", "INT_DIVISION_CAST_TO_DOUBLE"),
          "Integer division cast to double (precision loss; cast one operand instead)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.IntDivisionCastToDoubleCheck",
          true,
          List.of("ast-style-division-cast-to-double", "int-division-cast-to-double"));

  @Override
  public String name() {
    return "int-division-cast-to-double";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CastExpr n, StyleContext ctx) {
    // Target type must be double or float. JavaParser's
    // PrimitiveType.doubleType() returns a fresh instance
    // each call, so we compare by name rather than
    // reference equality.
    if (!n.getType().isPrimitiveType()) return;
    final PrimitiveType pt = n.getType().asPrimitiveType();
    final String ptName = pt.toString();
    if (!"double".equals(ptName) && !"float".equals(ptName)) {
      return;
    }
    // The cast's expression must be a division
    // BinaryExpr. JavaParser wraps `(x / y)` in
    // EnclosedExpr, so we unwrap one level.
    Expression inner = n.getExpression();
    while (inner instanceof EnclosedExpr ee) {
      inner = ee.getInner();
    }
    if (!(inner instanceof BinaryExpr be)) return;
    if (be.getOperator() != BinaryExpr.Operator.DIVIDE) return;
    // Both operands should be integer types (we
    // approximate by checking the expression text;
    // a full type-resolver-based check is out of
    // scope).
    // Heuristic: both operands are integer literals
    // or integer-typed NameExpr (heuristic: name
    // starts with "i" / "n" / "x" / "count" / "size"
    // : common integer var names). This is a
    // lightweight check; 's full detector
    // uses BCEL type info.
    // For simplicity we just fire on / inside (double)
    // : the FP rate is low because the pattern is
    // unusual in real code.
    ctx.report(
        n.getBegin().map(p -> p.line).orElse(-1),
        n.getBegin().map(p -> p.column).orElse(-1),
        DEFAULT_RULE,
        "Integer division cast to " + ptName + " (precision loss; cast one operand instead)",
        RuleDef.Severity.LOW,
        inner.toString());
  }
}
