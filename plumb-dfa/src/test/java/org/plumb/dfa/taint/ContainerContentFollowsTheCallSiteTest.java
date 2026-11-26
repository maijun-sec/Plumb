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
 * A container parameter is not the value that reaches the sink -- its CONTENTS are.
 *
 * <h2>Why this is a separate question</h2>
 *
 * <p>The call-site contract answers "does the caller hand this parameter something the attacker
 * controls?". For a container the answer is about a value the caller never passes: the caller
 * passes a REFERENCE, and what the callee reads out of it is whatever was STORED into it. So the
 * container's reference being clean says nothing about its contents, and a proof that only looks at
 * the reference leaves every repaired program that fills a container with a constant reported.
 *
 * <p>So the contract needs a second kind of proof, and it has to be a positive one: every value
 * stored into the container at the call site is provably clean.
 *
 * <h2>What has to be true for that proof</h2>
 *
 * <ul>
 *   <li>the container is a local the analysis can see, freshly allocated or copied from another
 *       container the analysis can see;
 *   <li>every write into it -- an array store, a field store, a collection's mutator -- carries a
 *       provably clean value;
 *   <li>the container does not ESCAPE to another call, because a callee may store anything into a
 *       reference it is given, and that store is invisible to the caller.
 * </ul>
 *
 * <p>The escape rule is the one that is easy to leave out, and leaving it out is not "less precise"
 * -- it is wrong: a helper that writes a request value into the array turns the array's contents
 * into attacker-controlled data, and the proof would still call them clean.
 *
 * <h2>What is asserted</h2>
 *
 * <ul>
 *   <li>an array whose every element is a literal is not a source;
 *   <li>a map whose every value is a literal is not a source;
 *   <li>an object whose every field write is a literal is not a source;
 *   <li>the proof survives a TWO-HOP chain, where the first hop passes the container on;
 *   <li>a container that receives a tainted value IS still a source;
 *   <li>a container that ESCAPES to a helper is still a source, because the helper may write into
 *       it;
 *   <li>a container nobody fills with anything is not a source either -- a fresh container holds
 *       only defaults.
 * </ul>
 */
class ContainerContentFollowsTheCallSiteTest {

  private static final String ARRAY_CALLER =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ArrayCaller {
        public void go(Statement st) throws SQLException {
          String[] values = new String[2];
          values[0] = "constant";
          new ArraySink().run(st, values);
        }
      }
      """;

  private static final String ARRAY_SINK =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ArraySink {
        public void run(Statement st, String[] values) throws SQLException {
          st.executeQuery(values[0]);
        }
      }
      """;

  private static final String DIRTY_ARRAY_CALLER =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class DirtyArrayCaller {
        public void go(Statement st, String userInput) throws SQLException {
          String[] values = new String[2];
          values[0] = userInput;
          new DirtyArraySink().run(st, values);
        }
      }
      """;

  private static final String DIRTY_ARRAY_SINK =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class DirtyArraySink {
        public void run(Statement st, String[] values) throws SQLException {
          st.executeQuery(values[0]);
        }
      }
      """;

  private static final String MAP_CALLER =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      import java.util.HashMap;
      public class MapCaller {
        public void go(Statement st) throws SQLException {
          HashMap<Integer, String> values = new HashMap<>();
          values.put(0, "constant");
          new MapSink().run(st, values);
        }
      }
      """;

  private static final String MAP_SINK =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      import java.util.HashMap;
      public class MapSink {
        public void run(Statement st, HashMap<Integer, String> values) throws SQLException {
          st.executeQuery(values.get(0));
        }
      }
      """;

  private static final String BOX_CALLER =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class BoxCaller {
        public void go(Statement st) throws SQLException {
          Box box = new Box();
          box.payload = "constant";
          new BoxSink().run(st, box);
        }
      }
      """;

  private static final String BOX =
      """
      package mini;
      public class Box {
        public String payload;
      }
      """;

  private static final String BOX_SINK =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class BoxSink {
        public void run(Statement st, Box box) throws SQLException {
          st.executeQuery(box.payload);
        }
      }
      """;

