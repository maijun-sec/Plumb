/*
 * TrustBoundaryViolationChecker (CWE-501) properly wired.
 *
 * The CFR-decompiled stub extends TaintedCheckerBase
 * with the default sinkArgumentIndex(0), but the
 * canonical trust-boundary.sinks pattern is
 *   `HttpSession.setAttribute(name, value)` /
 *   `HashMap.put(key, value)`
 * where the tainted value is the SECOND Java arg,
 * not the first. This checker overrides
 * sinkArgumentIndex to fix that.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.tac.ir.TacInstruction;

public final class TrustBoundaryViolationChecker extends TaintedCheckerBase {
  public TrustBoundaryViolationChecker() {
    super(
        new TaintRule(
            "dfa-taint-trust-boundary-violation",
            "dfa-taint-untrusted-source",
            "trust-boundary.sinks",
            "CWE-501",
            "MEDIUM",
            TaintDomain.StringTaint.INSTANCE));
  }

  /**
   * The canonical trust-boundary.sinks are setAttribute / put where the tainted data lands in Java
   * arg 1 (after the name / key).
   */
  @Override
  protected int sinkArgumentIndex(SinkFamily family, TacInstruction call) {
    return 1;
  }
}
