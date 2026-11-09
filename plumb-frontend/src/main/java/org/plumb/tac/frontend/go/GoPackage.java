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
package org.plumb.tac.frontend.go;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One Go package, as the build compiled it.
 *
 * <h2>Why a package and not a file</h2>
 *
 * <p>Go's unit of compilation is the <b>package</b>, not the file. A file cannot be compiled on
 * its own: it names symbols that its siblings declare, and the compiler reads the whole package
 * at once. So the unit the analysis works on is the package, and a project is a set of packages.
 *
 * <h2>Why the dependencies carry export data and not source</h2>
 *
 * <p>A dependency is resolved from its <b>export data</b> -- the compiler's own record of the
 * package's exported API, produced when that package was compiled. Resolving from source instead
 * would give a different answer whenever the build used a different build tag, a different
 * {@code GOOS}, or a vendored or replaced dependency. Resolving from the export data is what makes
 * the analysis agree with the build.
 *
 * @param importPath the package's import path, as the compiler was told it
 * @param directory the directory the package was compiled in
 * @param files the source files the package was compiled from
 * @param goVersion the language version the package was compiled at, or null
 * @param buildTags the build constraints the package was compiled under, or empty
 * @param imports the dependencies' export data, keyed by the dependency's import path
 */
public record GoPackage(
    String importPath,
    Path directory,
    List<Path> files,
    String goVersion,
    List<String> buildTags,
    Map<String, Path> imports) {

  public GoPackage {
    files = files == null ? List.of() : List.copyOf(files);
    buildTags = buildTags == null ? List.of() : List.copyOf(buildTags);
    imports = imports == null ? Map.of() : Map.copyOf(imports);
  }

  /**
   * A package with no files.
   *
   * @param importPath the package's import path
   * @return the package
   */
  public static GoPackage empty(String importPath) {
    return new GoPackage(importPath, null, List.of(), null, List.of(), Map.of());
  }
}
