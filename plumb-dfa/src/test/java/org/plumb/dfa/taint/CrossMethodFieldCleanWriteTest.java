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
 * A field that another method overwrites with a literal: the caller that reads it sees the literal.
 *
 * <h2>Why the engine used to report it</h2>
 *
 * <p>A field read consults three answers, in order: the location-sensitive one, the coarse
 * per-function one, and the class-level one. The class-level answer names the CLASS rather than the
 * object, so it carries whatever ANY function wrote to that field -- including a taint written by a
 * method that ran earlier, or on another instance.
 *
 * <p>When the overwrite happens in the SAME function, the engine knows: it records a clean write
 * and the read consults it instead of the class-level answer. When the overwrite happens in ANOTHER
 * method, the engine had no way to know: the callee's summary recorded the field's tainted writes
 * but said nothing about its clean ones, and an absent entry is not a clean one. So the caller fell
 * back on the class-level answer and reported a taint that the callee's own write had removed.
 *
 * <h2>What is asserted</h2>
 *
 * <ul>
 *   <li>a CALLEE that overwrites the field with a literal &#8594; the caller's read is clean
 *       &#8594; NOT reported;
 *   <li>a CALLEE that overwrites the field with a TAINTED value &#8594; still reported (the clean
 *       rule must not fire for a write that carries taint);
 *   <li>no overwrite at all &#8594; the class-level answer applies &#8594; reported;
 *   <li>a tainted write in the caller AFTER the callee's clean write &#8594; reported (the later
 *       write is the one the field holds).
 * </ul>
 */
class CrossMethodFieldCleanWriteTest {

  /** The callee overwrites the field with a literal; the caller reads it afterwards. */
  private static final String CALLEE_CLEANS =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class CalleeCleans {
        private String value;
        void fill(String userInput) {
          this.value = userInput;
        }
        void clear() {
          this.value = "constant";
        }
        public void use(Statement st, String userInput) throws SQLException {
          this.fill(userInput);
          this.clear();
          st.executeQuery("SELECT * FROM t WHERE c = '" + this.value + "'");
        }
      }
      """;

  /** The callee overwrites the field with a TAINTED value: the field is dirty after the call. */
  private static final String CALLEE_TAINTED =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class CalleeTainted {
        private String value;
        void set(String userInput) {
          this.value = userInput;
        }
        public void use(Statement st, String userInput) throws SQLException {
          this.set(userInput);
          st.executeQuery("SELECT * FROM t WHERE c = '" + this.value + "'");
        }
      }
      """;

  /** Nobody overwrites the field: the class-level answer applies and the field is dirty. */
  private static final String NO_OVERWRITE =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class NoOverwrite {
        private String value;
        void fill(String userInput) {
          this.value = userInput;
        }
        public void use(Statement st, String userInput) throws SQLException {
          this.fill(userInput);
          st.executeQuery("SELECT * FROM t WHERE c = '" + this.value + "'");
        }
      }
      """;

  /** The caller writes the field with a tainted value AFTER the callee cleaned it. */
  private static final String CALLER_WRITES_AFTER =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class CallerWritesAfter {
        private String value;
        void clear() {
          this.value = "constant";
        }
        public void use(Statement st, String userInput) throws SQLException {
          this.clear();
          this.value = userInput;
          st.executeQuery("SELECT * FROM t WHERE c = '" + this.value + "'");
        }
      }
      """;

  @TempDir Path tmp;

  @Test
  void aFieldCleanedByACalleeIsCleanInTheCaller() throws Exception {
    String dir = "a" + uid();
    List<Finding> findings = scan(List.of(translate(dir + "/CalleeCleans.java", CALLEE_CLEANS)));

    assertThat(inFile(findings, "CalleeCleans.java"))
        .as(
            "the callee overwrote the field with a literal, so the field holds that literal when"
                + " the caller reads it -- the class-level answer names the class, not this object")
        .isEmpty();
  }

  @Test
  void aFieldWrittenWithATaintedValueByACalleeIsStillReported() throws Exception {
    String dir = "b" + uid();
    List<Finding> findings = scan(List.of(translate(dir + "/CalleeTainted.java", CALLEE_TAINTED)));

    assertThat(inFile(findings, "CalleeTainted.java"))
        .as("the callee wrote the tainted value into the field, so the read is dirty")
        .hasSize(1);
  }

  @Test
  void aFieldNobodyOverwritesIsStillReported() throws Exception {
    String dir = "c" + uid();
    List<Finding> findings = scan(List.of(translate(dir + "/NoOverwrite.java", NO_OVERWRITE)));

    assertThat(inFile(findings, "NoOverwrite.java"))
        .as("nothing overwrote the field, so the write that did happen still stands")
        .hasSize(1);
  }

  @Test
  void aTaintedWriteInTheCallerAfterTheCalleesCleanWriteIsStillReported() throws Exception {
    String dir = "d" + uid();
    List<Finding> findings =
        scan(List.of(translate(dir + "/CallerWritesAfter.java", CALLER_WRITES_AFTER)));

    assertThat(inFile(findings, "CallerWritesAfter.java"))
        .as(
            "the caller's own write comes after the callee's clean write, so the field holds the"
                + " tainted value -- the later write is the one the field holds")
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
