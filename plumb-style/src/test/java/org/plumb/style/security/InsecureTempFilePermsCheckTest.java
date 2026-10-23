package org.plumb.style.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.Finding;

/**
 * Tests for {@link InsecureTempFilePermsCheck}.
 *
 * <p>Covers: positive (fires on {@code File.createTempFile}), negative (Files.createTempFile —
 * modern NIO API, out of scope), boundary (other static factory on File ? does not fire), unrelated
 * (does not fire).
 */
class InsecureTempFilePermsCheckTest {
  private final SecurityAstCheckTestHarness h =
      new SecurityAstCheckTestHarness(new InsecureTempFilePermsCheck());

  @Test
  void fileCreateTempFile_fires() {
    List<Finding> f =
        h.run(
            "import java.io.File; class T { void m() throws Exception { "
                + "File f = File.createTempFile(\"p\", \".tmp\"); } }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).cwe()).isEqualTo("CWE-378");
  }

  @Test
  void filesCreateTempFile_doesNotFire() {
    // java.nio.file.Files.createTempFile is the modern
    // API that sets 0600 by default — the DFA half
    // intentionally skipped this.
    List<Finding> f =
        h.run(
            "import java.nio.file.Files; class T { void m() throws Exception { "
                + "java.nio.file.Path p = Files.createTempFile(\"p\", \".tmp\"); "
                + "} }");
    assertThat(f).isEmpty();
  }

  @Test
  void unrelatedStaticMethod_doesNotFire() {
    List<Finding> f =
        h.run(
            "import java.io.File; class T { void m() throws Exception { "
                + "File.listRoots(); } }");
    assertThat(f).isEmpty();
  }

  @Test
  void plainMethodNamedCreateTempFileOnNonFile_doesNotFire() {
    // a class named "Foo" with a static
    // createTempFile() — wrong receiver, must not fire.
    List<Finding> f = h.run("class T { void m() { Foo.createTempFile(); } }");
    assertThat(f).isEmpty();
  }
}
