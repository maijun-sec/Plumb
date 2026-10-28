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
 * HTTP response splitting checker (CWE-113).
 * Tainted data flowing into response.setHeader()
 * / addHeader() / sendError() (the value side,
 * not the name side) or into a header name of
 * a forwarded response.
 *
 * Sink family: header.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacInstruction;

public final class HttpResponseSplittingChecker extends TaintedCheckerBase {
  private static final String PROMPT =
      """
      RULE: HTTP response splitting (CWE-113).
      This rule fires when a tainted string flows
      into an HTTP response header setter
      (response.setHeader / addHeader /
      setStatus / sendError / sendRedirect,
      where the tainted arg is the value).

      A finding is a TRUE POSITIVE (TP) when the
      data carries CRLF / control chars and
      reaches the response without sanitisation.

      A finding is a FALSE POSITIVE (FP) when the
      value is sanitised
      (e.g. URLEncoder.encode, header
      validation, an allow-list) OR when
      Content-Type is set to text/plain /
      application/json that the browser
      doesn't interpret as HTML.

      A finding is UNKNOWN when you cannot
      decide from source + dataflow alone.
      """;

  public HttpResponseSplittingChecker() {
    super(
        new TaintRule(
            "dfa-taint-http-response-splitting",
            "dfa-taint-untrusted-source",
            "header.sinks",
            "CWE-113",
            "HIGH",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  protected int sinkArgumentIndex(SinkFamily family, TacInstruction call) {
    // setHeader(String name, String value) → taint
    // is at Java arg 1 (the value). addHeader
    // has the same shape. sendError(int, String)
    // → taint at Java arg 1. sendRedirect(String)
    // → taint at Java arg 0. Set the default
    // for the family to 1; subclasses that
    // watch only sendRedirect can override.
    return 1;
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
