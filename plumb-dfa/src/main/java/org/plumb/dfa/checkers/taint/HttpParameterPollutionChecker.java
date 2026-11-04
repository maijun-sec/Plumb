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
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

/**
 * HTTP parameter pollution (CWE-235).
 *
 * <p>Tainted data flowing into an outbound HTTP request's URI or query parameters, where the caller
 * can supply the SAME parameter more than once -- or supply a parameter that the server parses
 * differently from the client's proxy.
 *
 * <p>The danger is a disagreement about which value wins: the application sees the first value and
 * the authorisation layer sees the last.
 */
public final class HttpParameterPollutionChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: HTTP parameter pollution (CWE-235).
      This rule fires when a tainted string flows
      into an outbound HTTP request's URI or
      query parameters (RestTemplate's
      getFor / postFor / exchange, or the
      equivalent).

      The risk is parameter pollution: the same
      parameter supplied twice, where the client
      and the server disagree about which value
      wins.

      A finding is a TRUE POSITIVE (TP) when
      the tainted value reaches the sink
      unescaped and unsanitised.

      A finding is a FALSE POSITIVE (FP) when
      the value is a fixed constant, or when it
      is validated or escaped before the call.

      A finding is UNKNOWN when you cannot tell
      from source + dataflow alone. Prefer
      UNKNOWN.
      """;

  public HttpParameterPollutionChecker() {
    super(
        new TaintRule(
            "dfa-taint-http-parameter-pollution",
            "dfa-taint-untrusted-source",
            "http-parameter-pollution.sinks",
            "CWE-235",
            "MEDIUM",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
