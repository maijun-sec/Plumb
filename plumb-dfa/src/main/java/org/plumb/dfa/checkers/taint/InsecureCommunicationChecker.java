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
 * Sensitive data sent over a channel that is not protected.
 *
 * Sink family: insecure-comms.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

/**
 * Sensitive data sent over a channel that is not protected.
 *
 * <p><b>Why this checker exists.</b> The family it watches -- {@code insecure-comms.sinks} --
 * carried a pattern set with no watcher. A family is a set of symbols; a checker is what looks at
 * them. Without a checker the pattern matched a call and the match led nowhere, so the defect class
 * could not produce a finding at all.
 */
public final class InsecureCommunicationChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: Insecure communication
      (CWE-297/319/326/327).
      This rule fires when sensitive data
      reaches a channel that does not
      protect it -- a plain socket, a
      cleartext HTTP endpoint, a
      communication channel with no
      encryption configured.

      A finding is a TRUE POSITIVE (TP) when
      the sensitive value is sent without
      transport protection.

      A finding is a FALSE POSITIVE (FP) when
      the channel is a loopback or an
      in-process call, OR when the data is
      not actually sensitive.

      A finding is UNKNOWN when you cannot
      decide from source + dataflow alone.
      """;

  public InsecureCommunicationChecker() {
    super(
        new TaintRule(
            "dfa-taint-insecure-communication",
            "dfa-taint-untrusted-source",
            "insecure-comms.sinks",
            "CWE-297,CWE-319,CWE-326,CWE-327",
            "HIGH",
            TaintDomain.MixedTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
