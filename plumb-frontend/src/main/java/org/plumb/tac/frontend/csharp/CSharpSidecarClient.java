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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.plumb.tac.frontend.sidecar.SidecarLocator;
import org.plumb.tac.frontend.sidecar.SidecarProcess;

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
 * <p>The process handling and the framing are shared with the other sidecars -- see {@link
 * SidecarProcess}. What is specific to C# is only where the executable lives.
 *
 * @see CSharpTacFrontend
 */
public final class CSharpSidecarClient implements AutoCloseable {

  /** The name used in the sidecar's messages. */
  private static final String LABEL = "C#";

  /**
   * Where the C# sidecar lives.
   *
   * <p>The build output is under {@code bin/Release/net8.0}, because the sidecar is a .NET
   * application and that is where the .NET build puts it.
   */
  private static final SidecarLocator.Layout LAYOUT =
      new SidecarLocator.Layout(
          "PLUMB_CSHARP_SIDECAR",
          "csharp-sidecar",
          List.of("bin/Release/net8.0", "bin/Debug/net8.0"),
          executableName());

  private final SidecarProcess process;

  private CSharpSidecarClient(SidecarProcess process) {
    this.process = process;
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
    return new CSharpSidecarClient(SidecarProcess.launch(LABEL, executable, workingDirectory));
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
   * Find the sidecar executable.
   *
   * <p>The lookup order is: an explicit override, the {@code PLUMB_CSHARP_SIDECAR} environment
   * variable, the build output next to this class, and the source tree's build directory.
   *
   * @param override an explicit path, or null
   * @return the executable, or null when none was found
   */
  public static Path locateExecutable(Path override) {
    return SidecarLocator.locate(override, LAYOUT, null);
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
}
