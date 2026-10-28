/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.plumb.build.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the sjavac option table, and for the per-compiler lookup.
 *
 * <p><b>Where the sjavac facts come from.</b> sjavac was removed from the JDK after Java 11, so the
 * reference is a JDK 11's own {@code com.sun.tools.sjavac.options.Option} enum -- which IS sjavac's
 * option table, and which carries the spelling every constant accepts. The measurements were made
 * by reading that enum reflectively and by classifying arguments with sjavac's own {@code
 * Options.parseArgs}.
 *
 * <p><b>The fact that shapes the table.</b> sjavac has its OWN option table, and it CONSUMES those
 * options itself. Everything sjavac does not recognise is forwarded to javac. Measured on the real
 * compiler, sjavac forwards {@code -encoding}, {@code -source}, {@code -target}, {@code --release},
 * {@code -g}, {@code -nowarn}, {@code -proc:none}, {@code -processor}, {@code -processorpath},
 * {@code -bootclasspath}, {@code --add-modules}, {@code -Xlint}, {@code -parameters} and {@code -A}
 * to javac -- so those are javac's options, and claiming them for sjavac would make javac's own
 * lookup reject them.
 */
class SjavacOptionTest {

  /**
   * The sjavac options that are sjavac's own.
   *
   * <p>Every one of these was read out of the real compiler's option enum.
   */
  private static final List<String> SJAVAC_OWN_OPTIONS =
      List.of(
          "-src",
          "--source-path",
          "-sourcepath",
          "--module-path",
          "-p",
          "--class-path",
          "-classpath",
          "-cp",
          "-x",
          "-i",
          "-tr",
          "-copy",
          "-j",
          "--server:",
          "--startserver:",
          "-implicit:",
          "--log=",
          "-verbose",
          "--permit-artifact=",
          "--permit-unidentified-artifacts",
          "--permit-sources-without-package",
          "--compare-found-sources",
          "-d",
          "-s",
          "-h",
          "--state-dir=");

  @Test
  void everySjavacOptionIsInTheTable() {
    for (String spelling : SJAVAC_OWN_OPTIONS) {
      assertThat(CompilerOption.byName(spelling, CompilerOption.Compiler.SJAVAC))
          .as("sjavac option %s", spelling)
          .isNotNull();
    }
  }

  @Test
  void theSjavacOnlyOptionsAreNotJavacOptions() {
    // javac has no --state-dir, no -copy, no --permit-artifact.  If javac's
    // lookup found them, javac would "know" options it does not have and the
    // value that followed them would be swallowed.
    for (String spelling : List.of("--state-dir=", "-copy", "--permit-artifact=", "--log=")) {
      assertThat(CompilerOption.byName(spelling, CompilerOption.Compiler.JAVAC))
          .as("javac must not know %s", spelling)
          .isNull();
    }
  }

  @Test
  void theOptionsSjavacForwardsToJavacAreJavacsOptions() {
    // Measured: the real sjavac hands these to javac rather than consuming
    // them.  So the capture has to treat them as javac's -- and the table
    // must NOT claim them for sjavac, because then javac's own lookup would
    // reject them and the option would be lost.
    for (String spelling :
        List.of(
            "-encoding",
            "-source",
            "-target",
            "--release",
            "-g",
            "-nowarn",
            "-proc:none",
            "-processor",
            "-processorpath",
            "-bootclasspath",
            "--add-modules",
            "-Xlint",
            "-parameters",
            "-A")) {
      assertThat(CompilerOption.byName(spelling, CompilerOption.Compiler.JAVAC))
          .as("javac option %s", spelling)
          .isNotNull();
    }
  }

  @Test
  void sjavacTakesTheValueOfItsOptionsFromTheNextToken() {
    // Measured: `-j 2` works and `-j=2` is REJECTED.  The same holds for
    // --server:, --startserver: and --state-dir=.  The spelling says where
    // the value MAY go, not that it must be joined.
    for (String spelling : List.of("-j", "--server:", "--startserver:", "--state-dir=")) {
      assertThat(CompilerOption.byName(spelling, CompilerOption.Compiler.SJAVAC))
          .as("sjavac option %s", spelling)
          .isNotNull();
    }
  }

  @Test
  void sjavacsImplicitSwitchIsItsOwnSpelling() {
    // javac spells it -implicit:none and -implicit:class.  sjavac spells it
    // `-implicit:` with the value in the next token.  They are different
    // spellings, and the table holds both.
    assertThat(CompilerOption.byName("-implicit:", CompilerOption.Compiler.SJAVAC)).isNotNull();
    assertThat(CompilerOption.byName("-implicit:none", CompilerOption.Compiler.SJAVAC)).isNotNull();
    assertThat(CompilerOption.byName("-implicit:class", CompilerOption.Compiler.SJAVAC))
        .isNotNull();
  }

