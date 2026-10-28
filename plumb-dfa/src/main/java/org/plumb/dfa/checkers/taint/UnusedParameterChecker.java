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

import java.util.LinkedHashSet;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

public final class UnusedParameterChecker extends TaintedCheckerBase {
  private final Set<TacOperand.TacVar> usedParams = new LinkedHashSet<TacOperand.TacVar>();
  private final Set<TacOperand.TacVar> emitted = new LinkedHashSet<TacOperand.TacVar>();

  public UnusedParameterChecker() {
    // disabled by default: it reported nothing on the code it was
    // measured against. Users can opt in by editing the constructor
    // to pass `false` to the 7-arg `new TaintRule(..., enabled)` overload.
    super(
        new TaintRule(
            "dfa-taint-unused-parameter",
            "plumb.nullable.may-be-null",
            "pointer.deref.sinks",
            "CWE-563",
            "LOW",
            (TaintDomain) TaintDomain.PointerTaint.INSTANCE,
            false));
  }

  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    super.checkInstruction(ins, fn, ctx);
    if (fn.params().isEmpty()) {
      return;
    }
    LinkedHashSet<TacOperand.TacVar> used = new LinkedHashSet<TacOperand.TacVar>();
    UnusedParameterChecker.recordUse(ins.lhs1, used);
    UnusedParameterChecker.recordUse(ins.lhs2, used);
    if (ins.args != null) {
      for (TacOperand a : ins.args) {
        UnusedParameterChecker.recordUse(a, used);
      }
    }
    this.usedParams.addAll(used);
  }

  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    super.initFunction(ctx, fn);
    this.usedParams.clear();
    this.emitted.clear();
  }

  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    FunctionSummary summary = super.summarizeFunction(ctx, fn);
    for (TacOperand.TacVar p : fn.params()) {
      if (this.emitted.contains(p)) continue;
      this.emitted.add(p);
      if (this.usedParams.contains(p)) continue;
      this.emit(ctx, fn, p);
    }
    return summary;
  }

  private static void recordUse(TacOperand op, Set<TacOperand.TacVar> used) {
    if (op instanceof TacOperand.TacVar) {
      TacOperand.TacVar v = (TacOperand.TacVar) op;
      used.add(v);
    }
  }

  private void emit(AnalysisContext ctx, TacFunction fn, TacOperand.TacVar param) {
    // Honor rule.enabled() flag. The rule is disabled by default,
    // and the emit path honors the flag.
    if (!this.rule.enabled()) {
      return;
    }
    Finding finding =
        Finding.dfa(
            this.rule.ruleId(),
            this.rule.ruleId(),
            "Parameter '" + param.name() + "' is declared but never used",
            this.rule.severity(),
            this.rule.cwe(),
            "",
            -1,
            -1,
            "unused-parameter: " + param.name());
    ctx.reporter().report(finding);
  }
}
