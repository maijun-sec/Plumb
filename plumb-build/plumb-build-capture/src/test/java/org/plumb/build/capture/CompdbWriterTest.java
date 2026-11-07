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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The compilation database the captures are written as.
 *
 * <p><b>Why the shape is pinned exactly.</b> A compilation database is an interchange format: the
 * producer writes it, another tool reads it, and neither side shares code with the other. A
 * consumer that expects the keys in a different order, or that finds {@code file} spelled with
 * backslashes, cannot read the file -- and the failure is a silent "no compilation units found"
 * rather than an error. So the shape is asserted byte for byte, not by parsing it back.
 */
@DisplayName("Capture: the compilation database format")
class CompdbWriterTest {

  /** A capture whose fields exercise the format's interesting cases. */
  private static CaptureEntry entry(
      String compiler, List<String> argv, List<String> sourceFiles, String workingDirectory) {
    return new CaptureEntry(
        "cli",
        compiler,
        "java",
        argv,
        "UTF-8",
        "17",
        null,
        List.<Path>of(),
        List.<Path>of(),
        List.<Path>of(),
        List.<Path>of(),
        List.<Path>of(),
        sourceFiles.stream().map(Paths::get).toList(),
        List.<String>of(),
        List.<String>of(),
        List.<String>of(),
        false,
        true,
        "17.0.1",
        "/opt/jdk",
        workingDirectory);
  }

  @Test
  @DisplayName("the database is an array of three-key objects")
  void theDatabaseIsAnArrayOfThreeKeyObjects() {
    String out =
        CompdbWriter.write(
            List.of(
                entry(
                    "javac",
                    List.of("javac", "-d", "out", "Hello.java"),
                    List.of("/proj/src/Hello.java"),
                    "/proj")));

    assertThat(out)
        .as(
            "the format is a JSON ARRAY of objects with the keys directory, arguments, file -- in"
                + " that order, because a consumer reads it positionally")
        .isEqualTo(
            """
            [
              {
                "directory": "/proj",
                "arguments": ["javac", "-d", "out", "Hello.java"],
                "file": "/proj/src/Hello.java"
              }
            ]
            """);
  }

  @Test
  @DisplayName("an empty capture list is an empty array")
  void anEmptyCaptureListIsAnEmptyArray() {
    assertThat(CompdbWriter.write(List.of())).isEqualTo("[\n]\n");
    assertThat(CompdbWriter.write(null)).isEqualTo("[\n]\n");
  }

  @Test
  @DisplayName("a capture with no source file has no entry")
  void aCaptureWithNoSourceFileHasNoEntry() {
    // A capture with no source file describes no compilation unit. An entry
    // for it would name a "file" that does not exist.
    String out = CompdbWriter.write(List.of(entry("javac", List.of("javac"), List.of(), "/proj")));

    assertThat(out).as("a compile with no unit has no entry").isEqualTo("[\n]\n");
  }

  @Test
  @DisplayName("the directory and the file are normalized, the arguments are not")
  void theDirectoryAndTheFileAreNormalizedTheArgumentsAreNot() {
    // This is the distinction that makes the format readable by another tool:
    // the two path fields are resolved by the consumer, so they are absolute
    // and unambiguous; the arguments are what the compiler was given, so they
    // are reproduced as they were.
    String out =
        CompdbWriter.write(
            List.of(
                entry(
                    "javac",
                    List.of("D:\\jdk\\bin\\javac.exe", "-d", ".trash\\out", ".trash\\Hello.java"),
                    List.of("D:\\proj\\src\\Hello.java"),
                    "D:\\proj\\build")));

    assertThat(out)
        .as(
            "directory and file get forward slashes; the arguments keep the"
                + " backslashes the compiler actually received")
        .contains("\"directory\": \"D:/proj/build\"")
        .contains("\"file\": \"D:/proj/src/Hello.java\"")
        .contains(
            "\"arguments\": [\"D:\\\\jdk\\\\bin\\\\javac.exe\", \"-d\", \".trash\\\\out\","
                + " \".trash\\\\Hello.java\"]");
  }

  @Test
  @DisplayName("a relative source file is resolved against the compile's directory")
  void aRelativeSourceFileIsResolvedAgainstTheCompilesDirectory() {
    String out =
        CompdbWriter.write(
            List.of(
                entry(
                    "javac",
                    List.of("javac", "src\\Hello.java"),
                    List.of("src\\Hello.java"),
                    "D:\\proj")));

    assertThat(out)
        .as("a relative path is meaningless to a consumer that runs elsewhere")
        .contains("\"file\": \"D:/proj/src/Hello.java\"");
  }

  @Test
  @DisplayName("the compile's directory falls back to the current one")
  void theCompilesDirectoryFallsBackToTheCurrentOne() {
    CaptureEntry noDirectory =
        new CaptureEntry(
            "cli",
            "javac",
            "java",
            List.of("javac", "Hello.java"),
            "UTF-8",
            "17",
            null,
            List.<Path>of(),
            List.<Path>of(),
            List.<Path>of(),
            List.<Path>of(),
            List.<Path>of(),
            List.of(Paths.get("Hello.java")),
            List.<String>of(),
            List.<String>of(),
            List.<String>of(),
            false,
            true,
            "17.0.1",
            "/opt/jdk",
            null);

    String out = CompdbWriter.write(List.of(noDirectory));

    assertThat(out)
        .as("without a working directory the current one is the only answer")
        .contains("\"directory\": \"")
        .contains("\"file\": \"");
  }

