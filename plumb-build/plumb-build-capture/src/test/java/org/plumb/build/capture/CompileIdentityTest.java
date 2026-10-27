package org.plumb.build.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the compile identity, which is what makes the duplicate suppression work.
 *
 * <p><b>Why the identity is not the argv.</b> One compile reaches several hooked entry points: the
 * command-line driver goes through {@code com.sun.tools.javac.Main#compile} and then {@code
 * com.sun.tools.javac.main.Main#compile}, and the API path goes through {@code JavacTool#getTask}
 * and then {@code JavacTaskImpl#call}. Each would otherwise write a file, and the analyser would
 * see one compile several times over.
 *
 * <p>But the argv is the wrong thing to compare on: it differs between the CLI and the API path
 * even for the same compile, and it is identical for two genuinely different compiles that happen
 * to be invoked with the same options.
 */
class CompileIdentityTest {

  private static CaptureEntry entry(
      List<Path> sources, Path output, List<Path> classpath, List<String> argv) {
    return new CaptureEntry(
        "cli",
        argv,
        "UTF-8",
        "17",
        output,
        classpath,
        List.of(Path.of("src")),
        List.of(),
        sources,
        "17.0.1",
        "/opt/jdk",
        "/work");
  }

  @Test
  void theSameCompileHasTheSameIdentity() {
    CaptureEntry a = entry(List.of(Path.of("Foo.java")), Path.of("out"), List.of(Path.of("a.jar")), List.of("-d", "out", "Foo.java"));
    CaptureEntry b = entry(List.of(Path.of("Foo.java")), Path.of("out"), List.of(Path.of("a.jar")), List.of("-d", "out", "Foo.java"));
    assertThat(a.sameCompileAs(b)).isTrue();
  }

  /**
   * The identity must not depend on the argv.
   *
   * <p>The CLI and the API path reach the same compile with different arguments, and the CLI and
   * the internal driver reach it with the same arguments. Both are one compile.
   */
  @Test
  void theIdentityDoesNotDependOnTheArgv() {
    CaptureEntry cli =
        entry(List.of(Path.of("Foo.java")), Path.of("out"), List.of(Path.of("a.jar")), List.of("-d", "out", "Foo.java"));
    CaptureEntry api =
        entry(
            List.of(Path.of("Foo.java")),
            Path.of("out"),
            List.of(Path.of("a.jar")),
            List.of("--release", "17", "-d", "out"));
    assertThat(cli.sameCompileAs(api)).isTrue();
  }

  /** The source files can arrive in a different order from the API than from the command line. */
  @Test
  void theIdentityDoesNotDependOnTheOrderOfTheSources() {
    CaptureEntry a =
        entry(
            List.of(Path.of("A.java"), Path.of("B.java")),
            Path.of("out"),
            List.of(Path.of("a.jar")),
            List.of("-d", "out"));
    CaptureEntry b =
        entry(
            List.of(Path.of("B.java"), Path.of("A.java")),
            Path.of("out"),
            List.of(Path.of("a.jar")),
            List.of("-d", "out"));
    assertThat(a.sameCompileAs(b)).isTrue();
  }

  @Test
  void differentSourcesAreDifferentCompiles() {
    CaptureEntry a = entry(List.of(Path.of("A.java")), Path.of("out"), List.of(Path.of("a.jar")), List.of());
    CaptureEntry b = entry(List.of(Path.of("B.java")), Path.of("out"), List.of(Path.of("a.jar")), List.of());
    assertThat(a.sameCompileAs(b)).isFalse();
  }

  @Test
  void differentOutputDirectoriesAreDifferentCompiles() {
    CaptureEntry a = entry(List.of(Path.of("A.java")), Path.of("out1"), List.of(Path.of("a.jar")), List.of());
    CaptureEntry b = entry(List.of(Path.of("A.java")), Path.of("out2"), List.of(Path.of("a.jar")), List.of());
    assertThat(a.sameCompileAs(b)).isFalse();
  }

  /** The classpath is part of the identity: a different classpath is a different compile. */
  @Test
  void differentClasspathsAreDifferentCompiles() {
    CaptureEntry a = entry(List.of(Path.of("A.java")), Path.of("out"), List.of(Path.of("a.jar")), List.of());
    CaptureEntry b = entry(List.of(Path.of("A.java")), Path.of("out"), List.of(Path.of("b.jar")), List.of());
    assertThat(a.sameCompileAs(b)).isFalse();
  }

  /** The language level changes the result, so it is part of the identity. */
  @Test
  void differentLanguageLevelsAreDifferentCompiles() {
    CaptureEntry a =
        new CaptureEntry(
            "cli", List.of(), "UTF-8", "17", Path.of("out"), List.of(Path.of("a.jar")), List.of(),
            List.of(), List.of(Path.of("A.java")), "17", "/opt/jdk", "/work");
    CaptureEntry b =
        new CaptureEntry(
            "cli", List.of(), "UTF-8", "11", Path.of("out"), List.of(Path.of("a.jar")), List.of(),
            List.of(), List.of(Path.of("A.java")), "17", "/opt/jdk", "/work");
    assertThat(a.sameCompileAs(b)).isFalse();
  }

  @Test
  void anEntryIsNotTheSameCompileAsNull() {
    CaptureEntry a = entry(List.of(Path.of("A.java")), Path.of("out"), List.of(), List.of());
    assertThat(a.sameCompileAs(null)).isFalse();
  }
}
