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
 *  org.plumb.dfa.config.SinkFamily
 *  org.plumb.dfa.engine.AnalysisContext
 *  org.plumb.dfa.engine.Finding
 *  org.plumb.dfa.taint.InputSourcePatterns
 *  org.plumb.dfa.taint.TaintDomain
 *  org.plumb.dfa.taint.TaintDomain$StringTaint
 *  org.plumb.dfa.taint.TaintRule
 *  org.plumb.dfa.taint.TaintedCheckerBase
 *  org.plumb.tac.ir.TacFunction
 *  org.plumb.tac.ir.TacInstruction
 *  org.plumb.tac.ir.TacInstruction$Kind
 *  org.plumb.tac.ir.TacOperand
 *  org.plumb.tac.ir.TacOperand$TacVar
 */
package org.plumb.dfa.checkers.taint;

import java.util.Optional;
import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.taint.InputSourcePatterns;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

public final class PathTraversalChecker extends TaintedCheckerBase {
  public PathTraversalChecker() {
    super(
        new TaintRule(
            "dfa-taint-path-traversal",
            "dfa-taint-untrusted-source",
            "file-path.sinks",
            "CWE-22",
            "HIGH",
            (TaintDomain) TaintDomain.StringTaint.INSTANCE));
  }

  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    TacOperand.TacVar d;
    Object object;
    String callee;
    super.checkInstruction(ins, fn, ctx);
    if (ins.kind == TacInstruction.Kind.CALL
        && InputSourcePatterns.isInputSourceCall((String) (callee = ins.calleeSig))
        && (object = ins.dest) instanceof TacOperand.TacVar) {
      d = (TacOperand.TacVar) object;
      this.taintedVars.put(d, "input:" + callee);
    }
    if (ins.kind == TacInstruction.Kind.NEW) {
      callee = ins.calleeSig;
      if (callee != null && (object = ins.dest) instanceof TacOperand.TacVar) {
        d = (TacOperand.TacVar) object;
        if (ins.args != null) {
          for (TacOperand arg : ins.args) {
            TacOperand.TacVar av;
            if (!(arg instanceof TacOperand.TacVar)
                || !this.taintedVars.containsKey(av = (TacOperand.TacVar) arg)) continue;
            this.taintedVars.put(d, "new(" + callee + "):" + (String) this.taintedVars.get(av));
            break;
          }
        }
      }
      if (callee == null) {
        return;
      }
      Optional familyOpt = ctx.config().sinkFamilies().lookup(callee);
      if (familyOpt.isEmpty()) {
        return;
      }
      SinkFamily family = (SinkFamily) familyOpt.get();
      if (!this.rule.matchesSinkFamily(family.name())) {
        return;
      }
      if (ins.args == null || ins.args.isEmpty()) {
        return;
      }
      int argIdx = this.sinkArgumentIndex(family, ins);
      if (argIdx < 0 || argIdx >= ins.args.size()) {
        return;
      }
      TacOperand arg = (TacOperand) ins.args.get(argIdx);
      if (!(arg instanceof TacOperand.TacVar)) {
        return;
      }
      TacOperand.TacVar av = (TacOperand.TacVar) arg;
      if (!this.taintedVars.containsKey(av)) {
        return;
      }
      ctx.reporter()
          .report(
              Finding.dfa(
                  this.rule.ruleId(),
                  this.rule.ruleId(),
                  "Path traversal: tainted path flows to " + callee,
                  this.rule.severity(),
                  this.rule.cwe(),
                  "",
                  ins.line,
                  ins.column,
                  "taint: " + (String) this.taintedVars.get(av) + " -> " + callee));
    }
  }
}
