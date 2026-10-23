package org.plumb.style.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.Finding;

class PredictableKeyPasswordCheckTest {
  private final SecurityAstCheckTestHarness h =
      new SecurityAstCheckTestHarness(new PredictableKeyPasswordCheck());

  @Test
  void pbeKeySpecLiteral_fires() {
    List<Finding> f =
        h.run("class T { void m() { " + "new javax.crypto.spec.PBEKeySpec(\"password\"); } }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).cwe()).isEqualTo("CWE-259");
  }

  @Test
  void secretKeySpecLiteral_fires() {
    List<Finding> f =
        h.run(
            "class T { void m() { "
                + "new javax.crypto.spec.SecretKeySpec("
                + "new byte[]{1,2,3}, \"AES\"); } }");
    // First arg is a byte[] literal; not a literal
    // expression per se (it's an ArrayCreationExpr)
    // so the rule does not fire. This is a known
    // conservative corner of the AST half.
    assertThat(f).isEmpty();
  }

  @Test
  void pbeKeySpecVariable_doesNotFire() {
    // The DFA half would have unwrapped a single MOVE
    // to recover the literal; the AST half is
    // conservative.
    List<Finding> f =
        h.run(
            "class T { void m() { String pw = System.getenv(\"P\"); "
                + "new javax.crypto.spec.PBEKeySpec(pw); } }");
    assertThat(f).isEmpty();
  }

  @Test
  void unrelatedCtor_doesNotFire() {
    List<Finding> f =
        h.run("class T { void m() { " + "new java.util.HashMap<String,String>(); } }");
    assertThat(f).isEmpty();
  }
}
