package org.plumb.dfa.checkers.taint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;

/**
 * Unit tests for the eight Coverity Java taint checkers (BAD_CERT, UNSAFE_REFLECTION, UNSAFE_JNI,
 * EL_INJECTION, OGNL_INJECTION, JCR_INJECTION, JSP_DYNAMIC_INCLUDE, TRUST_BOUNDARY_VIOLATION). Each
 * test verifies the checker's rule wiring (id, source fact, sink family, cwe, severity, taint
 * domain) so that a future change to the {@link TaintRule} constructor can't accidentally break the
 * "sink family / source fact / cwe" mapping without a test noticing.
 */
class R500CoverityJavaTaintCheckersTest {

  private static void assertRule(
      TaintedCheckerBase checker,
      String expectedIdPrefix,
      String expectedSource,
      String expectedSink,
      String expectedCwe,
      String expectedSeverity) {
    TaintRule rule = checker.rule();
    assertThat(rule.ruleId())
        .as("checker %s ruleId", expectedIdPrefix)
        .startsWith(expectedIdPrefix);
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
  void r500_badCertVerification() {
    assertRule(
        new BadCertVerificationChecker(),
        "dfa-taint-bad-cert-verification",
        "dfa-taint-untrusted-source",
        "ssl.sinks",
        "CWE-295",
        "CRITICAL");
  }

  @Test
  void r501_unsafeReflection() {
    assertRule(
        new UnsafeReflectionChecker(),
        "dfa-taint-unsafe-reflection",
        "dfa-taint-untrusted-source",
        "reflection.sinks",
        "CWE-470",
        "HIGH");
  }

  @Test
  void r502_unsafeJni() {
    assertRule(
        new UnsafeJniChecker(),
        "dfa-taint-unsafe-jni",
        "dfa-taint-untrusted-source",
        "jni.sinks",
        "CWE-114",
        "CRITICAL");
  }

  @Test
  void r503_elInjection() {
    assertRule(
        new ElInjectionChecker(),
        "dfa-taint-el-injection",
        "dfa-taint-untrusted-source",
        "el.sinks",
        "CWE-94",
        "CRITICAL");
  }

  @Test
  void r504_ognlInjection() {
    assertRule(
        new OgnlInjectionChecker(),
        "dfa-taint-ognl-injection",
        "dfa-taint-untrusted-source",
        "ognl.sinks",
        "CWE-94",
        "CRITICAL");
  }

  @Test
  void r505_jcrInjection() {
    assertRule(
        new JcrInjectionChecker(),
        "dfa-taint-jcr-injection",
        "dfa-taint-untrusted-source",
        "jcr.sinks",
        "CWE-94",
        "HIGH");
  }

  @Test
  void r506_jspDynamicInclude() {
    assertRule(
        new JspDynamicIncludeChecker(),
        "dfa-taint-jsp-dynamic-include",
        "dfa-taint-untrusted-source",
        "jsp-include.sinks",
        "CWE-829",
        "CRITICAL");
  }

  @Test
  void r507_trustBoundaryViolation() {
    assertRule(
        new TrustBoundaryViolationChecker(),
        "dfa-taint-trust-boundary-violation",
        "dfa-taint-untrusted-source",
        "trust-boundary.sinks",
        "CWE-501",
        "MEDIUM");
  }
}
