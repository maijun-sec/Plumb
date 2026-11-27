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
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
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
 * A path condition is a statement about a VALUE, not about a variable name.
 *
 * <h2>What was wrong</h2>
 *
 * <p>The engine built the path condition from the guards recorded against the sink argument's own
 * NAME. That works while the guard and the sink talk about the same name — and it stops working the
 * moment they do not, which is the common case:
 *
 * <pre>{@code
 * int copy = n;
 * if (copy > 5 && copy < 3) {   // the guard is on `copy`
 *     st.executeQuery("..." + n + "...");   // the sink's argument is `n`
 * }
 * }</pre>
 *
 * <p>Here the value that reaches the sink is the one the guard talks about — the two NAMES differ,
 * the VALUE does not — so the condition came out empty and the solver was never asked anything. On
 * the corpus this meant the solver was consulted <em>zero</em> times in 2,886 groups.
 *
 * <h2>What is asserted</h2>
 *
 * <ul>
 *   <li>a guard on a COPY of the value constrains the original ⇒ NOT reported;
 *   <li>a guard on the value constrains the CONCATENATION of it ⇒ NOT reported;
 *   <li>a guard on an UNRELATED variable says nothing about this value ⇒ still reported;
 *   <li>a guard that does not make the path unreachable ⇒ still reported;
 *   <li>an assignment ENDS the relation ⇒ a guard on the old copy no longer silences the original.
 * </ul>
 *
 * <p>The last three are the controls: without them, "the guard applied" and "the engine stopped
 * reporting" would be the same claim.
 */
class ValueAliasGuardTest {

  /** The guard is on a copy; the sink receives the original. */
  private static final String GUARD_ON_COPY =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class GuardOnCopy {
        public void go(Statement st, java.io.BufferedReader reader) throws Exception {
          int n = Integer.parseInt(reader.readLine());
          int copy = n;
          if (copy > 5 && copy < 3) {
            st.executeQuery("SELECT * FROM t WHERE c = '" + n + "'");
          }
        }
      }
      """;

  /** The guard is on the value; the sink receives a concatenation of it. */
  private static final String GUARD_ON_CONCAT =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class GuardOnConcat {
        public void go(Statement st, java.io.BufferedReader reader) throws Exception {
          int n = Integer.parseInt(reader.readLine());
          if (n > 5 && n < 3) {
            st.executeQuery("SELECT * FROM t WHERE c = '" + n + "'");
          }
        }
      }
      """;

  /** The guard is on a DIFFERENT value: it says nothing about this one. */
  private static final String GUARD_ON_UNRELATED =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class GuardOnUnrelated {
        public void go(Statement st, java.io.BufferedReader reader) throws Exception {
          int n = Integer.parseInt(reader.readLine());
          int m = Integer.parseInt(reader.readLine());
          if (m > 5 && m < 3) {
            st.executeQuery("SELECT * FROM t WHERE c = '" + n + "'");
          }
        }
      }
      """;

  /** The guard is satisfiable: the path it describes really exists. */
  private static final String REACHABLE_GUARD =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ReachableGuard {
        public void go(Statement st, java.io.BufferedReader reader) throws Exception {
          int n = Integer.parseInt(reader.readLine());
          int copy = n;
          if (copy > 5 && copy < 100) {
            st.executeQuery("SELECT * FROM t WHERE c = '" + n + "'");
          }
        }
      }
      """;

  /** The copy is overwritten before the sink, so it no longer holds the original's value. */
  private static final String REASSIGNED_COPY =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ReassignedCopy {
        public void go(Statement st, java.io.BufferedReader reader) throws Exception {
          int n = Integer.parseInt(reader.readLine());
          int copy = n;
          copy = 7;
          if (copy > 5 && copy < 3) {
            st.executeQuery("SELECT * FROM t WHERE c = '" + n + "'");
          }
        }
      }
      """;

  /** The guarded value goes into a field, and the sink reads the field back. */
  private static final String GUARD_THROUGH_FIELD =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class GuardThroughField {
        private int stored;
        public void go(Statement st, java.io.BufferedReader reader) throws Exception {
          int v = Integer.parseInt(reader.readLine());
          if (v > 5 && v < 3) {
            this.stored = v;
            st.executeQuery("SELECT * FROM t WHERE c = '" + this.stored + "'");
          }
        }
      }
      """;

  /** The field holds ANOTHER value: the guard on this one says nothing about that one. */
  private static final String FIELD_HOLDS_ANOTHER_VALUE =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class FieldHoldsAnotherValue {
        private int stored;
        public void go(Statement st, java.io.BufferedReader reader) throws Exception {
          int v = Integer.parseInt(reader.readLine());
          int other = Integer.parseInt(reader.readLine());
          if (v > 5 && v < 3) {
            this.stored = other;
            st.executeQuery("SELECT * FROM t WHERE c = '" + this.stored + "'");
          }
        }
      }
      """;

  /** The guarded value goes into a container, and the sink reads the element back. */
  private static final String GUARD_THROUGH_CONTAINER =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class GuardThroughContainer {
        public void go(Statement st, java.io.BufferedReader reader) throws Exception {
          int v = Integer.parseInt(reader.readLine());
          java.util.List<Integer> xs = new java.util.ArrayList<>();
          if (v > 5 && v < 3) {
            xs.add(v);
            st.executeQuery("SELECT * FROM t WHERE c = '" + xs.get(0) + "'");
          }
        }
      }
      """;

