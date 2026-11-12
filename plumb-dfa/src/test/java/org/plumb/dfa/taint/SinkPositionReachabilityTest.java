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
package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.DBCredentialSinkChecker;
import org.plumb.dfa.checkers.taint.LdapInjectionChecker;
import org.plumb.dfa.checkers.taint.SensitiveDataLeakChecker;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The catalogue names the argument position that carries the tainted value.
 *
 * <p><b>The defect class.</b> A position that no call of that API can supply is a SILENT failure.
 * The engine resolves the position against the call's own argument list and skips the check when
 * the position is past its end, so the rule never fires, the family looks alive in the catalogue,
 * and the finding simply does not appear. Three catalogue entries were in that state:
 *
 * <ul>
 *   <li>{@code ldap.sinks} claimed {@code InitialContext#lookup} at the family default (1), because
 *       the default was chosen for the three-argument {@code search(base, filter, controls)} shape.
 *       {@code lookup} takes ONE argument, so the position was past its end.
 *   <li>{@code db.credential.sinks} claimed {@code DataSource#getConnection} at the family default
 *       (2). The overload takes (user, password), so the password is at 1 -- and the sibling family
 *       {@code credential.sinks} already declared 1 for the same API.
 *   <li>{@code sensitive.exfil.sinks} and {@code pii.exfil.sinks} claimed {@code
 *       java.util.logging.Logger#debug} and {@code Logger#error}. The JDK's {@code
 *       java.util.logging.Logger} has NEITHER method: its levels are {@code severe}, {@code
 *       warning}, {@code info}, {@code config} and {@code fine}/{@code finer}/{@code finest}. The
 *       two entries could never match, so a secret logged at FINE level was not reported at all.
 * </ul>
 *
 * <p><b>What is deliberately NOT in this file.</b> {@code PreparedStatement#setString} was a
 * candidate for the same treatment, and it is absent on purpose. Binding a parameter cannot change
 * the statement, so a tainted bound value is not a SQL injection -- {@code DatabaseAccessSplitTest}
 * asserts that split, and the {@code sql.sinks} family must not claim the value slot of a setter.
 * The {@code database-access.sinks} family is the one that watches a tainted value reaching a
 * database API.
 *
 * <p>Each case is paired with the clean shape, so the fix cannot be a blanket "report every call of
 * this API".
 */
public final class SinkPositionReachabilityTest {

  @TempDir Path tmp;

  private static final String HEADER =
      """
      import java.util.logging.Logger;
      import javax.naming.InitialContext;
      import javax.sql.DataSource;

      public class Fixture {
      %s
      }
      """;

  /** A tainted value read from the environment reaches the catalogued slot. */
  private static final String TAINTED = "String tainted = System.getenv(\"PATH\");";

  /** The same call shape with a constant: nothing to report. */
  private static final String CLEAN = "String clean = \"constant\";";

  private List<Finding> scan(String body, String name) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, HEADER.formatted(body));
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    List<DfaChecker> checkers =
        List.of(
            new SqlInjectionChecker(),
            new LdapInjectionChecker(),
            new DBCredentialSinkChecker(),
            new SensitiveDataLeakChecker());
    EngineConfig base = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(base, checkers, "global", null).runWithGlobalStore(List.of(program), 0);
    return List.copyOf(reporter.all());
  }

  private List<Finding> hits(List<Finding> findings, String ruleId) {
    return findings.stream().filter(f -> ruleId.equals(f.ruleId())).toList();
  }

  private void assertReported(String ruleId, String body, String name) throws Exception {
    List<Finding> found = hits(scan(body, name), ruleId);
    assertThat(found)
        .as("a tainted value at the catalogued position must be reported by " + ruleId)
        .hasSize(1);
    assertThat(found.get(0).line()).isPositive();
  }

  private void assertNotReported(String ruleId, String body, String name) throws Exception {
    List<Finding> found = hits(scan(body, name), ruleId);
    assertThat(found)
        .as("a constant at the catalogued position must not be reported by " + ruleId)
        .isEmpty();
  }

  @Test
  void aTaintedNameInTheJndiLookupIsReported() throws Exception {
    assertReported(
        "dfa-taint-ldap-injection",
        """
          void run(InitialContext ctx) throws Exception {
            %s
            ctx.lookup(tainted);
          }
        """
            .formatted(TAINTED),
        "Ldap.java");
  }

  @Test
  void aTaintedPasswordInTheDataSourceSlotIsReported() throws Exception {
    assertReported(
        "dfa-taint-plaintext-password-sink",
        """
          void run(DataSource ds) throws Exception {
            %s
            ds.getConnection("user", tainted);
          }
        """
            .formatted(TAINTED),
        "Db.java");
  }

  @Test
  void aTaintedMessageAtTheJdkLoggerDebugLevelIsReported() throws Exception {
    assertReported(
        "dfa-taint-data-leak",
        """
          void run(Logger log) throws Exception {
            %s
            log.fine(tainted);
          }
        """
            .formatted(TAINTED),
        "Log.java");
  }

  @Test
  void aConstantInTheJndiLookupIsNotReported() throws Exception {
    assertNotReported(
        "dfa-taint-ldap-injection",
        """
          void run(InitialContext ctx) throws Exception {
            %s
            ctx.lookup(clean);
          }
        """
            .formatted(CLEAN),
        "LdapClean.java");
  }

  @Test
  void aConstantInTheDataSourceSlotIsNotReported() throws Exception {
    assertNotReported(
        "dfa-taint-plaintext-password-sink",
        """
          void run(DataSource ds) throws Exception {
            %s
            ds.getConnection("user", clean);
          }
        """
            .formatted(CLEAN),
        "DbClean.java");
  }

  @Test
  void aConstantAtTheJdkLoggerDebugLevelIsNotReported() throws Exception {
    assertNotReported(
        "dfa-taint-data-leak",
        """
          void run(Logger log) throws Exception {
            %s
            log.fine(clean);
          }
        """
            .formatted(CLEAN),
        "LogClean.java");
  }

  @Test
  void theCataloguedPositionIsOneTheCallCanSupply() {
    EngineConfig base = ConfigLoader.loadFromClasspath();

    // lookup(String name): one argument, so the position has to be 0. The
    // family default is 1, which is the filter position of the three-argument
    // search(base, filter, controls) shape.
    assertThat(positions(base, "ldap.sinks", "javax.naming.InitialContext.lookup/1"))
        .as("the tainted name of lookup is its only argument")
        .containsExactly(0);

    // getConnection(String user, String password): the password is the second
    // argument.
    assertThat(positions(base, "db.credential.sinks", "javax.sql.DataSource.getConnection/2"))
        .as("the tainted password of getConnection is the second argument")
        .containsExactly(1);

    // Logger#fine(String): one argument, the message itself.
    assertThat(positions(base, "sensitive.exfil.sinks", "java.util.logging.Logger.fine/1"))
        .as("the tainted message of Logger#fine is its only argument")
        .containsExactly(0);
  }

  private int[] positions(EngineConfig base, String familyName, String calleeSig) {
    SinkFamily family =
        base.sinkFamilies().byName(familyName).orElseThrow(() -> new AssertionError(familyName));
    return family.argIndexesFor(calleeSig);
  }
}
