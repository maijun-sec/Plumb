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
 *  org.plumb.tac.ir.TacOperand$TacVar
 */
package org.plumb.dfa.checkers.taint;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

public final class AssignmentInConditionalChecker extends TaintedCheckerBase {
  private final Set<TacOperand.TacVar> lastAssigned = new HashSet<TacOperand.TacVar>();

  public AssignmentInConditionalChecker() {
    super(
        new TaintRule(
            "dfa-taint-assignment-in-conditional",
            "plumb.nullable.may-be-null",
            "pointer.deref.sinks",
            "CWE-481",
            "MEDIUM",
            (TaintDomain) TaintDomain.PointerTaint.INSTANCE));
  }

  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    super.initFunction(ctx, fn);
    this.lastAssigned.clear();
  }

  /*
   * Enabled aggressive block sorting
   */
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    TacOperand tacOperand;
    super.checkInstruction(ins, fn, ctx);
    if (ins.kind == TacInstruction.Kind.MOVE
        && (tacOperand = ins.dest) instanceof TacOperand.TacVar) {
      TacOperand.TacVar d = (TacOperand.TacVar) tacOperand;
      if (!(ins.lhs1 instanceof TacOperand.Const)) {
        this.lastAssigned.add(d);
        return;
      }
    }
    if (ins.kind != TacInstruction.Kind.BRANCH) return;
    tacOperand = ins.lhs1;
    if (!(tacOperand instanceof TacOperand.TacVar)) return;
    TacOperand.TacVar v = (TacOperand.TacVar) tacOperand;
    if (this.lastAssigned.contains(v)) {
      this.emitMove(ctx, ins, v);
      return;
    }
    Optional<String> folded = AssignmentInConditionalChecker.foldedConstValue(ctx, (TacOperand) v);
    if (!folded.isPresent()) return;
    this.emitFoldedConst(ctx, ins, v, folded.get());
  }

  private static Optional<String> foldedConstValue(AnalysisContext ctx, TacOperand operand) {
    Optional kind = ctx.config().factKinds().byName("plumb.call.return-value-folded");
    if (kind.isEmpty()) {
      return Optional.empty();
    }
    List<Fact> facts =
        ctx.factBag().factsOfVar(AssignmentInConditionalChecker.operandName(operand));
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

  private void emitMove(AnalysisContext ctx, TacInstruction ins, TacOperand.TacVar v) {
    Finding finding =
        Finding.dfa(
            this.rule.ruleId(),
            this.rule.ruleId(),
            "Branch condition is the result of an assignment: did you mean '=='?",
            this.rule.severity(),
            this.rule.cwe(),
            "",
            ins.line,
            ins.column,
            "assignment-in-conditional: " + v.name());
    ctx.reporter().report(finding);
  }

  private void emitFoldedConst(
      AnalysisContext ctx, TacInstruction ins, TacOperand.TacVar v, String constValue) {
    Finding finding =
        Finding.dfa(
            this.rule.ruleId(),
            this.rule.ruleId(),
            "Branch condition folds to a constant: "
                + constValue
                + " \u2014 branch is dead/redundant (after cross-function fold)",
            this.rule.severity(),
            this.rule.cwe(),
            "",
            ins.line,
            ins.column,
            "assignment-in-conditional-folded: " + v.name() + " = " + constValue);
    ctx.reporter().report(finding);
  }
}
