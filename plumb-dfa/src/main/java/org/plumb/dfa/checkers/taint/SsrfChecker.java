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
 * Server-side request forgery checker (CWE-918).
 *
 * Sink family: ssrf.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

public final class SsrfChecker extends TaintedCheckerBase {
  private static final String PROMPT =
      """
      RULE: Server-side request forgery (CWE-918).
      This rule fires when a tainted URL, host or
      address reaches a call that makes the SERVER
      reach out: an HTTP client, a socket, a
      connection, a DNS lookup or a service client.

      The risk is that the attacker chooses the
      destination: an internal address the server
      can reach but the attacker cannot, or a
      server the attacker controls.

      A finding is a TRUE POSITIVE (TP) when the
      tainted value is the destination of a request
      the server makes and the destination is not
      restricted to a known allow-list.

      A finding is a FALSE POSITIVE (FP) when the
      destination is a constant, when it comes from
      a configuration value the attacker cannot
      change, or when the code validates it against
      an allow-list of hosts or schemes before the
      call.

      A finding is UNKNOWN when you cannot tell from
      source + dataflow alone.
      """;

  public SsrfChecker() {
    super(
        new TaintRule(
            "dfa-taint-ssrf",
            "dfa-taint-untrusted-source",
            "ssrf.sinks",
            "CWE-918",
            "HIGH",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
