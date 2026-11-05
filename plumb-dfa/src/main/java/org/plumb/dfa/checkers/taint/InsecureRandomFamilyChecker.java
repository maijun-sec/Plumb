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
 * Weak randomness: a predictable generator used where unpredictability matters.
 *
 * Sink family: insecure-random.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

/**
 * Weak randomness: a predictable generator used where unpredictability matters.
 *
 * <p><b>Why this checker exists.</b> The family it watches -- {@code insecure-random.sinks} --
 * carried a pattern set with no watcher. A family is a set of symbols; a checker is what looks at
 * them. Without a checker the pattern matched a call and the match led nowhere, so the defect class
 * could not produce a finding at all.
 */
public final class InsecureRandomFamilyChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: Weak randomness (CWE-335/330).
      This rule fires when a value that must
      be unpredictable -- a token, a
      session id, a key, a salt -- comes
      from a generator whose output an
      attacker can predict.

      A finding is a TRUE POSITIVE (TP) when
      the generator is seeded from a
      predictable source or is not a
      cryptographic generator, AND the
      value is used for a security
      decision.

      A finding is a FALSE POSITIVE (FP) when
      the value is only used for
      non-security purposes such as a
      jitter or a load-balancing choice.

      A finding is UNKNOWN when you cannot
      decide from source + dataflow alone.
      """;

  public InsecureRandomFamilyChecker() {
    super(
        new TaintRule(
            "dfa-taint-insecure-random",
            "dfa-taint-untrusted-source",
            "insecure-random.sinks",
            "CWE-335,CWE-330",
            "MEDIUM",
            TaintDomain.MixedTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
