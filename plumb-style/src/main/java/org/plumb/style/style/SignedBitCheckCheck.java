package org.plumb.style.style;

import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.expr.UnaryExpr.Operator;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** bitwise check on a signed value : fragile sign extension (BIT_SIGNED_CHECK). */
public final class SignedBitCheckCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-bit-signed-check", "BIT_SIGNED_CHECK"),
          "bitwise check on a signed value : fragile sign extension (BIT_SIGNED_CHECK)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-682"),
          "org.plumb.style.SignedBitCheckCheck",
          true,
          List.of("ast-style-signed-check", "bit-signed-check"));

  @Override
  public String name() {
    return "bit-signed-check";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    // JavaParser enum quirk: AND/OR/XOR are the logical
    // operators (&&/||/^^); BINARY_AND/BINARY_OR/BINARY_XOR
    // are the bitwise ones (&/|/^). We target bitwise.
    if (n.getOperator() != BinaryExpr.Operator.BINARY_AND
        && n.getOperator() != BinaryExpr.Operator.BINARY_OR
        && n.getOperator() != BinaryExpr.Operator.XOR) return;
    if (!looksLikeSignedMask(n.getLeft()) && !looksLikeSignedMask(n.getRight())) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Bitwise check with a high-bit mask : the literal is "
            + "interpreted as a negative int. Use a relational check "
            + "(< 0 for sign, & 1 for odd) instead.",
        RuleDef.Severity.MEDIUM,
        "0x8");
  }

  private static boolean looksLikeSignedMask(Expression e) {
    // A negative integer / long literal (e.g. 0x80000000
    // or -2147483648) is the canonical "high bit" mask.
    if (e instanceof UnaryExpr ue
        && ue.getOperator() == Operator.MINUS
        && (ue.getExpression() instanceof IntegerLiteralExpr
            || ue.getExpression() instanceof LongLiteralExpr)) {
      return true;
    }
    // A long literal whose value is in the high-bit range.
    if (e instanceof LongLiteralExpr ll) {
      try {
        long v = Long.parseLong(ll.getValue().replace("_", "").replace("L", "").replace("l", ""));
        return v == 0x80000000L || v == 0xFFFFFFFFL || v == Long.MIN_VALUE;
      } catch (NumberFormatException ex) {
        return false;
      }
    }
    return false;
  }
}
