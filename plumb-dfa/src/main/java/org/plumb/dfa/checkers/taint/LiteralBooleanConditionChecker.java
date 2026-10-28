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
 *  org.plumb.dfa.config.FactKind
 *  org.plumb.dfa.engine.AnalysisContext
 *  org.plumb.dfa.engine.Finding
 *  org.plumb.dfa.fact.Fact
 *  org.plumb.dfa.taint.TaintDomain
 *  org.plumb.dfa.taint.TaintDomain$PointerTaint
 *  org.plumb.dfa.taint.TaintRule
 *  org.plumb.dfa.taint.TaintedCheckerBase
 *  org.plumb.tac.ir.TacFunction
 *  org.plumb.tac.ir.TacInstruction
 *  org.plumb.tac.ir.TacInstruction$Kind
 *  org.plumb.tac.ir.TacOperand
 *  org.plumb.tac.ir.TacOperand$Const
 *  org.plumb.tac.ir.TacOperand$Const$Kind
 *  org.plumb.tac.ir.TacOperand$TacVar
 */
package org.plumb.dfa.checkers.taint;

import java.util.List;
import java.util.Optional;
import org.plumb.dfa.config.FactKind;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.fact.Fact;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

public final class LiteralBooleanConditionChecker extends TaintedCheckerBase {
  public LiteralBooleanConditionChecker() {
    // Disabled by default. Users can opt in
    // by editing the constructor to pass `true` to the
    // 7-arg `new TaintRule(..., enabled)` overload.
    super(
        new TaintRule(
            "dfa-taint-literal-boolean-condition",
            "plumb.nullable.may-be-null",
            "pointer.deref.sinks",
            "CWE-547",
            "LOW",
            (TaintDomain) TaintDomain.PointerTaint.INSTANCE,
            false));
  }

  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    Optional<String> folded;
    super.checkInstruction(ins, fn, ctx);
    if (ins.kind != TacInstruction.Kind.BRANCH) {
      return;
    }
    TacOperand cond = ins.lhs1;
    if (cond == null) {
      return;
    }
    if (cond instanceof TacOperand.Const) {
      TacOperand.Const c = (TacOperand.Const) cond;
      if (LiteralBooleanConditionChecker.isLiteralBoolean(c)) {
        this.emitSourceLiteral(ctx, ins, c);
      }
      return;
    }
    if (cond instanceof TacOperand.TacVar
        && (folded = LiteralBooleanConditionChecker.foldedConstValue(ctx, cond)).isPresent()) {
      this.emitFoldedConst(ctx, ins, folded.get());
    }
  }

  private static boolean isLiteralBoolean(TacOperand.Const c) {
    return switch (c.kind()) {
      case BOOL -> true;
      case INT -> {
        Integer i;
        Object v = c.value();
        if (v instanceof Integer && ((i = (Integer) v) == 0 || i == 1)) {
          yield true;
        }
        yield false;
      }
      default -> false;
    };
  }

  private static Optional<String> foldedConstValue(AnalysisContext ctx, TacOperand operand) {
    Optional kind = ctx.config().factKinds().byName("plumb.call.return-value-folded");
    if (kind.isEmpty()) {
      return Optional.empty();
    }
    List<Fact> facts =
        ctx.factBag().factsOfVar(LiteralBooleanConditionChecker.operandName(operand));
    for (Fact f : facts) {
      String v;
      if (!f.hasKind((FactKind) kind.get()) || (v = f.value()) == null || !v.startsWith("const:"))
        continue;
      return Optional.of(v.substring("const:".length()));
    }
    return Optional.empty();
  }

  private static String operandName(TacOperand op) {
    if (op instanceof TacOperand.TacVar) {
      TacOperand.TacVar v = (TacOperand.TacVar) op;
      return v.name();
    }
    return null;
  }

  private void emitSourceLiteral(AnalysisContext ctx, TacInstruction ins, TacOperand.Const c) {
    // step_f: honor rule.enabled() flag.
    if (!this.rule.enabled()) {
      return;
    }
    Finding finding =
        Finding.dfa(
            this.rule.ruleId(),
            this.rule.ruleId(),
            "Branch condition is a literal constant: "
                + String.valueOf(c.value())
                + " \u2014 likely a typo",
            this.rule.severity(),
            this.rule.cwe(),
            "",
            ins.line,
            ins.column,
            "literal-boolean: " + String.valueOf(c.value()));
    ctx.reporter().report(finding);
  }

  private void emitFoldedConst(AnalysisContext ctx, TacInstruction ins, String constValue) {
    // step_f: honor rule.enabled() flag.
    if (!this.rule.enabled()) {
      return;
    }
    Finding finding =
        Finding.dfa(
            this.rule.ruleId(),
            this.rule.ruleId(),
            "Branch condition folds to a literal constant: "
                + constValue
                + " \u2014 branch is dead/redundant (after cross-function fold)",
            this.rule.severity(),
            this.rule.cwe(),
            "",
            ins.line,
            ins.column,
            "literal-boolean-folded: " + constValue);
    ctx.reporter().report(finding);
  }
}
