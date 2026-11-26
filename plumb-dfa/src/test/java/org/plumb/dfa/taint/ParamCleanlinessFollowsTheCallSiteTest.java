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
 * A parameter is a source because of the entry boundary, and the ENTRY BOUNDARY is a guess.
 *
 * <h2>Why this matters</h2>
 *
 * <p>Every parameter is seeded as a taint source when its function is entered, so a method whose
 * parameter reaches a sink reports regardless of what any caller passed. That is the right default
 * for a method NOBODY calls -- the entry boundary really is a trust boundary there -- but it makes
 * "the caller passed something harmless" inexpressible. Every repaired program that hands a
 * constant to a helper is then reported, and no amount of precision work elsewhere can fix it.
 *
 * <h2>What the proof has to be</h2>
 *
 * <p>The engine may only drop the source when it can PROVE the parameter receives a clean value at
 * every call site. "The argument carries no taint label" is not such a proof: a variable only gains
 * a label once something tainted reaches it, so an unlabelled variable is far more often
 * "unanalysed" than "safe". The proofs that count are:
 *
 * <ul>
 *   <li>the call site passes a literal;
 *   <li>the call site passes a variable whose every definition is itself provably clean;
 *   <li>the call site passes a parameter that every call site of THAT parameter proves clean.
 * </ul>
 *
 * <p>The third rule is what makes the proof transitive, and transitivity is what a chain of helper
 * methods needs. It is also what forces the identity of a parameter to be the function's MERGED key
 * and not its bare signature: the second hop of a chain calls a method with the SAME NAME as the
 * first hop, so a bare signature pools the two and the proof becomes circular -- the second hop is
 * clean only if the first hop is, and pooling merges them.
 *
 * <h2>What is asserted</h2>
 *
 * <ul>
 *   <li>a parameter every call site fills with a literal is NOT a source;
 *   <li>the same holds for a variable the caller assigned only literals;
 *   <li>the same holds for a variable COPIED from a clean variable;
 *   <li>the same holds when the sink receives the parameter through a CONCATENATION -- the case
 *       that only the SEEDING SITE can decide, because a finding whose sink argument is a
 *       concatenation temp cannot be suppressed at the sink;
 *   <li>the proof survives a TWO-HOP chain of same-named methods;
 *   <li>the proof survives a THREE-HOP chain -- one hop more than the clean fixed point settles in
 *       a single round, so it only passes if the fixed point drives the iteration;
 *   <li>a parameter that receives a tainted value IS still a source (the mechanism must not
 *       blanket-suppress);
 *   <li>a parameter NOBODY calls is still a source (the entry boundary is a real boundary);
 *   <li>a value copied from a parameter is not clean, so the callee still reports.
 * </ul>
 */
class ParamCleanlinessFollowsTheCallSiteTest {

  private static final String LITERAL_CALLER =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class LiteralCaller {
        public void go(Statement st) throws SQLException {
          new LiteralSink().run(st, "constant");
        }
      }
      """;

  private static final String LITERAL_SINK =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class LiteralSink {
        public void run(Statement st, String q) throws SQLException {
          st.executeQuery(q);
        }
      }
      """;

  private static final String CLEAN_VAR_CALLER =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class CleanVarCaller {
        public void go(Statement st) throws SQLException {
          String s = "constant";
          new CleanVarSink().run(st, s);
        }
      }
      """;

  private static final String CLEAN_VAR_SINK =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class CleanVarSink {
        public void run(Statement st, String q) throws SQLException {
          st.executeQuery(q);
        }
      }
      """;

  private static final String CONCAT_CALLER =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ConcatCaller {
        public void go(Statement st) throws SQLException {
          new ConcatSink().run(st, "constant");
        }
      }
      """;

  private static final String CONCAT_SINK =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ConcatSink {
        public void run(Statement st, String q) throws SQLException {
          st.executeQuery("SELECT * FROM t WHERE c = '" + q + "'");
        }
      }
      """;

  private static final String TAINTED_CALLER =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class TaintedCaller {
        public void go(Statement st, String userInput) throws SQLException {
          new TaintedSink().run(st, userInput);
        }
      }
      """;

  private static final String TAINTED_SINK =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class TaintedSink {
        public void run(Statement st, String q) throws SQLException {
          st.executeQuery(q);
        }
      }
      """;

  private static final String DIRTY_VAR_CALLER =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class DirtyVarCaller {
        public void go(Statement st, String userInput) throws SQLException {
          String s = userInput;
          new DirtyVarSink().run(st, s);
        }
      }
      """;

  private static final String DIRTY_VAR_SINK =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class DirtyVarSink {
        public void run(Statement st, String q) throws SQLException {
          st.executeQuery(q);
        }
      }
      """;

  private static final String UNCALLED_SINK =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class UncalledSink {
        public void run(Statement st, String q) throws SQLException {
          st.executeQuery(q);
        }
      }
      """;

  private static final String COPY_CALLER =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class CopyCaller {
        public void go(Statement st) throws SQLException {
          String s = "constant";
          String t = s;
          new CopySink().run(st, t);
        }
      }
      """;

  private static final String COPY_SINK =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class CopySink {
        public void run(Statement st, String q) throws SQLException {
          st.executeQuery(q);
        }
      }
      """;

  // The three classes of the chain declare the SAME method name. The bare signature is
  // therefore not unique, which is exactly the situation in which pooling the parameter
  // identity makes the proof circular.
  private static final String CHAIN_A =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ChainA {
        public void good(Statement st) throws SQLException {
          new ChainB().sink(st, "constant");
        }
      }
      """;

  private static final String CHAIN_B =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ChainB {
        public void sink(Statement st, String q) throws SQLException {
          new ChainC().sink(st, q);
        }
      }
      """;

  private static final String CHAIN_C =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ChainC {
        public void sink(Statement st, String q) throws SQLException {
          st.executeQuery(q);
        }
      }
      """;

