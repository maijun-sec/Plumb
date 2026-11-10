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
 * Cleartext credential checker (CWE-311).
 *
 * Sink family: unencrypted-credential.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

public final class CleartextCredentialChecker extends TaintedCheckerBase {
  private static final String PROMPT =
      """
      RULE: Cleartext credential (CWE-311).
      This rule fires when a tainted secret reaches a
      store that keeps it without protecting it: an
      OAuth consumer secret, an access token and its
      secret, a token registration or revocation.

      The risk is that the secret is stored in the
      clear. A consumer secret that is written down
      where a log, a backup or a repository can read
      it is a secret the attacker can take.

      A finding is a TRUE POSITIVE (TP) when the
      tainted value is a secret or a token handed to a
      store that keeps it verbatim, with no encryption
      or hashing in front of it.

      A finding is a FALSE POSITIVE (FP) when the
      value is a constant the program owns, when it
      comes from a secret store that already decrypts
      it, or when the value is hashed or encrypted on
      the way in.

      A finding is UNKNOWN when you cannot tell from
      source + dataflow alone.
      """;

  public CleartextCredentialChecker() {
    super(
        new TaintRule(
            "dfa-taint-cleartext-credential",
            "dfa-taint-untrusted-source",
            "unencrypted-credential.sinks",
            "CWE-311",
            "CRITICAL",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
