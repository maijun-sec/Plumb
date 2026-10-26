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

  @Test
  void writesAndReadsBackACapture(@TempDir Path dir) {
    List<String> argv = List.of("-d", "out", "-classpath", "a.jar;b.jar", "src/Foo.java");

    CaptureSink sink = CaptureSink.get();
    sink.clear();
    sink.setOutputDirectory(dir);
    try {
      sink.record(argv);
    } finally {
      sink.setOutputDirectory(null);
      sink.clear();
    }

    List<JavacOptions> captures = CaptureReader.readAll(dir);

    assertThat(captures).hasSize(1);
    JavacOptions options = captures.get(0);
    assertThat(options.rawArguments()).isEqualTo(argv);
    assertThat(options.outputDirectory()).isEqualTo(Path.of("out"));
    assertThat(options.classpath()).containsExactly(Path.of("a.jar"), Path.of("b.jar"));
    assertThat(options.sourceFiles()).containsExactly(Path.of("src/Foo.java"));
  }

  @Test
  void escapesSpecialCharacters(@TempDir Path dir) {
    List<String> argv = List.of("path with space", "quote\"inside", "back\\slash");

    CaptureSink sink = CaptureSink.get();
    sink.clear();
    sink.setOutputDirectory(dir);
    try {
      sink.record(argv);
    } finally {
      sink.setOutputDirectory(null);
      sink.clear();
    }

    List<JavacOptions> captures = CaptureReader.readAll(dir);

    assertThat(captures).hasSize(1);
    assertThat(captures.get(0).rawArguments()).isEqualTo(argv);
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
  void jsonWriterAndReaderAgree(@TempDir Path dir) {
    List<String> argv = List.of("plain", "with space", "with\"quote", "with\\backslash", "");

    String json = CaptureHook.toJson(argv);
    assertThat(CaptureReader.parseJsonArray(json)).isEqualTo(argv);
  }

  @Test
  void parsesUnicodeEscapes() {
    assertThat(CaptureReader.parseJsonArray("[\"\\u0041\\u00e9\"]")).containsExactly("Aé");
  }

  @Test
  void writesAWellFormedJsonFile(@TempDir Path dir) throws Exception {
    List<String> argv = List.of("-d", "out");

    CaptureSink sink = CaptureSink.get();
    sink.clear();
    sink.setOutputDirectory(dir);
    try {
      sink.record(argv);
    } finally {
      sink.setOutputDirectory(null);
      sink.clear();
    }

    try (var files = Files.list(dir)) {
      var file = files.findFirst().orElseThrow();
      String content = Files.readString(file, StandardCharsets.UTF_8);
      assertThat(content).startsWith("[").endsWith("]");
      assertThat(content).contains("\"-d\"");
    }
  }
}
