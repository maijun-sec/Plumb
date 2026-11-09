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

/**
 * The whole Go project, as the build compiled it.
 *
 * <h2>Why the project is the unit</h2>
 *
 * <p>A Go project is a <b>module</b> (or a workspace of modules), and it is made of packages. The
 * packages depend on each other, so translating one of them alone would leave every cross-package
 * call unresolved -- and an unresolved call is a missing finding, not a missing precision.
 *
 * <p>So the profile carries <b>every</b> package the build compiled, and the frontend opens them
 * as one project. This is the same shape the C# frontend uses: one compilation for the whole
 * project, reused for every file.
 *
 * @param packages the packages the build compiled
 * @param moduleRoots the roots of the modules the packages belong to, for discovery
 * @param linkedPackages the import paths the linker put into the binary, or empty
 * @param binary the binary the build produced, or null
 * @param lostExportData the import configurations the build named but that could no longer be
 *     read, or empty
 * @param goroot the Go toolchain's root directory, or null when the build did not name one
 */
public record GoProjectProfile(
    List<GoPackage> packages,
    List<Path> moduleRoots,
    List<String> linkedPackages,
    Path binary,
    List<String> lostExportData,
    Path goroot) {

  public GoProjectProfile {
    packages = packages == null ? List.of() : List.copyOf(packages);
    moduleRoots = moduleRoots == null ? List.of() : List.copyOf(moduleRoots);
    linkedPackages = linkedPackages == null ? List.of() : List.copyOf(linkedPackages);
    lostExportData = lostExportData == null ? List.of() : List.copyOf(lostExportData);
  }

  /**
   * A profile with nothing captured.
   *
   * @return the empty profile
   */
  public static GoProjectProfile empty() {
    return new GoProjectProfile(List.of(), List.of(), List.of(), null, List.of(), null);
  }

  /**
   * Whether this profile carries any captured input at all.
   *
   * @return true when nothing was captured
   */
  public boolean isEmpty() {
    return packages.isEmpty();
  }

  /**
   * The package with a given import path.
   *
   * @param importPath the import path
   * @return the package, or null when the build did not compile it
   */
  public GoPackage packageOf(String importPath) {
    if (importPath == null) {
      return null;
    }
    for (GoPackage candidate : packages) {
      if (importPath.equals(candidate.importPath())) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * Every source file of every package.
   *
   * @return the files, without duplicates
   */
  public List<Path> allFiles() {
    java.util.Set<Path> out = new java.util.LinkedHashSet<>();
    for (GoPackage candidate : packages) {
      out.addAll(candidate.files());
    }
    return List.copyOf(out);
  }
}
