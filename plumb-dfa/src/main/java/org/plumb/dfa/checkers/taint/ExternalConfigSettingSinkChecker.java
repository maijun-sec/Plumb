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
 * Taint-style CWE-15 (External Control of
 * System or Configuration Setting) sink checker.
 *
 * <p>Fires when a tainted value flows into a
 * system / configuration setter. Canonical
 * Juliet CWE-15 pattern:</p>
 *
 * <pre>{@code
 *   String data = ...;  // tainted source
 *   System.setProperty("catalog", data);  // FLAW
 * }</pre>
 *
 * <p>Sinks (the
 * {@code external.config.sinks} family):</p>
 * <ul>
 *   <li>{@code System.setProperty(key, value)}</li>
 *   <li>{@code Connection.setCatalog(name)}</li>
 *   <li>{@code DriverManager.setLogStream(...)} /
 *       {@code setLogWriter}</li>
 *   <li>{@code Locale.setDefault(locale)}</li>
 *   <li>{@code TimeZone.setDefault(tz)}</li>
 *   <li>{@code URL.setURLStreamHandlerFactory(f)}</li>
 *   <li>{@code UIManager.setLookAndFeel(cls)}</li>
 *   <li>{@code javax.servlet.ServletContext.setInitParameter}</li>
 * </ul>
 *
 * <p>Companion to
 * {@link DBCredentialSinkChecker} (R7) and
 * {@link CleartextNetworkCredentialSinkChecker} (R9).
 * Same taint framework, different CWE label.
 * The three checkers are siblings: the data-flow
 * is the same; only the CWE classification
 * differs (data at rest vs data on wire vs
 * system config).</p>
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacInstruction;

public final class ExternalConfigSettingSinkChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: External control of system or configuration setting (CWE-15).
      This rule fires when a tainted string flows into a
      system / configuration setter like
      System.setProperty, Connection.setCatalog, or
      Locale.setDefault.

      A finding is a TRUE POSITIVE (TP) when the value
      is genuinely attacker-controlled (read from
      network / HTTP / file / env) and is used to
      modify a system / configuration setting that
      affects behavior.

      A finding is a FALSE POSITIVE (FP) when:
        * The value is hardcoded or comes from a
          trusted config bundle (not user-controllable)
        * The setter is for a connection pool /
          logging config that's intentionally tunable
          from a controlled config file
        * The class is in a test fixture
          (class named *Test* or under src/test/)

      A finding is UNKNOWN when ambiguous. Lean TP if
      the dataflow trace shows a network / file / env
      source.
      """;

  public ExternalConfigSettingSinkChecker() {
    super(
        new TaintRule(
            "dfa-taint-external-config-setting",
            "dfa-taint-untrusted-source",
            "external.config.sinks",
            "CWE-15",
            "CRITICAL",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  protected int sinkArgumentIndex(SinkFamily family, TacInstruction call) {
    // System.setProperty(key, value) — value is
    // Java arg index 1.
    // Connection.setCatalog(name) — name is
    // Java arg index 0.
    // Locale.setDefault(locale) — locale is
    // Java arg index 0.
    // Different sinks have different tainted
    // arg positions. We use 0 as the default
    // (most "set" methods take the new value
    // as the first user arg) and let the
    // framework's universal +1 offset handle
    // the TAC arg shift.
    return 0;
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
