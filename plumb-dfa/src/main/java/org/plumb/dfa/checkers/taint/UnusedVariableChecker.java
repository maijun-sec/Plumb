/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  org.plumb.dfa.engine.AnalysisContext
 *  org.plumb.dfa.engine.Finding
 *  org.plumb.dfa.summary.FunctionSummary
 *  org.plumb.dfa.taint.TaintDomain
 *  org.plumb.dfa.taint.TaintDomain$PointerTaint
 *  org.plumb.dfa.taint.TaintRule
 *  org.plumb.dfa.taint.TaintedCheckerBase
 *  org.plumb.tac.ir.TacFunction
 *  org.plumb.tac.ir.TacInstruction
 *  org.plumb.tac.ir.TacOperand
 *  org.plumb.tac.ir.TacOperand$TacVar
 */
package org.plumb.dfa.checkers.taint;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

public final class UnusedVariableChecker extends TaintedCheckerBase {
  private final Map<TacOperand.TacVar, TacInstruction> defs =
      new LinkedHashMap<TacOperand.TacVar, TacInstruction>();
  private final Map<TacOperand.TacVar, Boolean> used =
      new LinkedHashMap<TacOperand.TacVar, Boolean>();

  public UnusedVariableChecker() {
    // R-836 step_f: disabled by default. R-832 P3 Juliet baseline
    // showed 0 TP / 26k FP for this rule. Users can opt in
    // by editing the constructor to pass `true` to the
    // 7-arg `new TaintRule(..., enabled)` overload.
    super(
        new TaintRule(
            "dfa-taint-unused-local",
            "plumb.nullable.may-be-null",
            "pointer.deref.sinks",
            "CWE-563",
            "LOW",
            (TaintDomain) TaintDomain.PointerTaint.INSTANCE,
            false));
  }

  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    super.initFunction(ctx, fn);
    this.defs.clear();
    this.used.clear();
  }

  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    super.checkInstruction(ins, fn, ctx);
    TacOperand tacOperand = ins.dest;
    if (tacOperand instanceof TacOperand.TacVar) {
      TacOperand.TacVar d = (TacOperand.TacVar) tacOperand;
      this.defs.putIfAbsent(d, ins);
    }
    this.recordUse(ins.lhs1);
    this.recordUse(ins.lhs2);
    if (ins.args != null) {
      for (TacOperand arg : ins.args) {
        this.recordUse(arg);
      }
    }
  }

  private void recordUse(TacOperand op) {
    if (op instanceof TacOperand.TacVar) {
      TacOperand.TacVar v = (TacOperand.TacVar) op;
      this.used.putIfAbsent(v, Boolean.TRUE);
    }
  }

  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    FunctionSummary summary = super.summarizeFunction(ctx, fn);
    LinkedHashSet<TacOperand.TacVar> locals = new LinkedHashSet<>();
    locals.addAll(fn.locals());
    for (TacOperand.TacVar local : locals) {
      if (local.isTemp() || this.used.containsKey(local) || !this.defs.containsKey(local)) continue;
      TacInstruction def = this.defs.get(local);
      this.emitFinding(ctx, fn, def, local);
    }
    return summary;
  }

  private void emitFinding(
      AnalysisContext ctx, TacFunction fn, TacInstruction def, TacOperand.TacVar local) {
    // R-836 step_f: honor rule.enabled() flag (R-834 codemod
    // was bypassed by the emit path). 0 TP / 100% FP on
    // Juliet R-832 baseline.
    if (!this.rule.enabled()) {
      return;
    }
    Finding finding =
        Finding.dfa(
            this.rule.ruleId(),
            this.rule.ruleId(),
            "Unused local variable: " + local.name() + " is assigned but never read",
            this.rule.severity(),
            this.rule.cwe(),
            "",
            def.line,
            def.column,
            "unused-local: " + local.name());
    ctx.reporter().report(finding);
  }
}
