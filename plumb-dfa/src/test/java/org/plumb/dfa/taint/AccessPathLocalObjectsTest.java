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
 * Two objects of one class are two locations for the engine's field taint.
 *
 * <p><b>What this measures, and what it does not.</b> The engine treats every method PARAMETER as a
 * tainted source, so a helper that receives an object reports a finding on that object's fields
 * whatever was written to them. That is a separate design question from field sensitivity, and a
 * test that used a parameter as the carrier would measure that rule instead of this one.
 *
 * <p>So the carriers here are LOCALS. A local is not a source, so the only thing that can make its
 * field tainted is a write to that field -- and the only thing that can make the field of another
 * object tainted is a write to THAT field.
 *
 * <p>The two objects are the same class. The only thing that tells them apart is where they were
 * allocated, which is what the access path's root carries.
 */
@DisplayName("Taint: two local objects are two locations for field taint")
class AccessPathLocalObjectsTest {

  @TempDir Path tmp;

  /**
   * One function, two objects, one field written on the first and read on the second.
   *
   * <p>The taint enters through a field of a source class, so the flow is a FIELD flow and not a
   * parameter flow.
   */
  private static final String TWO_LOCAL_OBJECTS =
      """
      class Query {
        String text;
      }
      class Payload {
        String userInput;
      }
      class Runner {
        void run(Payload payload) {
          Query first = new Query();
          Query second = new Query();
          first.text = payload.userInput;
          String sql = "SELECT * FROM t WHERE name = '" + second.text + "'";
          java.sql.Statement st = null;
          st.executeQuery(sql);
        }
      }
      """;

  @Test
  @DisplayName("a field written on one object does not taint another object's field")
  void aFieldWrittenOnOneObjectDoesNotTaintAnotherObjectsField() throws Exception {
    List<Finding> findings = scan(TWO_LOCAL_OBJECTS);

    assertThat(findings)
        .as(
            "the taint was written to the FIRST object's field and read from the SECOND one's --"
                + " two objects are two locations, so the second field is clean")
        .isEmpty();
  }

  @Test
  @DisplayName("the same object's field does carry the taint to the sink")
  void theSameObjectsFieldDoesCarryTheTaintToTheSink() throws Exception {
    List<Finding> findings =
        scan(
            """
            class Query {
              String text;
            }
            class Payload {
              String userInput;
            }
            class Runner {
              void run(Payload payload) {
                Query first = new Query();
                first.text = payload.userInput;
                String sql = "SELECT * FROM t WHERE name = '" + first.text + "'";
                java.sql.Statement st = null;
                st.executeQuery(sql);
              }
            }
            """);

    assertThat(findings)
        .as(
            "the SAME object's field carries the taint into the SQL statement -- without this the"
                + " previous test would pass for the wrong reason")
        .isNotEmpty();
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
