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
