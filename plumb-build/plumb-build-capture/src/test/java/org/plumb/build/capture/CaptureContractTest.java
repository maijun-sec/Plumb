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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The capture contract covers every compiler the agent can hook, not just javac.
 *
 * <p><b>Why this matters.</b> The reader used to accept {@code javac-} files only. A directory
 * holding a Kotlin or a Scala capture was therefore a directory whose captures were <em>silently
 * dropped</em>: the analyser saw no Kotlin compile, and reported nothing, and the scan reported
 * success.
 *
 * <p><b>What the contract is.</b> The capture file is named after the compiler that produced it,
 * the record carries the compiler AND the language, and the reader accepts every compiler the agent
 * hooks.
 */
class CaptureContractTest {

  /** A capture JSON with the given compiler and language. */
  private static String captureJson(String compiler, String language) {
    return "{\n"
        + "  \"api\": \"cli\",\n"
        + "  \"compiler\": \""
        + compiler
        + "\",\n"
        + "  \"language\": \""
        + language
        + "\",\n"
        + "  \"argv\": [\"-d\", \"out\", \"src/Foo."
        + language
        + "\"],\n"
        + "  \"encoding\": \"UTF-8\",\n"
        + "  \"languageLevel\": \"17\",\n"
        + "  \"outputDirectory\": \"out\",\n"
        + "  \"classpath\": [\"a.jar\"],\n"
        + "  \"sourceFiles\": [\"src/Foo."
        + language
        + "\"]\n"
        + "}\n";
  }

  @Test
  void acceptsACaptureFromEveryCompilerTheAgentHooks(@TempDir Path dir) throws Exception {
    // One file per compiler, each named after the compiler that wrote it.
    Files.writeString(dir.resolve("javac-1.json"), captureJson("javac", "java"));
    Files.writeString(dir.resolve("ecj-2.json"), captureJson("ecj", "java"));
    Files.writeString(dir.resolve("sjavac-3.json"), captureJson("sjavac", "java"));
    Files.writeString(dir.resolve("kotlin-4.json"), captureJson("kotlin", "kotlin"));
    Files.writeString(dir.resolve("scala3-5.json"), captureJson("scala3", "scala"));
    Files.writeString(dir.resolve("scala2-6.json"), captureJson("scala2", "scala"));

    List<CaptureEntry> captures = CaptureReader.readAll(dir);

    assertThat(captures).as("every compiler's capture must be read, not just javac's").hasSize(6);
    assertThat(captures)
        .extracting(CaptureEntry::compiler)
        .containsExactlyInAnyOrder("javac", "ecj", "sjavac", "kotlin", "scala3", "scala2");
  }

  @Test
  void aCaptureCarriesTheLanguageAsWellAsTheCompiler(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("kotlin-1.json"), captureJson("kotlin", "kotlin"));

    CaptureEntry entry = CaptureReader.readAll(dir).get(0);

