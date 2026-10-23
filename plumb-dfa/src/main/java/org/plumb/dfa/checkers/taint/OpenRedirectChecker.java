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
