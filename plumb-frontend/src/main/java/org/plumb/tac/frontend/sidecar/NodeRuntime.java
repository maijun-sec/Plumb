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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Where the Node runtime is.
 *
 * <p>Two of the sidecars are Node programs rather than native executables: the TypeScript one and
 * the ArkTS one. They need the same runtime, and they must agree on where it is -- a frontend that
 * found a different Node than its sibling would make the same scan depend on which frontend ran
 * first.
 *
 * <h2>Why the environment variable is a HARD failure</h2>
 *
 * <p>An environment variable that names something that is not there is <b>hard</b> failure rather
 * than a reason to fall back: the operator asked for a specific runtime, and quietly running a
 * different one would make the analysis depend on a machine detail nobody chose.
 */
public final class NodeRuntime {

  private NodeRuntime() {}

  /**
   * Find the Node runtime.
   *
   * <p>The lookup order is: the {@code PLUMB_NODE} environment variable, then the PATH.
   *
   * @return the runtime, or null when none was found
   */
  public static Path locate() {
    String override = System.getenv("PLUMB_NODE");
    if (override != null && !override.isBlank()) {
      Path candidate;
      try {
        candidate = Path.of(override);
      } catch (RuntimeException ex) {
        return null;
      }
      return Files.isRegularFile(candidate) ? candidate.toAbsolutePath().normalize() : null;
    }
    return onPath(System.getenv("PATH"));
  }

  /**
   * Find the Node runtime on a given PATH.
   *
   * @param path the PATH to search
   * @return the runtime, or null when there is no node command on it
   */
  public static Path onPath(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    for (String entry : path.split(java.io.File.pathSeparator)) {
      if (entry.isBlank()) {
        continue;
      }
      Path directory;
      try {
        directory = Path.of(entry);
      } catch (RuntimeException ex) {
        // A PATH entry the platform cannot even parse is not a directory.
        continue;
      }
      for (String name : names()) {
        Path candidate = directory.resolve(name);
        if (Files.isRegularFile(candidate)) {
          return candidate.toAbsolutePath().normalize();
        }
      }
    }
    return null;
  }

  /**
   * The names a Node runtime may have on this platform.
   *
   * @return the names, most specific first
   */
  public static List<String> names() {
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    return windows ? List.of("node.exe", "node.cmd", "node") : List.of("node");
  }
}
