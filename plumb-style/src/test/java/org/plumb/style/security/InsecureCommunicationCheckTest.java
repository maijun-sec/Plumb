package org.plumb.style.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.Finding;

class InsecureCommunicationCheckTest {
  private final SecurityAstCheckTestHarness h =
      new SecurityAstCheckTestHarness(new InsecureCommunicationCheck());

  @Test
  void httpUrl_fires() {
    List<Finding> f =
        h.run(
            "class T { void m() throws Exception { "
                + "new java.net.URL(\"http://example.com\"); } }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).cwe()).isEqualTo("CWE-319");
  }

  @Test
  void httpsUrl_doesNotFire() {
    List<Finding> f =
        h.run(
            "class T { void m() throws Exception { "
                + "new java.net.URL(\"https://example.com\"); } }");
    assertThat(f).isEmpty();
  }

  @Test
  void ftpUri_fires() {
    List<Finding> f = h.run("class T { void m() { " + "new java.net.URI(\"ftp://files\"); } }");
    assertThat(f).hasSize(1);
  }

  @Test
  void unrelatedCtor_doesNotFire() {
    // HashMap ctor with an http-looking key — should not
    // be confused with a URL ctor.
    List<Finding> f =
        h.run("class T { void m() { " + "new java.util.HashMap<String,String>(); } }");
    assertThat(f).isEmpty();
  }
}
