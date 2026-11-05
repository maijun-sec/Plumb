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
 * Resource injection: attacker-controlled selection of a resource.
 *
 * Sink family: resource-injection.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

/**
 * Resource injection: attacker-controlled selection of a resource.
 *
 * <p><b>Why this checker exists.</b> The family it watches -- {@code resource-injection.sinks} --
 * carried a pattern set with no watcher. A family is a set of symbols; a checker is what looks at
 * them. Without a checker the pattern matched a call and the match led nowhere, so the defect class
 * could not produce a finding at all.
 */
public final class ResourceInjectionChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: Resource injection
      (CWE-94/95/99/502).
      This rule fires when tainted data
      selects the resource that is used --
      a file name, a URL, a host, a class
      to load -- so an attacker reaches a
      resource the application did not
      intend.

      A finding is a TRUE POSITIVE (TP) when
      the tainted value determines the
      resource and is not restricted to an
      allowed set.

      A finding is a FALSE POSITIVE (FP) when
      the value is normalised and checked
      against a base directory, a host
      allow-list, or a fixed resource
      name.

      A finding is UNKNOWN when you cannot
      decide from source + dataflow alone.
      """;

  public ResourceInjectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-resource-injection",
            "dfa-taint-untrusted-source",
            "resource-injection.sinks",
            "CWE-94,CWE-95,CWE-99,CWE-502",
            "MEDIUM",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
