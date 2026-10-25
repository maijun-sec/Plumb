/*
 * ? BadShift check (CWE-758) migrated from
 * plumb-dfa/.../checkers/BadShiftChecker.java to
 * plumb-style AST.
 *
 * <p>Detects suspicious shift counts in {@code <<},
 * {@code >>}, {@code >>>}. Two patterns fire when the
 * right-hand side is a compile-time integer literal:</p>
 *
 * <ol>
 *   <li><b>Negative count</b> — throws
 *       {@code ArithmeticException} at runtime in Java
 *       (e.g. {@code 1 << -1}).</li>
 *   <li><b>Count &ge; type bit width</b> — almost always a
 *       logic bug: {@code 1 << 32} on an int silently
 *       becomes {@code 1}, and {@code 1L << 64} on a long
 *       becomes {@code 1L}.</li>
 * </ol>
 *
 * <p>Variable shift counts are not flagged here (range
 * analysis out of scope; would be a taint/z3 step if
 * needed). The bit width is read from the source
 * operand's resolved type: long uses a 6-bit mask
 * (0..63); int / byte / short / char all promote to int
 * (5-bit mask, 0..31).</p>
 *
 * <p><b>Migration note.</b> The previous DFA half walked
 * TAC IR and unwrapped a single {@code MOVE} or
 * {@code UNARYOP NEG} to recover a constant. The AST
 * version only fires on integer-literal RHS ? the
 * equivalent of "MOVE int" with no further unwrapping.
 * This is a strict subset of the DFA half: the AST
 * version is more conservative (fewer FPs at the cost of
 * missing e.g. {@code int n = -1; 1 << n}). Re-enable the
 * DFA half if the DFA coverage gap matters; otherwise the
 * AST half is the canonical implementation.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.types.ResolvedPrimitiveType;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** CWE-758: shift amount out of range (negative or &ge; bit width). */
