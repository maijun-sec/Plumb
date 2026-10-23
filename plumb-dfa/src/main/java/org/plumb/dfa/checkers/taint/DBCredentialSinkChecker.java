/*
 * R7 ? Taint-style CWE-256 (Plaintext Storage of
 * Password) sink checker.
 *
 * <p>Fires when a tainted value (e.g. one read
 * from a .properties file via
 * {@code properties.getProperty("password")})
 * flows into the password slot of a DB
 * connection sink. Canonical Juliet CWE-256
 * pattern:</p>
 *
 * <pre>{@code
 *   Properties properties = new Properties();
 *   FileInputStream streamFileInput = new FileInputStream("...");
 *   properties.load(streamFileInput);
 *   String password = properties.getProperty("password");
 *   Connection c = DriverManager.getConnection(
 *       "jdbc:...", "sa", password);
 * }</pre>
 *
 * <p>Complement to
 * {@link org.plumb.dfa.checkers.HardCodedPasswordSinkChecker}
 * (which is a DFA-style sibling that
 * catches the literal-hard-coded variant
 * (password = "7e5tc4s3"). The taint-style
 * sibling here catches the file-sourced /
 * HTTP-sourced variant ? the testcases that the
 * DFA checker doesn't fire on.</p>
 *
 * <h3>Why a taint-style checker (not DFA)</h3>
 *
 * <p>The Juliet CWE-256 variants read the
 * password from {@code properties.getProperty()}
 * or {@code bufferedReader.readLine()}. The
 * taint flow goes through:
 *
 * <ol>
 *   <li>{@code FileInputStream.<init>(...)} ? file
 *       source</li>
 *   <li>{@code properties.load(streamFileInput)} —
 *       propagator</li>
 *   <li>{@code properties.getProperty("password")} —
 *       taint source (return value is tainted)</li>
 *   <li>{@code password = taint_source} ? local
 *       binding (TAC MOVE / ASSIGN)</li>
 *   <li>{@code DriverManager.getConnection(url, user, password)} —
 *       sink (password slot is the taint carrier)</li>
 * </ol>
 *
 * <p>A DFA pattern matcher would need to
 * symbolically track the FileInputStream,
 * Properties, and {@code load} propagator. The
 * taint framework already does this via the
 * standard {@code plumb.taint.untrusted-source}
 * source family + the cross-function summary
 * fold. Reusing it costs ~30 lines (this class)
 * vs. ~300 lines of DFA propagation logic.</p>
 *
 * <h3>Arg index offset (static method gotcha)</h3>
 *
 * <p>{@code DriverManager.getConnection} is
 * static, so the TAC CALL builder still prepends
 * a {@code "this"} placeholder at
 * {@code ins.args[0]}. The framework's universal
 * +1 offset in
 * {@link org.plumb.dfa.taint.TaintedCheckerBase#checkSinkForFamily}
 * is correct for this case (it converts the
 * Java arg index into the TAC arg index). We
 * override {@link #sinkArgumentIndex} to return
 * 2 (the Java arg index of the password slot)
 * and the framework maps it to {@code argIdx=3}
 * = {@code ins.args[3]} = password.</p>
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacInstruction;

public final class DBCredentialSinkChecker extends TaintedCheckerBase {

  /**
   * Custom AI prompt for CWE-256. The auto-generated default in TaintedCheckerBase would say
   * "Tainted value flows to db.credential.sinks (CWE-256)" ? true but not very actionable. The LLM
   * gets better verdicts with a focused rule that distinguishes TP (real tainted password reaching
   * a DB sink without sanitisation) from FP (the password is loaded from a vault / config that's
   * already trusted, or the sink is a JPA-managed credentials store).
   */
  private static final String PROMPT =
      """
      RULE: Plaintext password flowing into a DB credential sink (CWE-256).
      This rule fires when a tainted string flows into the password
      argument of a JDBC connection call (e.g.
      DriverManager.getConnection(url, user, password)).

      A finding is a TRUE POSITIVE (TP) when the password really
      does come from an untrusted source (file, network, HTTP
      request, env var, scanner) AND is passed to the connection
      sink without sanitisation. The risk is that the password
      ends up in a log file, error message, or exception trace.

      A finding is a FALSE POSITIVE (FP) when:
        * The password is loaded from a trusted secret store
          (HashiCorp Vault, AWS Secrets Manager, environment
          variable read once at startup and not re-read)
        * The "password" variable is actually an OAuth bearer
          token, API key, or other credential that has its own
          CWE family
        * The connection is being used in a test fixture
          (class named *Test* or under src/test/)
        * The taint is from a config file that's deployed as
          part of the application's own packaged resource
          (not user-controllable)

      A finding is UNKNOWN when you cannot determine from the
      source context + dataflow trace alone. Prefer UNKNOWN.
      """;

  public DBCredentialSinkChecker() {
    super(
        new TaintRule(
            "dfa-taint-plaintext-password-sink",
            "dfa-taint-untrusted-source",
            "db.credential.sinks",
            "CWE-256",
            "CRITICAL",
            TaintDomain.StringTaint.INSTANCE));
  }

  @Override
  protected int sinkArgumentIndex(SinkFamily family, TacInstruction call) {
    // DriverManager.getConnection(url, user, password)
    // is the canonical 3-arg form. The password
    // slot is Java arg index 2. The framework's
    // universal +1 offset maps this to
    // argIdx=3 = ins.args[3] = password.
    // (See class-level javadoc for the static-
    // method gotcha.)
    return 2;
  }

  @Override
  public String getAiPrompt(Finding finding) {
    return PROMPT;
  }
}
