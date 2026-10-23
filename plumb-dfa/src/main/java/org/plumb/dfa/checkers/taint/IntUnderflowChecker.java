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
 *  org.plumb.tac.ir.UnaryOperator
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

/**
 * Heuristic, taint-derived integer-underflow checker.
 *
 * <p><b>Deprecated since R765 (Oct 2026):</b> the {@code plumb.numeric.integer-underflow} rule is
 * fully superseded by the boundary framework ({@link
 * org.plumb.dfa.checkers.boundary.UnderflowConstraintChecker}, rule {@code
 * plumb.boundary.integer-underflow}), which is a Range-lattice + Z3 SMT-solver implementation
 * rather than a taint-only heuristic. The boundary rule achieves 100% precision on Juliet CWE-191
 * bad() methods and 0 findings on abstract-method dispatch (which the taint-based heuristic
 * severs). See {@code juliet-coverage/reports/r764/SUMMARY.md} for the empirical comparison.
 *
 * <p>Class kept (not deleted) because:
 *
 * <ol>
 *   <li>unit tests (R222bIntUnderflowCheckerTest) still exercise the heuristic path as a regression
 *       baseline;
 *   <li>existing user configs may explicitly enable {@code checks.intUnderflow: true} as a fallback
 *       for callers that have not migrated to {@code plumb.boundary.integer-underflow};
 *   <li>{@code juliet-coverage/configs/CWE191.yaml} keeps {@code plumb.numeric.integer-underflow}
 *       in the whitelist for direct A/B comparison.
 * </ol>
 *
 * <p>To disable this rule while keeping boundary active, set {@code checks.intUnderflow: {enabled:
 * false}} in your YAML config (the boundary rule {@code plumb.boundary.integer-underflow} is
 * independent and does not have an opt-out switch).
 */
@Deprecated
public final class IntUnderflowChecker extends TaintedCheckerBase {
  private final GuardTracker guards = new GuardTracker();
  private AnalysisContext currentCtx;

