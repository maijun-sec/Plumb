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
 *  org.plumb.dfa.taint.TaintDomain
 *  org.plumb.dfa.taint.TaintDomain$PointerTaint
 *  org.plumb.dfa.taint.TaintRule
 *  org.plumb.dfa.taint.TaintedCheckerBase
 *  org.plumb.tac.ir.TacFunction
 *  org.plumb.tac.ir.TacInstruction
 *  org.plumb.tac.ir.TacInstruction$Kind
 *  org.plumb.tac.ir.TacOperand
 *  org.plumb.tac.ir.TacOperand$TacVar
 */
package org.plumb.dfa.checkers.taint;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

public final class UnusedAssignmentChecker extends TaintedCheckerBase {
  private final Map<TacOperand.TacVar, TacInstruction> firstWrite =
      new LinkedHashMap<TacOperand.TacVar, TacInstruction>();
  private final Set<TacOperand.TacVar> read = new LinkedHashSet<TacOperand.TacVar>();
  private final Set<TacOperand.TacVar> emitted = new LinkedHashSet<TacOperand.TacVar>();

  public UnusedAssignmentChecker() {
    // disabled by default: opt in to enable it
    // it reports nothing on the code it was measured against, so it is opt-in.
    // by editing the constructor to pass `true` to the
    // 7-arg `new TaintRule(..., enabled)` overload.
    super(
        new TaintRule(
            "dfa-taint-unused-assignment",
            "plumb.nullable.may-be-null",
            "pointer.deref.sinks",
            "CWE-563",
            "LOW",
            (TaintDomain) TaintDomain.PointerTaint.INSTANCE,
            false));
  }

  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    super.initFunction(ctx, fn);
    this.firstWrite.clear();
    this.read.clear();
    this.emitted.clear();
  }

  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    TacOperand tacOperand;
    super.checkInstruction(ins, fn, ctx);
    if (ins.kind == TacInstruction.Kind.MOVE
        && (tacOperand = ins.dest) instanceof TacOperand.TacVar) {
      TacOperand.TacVar d = (TacOperand.TacVar) tacOperand;
      if (!this.firstWrite.containsKey(d) && !this.read.contains(d)) {
        this.firstWrite.put(d, ins);
      } else if (!this.read.contains(d)) {
        this.emitIfNew(ctx, d, this.firstWrite.get(d));
        this.firstWrite.put(d, ins);
      } else {
        this.firstWrite.put(d, ins);
      }
    } else {
      TacOperand.TacVar d;
      tacOperand = ins.dest;
      if (tacOperand instanceof TacOperand.TacVar
          && this.firstWrite.containsKey(d = (TacOperand.TacVar) tacOperand)
          && !this.read.contains(d)) {
        this.firstWrite.remove(d);
      }
    }
    this.recordRead(ins.lhs1);
    this.recordRead(ins.lhs2);
    if (ins.args != null) {
      for (TacOperand a : ins.args) {
        this.recordRead(a);
      }
    }
  }

  private void recordRead(TacOperand op) {
    if (op instanceof TacOperand.TacVar) {
      TacOperand.TacVar v = (TacOperand.TacVar) op;
      if (this.firstWrite.containsKey(v)) {
        this.firstWrite.remove(v);
      }
      this.read.add(v);
    }
  }

  private void emitIfNew(AnalysisContext ctx, TacOperand.TacVar d, TacInstruction dead) {
    // Honor rule.enabled() flag. The rule is disabled by
    // default, so the emit path has to honor the flag for
    // the disable to take effect.
    if (!this.rule.enabled()) {
      return;
    }
    if (!this.emitted.add(d)) {
      return;
    }
    Finding finding =
        Finding.dfa(
            this.rule.ruleId(),
            this.rule.ruleId(),
            "Unused assignment to '" + d.name() + "' (overwritten before read)",
            this.rule.severity(),
            this.rule.cwe(),
            "",
            dead.line,
            dead.column,
            "unused-assignment: " + d.name());
    ctx.reporter().report(finding);
  }
}
