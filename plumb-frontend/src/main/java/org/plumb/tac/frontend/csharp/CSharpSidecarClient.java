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
package org.plumb.tac.frontend.csharp;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The client side of the C# sidecar: a long-lived .NET process, spoken to over stdio.
 *
 * <h2>Why a sidecar</h2>
 *
 * <p>Plumb is a JVM product and Roslyn is a .NET library, so the C# frontend cannot call Roslyn
 * in-process. The alternatives were rejected: reading C# IL loses the source-level semantics
 * (nullable annotations are erased, {@code await} has already been rewritten into a state machine),
 * and re-implementing C# semantics in the JVM would mean writing a second compiler that disagrees
 * with Roslyn on every corner case. A sidecar keeps Roslyn's answers, which are <i>the</i> C#
 * compiler's answers.
 *
 * <h2>Why the sidecar is PROJECT-SCOPED and LONG-LIVED</h2>
 *
 * <p>A per-file {@code CSharpCompilation} cannot resolve a reference to a type declared in another
 * file of the same project. The symbol would simply fail to bind, and the scan would report "no
 * findings" for a project that contains a vulnerability. So the sidecar holds one compilation per
 * project and reuses it across files. That is also what makes the approach viable: building the
 * compilation is the expensive part, and doing it once per project rather than once per file is
 * the difference between usable and unusable.
 *
 * <h2>Totality</h2>
 *
 * <p>A frontend must not throw on malformed input. Everything here reports a failure as an
 * exception the caller turns into a structured problem -- never as a silent empty result, which
 * would read as "no findings".
 *
 * @see CSharpTacFrontend
 */
public final class CSharpSidecarClient implements AutoCloseable {

  /**
   * The largest frame accepted from the sidecar. A corrupt length must not become an
   * out-of-memory kill.
   */
  private static final int MAX_FRAME_BYTES = 256 * 1024 * 1024;

  private final Process process;
  private final DataOutputStream toSidecar;
  private final DataInputStream fromSidecar;
  private final Thread stderrPump;
  private final StringBuilder stderrTail = new StringBuilder();

