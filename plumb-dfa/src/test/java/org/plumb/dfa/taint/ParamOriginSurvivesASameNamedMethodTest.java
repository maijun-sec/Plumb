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
 * The parameter origin has to survive a program that declares the same method name twice.
 *
 * <h2>Why this matters</h2>
 *
 * <p>Every function's entry boundary seeds its parameters with a {@code param:<name>} label, so a
 * finding inside a sink method reports <em>that method's</em> parameter unless the engine recovers
 * the origin its callers pass in. A recovered origin is what tells a reader "the value came from
 * the caller's parameter" apart from "the taint never crossed the call boundary" — the two are
 * otherwise byte-for-byte indistinguishable in the report.
 *
 * <p>Recovering it needs the call site to be matched with the callee. That match has three
 * spellings to get right, and a program with ONE class cannot tell them apart:
 *
 * <ul>
 *   <li>the class the call site names. A declaration names its class with the package it lives in
 *       ("mini.SinkA"), while a call site in the source lane can only name it the way the source
 *       wrote it ("SinkA"). The lookup has to answer to both spellings;
 *   <li>the class of an unqualified call. An unqualified call is a call to a method of the CALLER's
 *       own class — that is what an unqualified call means — so the caller knows the class the
 *       lookup has to ask under;
 *   <li>where the first USER argument sits. A call that has a receiver carries it in the first
 *       operand, and a call that names no class does not, so the offset cannot be assumed.
 * </ul>
 *
 * <p>This test therefore uses a program where the bare "name/arity" key is NOT unique: a second
 * class declares the same method. With a unique key the lookup short-circuits on the bare name and
 * none of the three spellings is exercised, which is why the single-class version of this gate
 * passes whether the lookup is right or not.
 *
 * <h2>What is asserted</h2>
 *
 * <ul>
 *   <li>the reported source is the CALLER's parameter, not the sink method's own;
 *   <li>the finding is the one the called class produced, not the same-named method of another
 *       class;
 *   <li>the finding is still reported exactly once.
 * </ul>
 */
class ParamOriginSurvivesASameNamedMethodTest {

  private static final String ORIG =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Orig {
        public void go(Statement st, String userInput) throws SQLException {
          new SinkA().run(st, userInput);
        }
      }
      """;

  private static final String SINK_A =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class SinkA {
        public void run(Statement st, String q) throws SQLException {
          st.executeQuery(q);
        }
      }
      """;

  private static final String SINK_B =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class SinkB {
        public void run(Statement st, String q) throws SQLException {
          st.executeQuery(q);
        }
      }
      """;

  /** A caller whose sink method it calls by its OWN name, with no receiver and no class. */
  private static final String UNQUALIFIED =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Unqualified {
        public void run(Statement st, String q) throws SQLException {
          st.executeQuery(q);
        }
        public void go(Statement st, String userInput) throws SQLException {
          run(st, userInput);
        }
      }
      """;

  @TempDir Path tmp;

  @Test
  void theCallersParameterIsTheSourceWhenAnotherClassDeclaresTheSameMethod() throws Exception {
    String dir = "a" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(dir + "/Orig.java", ORIG),
                translate(dir + "/SinkA.java", SINK_A),
                translate(dir + "/SinkB.java", SINK_B)));

    // The other class declares the same method, so it has a sink site of its own. Its
    // parameter is an entry boundary nobody calls with anything, so it reports its OWN
    // parameter -- which is honest, and is why the finding under test is picked by FILE.
    List<Finding> inTheCalledClass = inFile(findings, "SinkA.java");

    assertThat(inTheCalledClass).as("the SQL sink of the called class must be detected").hasSize(1);

    String head = inTheCalledClass.get(0).dataflowHead();
    assertThat(head)
        .as(
            "the reported source must be the CALLER's parameter (userInput), not the sink method's"
                + " own parameter (q)")
        .contains("param:userInput")
        .doesNotContain("param:q");
  }

  @Test
  void anUnqualifiedCallTransfersTheOriginToo() throws Exception {
    String dir = "b" + uid();
    List<Finding> findings =
        scan(
            List.of(
                translate(dir + "/Unqualified.java", UNQUALIFIED),
                translate(dir + "/SinkA.java", SINK_A),
                translate(dir + "/SinkB.java", SINK_B)));

    List<Finding> inTheCaller = inFile(findings, "Unqualified.java");

    assertThat(inTheCaller).as("the SQL sink of the calling class must be detected").hasSize(1);

    String head = inTheCaller.get(0).dataflowHead();
    assertThat(head)
        .as(
            "an unqualified call names no class and carries no receiver, so its first operand is"
                + " the first USER argument; reading the operand after it would report the sink"
                + " method's own parameter")
        .contains("param:userInput")
        .doesNotContain("param:q");
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
