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
package org.plumb.tac.frontend.python;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.plumb.tac.frontend.sidecar.PythonRuntime;
import org.plumb.tac.frontend.sidecar.SidecarLocator;
import org.plumb.tac.frontend.sidecar.SidecarProcess;

/**
 * The client side of the Python sidecar: a long-lived Python process, spoken to over stdio.
 *
 * <h2>Why a sidecar</h2>
 *
 * <p>Plumb is a JVM product, and Python's own parser is not a JVM program. It is part of the
 * reference implementation, it decides what Python 3 means, and a JVM re-implementation of it would
 * be a second implementation that disagrees with the real one -- and a name that resolves
 * differently produces a different callee signature, which is what the sink catalogue matches on.
 *
 * <p>So the analysis runs in a sidecar that drives CPython's own {@code ast} module.
 *
 * <h2>Why the command is TWO words</h2>
 *
 * <p>Most sidecars are a single native executable, and the launch is one path. This one is a
 * <b>Python program</b>: the parser is reached through the script that drives it, so the command is
 * the interpreter and then the script. The framing, the handshake and the process handling are
 * shared with the other sidecars -- see {@link SidecarProcess} -- and only the command differs.
 *
 * <h2>Why the script is found and not built</h2>
 *
 * <p>The sidecar has no dependency to install -- {@code ast} is in the standard library -- but the
 * script itself is a source file that ships with the product. So it is looked up where it may live
 * -- the source tree, a distribution -- and a missing one is reported as a missing one, never as
 * "no findings".
 *
 * @see PythonTacFrontend
 */
public final class PythonSidecarClient implements AutoCloseable {

  /** The name used in the sidecar's messages. */
  private static final String LABEL = "Python";

  /**
   * Where the sidecar's entry script lives.
   *
   * <p>The script is not an executable, so it cannot be "the executable": it is the <b>argument</b>
   * of the interpreter, and the interpreter is found separately. The layout therefore names the
   * script's own directory and its file name.
   */
  private static final SidecarLocator.Layout LAYOUT =
      new SidecarLocator.Layout(
          "PLUMB_PYTHON_SIDECAR", "python-sidecar", List.of("src"), "main.py");

  private final SidecarProcess process;

  private PythonSidecarClient(SidecarProcess process) {
    this.process = process;
  }

  /**
   * Launch the sidecar.
   *
   * @param command the command line: the interpreter, then the script
   * @param workingDirectory the directory to run it in, or null for the current one
   * @return the client
   * @throws IOException when the sidecar could not be started
   */
  public static PythonSidecarClient launch(List<String> command, Path workingDirectory)
      throws IOException {
    return new PythonSidecarClient(SidecarProcess.launch(LABEL, command, workingDirectory));
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
    return process.exchange(request);
  }

  /**
   * The sidecar's most recent stderr output, for a diagnostic message.
   *
   * @return the tail of the sidecar's stderr, or an empty string
   */
  public String stderrTail() {
    return process.stderrTail();
  }

  /**
   * Ask the sidecar to exit, and wait a moment for it.
   *
   * @param timeoutMillis how long to wait for the exit
   */
  public void shutdown(long timeoutMillis) {
    process.shutdown(timeoutMillis);
  }

  @Override
  public void close() {
    process.close();
  }

  /**
   * Find the sidecar's entry script.
   *
   * <p>The lookup order is: an explicit override, the {@code PLUMB_PYTHON_SIDECAR} environment
   * variable, the directory next to this class, and the source tree's sidecar directory.
   *
   * @param override an explicit path, or null
   * @return the script, or null when none was found
   */
  public static Path locateScript(Path override) {
    return SidecarLocator.locate(override, LAYOUT, null);
  }

  /**
   * Find the Python runtime.
   *
   * <p>The lookup order is: the {@code PLUMB_PYTHON} environment variable, then the PATH.
   *
   * <p>An environment variable that names something that is not there is a <b>hard</b> failure
   * rather than a reason to fall back: the operator asked for a specific runtime, and quietly
   * running a different one would make the analysis depend on a machine detail nobody chose.
   *
   * @return the runtime, or null when none was found
   */
  public static Path locatePython() {
    return PythonRuntime.locate();
  }

  /**
   * Find the Python runtime on a given PATH.
   *
   * @param path the PATH to search
   * @return the runtime, or null when there is no python command on it
   */
  public static Path pythonOnPath(String path) {
    return PythonRuntime.onPath(path);
  }

  /**
   * The command that runs the sidecar.
   *
   * @param python the Python runtime
   * @param script the sidecar's entry script
   * @return the command line
   */
  public static List<String> command(Path python, Path script) {
    return List.of(python.toString(), script.toString());
  }
}
