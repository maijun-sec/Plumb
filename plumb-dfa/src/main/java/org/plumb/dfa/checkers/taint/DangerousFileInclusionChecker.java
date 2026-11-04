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
 * Dangerous file inclusion (CWE-98).
 *
 * <p>Tainted data flowing into a template or page INCLUDE -- a JSP's {@code <jsp:include>}, a
 * servlet's {@code RequestDispatcher.include}, or an equivalent. The tainted value chooses WHICH
 * FILE is included, so the attacker picks the file.
 *
 * <p>This is a different defect from path traversal ({@code file-path.sinks}): the path is not
 * being opened as data, it is being included as CODE.
 */
public final class DangerousFileInclusionChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: Dangerous file inclusion (CWE-98).
      This rule fires when a tainted string flows
      into a file INCLUDE (a JSP include, a
      RequestDispatcher.include, or the
      equivalent).

      The risk is that the tainted value chooses
      WHICH FILE is included, so the attacker
      picks the file and its contents run as
      code.

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

  public DangerousFileInclusionChecker() {
    super(
        new TaintRule(
            "dfa-taint-file-inclusion",
            "dfa-taint-untrusted-source",
            "file-inclusion.sinks",
            "CWE-98",
            "CRITICAL",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
