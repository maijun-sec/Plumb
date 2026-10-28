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
package org.plumb.dfa.checkers.taint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;

/**
 * Unit tests for the three P1 Coverity Java taint checkers (SESSION_FIXATION, INSECURE_RANDOM,
 * JAVA_CODE_INJECTION). Mirrors the previous test pattern.
 */
class R580CoverityP1TaintCheckersTest {

  private static void assertRule(
      TaintedCheckerBase checker,
      String expectedIdPrefix,
      String expectedSource,
      String expectedSink,
      String expectedCwe,
      String expectedSeverity) {
    TaintRule rule = checker.rule();
    assertThat(rule.ruleId()).as("%s ruleId", expectedIdPrefix).startsWith(expectedIdPrefix);
    assertThat(rule.sourceKindName())
        .as("%s source kind", expectedIdPrefix)
        .isEqualTo(expectedSource);
    assertThat(rule.sinkFamilyKey()).as("%s sink family", expectedIdPrefix).isEqualTo(expectedSink);
    assertThat(rule.cwe()).as("%s cwe", expectedIdPrefix).isEqualTo(expectedCwe);
    assertThat(rule.severityLabel())
        .as("%s severity label", expectedIdPrefix)
        .isEqualTo(expectedSeverity);
  }

  @Test
  void r580_sessionFixation() {
    assertRule(
        new SessionFixationChecker(),
        "dfa-taint-session-fixation",
        "dfa-taint-untrusted-source",
        "session.sinks",
        "CWE-384",
        "HIGH");
  }

  @Test
  void r581_insecureRandom() {
    assertRule(
        new InsecureRandomChecker(),
        "dfa-taint-insecure-random",
        "dfa-taint-untrusted-source",
        "random.sinks",
        "CWE-330",
        "MEDIUM");
  }

  @Test
  void r582_javaCodeInjection() {
    assertRule(
        new JavaCodeInjectionChecker(),
        "dfa-taint-java-code-injection",
        "dfa-taint-untrusted-source",
        "code-execution.sinks",
        "CWE-94",
        "CRITICAL");
  }
}
