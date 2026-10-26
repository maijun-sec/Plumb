package org.plumb.dfa.checkers.boundary;

import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * CWE-369: division or modulo by zero.
 *
 * <p>Fires when {@code a / b} or {@code a % b} on int / long could have {@code b == 0}. The {@link
 * Range} lattice tracks the divisor's range:
 *
 * <ul>
 *   <li><b>Constant divisor</b>: {@code x / 2} ⇒ divisor range {@code {2}} ⇒ canBeZero() = false ⇒
 *       don't fire.
 *   <li><b>Constant 0</b>: {@code x / 0} ⇒ divisor range {@code {0}} ⇒ canBeZero() = true ⇒ fire.
 *   <li><b>Unknown divisor</b>: {@code x / data} ⇒ divisor range is BOTTOM ⇒ canBeZero() = true ⇒
 *       fire (unless a guard says otherwise).
 *   <li><b>Guarded</b>: {@code if (data != 0) { x / data; }} -- DivZeroChecker's existing guard
 *       (`data != 0`) is recorded in {@link org.plumb.dfa.solver.GuardStore}; we consume it via
 *       {@code impliedGuard} and don't fire.
 * </ul>
 */
public final class DivZeroConstraintChecker extends BoundaryConstraintChecker {

  public DivZeroConstraintChecker() {
    super(
        new TaintRule(
            "dfa-boundary-divide-by-zero",
            "dfa-boundary-divide-by-zero",
            "numeric.binop-arith.sinks",
            "CWE-369",
            "HIGH",
            TaintDomain.NumericTaint.INSTANCE));
  }

  @Override
  protected String hazardKind() {
    return "divide-by-zero";
  }

  @Override
  protected boolean isBoundarySensitive(TacInstruction ins) {
    if (ins.kind != TacInstruction.Kind.BINOP) return false;
    if (ins.binop != BinaryOperator.DIV && ins.binop != BinaryOperator.MOD) {
      return false;
    }
    if (!(ins.dest instanceof TacOperand.TacVar dest)) return false;
    if (!isArithType(dest.type())) return false;
    return true;
  }

  @Override
  protected String sinkPredicate(TacInstruction ins, AnalysisContext ctx) {
    if (!(ins.lhs2 instanceof TacOperand.TacVar rhs)) {
      // Constant divisor — if 0, fire.
      if (ins.lhs2 instanceof TacOperand.Const c) {
        Object v = c.value();
        if (v instanceof Number n && n.longValue() == 0) {
          return "true"; // divisor is 0 → always fires
        }
        return null; // non-zero constant → safe
      }
      return null;
    }
    // Build predicate: rhs == 0
    return "(" + rhs.name() + " == 0)";
  }

  private static boolean isArithType(TacType t) {
    if (t == null) return false;
    return switch (t.kind()) {
      case BYTE, SHORT, INT, LONG, UNKNOWN -> true;
      default -> false;
    };
  }
}
