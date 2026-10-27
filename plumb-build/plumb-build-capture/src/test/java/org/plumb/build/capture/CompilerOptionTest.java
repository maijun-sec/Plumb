package org.plumb.build.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the data-driven option table.
 *
 * <p>The option knowledge lives in {@link CompilerOption}, and these tests pin the behaviour that
 * the previous hard-coded switch got wrong.
 *
 * <p><b>The bug this replaces.</b> The old implementation had a dozen option names in a switch and
 * a default branch that treated any argument ending in {@code .java} as a source file. So
 * {@code --processor-path some/where.jar} was silently dropped, and an option whose value
 * happened to end in {@code .java} was mistaken for a source.
 */
class CompilerOptionTest {

  // ---------------------------------------------------- the table itself

  @Test
  void everyOptionHasACanonicalName() {
    for (CompilerOption option : CompilerOption.ALL) {
      assertThat(option.canonicalName()).as("option %s", option.name()).isNotBlank();
      assertThat(option.name()).as("option %s", option.name()).isNotBlank();
    }
  }

  @Test
  void theTableHasNoDuplicateOptionNames() {
    long distinct =
        CompilerOption.ALL.stream().map(CompilerOption::name).distinct().count();
    assertThat(distinct).isEqualTo(CompilerOption.ALL.size());
  }

  @Test
  void everyOptionIsReachableByName() {
    for (CompilerOption option : CompilerOption.ALL) {
      assertThat(CompilerOption.byName(option.name())).as("option %s", option.name())
          .isSameAs(option);
    }
  }

  @Test
  void anUnknownOptionIsNotFound() {
    assertThat(CompilerOption.byName("--not-an-option")).isNull();
  }

  /**
   * The classpath must have all its spellings.
   *
   * <p>javac accepts {@code -classpath}, {@code --classpath} and {@code -cp}. All of them mean
   * the same thing, so all of them canonicalise to {@code --classpath}.
   */
  @Test
  void theClasspathHasEverySpelling() {
    for (String spelling : List.of("-classpath", "--classpath", "-cp")) {
      CompilerOption option = CompilerOption.byName(spelling);
      assertThat(option).as("spelling %s", spelling).isNotNull();
      assertThat(option.canonicalName()).as("spelling %s", spelling).isEqualTo("--classpath");
      assertThat(option.value()).as("spelling %s", spelling)
          .isEqualTo(CompilerOption.Value.PATH_LIST);
    }
  }

  /**
   * ECJ's {@code --class-path} is an option of its own.
   *
   * <p>It is not another spelling of {@code --classpath}: it belongs to ECJ, and javac does not
   * accept it. Sharing the canonical name would make javac learn to accept it.
   */
  @Test
  void ecjsClasspathSpellingIsItsOwnOption() {
    CompilerOption option = CompilerOption.byName("--class-path");
    assertThat(option).isNotNull();
    assertThat(option.canonicalName()).isEqualTo("--class-path");
    assertThat(option.compiler()).isEqualTo(CompilerOption.Compiler.ECJ);
  }

  // ---------------------------------------------------- the parsing

  @Test
  void theProcessorPathIsNotDropped() {
    // The old implementation knew nothing about --processor-path, so the
    // whole option disappeared.
    JavacOptions options =
        JavacOptions.parse(List.of("--processor-path", "lib/proc.jar", "Foo.java"));

    assertThat(options.processorPath()).containsExactly(Path.of("lib/proc.jar"));
  }

  @Test
  void anOptionValueEndingInJavaIsNotASourceFile() {
    // The old implementation's default branch treated any argument ending in
    // ".java" as a source file.
    JavacOptions options =
        JavacOptions.parse(List.of("-processorpath", "lib/Weird.java", "Foo.java"));

    assertThat(options.sourceFiles()).containsExactly(Path.of("Foo.java"));
  }

