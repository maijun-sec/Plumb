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
package org.plumb.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ParserConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The language level the build description asks for must actually reach the parser.
 *
 * <p>This is the regression guard for a chain that used to be broken end to end: the build
 * description carried a language level, it was propagated into {@link JavaModule#languageLevel()},
 * and then {@link JavaSourceParser} ignored it and always ran at {@code JAVA_17}. A project
 * targeting Java 21 therefore failed to parse with "switch patterns not supported".
 */
class JavaLanguageLevelTest {

  /** Java 21 syntax: sealed interface + record + a switch with patterns. */
  private static final String JAVA_21_SOURCE =
      "sealed interface Shape permits Circle, Square {}"
          + "record Circle(double r) implements Shape {}"
          + "record Square(double s) implements Shape {}"
          + "class Use { static String f(Shape s) {"
          + " return switch (s) { case Circle c -> \"c\"; case Square q -> \"q\"; }; } }";

  @Test
  @DisplayName("Java 21 syntax parses when the module declares level 21")
  void java21SyntaxParsesWhenLevelIsDeclared() throws Exception {
    Path root = Files.createTempDirectory("lang21");
    Path src = root.resolve("Use.java");
    Files.writeString(src, JAVA_21_SOURCE);

    JavaModule withLevel =
        JavaModule.singleRoot("m", root, List.of(), JavaModule.Scope.COMPILE, "21", null);

    JavaSourceParser parser = new JavaSourceParser(List.of(withLevel));
    ParsedSource ps = parser.parse(src);

    assertThat(ps.problems())
        .as("Java 21 syntax must parse at level 21; problems were: %s", ps.problems())
        .isEmpty();
  }

  @Test
  @DisplayName("Java 21 syntax FAILS to parse when the module declares level 17")
  void java21SyntaxFailsAtLevel17() throws Exception {
    Path root = Files.createTempDirectory("lang17");
    Path src = root.resolve("Use.java");
    Files.writeString(src, JAVA_21_SOURCE);

    JavaModule withLevel =
        JavaModule.singleRoot("m", root, List.of(), JavaModule.Scope.COMPILE, "17", null);

    JavaSourceParser parser = new JavaSourceParser(List.of(withLevel));
    ParsedSource ps = parser.parse(src);

    // This is the bug: before the chain was wired up, the level was 17 no matter what the build
    // description said, so a Java 21 project failed to parse.
    assertThat(ps.problems()).as("level 17 cannot parse switch patterns").isNotEmpty();
  }

  @Test
  @DisplayName("the level mapping covers Java 8 through Java 25")
  void levelMappingCoversJava8To25() {
    assertThat(JavaLanguageLevel.resolve("8")).isEqualTo(ParserConfiguration.LanguageLevel.JAVA_8);
    assertThat(JavaLanguageLevel.resolve("11"))
        .isEqualTo(ParserConfiguration.LanguageLevel.JAVA_11);
    assertThat(JavaLanguageLevel.resolve("17"))
        .isEqualTo(ParserConfiguration.LanguageLevel.JAVA_17);
    assertThat(JavaLanguageLevel.resolve("21"))
        .isEqualTo(ParserConfiguration.LanguageLevel.JAVA_21);

    // JavaParser 3.26.4 implements no grammar newer than Java 21, and its
    // BLEEDING_EDGE constant IS the JAVA_21 constant. So Java 22-25 parse at
    // Java 21's grammar. Mapping them to BLEEDING_EDGE would promise a newer
    // grammar than the parser delivers -- and a Java 25-only construct would then
    // fail with an error that looks like a Plumb defect.
    assertThat(JavaLanguageLevel.resolve("22"))
        .isEqualTo(ParserConfiguration.LanguageLevel.JAVA_21);
    assertThat(JavaLanguageLevel.resolve("25"))
        .isEqualTo(ParserConfiguration.LanguageLevel.JAVA_21);
    assertThat(JavaLanguageLevel.resolve("latest"))
        .isEqualTo(ParserConfiguration.LanguageLevel.JAVA_21);
  }

  @Test
  @DisplayName("Maven-style 1.8 means Java 8")
  void mavenStyleVersionIsAccepted() {
    assertThat(JavaLanguageLevel.resolve("1.8"))
        .isEqualTo(ParserConfiguration.LanguageLevel.JAVA_8);
    assertThat(JavaLanguageLevel.resolve("1.21"))
        .isEqualTo(ParserConfiguration.LanguageLevel.JAVA_21);
  }

  @Test
  @DisplayName("an absent level falls back to the default, an unknown one is not silently accepted")
  void fallbackBehaviour() {
    assertThat(JavaLanguageLevel.resolve(null)).isEqualTo(JavaLanguageLevel.DEFAULT);
    assertThat(JavaLanguageLevel.resolve("")).isEqualTo(JavaLanguageLevel.DEFAULT);
    assertThat(JavaLanguageLevel.resolve("not-a-version")).isEqualTo(JavaLanguageLevel.DEFAULT);

    assertThat(JavaLanguageLevel.isKnown("21")).isTrue();
    assertThat(JavaLanguageLevel.isKnown("25")).isTrue();
    assertThat(JavaLanguageLevel.isKnown("7")).isFalse();
    assertThat(JavaLanguageLevel.isKnown("26")).isFalse();
    assertThat(JavaLanguageLevel.isKnown("bogus")).isFalse();
  }

  @Test
  @DisplayName("the default is the newest NAMED level, not an older one")
  void defaultIsTheNewestNamedLevel() {
    // The default must be a level JavaParser names. BLEEDING_EDGE would accept a grammar
    // newer than any released JDK, so a default scan could accept source that javac
    // would reject -- a confusing report for the user.
    assertThat(JavaLanguageLevel.DEFAULT).isEqualTo(ParserConfiguration.LanguageLevel.JAVA_21);
  }

  @Test
  @DisplayName("Java 21 syntax parses with NO declared level")
  void java21SyntaxParsesAtTheDefault() throws Exception {
    // A project that says nothing about its level gets the default, and the default has
    // to be recent enough for modern source. This is the case the old JAVA_17 default
    // broke: the file simply did not parse.
    Path root = Files.createTempDirectory("langdefault");
    Path src = root.resolve("Use.java");
    Files.writeString(src, JAVA_21_SOURCE);

    JavaModule noLevel =
        JavaModule.singleRoot("m", root, List.of(), JavaModule.Scope.COMPILE, null, null);

    JavaSourceParser parser = new JavaSourceParser(List.of(noLevel));
    ParsedSource ps = parser.parse(src);

    assertThat(ps.problems())
        .as("Java 21 syntax must parse at the default level; problems were: %s", ps.problems())
        .isEmpty();
  }

  @Test
  @DisplayName("the default parser also accepts Java 21 syntax")
  void defaultParserAcceptsJava21Syntax() {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("Use.java"), JAVA_21_SOURCE);
    assertThat(ps.problems())
        .as("the no-arg parser must accept modern syntax; problems were: %s", ps.problems())
        .isEmpty();
  }

  @Test
  @DisplayName("what BLEEDING_EDGE actually is in JavaParser 3.26.4")
  void bleedingEdgeIdentity() {
    // A VERIFIED FACT about JavaParser 3.26.4, checked by identity rather than by name:
    // BLEEDING_EDGE and JAVA_21 are the SAME enum constant.
    //
    // The consequence is significant. The obvious mapping -- "Java 22-25 ->
    // BLEEDING_EDGE, which accepts a newer grammar" -- is false: it accepts exactly
    // Java 21's grammar. Mapping 22-25 to it would promise more than the parser
    // delivers, and a Java 25-only construct would fail with an error that reads as
    // "Plumb does not support Java 25" rather than "the parser does not".
    //
    // So the mapping goes to JAVA_21, and the level resolver's javadoc says why.
    assertThat(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        .as("BLEEDING_EDGE must be the same constant as JAVA_21 in JavaParser 3.26.4")
        .isSameAs(ParserConfiguration.LanguageLevel.JAVA_21);
    assertThat(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        .isNotSameAs(ParserConfiguration.LanguageLevel.JAVA_17);
  }
}
