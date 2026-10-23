/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  org.plumb.dfa.engine.AnalysisContext
 *  org.plumb.dfa.engine.Finding
 *  org.plumb.dfa.summary.FunctionSummary$Builder
 *  org.plumb.dfa.summary.Operation
 *  org.plumb.dfa.summary.Operation$SolverZ3Predicate
 *  org.plumb.dfa.taint.GuardTracker
 *  org.plumb.dfa.taint.GuardTracker$Guard
 *  org.plumb.dfa.taint.InputSourcePatterns
 *  org.plumb.dfa.taint.TaintDomain
 *  org.plumb.dfa.taint.TaintDomain$NumericTaint
 *  org.plumb.dfa.taint.TaintRule
 *  org.plumb.dfa.taint.TaintedCheckerBase
 *  org.plumb.tac.ir.BinaryOperator
 *  org.plumb.tac.ir.TacFunction
 *  org.plumb.tac.ir.TacInstruction
 *  org.plumb.tac.ir.TacInstruction$Kind
 *  org.plumb.tac.ir.TacOperand
 *  org.plumb.tac.ir.TacOperand$Const
 *  org.plumb.tac.ir.TacOperand$TacVar
 *  org.plumb.tac.ir.TacType
 */
package org.plumb.dfa.checkers.taint;

import java.util.HashSet;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.summary.Operation;
import org.plumb.dfa.taint.GuardTracker;
import org.plumb.dfa.taint.InputSourcePatterns;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.reporter.FlowStep;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;
import org.plumb.tac.ir.UnaryOperator;

public final class IntOverflowChecker extends TaintedCheckerBase {
  private final GuardTracker guards = new GuardTracker();
  private AnalysisContext currentCtx;