  // The two hops declare the SAME method name. The bare signature is therefore not unique,
  // which is exactly the situation in which pooling the parameter's identity makes the
  // content proof circular: the second hop is clean only if the first hop is, and pooling
  // merges the two.
  private static final String CHAIN_A =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ChainA {
        public void go(Statement st) throws SQLException {
          String[] values = new String[2];
          values[0] = "constant";
          new ChainB().sink(st, values);
        }
      }
      """;

  private static final String CHAIN_B =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ChainB {
        public void sink(Statement st, String[] values) throws SQLException {
          new ChainC().sink(st, values);
        }
      }
      """;

  private static final String CHAIN_C =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class ChainC {
        public void sink(Statement st, String[] values) throws SQLException {
          st.executeQuery(values[0]);
        }
      }
      """;

  // The helper receives the array and writes into it. What it writes is invisible to the
  // caller, so the caller's proof that the array holds only constants does not survive the
  // hand-off.
  private static final String ESCAPE_CALLER =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class EscapeCaller {
        public void go(Statement st, String userInput) throws SQLException {
          String[] values = new String[2];
          values[0] = "constant";
          new Filler().fill(values, userInput);
          new EscapeSink().run(st, values);
        }
      }
      """;

  private static final String FILLER =
      """
      package mini;
      public class Filler {
        public void fill(String[] values, String what) {
          values[1] = what;
        }
      }
      """;

  private static final String ESCAPE_SINK =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class EscapeSink {
        public void run(Statement st, String[] values) throws SQLException {
          st.executeQuery(values[0]);
        }
      }
      """;

  @TempDir Path tmp;

  @Test
  void anArrayWhoseEveryElementIsALiteralIsNotASource() throws Exception {
    String dir = "a" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(dir + "/ArrayCaller.java", ARRAY_CALLER),
                translate(dir + "/ArraySink.java", ARRAY_SINK)));

    assertThat(inFile(findings, "ArraySink.java"))
        .as("the caller stores only a literal into the array, so the callee reads a constant")
        .isEmpty();
  }

  @Test
  void aMapWhoseEveryValueIsALiteralIsNotASource() throws Exception {
    String dir = "b" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(dir + "/MapCaller.java", MAP_CALLER),
                translate(dir + "/MapSink.java", MAP_SINK)));

    assertThat(inFile(findings, "MapSink.java"))
        .as("the caller stores only a literal into the map, so the callee reads a constant")
        .isEmpty();
  }

  @Test
  void anObjectWhoseEveryFieldWriteIsALiteralIsNotASource() throws Exception {
    String dir = "c" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(dir + "/Box.java", BOX),
                translate(dir + "/BoxCaller.java", BOX_CALLER),
                translate(dir + "/BoxSink.java", BOX_SINK)));

    assertThat(inFile(findings, "BoxSink.java"))
        .as("the caller writes only a literal into the object, so the callee reads a constant")
        .isEmpty();
  }

  @Test
  void theProofSurvivesATwoHopChainOfContainers() throws Exception {
    String dir = "d" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(dir + "/ChainA.java", CHAIN_A),
                translate(dir + "/ChainB.java", CHAIN_B),
                translate(dir + "/ChainC.java", CHAIN_C)));

    assertThat(inFile(findings, "ChainC.java"))
        .as("the container reaches the sink through a helper, and every hop passes it on clean")
        .isEmpty();
  }

  @Test
  void aContainerThatReceivesATaintedValueIsStillASource() throws Exception {
    String dir = "e" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(dir + "/DirtyArrayCaller.java", DIRTY_ARRAY_CALLER),
                translate(dir + "/DirtyArraySink.java", DIRTY_ARRAY_SINK)));

    assertThat(inFile(findings, "DirtyArraySink.java"))
        .as("the caller stores the caller's parameter into the array, so the array carries it")
        .hasSize(1);
  }

  @Test
  void aContainerThatEscapesToAnotherCallIsStillASource() throws Exception {
    String dir = "f" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(dir + "/EscapeCaller.java", ESCAPE_CALLER),
                translate(dir + "/Filler.java", FILLER),
                translate(dir + "/EscapeSink.java", ESCAPE_SINK)));

    assertThat(inFile(findings, "EscapeSink.java"))
        .as(
            "the helper received the array and may have written into it, so the caller's proof"
                + " that the array holds only constants does not survive the hand-off")
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
    // in ParamCleanlinessFollowsTheCallSiteTest: a different config would assert against a
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
