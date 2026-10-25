package org.plumb.dfa.checkers.boundary;

import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * CWE-129: improper validation of array index (out-of-bounds read/write/alloc).
 *
 * <p>Fires when the index to {@code arr[i]}, {@code arr[i] = ...}, or {@code new T[i]} could be
 * &lt; 0 (no underflow possible for length because Java throws NegativeArraySizeException at
 * allocation time, which is also a CWE-129 hazard).
 *
 * <p>The {@link Range} lattice tracks the index's interval; the inherited {@link
 * BoundaryConstraintChecker#sinkPredicate} builds the SMT predicate the {@link
 * org.plumb.dfa.solver.SolverZ3Bridge} evaluates against recorded guards.
 *
 * <p>For NEWARRAY specifically, the {@code mem-alloc.sinks} family (sink-families.yaml) only
 * matches CALL patterns (Lesson 918). fixed this for the strict taint checker; this checker handles
 * NEWARRAY independently via {@link #isBoundarySensitive}.
 */
public final class ArrayBoundsConstraintChecker extends BoundaryConstraintChecker {

  public ArrayBoundsConstraintChecker() {
    super(
        new TaintRule(
            "dfa-boundary-array-out-of-bounds",
            "dfa-boundary-array-out-of-bounds",
            "array.sinks",
            "CWE-129",
            "HIGH",
            TaintDomain.NumericTaint.INSTANCE));
  }

  @Override
  protected String hazardKind() {
    return "out-of-bounds";
  }

  @Override
  protected boolean isBoundarySensitive(TacInstruction ins) {
    switch (ins.kind) {
      case ARRAYLOAD, ARRAYSTORE -> {
        // ARRAYLOAD/ARRAYSTORE has the
        // index in lhs2 (per TAC convention).
        if (!(ins.lhs2 instanceof TacOperand.TacVar idx)) return false;
        return isArithType(idx.type());
      }
      case NEWARRAY -> {
        // NEWARRAY has size in lhs1
        // (the array type is in dest's
        // arrayComponent).
        if (!(ins.lhs1 instanceof TacOperand.TacVar size)) return false;
        return isArithType(size.type());
      }
      default -> {
        return false;
      }
    }
  }

  @Override
  protected String sinkPredicate(TacInstruction ins, AnalysisContext ctx) {
    TacOperand.TacVar idx;
    if (ins.kind == TacInstruction.Kind.ARRAYLOAD || ins.kind == TacInstruction.Kind.ARRAYSTORE) {
      if (!(ins.lhs2 instanceof TacOperand.TacVar v)) return null;
      idx = v;
    } else if (ins.kind == TacInstruction.Kind.NEWARRAY) {
      if (!(ins.lhs1 instanceof TacOperand.TacVar v)) return null;
      idx = v;
    } else {
      return null;
    }
    // The bound question is "idx < 0" — for
    // NEWARRAY the only meaningful bound is
    // the lower bound (negative sizes throw).
    // For ARRAYLOAD/STORE we also want the
    // upper bound (>= array.length), but
    // tracking array length is out of scope
    // here (the strict checker handles it
    // via taint + length-based guards).
    return "(" + idx.name() + " < 0)";
  }

  private static boolean isArithType(TacType t) {
    if (t == null) return false;
    return switch (t.kind()) {
      case BYTE, SHORT, INT, LONG, UNKNOWN -> true;
      default -> false;
    };
  }
}
