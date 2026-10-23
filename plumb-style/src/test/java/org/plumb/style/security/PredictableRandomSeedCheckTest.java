package org.plumb.style.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.Finding;

class PredictableRandomSeedCheckTest {
  private final SecurityAstCheckTestHarness h =
      new SecurityAstCheckTestHarness(new PredictableRandomSeedCheck());

  @Test
  void randomCtorLiteral_fires() {
    List<Finding> f = h.run("class T { void m() { new java.util.Random(42); } }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).cwe()).isEqualTo("CWE-332");
  }

  @Test
  void secureRandomCtorLiteral_fires() {
    List<Finding> f = h.run("class T { void m() { new java.security.SecureRandom(12345L); } }");
    assertThat(f).hasSize(1);
  }

  @Test
  void randomNoCtor_doesNotFire() {
    // new Random() — no seed argument. Safe.
    List<Finding> f = h.run("class T { void m() { new java.util.Random(); } }");
    assertThat(f).isEmpty();
  }

  @Test
  void setSeedLiteral_fires() {
    List<Finding> f =
        h.run(
            "class T { void m() { "
                + "java.util.Random r = new java.util.Random(); "
                + "r.setSeed(42); } }");
    assertThat(f).hasSize(1);
  }

  @Test
  void setSeedVariable_doesNotFire() {
    List<Finding> f =
        h.run(
            "class T { void m() { "
                + "java.util.Random r = new java.util.Random(); "
                + "r.setSeed(System.currentTimeMillis()); } }");
    assertThat(f).isEmpty();
  }
}
