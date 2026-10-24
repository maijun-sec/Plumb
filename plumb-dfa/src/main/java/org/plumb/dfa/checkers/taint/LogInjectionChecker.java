package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;

/**
 * CWE-117: improper output neutralization for logs (log injection / log forging).
 *
 * <h2>Why this checker exists</h2>
 *
 * <p>Same defect shape as the CWE-502 gap closed in R1099: the {@code log.sinks} family has been
 * listed in {@code sink-families.yaml} (CWE-117, with {@code java.util.logging.Logger}, {@code
 * org.slf4j.Logger} and friends) but <em>no checker consumed it</em>. A family that no rule reads
 * cannot produce a finding, so the coverage claim was empty. The same applied to {@code
 * pii.exfil.sinks} (CWE-359).
 *
 * <h2>Which argument carries the payload</h2>
 *
 * <p>Logger methods put the message at Java arg 0 and an optional {@code Throwable} at arg 1, so
 * the family default of {@code 0} is already right for {@code LOG.info(msg)} and
 * {@code LOG.info(String, Throwable)}. A call such as {@code LOG.log(Level, String)} shifts the
 * message to arg 1; those patterns declare {@code arg_index: 1} in the YAML.
 *
 * <h2>Why it matters beyond cosmetics</h2>
 *
 * <p>An unescaped newline or a {@code ${...}} sequence in a logged value lets an attacker forge
 * log entries (CWE-117) and, when the log pipeline feeds a template or a lookup, escalate into the
 * Log4Shell family (CWE-117 / CWE-20). The sanitizer that matters is CRLF / newline stripping plus
 * neutralising {@code ${}; the {@code replace} chain in {@code logSanitized} below is exactly that.
 */
public final class LogInjectionChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: Log injection / log forging (CWE-117).
      This rule fires when attacker-controlled data reaches a logging
      call (java.util.logging.Logger, org.slf4j.Logger, Log4j,
      java.lang.System.Logger, java.util.logging.Logger.log(Level, ...)).

      A finding is a TRUE POSITIVE (TP) when the logged value can
      come from outside the trust boundary and still contains
      attacker-chosen bytes -- typically an HTTP header, query
      parameter, form field, path segment, or request body -- and
      the message is emitted without neutralising CR/LF or the
      `${` lookup sequence.

      A finding is a FALSE POSITIVE (FP) when:
        * The logged value is a constant, a numeric id, an enum
          name, or a value from the program's own configuration
        * The value is sanitised first: CRLF / newline stripped,
          or `${` and `}` removed (the Log4Shell mitigation)
        * The value went through a typed parser that rejects
          control characters
        * The call passes a Throwable rather than a message built
          from user data, with no interpolated user content

      Distinguish log injection (CWE-117, the message is forged)
      from sensitive-information exposure (CWE-532, a secret is
      written to the log). A finding here is about the STRUCTURE
      of the message, not about what it contains.

      A finding is UNKNOWN when the origin of the logged value
      cannot be determined from the source context + dataflow
      trace alone. Prefer UNKNOWN over guessing.
      """;

  public LogInjectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-log-injection",
            "dfa-taint-untrusted-source",
            "log.sinks",
            "CWE-117",
            "MEDIUM",
            TaintDomain.MixedTaint.INSTANCE));
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
