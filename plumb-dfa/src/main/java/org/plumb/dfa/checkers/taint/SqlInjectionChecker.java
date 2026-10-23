/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  org.plumb.dfa.taint.TaintDomain
 *  org.plumb.dfa.taint.TaintDomain$StringTaint
 *  org.plumb.dfa.taint.TaintRule
 *  org.plumb.dfa.taint.TaintedCheckerBase
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

public final class SqlInjectionChecker extends TaintedCheckerBase {
  /**
   * R-? Step 7 ? the rule prompt the LLM sees when judging one of our findings. Describes the
   * SQL-injection rule + the common sanitizer patterns we recognise (PreparedStatement, setXxx,
   * allow-list).
   */
  private static final String PROMPT =
      """
      RULE: SQL injection (CWE-89).
      This rule fires when a tainted string flows into a
      SQL-statement sink (Statement.executeQuery,
      Statement.executeUpdate, Statement.execute,
      PreparedStatement.execute, or any JDBC call that
      sends SQL to the database).

      A finding is a TRUE POSITIVE (TP) when the data
      really does reach the sink without parameterisation.

      A finding is a FALSE POSITIVE (FP) when the source is
      already trusted (e.g. a constant, a config value
      loaded from a vault, a hard-coded lookup-key) OR
      when there is a sanitizer on the path:
        * PreparedStatement.setString / setObject / setXxx
          between the source and the sink (bind variable
          parameterisation)
        * A name/key built from an allow-list / enum
        * The query is a static string with the
          tainted value only used inside a parameter,
          not concatenated into the SQL text
        * The string passes through a documented
          escape routine (rare; only in legacy code)

      A finding is UNKNOWN when you cannot determine
      from the source context + dataflow trace alone.
      Prefer UNKNOWN over guessing.
      """;

  public SqlInjectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-sql-injection",
            "dfa-taint-untrusted-source",
            "sql.sinks",
            "CWE-89",
            "CRITICAL",
            (TaintDomain) TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
