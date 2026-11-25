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
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The DOMAIN gate: a tainted value can only reach a sink that consumes that kind of value.
 *
 * <p><b>The defect this pins.</b> Every taint rule declares the kind of value it consumes -- a
 * string for SQL, a number for an allocation size -- and the engine had a domain lattice for it.
 * But the lattice was never connected to anything: the comparison at the sink was the rule's own
 * domain compared with ITSELF, which cannot fail. So the engine reported a tainted NUMBER as SQL
 * injection, and reported a tainted STRING as an uncontrolled memory allocation, and no amount of
 * configuration could stop it.
 *
 * <p><b>What changed.</b> The taint fact is now one value that carries both halves of what the
 * engine knows: the LABEL (where the value came from) and the DOMAIN (what KIND of value it is).
 * The domain is derived at the moment the fact is written, from the DESTINATION variable's type --
 * so a value that flows into a variable of another kind changes kind on the way, with no chain to
 * keep in step. The comparison at the sink is then real: the fact's domain against the rule's.
 *
 * <p><b>Why the guard has to isolate the mechanism.</b> The engine has a second gate that also
 * suppresses a mismatched value: the TYPE gate, which asks the sink family's declared expected type
 * against the resolved type of the value. Seven families declare one ({@code sql.sinks}, {@code
 * command.sinks}, {@code file-path.sinks}, {@code log-injection.sinks}, {@code
 * code-injection.sinks}, {@code ssrf.sinks}, {@code xss.sinks}) and sixty do not. A test built only
 * on those seven would be green whether or not the domain gate existed -- it would be pinning the
 * type gate and calling it the domain gate.
 *
 * <p>So the cases below deliberately split:
 *
 * <ul>
 *   <li>The <b>isolation</b> cases use {@code regex.sinks} and {@code mem-alloc.sinks}, neither of
 *       which declares an expected type. For those the type gate cannot fire, so a finding that
 *       disappears can only have been removed by the domain gate.
 *   <li>The <b>headline</b> case uses {@code sql.sinks}, which does declare one. It is kept because
 *       it is the shape people actually write, but it is stated here that the type gate could
 *       produce the same answer.
 *   <li>The <b>control</b> cases pin that the gate does NOT fire when it should not: a matching
 *       kind still reports, an UNKNOWN kind still reports, and a tainted number that becomes a
 *       string on the way still reports.
 * </ul>
 *
 * <p><b>What this deliberately does not claim.</b> The domain is a coarse kind, not a type lattice.
 * A tainted {@code Object} that holds a string is {@code MixedTaint} and reaches every sink, and
 * that is the direction the engine must err in: the gate may only remove a finding the engine can
 * PROVE is wrong.
 */
@DisplayName("Taint: the domain gate")
class TaintDomainGateTest {

  private static final String SQL_RULE = "dfa-taint-sql-injection";
  private static final String REGEX_RULE = "dfa-taint-regex-injection";
  private static final String MEM_ALLOC_RULE = "dfa-taint-uncontrolled-mem-alloc";

  /** A tainted NUMBER that reaches a SQL sink. A number is not SQL text. */
  private static final String SQL_NUMBER =
      """
      class G1 {
        void go(java.io.BufferedReader reader, java.sql.Statement stmt) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          stmt.executeQuery(data);
        }
      }
      """;

  /** A tainted STRING that reaches the same SQL sink: the classic shape, and it must still fire. */
  private static final String SQL_STRING =
      """
      class G2 {
        void go(java.io.BufferedReader reader, java.sql.Statement stmt) throws Exception {
          String data = reader.readLine();
          stmt.executeQuery(data);
        }
      }
      """;

  /**
   * A tainted NUMBER that reaches a regular-expression compile.
   *
   * <p>{@code regex.sinks} declares NO expected type, so the type gate cannot fire here: whatever
   * removes this finding is the domain gate and nothing else.
   */
  private static final String REGEX_NUMBER =
      """
      class G3 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          java.util.regex.Pattern p = java.util.regex.Pattern.compile(data);
          System.out.println(p);
        }
      }
      """;

  /** The same compile with a tainted STRING: the shape the rule is about. */
  private static final String REGEX_STRING =
      """
      class G4 {
        void go(java.io.BufferedReader reader) throws Exception {
          String data = reader.readLine();
          java.util.regex.Pattern p = java.util.regex.Pattern.compile(data);
          System.out.println(p);
        }
      }
      """;

  /**
   * A tainted STRING that reaches an allocation site.
   *
   * <p>{@code mem-alloc.sinks} declares NO expected type either, so this is the mirror of the regex
   * case: the allocation size is a number, and a string is not one.
   */
  private static final String MEM_ALLOC_STRING =
      """
      class G5 {
        void go(java.io.BufferedReader reader) throws Exception {
          String data = reader.readLine();
          StringBuilder sb = new StringBuilder(data);
          System.out.println(sb);
        }
      }
      """;

