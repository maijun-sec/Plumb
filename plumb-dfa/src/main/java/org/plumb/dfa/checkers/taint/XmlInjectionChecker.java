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
 * XML injection (CWE-91).
 *
 * <p>Tainted data flowing into an XML serializer's element name, attribute value, comment or
 * processing instruction. The danger is that the attacker controls XML <i>structure</i>, not just
 * text: a crafted element name or attribute breaks out of the intended document and injects new
 * nodes.
 *
 * <p>This is a different defect from XXE ({@code xxe.sinks}), which is about the parser reading
 * external entities, and from expression injection ({@code expression-injection.sinks}), which is
 * about an expression being evaluated. Here the tainted value becomes part of the XML being
 * written.
 */
public final class XmlInjectionChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: XML injection (CWE-91).
      This rule fires when a tainted string flows
      into an XML serializer (XmlSerializer's
      startTag / endTag / attribute / comment /
      entityRef / processingInstruction, or a
      SAX ContentHandler's startElement /
      endElement / processingInstruction).

      The risk is structural injection: the
      attacker chooses an element name or an
      attribute value and so controls the SHAPE
      of the document, not only its text.

      A finding is a TRUE POSITIVE (TP) when
      the tainted value becomes an element name,
      an attribute value, a comment, or a
      processing instruction.

      A finding is a FALSE POSITIVE (FP) when
      the value is a fixed constant, or when it
      is restricted to a known-safe character set
      before the call.

      A finding is UNKNOWN when you cannot tell
      from source + dataflow alone. Prefer
      UNKNOWN.
      """;

  public XmlInjectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-xml-injection",
            "dfa-taint-untrusted-source",
            "xml-injection.sinks",
            "CWE-91",
            "HIGH",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