  /** The container holds ANOTHER value: the guard on this one says nothing about that one. */
  private static final String CONTAINER_HOLDS_ANOTHER_VALUE =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ContainerHoldsAnotherValue {
        public void go(Statement st, java.io.BufferedReader reader) throws Exception {
          int v = Integer.parseInt(reader.readLine());
          int other = Integer.parseInt(reader.readLine());
          java.util.List<Integer> xs = new java.util.ArrayList<>();
          if (v > 5 && v < 3) {
            xs.add(other);
            st.executeQuery("SELECT * FROM t WHERE c = '" + xs.get(0) + "'");
          }
        }
      }
      """;

  // ─── B0.2b: the guard is in the CALLEE, and the value comes back across the call
  // boundary ───────────────────────────────────────────────────────────────────────────

  /** The guard is in the callee, and the tainted value leaves it as the return value. */
  private static final String GUARD_IN_CALLEE =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class GuardInCallee {
        int parse(java.io.BufferedReader reader) throws Exception {
          int n = Integer.parseInt(reader.readLine());
          if (n > 5 && n < 3) {
            return n;
          }
          return 0;
        }
        public void go(Statement st, java.io.BufferedReader reader) throws Exception {
          int v = parse(reader);
          st.executeQuery("SELECT * FROM t WHERE c = '" + v + "'");
        }
      }
      """;

  /** The callee hands the tainted value back with no condition at all. */
  private static final String UNCONDITIONAL_RETURN =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class UnconditionalReturn {
        int parse(java.io.BufferedReader reader) throws Exception {
          return Integer.parseInt(reader.readLine());
        }
        public void go(Statement st, java.io.BufferedReader reader) throws Exception {
          int v = parse(reader);
          st.executeQuery("SELECT * FROM t WHERE c = '" + v + "'");
        }
      }
      """;

  /** The callee's guard can hold, so the value really does come back. */
  private static final String REACHABLE_GUARD_IN_CALLEE =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ReachableGuardInCallee {
        int parse(java.io.BufferedReader reader) throws Exception {
          int n = Integer.parseInt(reader.readLine());
          if (n > 5 && n > 3) {
            return n;
          }
          return 0;
        }
        public void go(Statement st, java.io.BufferedReader reader) throws Exception {
          int v = parse(reader);
          st.executeQuery("SELECT * FROM t WHERE c = '" + v + "'");
        }
      }
      """;

