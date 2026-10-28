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
 *  org.plumb.dfa.summary.FunctionSummary
 *  org.plumb.dfa.taint.PathSensitiveCheckerBase
 *  org.plumb.tac.ir.TacFunction
 *  org.plumb.tac.ir.TacInstruction
 *  org.plumb.tac.ir.TacInstruction$Kind
 *  org.plumb.tac.ir.TacOperand$TacVar
 */
package org.plumb.dfa.checkers.taint;

import java.util.Map;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.taint.PathSensitiveCheckerBase;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

public final class ResourceLeakChecker extends PathSensitiveCheckerBase {
  public String ruleId() {
    return "dfa-taint-leak";
  }

  public String cwe() {
    return "CWE-404";
  }

  public String severityLabel() {
    return "HIGH";
  }

  protected boolean isAllocSite(TacInstruction ins) {
    if (ins.kind == TacInstruction.Kind.NEW || ins.kind == TacInstruction.Kind.NEWARRAY) {
      return true;
    }
    if (ins.kind == TacInstruction.Kind.CALL) {
      String callee = ins.calleeSig;
      if (callee == null) {
        return false;
      }
      return ResourceLeakChecker.containsCtor(callee, "FileInputStream")
          || ResourceLeakChecker.containsCtor(callee, "FileOutputStream")
          || ResourceLeakChecker.containsCtor(callee, "FileReader")
          || ResourceLeakChecker.containsCtor(callee, "FileWriter")
          || ResourceLeakChecker.containsCtor(callee, "BufferedReader")
          || ResourceLeakChecker.containsCtor(callee, "BufferedWriter")
          || ResourceLeakChecker.containsCtor(callee, "Scanner")
          || ResourceLeakChecker.containsCtor(callee, "Socket")
          || ResourceLeakChecker.containsCtor(callee, "ServerSocket")
          || ResourceLeakChecker.containsCtor(callee, "Connection")
          || ResourceLeakChecker.containsCtor(callee, "Statement")
          || ResourceLeakChecker.containsCtor(callee, "PreparedStatement")
          || ResourceLeakChecker.containsCtor(callee, "ResultSet")
          || ResourceLeakChecker.containsCtor(callee, "InputStream")
          || ResourceLeakChecker.containsCtor(callee, "OutputStream");
    }
    return false;
  }

  private static boolean containsCtor(String callee, String className) {
    int idx = callee.indexOf(className);
    if (idx < 0) {
      return false;
    }
    int after = idx + className.length();
    if (after >= callee.length()) {
      return false;
    }
    char sep = callee.charAt(after);
    if (sep != '.' && sep != '#') {
      return false;
    }
    return callee.startsWith("<init>", after + 1) || callee.startsWith(".<init>", after);
  }

  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    for (Map.Entry e : this.allocSites.entrySet()) {
      TacOperand.TacVar allocVar = (TacOperand.TacVar) e.getKey();
      TacInstruction allocSite = (TacInstruction) e.getValue();
      // An UNCONDITIONAL close discharges the obligation.
      if (this.closeSites.containsKey(allocVar) || this.passedOut.contains(allocVar)) continue;
      // A close that only happens on SOME path does not: the rule fires when the resource is
      // "not closed on every path", and a conditional close leaves the other path leaking. The
      // allocation is therefore still reported.
      this.emitLeakFinding(allocVar, allocSite, fn, ctx);
    }
    return super.summarizeFunction(ctx, fn);
  }
}
