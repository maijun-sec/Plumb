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
import org.plumb.dfa.config.ArgumentPassthrough;
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
 * The ARGUMENT → RESULT shape: a method whose result is built from one of its ARGUMENTS.
 *
 * <p><b>Why the shape needs its own table.</b> The receiver table says "the RECEIVER's taint
 * reaches the result". That is a different proposition, and it does not cover the methods that
 * build their result from an argument: {@code path.resolve(userInput)} returns a path built from
 * the argument, not from the receiver. There was no place in the model for that, so a rule stating
 * it could only be counted, never applied -- and the taint stopped at the call.
 *
 * <p><b>Why the rule is a table and not a fact.</b> Applied unconditionally, every method would
 * pass its argument's taint on and every result would be tainted. The table says WHICH method and
 * WHICH argument, so each inference is reviewable on its own. The negative case below is the
 * control: a method the table does not name must NOT carry the taint.
 *
 * <p><b>Why the word boundary is pinned.</b> The patterns are matched with {@code find()}, not
 * {@code matches()}. A rule for {@code Path.resolve} would therefore also match {@code
 * Path.resolveSibling} without a boundary, and the table would say a method it says nothing about
 * carries the taint.
 */
class ArgumentPassthroughTest {

  @TempDir Path tmp;

  /** The result of {@code resolve} is built from the argument, so the taint crosses. */
  private static final String RESOLVE =
      """
      package mini;
      import java.nio.file.Path;
      import java.nio.file.Paths;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Caller {
        public void go(Statement st, String userInput) throws SQLException {
          Path base = Paths.get("/srv");
          Path resolved = base.resolve(userInput);
          st.executeQuery(resolved.toString());
        }
      }
      """;

  /** The control: a method the table does not name must not carry the taint. */
  private static final String RESOLVE_SIBLING =
      """
      package mini;
      import java.nio.file.Path;
      import java.nio.file.Paths;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Caller {
        public void go(Statement st, String userInput) throws SQLException {
          Path base = Paths.get("/srv");
          Path resolved = base.resolveSibling(userInput);
          st.executeQuery(resolved.toString());
        }
      }
      """;

  /** The table is loaded, and it says which argument carries the taint. */
  @Test
  @DisplayName("the table is loaded and names the argument position")
  void theTableIsLoadedAndNamesTheArgumentPosition() {
    ArgumentPassthrough table = ConfigLoader.loadFromClasspath().argumentPassthrough();

    assertThat(table.size()).as("the argument-passthrough table must not be empty").isPositive();
    assertThat(table.argIndexesFor("java.nio.file.Path.resolve/1"))
        .as("the rule for Path.resolve names its first user argument")
        .containsExactly(0);
  }

  /** A rule for one method must not match the longer name that starts with it. */
  @Test
  @DisplayName("a rule for one method does not match the longer name that starts with it")
  void aRuleForOneMethodDoesNotMatchTheLongerName() {
    ArgumentPassthrough table = ConfigLoader.loadFromClasspath().argumentPassthrough();

    assertThat(table.argIndexesFor("java.nio.file.Path.resolveSibling/1"))
        .as("resolveSibling is not resolve, and the table must not say it is")
        .isEmpty();
  }

  /** The user argument is at args[1] on an instance call: the receiver holds args[0]. */
  @Test
  @DisplayName("the user argument is resolved past the receiver slot")
  void theUserArgumentIsResolvedPastTheReceiverSlot() {
    ArgumentPassthrough table = ConfigLoader.loadFromClasspath().argumentPassthrough();

    assertThat(table.tacArgIndexesFor("java.nio.file.Path.resolve/1", List.of("recv", "arg")))
        .as("an instance call keeps the receiver at args[0], so user argument 0 is args[1]")
        .containsExactly(1);
  }

  /** The taint reaches the sink through a result built from the argument. */
  @Test
  @DisplayName("the taint reaches the sink through a result built from the argument")
  void theTaintReachesTheSinkThroughAResultBuiltFromTheArgument() throws Exception {
    List<Finding> findings = scan(RESOLVE);

    assertThat(findings)
        .as(
            "the engine has no summary for Path.resolve, so without the argument rule the"
                + " result is clean and the taint stops at the call -- and the finding goes"
                + " missing while the scan reports success")
        .isNotEmpty();
  }

  /** The control: a method the table does not name carries nothing. */
  @Test
  @DisplayName("a method the table does not name carries nothing")
  void aMethodTheTableDoesNotNameCarriesNothing() throws Exception {
    List<Finding> findings = scan(RESOLVE_SIBLING);

    assertThat(findings)
        .as(
            "resolveSibling is not in the table, so the taint must not cross it. A finding here"
                + " means the table is applied to every method rather than to the ones it names")
        .isEmpty();
  }

  /**
   * Runs the SQL taint checker over one source file.
   *
   * @param source the Java source
   * @return the findings the checker reported
   */
  private List<Finding> scan(String source) throws Exception {
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
            base.receiverPassthroughPatterns(),
            base.languageSemantics(),
            base.argumentPassthrough());
    FindingReporter reporter =
        new DfaEngine(cfg, List.of(new SqlInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(translate(source)), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if ("dfa-taint-sql-injection".equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  private TacProgram translate(String source) throws Exception {
    Path file = tmp.resolve("Caller.java");
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    return new TacTranslator(ps).translate();
  }
}