  /**
   * The callee releases the tainted value TWICE: once under a condition that cannot hold, and once
   * with no condition at all.
   *
   * <p>The two releases are ALTERNATIVES, so the taint arrives when EITHER of them happens -- and
   * one of them always happens.
   */
  private static final String ONE_UNCONDITIONAL_RELEASE =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class OneUnconditionalRelease {
        int parse(java.io.BufferedReader reader) throws Exception {
          int n = Integer.parseInt(reader.readLine());
          if (n > 5 && n < 3) {
            return n;
          }
          return n;
        }
        public void go(Statement st, java.io.BufferedReader reader) throws Exception {
          int v = parse(reader);
          st.executeQuery("SELECT * FROM t WHERE c = '" + v + "'");
        }
      }
      """;

  @TempDir Path tmp;

  @Test
  void aGuardOnACopyConstrainsTheOriginal() throws Exception {
    String dir = "a" + uid();
    List<Finding> findings = scan(List.of(translate(dir + "/GuardOnCopy.java", GUARD_ON_COPY)));

    assertThat(inFile(findings, "GuardOnCopy.java"))
        .as(
            "the copy and the original hold one value, so the guard that says the value cannot be"
                + " in range also says the original cannot reach the sink")
        .isEmpty();
  }

  @Test
  void aGuardOnTheValueConstrainsTheConcatenationOfIt() throws Exception {
    String dir = "b" + uid();
    List<Finding> findings = scan(List.of(translate(dir + "/GuardOnConcat.java", GUARD_ON_CONCAT)));

    assertThat(inFile(findings, "GuardOnConcat.java"))
        .as("the sink's argument is a temporary built from the guarded value")
        .isEmpty();
  }

  @Test
  void aGuardOnAnUnrelatedVariableDoesNotSilenceThisValue() throws Exception {
    String dir = "c" + uid();
    List<Finding> findings =
        scan(List.of(translate(dir + "/GuardOnUnrelated.java", GUARD_ON_UNRELATED)));

    assertThat(inFile(findings, "GuardOnUnrelated.java"))
        .as("the guard is about another value entirely, so this one still reaches the sink")
        .hasSize(1);
  }

  @Test
  void aGuardThatDoesNotMakeThePathUnreachableDoesNotSilenceIt() throws Exception {
    String dir = "d" + uid();
    List<Finding> findings =
        scan(List.of(translate(dir + "/ReachableGuard.java", REACHABLE_GUARD)));

    assertThat(inFile(findings, "ReachableGuard.java"))
        .as("the bounds can both hold, so the path the guard describes really exists")
        .hasSize(1);
  }

  @Test
  void anAssignmentEndsTheRelationBetweenTheNames() throws Exception {
    String dir = "e" + uid();
    List<Finding> findings =
        scan(List.of(translate(dir + "/ReassignedCopy.java", REASSIGNED_COPY)));

    assertThat(inFile(findings, "ReassignedCopy.java"))
        .as(
            "the copy holds 7 now, so a guard on it says nothing about the original's value --"
                + " and the guard is about the copy, which is not the value that reaches the sink")
        .hasSize(1);
  }

  @Test
  void aGuardOnTheValueConstrainsTheValueReadBackFromAField() throws Exception {
    String dir = "f" + uid();
    List<Finding> findings =
        scan(List.of(translate(dir + "/GuardThroughField.java", GUARD_THROUGH_FIELD)));

    assertThat(inFile(findings, "GuardThroughField.java"))
        .as(
            "the field holds that value, so reading the field back reads the value the guard"
                + " talks about")
        .isEmpty();
  }

  @Test
  void aGuardDoesNotConstrainAFieldThatHoldsAnotherValue() throws Exception {
    String dir = "g" + uid();
    List<Finding> findings =
        scan(List.of(translate(dir + "/FieldHoldsAnotherValue.java", FIELD_HOLDS_ANOTHER_VALUE)));

    assertThat(inFile(findings, "FieldHoldsAnotherValue.java"))
        .as("the field holds a different value, so the guard on this one says nothing about it")
        .hasSize(1);
  }

  @Test
  void aGuardOnTheValueConstrainsTheElementReadBackFromAContainer() throws Exception {
    String dir = "h" + uid();
    List<Finding> findings =
        scan(List.of(translate(dir + "/GuardThroughContainer.java", GUARD_THROUGH_CONTAINER)));

    assertThat(inFile(findings, "GuardThroughContainer.java"))
        .as("the container holds that value, so reading the element back reads it")
        .isEmpty();
  }

  @Test
  void aGuardDoesNotConstrainAContainerThatHoldsAnotherValue() throws Exception {
    String dir = "i" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(
                    dir + "/ContainerHoldsAnotherValue.java", CONTAINER_HOLDS_ANOTHER_VALUE)));

    assertThat(inFile(findings, "ContainerHoldsAnotherValue.java"))
        .as("the container holds a different value, so the guard on this one says nothing about it")
        .hasSize(1);
  }

  @Test
  void aGuardInTheCalleeConstrainsTheValueThatComesBack() throws Exception {
    String dir = "j" + uid();
    List<Finding> findings = scan(List.of(translate(dir + "/GuardInCallee.java", GUARD_IN_CALLEE)));

    assertThat(inFile(findings, "GuardInCallee.java"))
        .as(
            "the callee releases the tainted value only under a condition no value satisfies, so"
                + " the value that comes back is not the tainted one")
        .isEmpty();
  }

  @Test
  void anUnconditionalReturnFromTheCalleeStillReachesTheSink() throws Exception {
    String dir = "k" + uid();
    List<Finding> findings =
        scan(List.of(translate(dir + "/UnconditionalReturn.java", UNCONDITIONAL_RETURN)));

    assertThat(inFile(findings, "UnconditionalReturn.java"))
        .as("the callee hands the tainted value back with no condition, so it really arrives")
        .hasSize(1);
  }

  @Test
  void aReachableGuardInTheCalleeDoesNotSilenceTheSink() throws Exception {
    String dir = "l" + uid();
    List<Finding> findings =
        scan(List.of(translate(dir + "/ReachableGuardInCallee.java", REACHABLE_GUARD_IN_CALLEE)));

    assertThat(inFile(findings, "ReachableGuardInCallee.java"))
        .as("the callee's bounds can both hold, so the path really exists")
        .hasSize(1);
  }

  @Test
  void oneUnconditionalReleaseKeepsTheOtherAlternativesAlive() throws Exception {
    String dir = "m" + uid();
    List<Finding> findings =
        scan(List.of(translate(dir + "/OneUnconditionalRelease.java", ONE_UNCONDITIONAL_RELEASE)));

    assertThat(inFile(findings, "OneUnconditionalRelease.java"))
        .as(
            "the two releases are alternatives: the unconditional one always happens, so the"
                + " tainted value arrives however the contradictory branch is read")
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
