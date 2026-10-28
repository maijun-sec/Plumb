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
package org.plumb.dfa.engine;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Generic build-output directory filter. Any project that's been compiled will have a build-output
 * directory (target/, build/, out/, bin/, .gradle/, antbuild/ etc.) where the compiler copies
 * source files to a different layout. These copies confuse the parser (they often re-arrange {@code
 * package} statements or duplicate the source under a nested path) and should be excluded from the
 * sweep.
 *
 * <p>The default exclusion set is the union of the names we have seen in real Java projects:
 * Maven's {@code target/}, Gradle's {@code build/}, IntelliJ's {@code out/}, plain {@code bin/},
 * Ant's {@code antbuild/}, the {@code .gradle/} configuration cache, Eclipse's {@code bin/} and the
 * per-user {@code .idea/} folder. Callers can extend the set via the constructor or the static
 * {@link #of(String...)} factory. The filter is a pure path-component test; the default inclusion /
 * exclusion is the same regardless of which project is being scanned.
 */
public final class BuildOutputFilter {

  private static final Set<String> DEFAULT_NAMES =
      Set.of(
          "target",
          "build",
          "out",
          "bin",
          "antbuild",
          "dist",
          ".gradle",
          ".idea",
          "node_modules",
          "__pycache__");

  private final Set<String> excludedLower;

  public BuildOutputFilter() {
    this(DEFAULT_NAMES);
  }

  public BuildOutputFilter(Set<String> excluded) {
    this.excludedLower = lowerCopy(excluded);
  }

  public static BuildOutputFilter of(String... extraNames) {
    Set<String> all = new LinkedHashSet<>(DEFAULT_NAMES);
    all.addAll(Arrays.asList(extraNames));
    return new BuildOutputFilter(all);
  }

  private static Set<String> lowerCopy(Set<String> in) {
    Set<String> out = new LinkedHashSet<>();
    for (String s : in) {
      if (s != null) out.add(s.toLowerCase(Locale.ROOT));
    }
    return out;
  }

  /** True if any path component of {@code p} is a known build-output directory. */
  public boolean isBuildOutput(Path p) {
    if (p == null) return false;
    for (Path part : p) {
      String name = part.getFileName() == null ? "" : part.getFileName().toString();
      if (name.isEmpty()) continue;
      if (excludedLower.contains(name.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  /** Convenience: filter a stream of paths in place. */
  public Stream<Path> filter(Stream<Path> in) {
    return in.filter(p -> !isBuildOutput(p));
  }
}
