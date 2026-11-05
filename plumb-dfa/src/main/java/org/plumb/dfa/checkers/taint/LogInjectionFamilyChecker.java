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
 * Log injection: tainted data written into a log record.
 *
 * Sink family: log-injection.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

/**
 * Log injection: tainted data written into a log record.
 *
 * <p><b>Why this checker exists.</b> The family it watches -- {@code log-injection.sinks} --
 * carried a pattern set with no watcher. A family is a set of symbols; a checker is what looks at
 * them. Without a checker the pattern matched a call and the match led nowhere, so the defect class
 * could not produce a finding at all.
 */
public final class LogInjectionFamilyChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: Log injection (CWE-117).
      This rule fires when tainted data
      reaches a logging call, where an
      embedded newline forges a log record
      or hides what really happened.

      A finding is a TRUE POSITIVE (TP) when
      the value reaches the log without
      newline or control-character
      stripping.

      A finding is a FALSE POSITIVE (FP) when
      the logging framework escapes the
      value, OR when the value is a
      structured field the framework
      encodes rather than concatenates.

      A finding is UNKNOWN when you cannot
      decide from source + dataflow alone.
      """;

  public LogInjectionFamilyChecker() {
    super(
        new TaintRule(
            "dfa-taint-log-injection-family",
            "dfa-taint-untrusted-source",
            "log-injection.sinks",
            "CWE-117",
            "MEDIUM",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
