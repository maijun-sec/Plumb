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
 * Cookie injection: tainted data written into a response cookie.
 *
 * Sink family: cookie.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

/**
 * Cookie injection: tainted data written into a response cookie.
 *
 * <p><b>Why this checker exists.</b> The family it watches -- {@code cookie.sinks} -- carried a
 * pattern set with no watcher. A family is a set of symbols; a checker is what looks at them.
 * Without a checker the pattern matched a call and the match led nowhere, so the defect class could
 * not produce a finding at all.
 */
public final class CookieInjectionChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: Cookie injection (CWE-113).
      This rule fires when tainted data is
      written into a response cookie, where
      a CRLF or a delimiter in the value
      changes what the browser stores or
      sends back.

      A finding is a TRUE POSITIVE (TP) when
      the value reaches the cookie without
      being stripped of control characters.

      A finding is a FALSE POSITIVE (FP) when
      the value is validated against an
      allow-list OR when the cookie is a
      server-side cookie whose value is an
      opaque identifier the server assigned.

      A finding is UNKNOWN when you cannot
      decide from source + dataflow alone.
      """;

  public CookieInjectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-cookie-injection",
            "dfa-taint-untrusted-source",
            "cookie.sinks",
            "CWE-113",
            "MEDIUM",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
