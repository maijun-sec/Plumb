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
package org.plumb.tac.frontend.arkts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.plumb.tac.frontend.sidecar.NodeRuntime;
import org.plumb.tac.frontend.sidecar.SidecarLocator;
import org.plumb.tac.frontend.sidecar.SidecarProcess;

/**
 * The client side of the ArkTS sidecar: a long-lived Node process, spoken to over stdio.
 *
 * <h2>Why a sidecar</h2>
 *
 * <p>Plumb is a JVM product, and the ArkTS compiler is not a JVM program. ArkTS is read by a fork
 * of the TypeScript compiler that knows the language: it parses {@code struct}, it knows the ArkUI
 * decorators, and it parses the declarative UI DSL. A JVM re-implementation of that grammar would
 * be a second implementation that disagrees with the real one -- and a disagreement here is not
 * cosmetic: the UI DSL is only parsed inside a render method the compiler looks up by name, and a
 * component is only a component when its name is on a list.
 *
 * <p>So the analysis runs in a sidecar that drives the compiler's own API.
 *
 * <h2>Why the sidecar is its own npm project</h2>
 *
 * <p>The ArkTS compiler and the TypeScript compiler are different programs with different APIs.
 * Sharing a file between the two sidecars would force one of them into the other's shape, so each
 * owns its own copy of the protocol and its own translator.
 *
 * <h2>Why the command is TWO words</h2>
 *
 * <p>The sidecar is a <b>Node program</b>: the compiler is reached through the script that drives
 * it, so the command is the Node runtime and then the script. The framing, the handshake and the
 * process handling are shared with the other sidecars -- see {@link SidecarProcess} -- and only the
 * command differs.
 *
 * @see ArktsTacFrontend
 */
public final class ArktsSidecarClient implements AutoCloseable {

  /** The name used in the sidecar's messages. */
  private static final String LABEL = "ArkTS";

  /**
   * Where the sidecar's entry script lives.
   *
   * <p>The script is not an executable, so it cannot be "the executable": it is the <b>argument</b>
   * of the Node runtime, and the runtime is found separately. The layout therefore names the
   * script's own directory and its file name.
   */
  private static final SidecarLocator.Layout LAYOUT =
      new SidecarLocator.Layout("PLUMB_ARKTS_SIDECAR", "arkts-sidecar", List.of("src"), "main.mjs");

  private final SidecarProcess process;

  private ArktsSidecarClient(SidecarProcess process) {
    this.process = process;
  }

  /**
   * Launch the sidecar.
   *
   * @param command the command line: the Node runtime, then the script
   * @param workingDirectory the directory to run it in, or null for the current one
   * @return the client
   * @throws IOException when the sidecar could not be started
   */
  public static ArktsSidecarClient launch(List<String> command, Path workingDirectory)
      throws IOException {
    return new ArktsSidecarClient(SidecarProcess.launch(LABEL, command, workingDirectory));
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
   * <p>The lookup order is: an explicit override, the {@code PLUMB_ARKTS_SIDECAR} environment
   * variable, the directory next to this class, and the source tree's sidecar directory.
   *
   * @param override an explicit path, or null
   * @return the script, or null when none was found
   */
  public static Path locateScript(Path override) {
    return SidecarLocator.locate(override, LAYOUT, null);
  }

  /**
   * Find the Node runtime.
   *
   * <p>The lookup order is: the {@code PLUMB_NODE} environment variable, then the PATH.
   *
   * @return the runtime, or null when none was found
   */
  public static Path locateNode() {
    return NodeRuntime.locate();
  }

  /**
   * The command that runs the sidecar.
   *
   * @param node the Node runtime
   * @param script the sidecar's entry script
   * @return the command line
   */
  public static List<String> command(Path node, Path script) {
    return List.of(node.toString(), script.toString());
  }
}
