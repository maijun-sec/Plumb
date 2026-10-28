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
