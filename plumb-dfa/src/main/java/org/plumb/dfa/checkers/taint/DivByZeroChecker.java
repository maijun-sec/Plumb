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
 *  org.plumb.tac.ir.TacOperand$Const$Kind
 *  org.plumb.tac.ir.TacOperand$TacVar
 *  org.plumb.tac.ir.TacType$Kind
 */
package org.plumb.dfa.checkers.taint;

import java.util.HashSet;
import java.util.Map;
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
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * Heuristic, taint-derived divide-by-zero checker.
 *
 * <p><b>Deprecated since R768 (Oct 2026):</b> the {@code plumb.numeric.divide-by-zero} rule is
 * fully superseded by the boundary framework ({@link
 * org.plumb.dfa.checkers.boundary.DivZeroConstraintChecker}, rule {@code
 * plumb.boundary.divide-by-zero}), which is a Range-lattice + Z3 SMT-solver implementation rather
 * than a taint-only heuristic. R765 evaluation found that the boundary rule is the <b>only</b> rule
 * that fires on Juliet CWE-369 ? the taint-based {@code DivByZeroChecker} produced 0 findings
 * across the entire CWE-369 corpus (the taint chain is severed by abstract-method dispatch in s81 /
 * cross-file propagation in s67a variants, which the boundary framework's Range lattice handles
 * directly via {@code (divisor != 0)} refutation). See {@code
 * juliet-coverage/reports/r765/SUMMARY.md} for the empirical comparison.
 *
 * <p>CWE-369 has the lowest boundary precision of the 4 numeric CWE (0.282 in R765) because Juliet
 * div-zero templates are sparse (s01-s04 only, no s05/s06) and the {@code (divisor != 0)} Z3
 * predicate is harder to refute than array-bounds. That said, the boundary rule's 0.282 is strictly
 * better than zero findings from the heuristic, so deprecation remains warranted.
 *
 * <p>Class kept (not deleted) because:
 *
 * <ol>
 *   <li>unit tests (R215MirrorCheckersTest, R225PlusEmitGuardsTest, R247CrossCheckerGuardTest)
 *       still exercise the heuristic path as a regression baseline;
 *   <li>existing user configs may explicitly enable {@code checks.divByZero: true} as a fallback
 *       for callers that have not migrated to {@code plumb.boundary.divide-by-zero};
 *   <li>{@code juliet-coverage/configs/CWE369.yaml} keeps {@code plumb.numeric.divide-by-zero} in
 *       the whitelist for direct A/B comparison (currently 0 vs boundary's 6568 findings).
 * </ol>
 *
 * <p>To disable this rule while keeping boundary active, set {@code checks.divByZero: {enabled:
 * false}} in your YAML config (the boundary rule {@code plumb.boundary.divide-by-zero} is
 * independent and does not have an opt-out switch).
 */
@Deprecated
public final class DivByZeroChecker extends TaintedCheckerBase {
  private final GuardTracker guards = new GuardTracker();
  private AnalysisContext currentCtx;

  public DivByZeroChecker() {
    super(
        new TaintRule(
            "dfa-taint-divide-by-zero",
            "dfa-taint-may-be-zero",
            "numeric.divide.sinks",
            "CWE-369",
            "HIGH",
            (TaintDomain) TaintDomain.NumericTaint.INSTANCE));
  }

  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    super.initFunction(ctx, fn);
    this.currentCtx = ctx;
    this.guards.clear();
  }

  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    super.checkInstruction(ins, fn, ctx);
    // Detect input source calls and mark dest as
    // "may-be-zero".
    if (ins.kind == TacInstruction.Kind.CALL) {
      String callee = ins.calleeSig;
      if (InputSourcePatterns.isInputSourceCall(callee)
          && ins.dest instanceof TacOperand.TacVar d) {
        markSource(d, "input:" + callee, ins, fn.signature(), "input " + callee);
      }
    }
    guards.track(ins);
    if (ins.kind != TacInstruction.Kind.BINOP) return;
    if (ins.binop != BinaryOperator.DIV && ins.binop != BinaryOperator.MOD) return;
    if (isFloatDivision(ins)) return;
    // The rhs (lhs2) is the divisor. If it's a const 0,
    // mark it tainted (implicit source). If it's a
    // tainted var, fire a finding.
    if (ins.lhs2 instanceof TacOperand.Const c
        && c.kind() == TacOperand.Const.Kind.INT
        && Integer.valueOf(0).equals(c.value())) {
      emitDivideByZero(ins, fn, ctx, ins.lhs2);
      return;
    }
    if (ins.lhs2 instanceof TacOperand.TacVar rhs && taintedVars.containsKey(rhs)) {
      // R1101: `isProvenNonZero` covers both the active `data != 0` guard and the
      // false-edge fact from `if (data == 0) { return 0; }`. The previous
      // `isGuardedBy(rhs, NE)` handled only the first, which is why the early-return
      // idiom was a false positive here while the boundary-family checker (after the
      // same R1101 fix) had already learned to stay silent -- two rules, one bug,
      // two different answers.
      if (guards.isProvenNonZero(rhs)) return;
      if (currentCtx != null
          && currentCtx.guardStore() != null
          && Boolean.TRUE.equals(
              currentCtx.guardStore().impliedGuard(rhs.name(), rhs.name() + " != 0", Map.of()))) {
        return;
      }
      emitDivideByZero(ins, fn, ctx, rhs);
    }
  }

  private static boolean isFloatDivision(TacInstruction ins) {
    if (ins.lhs1 instanceof TacOperand.TacVar v1
        && v1.type() != null
        && (v1.type().kind() == TacType.Kind.FLOAT || v1.type().kind() == TacType.Kind.DOUBLE)) {
      return true;
    }
    if (ins.lhs2 instanceof TacOperand.TacVar v2
        && v2.type() != null
        && (v2.type().kind() == TacType.Kind.FLOAT || v2.type().kind() == TacType.Kind.DOUBLE)) {
      return true;
    }
    if (ins.lhs1 instanceof TacOperand.Const c1
        && (c1.kind() == TacOperand.Const.Kind.FLOAT
            || c1.kind() == TacOperand.Const.Kind.DOUBLE)) {
      return true;
    }
    if (ins.lhs2 instanceof TacOperand.Const c2
        && (c2.kind() == TacOperand.Const.Kind.FLOAT
            || c2.kind() == TacOperand.Const.Kind.DOUBLE)) {
      return true;
    }
    return false;
  }

  private void emitDivideByZero(
      TacInstruction ins, TacFunction fn, AnalysisContext ctx, TacOperand divisor) {
    Object flow;
    String msg;
    if (divisor instanceof TacOperand.TacVar) {
      TacOperand.TacVar v = (TacOperand.TacVar) divisor;
      msg = "Division by zero: divisor " + v.name() + " may be zero at " + String.valueOf(ins);
      flow = "may-be-zero: " + (String) this.taintedVars.get(v);
    } else {
      msg = "Division by zero: divisor is a literal zero at " + String.valueOf(ins);
      flow = "may-be-zero: const-0";
    }
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
                (String) flow));
  }

  protected void emitSummaryOps(FunctionSummary.Builder b, TacFunction fn) {
    super.emitSummaryOps(b, fn);
    HashSet<String> emitted = new HashSet<>();
    for (GuardTracker.Guard g : this.guards.allGuardsSeen()) {
      String desc;
      if (g.op() != BinaryOperator.NE) continue;
      desc = g.boundedVar().name() + " " + g.op() + " " + g.literalDescription();
      if (!emitted.add(desc)) continue;
      b.recordReturnOp(
          (Operation) new Operation.SolverZ3Predicate(desc, Set.of(g.boundedVar().name())));
      String arithDesc = DivByZeroChecker.rewriteForArithDsl(desc);
      if (this.currentCtx == null || this.currentCtx.guardStore() == null) continue;
      this.currentCtx.guardStore().add(g.boundedVar().name(), arithDesc);
    }
  }

  static String rewriteForArithDsl(String symbolic) {
    if (symbolic == null) {
      return null;
    }
    return symbolic
        .replace(" EQ ", " == ")
        .replace(" NE ", " != ")
        .replace(" LT ", " < ")
        .replace(" LE ", " <= ")
        .replace(" GT ", " > ")
        .replace(" GE ", " >= ");
  }
}
