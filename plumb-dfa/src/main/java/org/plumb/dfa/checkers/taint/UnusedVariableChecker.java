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
    // disabled by default: opt in to enable it
    // it reports nothing on the code it was measured against, so it is opt-in.
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
    // Honor rule.enabled() flag. The emit path can otherwise
    // report a rule the caller switched off.
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
