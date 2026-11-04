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
 * XSLT injection (CWE-643).
 *
 * <p>Tainted data flowing into an XSLT stylesheet or transform. The stylesheet is what decides what
 * the output looks like, so a tainted value there is not an injection into data -- it is an
 * injection into the transformation.
 */
public final class XsltInjectionChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: XSLT injection (CWE-643).
      This rule fires when a tainted string flows
      into an XSLT stylesheet or transform.

      The risk is that the stylesheet decides what
      the output looks like, so the tainted value
      controls the transformation rather than
      the data.

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

  public XsltInjectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-xslt-injection",
            "dfa-taint-untrusted-source",
            "xslt-injection.sinks",
            "CWE-643",
            "HIGH",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