  // A chain of FOUR classes: one hop more than the clean fixed point settles in a
  // single round, so it only passes if the fixed point drives the iteration itself.
  private static final String LONG3_A =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Long3A {
        public void good(Statement st) throws SQLException {
          new Long3B().sink(st, "constant");
        }
      }
      """;

  private static final String LONG3_B =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Long3B {
        public void sink(Statement st, String q) throws SQLException {
          new Long3C().sink(st, q);
        }
      }
      """;

  private static final String LONG3_C =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Long3C {
        public void sink(Statement st, String q) throws SQLException {
          new Long3D().sink(st, q);
        }
      }
      """;

  private static final String LONG3_D =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Long3D {
        public void sink(Statement st, String q) throws SQLException {
          st.executeQuery(q);
        }
      }
      """;

  @TempDir Path tmp;

  @Test
  void aParameterEveryCallSiteFillsWithALiteralIsNotASource() throws Exception {
    String dir = "a" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(dir + "/LiteralCaller.java", LITERAL_CALLER),
                translate(dir + "/LiteralSink.java", LITERAL_SINK)));

    assertThat(inFile(findings, "LiteralSink.java"))
        .as("the sink receives a literal at every call site, so there is nothing to report")
        .isEmpty();
  }

  @Test
  void aParameterEveryCallSiteFillsWithACleanVariableIsNotASource() throws Exception {
    String dir = "b" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(dir + "/CleanVarCaller.java", CLEAN_VAR_CALLER),
                translate(dir + "/CleanVarSink.java", CLEAN_VAR_SINK)));

    assertThat(inFile(findings, "CleanVarSink.java"))
        .as("the caller assigns the variable only literals, so the callee receives a clean value")
        .isEmpty();
  }

  @Test
  void theProofSurvivesATwoHopChainOfSameNamedMethods() throws Exception {
    String dir = "c" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(dir + "/ChainA.java", CHAIN_A),
                translate(dir + "/ChainB.java", CHAIN_B),
                translate(dir + "/ChainC.java", CHAIN_C)));

    assertThat(inFile(findings, "ChainC.java"))
        .as("the value reaches the sink through two helpers, and every hop passes it on clean")
        .isEmpty();
  }

  @Test
  void aVariableCopiedFromACleanVariableIsStillClean() throws Exception {
    String dir = "h" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(dir + "/CopyCaller.java", COPY_CALLER),
                translate(dir + "/CopySink.java", COPY_SINK)));

    assertThat(inFile(findings, "CopySink.java"))
        .as("a copy of a clean variable is a clean value, so the callee receives a clean value")
        .isEmpty();
  }

  @Test
  void theProofSurvivesAThreeHopChainBecauseTheFixedPointDrivesTheIteration() throws Exception {
    String dir = "i" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(dir + "/Long3A.java", LONG3_A),
                translate(dir + "/Long3B.java", LONG3_B),
                translate(dir + "/Long3C.java", LONG3_C),
                translate(dir + "/Long3D.java", LONG3_D)));

    assertThat(inFile(findings, "Long3D.java"))
        .as(
            "the third hop is only clean once the second hop is, and that takes a round of its"
                + " own -- so the clean fixed point has to drive the iteration")
        .isEmpty();
  }

  @Test
  void aParameterEveryCallSiteFillsWithALiteralIsNotASourceThroughAConcatenationEither()
      throws Exception {
    String dir = "g" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(dir + "/ConcatCaller.java", CONCAT_CALLER),
                translate(dir + "/ConcatSink.java", CONCAT_SINK)));

    assertThat(inFile(findings, "ConcatSink.java"))
        .as(
            "the sink receives the parameter through a concatenation, so the finding cannot be"
                + " suppressed at the sink -- only a parameter that never became a source keeps"
                + " the taint out of the program in the first place")
        .isEmpty();
  }

  @Test
  void aParameterThatReceivesATaintedValueIsStillASource() throws Exception {
    String dir = "d" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(dir + "/TaintedCaller.java", TAINTED_CALLER),
                translate(dir + "/TaintedSink.java", TAINTED_SINK)));

    List<Finding> inTheSink = inFile(findings, "TaintedSink.java");
    assertThat(inTheSink).as("a tainted argument must still be reported").hasSize(1);
    assertThat(inTheSink.get(0).dataflowHead())
        .as("the reported source is the caller's parameter")
        .contains("param:userInput");
  }

  @Test
  void aValueCopiedFromAParameterIsNotCleanSoTheCalleeStillReports() throws Exception {
    String dir = "e" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(dir + "/DirtyVarCaller.java", DIRTY_VAR_CALLER),
                translate(dir + "/DirtyVarSink.java", DIRTY_VAR_SINK)));

    assertThat(inFile(findings, "DirtyVarSink.java"))
        .as("the variable carries the caller's parameter, so it is not a clean value")
        .hasSize(1);
  }

  @Test
  void aParameterNobodyCallsIsStillASource() throws Exception {
    String dir = "f" + uid();
    List<Finding> findings = scan(List.of(translate(dir + "/UncalledSink.java", UNCALLED_SINK)));

    assertThat(inFile(findings, "UncalledSink.java"))
        .as("the entry boundary of a method nobody calls really is a trust boundary")
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
    // Mirror ScanRunner.runUnifiedCheckers' EngineConfig exactly. Using
    // ConfigLoader.loadFromClasspath() directly is NOT equivalent: the runner rebuilds the
    // config with the built-in fact kinds and the receiver-passthrough patterns, and the
    // taint checkers behave differently without them. A test that used a different config
    // would be asserting against a configuration no user ever runs.
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
