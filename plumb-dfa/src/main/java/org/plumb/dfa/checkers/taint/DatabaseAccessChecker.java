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
 * Database access control (CWE-566): tainted data reaches a database.
 *
 * <p><b>WHY THIS IS NOT SQL INJECTION.</b> It is the same shape of taint flow as {@code
 * SqlInjectionChecker} (CWE-89) and the same APIs, but it is a DIFFERENT defect, and the difference
 * is the whole point of having both.
 *
 * <p>CWE-89 is about the value CHANGING the statement. CWE-566 is about the value REACHING the
 * database at all -- including as a BOUND PARAMETER, where it cannot change the statement but still
 * crosses a trust boundary.
 *
 * <p>Two independently built rule sets say so:
 *
 * <ul>
 *   <li>One ships two rule families over the same APIs. The rules for the SQL TEXT carry CWE-89 and
 *       name the SQL TEXT as the tainted argument; the rules for the BOUND PARAMETER carry CWE-566
 *       and name the varargs of {@code JdbcTemplate.queryForList(String, Object...)}.
 *   <li>The other models the same method with two distinct roles: one for the statement text and
 *       one for the bound parameter. In Spring's {@code NamedParameterJdbcOperations} the bound
 *       parameter carries the bound-parameter role and NEVER the statement-text role.
 * </ul>
 *
 * <p>So the two defect classes are kept apart. Folding them together would report a bound parameter
 * as a SQL injection -- a false positive on code that is exactly right, since a bound parameter
 * cannot change the statement.
 *
 * <p><b>THE PRACTICAL DIFFERENCE FOR THE READER.</b> A CWE-89 finding means "an attacker controls
 * the query". A CWE-566 finding means "an attacker's data is being written to a database, and
 * nobody decided that it should be". The second is a real concern -- it is how stored XSS and
 * data-exfiltration happen -- but it is a different conversation, and it has a different severity.
 */
public final class DatabaseAccessChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: database access control (CWE-566).
      This rule fires when tainted data reaches a
      database -- as the SQL text, or as a bound
      parameter.

      The concern is a TRUST BOUNDARY, not query
      syntax: attacker-controlled data is being
      written to a database without a decision that
      it should be. That is how stored XSS and
      data exfiltration happen.

      It is NOT the same as SQL injection (CWE-89),
      which is reported separately. A BOUND
      PARAMETER is safe from injection -- it cannot
      change the statement -- but it is still
      attacker data reaching the database, which is
      what this rule reports.

      A finding is a TRUE POSITIVE (TP) when
      untrusted data reaches the database without
      being validated for storage.

      A finding is a FALSE POSITIVE (FP) when
      the value is a fixed constant, or when it is
      validated or sanitised for storage before
      the call.

      A finding is UNKNOWN when you cannot tell
      from source + dataflow alone. Prefer
      UNKNOWN.
      """;

  public DatabaseAccessChecker() {
    super(
        new TaintRule(
            "dfa-taint-database-access",
            "dfa-taint-untrusted-source",
            "database-access.sinks",
            "CWE-566",
            "MEDIUM",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
