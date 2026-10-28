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
 *  org.plumb.dfa.taint.TaintDomain
 *  org.plumb.dfa.taint.TaintDomain$MixedTaint
 *  org.plumb.dfa.taint.TaintRule
 *  org.plumb.dfa.taint.TaintedCheckerBase
 *  org.plumb.tac.ir.TacFunction
 *  org.plumb.tac.ir.TacInstruction
 *  org.plumb.tac.ir.TacInstruction$Kind
 *  org.plumb.tac.ir.TacOperand
 *  org.plumb.tac.ir.TacOperand$TacVar
 */
package org.plumb.dfa.checkers.taint;

import java.util.List;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

public final class SensitivePIILeakChecker extends TaintedCheckerBase {
  private static final List<String> PII_SOURCE_PATTERNS =
      List.of(
          ".getName",
          ".getFirstName",
          ".getLastName",
          ".getFullName",
          ".getEmail",
          ".getPhone",
          ".getMobile",
          ".getAddress",
          ".getSsn",
          ".getSin",
          ".getDob",
          ".getBirthday",
          ".getDateOfBirth",
          ".getPassport",
          ".getDriverLicense",
          ".getCreditCard",
          ".getNationalId");

  public SensitivePIILeakChecker() {
    super(
        new TaintRule(
            "dfa-taint-pii-leak",
            "plumb.sensitive.pii",
            "pii.exfil.sinks",
            "CWE-359",
            "HIGH",
            (TaintDomain) TaintDomain.MixedTaint.INSTANCE));
  }

  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    TacOperand tacOperand;
    super.checkInstruction(ins, fn, ctx);
    if (ins.kind == TacInstruction.Kind.CALL
        && (tacOperand = ins.dest) instanceof TacOperand.TacVar) {
      TacOperand.TacVar d = (TacOperand.TacVar) tacOperand;
      String callee = ins.calleeSig;
      if (callee != null && SensitivePIILeakChecker.isPIISourceCall(callee)) {
        this.taintedVars.put(d, "pii:" + callee);
      }
    }
  }

  private static boolean isPIISourceCall(String callee) {
    for (String pat : PII_SOURCE_PATTERNS) {
      if (!callee.contains(pat)) continue;
      return true;
    }
    return false;
  }
}
