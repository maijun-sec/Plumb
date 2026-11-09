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
import java.util.concurrent.TimeUnit;

/**
 * A native sidecar process, spoken to over a length-prefixed frame.
 *
 * <h2>Why a sidecar</h2>
 *
 * <p>Plumb is a JVM product, and some languages cannot be analysed from the JVM: their own
 * compiler is a native library with no JVM port, and re-implementing its semantics in the JVM
 * would mean writing a second compiler that disagrees with the real one on every corner case.
 * So the analysis runs in a sidecar -- a separate process that owns the language's own front end
 * -- and the two halves talk over stdio.
 *
 * <h2>The framing</h2>
 *
 * <p>Each message is a JSON object, UTF-8 encoded, framed as {@code <4-byte big-endian length>
 * <payload>}. The length counts the payload bytes only.
 *
 * <p><b>Why not JSON Lines.</b> A source file may contain any byte in a string literal --
 * including a newline inside a verbatim string. A line-oriented protocol then has to either
 * escape the newlines or guess where a line ends, and a length prefix has no such ambiguity: the
 * frame boundary is explicit and cannot be spoofed by the content.
 *
 * <p><b>Why the protocol is on stdout and the chatter is on stderr.</b> The two are separate
 * pipes, so a stray diagnostic line can never corrupt the frame stream.
 *
 * <h2>Totality</h2>
 *
 * <p>A frontend must not throw on malformed input. Everything here reports a failure as an
 * exception the caller turns into a structured problem -- never as a silent empty result, which
 * would read as "no findings".
 */
public final class SidecarProcess implements AutoCloseable {

  /**
   * The largest frame accepted from the sidecar. A corrupt length must not become an
   * out-of-memory kill.
   */
  private static final int MAX_FRAME_BYTES = 256 * 1024 * 1024;

  private final String label;
  private final Process process;
  private final DataOutputStream toSidecar;
  private final DataInputStream fromSidecar;
  private final Thread stderrPump;
  private final StringBuilder stderrTail = new StringBuilder();

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
    ProcessBuilder builder = new ProcessBuilder(executable.toString());
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
      throw new IOException("the " + label + " sidecar sent a frame of " + length + " bytes, which is out of range");
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
}
