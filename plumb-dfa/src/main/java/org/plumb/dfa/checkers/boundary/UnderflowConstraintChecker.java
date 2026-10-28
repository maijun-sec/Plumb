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
 * CWE-191: integer underflow.
 *
 * <p>Fires when an arithmetic op ({@code -} or {@code *}, both -C2 / Lesson 912) or {@code --}
 * unary on int / long / byte / short could wrap below the type's lower bound.
 *
 * <p>Multiplication is a CWE-191 hazard because for byte / short a large positive times a large
 * positive can wrap to a negative (Juliet CWE191 byte_min_multiply).
 */
public final class UnderflowConstraintChecker extends BoundaryConstraintChecker {

  public UnderflowConstraintChecker() {
    super(
        new TaintRule(
            "dfa-boundary-integer-underflow",
            "dfa-boundary-integer-underflow",
            "numeric.binop-arith.sinks",
            "CWE-191",
            "HIGH",
            TaintDomain.NumericTaint.INSTANCE));
  }

  @Override
  protected String hazardKind() {
    return "underflow";
  }

  @Override
  protected boolean isBoundarySensitive(TacInstruction ins) {
    if (!(ins.dest instanceof TacOperand.TacVar dest)) return false;
    if (!isArithType(dest.type())) return false;
    switch (ins.kind) {
      case BINOP -> {
        // SUB and MUL both underflow in the
        // worst case for signed types.
        return ins.binop == BinaryOperator.SUB || ins.binop == BinaryOperator.MUL;
      }
      case UNARYOP -> {
        return ins.unop == UnaryOperator.PRE_DEC || ins.unop == UnaryOperator.POST_DEC;
      }
      default -> {
        return false;
      }
    }
  }

  @Override
  protected String sinkPredicate(TacInstruction ins, AnalysisContext ctx) {
    if (!(ins.dest instanceof TacOperand.TacVar dest)) return null;
    TacType t = dest.type();
    // D: TAC IR often leaves UNARYOP / BINOP
    // dest type as <unknown> (the front-end
    // hasn't propagated the operand type to
    // the new temp). Fall back to lhs1 type
    // so byte/short arithmetic emits the
    // byte/short MIN_VALUE predicate instead
    // of the int default. Without this,
    // intervalRefutesSinkPredicate compares
    // byte ranges (e.g. data == -128) against
    // the int default predicate `(data <
    // -2147483647)` and incorrectly refutes
    // the finding.
    if (t == TacType.UNKNOWN && ins.lhs1 instanceof TacOperand.TacVar lv) {
      TacType lt = lv.type();
      if (lt != null && lt != TacType.UNKNOWN) {
        t = lt;
      }
    }
    Range typeR = Range.ofType(t);
    if (typeR.isBottom) return null;
    if (ins.kind == TacInstruction.Kind.BINOP) {
      return buildBinopPredicate(ins, typeR);
    }
    if (ins.kind == TacInstruction.Kind.UNARYOP) {
      return buildDecPredicate(ins, typeR);
    }
    return null;
  }

  private String buildBinopPredicate(TacInstruction ins, Range typeR) {
    String lhs = operandName(ins.lhs1);
    String rhs = operandName(ins.lhs2);
    String op =
        switch (ins.binop) {
          case SUB -> "-";
          case MUL -> "*";
          default -> null;
        };
    if (op == null) return null;
    String resultExpr = "(" + lhs + " " + op + " " + rhs + ")";
    // Underflow = result < typeR.lo.
    return "((" + resultExpr + ") < " + typeR.lo + ")";
  }

  private String buildDecPredicate(TacInstruction ins, Range typeR) {
    String lhs = operandName(ins.lhs1);
    // `x--` underflows iff x == min(t)
    return "(" + lhs + " < " + (typeR.lo + 1) + ")";
  }

  private static String operandName(TacOperand op) {
    if (op instanceof TacOperand.TacVar v) return v.name();
    if (op instanceof TacOperand.Const c) {
      Object val = c.value();
      if (val instanceof Number n) return n.toString();
    }
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
