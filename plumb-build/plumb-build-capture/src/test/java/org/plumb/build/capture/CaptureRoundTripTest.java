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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for the capture write/read round trip. */
class CaptureRoundTripTest {

  private static CaptureEntry sample(List<String> argv) {
    return CaptureEntry.simple(
        "cli",
        argv,
        "UTF-8",
        "17",
        Path.of("out"),
        List.of(Path.of("a.jar"), Path.of("b.jar")),
        List.of(Path.of("src/Foo.java")));
  }

  @Test
  void writesAndReadsBackACapture(@TempDir Path dir) {
    CaptureEntry entry = sample(List.of("-d", "out", "-classpath", "a.jar;b.jar", "src/Foo.java"));

    CaptureSink sink = CaptureSink.get();
    sink.clear();
    sink.setOutputDirectory(dir);
    try {
      sink.record(entry);
    } finally {
      sink.setOutputDirectory(null);
      sink.clear();
    }

    List<CaptureEntry> captures = CaptureReader.readAll(dir);

    assertThat(captures).hasSize(1);
    CaptureEntry read = captures.get(0);
    assertThat(read.argv()).isEqualTo(entry.argv());
    assertThat(read.outputDirectory()).isEqualTo(Path.of("out"));
    assertThat(read.classpath()).containsExactly(Path.of("a.jar"), Path.of("b.jar"));
    assertThat(read.sourceFiles()).containsExactly(Path.of("src/Foo.java"));
    assertThat(read.encoding()).isEqualTo("UTF-8");
    assertThat(read.languageLevel()).isEqualTo("17");
    assertThat(read.api()).isEqualTo("cli");
  }

  @Test
  void escapesSpecialCharacters(@TempDir Path dir) {
    CaptureEntry entry =
        sample(List.of("path with space", "quote\"inside", "back\\slash", "tab\there"));

    CaptureSink sink = CaptureSink.get();
    sink.clear();
    sink.setOutputDirectory(dir);
    try {
      sink.record(entry);
    } finally {
      sink.setOutputDirectory(null);
      sink.clear();
    }

    List<CaptureEntry> captures = CaptureReader.readAll(dir);

    assertThat(captures).hasSize(1);
    assertThat(captures.get(0).argv()).isEqualTo(entry.argv());
  }

  @Test
  void readingAnAbsentDirectoryYieldsNothing(@TempDir Path dir) {
    assertThat(CaptureReader.readAll(dir.resolve("absent"))).isEmpty();
  }

  @Test
  void readingAnEmptyDirectoryYieldsNothing(@TempDir Path dir) {
    assertThat(CaptureReader.readAll(dir)).isEmpty();
  }

  @Test
  void writerAndReaderAgreeOnAWrittenFile(@TempDir Path dir) {
    CaptureEntry entry =
        sample(List.of("plain", "with space", "with\"quote", "with\\backslash", ""));

    String json = entry.toJson();
    CaptureEntry parsed = CaptureReader.parse(json);
    assertThat(parsed).isNotNull();
    assertThat(parsed.argv()).isEqualTo(entry.argv());
  }

  @Test
  void parsesUnicodeEscapes() {
    CaptureEntry parsed = CaptureReader.parse("{\"argv\": [\"\\u0041\\u00e9\"]}");
    assertThat(parsed).isNotNull();
    assertThat(parsed.argv()).containsExactly("Aé");
  }

  @Test
  void writesAWellFormedJsonFile(@TempDir Path dir) throws Exception {
    CaptureEntry entry = sample(List.of("-d", "out"));

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
      var file = files.findFirst().orElseThrow();
      String content = Files.readString(file, StandardCharsets.UTF_8);
      assertThat(content.trim()).startsWith("{").endsWith("}");
      assertThat(content).contains("\"-d\"");
      assertThat(content).contains("\"classpath\"");
      assertThat(content).contains("\"api\"");
    }
  }

  @Test
  void aMissingKeyReadsAsNull() {
    CaptureEntry parsed = CaptureReader.parse("{\"argv\": []}");
    assertThat(parsed).isNotNull();
    assertThat(parsed.encoding()).isNull();
    assertThat(parsed.outputDirectory()).isNull();
    assertThat(parsed.classpath()).isEmpty();
  }
}
