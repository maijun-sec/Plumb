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
 * Open redirect checker (CWE-601).
 * Tainted data flowing into HttpServletResponse
 * .sendRedirect(String) or
 * org.springframework.web.servlet.view.RedirectView
 * .setUrl / render.
 *
 * Sink family: open-redirect.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

public final class OpenRedirectChecker extends TaintedCheckerBase {
  private static final String PROMPT =
      """
      RULE: Open redirect (CWE-601).
      This rule fires when a tainted URL flows
      into response.sendRedirect(String) (or
      Spring's RedirectView.setUrl / render)
      so an attacker can redirect users to a
      phishing or malware site.

      A finding is a TRUE POSITIVE (TP) when
      the tainted value is the redirect
      target without validation.

      A finding is a FALSE POSITIVE (FP) when
      the value is restricted to a known
      allow-list of internal paths OR when
      the value is the result of a relative-
      path resolver that strips / prefixes.

      A finding is UNKNOWN when you cannot
      tell from source + dataflow alone.
      """;

  public OpenRedirectChecker() {
    super(
        new TaintRule(
            "dfa-taint-open-redirect",
            "dfa-taint-untrusted-source",
            "open-redirect.sinks",
            "CWE-601",
            "MEDIUM",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
