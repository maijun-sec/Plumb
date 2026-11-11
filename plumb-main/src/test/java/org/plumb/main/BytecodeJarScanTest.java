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
package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Scanning a JAR reports what is inside it.
 *
 * <p><b>Why this is a test at all.</b> A JAR is a first-class input: the scan routes {@code .jar},
 * {@code .class} and {@code .war} to the bytecode analyser and runs the same checkers over it. The
 * bytecode front end marks everything it lowers as a dependency rather than as the code under
 * review -- a class file carries no evidence of which it is -- and the engine analyses a dependency
 * body only when the library analysis is switched on, which it is not by default. So the JAR was
 * analysed as nothing and reported as clean, and a clean report is exactly what a scanner must
 * never produce when it looked at nothing.
 *
 * <p><b>What the scan now has to show.</b> The program inside the JAR opens a file whose name
 * arrived over the wire. That name is a path the program did not choose, so the path-traversal rule
 * has to fire, and the finding has to name the constructor that consumes the name.
 */
@DisplayName("Scan: a JAR is analysed")
class BytecodeJarScanTest {

  /**
   * A program that opens a file whose name arrived over the wire.
   *
   * <p>{@code readLine} is one of the input sources the engine knows by name, and nothing outside
   * the JDK is needed, so the program compiles with the compiler the test already has.
   */
  private static final String SOURCE =
      """
      import java.io.BufferedReader;
      import java.io.File;
      import java.io.IOException;

      public class Jarred {
        public File open(BufferedReader reader) throws IOException {
          String name = reader.readLine();
          return new File(name);
        }
      }
      """;

  /** Compile the program and package the class into a JAR. */
  private static Path jarOf(Path directory) throws IOException {
    Path classes = directory.resolve("classes");
    Files.createDirectories(classes);
    Path source = directory.resolve("Jarred.java");
    Files.writeString(source, SOURCE);

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    int rc =
        compiler.run(
            System.in,
            System.out,
            System.err,
            "-d",
            classes.toString(),
            "-encoding",
            "UTF-8",
            source.toString());
    assertThat(rc).as("the program must compile").isEqualTo(0);

    Path jar = directory.resolve("jarred.jar");
    try (java.util.jar.JarOutputStream out =
        new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
      Path classFile = classes.resolve("Jarred.class");
      out.putNextEntry(new java.util.zip.ZipEntry("Jarred.class"));
      out.write(Files.readAllBytes(classFile));
      out.closeEntry();
    }
    return jar;
  }

  /** Scan the JAR and return the emitted report. */
  private static String scan(Path jar, Path report) throws IOException {
    ScanConfig cfg = new ScanConfig();
    cfg.inputs.add(jar);
    cfg.outputFormat = ScanConfig.OutputFormat.NDJSON;
    cfg.outputPath = report;
    assertThat(new ScanRunner(cfg).run()).as("the scan must succeed").isEqualTo(0);
    return Files.readString(report);
  }

  @Test
  @DisplayName("the tainted name inside the JAR reaches the file it opens")
  void theTaintedNameInsideTheJarReachesTheFileItOpens(@TempDir Path directory) throws IOException {
    Path jar = jarOf(directory);
    Path report = directory.resolve("report.ndjson");

    String output = scan(jar, report);

    assertThat(output)
        .as("the path the program did not choose must be reported")
        .contains("dfa-taint-path-traversal");
    assertThat(output)
        .as("the finding must name the constructor that consumes the tainted name")
        .contains("java.io.File.<init>");
    assertThat(output)
        .as("a constructor is not recursion, and the implicit super() call is not one either")
        .doesNotContain("dfa-taint-uncontrolled-recursion");
  }

  @Test
  @DisplayName("the scan reports the JAR it looked at")
  void theScanReportsTheJarItLookedAt(@TempDir Path directory) throws IOException {
    Path jar = jarOf(directory);
    Path report = directory.resolve("report.ndjson");

    scan(jar, report);

    List<String> lines = Files.readAllLines(report);
    assertThat(lines).as("the report must not be empty").isNotEmpty();
  }
}
