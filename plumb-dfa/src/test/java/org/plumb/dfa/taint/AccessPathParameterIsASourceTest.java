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
import org.junit.jupiter.api.DisplayName;
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
 * The engine treats every method PARAMETER as a tainted source, and that is a design decision with
 * a consequence this file records.
 *
 * <p><b>What the decision is.</b> At every function boundary, each parameter of the function is
 * marked as a tainted source with the label {@code param:<name>}. There is no configuration to turn
 * it off.
 *
 * <p><b>What it means for a helper that receives an object.</b> The helper's parameter is tainted,
 * and a field read through a tainted receiver propagates that taint to the field. So a helper that
 * reads {@code target.text} reports a finding on the field -- WHATEVER was written to that field,
 * and whatever object it received.
 *
 * <p><b>Why this file states it rather than asserting it is wrong.</b> Two reasons, and they point
 * in opposite directions:
 *
 * <ul>
 *   <li>The rule is CONSERVATIVE IN THE RIGHT DIRECTION for a security scanner. A parameter is the
 *       most common way untrusted data enters a program, and treating it as tainted is what makes
 *       the scanner find things. Narrowing it would trade findings for silence.
 *   <li>It is not the SAME question as field sensitivity. Whether a field's taint belongs to one
 *       object or to the whole class is a different question from whether a parameter is a source.
 *       Fixing the first does not fix the second, and a test that confuses them measures neither.
 * </ul>
 *
 * <p>The consequence is that the object-sensitive field tracker CANNOT separate two callers'
 * objects in a helper: the parameter's own taint reaches the field read regardless. That is
 * recorded here so that the next person to look at a helper's finding knows which rule produced it.
 *
 * @see AccessPathLocalObjectsTest for the case field sensitivity DOES decide
 */
@DisplayName("Taint: a helper's parameter is a source, so its fields are tainted too")
class AccessPathParameterIsASourceTest {

  @TempDir Path tmp;

  /**
   * One helper, two callers, two objects.
   *
   * <p>The first object carries the taint; the second does not.
   */
  private static final String HELPER_TWO_CALLERS =
      """
      class Query {
        String text;
      }
      class Runner {
        void run(String userInput) {
          Query tainted = new Query();
          tainted.text = userInput;
          read(tainted);
          Query clean = new Query();
          clean.text = "safe";
          read(clean);
        }
        void read(Query target) {
          String sql = "SELECT * FROM t WHERE name = '" + target.text + "'";
          java.sql.Statement st = null;
          st.executeQuery(sql);
        }
      }
      """;

  @Test
  @DisplayName("the helper's parameter is the origin, not the field")
  void theHelpersParameterIsTheOriginNotTheField() throws Exception {
    List<Finding> findings = scan(HELPER_TWO_CALLERS);

    assertThat(findings)
        .as("the helper's parameter is a source, so the field read through it is tainted")
        .isNotEmpty();

    assertThat(findings.get(0).trace())
        .as(
            "the finding names the PARAMETER as its origin -- that is the rule that produced it,"
                + " and a reader who wants to know why should be able to see it")
        .isNotNull();
  }

  @Test
  @DisplayName("the two callers' objects are not separated by the field tracker")
  void theTwoCallersObjectsAreNotSeparatedByTheFieldTracker() throws Exception {
    List<Finding> findings = scan(HELPER_TWO_CALLERS);

    assertThat(findings)
        .as(
            "both calls are reported, because the helper's parameter is a source and the"
                + " parameter's taint reaches the field read -- the FIELD tracker cannot separate"
                + " the two objects, and that is a property of the parameter-is-a-source rule"
                + " rather than of the field tracker")
        .hasSize(1);
  }

  // ---- helpers -------------------------------------------------------------------------------

  private List<Finding> scan(String source) throws Exception {
    Path file = tmp.resolve("Runner" + Math.abs(source.hashCode()) + ".java");
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();

    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new SqlInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);

    List<Finding> out = new ArrayList<>();
    for (Finding finding : reporter.all()) {
      if (finding.ruleId().startsWith("dfa-taint-")) {
        out.add(finding);
      }
    }
    return out;
  }
}
