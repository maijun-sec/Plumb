package org.plumb.build.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link JavacOptions}. */
class JavacOptionsTest {

  @Test
  void parsesOutputDirectory() {
    JavacOptions options =
        JavacOptions.parse(List.of("-d", "out", "Foo.java"));

    assertThat(options.outputDirectory()).isEqualTo(Path.of("out"));
  }

  @Test
  void parsesClasspath() {
    JavacOptions options =
        JavacOptions.parse(List.of("-classpath", "a.jar;b.jar", "Foo.java"));

    assertThat(options.classpath()).containsExactly(Path.of("a.jar"), Path.of("b.jar"));
  }

  @Test
  void acceptsTheCpSynonym() {
    JavacOptions options = JavacOptions.parse(List.of("-cp", "a.jar", "Foo.java"));

    assertThat(options.classpath()).containsExactly(Path.of("a.jar"));
  }

  @Test
  void parsesSourceFiles() {
    JavacOptions options =
        JavacOptions.parse(List.of("-d", "out", "src/Foo.java", "src/Bar.java"));

    assertThat(options.sourceFiles())
        .containsExactly(Path.of("src/Foo.java"), Path.of("src/Bar.java"));
  }

  /**
   * A jar is not a source file.
   *
   * <p>javac accepts a jar on the source path, so one can appear as a positional argument. It is
   * not Java source, and recording it as one put an archive into the source list -- which is part
   * of the compile identity, so two different compiles could collide, and the analyser would try
   * to parse a jar as Java.
   */
  @Test
  void aJarIsNotASourceFile() {
    JavacOptions options = JavacOptions.parse(List.of("lib/dep.jar", "src/Foo.java"));

    assertThat(options.sourceFiles()).containsExactly(Path.of("src/Foo.java"));
  }

  /**
   * The value of a known option is never mistaken for a source.
   *
   * <p>The upstream project treated ANY argument ending in ".java" as a source, so the value of
   * an option it did not know became a source file. Here the option table consumes the values, so
   * only a positional argument can be one.
   */
  @Test
  void anOptionValueIsNeverASourceFile() {
    JavacOptions options =
        JavacOptions.parse(List.of("--processor-path", "lib/processor.jar", "src/Foo.java"));

    assertThat(options.sourceFiles()).containsExactly(Path.of("src/Foo.java"));
    assertThat(options.processorPath()).containsExactly(Path.of("lib/processor.jar"));
  }

  @Test
  void parsesEncoding() {
    JavacOptions options = JavacOptions.parse(List.of("-encoding", "ISO-8859-1", "Foo.java"));

    assertThat(options.encoding()).isEqualTo(StandardCharsets.ISO_8859_1);
  }

  @Test
  void parsesSourceAndTarget() {
    JavacOptions options = JavacOptions.parse(List.of("-source", "11", "-target", "17", "Foo.java"));

    assertThat(options.source()).isEqualTo("11");
    assertThat(options.target()).isEqualTo("17");
    assertThat(options.languageLevel()).isEqualTo("11");
  }

  /**
   * {@code --release} is kept apart from {@code -source} and {@code -target}.
   *
   * <p>They are not the same thing: {@code --release} pins the language level <em>and</em> the
   * platform API, while {@code -source} only pins the language level. Folding {@code --release}
   * into the source and target fields would lose that distinction, and a capture that reported
   * "source 17" for a {@code --release 11} compile would be wrong.
   */
  @Test
  void releaseIsItsOwnLevel() {
    JavacOptions options = JavacOptions.parse(List.of("--release", "17", "Foo.java"));

    assertThat(options.release()).isEqualTo("17");
    assertThat(options.source()).isNull();
    assertThat(options.target()).isNull();
    assertThat(options.languageLevel()).isEqualTo("17");
  }

  /**
   * {@code --release} wins over {@code -source}.
   *
   * <p>When both are given, the release level is the one the compiler actually used, because it
   * is the more specific of the two.
   */
  @Test
  void releaseWinsOverSource() {
    JavacOptions options =
        JavacOptions.parse(List.of("--release", "17", "-source", "11", "Foo.java"));

    assertThat(options.languageLevel()).isEqualTo("17");
  }

  @Test
  void parsesSourcePathAndModulePath() {
    JavacOptions options =
        JavacOptions.parse(
            List.of("-sourcepath", "src", "--module-path", "mods", "Foo.java"));

    assertThat(options.sourcePath()).containsExactly(Path.of("src"));
    assertThat(options.modulePath()).containsExactly(Path.of("mods"));
  }

  @Test
  void keepsTheRawArguments() {
    List<String> argv = List.of("-d", "out", "Foo.java");

    assertThat(JavacOptions.parse(argv).rawArguments()).isEqualTo(argv);
  }

  @Test
  void emptyInputYieldsEmptyOptions() {
    JavacOptions options = JavacOptions.parse(List.of());

    assertThat(options.classpath()).isEmpty();
    assertThat(options.sourceFiles()).isEmpty();
    assertThat(options.outputDirectory()).isNull();
  }

  @Test
  void aTrailingOptionWithoutAValueIsIgnored() {
    JavacOptions options = JavacOptions.parse(List.of("-d"));

    assertThat(options.outputDirectory()).isNull();
  }
}
