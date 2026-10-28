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
 * XPath injection checker (CWE-643).
 * Tainted data flowing into XPath.compile()
 * or XPath.evaluate() (or the
 * XPathExpression.evaluate() follow-up call).
 *
 * Sink family: xpath.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

public final class XPathInjectionChecker extends TaintedCheckerBase {
  private static final String PROMPT =
      """
      RULE: XPath injection (CWE-643).
      This rule fires when a tainted string flows
      into an XPath expression (XPath.compile /
      XPath.evaluate / XPathExpression.evaluate /
      XPathAPI.selectNodeList).

      A finding is a TRUE POSITIVE (TP) when the
      tainted data really becomes part of the
      XPath expression without parameterisation.

      A finding is a FALSE POSITIVE (FP) when the
      application uses a safe XPath builder
      (e.g. xalan XSLT with parameter binding)
      OR when the tainted value is restricted
      via an allow-list before the call.

      A finding is UNKNOWN when you cannot tell
      from source + dataflow alone. Prefer
      UNKNOWN.
      """;

  public XPathInjectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-xpath-injection",
            "dfa-taint-untrusted-source",
            "xpath.sinks",
            "CWE-643",
            "HIGH",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
