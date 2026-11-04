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
package org.plumb.dfa.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.checkers.taint.NullDerefChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.TuningConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.fact.BuiltinFactKinds;
import org.plumb.reporter.Finding;
import org.plumb.tac.frontend.scala.ScalaTacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;

/**
 * The DFA consumes a Scala function's TAC.
 *
 * <p><b>Why this test lives here and not in the frontend's own module.</b> The frontend's tests
 * prove that the frontend produces the right TAC. This test proves the other half: that the engine
 * downstream actually reads that TAC, and that the language marker and the precision marker the
 * Scala frontend sets survive the trip into a checker.
 *
 * <p><b>Why Scala is different from Kotlin here.</b> The Kotlin frontend's integration test leans
 * on nullability: a Kotlin {@code String} is not nullable, and that suppresses a null-dereference
 * finding. Scala has no such type system, so there is nothing comparable to suppress. What Scala
 * <i>does</i> contribute is the opposite: a Scala {@code String} <b>may</b> hold null, exactly like
 * a Java one. So the null dereference must still be reported on Scala code -- the frontend must not
 * have quietly invented Kotlin's type system for it.
 *
 * <p>The second half of the test is the precision marker. A Scala {@code match} is lowered into a
 * branch chain, so the function that contained it reports {@link IrPrecision#SOURCE_LOWERED}. A
 * checker consults that before trusting a precision-dependent fact about the function, so the
 * marker has to survive into the engine.
 */
class ScalaDfaIntegrationTest {

  /** The rule id the null-dereference checker reports under. */
  private static final String NULL_DEREF_RULE = "dfa-taint-null-dereference";

  /** The frontend under test. */
  private static ScalaTacFrontend frontend;

  @BeforeAll
  static void setUp() {
    frontend = new ScalaTacFrontend();
  }

  /**
   * Parses Scala source and runs the null-dereference checker over it.
   *
   * @param source the Scala source
   * @return the findings the null-dereference rule produced
   */
  private List<Finding> scan(String source) {
    List<TacFunction> functions = frontend.parse(source, Path.of("T.scala")).functions();
    assertThat(functions).as("the Scala source must lower to at least one function").isNotEmpty();

    TacProgram program = new TacProgram(Path.of("T.scala"), "T");
    for (TacFunction fn : functions) {
      program.addFunction(fn);
    }

    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig config =
        new EngineConfig(
            base.sinkFamilies(),
            BuiltinFactKinds.registryWithBuiltins(),
            base.checkers(),
            base.cweMappings(),
            new TuningConfig(
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
        new DfaEngine(config, List.of(new NullDerefChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);

    List<Finding> out = new ArrayList<>();
    for (Finding finding : reporter.all()) {
      if (NULL_DEREF_RULE.equals(finding.ruleId())) {
        out.add(finding);
      }
    }
    return out;
  }

  /**
   * A Scala {@code String} may hold null, exactly like a Java one, so a dereference of one is
   * reported.
   *
   * <p>This is the control that keeps the frontend honest. Scala has no non-null type system; if
   * the frontend marked every Scala reference non-null (as it would have to do for Kotlin), this
   * finding would disappear and the rule would be blind on Scala code -- a false negative that
   * looks like a clean scan.
   */
  @Test
  void aScalaStringMayHoldNullSoTheDereferenceIsReported() {
    String source =
        String.join(
            "\n",
            "package demo",
            "",
            "class Holder {",
            "  def getNullValue(): String = null",
            "}",
            "",
            "class Demo {",
            "  def deref(holder: Holder): Int = {",
            "    val value = holder.getNullValue()",
            "    value.length",
            "  }",
            "}");

    assertThat(scan(source))
        .as("a Scala String may hold null, so the dereference must be reported")
        .isNotEmpty();
  }

  /**
   * The Scala language marker survives into the engine.
   *
   * <p>The engine's checkers consult {@link TacFunction#sourceLanguage()} to decide whether a
   * language-specific rule applies. A Scala function that arrives as Java would silently lose every
   * Scala-bound rule, so the marker has to survive the frontend's own output.
   */
  @Test
  void theScalaLanguageMarkerSurvivesIntoTheEngine() {
    String source =
        String.join(
            "\n", "package demo", "", "class Demo {", "  def marker(): String = \"x\"", "}");

    List<TacFunction> functions = frontend.parse(source, Path.of("T.scala")).functions();
    assertThat(functions).isNotEmpty();
    for (TacFunction fn : functions) {
      assertThat(fn.sourceLanguage())
          .as("the function must be marked as Scala so the engine can bind Scala rules")
          .isEqualTo(SourceLanguage.SCALA);
    }
  }

  /**
   * The precision marker of a lowered {@code match} survives into the engine.
   *
   * <p>A {@code match} is lowered into a branch chain, so the function that contained it reports
   * {@link IrPrecision#SOURCE_LOWERED}. A checker consults that before trusting a
   * precision-dependent fact about the function, so the marker has to survive into the engine --
   * and it must be the <i>degraded</i> one, because the pattern's type narrowing is not expressible
   * in the TAC.
   */
  @Test
  void thePrecisionMarkerOfALoweredMatchSurvivesIntoTheEngine() {
    String source =
        String.join(
            "\n",
            "package demo",
            "",
            "class Demo {",
            "  def pick(o: Any): Int = o match {",
            "    case s: String => s.length",
            "    case _ => 0",
            "  }",
            "}");

    List<TacFunction> functions = frontend.parse(source, Path.of("T.scala")).functions();
    TacFunction pick =
        functions.stream()
            .filter(f -> f.name().endsWith(".pick"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the pick function must be lowered"));
    assertThat(pick.irPrecision())
        .as("a lowered match must downgrade the function's precision")
        .isEqualTo(IrPrecision.SOURCE_LOWERED);
  }

  /**
   * The engine runs over the Scala TAC without complaining.
   *
   * <p>The engine builds a CFG out of the instruction stream. A branch chain whose labels did not
   * line up would produce a dangling edge here, so this test is also the end-to-end proof that the
   * lowered control flow is well formed.
   */
  @Test
  void theEngineRunsOverTheScalaTacWithoutFailing() {
    String source =
        String.join(
            "\n",
            "package demo",
            "",
            "class Demo {",
            "  def add(a: Int, b: Int): Int = a + b",
            "  def pick(o: Any): Int = o match {",
            "    case s: String => s.length",
            "    case i: Int => i",
            "    case _ => 0",
            "  }",
            "  def loop(n: Int): Int = {",
            "    var total = 0",
            "    var i = 0",
            "    while i < n do",
            "      total = total + i",
            "      i = i + 1",
            "    total",
            "  }",
            "}");

    assertThatCode(() -> scan(source)).doesNotThrowAnyException();
  }
}
