package org.plumb.dfa.checkers.taint;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.fact.BuiltinFactKinds;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacBasicBlock;
import org.plumb.tac.ir.TacCfg;
import org.plumb.tac.ir.TacCfgBuilder;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * — UncheckedLoopCondition checker (CWE-606).
 *
 * <p>Juliet CWE-606 pattern:
 *
 * <pre>{@code
 * data = req.getQueryString();
 * int n = Integer.parseInt(data);
 * for (int i = 0; i < n; i++) {   // →bad: loop bound is tainted
 *     ...
 * }
 * }</pre>
 *
 * <p>Built on the unified {@link TaintedCheckerBase} taint machinery: a var is tainted when it
 * flows from an input-source call (via MOVE / BINOP / CALL propagation). When a loop BRANCH's
 * condition BINOP uses a tainted operand, fire CWE-606. Vars range-checked by an {@code if (v >= 0
 * && v < N)} guard before the loop are suppressed (Juliet goodB2G pattern).
 */
public final class UncheckedLoopConditionChecker extends TaintedCheckerBase {

  public UncheckedLoopConditionChecker() {
    super(
        new TaintRule(
            "dfa-taint-unchecked-loop-condition",
            BuiltinFactKinds.UNTRUSTED_SOURCE,
            "loop.bound.sinks",
            "CWE-606",
            "MEDIUM",
            TaintDomain.NumericTaint.INSTANCE));
  }

  private final Set<String> validatedVars = new HashSet<>();
  private final Set<String> reported = new HashSet<>();

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    super.initFunction(ctx, fn);
    validatedVars.clear();
    reported.clear();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    // Range-check tracking: `v < N` or `v <= N` followed
    // by a BRANCH (i.e. an `if (v < N)` guard) marks `v`
    // as bounded. Conservative: any upper-bound check is
    // treated as a guard.
    if (ins.kind == TacInstruction.Kind.BINOP
        && (ins.binop == BinaryOperator.LT
            || ins.binop == BinaryOperator.LE
            || ins.binop == BinaryOperator.GT
            || ins.binop == BinaryOperator.GE)
        && ins.lhs1 instanceof TacOperand.TacVar v) {
      validatedVars.add(v.name());
    }
    super.checkInstruction(ins, fn, ctx);
  }

  @Override
  public void finishFunction(AnalysisContext ctx, TacFunction fn) {
    // Walk the CFG: a loop BRANCH has its trueLabel
    // pointing at a "L_FOR_BODY" / "L_WHILE_BODY" /
    // "L_DO_WHILE_BODY" block. The instruction just
    // before the BRANCH is the loop condition BINOP.
    TacCfg cfg = TacCfgBuilder.build(fn);
    for (TacBasicBlock bb : cfg.blocks()) {
      List<Integer> indices = bb.indices();
      for (int i = 0; i < indices.size(); i++) {
        int idx = indices.get(i);
        if (i == 0) continue;
        TacInstruction br = fn.instructionAt(idx);
        if (br.kind != TacInstruction.Kind.BRANCH) continue;
        if (br.trueLabel == null) continue;
        if (!(br.trueLabel.startsWith("L_FOR_BODY")
            || br.trueLabel.startsWith("L_WHILE_BODY")
            || br.trueLabel.startsWith("L_DO_WHILE_BODY"))) {
          continue;
        }
        TacInstruction cond = fn.instructionAt(idx - 1);
        if (cond.kind != TacInstruction.Kind.BINOP) continue;
        if (cond.binop != BinaryOperator.LT
            && cond.binop != BinaryOperator.LE
            && cond.binop != BinaryOperator.GT
            && cond.binop != BinaryOperator.GE) {
          continue;
        }
        if (isTainted(cond.lhs1, cond.lhs2)) {
          String taintedName = taintedName(cond.lhs1, cond.lhs2);
          if (validatedVars.contains(taintedName)) continue;
          String key = "cwe606|" + idx;
          if (reported.add(key)) {
            emit(br, fn, ctx, taintedName, cond.binop);
          }
        }
      }
    }
    super.finishFunction(ctx, fn);
  }

  private boolean isTainted(TacOperand a, TacOperand b) {
    // was `taintedVars.containsKey(v1.name())`
    // — passing a String as key into a
    // `Map<TacOperand.TacVar, String>`. Always
    // false, so the checker was silently dead.
    // Match by name (TacVar equality isn't by name;
    // SSA-versioned names differ across definitions).
    if (a instanceof TacOperand.TacVar v1 && containsVar(v1)) {
      return true;
    }
    if (b instanceof TacOperand.TacVar v2 && containsVar(v2)) {
      return true;
    }
    return false;
  }

  private boolean containsVar(TacOperand.TacVar v) {
    for (TacOperand.TacVar key : taintedVars.keySet()) {
      if (key.name().equals(v.name())) return true;
    }
    return false;
  }

  private String taintedName(TacOperand a, TacOperand b) {
    // same containsKey(name()) bug as isTainted;
    // would always return "<unknown>".
    if (a instanceof TacOperand.TacVar v1 && containsVar(v1)) {
      return v1.name();
    }
    if (b instanceof TacOperand.TacVar v2 && containsVar(v2)) {
      return v2.name();
    }
    return "<unknown>";
  }

  private void emit(
      TacInstruction ins, TacFunction fn, AnalysisContext ctx, String varName, BinaryOperator op) {
    Finding f =
        Finding.violation(
            "UncheckedLoopCondition",
            "dfa-taint-unchecked-loop-condition",
            "Loop bound '"
                + varName
                + "' is derived from untrusted input "
                + "without validation (loop operator "
                + op
                + ") — an attacker can drive the loop count "
                + "(CWE-606). Validate the bound against a small upper "
                + "limit before the loop.",
            RuleDef.Severity.MEDIUM,
            "CWE-606",
            ins.line,
            ins.column,
            fn.signature(),
            "unchecked-loop-bound: " + varName);
    ctx.reporter().report(f);
  }
}
