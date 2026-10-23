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
