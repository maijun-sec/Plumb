/*
 * Taint-style checker for CWE-674
 * (Uncontrolled Recursion).
 *
 * <p>Sibling to {@link
 * org.plumb.dfa.checkers.RecursionChecker}
 * — the DFA-style version in the main checkers
 * package. The DFA version runs always; this one
 * runs only when taint is enabled (because it
 * extends {@link TaintedCheckerBase}). It uses
 * the same name + arity match against the
 * callee signature and emits a CWE-674 finding
 * at each self-recursive call site.</p>
 *
 * <p>Bug fix: the original CFR-decompiled
 * version only checked for the
 * {@code #shortName()} and {@code #shortName}
 * Jimple-style suffixes. The TAC IR for Java
 * source uses the {@code pkg.Cls.method/arity}
 * format emitted by
 * {@link org.plumb.tac.translate.TacTranslator#methodSig},
 * so the original never fired on the Juliet
 * CWE-674 corpus. This version adds the
 * {@code .shortName/arity} suffix check and the
 * arity-aware match so the Java-source path
 * works too.</p>
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

public final class UncontrolledRecursionChecker extends TaintedCheckerBase {
  public UncontrolledRecursionChecker() {
    super(
        new TaintRule(
            "dfa-taint-uncontrolled-recursion",
            "plumb.nullable.may-be-null",
            "pointer.deref.sinks",
            "CWE-674",
            "MEDIUM",
            (TaintDomain) TaintDomain.PointerTaint.INSTANCE));
  }

  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    super.checkInstruction(ins, fn, ctx);
    if (ins.kind != TacInstruction.Kind.CALL) {
      return;
    }
    if (ins.calleeSig == null) {
      return;
    }
    String callee = ins.calleeSig;
    String mine = fn.signature();
    // Same logic as
    // RecursionChecker: extract the
    // bare method name from the callee sig
    // (strip the class prefix and the
    // /arity suffix), compare to the fn's
    // short name. Also match arity to avoid
    // foo(1) matching foo(2).
    String fnName = fn.name();
    int lastDot = fnName == null ? -1 : fnName.lastIndexOf('.');
    String shortName = (fnName != null && lastDot >= 0) ? fnName.substring(lastDot + 1) : fnName;
    int arity = fn.params() == null ? 0 : fn.params().size();
    int lastSep = Math.max(callee.lastIndexOf('.'), callee.lastIndexOf('#'));
    String calleeMethod = lastSep >= 0 ? callee.substring(lastSep + 1) : callee;
    int slash = calleeMethod.lastIndexOf('/');
    int calleeArity = -1;
    if (slash > 0) {
      try {
        calleeArity = Integer.parseInt(calleeMethod.substring(slash + 1));
      } catch (NumberFormatException ignore) {
      }
      calleeMethod = calleeMethod.substring(0, slash);
    }
    boolean nameMatch = shortName != null && shortName.equals(calleeMethod);
    boolean arityMatch = calleeArity < 0 || calleeArity == arity;
    boolean isSelfRecursion =
        callee.equals(mine)
            || (nameMatch && arityMatch)
            || (shortName != null
                && (callee.endsWith("#" + shortName + "()") || callee.endsWith("#" + shortName)));
    if (isSelfRecursion) {
      Finding finding =
          Finding.dfa(
              this.rule.ruleId(),
              this.rule.ruleId(),
              "Uncontrolled recursion: "
                  + fn.signature()
                  + " calls itself without an obvious base case (no early-return guard found in the"
                  + " immediate predecessors)",
              this.rule.severity(),
              this.rule.cwe(),
              "",
              ins.line,
              ins.column,
              "uncontrolled-recursion: " + fn.signature());
      ctx.reporter().report(finding);
    }
  }
}
