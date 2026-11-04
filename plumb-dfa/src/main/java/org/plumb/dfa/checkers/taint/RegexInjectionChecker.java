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
 * Regular expression injection (CWE-1333).
 *
 * <p>Tainted data flowing into a compiled regular expression. The concern is ReDoS: a pattern the
 * attacker chose can make the matching exponential, and a single request can then pin a CPU.
 *
 * <h2>Why this rule watches ONE family</h2>
 *
 * <p>The defect class is the same in every JVM language, but the SYMBOL is not:
 *
 * <ul>
 *   <li>{@code java.util.regex.Pattern#compile} -- reachable from every JVM language, because they
 *       all compile down to the same Java class. Shared.
 *   <li>{@code kotlin.text.Regex} -- Kotlin-only.
 *   <li>{@code scala.util.matching.Regex} -- Scala-only.
 * </ul>
 *
 * <p>So all three are entries in ONE family, {@code regex.sinks}: the shared symbols are the
 * family's shared patterns, and the language-specific ones are its per-language table. The rule
 * names the family once, and the catalog says where each of its sinks lives.
 *
 * <p>This is the point of the language dimension: the rule is written once, and the finding carries
 * the same CWE in every language. A Java call site never sees the Kotlin symbol, and a Kotlin call
 * site never sees the Scala one -- but all of them are covered by one rule.
 */
public final class RegexInjectionChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: Regular expression injection (CWE-1333).
      This rule fires when a tainted string
      flows into the pattern position of a
      regular-expression compile (Pattern.compile,
      Pattern.matches, kotlin.text.Regex, or
      scala.util.matching.Regex).

      The risk is ReDoS: an attacker-chosen
      pattern can make the matching exponential,
      so a single request pins a CPU core. The
      secondary risk is a bypass, where the
      attacker supplies a pattern that matches
      something the developer did not intend.

      A finding is a TRUE POSITIVE (TP) when
      the tainted value becomes the regex pattern
      itself.

      A finding is a FALSE POSITIVE (FP) when
      the tainted value is a FLAG, a replacement
      string, or an input to the matcher rather
      than the pattern, or when the pattern is
      built entirely from constants.

      A finding is UNKNOWN when you cannot tell
      from source + dataflow alone.
      """;

  public RegexInjectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-regex-injection",
            "dfa-taint-untrusted-source",
            // The one family of this defect class. Its shared patterns are
            // the Java-library symbols; its per-language table holds the
            // Kotlin and Scala ones.
            "regex.sinks",
            "CWE-1333",
            "MEDIUM",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
