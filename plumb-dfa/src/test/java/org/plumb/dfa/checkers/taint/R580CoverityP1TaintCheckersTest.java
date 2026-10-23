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