  /** The same allocation with a tainted NUMBER: that is the shape the rule is about. */
  private static final String MEM_ALLOC_NUMBER =
      """
      class G6 {
        void go(java.io.BufferedReader reader) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          StringBuilder sb = new StringBuilder(data);
          System.out.println(sb);
        }
      }
      """;

  /**
   * A tainted value whose kind the engine cannot tell.
   *
   * <p>{@code Object} could hold a string, so the fact's kind is unknown and the gate must not
   * fire: an unknown kind never suppresses a finding.
   */
  private static final String SQL_UNKNOWN_KIND =
      """
      class G7 {
        void go(java.io.BufferedReader reader, java.sql.Statement stmt) throws Exception {
          Object data = reader.readLine();
          stmt.executeQuery(data);
        }
      }
      """;

  /**
   * A tainted NUMBER that becomes a STRING on the way.
   *
   * <p>The kind follows the value, not the origin: once the number has been concatenated into a
   * string, the string is what reaches the sink, and the string is what the SQL sink consumes.
   */
  private static final String SQL_CONCATENATED =
      """
      class G8 {
        void go(java.io.BufferedReader reader, java.sql.Statement stmt) throws Exception {
          int data = Integer.parseInt(reader.readLine().trim());
          String query = "SELECT * FROM t WHERE id = " + data;
          stmt.executeQuery(query);
        }
      }
      """;

  @TempDir Path tmp;

  /** Run one checker over one source file and return the findings it produced. */
  private List<Finding> scan(TaintedCheckerBase checker, String ruleId, String source, String name)
      throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(base, List.of(checker), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (ruleId.equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  @Test
  @DisplayName("a tainted number is not SQL injection")
  void aTaintedNumberIsNotSqlInjection() throws Exception {
    assertThat(scan(new SqlInjectionChecker(), SQL_RULE, SQL_NUMBER, "G1.java"))
        .as(
            "CWE-89: the SQL text is a string, and a number cannot be SQL text -- but this case is "
                + "removed by the TYPE gate, because sql.sinks declares expects_type: string. The "
                + "DOMAIN gate would agree; the two cases below it (regex.sinks and "
                + "mem-alloc.sinks) are the ones the DOMAIN gate alone removes, because those "
                + "families declare no expected type")
        .isEmpty();
  }

  @Test
  @DisplayName("a tainted string is still SQL injection")
  void aTaintedStringIsStillSqlInjection() throws Exception {
    assertThat(scan(new SqlInjectionChecker(), SQL_RULE, SQL_STRING, "G2.java"))
        .as("CWE-89: the control -- a matching kind must keep its finding")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a tainted number is not a regular-expression injection")
  void aTaintedNumberIsNotARegularExpressionInjection() throws Exception {
    assertThat(scan(new RegexInjectionChecker(), REGEX_RULE, REGEX_NUMBER, "G3.java"))
        .as(
            "CWE-1333: regex.sinks declares no expected type, so the type gate cannot fire here -- "
                + "this finding is removed by the domain gate alone")
        .isEmpty();
  }

  @Test
  @DisplayName("a tainted string is still a regular-expression injection")
  void aTaintedStringIsStillARegularExpressionInjection() throws Exception {
    assertThat(scan(new RegexInjectionChecker(), REGEX_RULE, REGEX_STRING, "G4.java"))
        .as("CWE-1333: the control -- a matching kind must keep its finding")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a tainted string is not an uncontrolled allocation")
  void aTaintedStringIsNotAnUncontrolledAllocation() throws Exception {
    assertThat(scan(new UncontrolledMemAllocChecker(), MEM_ALLOC_RULE, MEM_ALLOC_STRING, "G5.java"))
        .as(
            "CWE-789: mem-alloc.sinks declares no expected type, so the type gate cannot fire "
                + "here -- the allocation size is a number, and a string is not one")
        .isEmpty();
  }

  @Test
  @DisplayName("a tainted number is still an uncontrolled allocation")
  void aTaintedNumberIsStillAnUncontrolledAllocation() throws Exception {
    assertThat(scan(new UncontrolledMemAllocChecker(), MEM_ALLOC_RULE, MEM_ALLOC_NUMBER, "G6.java"))
        .as("CWE-789: the control -- a matching kind must keep its finding")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a tainted value of unknown kind still reaches the sink")
  void aTaintedValueOfUnknownKindStillReachesTheSink() throws Exception {
    assertThat(scan(new SqlInjectionChecker(), SQL_RULE, SQL_UNKNOWN_KIND, "G7.java"))
        .as(
            "an Object could hold a string, so the kind is unknown -- and an unknown kind never "
                + "suppresses a finding")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a tainted number that became a string still reaches the sink")
  void aTaintedNumberThatBecameAStringStillReachesTheSink() throws Exception {
    assertThat(scan(new SqlInjectionChecker(), SQL_RULE, SQL_CONCATENATED, "G8.java"))
        .as(
            "the kind follows the value, not the origin: once the number has been concatenated "
                + "into a string, the string is what the SQL sink consumes")
        .isNotEmpty();
  }
}
