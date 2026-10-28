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
 * R1099 regression gate: a cross-file taint finding must name the CALLER's parameter as the taint
 * source, not the sink method's own parameter.
 *
 * <h2>Why this matters</h2>
 *
 * <p>Every function's entry boundary seeds its parameters with a {@code param:<name>} label. When a
 * sink lived in its own method, the emitted finding therefore reported <em>that method's</em>
 * parameter as the source — which is byte-for-byte indistinguishable from "taint never crossed the
 * call boundary". That ambiguity is what made cross-file taint analysis look absent when it was in
 * fact propagating correctly.
 *
 * <p>{@link org.plumb.dfa.engine.DfaEngine#computeParamOrigins} now pre-computes, for every
 * parameter, the origin its callers actually pass in, and the taint checker publishes that origin
 * at emit time when one is available.
 *
 * <h2>What is asserted</h2>
 *
 * <ul>
 *   <li>the sink is still reported exactly once (no regression in detection);
 *   <li>the reported source is the caller's parameter, not the callee's;
 *   <li>a single-file program with no caller keeps the honest {@code param:} label, since there is
 *       genuinely no upstream provenance to report.
 * </ul>
 */
class R1099CallerOriginAttributionTest {

  private static final String ORIG =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Orig {
        public void go(Statement st, String userInput) throws SQLException {
          new Sink().run(st, userInput);
        }
      }
      """;

  private static final String SINK =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Sink {
        public void run(Statement st, String q) throws SQLException {
          st.executeQuery(q);
        }
      }
      """;

  private static final String SOLO =
      """
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Solo {
        public void go(Statement st, String onlyInput) throws SQLException {
          st.executeQuery(onlyInput);
        }
      }
      """;

  @TempDir Path tmp;

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

  @Test
  void crossFileSinkNamesTheCallersParameterNotItsOwn() throws Exception {
    // Both files must live in the SAME directory: the cross-file resolution this test
    // exercises keys on the package/enclosing class, and splitting them into two
    // directories makes them two unrelated single-file programs.
    String dir = "a" + uid();
    List<Finding> findings =
        scan(List.of(translate(dir + "/Orig.java", ORIG), translate(dir + "/Sink.java", SINK)));

    assertThat(findings).as("cross-file SQL sink must still be detected").hasSize(1);

    String head = findings.get(0).dataflowHead();
    assertThat(head)
        .as(
            "the reported source must be the CALLER's parameter (userInput), not the sink"
                + " method's own parameter (q). Reporting `param:q` is what made cross-file"
                + " taint indistinguishable from a propagation failure.")
        .contains("param:userInput")
        .doesNotContain("param:q");
  }

  @Test
  void singleFileWithNoCallerKeepsItsOwnParameterLabel() throws Exception {
    List<Finding> findings = scan(List.of(translate("b" + uid() + "/Solo.java", SOLO)));

    assertThat(findings).hasSize(1);
    // No caller exists, so there is no upstream provenance to report. The honest label
    // (the method's own parameter) must be preserved rather than invented.
    assertThat(findings.get(0).dataflowHead()).contains("param:onlyInput");
  }

  @Test
  void detectionIsUnaffectedByTheAttributionChange() throws Exception {
    // Guards against a future "fix" that silences findings instead of re-attributing them.
    String dir = "c" + uid();
    List<Finding> crossFile =
        scan(List.of(translate(dir + "/Orig.java", ORIG), translate(dir + "/Sink.java", SINK)));
    assertThat(crossFile).hasSize(1);
    assertThat(crossFile.get(0).severity()).isNotNull();
  }

  /** Per-call suffix so each test gets its own package, keeping @TempDir dirs independent. */
  private static String uid() {
    return java.util.UUID.randomUUID().toString().substring(0, 8);
  }
}
