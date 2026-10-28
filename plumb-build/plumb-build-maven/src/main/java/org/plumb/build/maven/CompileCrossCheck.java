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
package org.plumb.build.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.plumb.build.CompileDiscrepancy;

/**
 * Compares what a build <em>declared</em> against what the compiler <em>actually did</em>.
 *
 * <p><b>Why the two are compared at all.</b> The two sides answer the same questions -- what
 * language level, what encoding, what classpath -- and they are used together: a build is analysed
 * by reading its pom AND by capturing the compile it really performed. When they disagree, one of
 * them is wrong, and the analysis is wrong in a way nobody can see.
 *
 * <p><b>Why the difference is reported rather than resolved.</b> There is no right answer to pick
 * in general: the pom may be stale, the capture may be from a different build, and the two may
 * describe different modules. A cross-check that silently chose one side would hide exactly the
 * thing it was built to find.
 *
 * <p><b>Why the capture is read as JSON.</b> The capture module is deliberately standalone: the
 * command parsing and the capture are not part of the analysis, so the analysis does not depend on
 * them. The capture's output is a file, and a file is what the analyser reads.
 *
 * <p><b>Why the result is sorted.</b> Two runs over the same inputs must produce the same report,
 * in the same order. The differences are sorted by parameter name, and the values within a
 * parameter are sorted too.
 */
public final class CompileCrossCheck {

  /** The parameter names this check compares. */
  public static final String LANGUAGE_LEVEL = "languageLevel";

  public static final String ENCODING = "encoding";
  public static final String OUTPUT_DIRECTORY = "outputDirectory";
  public static final String CLASSPATH = "classpath";
  public static final String SOURCE_PATH = "sourcePath";
  public static final String SOURCE_FILES = "sourceFiles";

  private CompileCrossCheck() {}

  /**
   * Compares one captured compile against one module's declaration.
   *
   * @param capture the capture's JSON, as the agent wrote it
   * @param declared the module's declaration
   * @return the differences, sorted by parameter name
   */
  public static List<CompileDiscrepancy> compare(String capture, ModuleDescriptor declared) {
    CapturedCompile observed = CapturedCompile.parse(capture);
    if (observed == null) {
      return List.of();
    }

    // A map, so the differences come out in a fixed order.
    TreeMap<String, CompileDiscrepancy> byParameter = new TreeMap<>();

    compareValue(
        byParameter,
        LANGUAGE_LEVEL,
        declared.languageLevel(),
        observed.languageLevel(),
        "the language level the build declared and the one the compiler used");

    compareValue(
        byParameter,
        ENCODING,
        declared.encoding() == null ? null : declared.encoding().name(),
        observed.encoding(),
        "the source encoding the build declared and the one the compiler used");

    comparePaths(
        byParameter,
        OUTPUT_DIRECTORY,
        declared.outputDirectory() == null ? null : List.of(declared.outputDirectory()),
        observed.outputDirectory() == null ? List.of() : List.of(observed.outputDirectory()),
        "the output directory the build declared and the one the compiler wrote to");

    comparePaths(
        byParameter,
        CLASSPATH,
        declared.classpath(),
        observed.classpath(),
        "the classpath the build resolved and the one the compiler received");

    comparePaths(
        byParameter,
        SOURCE_PATH,
        List.of(),
        observed.sourcePath(),
        "the source path the compiler used; the pom declares no source path of its own");

    comparePaths(
        byParameter,
        SOURCE_FILES,
        List.of(),
        observed.sourceFiles(),
        "the source files the compiler compiled");

    return new ArrayList<>(byParameter.values());
  }

  /**
   * Compares one value.
   *
   * <p>An absent value on either side is <em>not</em> a difference: an undeclared parameter is
   * unknown, not a default, so there is nothing to disagree about. A difference needs both sides to
   * have an answer.
   */
  private static void compareValue(
      TreeMap<String, CompileDiscrepancy> byParameter,
      String parameter,
      String declared,
      String observed,
      String detail) {
    if (declared == null || observed == null) {
      return;
    }
    if (declared.equals(observed)) {
      return;
    }
    byParameter.put(parameter, new CompileDiscrepancy(parameter, declared, observed, detail));
  }

