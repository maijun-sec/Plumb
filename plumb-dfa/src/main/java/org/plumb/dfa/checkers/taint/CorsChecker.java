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
 * Permissive cross-domain policy checker (CWE-942).
 *
 * Sink family: cors.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

public final class CorsChecker extends TaintedCheckerBase {
  private static final String PROMPT =
      """
      RULE: Permissive cross-domain policy (CWE-942).
      This rule fires when tainted data reaches a
      cross-domain response header: an origin, or a
      header the browser reads to decide which origin
      may read the response.

      The risk is that the policy is written from
      tainted data. An allow-origin the attacker
      chooses, or a wildcard credential header, lets
      a page the attacker serves read a response the
      server meant for another origin.

      A finding is a TRUE POSITIVE (TP) when the
      tainted value is the allowed origin and the
      header does not restrict it to a fixed set.

      A finding is a FALSE POSITIVE (FP) when the
      origin is a constant, when it comes from a
      configuration value the attacker cannot change,
      or when the code checks the origin against an
      allow-list before the header is written.

      A finding is UNKNOWN when you cannot tell from
      source + dataflow alone.
      """;

  public CorsChecker() {
    super(
        new TaintRule(
            "dfa-taint-cors",
            "dfa-taint-untrusted-source",
            "cors.sinks",
            "CWE-942",
            "MEDIUM",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
