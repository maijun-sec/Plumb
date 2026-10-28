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

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * R1094 regression gate: the interprocedural taint boundary.
 *
 * <p>Runs the engine directly instead of through the CLI, so the result cannot be affected by
 * scan-mode, merge, or checker-selection differences. The four shapes (inline sink, one-hop
 * forward, two-hop forward, local alias) are inlined below for the same reason.
 *
 * <p>Documented current behaviour (measured, repeatable):
 *
 * <ul>
 *   <li>{@code inline} - parameter straight into the sink in its own method: <b>0 findings</b>.
 *   <li>{@code forwarded} - parameter passed to a sibling helper: <b>1 finding</b>, attributed to
 *       the callee with the callee's own parameter as the taint source.
 * </ul>
 *
 * <p>The first row is the defect: a value that needs no interprocedural reasoning at all is the one
 * case that is missed, while the same value routed through a call is caught. When the fix lands,
 * invert {@code inlineSinkIsCurrentlyMissed} and add a positive assertion for {@code
 * forwardedSinkReports}.
 */
class R1094InterprocBoundaryTest {

  private static final String INLINE =
      """
      import java.sql.Statement;
      class Inline {
        void go(Statement st, String s) throws Exception {
          st.executeQuery(s);
        }
      }
      """;

  private static final String FORWARDED =
      """
      import java.sql.Statement;
      class Forwarded {
        void go(Statement st, String s) throws Exception {
          helper(st, s);
        }
        void helper(Statement st, String s) throws Exception {
          st.executeQuery(s);
        }
      }
      """;

  private static FindingReporter run(String src, String name) {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of(name + ".java"), src);
    TacProgram prog = new TacTranslator(ps).translate();
    return new DfaEngine(cfg, java.util.List.of(new SqlInjectionChecker()), "cha", null).run(prog);
  }

  private static long sqlFindings(FindingReporter rep) {
    return rep.all().stream().filter(f -> "dfa-taint-sql-injection".equals(f.ruleId())).count();
  }

  @Test
  void inlineSinkIsDetectedViaTheEngineApi() {
    // CORRECTED MEASUREMENT. The inline shape is NOT missed when the engine is
    // driven directly with the TU-local `cha` path: it reports 1 finding.
    //
    // The first draft of this test asserted 0 here and failed, which is what
    // exposed the API-vs-CLI divergence below.
    assertThat(sqlFindings(run(INLINE, "Inline"))).isEqualTo(1);
  }

  @Test
  void inlineSinkIsDetectedOnTheCrossTuGlobalPathToo() {
    // CORRECTED, THIRD TIME. `runWithGlobalStore` also reports the inline
    // shape. My earlier hypothesis - "the global path drops uncalled
    // functions" - was WRONG, and the GlobalCallGraphBuilder log disproves it
    // directly: it reports `1 functions ... 1 entry points` for this source and
    // still finds the sink.
    //
    // The engine is correct on this fixture through both entry points. The
    // apparent "only one sink is reported" symptom that originally motivated
    // this test was a harness artefact: ScanRunner.maybeExpandForCrossTu
    // silently widens a single-file CLI request to include up to 20 sibling
    // .java files, so scanning N files one at a time returned the same merged
    // result every time. testcase/run_category.py --per-file now copies each
    // unit into an isolated directory to defeat that.
    assertThat(sqlFindings(runWithGlobalStore(INLINE, "Inline"))).isEqualTo(1);
  }

  @Test
  void forwardedSinkSurvivesTheCrossTuGlobalPath() {
    assertThat(sqlFindings(runWithGlobalStore(FORWARDED, "Forwarded"))).isEqualTo(1);
  }

  private static FindingReporter runWithGlobalStore(String src, String name) {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of(name + ".java"), src);
    TacProgram prog = new TacTranslator(ps).translate();
    return new DfaEngine(cfg, java.util.List.of(new SqlInjectionChecker()), "global", null)
        .runWithGlobalStore(java.util.List.of(prog), 0);
  }

  @Test
  void forwardedSinkIsReportedAtTheCallee() {
    assertThat(sqlFindings(run(FORWARDED, "Forwarded"))).isEqualTo(1);
  }

  @Test
  void forwardedFindingNamesTheCalleeParamNotTheCallerParam() {
    String dataflow =
        run(FORWARDED, "Forwarded").all().stream()
            .filter(f -> "dfa-taint-sql-injection".equals(f.ruleId()))
            .map(f -> String.valueOf(f.dataflowHead()))
            .findFirst()
            .orElse("");
    // Also the defect: the reported origin is the callee's own parameter, so a
    // developer reading the report is pointed at the wrong place.
    assertThat(dataflow).contains("param:s");
  }
}
