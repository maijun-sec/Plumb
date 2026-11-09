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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.plumb.tac.frontend.CompileContext;

/**
 * Finds a Go project's packages in the source tree, for a scan that had no build capture.
 *
 * <h2>Why the whole module and not the one package</h2>
 *
 * <p>A Go package cannot be type checked on its own -- it names symbols its siblings declare --
 * and the packages of a module depend on each other. So a scan that analysed only the package the
 * scanned file happens to be in would leave every cross-package call unresolved, and an
 * unresolved call is a missing finding rather than a missing precision.
 *
 * <p>So the discovery walks the <b>module</b>: the nearest ancestor directory that holds a {@code
 * go.mod} file, and every package inside it.
 *
 * <h2>What it does not know</h2>
 *
 * <p>The dependencies' export data. That comes from the build, and without a capture there is
 * none -- so the external packages' types may be unknown, and the frontend says so rather than
 * pretending otherwise.
 */
public final class GoProjectDiscovery {

  private GoProjectDiscovery() {}

  /**
   * The directories that are never part of a module's own packages.
   *
   * <p>{@code vendor} holds a dependency's source rather than the module's own code, {@code
   * testdata} holds fixtures, and a directory whose name starts with {@code _} or {@code .} is
   * ignored by the Go toolchain itself.
   */
  private static final Set<String> EXCLUDED_DIRECTORIES =
      Set.of("vendor", "testdata", "node_modules");

  /**
   * Discover the project a file belongs to.
   *
   * @param file the file being parsed
   * @param ctx the compile context, or null
   * @return the project; empty when the file belongs to no module
   */
  public static GoProjectProfile discover(Path file, CompileContext ctx) {
    if (file == null) {
      return GoProjectProfile.empty();
    }
    Path moduleRoot = moduleRootOf(file);
    if (moduleRoot == null) {
      return GoProjectProfile.empty();
    }
    String modulePath = modulePathOf(moduleRoot);
    List<GoPackage> packages = packagesOf(moduleRoot, modulePath);
    return new GoProjectProfile(
        packages, List.of(moduleRoot), List.of(), null, List.of(), toolchainRootOf());
  }

  /**
   * The Go toolchain's root directory, found on the PATH.
   *
   * <h2>Why the discovery path needs it</h2>
   *
   * <p>The standard library's source lives under the toolchain's root, and the front end has to
   * find it to type-check the project's dependencies. A capture names the toolchain outright -- the
   * compiler's own path says which one it was -- but a scan with no capture has nothing to read
   * that from, so the {@code go} command on the PATH is the next best answer: it lives at {@code
   * <root>/bin/go}, so the root is two levels up.
   *
   * <p>The environment's {@code GOROOT} is deliberately NOT used. It is a leftover of whatever
   * toolchain was installed last, it outlives the toolchain it names, and a value that points at a
   * directory which no longer exists makes the standard library's source look absent -- which makes
   * every standard library import fail silently.
   *
   * @return the toolchain's root directory, or null when there is no go command on the PATH
   */
  static Path toolchainRootOf() {
    return toolchainRootIn(System.getenv("PATH"));
  }

