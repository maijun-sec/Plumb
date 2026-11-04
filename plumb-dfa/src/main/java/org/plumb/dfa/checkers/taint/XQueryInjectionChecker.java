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
 * XQuery injection (CWE-943).
 *
 * <p>Tainted data flowing into an XQuery expression -- an {@code XQDynamicContext#bind} call, or a
 * query built as a string. XQuery is a full query language over XML, so a tainted value that
 * becomes part of the expression is a query injection in the same sense as SQL injection.
 */
public final class XQueryInjectionChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: XQuery injection (CWE-943).
      This rule fires when a tainted string flows
      into an XQuery expression (an
      XQDynamicContext bind, or a query built
      as a string).

      The risk is that XQuery is a full query
      language over XML, so the tainted value
      becomes part of the query.

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

  public XQueryInjectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-xquery-injection",
            "dfa-taint-untrusted-source",
            "xquery.sinks",
            "CWE-943",
            "HIGH",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
