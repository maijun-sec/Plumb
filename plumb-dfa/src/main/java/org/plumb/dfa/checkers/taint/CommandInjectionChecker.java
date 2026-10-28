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

public final class CommandInjectionChecker extends TaintedCheckerBase {
  private static final String PROMPT =
      """
      RULE: OS command injection (CWE-78).
      This rule fires when a tainted string flows into a
      command-execution sink (Runtime.exec, ProcessBuilder
      .command / .command(List), new ProcessBuilder([...])
      with concatenated args, java.lang.ProcessImpl).

      A finding is a TRUE POSITIVE (TP) when the data
      really does reach the sink and is used to build
      the command line (concatenation, format, builder
      .command(arg)).

      A finding is a FALSE POSITIVE (FP) when:
        * The source is already trusted (constant,
          config value, hard-coded lookup-key)
        * The args are tokenized through a safe
          allow-list (only known commands + flags)
        * The call site uses ProcessBuilder.command(List)
          with each element validated against an
          allow-list (so shell metacharacters can't
          be injected)
        * The call is wrapped in a documented
          command-validation helper (rare)

      A finding is UNKNOWN when you cannot determine
      from the source context + dataflow trace alone.
      Prefer UNKNOWN over guessing.
      """;

  public CommandInjectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-command-injection",
            "dfa-taint-untrusted-source",
            "command.sinks",
            "CWE-78",
            "CRITICAL",
            (TaintDomain) TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
