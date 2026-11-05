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
 * Formula injection: tainted data written into a spreadsheet cell.
 *
 * Sink family: formula-injection.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

/**
 * Formula injection: tainted data written into a spreadsheet cell.
 *
 * <p><b>Why this checker exists.</b> The family it watches -- {@code formula-injection.sinks} --
 * carried a pattern set with no watcher. A family is a set of symbols; a checker is what looks at
 * them. Without a checker the pattern matched a call and the match led nowhere, so the defect class
 * could not produce a finding at all.
 */
public final class FormulaInjectionChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: Formula injection (CWE-1236).
      This rule fires when tainted data is
      written into a spreadsheet cell, where
      a leading '=' or '+' makes the
      spreadsheet evaluate the value as a
      formula.

      A finding is a TRUE POSITIVE (TP) when
      the cell value is attacker-controlled
      and is not prefixed or escaped.

      A finding is a FALSE POSITIVE (FP) when
      the writer prefixes a quote or escapes
      the leading characters.

      A finding is UNKNOWN when you cannot
      decide from source + dataflow alone.
      """;

  public FormulaInjectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-formula-injection",
            "dfa-taint-untrusted-source",
            "formula-injection.sinks",
            "CWE-1236",
            "MEDIUM",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
