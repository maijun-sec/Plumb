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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test: run a real Gradle build with the agent attached and read the captures back.
 *
 * <p><b>What this test is for.</b> Gradle is the case where the classpath cannot be read off a
 * build script. The downstream module's classpath contains the upstream module's <em>output
 * directory</em> -- a directory that only exists after the upstream module has been compiled, and
 * whose contents are the upstream module's classes. No static reading of {@code build.gradle} can
 * produce that; only asking Gradle can.
 *
 * <p>So the assertion is not "something was captured". It is: the downstream module's capture names
 * the upstream module's output directory and the external dependency's jar. That is the ground
 * truth a static analyser needs, and it is the thing a build-script reader cannot get.
 *
 * <p><b>Why the agent is enough.</b> Gradle assembles the compile in the daemon and performs it
 * through {@code javax.tools}. The daemon hook forces the build into the foreground process that
 * holds the agent, so the existing {@code JavacTool#getTask} hook records the effective compile.
 * There is deliberately no Gradle-specific classpath hook -- see {@link GradleHooks}.
 *
 * <p><b>When Gradle is not available</b> the test returns without asserting. That is a partial
 * skip, and it is reported as such: the test still runs the {@code javax.tools} path in {@code
 * CaptureAgentE2EIT}, so the capture machinery itself is always covered.
 */
class GradleCaptureE2EIT {

  /**
   * The build captures the downstream module's classpath, and that classpath names the upstream
   * module's output directory.
   */
  @Test
  void capturesTheFinalResolvedGradleClasspath(@TempDir Path dir) throws Exception {
    Path gradle = findGradle();
    // A missing Gradle is a skip, not a pass.  Returning quietly would leave
    // a test that asserts nothing -- and a test that asserts nothing is
    // indistinguishable from a test that passes.
    Assumptions.assumeTrue(gradle != null, "no Gradle on this host that can build on this JDK");

    Path project = dir.resolve("project");
    Path upstream = project.resolve("upstream");
    Path downstream = project.resolve("downstream");
    writeProject(project, upstream, downstream);

    Path captures = dir.resolve("captures");
    Files.createDirectories(captures);

    Path agent = agentJar();
    Assumptions.assumeTrue(Files.exists(agent), "the agent jar is not packaged yet: " + agent);

    // The agent must reach the JVM that performs the compile.  Gradle runs
    // the build in a daemon, so the daemon is forced off and the build runs in
    // the foreground process -- which is the one holding the agent.  The agent
    // reaches that process through Gradle's own jvmargs mechanism, because
    // that is the only argument list Gradle forwards to the JVM it builds in.
    //
    // The path is written with forward slashes: the JVM's -javaagent option
    // parser is sensitive to a backslash on Windows.
    writeGradleProperties(project, agent);

    List<String> command = new ArrayList<>();
    command.add(gradle.toString());
    command.add("--no-daemon");
    command.add("--console=plain");
    command.add("--quiet");
    command.add("build");

    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(project.toFile());
    pb.environment().put(CaptureRecorder.ENV_OUTPUT_DIR, captures.toString());
    pb.redirectErrorStream(true);
    Process process = pb.start();
    String output = new String(process.getInputStream().readAllBytes());
    int exitCode = process.waitFor();

    assertThat(exitCode).as("gradle output: %s", output).isZero();

    List<CaptureEntry> captured = CaptureReader.readAll(captures);
    assertThat(captured).as("the agent should have recorded the Gradle compiles").isNotEmpty();

    // The downstream module's compile is the one whose classpath matters.
    CaptureEntry downstreamCompile = null;
    for (CaptureEntry entry : captured) {
      if (entry.outputDirectory() != null
          && entry.outputDirectory().startsWith(downstream.toAbsolutePath())) {
        downstreamCompile = entry;
      }
    }
    assertThat(downstreamCompile)
        .as("the downstream compile should have been captured; got %s", captured)
        .isNotNull();

    // The upstream module's output directory: the thing no build-script
    // reader can produce.
    Path upstreamClasses = upstream.resolve("build/classes/java/main").toAbsolutePath();
    assertThat(downstreamCompile.classpath())
        .as("the downstream classpath should name the upstream output directory")
        .contains(upstreamClasses);

    // And the external dependency's jar.
    assertThat(downstreamCompile.classpath())
        .as("the downstream classpath should name the dependency jar")
        .anySatisfy(
            p -> {
              assertThat(p.getFileName().toString()).endsWith(".jar");
              assertThat(p.toString()).contains("slf4j-api");
            });
  }

  /** Write a two-module Gradle project: {@code downstream} depends on {@code upstream}. */
  private static void writeProject(Path project, Path upstream, Path downstream) throws Exception {
    Files.createDirectories(project);
    Files.createDirectories(upstream.resolve("src/main/java/com/example/upstream"));
    Files.createDirectories(downstream.resolve("src/main/java/com/example/downstream"));

    Files.writeString(
        project.resolve("settings.gradle"),
        "rootProject.name = 'capture-demo'\ninclude 'upstream'\ninclude 'downstream'\n");

    Files.writeString(
        project.resolve("build.gradle"),
        """
        // The downstream module's classpath is the thing under test: it holds
        // the upstream module's output directory, which only exists after the
        // upstream module has been compiled.
        allprojects {
            repositories {
                mavenCentral()
            }
        }
        """);

    Files.writeString(upstream.resolve("build.gradle"), "plugins { id 'java-library' }\n");

    Files.writeString(
        downstream.resolve("build.gradle"),
        """
        plugins {
            id 'java-library'
        }

        dependencies {
            implementation project(':upstream')
            implementation 'org.slf4j:slf4j-api:2.0.13'
        }
        """);

    Files.writeString(
        upstream.resolve("src/main/java/com/example/upstream/Upstream.java"),
        "package com.example.upstream;\n"
            + "public class Upstream { public int value() { return 1; } }\n");
    Files.writeString(
        downstream.resolve("src/main/java/com/example/downstream/Downstream.java"),
        "package com.example.downstream;\n"
            + "import com.example.upstream.Upstream;\n"
            + "public class Downstream { public int value() { return new Upstream().value(); }"
            + " }\n");
  }

  /**
   * Write the {@code gradle.properties} that attaches the agent to Gradle's JVM.
   *
   * <p>Three settings, and each one earns its place:
   *
   * <ul>
   *   <li>{@code org.gradle.jvmargs} carries the {@code -javaagent} option. This is the only
   *       argument list Gradle forwards to the JVM it builds in, so it is how the agent reaches the
   *       process that performs the compile.
   *   <li>{@code plumb.capture.gradle.disable-daemon} turns on the Gradle hooks, which force the
   *       daemon and the build cache off so the compile actually happens.
   *   <li>{@code org.gradle.daemon=false} stops Gradle from forking a daemon at all.
   * </ul>
   *
   * <p>The path uses forward slashes: the JVM's {@code -javaagent} parser treats a backslash as an
   * escape character on Windows.
   */
  private static void writeGradleProperties(Path project, Path agent) throws Exception {
    String path = agent.toAbsolutePath().toString().replace('\\', '/');
    String extra = System.getProperty("plumb.capture.mutant.extra");
    // The agent's own arguments are the configuration channel that reaches
    // Gradle's JVM: Gradle forwards the -javaagent and nothing else, and the
    // agent's arguments are handed to premain.
    String arguments = GradleHooks.PROP_DISABLE_DAEMON + "=true";
    if (extra != null) {
      arguments = arguments + "," + extra;
    }
    String properties =
        "org.gradle.jvmargs=-Xmx1g -javaagent:"
            + path
            + "="
            + arguments
            + "\n"
            + "org.gradle.daemon=false\n";
    Files.writeString(project.resolve("gradle.properties"), properties);
  }

  /**
   * Find a Gradle launcher on this host.
   *
   * <p>Four places are tried, in order:
   *
   * <ol>
   *   <li>an explicit {@code PLUMB_GRADLE} override;
   *   <li>the Gradle wrapper distributions in the user's home -- every version is considered, and
   *       the newest one that can actually run on this JDK wins;
   *   <li>a {@code gradle} on the {@code PATH};
   *   <li>the Gradle distributions this repository's own probes downloaded.
   * </ol>
   *
   * <p><b>Why the version matters.</b> A Gradle that cannot run on the JDK under test is worse than
   * no Gradle: the build fails for a reason that has nothing to do with the capture. Gradle 8.x
   * cannot read a Java 25 class file ("Unsupported class file major version 69"), so the candidate
   * is checked by running it before it is trusted.
   *
   * @return the launcher, or {@code null} when there is none that works here
   */
  private static Path findGradle() {
    String override = System.getenv("PLUMB_GRADLE");
    if (override != null && !override.isBlank()) {
      Path p = Path.of(override);
      if (Files.exists(p) && canRun(p)) {
        return p;
      }
    }
    // The wrapper distributions, newest first.  The depth is 5 because the
    // layout is dists/gradle-<version>-bin/<hash>/gradle-<version>/bin/gradle.
    Path dists = Path.of(System.getProperty("user.home"), ".gradle", "wrapper", "dists");
    if (Files.isDirectory(dists)) {
      List<Path> candidates = new ArrayList<>();
      try (var stream = Files.walk(dists, 5)) {
        stream
            .filter(p -> p.getFileName().toString().equals(isWindows() ? "gradle.bat" : "gradle"))
            .filter(Files::isRegularFile)
            .forEach(candidates::add);
      } catch (Exception e) {
        // Fall through to the other places.
      }
      // Newest first.  The version is compared numerically, because a
      // string comparison puts "8.9" above "9.2.0" -- and the older Gradle is
      // the one that cannot run on a modern JDK.
      candidates.sort((a, b) -> Integer.compare(versionOf(b), versionOf(a)));
      for (Path candidate : candidates) {
        if (canRun(candidate)) {
          return candidate;
        }
      }
    }
    // A gradle on the path.
    String path = System.getenv("PATH");
    if (path != null) {
      for (String dir : path.split(java.io.File.pathSeparator)) {
        if (dir.isBlank()) {
          continue;
        }
        Path candidate = Path.of(dir, isWindows() ? "gradle.bat" : "gradle");
        if (Files.isRegularFile(candidate) && canRun(candidate)) {
          return candidate;
        }
      }
    }
    // The distributions this repository's probes downloaded.  The depth is 4
    // because the layout is <temp>/<group>/<version>/bin/gradle.
    Path temp = Path.of(System.getProperty("java.io.tmpdir"));
    if (Files.isDirectory(temp)) {
      try (var stream = Files.walk(temp, 5)) {
        return stream
            .filter(p -> p.getFileName().toString().equals(isWindows() ? "gradle.bat" : "gradle"))
            .filter(Files::isRegularFile)
            .filter(GradleCaptureE2EIT::canRun)
            .findFirst()
            .orElse(null);
      } catch (Exception e) {
        return null;
      }
    }
    return null;
  }

  /**
   * The Gradle version of a distribution, as a comparable number.
   *
   * <p>The path holds the version twice -- in the directory name and in the distribution's own name
   * -- so the first {@code gradle-<version>} wins.
   *
   * @return the version as {@code major * 1000 + minor}, or {@code -1} when it cannot be read
   */
  private static int versionOf(Path gradle) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("gradle-(\\d+)\\.(\\d+)").matcher(gradle.toString());
    if (!m.find()) {
      return -1;
    }
    try {
      return Integer.parseInt(m.group(1)) * 1000 + Integer.parseInt(m.group(2));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * Whether this Gradle can run on this JVM.
   *
   * <p><b>{@code --version} is not enough.</b> It succeeds for a Gradle that cannot actually build:
   * Gradle 8.9 prints its version happily on a Java 25 JVM, and then fails every build with
   * "Unsupported class file major version 69". So the probe is a real build of a trivial project,
   * because that is the thing the test needs to work.
   */
  private static boolean canRun(Path gradle) {
    try {
      Path probe = Files.createTempDirectory("plumb-gradle-probe");
      try {
        Files.writeString(probe.resolve("settings.gradle"), "rootProject.name = 'probe'\n");
        Files.writeString(probe.resolve("build.gradle"), "");
        ProcessBuilder pb = new ProcessBuilder(gradle.toString(), "--no-daemon", "build");
        pb.directory(probe.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        return process.waitFor() == 0;
      } finally {
        deleteTree(probe);
      }
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Delete a directory tree.
   *
   * <p>The probe directory is a throwaway, but the build wrote a {@code .gradle} cache into it, so
   * it has to go as a tree rather than as an empty directory.
   */
  private static void deleteTree(Path root) {
    try (var stream = Files.walk(root)) {
      stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (Exception ignored) {
                  // Best effort: a leftover temp directory is not worth failing the test over.
                }
              });
    } catch (Exception ignored) {
      // Best effort.
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  /**
   * The agent jar to attach.
   *
   * <p>During the integration-test phase the jar has been packaged, so it exists.
   */
  private static Path agentJar() {
    return Path.of("target", "plumb-build-capture-0.1.0-SNAPSHOT.jar").toAbsolutePath();
  }
}
