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
 *  org.plumb.tac.ir.TacOperand$TacVar
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

/**
 * Heuristic, taint-derived unchecked-array-index checker.
 *
 * <p><b>Deprecated since R767 (Oct 2026):</b> the {@code plumb.numeric.unchecked-array-index} rule
 * is fully superseded by the boundary framework ({@link
 * org.plumb.dfa.checkers.boundary.ArrayBoundsConstraintChecker}, rule {@code
 * plumb.boundary.array-out-of-bounds}), which is a Range-lattice + Z3 SMT-solver implementation
 * rather than a taint-only heuristic. R765 evaluation showed the boundary rule at <b>0.636
 * precision</b> versus the heuristic at <b>0.294 precision</b> on Juliet CWE-129 (a +0.342 absolute
 * improvement, more than 2x relative). See {@code juliet-coverage/reports/r765/SUMMARY.md} for the
 * empirical comparison.
 *
 * <p>Class kept (not deleted) because:
 *
 * <ol>
 *   <li>unit tests (R19Cwe129Sweep, R225PlusEmitGuardsTest, R247CrossCheckerGuardTest,
 *       R576ArrayIndexFingerprint, and 13 integration tests) still exercise the heuristic path as a
 *       regression baseline;
 *   <li>existing user configs may explicitly enable {@code checks.arrayBounds: true} as a fallback
 *       for callers that have not migrated to {@code plumb.boundary.array-out-of-bounds};
 *   <li>{@code juliet-coverage/configs/CWE129.yaml} keeps {@code
 *       plumb.numeric.unchecked-array-index} in the whitelist for direct A/B comparison.
 * </ol>
 *
 * <p>To disable this rule while keeping boundary active, set {@code checks.arrayBounds: {enabled:
 * false}} in your YAML config (the boundary rule {@code plumb.boundary.array-out-of-bounds} is
 * independent and does not have an opt-out switch).
 */
@Deprecated
public final class ArrayIndexChecker extends TaintedCheckerBase {
  private final GuardTracker guards = new GuardTracker();
  private AnalysisContext currentCtx;

  public ArrayIndexChecker() {
    super(
        new TaintRule(
            "dfa-taint-unchecked-array-index",
            "dfa-taint-untrusted-source",
            "array.index.sinks",
            "CWE-129",
            "HIGH",
            (TaintDomain) TaintDomain.NumericTaint.INSTANCE));
  }

  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    super.initFunction(ctx, fn);
    this.guards.clear();
    this.currentCtx = ctx;
  }

  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
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
    // R708: also handle NEWARRAY (`new T[size]`) — the
    // canonical CWE-129 array-allocation sink. The size
    // operand lives in lhs1 (per TacInstruction.NEWARRAY
    // shape). When size is tainted AND not bounds-checked
    // (no GE/GT guard seen), report.
    if (ins.kind == TacInstruction.Kind.NEWARRAY) {
      TacOperand.TacVar size =
          (ins.lhs1 instanceof TacOperand.TacVar) ? (TacOperand.TacVar) ins.lhs1 : null;
      if (size == null) return;
      if (!this.taintedVars.containsKey(size)) return;
      if (this.guards.isGuardedByIncludingDerived(size, BinaryOperator.GE)
          || this.guards.isGuardedByIncludingDerived(size, BinaryOperator.GT)) {
        return;
      }
      String src = (String) this.taintedVars.get(size);
      String msg = "Array allocation with tainted size: new T[" + size.name() + "]";
      String newArrayFlow = "unchecked-newarray: " + src;
      // R779 — single "violation" FlowStep. The taint
      // source is the input that flowed into `size`; the
      // step's expr carries the label.
      FlowStep newArrayStep =
          new FlowStep(
              "violation",
              "",
              ins.line,
              ins.column,
              fn == null ? "" : (fn.signature() == null ? "" : fn.signature()),
              newArrayFlow);
      ctx.reporter()
          .report(
              Finding.dfa(
                  this.rule.ruleId(),
                  this.rule.ruleId(),
                  msg,
                  this.rule.severity(),
                  this.rule.cwe(),
                  "",
                  ins.line,
                  ins.column,
                  newArrayFlow,
                  java.util.Collections.singletonList(newArrayStep)));
      return;
    }
    if (ins.kind != TacInstruction.Kind.ARRAYLOAD && ins.kind != TacInstruction.Kind.ARRAYSTORE) {
      return;
    }
    TacOperand.TacVar index =
        (ins.lhs2 instanceof TacOperand.TacVar) ? (TacOperand.TacVar) ins.lhs2 : null;
    if (index == null) return;
    if (!this.taintedVars.containsKey(index)) {
      return;
    }
    if (this.guards.isGuardedByIncludingDerived(index, BinaryOperator.GE)
        || this.guards.isGuardedByIncludingDerived(index, BinaryOperator.GT)) {
      return;
    }
    String src = (String) this.taintedVars.get(index);
    String msg =
        (ins.kind == TacInstruction.Kind.ARRAYLOAD
                ? "Array index out of bounds: load at index "
                : "Array index out of bounds: store at index ")
            + index.name();
    String arrayOpFlow = "unchecked-index: " + src;
    // R779 — single "violation" FlowStep at the
    // ARRAYLOAD / ARRAYSTORE site.
    FlowStep arrayOpStep =
        new FlowStep(
            "violation",
            "",
            ins.line,
            ins.column,
            fn == null ? "" : (fn.signature() == null ? "" : fn.signature()),
            arrayOpFlow);
    ctx.reporter()
        .report(
            Finding.dfa(
                this.rule.ruleId(),
                this.rule.ruleId(),
                msg,
                this.rule.severity(),
                this.rule.cwe(),
                "",
                ins.line,
                ins.column,
                arrayOpFlow,
                java.util.Collections.singletonList(arrayOpStep)));
  }

  protected void emitSummaryOps(FunctionSummary.Builder b, TacFunction fn) {
    super.emitSummaryOps(b, fn);
    HashSet<String> emitted = new HashSet<>();
    for (GuardTracker.Guard g : this.guards.allGuardsSeen()) {
      String desc;
      if (g.op() != BinaryOperator.GE && g.op() != BinaryOperator.GT) continue;
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
}
