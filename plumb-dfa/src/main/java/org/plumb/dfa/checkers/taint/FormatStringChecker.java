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
 * Uncontrolled format string checker (CWE-134).
 * Tainted data flowing into String.format,
 * System.out/err.printf/format, or
 * PrintStream/PrintWriter.printf/format.
 *
 * Sink family: format-string.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

public final class FormatStringChecker extends TaintedCheckerBase {
  private static final String PROMPT =
      """
      RULE: Uncontrolled format string (CWE-134).
      This rule fires when a tainted string
      flows into the format string position of
      a printf-style call (String.format,
      System.out.printf, PrintStream.printf,
      etc.) so the attacker controls the
      format spec.

      A finding is a TRUE POSITIVE (TP) when
      the tainted value is used directly as
      the format string (not as a format
      argument).

      A finding is a FALSE POSITIVE (FP) when
      the tainted value is in a format-arg
      position (printf("hello %s", tainted))
      rather than the format position
      (printf(tainted)), or when the format
      string is constant.

      A finding is UNKNOWN when you cannot
      tell from source + dataflow alone.
      """;

  public FormatStringChecker() {
    super(
        new TaintRule(
            "dfa-taint-format-string",
            "dfa-taint-untrusted-source",
            "format-string.sinks",
            "CWE-134",
            "MEDIUM",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
