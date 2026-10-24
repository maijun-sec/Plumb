package org.plumb.dfa.checkers.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.TuningConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.fact.BuiltinFactKinds;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * R1115: the equality allowlist is the correct fix for identifier injection, and the rule used to
 * call it a vulnerability.
 *
 * <p>Bind variables cannot be used for identifiers, so {@code ORDER BY}, table and column names can
 * only be made safe by comparing them against a fixed set and substituting a default. The engine
 * modelled sanitizers by callee signature ({@code Integer.parseInt}, {@code URLEncoder.encode}); an
 * allowlist is not a call at all, it is a value being narrowed, so nothing matched it.
 *
 * <p>The tests pin the narrowing in BOTH directions, because the tempting wrong version -- "this
 * variable was compared to some literals, so it is safe" -- would suppress the genuinely vulnerable
 * shape where the guard has a fall-through carrying attacker data.
 */
class R1115AllowlistSanitizerTest {

  private static final String RULE = "dfa-taint-sql-injection";

  @TempDir Path tmp;

  private List<Finding> scan(String source, String name) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();

    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig cfg =
        new EngineConfig(
            base.sinkFamilies(),
            BuiltinFactKinds.registryWithBuiltins(),
            base.checkers(),
            base.cweMappings(),
            new TuningConfig(
                2,
                1000,
                1000,
                100,
                4,
                false,
                false,
                false,
                "full",
                "cwe_family",
                "sarif",
                false,
                0),
            base.receiverPassthroughPatterns());

