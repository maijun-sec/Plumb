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
package org.plumb.tac.frontend.php;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.plumb.tac.frontend.CompileContext;

/**
 * Finds the source files of a PHP project in the source tree.
 *
 * <h2>Why the whole project and not the one file</h2>
 *
 * <p>A PHP file's module name is its path under the project's root, and a callee's owner is the
 * namespace that declares it. So the sidecar is told where the project is, and every file is
 * translated with that root in mind. A file analysed on its own would carry a module name that says
 * nothing about where it lives.
 *
 * <h2>Where the project's boundary is</h2>
 *
 * <p>The boundary is the compile context's module root when the file belongs to one -- that is the
 * root the scan was told to analyse -- and otherwise the nearest ancestor that holds a {@code
 * composer.json}, which is the PHP toolchain's own statement of what a project is. A single file
 * with neither is its own project.
 *
 * <p>What is deliberately <b>not</b> a marker is a bare directory of PHP files: PHP has no
 * configuration file that says "this directory is a project", and inventing one would make the
 * boundary a guess.
 *
 * <h2>What is left out</h2>
 *
 * <p>The {@code vendor} directory holds the dependencies, {@code node_modules} and {@code dist} /
 * {@code build} / {@code out} hold the build's output, and {@code coverage} holds a report. A
 * directory whose name starts with a dot is a tool's own state.
 */
public final class PhpProjectDiscovery {

  private PhpProjectDiscovery() {}

  /** The extensions of the files that belong to a PHP project. */
  public static final Set<String> EXTENSIONS = Set.of("php");

  /**
   * The directories that never hold a project's own sources.
   *
   * <p>{@code vendor} holds the dependencies Composer installed, {@code node_modules} holds the
   * dependencies of a front-end build, {@code dist} / {@code build} / {@code out} hold the build's
   * output, and {@code coverage} holds a report.
   */
  private static final Set<String> EXCLUDED_DIRECTORIES =
      Set.of("vendor", "node_modules", "dist", "build", "out", "coverage");

  /**
   * The files that mark a project.
   *
   * <p>The Composer manifest is the toolchain's own list of what the project is and where its
   * sources live, so a boundary that ignores it would cut a project in the wrong place.
   */
  private static final List<String> PROJECT_MARKERS = List.of("composer.json");

  /**
   * The most files a project may hold.
   *
   * <p>A project can hold tens of thousands of source files, and giving all of them to the sidecar
   * is both slow and pointless for an analysis that reads a few of them. The cap makes the cost
   * bounded, and the truncation is <b>reported</b> rather than hidden: a project that was cut short
   * may have unknown module names, and that is a lower bound on the findings rather than an answer.
   */
  public static final int MAX_FILES = 5000;

  /**
   * The files of one project.
   *
   * @param root the project's root directory, or null when there is none
   * @param files the project's source files, in a stable order
   * @param truncated whether the file list was cut short by {@link #MAX_FILES}
   */
  public record Project(Path root, List<Path> files, boolean truncated) {

    public Project {
      files = files == null ? List.of() : List.copyOf(files);
    }

    /** A project with no files. */
    public static Project empty() {
      return new Project(null, List.of(), false);
    }
  }

  /**
   * Discover the project a file belongs to.
   *
   * @param file the file being parsed
   * @param ctx the compile context, or null
   * @return the project
   */
  public static Project discover(Path file, CompileContext ctx) {
    if (file == null) {
      return Project.empty();
    }
    Path root = rootOf(file, ctx);
    if (root == null) {
      return Project.empty();
    }
    List<Path> files = filesUnder(root);
    boolean truncated = files.size() > MAX_FILES;
    if (truncated) {
      files = List.copyOf(files.subList(0, MAX_FILES));
    }
    return new Project(root, files, truncated);
  }

  /**
   * The root of the project a file belongs to.
   *
   * @param file the file
   * @param ctx the compile context, or null
   * @return the root, or null when the file has no directory
   */
  static Path rootOf(Path file, CompileContext ctx) {
    if (file == null) {
      return null;
    }
    Path absolute = file.toAbsolutePath().normalize();
    Path moduleRoot = moduleRootOf(absolute, ctx);
    if (moduleRoot != null) {
      return moduleRoot;
    }
    Path marked = markedRootOf(absolute);
    if (marked != null) {
      return marked;
    }
    return absolute.getParent();
  }

  /**
   * The compile context's module root that holds the file.
   *
   * @param file the file
   * @param ctx the compile context, or null
   * @return the module root, or null
   */
  private static Path moduleRootOf(Path file, CompileContext ctx) {
    if (ctx == null) {
      return null;
    }
    for (CompileContext.Module module : ctx.modules()) {
      Path root = module.sourceRoot();
      if (root == null) {
        continue;
      }
      Path normalized = root.toAbsolutePath().normalize();
      if (file.startsWith(normalized)) {
        return normalized;
      }
    }
    return null;
  }

  /**
   * The nearest ancestor directory that marks a project.
   *
   * @param file the file
   * @return the directory, or null
   */
  static Path markedRootOf(Path file) {
    Path current = file;
    if (!Files.isDirectory(current)) {
      current = current.getParent();
    }
    while (current != null) {
      for (String marker : PROJECT_MARKERS) {
        if (Files.isRegularFile(current.resolve(marker))) {
          return current;
        }
      }
      current = current.getParent();
    }
    return null;
  }

  /**
   * The source files under a root.
   *
   * @param root the root
   * @return the files, sorted by path
   */
  static List<Path> filesUnder(Path root) {
    Set<Path> out = new LinkedHashSet<>();
    try (var walk = Files.walk(root, 24)) {
      walk.filter(Files::isRegularFile)
          .filter(PhpProjectDiscovery::isSourceFile)
          .filter(path -> !isUnderExcludedDirectory(root, path))
          .forEach(out::add);
    } catch (IOException | UncheckedIOException | SecurityException ex) {
      // A tree that cannot be walked yields what was found so far. The
      // frontend says how many files the sidecar got, so a partial tree is
      // visible rather than silent.
      //
      // The unchecked failure is the interesting one: a directory that cannot
      // be READ -- a junction, a permission, a drive that is not there --
      // aborts the traversal with a runtime exception rather than an I/O
      // one, and a scan must not die of it.
    }
    List<Path> files = new ArrayList<>(out);
    files.sort(java.util.Comparator.comparing(Path::toString));
    return files;
  }

  /**
   * Whether a file is one this sidecar reads.
   *
   * @param file the file
   * @return true when the file's extension names a language of the sidecar
   */
  public static boolean isSourceFile(Path file) {
    if (file == null) {
      return false;
    }
    Path name = file.getFileName();
    if (name == null) {
      return false;
    }
    String text = name.toString();
    int dot = text.lastIndexOf('.');
    if (dot < 0 || dot == text.length() - 1) {
      return false;
    }
    return EXTENSIONS.contains(text.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  /**
   * Whether a file lives in a directory that is outside the project's own sources.
   *
   * @param root the project's root
   * @param file the file
   * @return true when the file is under an excluded directory
   */
  static boolean isUnderExcludedDirectory(Path root, Path file) {
    Path relative = root.relativize(file);
    Path parent = relative.getParent();
    if (parent == null) {
      return false;
    }
    for (Path segment : parent) {
      String name = segment.toString().toLowerCase(Locale.ROOT);
      if (EXCLUDED_DIRECTORIES.contains(name)) {
        return true;
      }
      if (name.startsWith(".")) {
        return true;
      }
    }
    return false;
  }
}