  /**
   * Compares two lists of paths.
   *
   * <p>The comparison is on the <em>names</em> of the paths, not on the paths themselves: the
   * declared classpath holds resolved jars and the observed one holds the paths the compiler was
   * given, and the two are written differently on every platform.
   *
   * <p>Only an entry that is on one side and not the other is a difference. The two sides
   * legitimately differ in how much they know: the pom does not know which jars the compiler was
   * given, and the capture does not know which jars the build declared.
   */
  private static void comparePaths(
      TreeMap<String, CompileDiscrepancy> byParameter,
      String parameter,
      List<Path> declared,
      List<Path> observed,
      String detail) {
    Set<String> declaredNames = namesOf(declared);
    Set<String> observedNames = namesOf(observed);
    if (declaredNames.isEmpty() || observedNames.isEmpty()) {
      // One side knows nothing about this parameter: there is nothing to
      // disagree about.
      return;
    }
    Set<String> onlyDeclared = new LinkedHashSet<>(declaredNames);
    onlyDeclared.removeAll(observedNames);
    Set<String> onlyObserved = new LinkedHashSet<>(observedNames);
    onlyObserved.removeAll(declaredNames);
    if (onlyDeclared.isEmpty() && onlyObserved.isEmpty()) {
      return;
    }
    byParameter.put(
        parameter,
        new CompileDiscrepancy(
            parameter,
            String.join(",", declaredNames),
            String.join(",", observedNames),
            detail
                + "; only in the declaration: "
                + onlyDeclared
                + ", only in the capture: "
                + onlyObserved));
  }

  /** The file names of a list of paths, sorted. */
  private static Set<String> namesOf(List<Path> paths) {
    Set<String> out = new LinkedHashSet<>();
    if (paths == null) {
      return out;
    }
    for (Path p : paths) {
      if (p == null) {
        continue;
      }
      out.add(p.getFileName() == null ? p.toString() : p.getFileName().toString());
    }
    return out;
  }

  /**
   * The captured compile, as the cross-check needs it.
   *
   * <p>Read from the capture's JSON, because the capture module is deliberately not a dependency of
   * the analysis.
   */
  static final class CapturedCompile {

    final String languageLevel;
    final String encoding;
    final Path outputDirectory;
    final List<Path> classpath;
    final List<Path> sourcePath;
    final List<Path> sourceFiles;

    private CapturedCompile(
        String languageLevel,
        String encoding,
        Path outputDirectory,
        List<Path> classpath,
        List<Path> sourcePath,
        List<Path> sourceFiles) {
      this.languageLevel = languageLevel;
      this.encoding = encoding;
      this.outputDirectory = outputDirectory;
      this.classpath = classpath;
      this.sourcePath = sourcePath;
      this.sourceFiles = sourceFiles;
    }

    /** The language level the compiler used. */
    String languageLevel() {
      return languageLevel;
    }

    /** The source encoding the compiler used. */
    String encoding() {
      return encoding;
    }

    /** The directory the compiler wrote to. */
    Path outputDirectory() {
      return outputDirectory;
    }

    /** The classpath the compiler received. */
    List<Path> classpath() {
      return classpath;
    }

    /** The source path the compiler used. */
    List<Path> sourcePath() {
      return sourcePath;
    }

    /** The source files the compiler compiled. */
    List<Path> sourceFiles() {
      return sourceFiles;
    }

    /**
     * The captured compile in a JSON file.
     *
     * @param file the capture file
     * @return the compile, or {@code null} when the file cannot be read
     */
    static CapturedCompile read(Path file) {
      try {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
      } catch (IOException e) {
        return null;
      }
    }

