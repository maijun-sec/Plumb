package org.plumb.build.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
  void ecjsClasspathSpellingIsSharedWithJavac() {
    // Both compilers accept the spelling and it means the same thing, so it
    // is one option shared by both -- not one per compiler.
    CompilerOption option = CompilerOption.byName("--class-path");
    assertThat(option).isNotNull();
    assertThat(option.canonicalName()).isEqualTo("--classpath");
    assertThat(option.compiler()).isNull();
    assertThat(CompilerOption.byName("--class-path", CompilerOption.Compiler.JAVAC)).isNotNull();
    assertThat(CompilerOption.byName("--class-path", CompilerOption.Compiler.ECJ)).isNotNull();
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

    // javac knows the spelling too: its own help lists "--class-path <path>,
    // -classpath <path>, -cp <path>".  So the classpath is read there as
    // well -- and it USED to be lost, because the option was unknown to
    // javac and the value that followed it was not a source file either.
    JavacOptions javac =
        JavacOptions.parse(
            List.of("--class-path", "a.jar", "Foo.java"), CompilerOption.Compiler.JAVAC);
    assertThat(javac.classpath()).containsExactly(Path.of("a.jar"));
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
   * <p>If the lookup were global, javac would "know" ECJ's {@code -src} and the source directory
   * would be read from an option javac does not have.
   *
   * <p><b>Note on {@code --class-path}.</b> This test used to assert that javac does not know
   * {@code --class-path}, and the option table agreed. Both were wrong: javac's own help lists
   * "--class-path &lt;path&gt;, -classpath &lt;path&gt;, -cp &lt;path&gt;", and a real javac run
   * accepts the spelling. A capture that did not know the option lost the classpath entirely --
   * the option was unknown, and the value that followed it was not a source file either, so both
   * vanished. The compiler is the yardstick, and it accepts the spelling.
   */
  @Test
  void anOptionOfAnotherCompilerIsNotFound() {
    assertThat(CompilerOption.byName("-src", CompilerOption.Compiler.ECJ)).isNotNull();
    assertThat(CompilerOption.byName("-src", CompilerOption.Compiler.JAVAC)).isNull();
  }

  /** javac does know {@code --class-path}: its own help lists it. */
  @Test
  void javacKnowsItsOwnDoubleDashClasspath() {
    assertThat(CompilerOption.byName("--class-path", CompilerOption.Compiler.JAVAC)).isNotNull();
    assertThat(CompilerOption.byName("--class-path", CompilerOption.Compiler.ECJ)).isNotNull();
    // Both spellings mean the same thing, so they share a canonical name.
    assertThat(CompilerOption.byName("--class-path").canonicalName())
        .isEqualTo(CompilerOption.byName("-classpath").canonicalName());
  }

  @Test
  void everyCompilerHasItsOwnOptionList() {
    for (CompilerOption.Compiler compiler : CompilerOption.Compiler.values()) {
      assertThat(CompilerOption.forCompiler(compiler))
          .as("compiler %s", compiler)
          .isNotEmpty();
    }
  }

  // ---------------------------------------------------- the options javac lists

  /**
   * The options javac accepts, checked against javac's own help.
   *
   * <p><b>Why the compiler is the yardstick.</b> The table is supposed to be the compiler's option
   * set, so it is compared against the compiler. A table written from memory drifts, and a missing
   * option does not fail -- it is silently unknown, and the value that follows it is lost too.
   *
   * <p>Every option below was checked against a real {@code javac} run.
   */
  @Test
  void javacAcceptsTheDoubleDashSpellings() {
    assertThat(CompilerOption.byName("--source")).isNotNull();
    assertThat(CompilerOption.byName("--target")).isNotNull();
    assertThat(CompilerOption.byName("--class-path")).isNotNull();
    assertThat(CompilerOption.byName("--source").canonicalName())
        .isEqualTo(CompilerOption.byName("-source").canonicalName());
    assertThat(CompilerOption.byName("--target").canonicalName())
        .isEqualTo(CompilerOption.byName("-target").canonicalName());
  }

  @Test
  void javacAcceptsTheAnnotationProcessingSwitchValues() {
    // javac spells it -proc:{none,only,full}
    assertThat(CompilerOption.byName("-proc:none")).isNotNull();
    assertThat(CompilerOption.byName("-proc:only")).isNotNull();
    assertThat(CompilerOption.byName("-proc:full")).isNotNull();
    // Only -proc:only does not produce a class file.
    assertThat(CompilerOption.byName("-proc:only").effect())
        .isEqualTo(CompilerOption.Effect.NOT_COMPILATION);
    assertThat(CompilerOption.byName("-proc:none").effect())
        .isEqualTo(CompilerOption.Effect.COMPILATION);
  }

  @Test
  void javacAcceptsTheImplicitSwitchValues() {
    // javac spells it -implicit:{none,class}
    assertThat(CompilerOption.byName("-implicit:none")).isNotNull();
    assertThat(CompilerOption.byName("-implicit:class")).isNotNull();
  }

  @Test
  void javacAcceptsTheDebugSwitchColonForms() {
    assertThat(CompilerOption.byName("-g:none")).isNotNull();
    assertThat(CompilerOption.byName("-g:lines")).isNotNull();
    assertThat(CompilerOption.byName("-g:vars")).isNotNull();
    assertThat(CompilerOption.byName("-g:source")).isNotNull();
  }

  @Test
  void javacAcceptsTheModuleOfTheCreatedFiles() {
    assertThat(CompilerOption.byName("--default-module-for-created-files")).isNotNull();
  }

  @Test
  void javacAcceptsTheAnnotationProcessorOptions() {
    // -Akey[=value] -- the value is optional, and the key may stand alone.
    CompilerOption option = CompilerOption.byName("-A");
    assertThat(option).isNotNull();
    assertThat(option.repeat()).isEqualTo(CompilerOption.Repeat.ALL_APPLY);
  }

  // ---------------------------------------------------- argument files

  /**
   * The argument file, and the compile parameters that live inside it.
   *
   * <p>This is the gap that made a capture confidently wrong: a build that puts its classpath in an
   * argument file told javac everything through the file, and the command line carried almost
   * nothing.
   */
  @Test
  void anArgumentFileIsExpandedIntoTheArgumentsItHolds(@TempDir Path dir) throws IOException {
    Path options = dir.resolve("options.txt");
    Files.writeString(
        options,
        "-encoding UTF-8\n-source 21\n-classpath " + dir.resolve("lib.jar") + "\n",
        StandardCharsets.UTF_8);

    JavacOptions parsed =
        JavacOptions.parse(List.of("@" + options, "-d", dir.resolve("out").toString()));

    assertThat(parsed.encodingName()).isEqualTo("UTF-8");
    assertThat(parsed.source()).isEqualTo("21");
    assertThat(parsed.classpath()).containsExactly(dir.resolve("lib.jar"));
  }

  /**
   * The argument file's format: whitespace splits, and the quotes around a token are stripped.
   *
   * <p>This was measured against javac: {@code -d "C:\a"} gives the value {@code C:\a}, while
   * {@code -d "C:\a b"} gives the value {@code C:\a} and a stray {@code b"} -- the quotes do not
   * group a value with a space in it.
   */
  @Test
  void anArgumentFileSplitsAQuotedValueThatHasASpaceInIt(@TempDir Path dir) throws IOException {
    Path options = dir.resolve("options.txt");
    Files.writeString(options, "-classpath \"a b\"\n", StandardCharsets.UTF_8);

    JavacOptions parsed = JavacOptions.parse(List.of("@" + options));

    // The space inside the quotes split the token, so the classpath entry
    // is the half-quoted "a -- which is not a usable path, and the capture
    // drops it exactly as javac ignores a classpath entry that does not
    // exist.  The stray b" is a positional argument, and a positional
    // argument is not a source file unless it ends in .java.
    assertThat(parsed.classpath()).isEmpty();
  }

  /** A quoted value with no space in it is one token, and the quotes are gone. */
  @Test
  void aQuotedValueWithNoSpaceIsOneToken(@TempDir Path dir) throws IOException {
    Path options = dir.resolve("options.txt");
    Files.writeString(options, "-classpath \"lib.jar\"\n", StandardCharsets.UTF_8);

    JavacOptions parsed = JavacOptions.parse(List.of("@" + options));

    assertThat(parsed.classpath()).containsExactly(Path.of("lib.jar"));
  }

  /** An argument file that cannot be read leaves the argument alone. */
  @Test
  void anArgumentFileThatCannotBeReadIsLeftAsItWas(@TempDir Path dir) {
    JavacOptions parsed = JavacOptions.parse(List.of("@" + dir.resolve("nope.txt")));

    assertThat(parsed.encodingName()).isNull();
    assertThat(parsed.sourceFiles()).isEmpty();
  }

  /** An argument file that refers to itself does not loop. */
  @Test
  void anArgumentFileThatRefersToItselfDoesNotLoop(@TempDir Path dir) throws IOException {
    Path options = dir.resolve("options.txt");
    Files.writeString(options, "@" + options + "\n", StandardCharsets.UTF_8);

    JavacOptions parsed = JavacOptions.parse(List.of("@" + options));

    assertThat(parsed.encodingName()).isNull();
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
