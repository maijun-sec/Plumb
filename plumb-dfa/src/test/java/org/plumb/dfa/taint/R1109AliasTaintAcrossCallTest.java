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
 * R1109: cross-function taint must survive an <em>alias</em> at the call boundary.
 *
 * <h2>Why the obvious test does not exist</h2>
 *
 * <p>The tempting case is "the helper's sink parameter is tainted but the caller's argument is
 * clean". That case cannot be written against this engine: {@code TaintedCheckerBase.initFunction}
 * unconditionally marks every parameter as a source, so a "clean" parameter does not exist. An
 * earlier draft of this test assumed one and produced a false failure that looked like an index
 * bug. Worth recording, because the failure mode reads exactly like a real defect.
 *
 * <h2>What alias reasoning can actually add here</h2>
 *
 * <p>Since all parameters are sources, the interesting question is not "which parameter" but "does
 * the fact reach the reference the sink reads <em>through a name the summary never mentions</em>".
 * The callee summarises its own parameter names; a caller that reaches the same object by a
 * different route — a field, a container element, or a plain copy — is invisible to that summary.
 *
 * <p>So the case below taints through a copy: the caller assigns the value to a local, then passes
 * it. The callee knows nothing about the caller's local, so the finding can only appear if taint
 * follows the value rather than the name.
 *
 * <p>The negative control is a sanitizer applied to the copy. If taint followed the name instead of
 * the value, the sanitized copy would still report.
 */
class R1109AliasTaintAcrossCallTest {

  @TempDir Path tmp;

  private static final String SINK_HELPER =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Helper {
        public void use(Statement st, String q) throws SQLException {
          st.executeQuery(q);
        }
      }
      """;

  /** Taint reaches the sink through a copy of the caller's parameter. */
  private static final String CALLER_TAINT_THROUGH_COPY =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Caller {
        public void go(Statement st, String userInput) throws SQLException {
          String copy = userInput;
          new Helper().use(st, copy);
        }
      }
      """;

  /** Taint reaches the sink through a field of the caller's parameter. */
  private static final String CALLER_TAINT_THROUGH_FIELD =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Caller {
        public void go(Statement st, String userInput) throws SQLException {
          new Holder(userInput).passOn(st);
        }
        public static class Holder {
          private final String payload;
          Holder(String payload) {
            this.payload = payload;
          }
          void passOn(Statement st) throws SQLException {
            new Helper().use(st, payload);
          }
        }
      }
      """;

  /**
   * The value is a literal at every call site, so no caller can be leaking attacker data.
   *
   * <p>This is the case the R1109 gate can actually prove. "The caller sanitised it" is not
   * decidable from {@code lab} alone — an unlabelled variable is unanalysed, not clean — so the
   * gate requires positive proof, and a literal is the only form available. A value derived from a
   * tainted one ({@code Integer.toString(input.length())}) is deliberately still reported, because
   * proving it clean would need a real sanitizer model, not an absent label.
   */
  private static final String CALLER_LITERAL =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Caller {
        public void go(Statement st) throws SQLException {
          new Helper().use(st, "SELECT 1");
        }
      }
      """;

  /**
   * The value is derived from the tainted input, so it stays a plausible carrier and keeps
   * reporting.
   *
   * <p>The control for {@link #literalArgumentAtEveryCallSiteIsNotReported}: it proves the gate is
   * not simply switching off every cross-file SQL finding.
   */
  private static final String CALLER_SANITIZED =
      """
      package mini;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Caller {
        public void go(Statement st, String userInput) throws SQLException {
          String copy = Integer.toString(userInput.length());
          new Helper().use(st, copy);
        }
      }
      """;

  private List<Finding> scan(String helper, String caller) throws Exception {
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
    String dir = "q" + uid();
    FindingReporter reporter =
        new DfaEngine(cfg, List.of(new SqlInjectionChecker()), "global", null)
            .runWithGlobalStore(
                List.of(
                    translate(dir + "/Helper.java", helper),
                    translate(dir + "/Caller.java", caller)),
                0);
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

  private static String uid() {
    return java.util.UUID.randomUUID().toString().substring(0, 8);
  }

  @Test
  void taintFollowsACopyOfTheCallersParameter() throws Exception {
    List<Finding> findings = scan(SINK_HELPER, CALLER_TAINT_THROUGH_COPY);
    assertThat(findings)
        .as(
            "the value is the same string; only the name differs from the caller's parameter."
                + " Origin attribution itself is R1099's subject and is asserted there.")
        .isNotEmpty();
  }

  @Test
  void taintFollowsAFieldOfTheCallersValue() throws Exception {
    List<Finding> findings = scan(SINK_HELPER, CALLER_TAINT_THROUGH_FIELD);
    assertThat(findings)
        .as("the tainted value reaches the sink through a field of a constructor argument")
        .isNotEmpty();
  }

  @Test
  void literalArgumentAtEveryCallSiteIsNotReported() throws Exception {
    List<Finding> findings = scan(SINK_HELPER, CALLER_LITERAL);
    assertThat(findings)
        .as(
            "the only caller passes a literal, so the callee's parameter cannot carry attacker"
                + " data. Reporting it means the entry-boundary seed was treated as real taint.")
        .isEmpty();
  }

  @Test
  void aValueDerivedFromTheTaintedInputKeepsReporting() throws Exception {
    List<Finding> findings = scan(SINK_HELPER, CALLER_SANITIZED);
    assertThat(findings)
        .as(
            "the passed value is computed from the tainted input. Absence of a taint label is not"
                + " proof of cleanliness, so this must still report -- the control proving the gate"
                + " is not suppressing on 'unlabelled' alone.")
        .isNotEmpty();
  }
}
