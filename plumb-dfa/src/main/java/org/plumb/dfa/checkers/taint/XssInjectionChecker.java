/*
 * XSS injection checker (CWE-79).
 * Tainted data flowing into a writer/print/append
 * on the HTTP response. Sink family: xss.sinks
 * (see plumb-dfa/src/main/resources/
 *  sink-families.yaml).
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacInstruction;

public final class XssInjectionChecker extends TaintedCheckerBase {
  private static final String PROMPT =
      """
      RULE: XSS (Cross-Site Scripting, CWE-79).
      This rule fires when a tainted string flows
      into an HTTP response writer (response
      writer print/println/write/append), or into
      a String that is then written to the
      response.

      A finding is a TRUE POSITIVE (TP) when the
      data really does reach the response body or
      a response header without escaping or
      sanitisation.

      A finding is a FALSE POSITIVE (FP) when the
      data is sanitised on the way to the sink
      (HTML escape, OWASP Java Encoder, allow-list
      of tags, etc.) OR when the response uses
      Content-Type that does not interpret HTML
      (e.g. application/json with proper encoding).

      A finding is UNKNOWN when you cannot decide
      from the dataflow alone. Prefer UNKNOWN.
      """;

  public XssInjectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-xss-injection",
            "dfa-taint-untrusted-source",
            "xss.sinks",
            "CWE-79",
            "HIGH",
            TaintDomain.StringTaint.INSTANCE));
  }

  /**
   * R692: Override default sink arg index to 1 (the value side). Default 0 works for
   * PrintWriter.print(String) where the string is the only user-visible arg, but fails for sinks
   * like HttpServletResponse.sendError(int code, String msg) where the int code at user-arg 0 is
   * never tainted and the string at user-arg 1 carries the XSS payload.
   *
   * <p>Sinks with 0 user-args (e.g. response.getWriter()) fall through the arg-bounds check in
   * checkSinkForFamily and stay silent, which is the correct behaviour.
   */
  @Override
  protected int sinkArgumentIndex(SinkFamily family, TacInstruction call) {
    return 1;
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
