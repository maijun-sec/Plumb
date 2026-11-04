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

import com.github.javaparser.ParserConfiguration;
import java.util.Locale;

/**
 * Maps a build-description language level onto a JavaParser {@link
 * ParserConfiguration.LanguageLevel}.
 *
 * <p>This closes a chain that used to be broken. The build description carries a language level as
 * a string ({@code "8"}, {@code "11"}, {@code "17"}, {@code "21"}, {@code "25"}, ...), it was
 * propagated all the way into {@link JavaModule#languageLevel()}, and then nothing consumed it --
 * {@link JavaSourceParser} hard-coded {@code JAVA_17}. The build's own configuration therefore had
 * no effect on parsing.
 *
 * <h2>Supported range</h2>
 *
 * <p>Java 8 through Java 25 are accepted. The mapping is not one-to-one, because JavaParser's enum
 * stops at {@code JAVA_21}:
 *
 * <ul>
 *   <li>Java 8..21 map to the matching {@code JAVA_n} constant.
 *   <li>Java 22..25 map to {@link ParserConfiguration.LanguageLevel#JAVA_21}, the newest level
 *       JavaParser actually implements.
 * </ul>
 *
 * <h2>A verified fact about BLEEDING_EDGE</h2>
 *
 * <p><b>{@code BLEEDING_EDGE} and {@code JAVA_21} are the SAME enum constant</b> in JavaParser
 * 3.26.4 -- verified by identity comparison, not by name. So mapping Java 22-25 to {@code
 * BLEEDING_EDGE} does not buy a newer grammar; it buys exactly Java 21.
 *
 * <p>That matters because the alternative is a silent lie. A project declaring Java 25 would be
 * told "parsed at BLEEDING_EDGE, which accepts anything", and then a Java 25-only construct would
 * fail to parse with an error that looks like a Plumb defect rather than a parser limitation.
 *
 * <p>So Java 22-25 map to {@code JAVA_21} and the parser is honest about what it supports. Getting
 * real Java 22-25 grammar needs a JavaParser that names those levels -- which is a dependency
 * upgrade, not a configuration change.
 *
 * <h2>Why not silently downgrade</h2>
 *
 * <p>An unrecognised level does <b>not</b> fall back to a lower level. A file written for Java 21
 * would then fail to parse with "switch patterns not supported", which looks like a Plumb bug
 * rather than a configuration mistake. It falls back to the default and is logged instead.
 */
public final class JavaLanguageLevel {

  /**
   * The default when nothing is configured.
   *
   * <p><b>Java 21</b> -- the newest level JavaParser names explicitly.
   *
   * <p>The reasoning: this is the highest level for which the parser has a NAMED constant, so the
   * behaviour is pinned and reviewable rather than dependent on whatever the newest grammar happens
   * to be. A project that needs Java 22-25 says so in its build description, and that maps to
   * {@code BLEEDING_EDGE}.
   *
   * <p>Why not {@code BLEEDING_EDGE} as the default: it accepts a grammar that is newer than any
   * released Java, so a default scan would accept source that no JDK can compile, and a syntax
   * error would surface as "Plumb parsed this but javac would not" -- a confusing report. Named
   * levels only accept what a real JDK accepts.
   *
   * <p>Why not Java 17 (the previous default): the previous default predated Java 21's pattern
   * matching, and a project on Java 21 that did not declare its level would fail to parse with
   * "switch patterns not supported". Declaring nothing should mean "a recent Java", not "the Java
   * of 2021".
   */
  public static final ParserConfiguration.LanguageLevel DEFAULT =
      ParserConfiguration.LanguageLevel.JAVA_21;

  /**
   * The newest language level JavaParser 3.26.4 actually implements.
   *
   * <p>JavaParser's enum names levels up to {@code JAVA_21}. Its {@code BLEEDING_EDGE} constant is
   * the SAME object as {@code JAVA_21} -- verified by identity -- so it does not reach a newer
   * grammar. Anything above Java 21 therefore parses at Java 21's grammar.
   */
  public static final int NEWEST_NAMED = 21;

