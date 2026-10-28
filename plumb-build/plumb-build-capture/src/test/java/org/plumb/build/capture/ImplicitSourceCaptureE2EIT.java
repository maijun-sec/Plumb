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
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The capture must name the sources the compiler ACTUALLY compiled.
 *
 * <p><b>Why this test exists.</b> The command line names the sources the compiler was given. The
 * compiler also compiles the sources it finds itself, through the source path: a class that is
 * referenced but not named is looked up on the source path, parsed and compiled. A capture that
 * lists only the command-line sources therefore misses code the compiler really compiled -- and
 * that code is exactly what a static analyser has to look at.
 *
 * <p>So the fixture here is deliberately asymmetric: one source is named on the command line, and
 * one is found through the source path. A third file sits in the source path and is referenced by
 * nobody -- it must NOT appear, because the compiler did not compile it.
 *
 * <p>That third file is what makes the test worth having. A capture that simply listed every file
 * in the source path would pass the first half of this test and fail the second half. The source
 * path is a <em>search path</em>, not a list of compiled files.
 */
class ImplicitSourceCaptureE2EIT {

  /**
   * The API path -- the one Gradle, Maven and the IDEs take.
   *
   * <p>The compile happens in this JVM, with the agent installed through the {@code
   * Instrumentation} API.
   */
  @Test
  void theCaptureNamesTheSourcesTheCompilerFoundOnTheSourcePath(@TempDir Path dir)
      throws Exception {
    Path src = dir.resolve("src");
    Files.createDirectories(src);
    Path main = src.resolve("Main.java");
    Files.writeString(main, "public class Main { Helper helper = new Helper(); }\n");
    Path helper = src.resolve("Helper.java");
    Files.writeString(helper, "class Helper { void go() {} }\n");
    // In the source path, but referenced by nobody: the compiler never
    // compiles it, so the capture must not name it.
    Path unused = src.resolve("Unused.java");
    Files.writeString(unused, "class Unused { }\n");

    Path classes = dir.resolve("classes");
    Files.createDirectories(classes);
    Path captures = dir.resolve("captures");
    Files.createDirectories(captures);

    // The agent is installed into this JVM.
    java.lang.instrument.Instrumentation inst = net.bytebuddy.agent.ByteBuddyAgent.install();
    System.setProperty(CaptureRecorder.PROP_OUTPUT_DIR, captures.toString());
    CaptureAgent.install(inst);
    CaptureSink.get().setOutputDirectory(captures);

    try {
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
        var units = fm.getJavaFileObjectsFromFiles(List.of(main.toFile()));
        var task =
            compiler.getTask(
                null,
                fm,
                null,
                List.of("-d", classes.toString(), "-sourcepath", src.toString()),
                null,
                units);
        task.call();
      }
    } finally {
      CaptureSink.get().setOutputDirectory(null);
      System.clearProperty(CaptureRecorder.PROP_OUTPUT_DIR);
    }

    // The compiler really did compile the helper: that is what makes this
    // test about the capture and not about a compile that failed.
    assertThat(classes.resolve("Helper.class"))
        .as("the compiler should have compiled the source it found on the source path")
        .exists();

    List<CaptureEntry> captured = CaptureReader.readAll(captures);
    assertThat(captured).as("the agent should have recorded the compile").isNotEmpty();

    CaptureEntry entry = captured.get(captured.size() - 1);
    assertThat(entry.sourceFiles())
        .as("the capture must name the source the compiler found on the source path")
        .contains(helper);
    assertThat(entry.sourceFiles()).contains(main);
    assertThat(entry.sourceFiles())
        .as("the compiler did not compile the unreferenced file, so the capture must not name it")
        .doesNotContain(unused);
  }

  /**
   * The command-line path.
   *
   * <p>The CLI driver reaches the compiler through a different entry point, and the source path
   * works there too.
   */
  @Test
  void theCommandLineCaptureNamesTheSourcesFoundOnTheSourcePath(@TempDir Path dir)
      throws Exception {
    Path src = dir.resolve("src");
    Files.createDirectories(src);
    Path main = src.resolve("Main.java");
    Files.writeString(main, "public class Main { Helper helper = new Helper(); }\n");
    Path helper = src.resolve("Helper.java");
    Files.writeString(helper, "class Helper { void go() {} }\n");

    Path classes = dir.resolve("classes");
    Files.createDirectories(classes);
    Path captures = dir.resolve("captures");
    Files.createDirectories(captures);

    Path javac = findJavac();
    org.junit.jupiter.api.Assumptions.assumeTrue(javac != null, "no javac executable on this host");

    ProcessBuilder pb =
        new ProcessBuilder(
            javac.toString(),
            "-J-javaagent:" + agentJar(),
            "-d",
            classes.toString(),
            "-sourcepath",
            src.toString(),
            main.toString());
    pb.environment().put(CaptureRecorder.ENV_OUTPUT_DIR, captures.toString());
    pb.redirectErrorStream(true);
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes());
    int exitCode = process.waitFor();

    assertThat(exitCode).as("javac output: %s", output).isZero();
    assertThat(classes.resolve("Helper.class"))
        .as("the compiler should have compiled the source it found on the source path")
        .exists();

    List<CaptureEntry> captured = CaptureReader.readAll(captures);
    assertThat(captured).as("the agent should have recorded the compile").isNotEmpty();

    CaptureEntry entry = captured.get(captured.size() - 1);
    assertThat(entry.sourceFiles())
        .as("the capture must name the source the compiler found on the source path")
        .contains(helper);
    assertThat(entry.sourceFiles()).contains(main);
  }

  /** The javac executable, or null when this host has none. */
  private static Path findJavac() {
    Path javaHome = Path.of(System.getProperty("java.home"));
    Path javac = javaHome.resolve("bin").resolve(isWindows() ? "javac.exe" : "javac");
    return Files.isExecutable(javac) ? javac : null;
  }

  /** The agent jar the tests attach. */
  private static String agentJar() {
    Path jar = Path.of("target", "plumb-build-capture-0.1.0-SNAPSHOT.jar").toAbsolutePath();
    return jar.toString();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }
}