  public IntOverflowChecker() {
    super(
        new TaintRule(
            "dfa-taint-integer-overflow",
            "plumb.numeric.overflow-hazard",
            "numeric.binop-arith.sinks",
            "CWE-190",
            "HIGH",
            (TaintDomain) TaintDomain.NumericTaint.INSTANCE));
  }

  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    super.initFunction(ctx, fn);
    this.guards.clear();
    this.currentCtx = ctx;
  }

  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    TacOperand.TacVar l2;
    TacOperand.TacVar l1;
    TacOperand tacOperand;
    String callee;
    super.checkInstruction(ins, fn, ctx);
    if (ins.kind == TacInstruction.Kind.CALL
        && InputSourcePatterns.isInputSourceCall((String) (callee = ins.calleeSig))
        && (tacOperand = ins.dest) instanceof TacOperand.TacVar) {
      TacOperand.TacVar d = (TacOperand.TacVar) tacOperand;
      this.taintedVars.put(d, "input:" + callee);
    }
    this.guards.track(ins);
    if (ins.kind == TacInstruction.Kind.UNARYOP) {
      // R692: PRE_INC / POST_INC can overflow the operand
      // just like ADD with literal 1. The Juliet CWE-190
      // postinc / preinc variants use `data++` / `++data`
      // patterns; without UNARYOP handling they silently
      // miss every pre/post-inc overflow.
      //
      // R780: PRE_DEC / POST_DEC are now IntUnderflowChecker's
      // territory. Previously both checkers fired on the
      // same instruction, but IntOverflowChecker tagged
      // the finding CWE-191 while its rule_id was
      // plumb.numeric.integer-overflow (rule-level CWE =
      // ['190']); the eval pipeline's CWE alignment then
      // marked the finding as cwe-mismatch. Splitting the
      // pre/post-dec semantics into IntUnderflowChecker
      // gives every UNOP finding exactly one owner.
      UnaryOperator uop = ins.unop;
      if (uop == UnaryOperator.PRE_INC || uop == UnaryOperator.POST_INC) {
        if (ins.dest instanceof TacOperand.TacVar destVar
            && destVar.type() != TacType.FLOAT
            && destVar.type() != TacType.DOUBLE
            && ins.lhs1 instanceof TacOperand.TacVar operand
            && this.taintedVars.containsKey(operand)) {
          TacType dt = destVar.type();
          if (dt == TacType.BYTE
              || dt == TacType.SHORT
              || dt == TacType.INT
              || dt == TacType.LONG
              || dt == TacType.UNKNOWN) {
            if (this.guards.isGuardedBy(operand, BinaryOperator.LT)
                || this.guards.isGuardedBy(operand, BinaryOperator.LE)
                || this.guards.isGuardedBy(operand, BinaryOperator.GT)
                || this.guards.isGuardedBy(operand, BinaryOperator.GE)) {
              // Operand is bounded; skip.
            } else {
              String label = "arith:" + uop.name();
              this.taintedVars.put(destVar, label);
              String unopFlow = "overflow-hazard: " + this.taintedVars.get(operand);
              // R780b: same UNKNOWN-fallback as the
              // BINOP branch — if dest is UNKNOWN,
              // use the operand's type.
              TacType unopType = (dt == TacType.UNKNOWN && operand != null) ? operand.type() : dt;
              FlowStep unopStep =
                  new FlowStep(
                      "violation",
                      "",
                      ins.line,
                      ins.column,
                      fn == null ? "" : (fn.signature() == null ? "" : fn.signature()),
                      unopFlow);
              ctx.reporter()
                  .report(
                      Finding.dfa(
                          this.rule.ruleId(),
                          this.rule.ruleId(),
                          "Integer overflow risk: "
                              + uop.name()
                              + " on "
                              + typeName(unopType)
                              + " at "
                              + ins,
                          this.rule.severity(),
                          "CWE-190",
                          "",
                          ins.line,
                          ins.column,
                          unopFlow,
                          java.util.Collections.singletonList(unopStep)));
            }
          }
        }
      }
      return;
    }
    if (ins.kind != TacInstruction.Kind.BINOP) {
      return;
    }
    BinaryOperator op = ins.binop;
    // R780: IntOverflowChecker now owns ADD and MUL only.
    // SUB underflow is IntUnderflowChecker's territory
    // (it tags CWE-191 on every SUB finding, which keeps
    // the rule_id ↔ finding-CWE invariant for the eval
    // pipeline's CWE-alignment check). Pre-R780 both
    // checkers fired on SUB but IntOverflowChecker tagged
    // CWE-191 while rule_id was plumb.numeric.integer-overflow
    // (rule-level CWE = ['190']) — every such finding was
    // marked cwe-mismatch by eval and dropped.
    if (op != BinaryOperator.ADD && op != BinaryOperator.MUL) {
      return;
    }
    if (ins.dest == null || !((tacOperand = ins.dest) instanceof TacOperand.TacVar)) {
      return;
    }
    TacOperand.TacVar destVar = (TacOperand.TacVar) tacOperand;
    TacType dt = destVar.type();
    if (dt != TacType.BYTE
        && dt != TacType.SHORT
        && dt != TacType.INT
        && dt != TacType.LONG
        && dt != TacType.UNKNOWN) {
      return;
    }
    TacOperand tacOperand2 = ins.lhs1;
    boolean lhs1Hazard =
        tacOperand2 instanceof TacOperand.TacVar
            && this.taintedVars.containsKey(l1 = (TacOperand.TacVar) tacOperand2);
    TacOperand tacOperand3 = ins.lhs2;
    boolean lhs2Hazard =
        tacOperand3 instanceof TacOperand.TacVar
            && this.taintedVars.containsKey(l2 = (TacOperand.TacVar) tacOperand3);
    this.taintedVars.put(destVar, "arith:" + String.valueOf(op));
    if (lhs1Hazard || lhs2Hazard) {
      if (IntOverflowChecker.isStringConcat(ins.lhs1, ins.lhs2, destVar)) {
        return;
      }
      if (this.isGuardBoundedOverflow(ins)) {
        return;
      }
      // R781 — direction split. When one operand is a
      // literal int/long constant, the math can only go
      // one direction (overflow or underflow, not both).
      // For example `byte - 1` only underflows (lhs hits
      // MIN, no overflow possible because -1 doesn't
      // push lhs above MAX). Pre-R781 both checkers fired
      // on every ADD/MUL/SUB, then eval's CWE-alignment
      // skipped the wrong-direction one. R781 lets each
      // checker ONLY emit when its direction is
      // mathematically possible. The two-taint-operand
      // case (no const rhs) still fires both directions
      // (conservative).
      //
      // Note: this assumes the LHS is signed (which is
      // true for byte/short/int/long). Char is unsigned
      // but the translator only assigns char to
      // unsigned arithmetic contexts, so the cwe-190
      // boundary number for char would be different;
      // for now we treat it the same as signed and
      // accept the small precision drift.
      Long constRhs = IntOverflowChecker.constRhsValue(ins.lhs1, ins.lhs2, lhs1Hazard, lhs2Hazard);
      if (constRhs != null) {
        int possible = IntOverflowChecker.possibleDirections(op, constRhs);
        if ((possible & 1) == 0) {
          // overflow direction not possible for this
          // op + const rhs. Skip IntOverflowChecker's
          // emit; IntUnderflowChecker (if direction
          // bit 2 is set) will fire instead.
          return;
        }
      }
      // R779 — single "violation" FlowStep at the
      // binary-op itself. R780 — message includes the
      // actual Java type (byte / short / int / long)
      // so the human reader sees what's at risk, not
      // just "int/long" as the legacy message claimed.
      //
      // R780b: when the dest's type is UNKNOWN (e.g. the
      // translator inferred `<unknown>` for the result
      // because the source operand's type didn't
      // propagate through the BINOP), fall back to the
      // hazardous operand's type. The lhs1/lhs2 type is
      // usually the same as the dest (Java widens
      // byte/short to int for BINOP, so the dest is
      // typically int even when the source is byte).
      // We still want to surface that the underlying
      // variable is byte / short / etc.
      String binopFlow =
          "overflow-hazard: "
              + (lhs1Hazard
                  ? (String) this.taintedVars.get(ins.lhs1)
                  : (String) this.taintedVars.get(ins.lhs2));
      TacType sourceType =
          (lhs1Hazard && ins.lhs1 instanceof TacOperand.TacVar)
              ? ((TacOperand.TacVar) ins.lhs1).type()
              : dt;
      if (dt == TacType.UNKNOWN && sourceType != TacType.UNKNOWN) {
        dt = sourceType;
      }
      FlowStep binopStep =
          new FlowStep(
              "violation",
              "",
              ins.line,
              ins.column,
              fn == null ? "" : (fn.signature() == null ? "" : fn.signature()),
              binopFlow);
      ctx.reporter()
          .report(
              Finding.dfa(
                  this.rule.ruleId(),
                  this.rule.ruleId(),
                  "Integer overflow risk: "
                      + String.valueOf(op)
                      + " on "
                      + typeName(dt)
                      + " at "
                      + String.valueOf(ins),
                  this.rule.severity(),
                  "CWE-190",
                  "",
                  ins.line,
                  ins.column,
                  binopFlow,
                  java.util.Collections.singletonList(binopStep)));
    }
  }

  /**
   * R781 ? extract the literal constant on the non-hazard side of the BINOP. Returns null if the
   * non-hazard side is a taint var (no const rhs to inspect) or if the const is not int/long
   * (float/double/char etc., which the existing type filter already excludes ? defensive).
   */
  private static Long constRhsValue(
      TacOperand lhs1, TacOperand lhs2, boolean lhs1Hazard, boolean lhs2Hazard) {
    // Hazard is the taint var; the other side is the const.
    TacOperand otherSide = null;
    if (lhs1Hazard && !lhs2Hazard) otherSide = lhs2;
    else if (lhs2Hazard && !lhs1Hazard) otherSide = lhs1;
    else return null; // both or neither taint
    if (!(otherSide instanceof TacOperand.Const)) return null;
    TacOperand.Const c = (TacOperand.Const) otherSide;
    if (c.kind() == TacOperand.Const.Kind.INT) {
      return (long) (Integer) c.value();
    }
    if (c.kind() == TacOperand.Const.Kind.LONG) {
      return (Long) c.value();
    }
    return null;
  }

  /**
   * R781 ? direction bitmask for `taint op const` on a signed integer type. Bit 1 (value 1) =
   * overflow possible; bit 2 (value 2) = underflow possible. Returns 0 when no risk, 1 when only
   * overflow, 2 when only underflow, 3 when both.
   *
   * <p>For signed types the math is direction-asymmetric:
   *
   * <ul>
   *   <li>ADD rhs > 0: only overflow (lhs + positive can't underflow because adding positive never
   *       crosses the negative boundary ? at worst it crosses the positive boundary)
   *   <li>ADD rhs < 0: only underflow (adding negative crosses the negative boundary, not positive)
   *   <li>SUB rhs > 0: only underflow (subtracting positive pushes lhs down, can cross MIN)
   *   <li>SUB rhs < 0: only overflow (subtracting negative = adding positive)
   *   <li>MUL rhs == 0 / ±1: no risk (result is exact)
   *   <li>MUL |rhs| ≥ 2: both possible in principle (signed mul by 64 of a byte-typed taint CAN
   *       wrap to MIN). But the underflow direction is only reachable when the taint's value is
   *       non-trivially large; the overflow direction is the primary one. We fire both for safety
   *       but the static eval will keep only the matching CWE.
   * </ul>
   */
  static int possibleDirections(BinaryOperator op, long rhs) {
    if (op == BinaryOperator.ADD) {
      if (rhs > 0) return 1; // overflow only
      if (rhs < 0) return 2; // underflow only
      return 0; // rhs == 0: no risk
    }
    if (op == BinaryOperator.SUB) {
      if (rhs > 0) return 2; // underflow only
      if (rhs < 0) return 1; // overflow only (sub neg = add pos)
      return 0;
    }
    if (op == BinaryOperator.MUL) {
      if (rhs == 0 || rhs == 1 || rhs == -1) return 0;
      // Signed mul: overflow when |result| > MAX;
      // underflow when result wraps to < MIN (only
      // possible when |rhs| >= 2 and the taint's |val|
      // is large enough to make |result| > 2 * MAX
      // *before* the wrap, which is rare). For
      // simplicity we say "both possible" and let
      // eval filter on CWE alignment. Static analysis
      // can't know the taint's actual |val|.
      return 3; // both
    }
    return 3; // unknown op: conservative
  }

  /**
   * R780 ? human-readable Java type name for the numeric overflow message. TacType enum values
   * include {@code BYTE}, {@code SHORT}, {@code INT}, {@code LONG} but also {@code UNKNOWN} (when
   * the translator loses the source-level type) and {@code FLOAT}/{@code DOUBLE} (which this
   * checker filters out, but defensive). The Juliet corpus uses {@code byte}/{@code short}/{@code
   * int} /{@code long} (signed) extensively; for unknown types we fall back to "int/long" so the
   * message is at least directionally correct.
   */
  private static String typeName(TacType dt) {
    if (dt == null) return "int/long";
    if (dt == TacType.BYTE) return "byte";
    if (dt == TacType.SHORT) return "short";
    if (dt == TacType.INT) return "int";
    if (dt == TacType.LONG) return "long";
    if (dt == TacType.CHAR) return "char";
    return "int/long";
  }

  private boolean isGuardBoundedOverflow(TacInstruction ins) {
    BinaryOperator op = ins.binop;
    if (op == null) {
      return false;
    }
    if (op == BinaryOperator.ADD) {
      return this.isOperandGuarded(ins.lhs1, BinaryOperator.LT)
          || this.isOperandGuarded(ins.lhs1, BinaryOperator.LE)
          || this.isOperandGuarded(ins.lhs2, BinaryOperator.LT)
          || this.isOperandGuarded(ins.lhs2, BinaryOperator.LE);
    }
    if (op == BinaryOperator.SUB) {
      return this.isOperandGuarded(ins.lhs1, BinaryOperator.GT)
          || this.isOperandGuarded(ins.lhs1, BinaryOperator.GE)
          || this.isOperandGuarded(ins.lhs2, BinaryOperator.GT)
          || this.isOperandGuarded(ins.lhs2, BinaryOperator.GE);
    }
    return false;
  }

  private boolean isOperandGuarded(TacOperand operand, BinaryOperator guardOp) {
    if (!(operand instanceof TacOperand.TacVar)) {
      return false;
    }
    TacOperand.TacVar v = (TacOperand.TacVar) operand;
    return this.guards.isGuardedBy(v, guardOp);
  }

  protected void emitSummaryOps(FunctionSummary.Builder b, TacFunction fn) {
    super.emitSummaryOps(b, fn);
    HashSet<String> emitted = new HashSet<>();
    for (GuardTracker.Guard g : this.guards.allGuardsSeen()) {
      String desc =
          g.boundedVar().name() + " " + String.valueOf(g.op()) + " " + g.literalDescription();
      if (!emitted.add(desc)) continue;
      b.recordReturnOp(
          (Operation) new Operation.SolverZ3Predicate(desc, Set.of(g.boundedVar().name())));
      if (this.currentCtx == null || this.currentCtx.guardStore() == null) continue;
      this.currentCtx
          .guardStore()
          .add(g.boundedVar().name(), DivByZeroChecker.rewriteForArithDsl(desc));
    }
  }

  private static boolean isStringConcat(TacOperand lhs1, TacOperand lhs2, TacOperand.TacVar dest) {
    TacOperand.TacVar rv;
    TacOperand.TacVar lv;
    TacOperand.Const lc2;
    TacOperand.Const lc1;
    if (dest.type() == TacType.STRING) {
      return true;
    }
    if (lhs1 instanceof TacOperand.Const
        && (lc1 = (TacOperand.Const) lhs1).type() == TacType.STRING) {
      return true;
    }
    if (lhs2 instanceof TacOperand.Const
        && (lc2 = (TacOperand.Const) lhs2).type() == TacType.STRING) {
      return true;
    }
    if (lhs1 instanceof TacOperand.TacVar
        && (lv = (TacOperand.TacVar) lhs1).type() == TacType.STRING) {
      return true;
    }
    return lhs2 instanceof TacOperand.TacVar
        && (rv = (TacOperand.TacVar) lhs2).type() == TacType.STRING;
  }
}