  // ---------------------------------------------------- the per-compiler lookup

  /**
   * Two compilers may spell an option the same way and mean different things by it.
   *
   * <p>ECJ and sjavac both have {@code -src}, {@code -copy}, {@code -x}, {@code -i}, {@code -tr}
   * and {@code -j}. The index holds one option per (name, compiler) pair, so each compiler finds
   * its own.
   *
   * <p><b>Why this matters.</b> The index used to hold one option per NAME, so the second entry
   * silently overwrote the first and ECJ's lookup rejected all six: the option was dropped and the
   * value that followed it was read as a source file.
   */
  @Test
  void twoCompilersMaySpellAnOptionTheSameWay() {
    for (String spelling : List.of("-src", "-copy", "-x", "-i", "-tr", "-j")) {
      assertThat(CompilerOption.byName(spelling, CompilerOption.Compiler.ECJ))
          .as("ECJ's %s", spelling)
          .isNotNull();
      assertThat(CompilerOption.byName(spelling, CompilerOption.Compiler.SJAVAC))
          .as("sjavac's %s", spelling)
          .isNotNull();
    }
  }

  @Test
  void theTwoCompilersMayMeanDifferentThingsByTheSameSpelling() {
    // sjavac's -copy may repeat and ECJ's may not.  The table has to be able
    // to say that, and the lookup has to hand each compiler its own.
    assertThat(CompilerOption.byName("-copy", CompilerOption.Compiler.ECJ).repeat())
        .isEqualTo(CompilerOption.Repeat.LAST_WINS);
    assertThat(CompilerOption.byName("-copy", CompilerOption.Compiler.SJAVAC).repeat())
        .isEqualTo(CompilerOption.Repeat.ALL_APPLY);
  }

  @Test
  void theHeaderDirectoryIsSharedByTheTwoCompilers() {
    // -h is ECJ's and sjavac's, and it means the same thing to both, so it
    // is one option that both find.  The real sjavac's option enum lists
    // "-h  Directory for header files".
    CompilerOption option = CompilerOption.byName("-h");
    assertThat(option).isNotNull();
    assertThat(option.compiler()).isNull();
    assertThat(CompilerOption.byName("-h", CompilerOption.Compiler.ECJ)).isNotNull();
    assertThat(CompilerOption.byName("-h", CompilerOption.Compiler.SJAVAC)).isNotNull();
  }

  @Test
  void theEcjOptionsMeasuredAgainstTheRealCompilerAreKnown() {
    // These four were found by running ECJ's own `-help`.  `-annotationpath`
    // is the one that mattered: it names the external annotations that
    // support annotation-based null analysis, and when it was unknown the
    // path that followed it was consumed as a positional argument too, so
    // both the option and the path were lost.
    for (String spelling : List.of("-annotationpath", "-warn:none", "-?:warn", "-help:warn")) {
      assertThat(CompilerOption.byName(spelling, CompilerOption.Compiler.ECJ))
          .as("ECJ option %s", spelling)
          .isNotNull();
    }
  }

  @Test
  void theAnnotationPathIsAPathList() {
    // A path that is not recognised as a path list would be read as a
    // single value, and a path list joined with the platform separator
    // would not be split apart.
    assertThat(CompilerOption.byName("-annotationpath", CompilerOption.Compiler.ECJ).value())
        .isEqualTo(CompilerOption.Value.PATH_LIST);
  }

  // ---------------------------------------------------- the parsing

  @Test
  void sjavacArgumentsAreParsedWithSjavacsTable() {
    JavacOptions options =
        JavacOptions.parse(
            List.of("--class-path", "a.jar", "--state-dir=", "state", "--log=", "info", "Foo.java"),
            CompilerOption.Compiler.SJAVAC);

    assertThat(options.classpath()).containsExactly(java.nio.file.Path.of("a.jar"));
    // The sjavac-only options are recognised, so their values are not read
    // as source files.
    assertThat(options.sourceFiles()).containsExactly(java.nio.file.Path.of("Foo.java"));
  }

  @Test
  void theSjavacSourcePathIsParsed() {
    JavacOptions options =
        JavacOptions.parse(
            List.of("--source-path", "src", "Foo.java"), CompilerOption.Compiler.SJAVAC);
    assertThat(options.sourcePath()).containsExactly(java.nio.file.Path.of("src"));
  }
}