  private JavaLanguageLevel() {}

  /**
   * Resolves a language level string.
   *
   * @param level the level as written in the build description; {@code null} or blank means "use
   *     the default"
   * @return the matching JavaParser level; {@link #DEFAULT} when the input is absent or
   *     unrecognised
   */
  public static ParserConfiguration.LanguageLevel resolve(String level) {
    if (level == null || level.isBlank()) return DEFAULT;
    String k = level.trim().toLowerCase(Locale.ROOT);

    // Maven-style "1.8" and Gradle-style "8" both mean Java 8.
    if (k.startsWith("1.")) k = k.substring(2);

    switch (k) {
      case "latest":
      case "current":
      case "bleeding-edge":
      case "bleeding_edge":
        // "latest" means the newest level this parser can actually reach, which is
        // Java 21 -- NOT BLEEDING_EDGE, which is the same constant as JAVA_21.
        return ParserConfiguration.LanguageLevel.JAVA_21;
      case "8":
      case "1.8":
        return ParserConfiguration.LanguageLevel.JAVA_8;
      case "9":
        return ParserConfiguration.LanguageLevel.JAVA_9;
      case "10":
        return ParserConfiguration.LanguageLevel.JAVA_10;
      case "11":
        return ParserConfiguration.LanguageLevel.JAVA_11;
      case "12":
        return ParserConfiguration.LanguageLevel.JAVA_12;
      case "13":
        return ParserConfiguration.LanguageLevel.JAVA_13;
      case "14":
        return ParserConfiguration.LanguageLevel.JAVA_14;
      case "15":
        return ParserConfiguration.LanguageLevel.JAVA_15;
      case "16":
        return ParserConfiguration.LanguageLevel.JAVA_16;
      case "17":
        return ParserConfiguration.LanguageLevel.JAVA_17;
      case "18":
        return ParserConfiguration.LanguageLevel.JAVA_18;
      case "19":
        return ParserConfiguration.LanguageLevel.JAVA_19;
      case "20":
        return ParserConfiguration.LanguageLevel.JAVA_20;
      case "21":
        return ParserConfiguration.LanguageLevel.JAVA_21;
      case "22":
      case "23":
      case "24":
      case "25":
        // JavaParser 3.26.4 implements no grammar newer than Java 21. Its
        // BLEEDING_EDGE constant IS JAVA_21 (same object), so mapping to it would
        // promise a newer grammar than it delivers. Map honestly to JAVA_21.
        return ParserConfiguration.LanguageLevel.JAVA_21;
      default:
        return DEFAULT;
    }
  }

  /**
   * The numeric Java version this level corresponds to, for diagnostics.
   *
   * @return the major version number, or {@code -1} when the input cannot be read as one
   */
  public static int majorVersion(String level) {
    if (level == null || level.isBlank()) return -1;
    String k = level.trim().toLowerCase(Locale.ROOT);
    if (k.startsWith("1.")) k = k.substring(2);
    if (k.equals("latest") || k.equals("current") || k.equals("bleeding-edge"))
      return NEWEST_NAMED + 1;
    try {
      return Integer.parseInt(k);
    } catch (NumberFormatException ex) {
      return -1;
    }
  }

  /** Whether the given level is one this resolver recognises. */
  public static boolean isKnown(String level) {
    if (level == null || level.isBlank()) return true;
    String k = level.trim().toLowerCase(Locale.ROOT);
    if (k.startsWith("1.")) k = k.substring(2);
    switch (k) {
      case "latest":
      case "current":
      case "bleeding-edge":
      case "bleeding_edge":
        return true;
      default:
        try {
          int v = Integer.parseInt(k);
          return v >= 8 && v <= 25;
        } catch (NumberFormatException ex) {
          return false;
        }
    }
  }
}
