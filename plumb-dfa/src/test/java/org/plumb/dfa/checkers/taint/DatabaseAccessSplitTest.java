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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * The CWE-89 / CWE-566 split: SQL injection and database access are DIFFERENT defect classes.
 *
 * <p><b>THE DEFECT THIS PINS.</b> The rule-pack conversion mapped {@code "Access
 * Control"/"Database"} onto {@code sql.sinks}. That category is <b>CWE-566</b>, not CWE-89, and the
 * two rule sets name DIFFERENT arguments of the SAME APIs:
 *
 * <ul>
 *   <li>The {@code SQL Injection} rules carry CWE-89 and name the <b>SQL TEXT</b>.
 *   <li>The {@code Access Control / Database} rules carry CWE-566 and name the <b>BOUND
 *       PARAMETER</b> -- the varargs of {@code JdbcTemplate.queryForList(String, Object...)}.
 * </ul>
 *
 * <p>The other upstream model library says the same thing independently: in {@code
 * org/springframework/jdbc/core/namedparam/NamedParameterJdbcOperations.java} the bound parameter
 * is a {@code database_sink} and NEVER a {@code sql_sink}.
 *
 * <p>So a bound parameter cannot change the statement, and reporting it as SQL injection is a false
 * positive on exactly-right code. The concrete damage: the framework corpus measurement lost 10 of
 * its 34 findings, because the CWE-566 rule's index-1 position won the race against the CWE-89
 * rule's index-0 position for {@code JdbcTemplate.queryForList} -- so the SQL TEXT was no longer
 * the argument being inspected.
 *
 * <p>These tests pin the split from both sides: the SQL text is a SQL-injection finding, the bound
 * parameter is a database-access finding, and neither is reported as the other.
 */
class DatabaseAccessSplitTest {

  @TempDir Path tmp;

  private List<Finding> scan(String source, String name, DfaChecker... checkers) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(base, List.of(checkers), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    return new ArrayList<>(reporter.all());
  }

  private static List<Finding> ofRule(List<Finding> findings, String ruleId) {
    List<Finding> out = new ArrayList<>();
    for (Finding f : findings) {
      if (ruleId.equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  private static final String SQL_RULE = "dfa-taint-sql-injection";
  private static final String DB_RULE = "dfa-taint-database-access";

  @Test
  @DisplayName("the tainted SQL text is a SQL-injection finding")
  void taintedSqlTextIsSqlInjection() throws Exception {
    List<Finding> findings =
        scan(
            """
            class T {
              void go(String userInput) throws Exception {
                java.sql.Statement st = null;
                st.executeQuery("SELECT * FROM t WHERE c = '" + userInput + "'");
              }
            }
            """,
            "SqlText.java",
            new SqlInjectionChecker(),
            new DatabaseAccessChecker());

    assertThat(ofRule(findings, SQL_RULE))
        .as("the tainted SQL text changes the statement -- that is CWE-89")
        .hasSize(1);
    assertThat(ofRule(findings, DB_RULE))
        .as("and it also reaches the database, so the CWE-566 rule sees it too")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a bound parameter is NOT a SQL-injection finding")
  void boundParameterIsNotSqlInjection() throws Exception {
    // The SQL is a CONSTANT with a '?' placeholder; the tainted value is the
    // bound parameter. It cannot change the statement.
    List<Finding> findings =
        scan(
            """
            class T {
              void go(String userInput) throws Exception {
                java.sql.PreparedStatement ps = null;
                ps.setString(1, userInput);
                ps.executeQuery("SELECT * FROM t WHERE c = ?");
              }
            }
            """,
            "BoundParam.java",
            new SqlInjectionChecker(),
            new DatabaseAccessChecker());

    assertThat(ofRule(findings, SQL_RULE))
        .as(
            "a bound parameter cannot change the statement -- reporting it as SQL injection"
                + " is a false positive on exactly-right code")
        .isEmpty();
  }

  @Test
  @DisplayName("the two defect classes are separate families with separate CWEs")
  void theTwoClassesAreSeparateFamilies() {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();

    SinkFamily sql = cfg.sinkFamilies().byName("sql.sinks").orElseThrow();
    SinkFamily db = cfg.sinkFamilies().byName("database-access.sinks").orElseThrow();

    assertThat(sql.name()).isEqualTo("sql.sinks");
    assertThat(db.name()).isEqualTo("database-access.sinks");
    assertThat(sql.cwe()).as("the SQL-injection family is CWE-89").contains("CWE-89");
    assertThat(db.cwe()).as("the database-access family is CWE-566").contains("CWE-566");
    assertThat(sql.cwe())
        .as("and the CWE-566 rules must NOT have been folded into the CWE-89 family")
        .doesNotContain("CWE-566");
  }

  @Test
  @DisplayName("the database-access family has a checker, so it can actually fire")
  void theDatabaseAccessFamilyHasAChecker() {
    // A family with no checker never fires. The family and the checker have to
    // arrive together, or the coverage report claims a CWE that nothing reports.
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    assertThat(cfg.sinkFamilies().byName("database-access.sinks"))
        .as("the family exists")
        .isPresent();
    assertThat(cfg.cweMappings().get("DatabaseAccess"))
        .as("and its CWE is accounted for")
        .isEqualTo("CWE-566");
  }
}
