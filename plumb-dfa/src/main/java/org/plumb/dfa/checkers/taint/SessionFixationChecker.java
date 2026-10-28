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