  public IntUnderflowChecker() {
    super(
        new TaintRule(
            "dfa-taint-integer-underflow",
            "plumb.numeric.overflow-hazard",
            "numeric.binop-arith.sinks",
            "CWE-191",
            "HIGH",
            (TaintDomain) TaintDomain.NumericTaint.INSTANCE));
  }

  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    super.initFunction(ctx, fn);
    this.guards.clear();
    this.currentCtx = ctx;
  }

  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    boolean lhs2Hazard;
    TacOperand.TacVar d;
    TacOperand tacOperand;
    String callee;
    super.checkInstruction(ins, fn, ctx);
    if (ins.kind == TacInstruction.Kind.CALL
        && InputSourcePatterns.isInputSourceCall((String) (callee = ins.calleeSig))
        && (tacOperand = ins.dest) instanceof TacOperand.TacVar) {
      d = (TacOperand.TacVar) tacOperand;
      this.taintedVars.put(d, "input:" + callee);
    }
    this.guards.track(ins);
    if (ins.dest == null || !(ins.dest instanceof TacOperand.TacVar)) {
      return;
    }
    d = (TacOperand.TacVar) ins.dest;
    TacOperand.TacVar destVar = (TacOperand.TacVar) d;
    TacType dt = destVar.type();
    if (dt != TacType.BYTE
        && dt != TacType.SHORT
        && dt != TacType.INT
        && dt != TacType.LONG
        && dt != TacType.UNKNOWN) {
      return;
    }
    if (ins.kind == TacInstruction.Kind.UNARYOP) {
      if (ins.unop != UnaryOperator.PRE_DEC && ins.unop != UnaryOperator.POST_DEC) {
        return;
      }
      String operandLabel = this.isRealHazard(ins.lhs1);
      if (operandLabel != null) {
        this.taintedVars.put(destVar, "underflow:" + String.valueOf(ins.unop));
      }
      if (operandLabel == null) {
        return;
      }
      if (this.isGuardBoundedUnderflowByOperand(ins.lhs1)) {
        return;
      }
      // R780 — message includes the actual Java type.
      // Also: this is the SOLE owner of PRE_DEC/POST_DEC
      // findings (IntOverflowChecker used to fire on the
      // same instructions with a mismatched CWE-191
      // tag). Force CWE-191 here so every PRE_DEC
      // finding has consistent rule_id ↔ CWE.
      //
      // R780b: see IntOverflowChecker — fall back to
      // the operand's type when dest is UNKNOWN.
      String unopFlow = "underflow-hazard: " + operandLabel;
      TacType unopType =
          (destVar.type() == TacType.UNKNOWN)
              ? (ins.lhs1 instanceof TacOperand.TacVar
                  ? ((TacOperand.TacVar) ins.lhs1).type()
                  : destVar.type())
              : destVar.type();
      ctx.reporter()
          .report(
              Finding.dfa(
                  this.rule.ruleId(),
                  this.rule.ruleId(),
                  "Integer underflow risk: "
                      + String.valueOf(ins.unop)
                      + " on "
                      + typeName(unopType)
                      + " at "
                      + String.valueOf(ins),
                  this.rule.severity(),
                  "CWE-191",
                  "",
                  ins.line,
                  ins.column,
                  unopFlow,
                  java.util.Collections.singletonList(
                      new FlowStep(
                          "violation",
                          "",
                          ins.line,
                          ins.column,
                          fn == null ? "" : (fn.signature() == null ? "" : fn.signature()),
                          unopFlow))));
      return;
    }
    if (ins.kind != TacInstruction.Kind.BINOP) {
      return;
    }
    BinaryOperator op = ins.binop;
    if (op != BinaryOperator.SUB && op != BinaryOperator.ADD && op != BinaryOperator.MUL) {
      return;
    }
    String lhs1Label = this.isRealHazard(ins.lhs1);
    String lhs2Label = this.isRealHazard(ins.lhs2);
    boolean lhs1Hazard = lhs1Label != null;
    boolean bl = lhs2Hazard = lhs2Label != null;
    if (lhs1Hazard || lhs2Hazard) {
      this.taintedVars.put(destVar, "underflow:" + String.valueOf(op));
    }
    if (lhs1Hazard || lhs2Hazard) {
      if (IntUnderflowChecker.isStringConcat(ins.lhs1, ins.lhs2, destVar)) {
        return;
      }
      if (this.isGuardBoundedUnderflow(ins)) {
        return;
      }
      // R781 — direction split. When one operand is a
      // literal int/long constant, the math can only
      // go one direction. Mirror of IntOverflowChecker's
      // direction gate: underflow bit (value 2) is
      // required for this checker to emit.
      Long constRhs = IntUnderflowChecker.constRhsValue(ins.lhs1, ins.lhs2, lhs1Hazard, lhs2Hazard);
      if (constRhs != null) {
        int possible = IntUnderflowChecker.possibleDirections(op, constRhs);
        if ((possible & 2) == 0) {
          // underflow direction not possible for
          // this op + const rhs. Skip
          // IntUnderflowChecker's emit; the overflow
          // direction (if applicable) is the other
          // checker's responsibility.
          return;
        }
      }
      // R780 — message includes actual Java type, CWE
      // forced to CWE-191 to match rule_id. The op
      // (SUB / ADD / MUL) is preserved because Juliet
      // CWE-191 uses both SUB (e.g. `data - 1` when
      // data is MIN_VALUE) and MUL (e.g. `data * 2`
      // when data is in the underflow range).
      //
      // R780b: see IntOverflowChecker — fall back to the
      // hazardous operand's type when dest is UNKNOWN.
      TacType destType = destVar.type();
      if (destType == TacType.UNKNOWN) {
        if (lhs1Hazard && ins.lhs1 instanceof TacOperand.TacVar) {
          destType = ((TacOperand.TacVar) ins.lhs1).type();
        } else if (ins.lhs2 instanceof TacOperand.TacVar) {
          destType = ((TacOperand.TacVar) ins.lhs2).type();
        }
      }
      String uBinFlow = "underflow-hazard: " + (lhs1Hazard ? lhs1Label : lhs2Label);
      ctx.reporter()
          .report(
              Finding.dfa(
                  this.rule.ruleId(),
                  this.rule.ruleId(),
                  "Integer underflow risk: "
                      + String.valueOf(op)
                      + " on "
                      + typeName(destType)
                      + " at "
                      + String.valueOf(ins),
                  this.rule.severity(),
                  "CWE-191",
                  "",
                  ins.line,
                  ins.column,
                  uBinFlow,
                  java.util.Collections.singletonList(
                      new FlowStep(
                          "violation",
                          "",
                          ins.line,
                          ins.column,
                          fn == null ? "" : (fn.signature() == null ? "" : fn.signature()),
                          uBinFlow))));
    }
  }

  /**
   * R781 ? see IntOverflowChecker#constRhsValue. Mirror helper for the underflow checker; identical
   * body, kept as a separate method so each checker owns its own copy (the two checkers are already
   * parallel implementations of TaintedCheckerBase's emit logic; we don't want to introduce a
   * shared util just for two static methods).
   */
  private static Long constRhsValue(
      TacOperand lhs1, TacOperand lhs2, boolean lhs1Hazard, boolean lhs2Hazard) {
    TacOperand otherSide = null;
    if (lhs1Hazard && !lhs2Hazard) otherSide = lhs2;
    else if (lhs2Hazard && !lhs1Hazard) otherSide = lhs1;
    else return null;
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
   * R781 ? see IntOverflowChecker#possibleDirections. Same direction bitmask. Underflow-only cases
   * (bit 2 set) are the cases IntUnderflowChecker handles; the int underflow checker is the only
   * owner of those.
   */
  static int possibleDirections(BinaryOperator op, long rhs) {
    if (op == BinaryOperator.ADD) {
      if (rhs > 0) return 1;
      if (rhs < 0) return 2;
      return 0;
    }
    if (op == BinaryOperator.SUB) {
      if (rhs > 0) return 2;
      if (rhs < 0) return 1;
      return 0;
    }
    if (op == BinaryOperator.MUL) {
      if (rhs == 0 || rhs == 1 || rhs == -1) return 0;
      return 3;
    }
    return 3;
  }

  /**
   * R780 ? see IntOverflowChecker#typeName. Same mapping to keep the human-readable type in the
   * underflow message consistent with the overflow message.
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

  private String isRealHazard(TacOperand operand) {
    if (!(operand instanceof TacOperand.TacVar)) {
      return null;
    }
    TacOperand.TacVar v = (TacOperand.TacVar) operand;
    String label = (String) this.taintedVars.get(v);
    if (label == null) {
      return null;
    }
    if (label.startsWith("param:")) {
      return null;
    }
    return label;
  }

  private boolean isGuardBoundedUnderflow(TacInstruction ins) {
    BinaryOperator op = ins.binop;
    if (op == null) {
      return false;
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

  private boolean isGuardBoundedUnderflowByOperand(TacOperand operand) {
    if (!(operand instanceof TacOperand.TacVar)) {
      return false;
    }
    return this.isOperandGuarded(operand, BinaryOperator.GT)
        || this.isOperandGuarded(operand, BinaryOperator.GE);
  }

  protected void emitSummaryOps(FunctionSummary.Builder b, TacFunction fn) {
    super.emitSummaryOps(b, fn);
    HashSet<String> emitted = new HashSet<>();
    for (GuardTracker.Guard g : this.guards.allGuardsSeen()) {
      String desc;
      if (g.op() != BinaryOperator.GT && g.op() != BinaryOperator.GE) continue;
      desc = g.boundedVar().name() + " " + g.op() + " " + g.literalDescription();
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
