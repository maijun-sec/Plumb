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
 * <h2>Why the package also carries the modules it belongs to</h2>
 *
 * <p>The export data is gone by the time the analysis reads the capture -- the build deletes its
 * temporary directory when it ends -- so a dependency is usually resolved from its source instead.
 * The source of a dependency that was <b>replaced</b> or that belongs to another module of a
 * <b>workspace</b> is not in the toolchain's module cache, so the front end reads it from the
 * module's own build description and passes it on.
 *
 * @param importPath the package's import path, as the compiler was told it
 * @param directory the directory the package was compiled in
 * @param files the source files the package was compiled from
 * @param goVersion the language version the package was compiled at, or null
 * @param buildTags the build constraints the package was compiled under, or empty
 * @param imports the dependencies' export data, keyed by the dependency's import path
 * @param modules the modules that live on disk, keyed by their module path; a dependency the
 *     toolchain's own lookup finds is absent from it
 */
public record GoPackage(
    String importPath,
    Path directory,
    List<Path> files,
    String goVersion,
    List<String> buildTags,
    Map<String, Path> imports,
    Map<String, Path> modules) {

  public GoPackage {
    files = files == null ? List.of() : List.copyOf(files);
    buildTags = buildTags == null ? List.of() : List.copyOf(buildTags);
    imports = imports == null ? Map.of() : Map.copyOf(imports);
    modules = modules == null ? Map.of() : Map.copyOf(modules);
  }

  /**
   * A package with no files.
   *
   * @param importPath the package's import path
   * @return the package
   */
  public static GoPackage empty(String importPath) {
    return new GoPackage(importPath, null, List.of(), null, List.of(), Map.of(), Map.of());
  }
}
