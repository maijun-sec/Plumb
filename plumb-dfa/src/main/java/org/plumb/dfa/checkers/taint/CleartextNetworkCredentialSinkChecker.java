/*
 * Taint-style CWE-319 (Cleartext
 * Transmission of Sensitive Information) sink
 * checker.
 *
 * <p>Companion to {@link DBCredentialSinkChecker}
 * (CWE-256). Same taint framework, same
 * {@code db.credential.sinks} family, same
 * {@code sinkArgumentIndex(2)} ? but the CWE label
 * is CWE-319 (cleartext channel) instead of
 * CWE-256 (plaintext storage). The two checkers
 * run side-by-side and tag their findings with
 * different CWEs so the Juliet test
 * infrastructure can count CWE-256 hits
 * separately from CWE-319 hits.</p>
 *
 * <p>Why two checkers? The CWE-256 vs CWE-319
 * distinction is about the <em>channel</em>:
 * CWE-256 is "stored in plaintext at rest",
 * CWE-319 is "transmitted in plaintext over the
 * wire". A password read from a Properties file
 * is CWE-256; a password read from a Socket is
 * CWE-319. The taint framework doesn't
 * distinguish, so we run both checkers and let
 * the AI dedup pass judge which is more
 * accurate.</p>
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacInstruction;

public final class CleartextNetworkCredentialSinkChecker extends TaintedCheckerBase {

  private static final String PROMPT =
      """
      RULE: Cleartext network password flowing into a DB credential sink (CWE-319).
      This rule fires when a tainted string (typically read via
      a cleartext network channel: Socket, unencrypted HTTP,
      FTP, SMTP) flows into the password argument of a JDBC
      connection call.

      A finding is a TRUE POSITIVE (TP) when the password
      really does come from a cleartext network channel AND
      is passed to the connection sink without encryption.
      The risk is that the password is observable by
      anyone with network access between the source and
      the destination.

      A finding is a FALSE POSITIVE (FP) when:
        * The channel is encrypted (TLS / SSL / SSH)
        * The password is loaded from a vault / secret store
        * The connection is in a test fixture (class named
          *Test* or under src/test/)
        * The taint is from a local file (which is CWE-256
          territory, not CWE-319)

      A finding is UNKNOWN when you cannot determine from
      the source context + dataflow trace alone. Prefer
      UNKNOWN. Lean TP if the dataflow trace shows a Socket
      or unencrypted URL read on the source side.
      """;

  public CleartextNetworkCredentialSinkChecker() {
    super(
        new TaintRule(
            "dfa-taint-cleartext-network-credential-sink",
            "dfa-taint-untrusted-source",
            "db.credential.sinks",
            "CWE-319",
            "CRITICAL",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  protected int sinkArgumentIndex(SinkFamily family, TacInstruction call) {
    // Same as DBCredentialSinkChecker: password is
    // Java arg index 2; framework's universal +1
    // offset maps to argIdx=3 = ins.args[3].
    return 2;
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
