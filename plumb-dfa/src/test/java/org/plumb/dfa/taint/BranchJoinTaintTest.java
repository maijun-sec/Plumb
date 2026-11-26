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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The join of two branches: a value that EVERY arm overwrote is clean where the arms meet.
 *
 * <h2>Why the engine used to report it</h2>
 *
 * <p>An assignment that overwrites a tainted value with a constant kills the taint -- but the kill
 * holds for the block it sits in, because the engine's analysis is path-insensitive: a kill on one
 * path does not reach a use reached by another path. So at the point where two arms meet, the
 * engine put the taint back, and a program whose two arms BOTH overwrite the value was still
 * reported at the sink.
 *
 * <p>That is not a close call. If both arms overwrite the value, then on every path out of the
 * branch the value is a literal, and the sink downstream of the branch receives a literal. There is
 * no path that carries the taint there.
 *
 * <h2>Why the rule is a MUST and not a MAY</h2>
 *
 * <p>The value at the join is clean only if EVERY predecessor is clean. One arm that does not
 * overwrite is enough to keep the taint: the join may be reached through that arm, and the sink
 * downstream is reachable by it. The tests below pin that direction, because the opposite mistake
 * -- treating "some arms cleaned it" as "the join is clean" -- is the one that loses findings.
 *
 * <h2>What is asserted</h2>
 *
 * <ul>
 *   <li>BOTH arms overwrite the value &#8594; the join is clean &#8594; NOT reported;
 *   <li>ONE arm overwrites it &#8594; the join still carries the taint &#8594; reported;
 *   <li>NO arm overwrites it &#8594; reported;
 *   <li>TWO of THREE arms overwrite it &#8594; the third one keeps the taint &#8594; reported.
 * </ul>
 */
class BranchJoinTaintTest {

  /** Both arms overwrite the value with a literal, so the join holds a literal. */
  private static final String BOTH_ARMS_CLEAN =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class BothArmsClean {
        public void go(Statement st, String userInput, boolean flag) throws SQLException {
          String v = userInput;
          if (flag) {
            v = "constant-a";
          } else {
            v = "constant-b";
          }
          st.executeQuery("SELECT * FROM t WHERE c = '" + v + "'");
        }
      }
      """;

  /** Only the first arm overwrites the value; the second arm never runs the assignment. */
  private static final String ONE_ARM_CLEAN =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class OneArmClean {
        public void go(Statement st, String userInput, boolean flag) throws SQLException {
          String v = userInput;
          if (flag) {
            v = "constant";
          }
          st.executeQuery("SELECT * FROM t WHERE c = '" + v + "'");
        }
      }
      """;

  /** No arm overwrites the value. */
  private static final String NO_ARM_CLEAN =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class NoArmClean {
        public void go(Statement st, String userInput, boolean flag) throws SQLException {
          String v = userInput;
          if (flag) {
            st.executeQuery("SELECT * FROM t WHERE c = '" + v + "'");
          } else {
            st.executeQuery("SELECT * FROM t WHERE c = '" + v + "'");
          }
        }
      }
      """;

  /** Two of the three arms overwrite the value; the third one carries the taint to the join. */
  private static final String TWO_OF_THREE_ARMS_CLEAN =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class TwoOfThreeArmsClean {
        public void go(Statement st, String userInput, int mode) throws SQLException {
          String v = userInput;
          if (mode == 0) {
            v = "constant-a";
          } else if (mode == 1) {
            v = "constant-b";
          }
          st.executeQuery("SELECT * FROM t WHERE c = '" + v + "'");
        }
      }
      """;

  @TempDir Path tmp;

  @Test
  void aValueOverwrittenInEveryArmIsCleanAtTheJoin() throws Exception {
    String dir = "a" + uid();
    List<Finding> findings = scan(List.of(translate(dir + "/BothArmsClean.java", BOTH_ARMS_CLEAN)));

    assertThat(inFile(findings, "BothArmsClean.java"))
        .as(
            "every path out of the branch overwrites the value, so the value at the join is a"
                + " literal on every path and nothing tainted reaches the sink")
        .isEmpty();
  }

  @Test
  void aValueOverwrittenInOnlyOneArmIsStillTaintedAtTheJoin() throws Exception {
    String dir = "b" + uid();
    List<Finding> findings = scan(List.of(translate(dir + "/OneArmClean.java", ONE_ARM_CLEAN)));

    assertThat(inFile(findings, "OneArmClean.java"))
        .as(
            "the other arm never runs the assignment, so the join is reachable carrying the taint"
                + " -- the join rule is a must, not a may")
        .hasSize(1);
  }

  @Test
  void aValueNobodyOverwritesIsStillReportedAtTheJoin() throws Exception {
    String dir = "c" + uid();
    List<Finding> findings = scan(List.of(translate(dir + "/NoArmClean.java", NO_ARM_CLEAN)));

    assertThat(inFile(findings, "NoArmClean.java"))
        .as("the value reaches both sinks exactly as it came in")
        .hasSize(2);
  }

  @Test
  void aValueOverwrittenInTwoOfThreeArmsIsStillTaintedAtTheJoin() throws Exception {
    String dir = "d" + uid();
    List<Finding> findings =
        scan(List.of(translate(dir + "/TwoOfThreeArmsClean.java", TWO_OF_THREE_ARMS_CLEAN)));

    assertThat(inFile(findings, "TwoOfThreeArmsClean.java"))
        .as(
            "the third arm carries the taint to the join, so two clean arms do not make the join"
                + " clean -- the rule counts EVERY predecessor, not some of them")
        .hasSize(1);
  }

  /** The findings a scan reported for one file of the program. */
  private static List<Finding> inFile(List<Finding> findings, String fileName) {
    List<Finding> out = new ArrayList<>();
    for (Finding f : findings) {
      if ((f.filePath() == null ? "" : f.filePath()).contains(fileName)) out.add(f);
    }
    return out;
  }

  private List<Finding> scan(List<TacProgram> programs) {
    // Mirror ScanRunner.runUnifiedCheckers' EngineConfig exactly, for the reason spelled out
    // in ContainerContentFollowsTheCallSiteTest: a different config would assert against a
    // configuration no user ever runs.
    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig cfg =
        new EngineConfig(
            base.sinkFamilies(),
            org.plumb.dfa.fact.BuiltinFactKinds.registryWithBuiltins(),
            base.checkers(),
            base.cweMappings(),
            new org.plumb.dfa.config.TuningConfig(
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
            .runWithGlobalStore(programs, 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if ("dfa-taint-sql-injection".equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  private TacProgram translate(String relPath, String source) throws Exception {
    Path file = tmp.resolve(relPath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    return new TacTranslator(ps).translate();
  }

  /** Per-call suffix so each test gets its own package, keeping @TempDir dirs independent. */
  private static String uid() {
    return java.util.UUID.randomUUID().toString().substring(0, 8);
  }
}
