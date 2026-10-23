/*
 * SessionFixationChecker (CWE-384) properly wired.
 *
 * The CFR-decompiled stub extends TaintedCheckerBase
 * with the default sinkArgumentIndex(0), but the
 * canonical session.sinks pattern is
 * `setHeader(name, value)` /
 * `setAttribute(name, value)` where the tainted
 * value is the SECOND Java arg, not the first. This
 * checker overrides sinkArgumentIndex to fix that.
 * Also extends the same short-form callee-sig
 * support that the patterns accept.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.tac.ir.TacInstruction;

public final class SessionFixationChecker extends TaintedCheckerBase {
  public SessionFixationChecker() {
    super(
        new TaintRule(
            "dfa-taint-session-fixation",
            "dfa-taint-untrusted-source",
            "session.sinks",
            "CWE-384",
            "HIGH",
            TaintDomain.StringTaint.INSTANCE));
  }

  /**
   * The canonical session.sinks are setHeader / setAttribute / changeSessionId / Session.setId,
   * where the attacker-controlled value lands in Java arg 1 (after the name / target).
   */
  @Override
  protected int sinkArgumentIndex(SinkFamily family, TacInstruction call) {
    return 1;
  }
}
