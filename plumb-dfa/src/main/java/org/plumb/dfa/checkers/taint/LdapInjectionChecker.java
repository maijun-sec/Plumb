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
 * LDAP injection checker (CWE-90).
 * Tainted data flows into a DirContext /
 * LdapContext / InitialContext.search()
 * call. The standard signature is
 *   search(String baseDN, String filter,
 *          SearchControls controls)
 * where the tainted value is at Java arg
 * index 1 (the filter), not 0 (the base DN).
 * The base sinkArgumentIndex default returns
 * 0, which would pick the (typically clean)
 * baseDN and silently miss the taint.
 *
 * Sink family: ldap.sinks (see
 *  plumb-dfa/src/main/resources/
 *  sink-families.yaml).
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacInstruction;

public final class LdapInjectionChecker extends TaintedCheckerBase {
  private static final String PROMPT =
      """
      RULE: LDAP injection (CWE-90).
      This rule fires when a tainted string flows
      into an LDAP search filter (e.g. via
      DirContext.search("", filter, controls)
      where filter is built by concatenating
      user input).

      A finding is a TRUE POSITIVE (TP) when the
      data really does reach the filter without
      proper escaping or parameterisation.

      A finding is a FALSE POSITIVE (FP) when the
      data is sanitised (e.g. JNDI context
      parameter binding, RFC 4515 escaping
      routine, allow-list) OR when the
      application uses a high-level ORM that
      safely parameterises the filter (rare;
      most JNDI code does manual string
      concat).

      A finding is UNKNOWN when you cannot tell
      from source + dataflow alone. Prefer
      UNKNOWN.
      """;

  public LdapInjectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-ldap-injection",
            "dfa-taint-untrusted-source",
            "ldap.sinks",
            "CWE-90",
            "HIGH",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  protected int sinkArgumentIndex(SinkFamily family, TacInstruction call) {
    // search(Name, String filter, Controls) →
    // taint-bearing filter is at Java arg 1.
    // search(Name, String filter, Object[] args,
    //        Controls) → taint is still arg 1.
    return 1;
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