    FindingReporter reporter =
        new DfaEngine(cfg, List.of(new SqlInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (RULE.equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  /** The real fix: both exits from the guard hold a listed constant. */
  @Test
  void anAllowlistedIdentifierIsNotReported() throws Exception {
    List<Finding> out =
        scan(
            """
            import java.sql.*;
            class T {
              String safeOrderBy(Connection conn, String requestedColumn) throws SQLException {
                Statement st = conn.createStatement();
                String allowed = requestedColumn;
                if (!"createdAt".equals(allowed) && !"userId".equals(allowed)) {
                  allowed = "createdAt";
                }
                return st.executeQuery("SELECT * FROM events ORDER BY " + allowed).toString();
              }
            }
            """,
            "AllowlistedIdentifier.java");
    assertThat(out).as("every path out of the guard holds one of two literals").isEmpty();
  }

  /**
   * KNOWN GAP, deliberately left failing rather than deleted: the reject-by-return idiom.
   *
   * <p>{@code if (!"id".equals(col) && !"name".equals(col)) return null;} is equally correct, and
   * unlike the substitute form it never reassigns anything. Proving it needs the fact "reaching
   * this instruction means col is one of two constants", which is a statement about the PATH, not
   * about any variable's value. The engine has no path-sensitive value lattice: {@link
   * org.plumb.dfa.taint.GuardTracker} models null-ness and numeric bounds, not string domains.
   *
   * <p>It is not fixed here because the substitute form is the one the measured false positive
   * actually used (category-05 {@code SQL-orderby-allowlist-tn}), and building a string-domain path
   * lattice to close a case I invented would be a much larger change than the defect warrants.
   *
   * <p>The assertion documents the CURRENT behaviour, so it turns red the day someone teaches the
   * engine this idiom -- which is the signal to implement it. Flipping it to {@code isEmpty()}
   * without that work would silently drop a real sanitizing pattern the rule should learn.
   */
  @Test
  void anEarlyReturnAllowlistIsReportedBecausePathSensitivityIsMissing() throws Exception {
    List<Finding> out =
        scan(
            """
            import java.sql.*;
            class T {
              String safeByReturn(Connection conn, String col) throws SQLException {
                Statement st = conn.createStatement();
                if (!"id".equals(col) && !"name".equals(col)) {
                  return null;
                }
                return st.executeQuery("SELECT * FROM t ORDER BY " + col).toString();
              }
            }
            """,
            "AllowlistEarlyReturn.java");
    assertThat(out)
        .as("see the javadoc: this idiom needs a path-sensitive string-domain lattice")
        .isNotEmpty();
  }

  /**
   * The control that keeps the narrowing honest.
   *
   * <p>A bare comparison does NOT constrain the fall-through: when {@code col} is not "id", control
   * leaves the if and reaches the sink with attacker input intact. Suppressing this would be the
   * wrong version of the rule.
   */
  @Test
  void aComparisonWithoutSubstitutionIsStillReported() throws Exception {
    List<Finding> out =
        scan(
            """
            import java.sql.*;
            class T {
              String unsafeByCompare(Connection conn, String col) throws SQLException {
                Statement st = conn.createStatement();
                if ("id".equals(col)) {
                  col = "id";
                }
                return st.executeQuery("SELECT * FROM t ORDER BY " + col).toString();
              }
            }
            """,
            "CompareOnly.java");
    assertThat(out).as("the `col != \"id\"` path still carries user input").isNotEmpty();
  }

  /** No allowlist at all: the plain concatenation must keep firing. */
  @Test
  void anUncheckedIdentifierIsStillReported() throws Exception {
    List<Finding> out =
        scan(
            """
            import java.sql.*;
            class T {
              String unsafe(Connection conn, String col) throws SQLException {
                Statement st = conn.createStatement();
                return st.executeQuery("SELECT * FROM t ORDER BY " + col).toString();
              }
            }
            """,
            "NoAllowlist.java");
    assertThat(out).isNotEmpty();
  }

  /**
   * R1118: a bare literal assignment is NOT an allowlist.
   *
   * <p>Added because a deeper mutation battery showed the rule could drop its "the variable was
   * actually compared against literals first" requirement with every test still green. That
   * relaxation is the dangerous one: there are ~1358 {@code = "literal"} assignments across the
   * corpus, so forgetting the comparison turns the tracker into a taint eraser.
   *
   * <p>The comparison is what makes the later substitution meaningful. Without it, any {@code x =
   * "safe"} anywhere would clear {@code x}, and the real taint on the fall-through would disappear
   * with it.
   */
  @Test
  void aLiteralAssignmentWithoutAComparisonIsStillReported() throws Exception {
    List<Finding> out =
        scan(
            """
            import java.sql.*;
            class T {
              String notAnAllowlist(Connection conn, String col) throws SQLException {
                Statement st = conn.createStatement();
                // A plain reassignment. Nothing constrains `col`; this is a bug, not a sanitizer.
                col = "id";
                return st.executeQuery("SELECT * FROM t ORDER BY " + col + col).toString();
              }
            }
            """,
            "LiteralWithoutCompare.java");
    assertThat(out)
        .as("no comparison, so the reassignment proves nothing and the input still flows")
        .isNotEmpty();
  }

  /**
   * R1118: a negated guard must not certify an unrelated variable.
   *
   * <p>The second surviving mutation allowed a negated comparison to mark <em>every</em> compared
   * variable as allowlisted, so one guard on {@code col} would silence an unrelated {@code other}
   * that still carries user input. Caught only because the test suite happened to have no shape
   * where two variables are involved.
   */
  @Test
  void aNegatedGuardDoesNotCertifyAnUnrelatedVariable() throws Exception {
    List<Finding> out =
        scan(
            """
            import java.sql.*;
            class T {
              String twoVars(Connection conn, String col, String other) throws SQLException {
                Statement st = conn.createStatement();
                if (!"id".equals(col) && !"name".equals(col)) {
                  col = "id";
                }
                // `other` was never constrained. The guard on `col` says nothing about it.
                return st.executeQuery("SELECT * FROM t WHERE a = '" + other + "' ORDER BY " + col)
                    .toString();
              }
            }
            """,
            "TwoVars.java");
    assertThat(out).as("`other` is still fully attacker-controlled").isNotEmpty();
  }

  /**
   * R1118: only an equality against a STRING LITERAL counts as a comparison.
   *
   * <p>A third surviving mutation accepted a comparison where neither side was a literal. {@code if
   * (!a.equals(b)) { x = "safe"; }} constrains nothing about either operand, so treating it as an
   * allowlist would be unsound.
   */
  @Test
  void aComparisonBetweenTwoVariablesIsNotAnAllowlist() throws Exception {
    List<Finding> out =
        scan(
            """
            import java.sql.*;
            class T {
              String varVsVar(Connection conn, String col, String other) throws SQLException {
                Statement st = conn.createStatement();
                if (!other.equals(col)) {
                  col = "id";
                }
                return st.executeQuery("SELECT * FROM t ORDER BY " + col + other).toString();
              }
            }
            """,
            "VarVsVar.java");
    assertThat(out)
        .as("`other.equals(col)` proves nothing about `col`; both stay tainted")
        .isNotEmpty();
  }

  /**
   * R1118: a variable wrongly marked allowlisted must not survive a later literal MOVE.
   *
   * <p>This is the shape that catches the last surviving mutation, in which a negated comparison
   * was allowed to certify <em>every</em> compared variable rather than just the one it tested.
   *
   * <p>Why the simpler {@code twoVars} case did not catch it: {@link
   * org.plumb.dfa.taint.EqualityAllowlistTracker#isAllowlisted} is consulted in exactly one place
   * -- the MOVE handler, for the MOVE's destination. A variable that is merely <em>marked</em> but
   * never reassigned is inert. So the bug only becomes observable when the wrongly-certified
   * variable later receives a literal assignment of its own, which is exactly what happens here.
   *
   * <p>Recorded because it is a latent trap rather than a live defect: suppression currently lives
   * at the MOVE, so the mutation changes no behaviour today. If suppression is ever moved to the
   * sink -- which is the more obvious place, and the first thing tried in R1115 -- the same
   * mutation silently starts hiding findings.
   */
  @Test
  void anOverCertifiedVariableDoesNotKeepItsTaintAfterALaterLiteralMove() throws Exception {
    List<Finding> out =
        scan(
            """
            import java.sql.*;
            class T {
              String overCertified(Connection conn, String col, String other) throws SQLException {
                Statement st = conn.createStatement();
                if (!"id".equals(col) && !"name".equals(col)) {
                  col = "id";
                }
                // `other` gets its own literal MOVE, but no guard ever constrained IT. If the
                // guard on `col` were allowed to certify every compared variable, this MOVE would
                // be treated as the proof and the second `other` below would be silenced.
                other = "safe";
                return st.executeQuery("SELECT * FROM t WHERE x = '" + other + "' ORDER BY " + other)
                    .toString();
              }
            }
            """,
            "OverCertified.java");
    assertThat(out)
        .as("`other = \"safe\"` is a plain assignment, not an allowlist substitution")
        .isNotEmpty();
  }
}