    // The language is separate from the compiler because two compilers compile
    // the same language, and a consumer usually cares about the language.
    assertThat(entry.language()).isEqualTo("kotlin");
    assertThat(entry.compiler()).isEqualTo("kotlin");
  }

  @Test
  void readsTheCapturesOfOneLanguage(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("javac-1.json"), captureJson("javac", "java"));
    Files.writeString(dir.resolve("ecj-2.json"), captureJson("ecj", "java"));
    Files.writeString(dir.resolve("kotlin-3.json"), captureJson("kotlin", "kotlin"));
    Files.writeString(dir.resolve("scala3-4.json"), captureJson("scala3", "scala"));

    assertThat(CaptureReader.readAll(dir, "java")).hasSize(2);
    assertThat(CaptureReader.readAll(dir, "kotlin")).hasSize(1);
    assertThat(CaptureReader.readAll(dir, "scala")).hasSize(1);
  }

  @Test
  void ignoresAFileThatIsNotACapture(@TempDir Path dir) throws Exception {
    // A JSON file that is not a capture must not be parsed as one: the
    // file name says what it is, and a random JSON has no business here.
    Files.writeString(dir.resolve("notes.json"), "{\"hello\": \"world\"}");
    Files.writeString(dir.resolve("kotlin-1.json"), captureJson("kotlin", "kotlin"));

    List<CaptureEntry> captures = CaptureReader.readAll(dir);

    assertThat(captures).hasSize(1);
    assertThat(captures.get(0).compiler()).isEqualTo("kotlin");
  }

  @Test
  void aCaptureWithoutALanguageStillParses(@TempDir Path dir) throws Exception {
    // A capture written before the language field existed must still be read.
    Files.writeString(
        dir.resolve("javac-1.json"),
        "{\n"
            + "  \"api\": \"cli\",\n"
            + "  \"compiler\": \"javac\",\n"
            + "  \"argv\": [\"-d\", \"out\"],\n"
            + "  \"outputDirectory\": \"out\"\n"
            + "}\n");

    List<CaptureEntry> captures = CaptureReader.readAll(dir);

    assertThat(captures).hasSize(1);
    assertThat(captures.get(0).language()).isNull();
    assertThat(captures.get(0).compiler()).isEqualTo("javac");
  }

  @Test
  void theWrittenCaptureCarriesTheLanguage(@TempDir Path dir) {
    CaptureEntry entry =
        CaptureEntry.simple(
            "cli",
            List.of("-d", "out", "src/Foo.java"),
            "UTF-8",
            "17",
            Path.of("out"),
            List.of(Path.of("a.jar")),
            List.of(Path.of("src/Foo.java")));

    String json = entry.toJson();

    assertThat(json).contains("\"language\": \"java\"");
    assertThat(json).contains("\"compiler\": \"javac\"");
  }

  @Test
  void theWrittenFileIsNamedAfterTheCompiler(@TempDir Path dir) throws Exception {
    // The file name is what lets a reader pick the files of one compiler
    // without parsing them, so it must carry the compiler and not a fixed
    // prefix.
    CaptureEntry entry =
        CaptureEntry.simple(
            "cli",
            List.of("-d", "out", "src/Foo.java"),
            "UTF-8",
            "17",
            Path.of("out"),
            List.of(Path.of("a.jar")),
            List.of(Path.of("src/Foo.java")));

    CaptureSink sink = CaptureSink.get();
    sink.clear();
    sink.setOutputDirectory(dir);
    try {
      sink.record(entry);
    } finally {
      sink.setOutputDirectory(null);
      sink.clear();
    }

    try (var files = Files.list(dir)) {
      assertThat(files.map(p -> p.getFileName().toString()))
          .as("the capture file must be named after the compiler that wrote it")
          .allMatch(name -> name.startsWith("javac-") && name.endsWith(".json"));
    }
  }

  @Test
  void twoCapturesOfDifferentLanguagesAreDifferentCompiles() {
    CaptureEntry javaCapture = captureOf("javac", "java");
    CaptureEntry kotlinCapture = captureOf("kotlin", "kotlin");

    assertThat(javaCapture.sameCompileAs(kotlinCapture))
        .as("a Java compile and a Kotlin compile are two different compiles")
        .isFalse();
  }

  /** A capture of the given compiler and language, with one source file. */
  private static CaptureEntry captureOf(String compiler, String language) {
    return new CaptureEntry(
        "cli",
        compiler,
        language,
        List.of(),
        null,
        "17",
        Path.of("out"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(Path.of("A.java")),
        List.of(),
        List.of(),
        List.of(),
        false,
        true,
        "17",
        "/jdk",
        "/work");
  }

  @Test
  void theLanguageFieldIsReadFromTheJson(@TempDir Path dir) throws Exception {
    Files.writeString(
        dir.resolve("scala3-1.json"),
        captureJson("scala3", "scala").replace("\"scala\"", "\"scala\""));

    CaptureEntry entry = CaptureReader.readAll(dir).get(0);

    assertThat(entry.language()).isEqualTo("scala");
    assertThat(entry.sourceFiles()).containsExactly(Path.of("src/Foo.scala"));
  }

  @Test
  void aCaptureIsRecognisedByItsPrefixAndItsExtension() {
    assertThat(CaptureReader.isCaptureFile("kotlin-123.json")).isTrue();
    assertThat(CaptureReader.isCaptureFile("scala3-1.json")).isTrue();
    assertThat(CaptureReader.isCaptureFile("ecj-9.json")).isTrue();
    assertThat(CaptureReader.isCaptureFile("sjavac-9.json")).isTrue();
    assertThat(CaptureReader.isCaptureFile("javac-9.json")).isTrue();
    assertThat(CaptureReader.isCaptureFile("kotlin-123.json.txt")).isFalse();
    assertThat(CaptureReader.isCaptureFile("notes.json")).isFalse();
    assertThat(CaptureReader.isCaptureFile("kotlin-123")).isFalse();
  }
}