public final class BadShiftCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-bad-shift", "BAD_SHIFT"),
          "Shift count is negative or exceeds the operand's bit width (CWE-758)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-758"),
          "org.plumb.style.style.BadShiftCheck",
          true,
          List.of("dfa-style-bad-shift", "bad-shift"));

  @Override
  public String name() {
    return "bad-shift";
  }

  @Override
  public void init(StyleContext ctx) {
    // stateless
  }

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    Operator op = n.getOperator();
    if (op != Operator.LEFT_SHIFT
        && op != Operator.SIGNED_RIGHT_SHIFT
        && op != Operator.UNSIGNED_RIGHT_SHIFT) {
      return;
    }
    Expression rhs = n.getRight();
    long count = literalCount(rhs);
    if (count == Long.MIN_VALUE) {
      // not a literal — DFA half would have unwrapped
      // a single MOVE / UNARYOP NEG. Conservative:
      // skip.
      return;
    }
    int bitWidth = bitWidthOfLhs(n);
    String reason;
    if (count < 0) {
      reason = "negative shift count " + count + " (throws ArithmeticException at runtime)";
    } else if (count >= bitWidth) {
      reason =
          "shift count "
              + count
              + " >= type bit width "
              + bitWidth
              + " (silently masked; likely a bug)";
    } else {
      return; // safe
    }
    ctx.report(
        n.getBegin().map(p -> p.line).orElse(-1),
        n.getBegin().map(p -> p.column).orElse(-1),
        DEFAULT_RULE,
        "Suspicious shift: " + reason + " (CWE-758)",
        RuleDef.Severity.MEDIUM,
        n.toString());
  }

  /**
   * Try to extract a constant int / long value from a literal expression. Returns {@link
   * Long#MIN_VALUE} when the RHS is not a literal ? the visit() caller treats that as "not a static
   * constant, skip".
   */
  private static long literalCount(Expression e) {
    if (e instanceof IntegerLiteralExpr il) {
      try {
        return Long.parseLong(il.asNumber().toString());
      } catch (NumberFormatException nfe) {
        return Long.MIN_VALUE;
      }
    }
    if (e instanceof LongLiteralExpr ll) {
      String s = ll.asNumber().toString();
      if (s.endsWith("L") || s.endsWith("l")) s = s.substring(0, s.length() - 1);
      try {
        return Long.parseLong(s);
      } catch (NumberFormatException nfe) {
        return Long.MIN_VALUE;
      }
    }
    // Unary minus on an integer literal — the AST
    // parses `1 << -1` as `1 << UnaryExpr(NEG, 1)`.
    // Unwrap the single negation so the rule still
    // fires on this case. Compound expressions
    // (`-(1 << 2)` etc.) are out of scope.
    if (e instanceof UnaryExpr ue && ue.getOperator() == UnaryExpr.Operator.MINUS) {
      long inner = literalCount(ue.getExpression());
      if (inner == Long.MIN_VALUE) return Long.MIN_VALUE;
      return -inner;
    }
    return Long.MIN_VALUE;
  }

  /**
   * Bit width derived from the LHS expression's declared type. Falls back to 32 (int) when the type
   * is unknown ? matches the previous DFA half's assumption.
   *
   * <p>The signature accepts the surrounding {@link Expression} as well so we can inspect a wider
   * context when the LHS itself is a literal (e.g. {@code 1 << 32}: the literal's type comes from
   * the assignment's declared type, not from the literal node).
   */
  private static int bitWidthOfLhs(BinaryExpr n) {
    // Try the LHS's resolved type first.
    try {
      ResolvedType rt = n.getLeft().calculateResolvedType();
      if (rt instanceof ResolvedPrimitiveType pt) {
        switch (pt) {
          case LONG:
            return 64;
          case INT:
          case SHORT:
          case BYTE:
          case CHAR:
          case BOOLEAN:
            return 32;
          default:
            return 32;
        }
      }
    } catch (RuntimeException ignore) {
      // unresolved type — fall through
    }
    // Walk up to find a typed context: the enclosing
    // VariableDeclarator's declared type is the most
    // reliable signal. For an expression like
    //   int x = 1 << 32;
    // the LHS literal "1" has no symbol; only the
    // assignment's declared type is knowable.
    com.github.javaparser.ast.Node parent = n;
    while (parent.getParentNode().isPresent()) {
      parent = parent.getParentNode().get();
      if (parent instanceof VariableDeclarator vd) {
        Type t = vd.getType();
        if (t instanceof PrimitiveType pt) {
          // PrimitiveType exposes its kind as
          // the toString of the asString() form
          // ("long" / "int" / etc.) — but the
          // canonical 3.26 API is the
          // asString() method.
          String kind = pt.asString();
          if ("long".equals(kind)) return 64;
          if ("int".equals(kind)
              || "short".equals(kind)
              || "byte".equals(kind)
              || "char".equals(kind)
              || "boolean".equals(kind)) return 32;
        }
        if (t instanceof ClassOrInterfaceType ct && "Long".equals(ct.getNameAsString())) {
          return 64;
        }
        // Unknown declared type — stop here.
        break;
      }
      if (parent instanceof com.github.javaparser.ast.expr.CastExpr) {
        Type t = ((com.github.javaparser.ast.expr.CastExpr) parent).getType();
        if (t instanceof PrimitiveType pt && "long".equals(pt.asString())) {
          return 64;
        }
      }
    }
    return 32;
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return "RULE: Shift amount out of range or by a non-primitive (CWE-758). "
        + "This rule fires when an int / long is shifted by an amount outside "
        + "[0, 31] / [0, 63] or by a value that may be negative.\n"
        + "\n"
        + "A finding is a TRUE POSITIVE (TP) when the shift amount is a "
        + "compile-time literal outside the valid range.\n"
        + "\n"
        + "A finding is a FALSE POSITIVE (FP) when the shift amount is in "
        + "[0, 31] / [0, 63] or is validated before the shift.\n"
        + "\n"
        + "UNKNOWN when the shift amount is variable but untraceable.";
  }
}