  /**
   * The Go toolchain's root directory, found on a given PATH.
   *
   * @param path the PATH to search
   * @return the toolchain's root directory, or null when there is no go command on it
   */
  static Path toolchainRootIn(String path) {
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
      for (String name : new String[] {"go", "go.exe"}) {
        Path candidate = directory.resolve(name);
        if (!Files.isRegularFile(candidate)) {
          continue;
        }
        Path bin = candidate.getParent();
        Path root = bin == null ? null : bin.getParent();
        if (root != null && isToolchainRoot(root)) {
          return root.toAbsolutePath().normalize();
        }
      }
    }
    return null;
  }

  /**
   * Whether a path names a Go toolchain's root.
   *
   * <p>The check is for the standard library's <b>source</b>, and not merely for a directory. A
   * directory can outlive the toolchain that filled it: an emptied GOROOT that still holds the
   * module cache looks exactly like a toolchain to a directory check, and the front end would then
   * look for the standard library where it has never been.
   *
   * @param root the candidate root
   * @return true when the root holds a Go toolchain
   */
  private static boolean isToolchainRoot(Path root) {
    return root != null
        && Files.isDirectory(root)
        && Files.isDirectory(root.resolve("src"));
  }

  /**
   * The module root a file belongs to.
   *
   * @param file the file
   * @return the nearest ancestor directory holding a {@code go.mod}, or null
   */
  public static Path moduleRootOf(Path file) {
    Path current = file.toAbsolutePath().normalize();
    if (!Files.isDirectory(current)) {
      current = current.getParent();
    }
    while (current != null) {
      if (Files.isRegularFile(current.resolve("go.mod"))) {
        return current;
      }
      current = current.getParent();
    }
    return null;
  }

  /**
   * The module's import path, as its {@code go.mod} declares it.
   *
   * @param moduleRoot the module's root
   * @return the module path, or the directory's own name when it cannot be read
   */
  public static String modulePathOf(Path moduleRoot) {
    Path goMod = moduleRoot.resolve("go.mod");
    if (!Files.isRegularFile(goMod)) {
      return moduleRoot.getFileName() == null ? "" : moduleRoot.getFileName().toString();
    }
    try {
      for (String line : Files.readAllLines(goMod, StandardCharsets.UTF_8)) {
        String text = line.trim();
        if (text.startsWith("module ")) {
          String path = text.substring("module ".length()).trim();
          return path.isEmpty() ? moduleRoot.getFileName().toString() : path;
        }
      }
    } catch (IOException ex) {
      // A module file that cannot be read leaves the import path unknown, and
      // the directory's name is the best answer available.
    }
    return moduleRoot.getFileName() == null ? "" : moduleRoot.getFileName().toString();
  }

  /**
   * Every package in a module.
   *
   * @param moduleRoot the module's root
   * @param modulePath the module's import path
   * @return the packages, the root package first
   */
  public static List<GoPackage> packagesOf(Path moduleRoot, String modulePath) {
    List<GoPackage> packages = new ArrayList<>();
    List<Path> directories = packageDirectories(moduleRoot);
    for (Path directory : directories) {
      List<Path> files = goFilesOf(directory);
      if (files.isEmpty()) {
        continue;
      }
      packages.add(
          new GoPackage(
              importPathOf(moduleRoot, modulePath, directory),
              directory,
              files,
              null,
              List.of(),
              java.util.Map.of(),
              java.util.Map.of()));
    }
    return packages;
  }

  /**
   * The directories under a module that hold Go files.
   *
   * @param moduleRoot the module's root
   * @return the directories, nearest first
   */
  private static List<Path> packageDirectories(Path moduleRoot) {
    List<Path> out = new ArrayList<>();
    try (var walk = Files.walk(moduleRoot)) {
      walk.filter(Files::isDirectory)
          .filter(path -> !isExcluded(moduleRoot, path))
          .forEach(out::add);
    } catch (IOException ex) {
      // A module that cannot be walked yields what was found so far.
    }
    out.sort(java.util.Comparator.comparingInt(Path::getNameCount));
    return out;
  }

  /**
   * Whether a directory is outside the module's own packages.
   *
   * @param moduleRoot the module's root
   * @param directory the directory
   * @return true when the directory holds no package of this module
   */
  private static boolean isExcluded(Path moduleRoot, Path directory) {
    if (directory.equals(moduleRoot)) {
      return false;
    }
    Path relative = moduleRoot.relativize(directory);
    for (Path segment : relative) {
      String name = segment.toString().toLowerCase(Locale.ROOT);
      if (EXCLUDED_DIRECTORIES.contains(name)) {
        return true;
      }
      if (name.startsWith("_") || name.startsWith(".")) {
        return true;
      }
    }
    return false;
  }

  /**
   * The Go files of a directory.
   *
   * <p>Test files are left out: they are not part of the package the program ships, and they are
   * compiled with the package's own test-only additions.
   *
   * @param directory the directory
   * @return the files
   */
  private static List<Path> goFilesOf(Path directory) {
    Set<Path> out = new LinkedHashSet<>();
    try (var list = Files.list(directory)) {
      list.filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".go"))
          .filter(path -> !path.getFileName().toString().endsWith("_test.go"))
          .forEach(out::add);
    } catch (IOException ex) {
      // A directory that cannot be listed contributes no files.
    }
    return List.copyOf(out);
  }

  /**
   * The import path of a package directory.
   *
   * @param moduleRoot the module's root
   * @param modulePath the module's import path
   * @param directory the package's directory
   * @return the import path
   */
  private static String importPathOf(Path moduleRoot, String modulePath, Path directory) {
    Path relative = moduleRoot.relativize(directory);
    String suffix = relative.toString().replace('\\', '/');
    if (suffix.isEmpty() || ".".equals(suffix)) {
      return modulePath;
    }
    return modulePath.isEmpty() ? suffix : modulePath + "/" + suffix;
  }
}