    /**
     * The captured compile in a JSON document.
     *
     * <p>The capture's JSON is written by hand, so it is a flat object of string and array values.
     * It is read here by hand too, rather than with a JSON library: the module has no JSON
     * dependency, and the format is small enough that a library would be more machinery than the
     * job needs.
     *
     * @param json the capture's JSON
     * @return the compile, or {@code null} when the document is not a capture
     */
    static CapturedCompile parse(String json) {
      if (json == null || json.isBlank()) {
        return null;
      }
      String languageLevel = stringField(json, "languageLevel");
      String encoding = stringField(json, "encoding");
      Path outputDirectory = pathField(json, "outputDirectory");
      List<Path> classpath = pathArray(json, "classpath");
      List<Path> sourcePath = pathArray(json, "sourcePath");
      List<Path> sourceFiles = pathArray(json, "sourceFiles");
      if (languageLevel == null
          && encoding == null
          && outputDirectory == null
          && classpath.isEmpty()
          && sourcePath.isEmpty()
          && sourceFiles.isEmpty()) {
        // Not a capture at all: something else that happens to be JSON.
        return null;
      }
      return new CapturedCompile(
          languageLevel, encoding, outputDirectory, classpath, sourcePath, sourceFiles);
    }

    /** The value of a string field, or {@code null}. */
    private static String stringField(String json, String name) {
      String marker = "\"" + name + "\"";
      int at = json.indexOf(marker);
      if (at < 0) {
        return null;
      }
      int colon = json.indexOf(':', at + marker.length());
      if (colon < 0) {
        return null;
      }
      // The value may be the literal `null`, and then there is no opening
      // quote to find.  This has to be checked BEFORE the quote is looked
      // for: without it, the reader runs past the `null` and picks up the
      // next field's key as the value.
      int after = colon + 1;
      while (after < json.length() && Character.isWhitespace(json.charAt(after))) {
        after++;
      }
      if (json.startsWith("null", after)) {
        return null;
      }
      int start = json.indexOf('"', after);
      if (start < 0) {
        return null;
      }
      int end = start + 1;
      while (end < json.length()) {
        char c = json.charAt(end);
        if (c == '\\') {
          end += 2;
          continue;
        }
        if (c == '"') {
          break;
        }
        end++;
      }
      if (end >= json.length()) {
        return null;
      }
      return unescape(json.substring(start + 1, end));
    }

    /** The value of a path field, or {@code null}. */
    private static Path pathField(String json, String name) {
      String value = stringField(json, name);
      if (value == null || value.isBlank()) {
        return null;
      }
      try {
        return Path.of(value);
      } catch (RuntimeException e) {
        return null;
      }
    }

    /** The values of an array-of-paths field. */
    private static List<Path> pathArray(String json, String name) {
      List<Path> out = new ArrayList<>();
      String marker = "\"" + name + "\"";
      int at = json.indexOf(marker);
      if (at < 0) {
        return out;
      }
      int open = json.indexOf('[', at);
      if (open < 0) {
        return out;
      }
      int close = json.indexOf(']', open);
      if (close < 0) {
        return out;
      }
      String body = json.substring(open + 1, close);
      // The values are quoted strings, separated by commas.
      int i = 0;
      while (i < body.length()) {
        int start = body.indexOf('"', i);
        if (start < 0) {
          break;
        }
        int end = start + 1;
        while (end < body.length()) {
          char c = body.charAt(end);
          if (c == '\\') {
            end += 2;
            continue;
          }
          if (c == '"') {
            break;
          }
          end++;
        }
        if (end >= body.length()) {
          break;
        }
        String value = unescape(body.substring(start + 1, end));
        if (value != null && !value.isBlank()) {
          try {
            out.add(Path.of(value));
          } catch (RuntimeException e) {
            // Not a path: skip it.
          }
        }
        i = end + 1;
      }
      return out;
    }

    /** The escape sequences the capture writes. */
    private static String unescape(String value) {
      return value
          .replace("\\\"", "\"")
          .replace("\\\\", "\\")
          .replace("\\n", "\n")
          .replace("\\r", "\r")
          .replace("\\t", "\t");
    }
  }
}
