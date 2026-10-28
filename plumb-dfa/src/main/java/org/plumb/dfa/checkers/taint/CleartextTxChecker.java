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
 * C2: Taint-style CWE-319 (Cleartext
 * Transmission of Sensitive Information) sink
 * checker.
 *
 * <p>Fires when a tainted value flows into a
 * network-channel write: {@link
 * java.io.PrintWriter#print},
 * {@link java.io.PrintWriter#println},
 * {@link java.io.OutputStream#write},
 * {@link java.net.Socket#getOutputStream},
 * etc. (See {@code sink-families.yaml}
 * {@code cleartext-tx.sinks} for the full
 * pattern list.)</p>
 *
 * <p>Companion to
 * {@link CleartextNetworkCredentialSinkChecker}.
 * The two checkers run side-by-side and tag
 * their findings with CWE-319. This general
 * sink-family variant fires on ANY tainted data
 * going into a write sink, not just credentials;
 * the AI prompt is the filter for false
 * positives where the data is non-sensitive or
 * the channel is non-network (e.g.
 * {@code System.out}).</p>
 *
 * <h3>Why a separate checker (not extension of CleartextNetworkCredentialSinkChecker)</h3>
 *
 * <p>The credential-focused sibling
 * ({@link CleartextNetworkCredentialSinkChecker})
 * is tagged CWE-319 too, but only fires on the
 * {@code db.credential.sinks} family -- i.e. the
 * JDBC / Kerberos / password sink patterns where
 * the password is consumed by a credential
 * subsystem. The general send-to-channel pattern
 * (writer.println(data) over a Socket) belongs to
 * a different family because the taint propagator
 * from sysenv / file / network to a write sink is
 * the canonical CWE-319 vector even when the data
 * is not a password (e.g. SSN, credit card, API
 * key, session token). Putting both into the
 * same family would conflate "sink is a
 * credential subsystem" with "sink is a
 * network-channel write" and break the AI's
 * ability to discriminate.</p>
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

public final class CleartextTxChecker extends TaintedCheckerBase {

  /**
   * Custom AI prompt for the general CWE-319 send-sink family. The auto-generated default in {@link
   * TaintedCheckerBase} says "Tainted value flows to cleartext-tx.sinks (CWE-319)" -- true but not
   * very actionable. The LLM gets better verdicts with a focused rule that distinguishes TP (real
   * tainted sensitive data reaching a network channel) from FP (the PrintWriter is over a local log
   * file or System.out, not a network channel; or the tainted data is non-sensitive like a public
   * hostname).
   */
  private static final String PROMPT =
      """
      RULE: Cleartext transmission of sensitive information (CWE-319).
      This rule fires when a tainted value flows into a network-channel
      write sink: PrintWriter.print/println/write, OutputStream.write,
      Writer.write, or a Socket's getOutputStream. The static half
      is intentionally lenient; the AI is the primary filter.

      A finding is a TRUE POSITIVE (TP) when the written data is
      sensitive AND the channel is unencrypted:
        * `writer.println(password)` where `writer` wraps a
          `socket.getOutputStream()` (sends password unencrypted
          over TCP)
        * `os.write(ssnBytes)` where `os` is from
          `socket.getOutputStream()` (sends PII unencrypted)
        * `writer.println(apiKey)` where `writer` is over a
          `URLConnection.getOutputStream()` (sends API key
          unencrypted over HTTP, not HTTPS)
        * The trace shows taint originating from a network /
          file / env source AND the write destination is a
          Socket / URLConnection / SocketChannel
          (not a local file / logger / System.out)

      A finding is a FALSE POSITIVE (FP) when:
        * The PrintWriter / OutputStream writes to a local
          file or logger (NOT a network channel)
        * The data is encrypted before the write
          (Cipher.wrap / TLS / SSH wraps the stream)
        * The data is non-sensitive (a public URL, a
          constant string, a method's own return value)
        * The function is in a test fixture (class named
          *Test* or under src/test/)
        * The class is generated (target/generated-sources/,
          class name contains $$)

      A finding is UNKNOWN when ambiguous. Lean TP if the
      trace shows the sink call writes to a Socket /
      URLConnection / SocketChannel output stream AND the
      tainted source contains 'password' / 'ssn' / 'secret'
      / 'token' / 'credential' in the variable name.
      """;

  public CleartextTxChecker() {
    super(
        new TaintRule(
            "dfa-taint-cleartext-tx",
            "dfa-taint-untrusted-source",
            "cleartext-tx.sinks",
            "CWE-319",
            "HIGH",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  protected int sinkArgumentIndex(SinkFamily family, TacInstruction call) {
    // PrintWriter.print(s) / println(s) / write(s):
    // the value being written is the first user
    // arg (Java index 0). OutputStream.write(b):
    // same. Socket.getOutputStream(): the
    // returned OutputStream is the only arg
    // (Java index 0); the framework treats the
    // returned stream as tainted only when the
    // CALL has a dest var AND the source rule
    // for this family is the cross-fn fold (not
    // relevant here).
    //
    // The +1 universal offset in
    // TaintedCheckerBase.checkSinkForFamily
    // maps Java index 0 to argIdx=1 =
    // ins.args[1] (skipping args[0] which is
    // the receiver for instance methods like
    // PrintWriter.println, OutputStream.write).
    return 0;
  }

  @Override
  public void checkInstruction(
      TacInstruction ins, TacFunction fn, org.plumb.dfa.engine.AnalysisContext ctx) {
    // Run the base class first so the standard
    // MOVE / BINOP / CALL taint propagation +
    // sink-check paths fire normally.
    super.checkInstruction(ins, fn, ctx);
    // C2: extend the source set with the
    // canonical credential-construction calls
    // that the CWE-319 Juliet "send_*" pattern
    // relies on. Without these, hardcoded
    // password → PrintWriter.println over a
    // Socket never sees taint because the data
    // source is not in InputSourcePatterns (it
    // is a literal PasswordAuthentication
    // constructor). The AI prompt filters the
    // resulting findings to distinguish TP
    // (real credential over a cleartext channel)
    // from FP (the variable is non-sensitive or
    // the channel is local).
    if (ins.kind == TacInstruction.Kind.CALL && ins.dest instanceof TacOperand.TacVar dest) {
      String callee = ins.calleeSig;
      if (callee != null) {
        // java.net.PasswordAuthentication.getPassword() returns
        // the char[] of the password the
        // object was constructed with.
        // The dest var (e.g. credentials.getPassword())
        // is the password itself.
        if (callee.contains("PasswordAuthentication.getPassword")) {
          markSource(
              dest,
              "input:PasswordAuthentication.getPassword",
              ins,
              fn.signature(),
              "credential source: PasswordAuthentication.getPassword");
        }
        // javax.security.auth.kerberos.KerberosKey has no
        // getPassword API (the password is
        // consumed by the constructor), but
        // there are similar credential accessors
        // on related classes. Pattern-match on
        // the substring to allow future
        // variants without touching the engine.
        else if (callee.contains("KerberosKey")
            && (callee.contains(".getEncoded") || callee.contains(".getPrincipal"))) {
          markSource(
              dest,
              "input:KerberosKey.getEncoded",
              ins,
              fn.signature(),
              "credential source: KerberosKey access");
        }
      }
    }
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
