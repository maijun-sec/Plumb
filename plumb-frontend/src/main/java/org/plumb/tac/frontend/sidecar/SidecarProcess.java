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
package org.plumb.tac.frontend.sidecar;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A native sidecar process, spoken to over a length-prefixed frame.
 *
 * <h2>Why a sidecar</h2>
 *
 * <p>Plumb is a JVM product, and some languages cannot be analysed from the JVM: their own compiler
 * is a native library with no JVM port, and re-implementing its semantics in the JVM would mean
 * writing a second compiler that disagrees with the real one on every corner case. So the analysis
 * runs in a sidecar -- a separate process that owns the language's own front end -- and the two
 * halves talk over stdio.
 *
 * <h2>The framing</h2>
 *
 * <p>Each message is a JSON object, UTF-8 encoded, framed as {@code <4-byte big-endian length>
 * <payload>}. The length counts the payload bytes only.
 *
 * <p><b>Why not JSON Lines.</b> A source file may contain any byte in a string literal -- including
 * a newline inside a verbatim string. A line-oriented protocol then has to either escape the
 * newlines or guess where a line ends, and a length prefix has no such ambiguity: the frame
 * boundary is explicit and cannot be spoofed by the content.
 *
 * <p><b>Why the protocol is on stdout and the chatter is on stderr.</b> The two are separate pipes,
 * so a stray diagnostic line can never corrupt the frame stream.
 *
 * <h2>Totality</h2>
 *
 * <p>A frontend must not throw on malformed input. Everything here reports a failure as an
 * exception the caller turns into a structured problem -- never as a silent empty result, which
 * would read as "no findings".
 */
public final class SidecarProcess implements AutoCloseable {

  /**
   * The largest frame accepted from the sidecar. A corrupt length must not become an out-of-memory
   * kill.
   */
  private static final int MAX_FRAME_BYTES = 256 * 1024 * 1024;

  private final String label;
  private final Process process;
  private final DataOutputStream toSidecar;
  private final DataInputStream fromSidecar;
  private final Thread stderrPump;
  private final StringBuilder stderrTail = new StringBuilder();

  /**
   * Whether the stderr pump has reached the end of the sidecar's stderr.
   *
   * <p>The pump runs on its own thread, so the pipe can break before the pump has read what the
   * sidecar said. The message that reports the failure waits for this flag, so that the sidecar's
   * last words are in it.
   */
  private volatile boolean stderrClosed;

