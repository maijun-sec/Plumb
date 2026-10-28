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
 *  org.plumb.dfa.engine.FieldDefUseTracker
 *  org.plumb.dfa.engine.Finding
 *  org.plumb.dfa.summary.FunctionSummary
 *  org.plumb.dfa.taint.TaintDomain
 *  org.plumb.dfa.taint.TaintDomain$PointerTaint
 *  org.plumb.dfa.taint.TaintRule
 *  org.plumb.dfa.taint.TaintedCheckerBase
 *  org.plumb.tac.ir.TacFunction
 *  org.plumb.tac.ir.TacInstruction
 *  org.plumb.tac.ir.TacInstruction$Kind
 */
package org.plumb.dfa.checkers.taint;

import java.util.LinkedHashSet;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.FieldDefUseTracker;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

public final class UnusedFieldChecker extends TaintedCheckerBase {
  private final Set<String> emitted = new LinkedHashSet<String>();
  private AnalysisContext currentCtx;

  public UnusedFieldChecker() {
    super(
        new TaintRule(
            "dfa-taint-unused-private-field",
            "plumb.nullable.may-be-null",
            "pointer.deref.sinks",
            "CWE-563",
            "LOW",
            (TaintDomain) TaintDomain.PointerTaint.INSTANCE));
  }

  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    super.initFunction(ctx, fn);
    this.currentCtx = ctx;
  }

  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    super.checkInstruction(ins, fn, ctx);
    if (this.currentCtx == null) {
      return;
    }
    FieldDefUseTracker t = this.currentCtx.fieldDefUseTracker();
    if (t == null) {
      return;
    }
    if (ins.kind == TacInstruction.Kind.FIELDSTORE) {
      t.recordFieldStore(ins, fn.signature());
    } else if (ins.kind == TacInstruction.Kind.FIELDLOAD) {
      t.recordFieldLoad(ins, fn.signature());
    }
  }

  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    FunctionSummary summary = super.summarizeFunction(ctx, fn);
    if (this.currentCtx == null) {
      return summary;
    }
    FieldDefUseTracker t = this.currentCtx.fieldDefUseTracker();
    if (t == null) {
      return summary;
    }
    for (String fieldKey : t.writtenKeys()) {
      if (!this.emitted.add(fieldKey) || t.isRead(fieldKey)) continue;
      String fieldName =
          fieldKey.startsWith("field:") ? fieldKey.substring("field:".length()) : fieldKey;
      this.emit(ctx, fn, fieldName);
    }
    return summary;
  }

  private void emit(AnalysisContext ctx, TacFunction fn, String fieldName) {
    Finding finding =
        Finding.dfa(
            this.rule.ruleId(),
            this.rule.ruleId(),
            "Private field '" + fieldName + "' is assigned but never read",
            this.rule.severity(),
            this.rule.cwe(),
            "",
            -1,
            -1,
            "unused-private-field: " + fieldName);
    ctx.reporter().report(finding);
  }
}
