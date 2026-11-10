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
 * Where the Python runtime is.
 *
 * <p>The Python sidecar is a Python program: it drives CPython's own {@code ast} module, which is
 * the parser that decides what Python 3 means. There is no second implementation of it in the JVM
 * that would agree with it on every corner case, so the analysis runs in a sidecar and this is
 * where that sidecar's interpreter lives.
 *
 * <h2>Why the environment variable is a HARD failure</h2>
 *
 * <p>An environment variable that names something that is not there is <b>hard</b> failure rather
 * than a reason to fall back: the operator asked for a specific runtime, and quietly running a
 * different one would make the analysis depend on a machine detail nobody chose.
 *
 * <h2>Why Python 3 and not Python 2</h2>
 *
 * <p>The sidecar parses with Python 3's grammar. A Python 2 interpreter would read a Python 3 file
 * as a broken one -- or worse, read it as a Python 2 file -- so the frontend asks the sidecar which
 * interpreter it is running on and refuses one that is not a Python 3. The version is checked at
 * run time rather than assumed, because a PATH lookup cannot tell a Python 2 from a Python 3 by its
 * name.
 */
public final class PythonRuntime {

  private PythonRuntime() {}

  /**
   * Find the Python runtime.
   *
   * <p>The lookup order is: the {@code PLUMB_PYTHON} environment variable, then the PATH.
   *
   * @return the runtime, or null when none was found
   */
  public static Path locate() {
    String override = System.getenv("PLUMB_PYTHON");
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
   * Find the Python runtime on a given PATH.
   *
   * @param path the PATH to search
   * @return the runtime, or null when there is no python command on it
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
   * The names a Python runtime may have on this platform.
   *
   * <p>The version-suffixed name comes first: on a machine that has both, the unversioned name is
   * the one that is most likely to be something else.
   *
   * @return the names, most specific first
   */
  public static List<String> names() {
    boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    return windows
        ? List.of("python3.exe", "python.exe", "python3", "python")
        : List.of("python3", "python");
  }
}
