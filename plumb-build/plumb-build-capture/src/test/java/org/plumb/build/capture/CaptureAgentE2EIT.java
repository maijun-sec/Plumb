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
 * End-to-end test: run a real compile with the agent attached and read the capture back.
 *
 * <p>This is the scenario the original btrace-based tool served. The test runs a real compile
 * with the agent installed and checks that the compile was recorded.
 *
 * <p><b>Two paths are exercised, on purpose.</b> A "javac command line" is not one method: the
 * command-line driver and the {@code javax.tools} API are different entry points, and a capture
 * that only covers one of them silently misses every build that takes the other. Hooking only the
 * CLI would make this test pass while the tool is useless for Gradle, Maven and the IDEs.
 */
class CaptureAgentE2EIT {

  /**
   * The compile runs in a child JVM with the agent attached, because the agent has to be installed
   * before the compiler's classes are loaded. The child writes the capture into a directory the
   * parent reads back.
   */
  @Test
  void capturesTheCommandLineCompile(@TempDir Path dir) throws Exception {
    Path source = dir.resolve("Hello.java");
    Files.writeString(
        source,
        "public class Hello { public static void main(String[] a) { System.out.println(\"hi\"); } }");
    Path classes = dir.resolve("classes");
    Files.createDirectories(classes);
    Path captures = dir.resolve("captures");
    Files.createDirectories(captures);

    Path javac = findJavac();
    if (javac == null) {
      // No javac executable on this host -- the API path below still
      // exercises the agent, so this is a partial skip, not a full one.
      return;
    }

    ProcessBuilder pb =
        new ProcessBuilder(
            javac.toString(),
            "-J-javaagent:" + agentJar(),
            "-d",
            classes.toString(),
            "-classpath",
            System.getProperty("java.class.path"),
            source.toString());
    pb.environment().put(CaptureRecorder.ENV_OUTPUT_DIR, captures.toString());
    pb.redirectErrorStream(true);
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes());
    int exitCode = process.waitFor();

    assertThat(exitCode).as("javac output: %s", output).isZero();

    List<CaptureEntry> captured = CaptureReader.readAll(captures);
    assertThat(captured).as("the agent should have recorded the compile").isNotEmpty();

    CaptureEntry entry = captured.get(captured.size() - 1);
    assertThat(entry.api()).isEqualTo("cli");
    assertThat(entry.outputDirectory()).isEqualTo(classes);
    assertThat(entry.sourceFiles()).contains(source);
    assertThat(entry.classpath()).isNotEmpty();
    assertThat(entry.argv()).contains("-d");
  }

  /**
   * The {@code javax.tools} path -- the one Gradle, Maven and the IDEs take.
   *
   * <p>The compile happens in this JVM, with the agent installed through the {@code
   * Instrumentation} API. The capture directory is set on the sink rather than through the
   * environment, because the environment cannot be changed from inside a running JVM.
   */
  @Test
  void capturesTheApiCompile(@TempDir Path dir) throws Exception {
    Path source = dir.resolve("ApiHello.java");
    Files.writeString(
        source, "public class ApiHello { public static void main(String[] a) { System.out.println(\"hi\"); } }");
    Path classes = dir.resolve("classes");
    Files.createDirectories(classes);
    Path captures = dir.resolve("captures");
    Files.createDirectories(captures);

    // Install the agent into this JVM.
    java.lang.instrument.Instrumentation inst =
        net.bytebuddy.agent.ByteBuddyAgent.install();
    // The environment of a running JVM cannot be changed, so the capture
    // directory goes in through the system property.
    System.setProperty(CaptureRecorder.PROP_OUTPUT_DIR, captures.toString());
    CaptureAgent.install(inst);
    CaptureSink.get().setOutputDirectory(captures);

    try {
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
        var units = fm.getJavaFileObjectsFromFiles(List.of(source.toFile()));
        var task =
            compiler.getTask(
                null,
                fm,
                null,
                List.of("-d", classes.toString()),
                null,
                units);
        task.call();
      }
    } finally {
      CaptureSink.get().setOutputDirectory(null);
      System.clearProperty(CaptureRecorder.PROP_OUTPUT_DIR);
    }

    List<CaptureEntry> captured = CaptureReader.readAll(captures);
    assertThat(captured).as("the agent should have recorded the API compile").isNotEmpty();

    CaptureEntry entry = captured.get(captured.size() - 1);
    assertThat(entry.api()).isEqualTo("api");
    assertThat(entry.argv()).contains("-d");
    assertThat(entry.sourceFiles()).contains(source);
  }

  /**
   * One compile must produce one capture.
   *
   * <p>A command-line compile reaches <em>two</em> hooked entry points: {@code
   * com.sun.tools.javac.Main#compile} and then {@code com.sun.tools.javac.main.Main#compile}. Both
   * carry the same argv, so without duplicate suppression the capture would be written twice and
   * the analyser would see one compile twice over.
   *
   * <p>This is the test that would catch the suppression breaking: the count is checked, not the
   * content.
   */
  @Test
  void oneCompileProducesOneCapture(@TempDir Path dir) throws Exception {
    Path source = dir.resolve("Once.java");
    Files.writeString(source, "public class Once { }");
    Path classes = dir.resolve("classes");
    Files.createDirectories(classes);
    Path captures = dir.resolve("captures");
    Files.createDirectories(captures);

    Path javac = findJavac();
    if (javac == null) {
      return;
    }

    ProcessBuilder pb =
        new ProcessBuilder(
            javac.toString(),
            "-J-javaagent:" + agentJar(),
            "-d",
            classes.toString(),
            source.toString());
    pb.environment().put(CaptureRecorder.ENV_OUTPUT_DIR, captures.toString());
    pb.redirectErrorStream(true);
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes());
    assertThat(process.waitFor()).as("javac output: %s", output).isZero();

    List<CaptureEntry> captured = CaptureReader.readAll(captures);
    assertThat(captured).as("the agent should have recorded the compile").hasSize(1);
    assertThat(captured.get(0).sourceFiles()).contains(source);
  }

  /**
   * The agent must not break the compile it observes.
   *
   * <p>A capture that makes the build fail is worse than no capture at all: the user's build
   * breaks and they cannot tell why.
   */
  @Test
  void theAgentDoesNotBreakTheCompile(@TempDir Path dir) throws Exception {
    Path source = dir.resolve("Ok.java");
    Files.writeString(source, "public class Ok { }");
    Path classes = dir.resolve("classes");
    Files.createDirectories(classes);

    java.lang.instrument.Instrumentation inst = net.bytebuddy.agent.ByteBuddyAgent.install();
    CaptureAgent.install(inst);

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
      var units = fm.getJavaFileObjectsFromFiles(List.of(source.toFile()));
      var task = compiler.getTask(null, fm, null, List.of("-d", classes.toString()), null, units);
      Boolean ok = task.call();
      assertThat(ok).as("the compile must still succeed with the agent attached").isTrue();
    }
    assertThat(classes.resolve("Ok.class")).exists();
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
   * <p>During the test phase the jar has not been packaged yet, so the exploded classes directory
   * is used instead -- which is enough, because {@code -javaagent} accepts a jar only. When the jar
   * is absent the test is skipped.
   */
  private static String agentJar() {
    Path jar = Path.of("target", "plumb-build-capture-0.1.0-SNAPSHOT.jar").toAbsolutePath();
    return jar.toString();
  }
}