  private SidecarProcess(String label, Process process) {
    this.label = label;
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
              } finally {
                stderrClosed = true;
              }
            },
            "plumb-" + label + "-stderr");
    this.stderrPump.setDaemon(true);
    this.stderrPump.start();
  }

  /**
   * Launch a sidecar.
   *
   * @param label the sidecar's name, used in the messages and the thread name
   * @param executable the sidecar executable
   * @param workingDirectory the directory to run it in, or null for the current one
   * @return the process
   * @throws IOException when the sidecar could not be started
   */
  public static SidecarProcess launch(String label, Path executable, Path workingDirectory)
      throws IOException {
    if (executable == null || !Files.isRegularFile(executable)) {
      throw new IOException("the " + label + " sidecar was not found at " + executable);
    }
    return launch(label, List.of(executable.toString()), workingDirectory);
  }

  /**
   * Launch a sidecar with a full command line.
   *
   * <p>Most sidecars are one native executable. Some are not: the TypeScript one is a Node program,
   * so the command is the Node runtime and then the script it runs. The framing, the handshake and
   * the process handling are the same either way -- only the command differs.
   *
   * @param label the sidecar's name, used in the messages and the thread name
   * @param command the command line: the program, then its arguments
   * @param workingDirectory the directory to run it in, or null for the current one
   * @return the process
   * @throws IOException when the sidecar could not be started
   */
  public static SidecarProcess launch(String label, List<String> command, Path workingDirectory)
      throws IOException {
    if (command == null || command.isEmpty()) {
      throw new IOException("the " + label + " sidecar was given no command to run");
    }
    String program = command.get(0);
    if (program == null || program.isBlank()) {
      throw new IOException("the " + label + " sidecar was given no program to run");
    }
    // A program named by a PATH is looked up by the operating system; a
    // program named by a path must exist, because a typo there would
    // otherwise surface as an opaque "cannot start".
    if (namesAPath(program) && !Files.isRegularFile(Path.of(program))) {
      throw new IOException("the " + label + " sidecar was not found at " + program);
    }
    ProcessBuilder builder = new ProcessBuilder(command);
    if (workingDirectory != null) {
      builder.directory(workingDirectory.toFile());
    }
    // The protocol is on stdout; the sidecar's chatter is on stderr. Nothing else is
    // inherited, so a stray line can never corrupt the frame stream.
    builder.redirectErrorStream(false);
    Process process = builder.start();
    return new SidecarProcess(label, process);
  }

  /**
   * Whether a program name names a path rather than a PATH entry.
   *
   * @param program the program name
   * @return true when the name carries a directory separator
   */
  private static boolean namesAPath(String program) {
    return program.indexOf('/') >= 0 || program.indexOf('\\') >= 0;
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
    try {
      toSidecar.writeInt(payload.length);
      toSidecar.write(payload);
      toSidecar.flush();
    } catch (IOException ex) {
      throw died(ex);
    }

    int length;
    try {
      length = fromSidecar.readInt();
    } catch (IOException ex) {
      throw died(ex);
    }
    if (length < 0 || length > MAX_FRAME_BYTES) {
      throw new IOException(
          "the " + label + " sidecar sent a frame of " + length + " bytes, which is out of range");
    }
    byte[] response = new byte[length];
    try {
      fromSidecar.readFully(response);
    } catch (IOException ex) {
      throw died(ex);
    }
    return new String(response, StandardCharsets.UTF_8);
  }

  /**
   * The exception to raise when the sidecar did not answer.
   *
   * <p><b>Why the sidecar's own words are in the message.</b> A sidecar dies for reasons the JVM
   * cannot see: the Node runtime cannot find the module the script imports, the runtime is missing
   * itself, the script has a syntax error. Every one of those says itself on the sidecar's stderr,
   * and the sidecar's stderr is exactly where it goes. Without it the reader gets a broken pipe or
   * an end of file and has to guess which of the reasons it was -- and the most common one, a
   * dependency that was never installed, looks like a broken product rather than a missing setup
   * step. So the tail of the stderr goes into the message: the cause is the one the sidecar
   * reported.
   *
   * @param cause what the pipe did
   * @return the exception to throw
   */
  private IOException died(IOException cause) {
    awaitTheEnd();
    StringBuilder message = new StringBuilder();
    message.append("the ").append(label).append(" sidecar did not answer");
    if (!process.isAlive()) {
      message.append(" (it exited with ").append(process.exitValue()).append(')');
    }
    message.append(": ").append(cause.getMessage());
    String tail = stderrTail().trim();
    if (!tail.isEmpty()) {
      message.append("; its last output was: ").append(tail);
    }
    return new IOException(message.toString(), cause);
  }

  /**
   * Wait for the sidecar to end, and for its stderr to be collected.
   *
   * <p>The sidecar's last words are on its stderr, and the thread that collects them reads the pipe
   * until it ends. A pipe that breaks can be noticed before that thread has read what was written,
   * so the message would be built from an empty tail and would say nothing about the real cause. So
   * the wait is for the sidecar to end and for the pump to catch up.
   *
   * <p>A sidecar that is still running is not waited for: it may answer the next request, and a
   * failure in the middle of a conversation is not its end.
   */
  private void awaitTheEnd() {
    if (process.isAlive()) {
      return;
    }
    try {
      process.waitFor(2, TimeUnit.SECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      return;
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!stderrClosed && System.nanoTime() < deadline) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        return;
      }
    }
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
}