  @Test
  void theModuleOptionsAreParsed() {
    JavacOptions options =
        JavacOptions.parse(
            List.of(
                "--module-path", "mods",
                "--module-source-path", "srcmods",
                "--add-modules", "java.se,java.compiler",
                "--add-exports", "a/b=c/d",
                "--add-reads", "a/b=c",
                "--patch-module", "a=dir",
                "Foo.java"));

    assertThat(options.modulePath()).containsExactly(Path.of("mods"));
    assertThat(options.moduleSourcePath()).containsExactly(Path.of("srcmods"));
    assertThat(options.addModules()).containsExactly("java.se", "java.compiler");
    assertThat(options.addExports()).containsExactly("a/b=c/d");
    assertThat(options.addReads()).containsExactly("a/b=c");
  }

  @Test
  void theModuleOptionsAreCommaSeparatedNotPathSeparated() {
    // On Unix the path separator is a colon, and this value has none --
    // splitting it on the path separator would be wrong by accident only on
    // Windows.
    JavacOptions options = JavacOptions.parse(List.of("--add-modules", "java.se,java.compiler"));

    assertThat(options.addModules()).containsExactly("java.se", "java.compiler");
  }

  @Test
  void theSystemAndBootClassPathAreParsed() {
    JavacOptions options =
        JavacOptions.parse(List.of("--system", "none", "-bootclasspath", "boot.jar", "Foo.java"));

    assertThat(options.bootClassPath()).containsExactly(Path.of("boot.jar"));
  }

  @Test
  void thePreviewFlagIsRecorded() {
    JavacOptions options = JavacOptions.parse(List.of("--enable-preview", "Foo.java"));

    assertThat(options.preview()).isTrue();
  }

  @Test
  void anOptionWithNoValueDoesNotEatTheNextArgument() {
    JavacOptions options = JavacOptions.parse(List.of("--enable-preview", "Foo.java"));

    assertThat(options.sourceFiles()).containsExactly(Path.of("Foo.java"));
  }

  // ---------------------------------------------------- not a compilation

  /**
   * {@code -Xprint} and {@code -proc:only} do not produce a class file.
   *
   * <p>A capture that counted them as compiles would report a compilation that never happened.
   */
  @Test
  void theOptionsThatDoNotCompileAreRecognised() {
    assertThat(JavacOptions.parse(List.of("-Xprint", "Foo.java")).isCompilation()).isFalse();
    assertThat(JavacOptions.parse(List.of("-proc:only", "Foo.java")).isCompilation()).isFalse();
    assertThat(JavacOptions.parse(List.of("-d", "out", "Foo.java")).isCompilation()).isTrue();
  }

  // ---------------------------------------------------- repetition

  /**
   * The classpath given twice means the last one wins.
   *
   * <p>Not both: javac does not concatenate two {@code -classpath} options, it uses the last.
   * Reading it as a concatenation produces a classpath that never existed.
   */
  @Test
  void theLastClasspathWins() {
    JavacOptions options =
        JavacOptions.parse(List.of("-classpath", "a.jar", "-classpath", "b.jar", "Foo.java"));

    assertThat(options.classpath()).containsExactly(Path.of("b.jar"));
  }

  /** {@code --add-modules} given twice: both apply. */
  @Test
  void everyAddModulesApplies() {
    JavacOptions options =
        JavacOptions.parse(List.of("--add-modules", "java.se", "--add-modules", "java.sql"));

    assertThat(options.addModules()).containsExactly("java.se", "java.sql");
  }

  // ---------------------------------------------------- attached values

  /**
   * An option and its value may be written together: {@code -encodingUTF-8}.
   *
   * <p>javac accepts this form, and so does every build tool that shells out to it.
   */
  @Test
  void anAttachedValueIsParsed() {
    JavacOptions options = JavacOptions.parse(List.of("-encodingUTF-8", "Foo.java"));

    assertThat(options.encodingName()).isEqualTo("UTF-8");
    assertThat(options.sourceFiles()).containsExactly(Path.of("Foo.java"));
  }

