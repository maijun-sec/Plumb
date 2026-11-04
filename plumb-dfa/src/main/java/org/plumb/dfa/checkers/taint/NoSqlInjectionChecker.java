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
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

/**
 * NoSQL injection (CWE-943).
 *
 * <p>Tainted data flowing into a NoSQL query's filter or document -- MongoDB's {@code
 * Collection#find}, a {@code Criteria#matches} filter, or a document field. The danger is that the
 * tainted value becomes a QUERY OPERATOR rather than a value: {@code {"$ne": null}} matches every
 * document, so an authorisation filter that is meant to say "owner == me" becomes "owner is not
 * null".
 *
 * <p>This is a different defect from SQL injection ({@code sql.sinks}): the injection is into a
 * document structure, not into a query string.
 */
public final class NoSqlInjectionChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: NoSQL injection (CWE-943).
      This rule fires when a tainted string flows
      into a NoSQL query (a MongoDB find /
      update, or a Criteria filter).

      The risk is that the tainted value becomes a
      query OPERATOR rather than a value: a
      filter meant to say "owner == me" becomes
      "owner is not null", which matches every
      document.

      A finding is a TRUE POSITIVE (TP) when
      the tainted value reaches the sink
      unescaped and unsanitised.

      A finding is a FALSE POSITIVE (FP) when
      the value is a fixed constant, or when it
      is validated or escaped before the call.

      A finding is UNKNOWN when you cannot tell
      from source + dataflow alone. Prefer
      UNKNOWN.
      """;

  public NoSqlInjectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-nosql-injection",
            "dfa-taint-untrusted-source",
            "nosql.sinks",
            "CWE-943",
            "HIGH",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
