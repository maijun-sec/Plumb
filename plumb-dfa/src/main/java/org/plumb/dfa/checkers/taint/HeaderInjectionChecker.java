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
 * CRLF injection: tainted data written into an HTTP header.
 *
 * Sink family: header-injection.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

/**
 * CRLF injection: tainted data written into an HTTP header.
 *
 * <p><b>Why this checker exists.</b> The family it watches -- {@code header-injection.sinks} --
 * carried a pattern set with no watcher. A family is a set of symbols; a checker is what looks at
 * them. Without a checker the pattern matched a call and the match led nowhere, so the defect class
 * could not produce a finding at all.
 */
public final class HeaderInjectionChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: CRLF header injection
      (CWE-93/113).
      This rule fires when tainted data
      reaches an HTTP header setter, where
      an embedded CRLF ends the header and
      starts another.

      A finding is a TRUE POSITIVE (TP) when
      the value can carry CR or LF and
      reaches the header without stripping.

      A finding is a FALSE POSITIVE (FP) when
      the value is validated against an
      allow-list OR when the framework
      rejects CR and LF in header values.

      A finding is UNKNOWN when you cannot
      decide from source + dataflow alone.
      """;

  public HeaderInjectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-header-injection",
            "dfa-taint-untrusted-source",
            "header-injection.sinks",
            "CWE-93,CWE-113",
            "MEDIUM",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