  @Test
  void anAttachedOutputDirectoryIsParsed() {
    JavacOptions options = JavacOptions.parse(List.of("-dout", "Foo.java"));

    assertThat(options.outputDirectory()).isEqualTo(Path.of("out"));
  }

  // ---------------------------------------------------- the compilers differ

  /**
   * ECJ spells the classpath {@code --class-path}, and javac does not know that spelling.
   *
   * <p>Parsing ECJ's arguments with javac's table would drop the classpath -- the one thing a
   * static analyser cannot do without.
   */
  @Test
  void ecjHasItsOwnClasspathSpelling() {
    JavacOptions ecj =
        JavacOptions.parse(
            List.of("--class-path", "a.jar", "Foo.java"), CompilerOption.Compiler.ECJ);
    assertThat(ecj.classpath()).containsExactly(Path.of("a.jar"));

    // javac does not know --class-path, so the value is not a classpath there.
    JavacOptions javac =
        JavacOptions.parse(
            List.of("--class-path", "a.jar", "Foo.java"), CompilerOption.Compiler.JAVAC);
    assertThat(javac.classpath()).isEmpty();
  }

  @Test
  void ecjHasItsOwnOptions() {
    // -profile is ECJ-only. javac does not know it, so its value is not
    // swallowed as an option -- but it is not a source file either.
    JavacOptions ecj =
        JavacOptions.parse(
            List.of("-profile", "myprofile", "Foo.java"), CompilerOption.Compiler.ECJ);
    assertThat(ecj.sourceFiles()).containsExactly(Path.of("Foo.java"));
  }

  /**
   * An option that belongs to another compiler is not that compiler's option.
   *
   * <p>If the lookup were global, javac would "know" ECJ's {@code --class-path} and the
   * classpath would be read from an option javac does not have.
   */
  @Test
  void anOptionOfAnotherCompilerIsNotFound() {
    assertThat(CompilerOption.byName("--class-path", CompilerOption.Compiler.ECJ)).isNotNull();
    assertThat(CompilerOption.byName("--class-path", CompilerOption.Compiler.JAVAC)).isNull();
    assertThat(CompilerOption.byName("-src", CompilerOption.Compiler.ECJ)).isNotNull();
    assertThat(CompilerOption.byName("-src", CompilerOption.Compiler.JAVAC)).isNull();
  }

  @Test
  void everyCompilerHasItsOwnOptionList() {
    for (CompilerOption.Compiler compiler : CompilerOption.Compiler.values()) {
      assertThat(CompilerOption.forCompiler(compiler))
          .as("compiler %s", compiler)
          .isNotEmpty();
    }
  }

  // ---------------------------------------------------- path splitting

  /**
   * The path separator is platform-dependent, and the drive letter is part of the path.
   *
   * <p>Splitting on both separators at once turns {@code D:\cache\repo.jar} into {@code D} and
   * {@code \cache\repo.jar} -- two paths that are both wrong, and the first one happens to look
   * like a valid relative path, so the breakage is easy to miss.
   */
  @Test
  void aWindowsPathIsNotSplitOnItsDriveLetter() {
    String separator = CompilerOption.isWindows() ? ";" : ":";
    String value = "D:\\cache\\repo.jar" + separator + "C:\\other\\x.jar";

    List<Path> paths = CompilerOption.splitPath(value);

    assertThat(paths).hasSize(2);
    assertThat(paths.get(0).toString()).endsWith("repo.jar");
    assertThat(paths.get(1).toString()).endsWith("x.jar");
    if (CompilerOption.isWindows()) {
      assertThat(paths.get(0).toString()).isEqualTo("D:\\cache\\repo.jar");
    }
  }

  @Test
  void anEmptyPathValueYieldsNothing() {
    assertThat(CompilerOption.splitPath("")).isEmpty();
    assertThat(CompilerOption.splitPath(null)).isEmpty();
  }
}