  @Test
  @DisplayName("a capture with an empty argv names the compiler instead")
  void aCaptureWithAnEmptyArgvNamesTheCompilerInstead() {
    // The API path can produce a capture with no recorded argv. An empty
    // "arguments" array would describe no compiler at all.
    String out =
        CompdbWriter.write(List.of(entry("kotlin", List.of(), List.of("/proj/Main.kt"), "/proj")));

    assertThat(out)
        .as("the compiler is argv[0] by definition")
        .contains("\"arguments\": [\"kotlin\"]");
  }

  @Test
  @DisplayName("only the first source file becomes the entry's unit")
  void onlyTheFirstSourceFileBecomesTheEntrysUnit() {
    String out =
        CompdbWriter.write(
            List.of(
                entry(
                    "javac",
                    List.of("javac", "A.java", "B.java"),
                    List.of("/proj/A.java", "/proj/B.java"),
                    "/proj")));

    assertThat(out)
        .as(
            "the format has one file per entry, so a multi-source compile cannot"
                + " be represented as one entry per source -- and the second file"
                + " survives only inside the arguments, which are verbatim")
        .contains("\"file\": \"/proj/A.java\"")
        .doesNotContain("\"file\": \"/proj/B.java\"");
  }

  @Test
  @DisplayName("paths are normalized the way a consumer resolves them")
  void pathsAreNormalizedTheWayAConsumerResolvesThem() {
    assertThat(CompdbWriter.normalizePath("D:\\work\\proj"))
        .as("backslashes become forward slashes")
        .isEqualTo("D:/work/proj");
    assertThat(CompdbWriter.normalizePath("D:/work/./proj"))
        .as("a current-directory segment is dropped")
        .isEqualTo("D:/work/proj");
    assertThat(CompdbWriter.normalizePath("D:/work/build/../proj"))
        .as("a parent segment collapses with the one before it")
        .isEqualTo("D:/work/proj");
    assertThat(CompdbWriter.normalizePath("D:/work/../../proj"))
        .as("a parent segment above an absolute root is dropped, not kept")
        .isEqualTo("D:/proj");
    assertThat(CompdbWriter.normalizePath("/work/../proj"))
        .as("the same rule holds for a rooted path")
        .isEqualTo("/proj");
    assertThat(CompdbWriter.normalizePath("a/../b"))
        .as("a relative path keeps its parent segments, because they mean something there")
        .isEqualTo("b");
    assertThat(CompdbWriter.normalizePath("../b"))
        .as("a relative path that cannot collapse keeps the segment")
        .isEqualTo("../b");
    assertThat(CompdbWriter.normalizePath("D:/work/Proj"))
        .as("the case of a path is left alone: it can name a different file")
        .isEqualTo("D:/work/Proj");
    assertThat(CompdbWriter.normalizePath("")).isEqualTo("");
  }

  @Test
  @DisplayName("the quote escapes what a JSON parser needs escaped")
  void theQuoteEscapesWhatAJsonParserNeedsEscaped() {
    assertThat(CompdbWriter.quote("a\"b")).isEqualTo("\"a\\\"b\"");
    assertThat(CompdbWriter.quote("a\\b")).isEqualTo("\"a\\\\b\"");
    assertThat(CompdbWriter.quote("a\nb")).isEqualTo("\"a\\nb\"");
    assertThat(CompdbWriter.quote("a\tb")).isEqualTo("\"a\\tb\"");
    assertThat(CompdbWriter.quote("a\u0001b")).isEqualTo("\"a\\u0001b\"");
    assertThat(CompdbWriter.quote("\u4e2d\u6587"))
        .as("non-ASCII is emitted as it is, which JSON allows")
        .isEqualTo("\"\u4e2d\u6587\"");
    assertThat(CompdbWriter.quote(null)).isEqualTo("null");
  }

  @Test
  @DisplayName("absolute paths are recognised in both shapes")
  void absolutePathsAreRecognisedInBothShapes() {
    assertThat(CompdbWriter.isAbsolute("/proj/src")).isTrue();
    assertThat(CompdbWriter.isAbsolute("D:/proj/src")).isTrue();
    assertThat(CompdbWriter.isAbsolute("D:\\proj\\src")).isTrue();
    assertThat(CompdbWriter.isAbsolute("src/Hello.java")).isFalse();
    assertThat(CompdbWriter.isAbsolute("")).isFalse();
    assertThat(CompdbWriter.isAbsolute(null)).isFalse();
  }

  @Test
  @DisplayName("the captures a directory holds become one database")
  void theCapturesADirectoryHoldsBecomeOneDatabase() throws Exception {
    // The end-to-end shape: write captures to a directory the way the agent
    // does, read them back, and turn them into one database.
    Path dir = Files.createTempDirectory("compdb-test");
    try {
      CaptureEntry javac = entry("javac", List.of("javac", "A.java"), List.of("/p/A.java"), "/p");
      CaptureEntry kotlin = entry("kotlin", List.of("kotlinc", "B.kt"), List.of("/p/B.kt"), "/p");

      // The agent names a file after the compiler, and the reader recognises
      // the name -- so a capture written by one is found by the other.
      Files.writeString(dir.resolve("javac-1.json"), javac.toJson());
      Files.writeString(dir.resolve("kotlin-2.json"), kotlin.toJson());

      List<CaptureEntry> read = CaptureReader.readAll(dir);
      assertThat(read).as("both captures are read back").hasSize(2);

      String out = CompdbWriter.write(read);
      assertThat(out)
          .as("both languages appear, each as its own entry")
          .contains("\"file\": \"/p/A.java\"")
          .contains("\"file\": \"/p/B.kt\"")
          .contains("\"arguments\": [\"javac\", \"A.java\"]")
          .contains("\"arguments\": [\"kotlinc\", \"B.kt\"]");
    } finally {
      Files.deleteIfExists(dir.resolve("javac-1.json"));
      Files.deleteIfExists(dir.resolve("kotlin-2.json"));
      Files.deleteIfExists(dir);
    }
  }
}
