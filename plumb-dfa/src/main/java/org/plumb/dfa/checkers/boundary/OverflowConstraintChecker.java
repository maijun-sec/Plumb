/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.plumb.dfa.checkers.boundary;

import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;
import org.plumb.tac.ir.UnaryOperator;

/**
 * CWE-190: integer overflow / wrap.
 *
 * <p>Fires when an arithmetic op or {@code ++} unary on int / long / byte / short could wrap around
 * the type's upper bound. The {@link Range} lattice from {@link BoundaryConstraintChecker} + the
 * inherited {@link org.plumb.dfa.solver.GuardStore} + Z3 bridge give us:
 *
 * <ul>
 *   <li><b>Constant-only</b>: {@code int x = MAX_VALUE; x + 1;} -- range says {@code x = 127} (byte
 *       case) or {@code x = 2147483647} (int); arithmetic overflows ⇒ fire.
 *   <li><b>Variable with literal guard</b>: {@code if (a &lt; MAX) { a + 1; }} -- range says {@code
 *       a &lt; MAX} ⇒ {@code a+1 ≤ MAX} ⇒ safe; don't fire.
 *   <li><b>Variable with compound guard</b>: {@code if (a &lt; MAX - 5) { x = a; x++; }} -- Range
 *       says {@code a &lt; MAX-5}; {@code MOVE x = a} narrows {@code x &lt; MAX-5}; {@code x++} ⇒
 *       {@code x+1 ≤ MAX-4} ⇒ safe; don't fire (Z3 confirms).
 *   <li><b>Type-bounded</b>: {@code byte b = (byte)(a + 1);} -- dest is byte, range narrows to
 *       [-128, 127]; if the interval extended outside ⇒ fire.
 * </ul>
 */
public final class OverflowConstraintChecker extends BoundaryConstraintChecker {

  public OverflowConstraintChecker() {
    super(
        new TaintRule(
            "dfa-boundary-integer-overflow",
            "dfa-boundary-integer-overflow",
            "numeric.binop-arith.sinks",
            "CWE-190",
            "HIGH",
            TaintDomain.NumericTaint.INSTANCE));
  }

  @Override
  protected String hazardKind() {
    return "overflow";
  }

  @Override
  protected boolean isBoundarySensitive(TacInstruction ins) {
    if (!(ins.dest instanceof TacOperand.TacVar dest)) return false;
    if (!isArithType(dest.type())) return false;
    // Skip string concatenation — `"foo" + x`
    // is a Java StringBuilder op, not an
    // integer arithmetic hazard.
    if (ins.kind == TacInstruction.Kind.BINOP && isStringConcat(ins.lhs1, ins.lhs2, dest)) {
      return false;
    }
    switch (ins.kind) {
      case BINOP -> {
        return ins.binop == BinaryOperator.ADD
            || ins.binop == BinaryOperator.SUB
            || ins.binop == BinaryOperator.MUL;
      }
      case UNARYOP -> {
        return ins.unop == UnaryOperator.PRE_INC || ins.unop == UnaryOperator.POST_INC;
      }
      default -> {
        return false;
      }
    }
  }

  private static boolean isStringConcat(TacOperand lhs1, TacOperand lhs2, TacOperand.TacVar dest) {
    if (dest.type() == TacType.STRING) return true;
    if (lhs1 instanceof TacOperand.Const c && c.type() == TacType.STRING) return true;
    if (lhs2 instanceof TacOperand.Const c && c.type() == TacType.STRING) return true;
    if (lhs1 instanceof TacOperand.TacVar v && v.type() == TacType.STRING) return true;
    return lhs2 instanceof TacOperand.TacVar v && v.type() == TacType.STRING;
  }

  @Override
  protected String sinkPredicate(TacInstruction ins, AnalysisContext ctx) {
    if (!(ins.dest instanceof TacOperand.TacVar dest)) return null;
    TacType t = dest.type();
    Range typeR = Range.ofType(t);
    if (typeR.isBottom) return null;
    if (ins.kind == TacInstruction.Kind.BINOP) {
      return buildBinopPredicate(ins, dest, typeR);
    }
    if (ins.kind == TacInstruction.Kind.UNARYOP) {
      return buildIncPredicate(ins, dest, typeR);
    }
    return null;
  }

  private String buildBinopPredicate(TacInstruction ins, TacOperand.TacVar dest, Range typeR) {
    String lhs = operandName(ins.lhs1);
    String rhs = operandName(ins.lhs2);
    String op =
        switch (ins.binop) {
          case ADD -> "+";
          case SUB -> "-";
          case MUL -> "*";
          default -> null;
        };
    if (op == null) return null;
    // Predicate: the result can overflow iff
    //   lhs OP rhs is outside [typeR.lo, typeR.hi].
    // We phrase as "result > typeR.hi || result < typeR.lo"
    // — local solver handles this directly.
    String resultExpr = "(" + lhs + " " + op + " " + rhs + ")";
    String overflow =
        "((" + resultExpr + ") > " + typeR.hi + " || (" + resultExpr + ") < " + typeR.lo + ")";
    return overflow;
  }

  private String buildIncPredicate(TacInstruction ins, TacOperand.TacVar dest, Range typeR) {
    String lhs = operandName(ins.lhs1);
    // `x++` overflows iff x == max(t)
    String overflow =
        "(" + lhs + " > " + (typeR.hi - 1) + " || " + lhs + " < " + (typeR.lo - 1) + ")";
    return overflow;
  }

  private static String operandName(TacOperand op) {
    if (op instanceof TacOperand.TacVar v) return v.name();
    if (op instanceof TacOperand.Const c) {
      Object val = c.value();
      if (val instanceof Number n) return n.toString();
    }
    // Use a fresh variable that the solver
    // will treat as free (consistent with
    // our conservative SAT for unbound).
    return "_" + System.identityHashCode(op);
  }

  private static boolean isArithType(TacType t) {
    if (t == null) return false;
    return switch (t.kind()) {
      case BYTE, SHORT, INT, LONG, UNKNOWN -> true;
      default -> false;
    };
  }
}
