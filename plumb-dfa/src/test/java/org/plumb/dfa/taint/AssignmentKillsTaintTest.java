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
 * An assignment that overwrites a tainted value with a clean one kills the taint.
 *
 * <h2>Why the engine used to keep it</h2>
 *
 * <p>The engine's taint analysis is path-insensitive: it walks a function in reverse post-order and
 * keeps one set of facts for the whole function. A program that overwrites a tainted value with a
 * constant is not exploitable -- but a program that overwrites it <em>on one path only</em> is. The
 * engine could not tell the two apart, so it kept the taint on every reassignment, and every
 * repaired program that overwrote the value before the sink was still reported.
 *
 * <h2>What the kill is, and where it stops</h2>
 *
 * <p>The kill holds for the rest of the <b>block</b> it sits in, and the fact comes back when the
 * block ends. That is exactly what a straight-line region can prove: the assignment ran before the
 * use, on every path that reaches the use through this block. Past the block, another path may
 * reach the use without running the assignment at all -- so the fact is restored and the use sees
 * the taint it would have seen without the kill.
 *
 * <p>The consequence worth stating: a kill in one arm of a branch does <em>not</em> silence the
 * other arm. That is the difference between "the assignment killed the taint" and "the assignment
 * killed the taint on the path it is on", and only the second one is true.
 *
 * <h2>What is asserted</h2>
 *
 * <ul>
 *   <li>a value overwritten with a literal before the sink is NOT reported;
 *   <li>a PARAMETER overwritten with a literal before the sink is NOT reported;
 *   <li>a FIELD overwritten with a literal and read back is NOT reported;
 *   <li>a value overwritten in ONE ARM of a branch is still reported at the join, because the other
 *       arm never ran the assignment;
 *   <li>a value nobody overwrites is still reported.
 * </ul>
 */
class AssignmentKillsTaintTest {

  /** A local overwritten with a literal before the sink. */
  private static final String REASSIGN_LOCAL =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ReassignLocal {
        public void go(Statement st, String userInput) throws SQLException {
          String v = userInput;
          v = "constant";
          st.executeQuery("SELECT * FROM t WHERE c = '" + v + "'");
        }
      }
      """;

  /** The parameter itself overwritten with a literal before the sink. */
  private static final String REASSIGN_PARAM =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ReassignParam {
        public void go(Statement st, String userInput) throws SQLException {
          userInput = "constant";
          st.executeQuery("SELECT * FROM t WHERE c = '" + userInput + "'");
        }
      }
      """;

  /** The field overwritten with a literal, then read back at the sink. */
  private static final String REASSIGN_FIELD =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ReassignField {
        private String value;
        public void go(Statement st, String userInput) throws SQLException {
          this.value = userInput;
          this.value = "constant";
          st.executeQuery("SELECT * FROM t WHERE c = '" + this.value + "'");
        }
      }
      """;

  /**
   * The overwrite happens in ONE arm of the branch. The other arm never runs it, so the value at
   * the join still carries the taint on that path -- and the sink is still reachable by it.
   */
  private static final String REASSIGN_IN_ONE_ARM =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ReassignInOneArm {
        public void go(Statement st, String userInput, boolean flag) throws SQLException {
          String v = userInput;
          if (flag) {
            v = "constant";
          }
          st.executeQuery("SELECT * FROM t WHERE c = '" + v + "'");
        }
      }
      """;

  /** Nothing is overwritten: the value reaches the sink as it came in. */
  private static final String NO_REASSIGN =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class NoReassign {
        public void go(Statement st, String userInput) throws SQLException {
          String v = userInput;
          st.executeQuery("SELECT * FROM t WHERE c = '" + v + "'");
        }
      }
      """;

  @TempDir Path tmp;

  @Test
  void aValueOverwrittenWithALiteralIsNotReported() throws Exception {
    String dir = "a" + uid();
    List<Finding> findings = scan(List.of(translate(dir + "/ReassignLocal.java", REASSIGN_LOCAL)));

    assertThat(inFile(findings, "ReassignLocal.java"))
        .as("the literal overwrote the tainted value before the sink, so nothing reaches it")
        .isEmpty();
  }

  @Test
  void aParameterOverwrittenWithALiteralIsNotReported() throws Exception {
    String dir = "b" + uid();
    List<Finding> findings = scan(List.of(translate(dir + "/ReassignParam.java", REASSIGN_PARAM)));

    assertThat(inFile(findings, "ReassignParam.java"))
        .as("the parameter itself was overwritten, so it holds a literal at the sink")
        .isEmpty();
  }

  @Test
  void aFieldOverwrittenWithALiteralIsNotReportedWhenItIsReadBack() throws Exception {
    String dir = "c" + uid();
    List<Finding> findings = scan(List.of(translate(dir + "/ReassignField.java", REASSIGN_FIELD)));

    assertThat(inFile(findings, "ReassignField.java"))
        .as("the field was overwritten with a literal, so reading it back reads the literal")
        .isEmpty();
  }

  @Test
  void anOverwriteInOneArmOfABranchDoesNotSilenceTheOtherArm() throws Exception {
    String dir = "d" + uid();
    List<Finding> findings =
        scan(List.of(translate(dir + "/ReassignInOneArm.java", REASSIGN_IN_ONE_ARM)));

    assertThat(inFile(findings, "ReassignInOneArm.java"))
        .as(
            "the overwrite ran on one path only, so the other path still carries the taint to the"
                + " sink -- the kill holds for its own block and no longer")
        .hasSize(1);
  }

  @Test
  void aValueNobodyOverwritesIsStillReported() throws Exception {
    String dir = "e" + uid();
    List<Finding> findings = scan(List.of(translate(dir + "/NoReassign.java", NO_REASSIGN)));

    assertThat(inFile(findings, "NoReassign.java"))
        .as("the value reaches the sink exactly as it came in")
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
