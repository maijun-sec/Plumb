package org.plumb.build.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test: run a real {@code javac} compile with the agent attached
 * and read the capture back.
 *
 * <p>This is the scenario the original btrace-based tool served. The test
 * spawns the JDK's own {@code javac}, passes {@code -javaagent} and a capture
 * directory through the environment, and then checks that the command line
 * was recorded.
 *
 * <p>The test is skipped when the JDK's {@code javac} cannot be found.
 */
class CaptureAgentE2EIT {

  @Test
  void capturesTheJavacCommandLine(@TempDir Path dir) throws Exception {
    Path javac = findJavac();
    if (javac == null) {
      return;
    }

    Path source = dir.resolve("Hello.java");
    Files.writeString(
        source, "public class Hello { public static void main(String[] a) { System.out.println(\"hi\"); } }");
    Path classes = dir.resolve("classes");
    Files.createDirectories(classes);
    Path captures = dir.resolve("captures");
    Files.createDirectories(captures);

    ProcessBuilder pb =
        new ProcessBuilder(
            javac.toString(),
            "-J-javaagent:" + agentJar(),
            "-d",
            classes.toString(),
            "-classpath",
            System.getProperty("java.class.path"),
            source.toString());
    pb.environment().put(CaptureHook.ENV_OUTPUT_DIR, captures.toString());
    pb.redirectErrorStream(true);
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes());
    int exitCode = process.waitFor();

    assertThat(exitCode).as("javac output: %s", output).isZero();

    List<JavacOptions> captured = CaptureReader.readAll(captures);
    assertThat(captured).as("the agent should have recorded the javac command line").isNotEmpty();

    JavacOptions options = captured.get(captured.size() - 1);
    assertThat(options.outputDirectory()).isEqualTo(classes);
    assertThat(options.sourceFiles()).contains(source);
    assertThat(options.classpath()).isNotEmpty();
    assertThat(options.rawArguments()).contains("-d");
  }

  /** Locate the JDK's {@code javac} executable, or {@code null}. */
  private static Path findJavac() {
    String javaHome = System.getProperty("java.home");
    if (javaHome == null) {
      return null;
    }
    Path exe = Path.of(javaHome, "bin", isWindows() ? "javac.exe" : "javac");
    return Files.isExecutable(exe) ? exe : null;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }

  /**
   * The agent jar to attach.
   *
   * <p>During the test phase the jar has not been packaged yet, so the
   * exploded classes directory is used instead -- which is enough, because
   * {@code -javaagent} accepts a jar only. When the jar is absent the test
   * is skipped.
   */
  private static String agentJar() {
    Path jar =
        Path.of("target", "plumb-build-capture-0.1.0-SNAPSHOT.jar").toAbsolutePath();
    return jar.toString();
  }
}
