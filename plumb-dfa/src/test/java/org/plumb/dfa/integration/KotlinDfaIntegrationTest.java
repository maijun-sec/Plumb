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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
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
import org.plumb.tac.frontend.kotlin.KotlinTacFrontend;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;

/**
 * The DFA consumes a Kotlin function's TAC.
 *
 * <p><b>Why this test lives here and not in the frontend's own module.</b> The frontend's tests
 * prove that the frontend produces the right TAC. This test proves the other half: that the engine
 * downstream actually reads that TAC, and that the information the Kotlin frontend uniquely
 * contributes -- the nullability -- reaches the checker that consumes it.
 *
 * <p><b>Why the null-dereference rule is the probe.</b> It is the one checker that reads {@link
 * org.plumb.tac.ir.TacType#isNullable()}. It seeds its type-system facts from the function's
 * parameters and locals, so a Kotlin {@code String} (non-nullable) is a <i>suppression</i> and a
 * Kotlin {@code String?} is not. That is exactly the false-positive removal the Kotlin frontend
 * exists to provide, and it is observable from the outside: one shape reports, the other does not.
 *
 * <p><b>The direction is one-way.</b> A non-null declared type may only ever suppress a finding. It
 * must never be used to prove that a call site cannot receive null -- a Java method seen from
 * Kotlin is a platform type and may be null at runtime. The second half of this test pins that: a
 * Kotlin {@code String?} parameter still reports.
 */
class KotlinDfaIntegrationTest {

  /** The rule id the null-dereference checker reports under. */
  private static final String NULL_DEREF_RULE = "dfa-taint-null-dereference";

  /** The frontend under test. */
  private static KotlinTacFrontend frontend;

  /** The directory the frontend stages its source under. */
  private static Path sourceRoot;

  @BeforeAll
  static void setUp() throws IOException {
    sourceRoot = Files.createTempDirectory("plumb-kotlin-dfa-test");
    frontend = new KotlinTacFrontend(sourceRoot);
  }

  @AfterAll
  static void tearDown() {
    if (frontend != null) {
      frontend.close();
    }
  }

  /**
   * Parses Kotlin source and runs the null-dereference checker over it.
   *
   * @param source the Kotlin source
   * @return the findings the null-dereference rule produced
   */
  private List<Finding> scan(String source) {
    List<TacFunction> functions = frontend.parse(source, sourceRoot.resolve("T.kt")).functions();
    assertThat(functions).as("the Kotlin source must lower to at least one function").isNotEmpty();

    TacProgram program = new TacProgram(sourceRoot.resolve("T.kt"), "T");
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
   * A Kotlin variable with a <b>nullable</b> declared type, holding the result of a call that may
   * return null, is dereferenced without a check: the finding must survive.
   *
   * <p>This is the control that keeps the suppression honest. If the frontend marked every Kotlin
   * reference type non-null, this finding would disappear and the rule would be blind on Kotlin
   * code -- a false negative that looks like a clean scan.
   */
  @Test
  void aNullableKotlinVariableIsStillReportedAsMayBeNull() {
    String source =
        String.join(
            "\n",
            "package demo",
            "",
            "class Holder {",
            "    fun getNullValue(): String? = null",
            "}",
            "",
            "fun derefNullable(holder: Holder): Int {",
            "    val value = holder.getNullValue()",
            "    return value.length",
            "}");

    assertThat(scan(source))
        .as("a Kotlin String? may hold null, so the dereference must be reported")
        .isNotEmpty();
  }

  /**
   * A Kotlin variable with a <b>non-null</b> declared type, holding the result of a call that may
   * return null, is dereferenced without a check: the finding must be suppressed.
   *
   * <p>This is the whole point of the Kotlin frontend. Java's frontend cannot produce this
   * suppression, because in Java every reference type may hold null. Kotlin's type system says this
   * declaration cannot, and the DFA reads that from the TAC's type.
   *
   * <p>The value comes from a call rather than from a null literal, because the checker
   * deliberately lets an explicit null assignment override the type fact: the type says what the
   * declaration permits, the assignment says what the path actually put there. A Java method seen
   * from Kotlin is a <i>platform type</i> -- the compiler inserts an assertion, but {@code
   * -Xno-call-assertions} removes it -- so the runtime value may well be null. The Kotlin
   * declaration is what says it cannot.
   */
  @Test
  void aNonNullKotlinVariableSuppressesTheNullDereferenceFinding() {
    String source =
        String.join(
            "\n",
            "package demo",
            "",
            "class Holder {",
            "    fun getNullValue(): String? = null",
            "}",
            "",
            "fun derefNonNull(holder: Holder): Int {",
            "    val value: String = holder.getNullValue()",
            "    return value.length",
            "}");

    assertThat(scan(source))
        .as(
            "a Kotlin String cannot hold null, so the null-dereference finding must be "
                + "suppressed -- this is the false positive the Kotlin frontend removes")
        .isEmpty();
  }

  /**
   * A method call on a possibly-null receiver is reported.
   *
   * <p>The TAC's {@code CALL} instruction has no receiver slot, so the receiver is the first
   * argument -- the convention the DFA's null-dereference checker reads. If the frontend left the
   * receiver out, this finding would disappear and the rule would be blind on every Kotlin method
   * call.
   */
  @Test
  void aMethodCallOnAPossiblyNullReceiverIsReported() {
    String source =
        String.join(
            "\n",
            "package demo",
            "",
            "class Holder {",
            "    fun getNullValue(): String? = null",
            "}",
            "",
            "fun derefReceiver(holder: Holder): Int {",
            "    val value = holder.getNullValue()",
            "    return value.length",
            "}");

    assertThat(scan(source)).as("a call on a possibly-null receiver must be reported").isNotEmpty();
  }

  /**
   * The same Kotlin function, lowered, keeps its language marker all the way into the engine.
   *
   * <p>The engine's checkers consult {@link TacFunction#sourceLanguage()} to decide whether a
   * language-specific rule applies. A Kotlin function that arrives as Java would silently lose
   * every Kotlin-bound rule, so the marker has to survive the frontend's own output.
   */
  @Test
  void theKotlinLanguageMarkerSurvivesIntoTheEngine() {
    String source =
        String.join("\n", "package demo", "", "fun marker(): String {", "    return \"x\"", "}");

    List<TacFunction> functions = frontend.parse(source, sourceRoot.resolve("T.kt")).functions();
    assertThat(functions).isNotEmpty();
    for (TacFunction fn : functions) {
      assertThat(fn.sourceLanguage())
          .as("the function must be marked as Kotlin so the engine can bind Kotlin rules")
          .isEqualTo(org.plumb.tac.ir.SourceLanguage.KOTLIN);
    }
  }
}