  private CSharpSidecarClient(Process process) {
    this.process = process;
    this.toSidecar = new DataOutputStream(process.getOutputStream());
    this.fromSidecar = new DataInputStream(process.getInputStream());
    // The sidecar's own chatter goes to stderr. It is drained on a separate thread:
    // a full stderr pipe blocks the sidecar, and a blocked sidecar stops answering.
    this.stderrPump =
        new Thread(
            () -> {
              try (InputStream stream = process.getErrorStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = stream.read(buffer)) >= 0) {
                  synchronized (stderrTail) {
                    stderrTail.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
                    if (stderrTail.length() > 8192) {
                      stderrTail.delete(0, stderrTail.length() - 8192);
                    }
                  }
                }
              } catch (IOException ignored) {
                // The pipe closed with the process. Nothing to report.
              }
            },
            "plumb-csharp-sidecar-stderr");
    this.stderrPump.setDaemon(true);
    this.stderrPump.start();
  }

  /**
   * Launch the sidecar.
   *
   * @param executable the sidecar executable
   * @param workingDirectory the directory to run it in, or null for the current one
   * @return the client
   * @throws IOException when the sidecar could not be started
   */
  public static CSharpSidecarClient launch(Path executable, Path workingDirectory)
      throws IOException {
    if (executable == null || !Files.isRegularFile(executable)) {
      throw new IOException("the C# sidecar was not found at " + executable);
    }
    ProcessBuilder builder = new ProcessBuilder(executable.toString());
    if (workingDirectory != null) {
      builder.directory(workingDirectory.toFile());
    }
    // The protocol is on stdout; the sidecar's chatter is on stderr. Nothing else is
    // inherited, so a stray line can never corrupt the frame stream.
    builder.redirectErrorStream(false);
    Process process = builder.start();
    return new CSharpSidecarClient(process);
  }

  /**
   * Whether the sidecar is still running.
   *
   * @return true when the process is alive
   */
  public boolean isAlive() {
    return process.isAlive();
  }

  /**
   * Send a request and read the response.
   *
   * @param request the request, already serialised to JSON
   * @return the response, as JSON
   * @throws IOException when the sidecar died or the frame was malformed
   */
  public String exchange(String request) throws IOException {
    byte[] payload = request.getBytes(StandardCharsets.UTF_8);
    toSidecar.writeInt(payload.length);
    toSidecar.write(payload);
    toSidecar.flush();

    int length = fromSidecar.readInt();
    if (length < 0 || length > MAX_FRAME_BYTES) {
      throw new IOException("the C# sidecar sent a frame of " + length + " bytes, which is out of range");
    }
    byte[] response = new byte[length];
    fromSidecar.readFully(response);
    return new String(response, StandardCharsets.UTF_8);
  }

  /**
   * The sidecar's most recent stderr output, for a diagnostic message.
   *
   * @return the tail of the sidecar's stderr, or an empty string
   */
  public String stderrTail() {
    synchronized (stderrTail) {
      return stderrTail.toString();
    }
  }

  /**
   * Ask the sidecar to exit, and wait a moment for it.
   *
   * @param timeoutMillis how long to wait for the exit
   */
  public void shutdown(long timeoutMillis) {
    try {
      toSidecar.writeInt(0);
      toSidecar.flush();
    } catch (IOException ignored) {
      // The pipe is already gone.
    }
    try {
      process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    shutdown(2000);
    if (process.isAlive()) {
      process.destroy();
      try {
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
          process.destroyForcibly();
        }
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
      }
    }
  }

  /**
   * Find the sidecar executable.
   *
   * <p>The lookup order is: an explicit override, the {@code PLUMB_CSHARP_SIDECAR} environment
   * variable, the build output next to this class, and the source tree's build directory.
   *
   * @param override an explicit path, or null
   * @return the executable, or null when none was found
   */
  public static Path locateExecutable(Path override) {
    List<Path> candidates = new ArrayList<>();
    if (override != null) {
      candidates.add(override);
    }
    String fromEnvironment = System.getenv("PLUMB_CSHARP_SIDECAR");
    if (fromEnvironment != null && !fromEnvironment.isBlank()) {
      candidates.add(Path.of(fromEnvironment));
    }

    // Next to the JAR: the distribution ships the sidecar alongside it.
    Path here = pathOfThisClass();
    if (here != null) {
      Path directory = here.getParent();
      if (directory != null) {
        candidates.add(directory.resolve("csharp-sidecar").resolve(executableName()));
        candidates.add(directory.resolve(executableName()));
      }
    }

    // The source tree's build output.
    //
    // The roots are every ancestor of the class's location that has a pom.xml,
    // PLUS the current working directory and its ancestors. The working
    // directory matters because the class is often loaded from the JAR in the
    // local Maven repository -- whose ancestors are not the source tree at all
    // -- while the process was started from the source tree.
    List<Path> roots = repositoryRootsOf(here);
    roots.addAll(repositoryRootsOf(workingDirectory()));
    for (Path base : roots) {
      candidates.add(sidecarIn(base.resolve("csharp-sidecar")));
      candidates.add(sidecarIn(base.resolve("plumb-frontend").resolve("csharp-sidecar")));
    }

    for (Path candidate : candidates) {
      if (candidate != null && Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  /** The process's working directory, or null when it cannot be determined. */
  private static Path workingDirectory() {
    try {
      return Path.of("").toAbsolutePath().normalize();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /** The sidecar's build output, in the release and debug configurations. */
  private static Path sidecarIn(Path projectDirectory) {
    Path release =
        projectDirectory.resolve("bin").resolve("Release").resolve("net8.0").resolve(executableName());
    if (Files.isRegularFile(release)) {
      return release;
    }
    return projectDirectory.resolve("bin").resolve("Debug").resolve("net8.0").resolve(executableName());
  }

  /**
   * The sidecar executable's file name for this platform.
   *
   * @return the file name
   */
  public static String executableName() {
    boolean windows =
        System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    return windows ? "Plumb.CSharpSidecar.exe" : "Plumb.CSharpSidecar";
  }

  private static Path pathOfThisClass() {
    try {
      return Path.of(
          CSharpSidecarClient.class
              .getProtectionDomain()
              .getCodeSource()
              .getLocation()
              .toURI());
    } catch (Exception ex) {
      return null;
    }
  }

  /**
   * The candidate build roots, nearest first.
   *
   * <p><b>Why EVERY ancestor is collected, not just the first.</b> The class's location depends
   * on who is asking: the frontend's own tests run from {@code
   * plumb-frontend/target/test-classes}, so the first ancestor with a pom.xml is {@code
   * plumb-frontend}; but the DFA's tests run from {@code plumb-dfa/target/test-classes}, where it
   * is {@code plumb-dfa}. The sidecar lives in {@code plumb-frontend/csharp-sidecar}, so a lookup
   * that stopped at the first pom.xml would find the module directory and never reach the
   * repository root.
   *
   * <p>So every ancestor is a candidate, and the caller tries the sidecar's location under each
   * of them. The first hit wins, and the nearest match is the right one.
   *
   * @param here the location of this class
   * @return the candidate roots
   */
  private static List<Path> repositoryRootsOf(Path here) {
    List<Path> roots = new ArrayList<>();
    if (here == null) {
      return roots;
    }
    Path current = here.toAbsolutePath().normalize();
    // A FILE (a jar) is not a directory to walk from; start at its parent.
    if (!Files.isDirectory(current)) {
      current = current.getParent();
    }
    for (int i = 0; i < 8 && current != null; i++) {
      if (Files.isRegularFile(current.resolve("pom.xml"))) {
        roots.add(current);
      }
      current = current.getParent();
    }
    return roots;
  }
}
