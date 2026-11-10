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
 * Weak cryptography checker (CWE-261 / CWE-326).
 *
 * Sink family: weak-crypto.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

public final class WeakCryptoChecker extends TaintedCheckerBase {
  private static final String PROMPT =
      """
      RULE: Weak cryptography (CWE-261 / CWE-326).
      This rule fires when tainted data chooses the
      cryptographic algorithm or the key size: a
      digest, a cipher, a MAC or a key generator
      factory named at runtime, or a key size set on
      a cipher or a key pair.

      The risk is that the attacker picks the
      algorithm. A digest the attacker can break, a
      cipher in a mode that leaks, or a key shorter
      than the data it protects all turn a
      cryptographic operation into a weaker one than
      the code appears to perform.

      A finding is a TRUE POSITIVE (TP) when the
      tainted value is the algorithm name or the key
      length, and the value is not restricted to an
      allow-list of approved algorithms or sizes.

      A finding is a FALSE POSITIVE (FP) when the
      algorithm comes from a constant, when it comes
      from a configuration value the attacker cannot
      change, or when the code checks the name against
      an allow-list before the call.

      A finding is UNKNOWN when you cannot tell from
      source + dataflow alone.
      """;

  public WeakCryptoChecker() {
    super(
        new TaintRule(
            "dfa-taint-weak-crypto",
            "dfa-taint-untrusted-source",
            "weak-crypto.sinks",
            "CWE-261,CWE-326",
            "MEDIUM",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
