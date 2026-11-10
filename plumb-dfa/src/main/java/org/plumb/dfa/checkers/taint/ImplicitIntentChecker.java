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
 * Implicit intent checker (CWE-927).
 *
 * Sink family: android-implicit-intent.sinks.
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

public final class ImplicitIntentChecker extends TaintedCheckerBase {
  private static final String PROMPT =
      """
      RULE: Implicit intent for sensitive communication
      (CWE-927).
      This rule fires when tainted data reaches an
      intent the app hands to the system without
      naming the component that should receive it:
      startActivity, startActivityForResult and the
      other activity entry points.

      The risk is that the system, not the app, picks
      the receiver. Any app on the device can claim the
      intent, so tainted data carried by it reaches an
      app the author did not choose.

      A finding is a TRUE POSITIVE (TP) when the
      tainted value is carried by an intent that is
      started without an explicit component, and the
      data in it is not merely a constant.

      A finding is a FALSE POSITIVE (FP) when the
      intent names its component explicitly, when the
      tainted value is the intent's own action or
      category rather than its data, or when the value
      is a constant the app owns.

      A finding is UNKNOWN when you cannot tell from
      source + dataflow alone.
      """;

  public ImplicitIntentChecker() {
    super(
        new TaintRule(
            "dfa-taint-implicit-intent",
            "dfa-taint-untrusted-source",
            "android-implicit-intent.sinks",
            "CWE-927",
            "HIGH",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
