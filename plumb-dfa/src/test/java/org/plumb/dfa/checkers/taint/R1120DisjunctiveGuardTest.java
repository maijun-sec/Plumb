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
 * R1120: a DISJUNCTIVE guard is not an allowlist.
 *
 * <p>Found by the second independent audit, against R1115's own rule.
 *
 * <p>The tracker recorded a variable as allowlisted from any UNARYOP negation of an equality
 * result, without checking that the negation was the whole branch condition. These two shapes are
 * byte-identical to the tracker:
 *
 * <pre>
 *   sound:    t2 = NOT t1 ; if t2 goto L_THEN
 *   unsound:  t2 = NOT t1 ; t5 = t2 LOR t4 ; if t5 goto L_THEN
 * </pre>
 *
 * <p>In the second, {@code other == "users"} makes the {@code OR} true no matter what {@code col}
 * is, so the {@code col = "createdAt"} substitution <em>never runs</em> and {@code col} reaches the
 * sink fully attacker-controlled. The rule suppressed the finding anyway.
 *
 * <p>The {@code &&} form is sound by accident -- all conjuncts must fail before the body runs --
 * which is why every pre-existing allowlist test passed and none used {@code ||}.
 */
class R1120DisjunctiveGuardTest {

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

  /**
   * The shape the audit confirmed: {@code col} is substituted inside a guard one disjunct of which
   * does not mention it, so the substitution is not guaranteed to run.
   */
  @Test
  void aDisjunctThatDoesNotMentionTheVariableDoesNotProveItSafe() throws Exception {
    List<Finding> out =
        scan(
            """
            import java.util.*;
            class T {
              String bad(Connection conn, String col, String other) throws SQLException {
                Statement st = conn.createStatement();
                Map<String, String> m = new HashMap<>();
                m.get("k");
                if (!"createdAt".equals(col) || !"users".equals(other)) {
                  col = "createdAt";
                }
                // `other == "users"` makes the OR true whatever `col` is, so the substitution never
                // runs and `col` is still attacker-controlled here.
                return st.executeQuery("SELECT * FROM t ORDER BY " + col).toString();
              }
            }
            """,
            "Disjunct.java");
    assertThat(out)
        .as("a disjunct that does not mention `col` means the body may never execute")
        .isNotEmpty();
  }

  /** Control: the conjunction form IS a real allowlist and must stay silent. */
  @Test
  void theConjunctiveFormStillStaysSilent() throws Exception {
    List<Finding> out =
        scan(
            """
            import java.sql.*;
            class T {
              String ok(Connection conn, String col) throws SQLException {
                Statement st = conn.createStatement();
                if (!"id".equals(col) && !"name".equals(col)) {
                  col = "id";
                }
                return st.executeQuery("SELECT * FROM t ORDER BY " + col).toString();
              }
            }
            """,
            "Conjunct.java");
    assertThat(out).as("all conjuncts must fail, so the substitution is guaranteed").isEmpty();
  }
}
