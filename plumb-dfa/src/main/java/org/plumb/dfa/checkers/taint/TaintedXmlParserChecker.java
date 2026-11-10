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
 * Tainted XML reaches a parser checker (CWE-611).
 *
 * Sink family: xxe.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

public final class TaintedXmlParserChecker extends TaintedCheckerBase {
  private static final String PROMPT =
      """
      RULE: XML external entity (CWE-611).
      This rule fires when tainted XML reaches a
      parser: a document builder, a transformer, an
      unmarshaller, a SAX or StAX reader, or the
      .NET XML reader settings.

      The risk is that the parser resolves the
      entities the document names. An external entity
      makes the parser read a file or a URL the
      attacker chose, so the answer can carry local
      file contents into the output, or reach a host
      the server can reach but the attacker cannot.

      A finding is a TRUE POSITIVE (TP) when the
      tainted document is parsed by a parser that
      resolves external entities and DTDs, and the
      parser is not configured to disable them.

      A finding is a FALSE POSITIVE (FP) when the
      parser has external entities and DTD processing
      switched off, when it is a hardened factory the
      code configured on purpose, or when the tainted
      value is the parser's own configuration rather
      than the document it reads.

      A finding is UNKNOWN when you cannot tell from
      source + dataflow alone.
      """;

  public TaintedXmlParserChecker() {
    super(
        new TaintRule(
            "dfa-taint-xxe",
            "dfa-taint-untrusted-source",
            "xxe.sinks",
            "CWE-611",
            "MEDIUM",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
