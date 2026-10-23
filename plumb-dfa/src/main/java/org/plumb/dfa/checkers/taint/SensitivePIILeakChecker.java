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
