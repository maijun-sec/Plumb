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

import java.util.HashSet;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.FieldDefUseTracker;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

public final class UnusedFieldChecker extends TaintedCheckerBase {
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

  /**
   * The finding is emitted here, once, after every function has been visited.
   *
   * <p>Two questions decide it: "is this field ever read?" and "which function owns the finding?".
   * Both need the WHOLE program. The first needs every function's reads to have been seen, and the
   * second needs a rule that does not depend on the order the functions were visited in.
   *
   * <p>Emitting it per function, while that function is being analysed, answers the first question
   * with whatever the analysis had seen SO FAR: a field that a later function reads looks unread,
   * and the finding lands on whichever function happened to be visited first. With more than one
   * worker, each worker has its own copy of this checker, so each of them can report the same
   * field, and the set of findings depends on the schedule.
   *
   * <p>The owner is the FIRST function in program order that writes the field. The order is the
   * program's, not the schedule's, so the finding lands in the same place on every run.
   *
   * <p>The field key names the field AND the class it belongs to, so two classes that declare a
   * field of the same name are two fields: one class's read no longer suppresses the other class's
   * report. The class is the one the field hangs on -- the type of the receiver the access is made
   * on -- and not the class the access happens to be written in: a base class's field written in
   * the subclass and read in the base class is one field.
   */
  @Override
  public void finish(AnalysisContext ctx) {
    super.finish(ctx);
    if (ctx == null || ctx.program() == null) {
      return;
    }
    FieldDefUseTracker t = ctx.fieldDefUseTracker();
    if (t == null) {
      return;
    }
    Set<String> written = t.writtenKeys();
    Set<String> reported = new HashSet<String>();
    TacFunction previous = ctx.currentFunction();
    try {
      for (TacFunction fn : ctx.program().functions()) {
        for (TacInstruction ins : fn.instructions()) {
          if (ins.kind != TacInstruction.Kind.FIELDSTORE) {
            continue;
          }
          String fieldKey = FieldDefUseTracker.fieldKey(ins);
          if (fieldKey == null || !written.contains(fieldKey)) {
            continue;
          }
          if (!reported.add(fieldKey) || t.isRead(fieldKey)) {
            continue;
          }
          String fieldName = FieldDefUseTracker.nameOf(fieldKey);
          // The reporter resolves the finding's file from the CURRENT function, so it has to
          // be the owner of the field and not whatever the analysis happened to leave behind.
          ctx.setCurrentFunction(fn);
          this.emit(ctx, fn, fieldName);
        }
      }
    } finally {
      ctx.setCurrentFunction(previous);
    }
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
