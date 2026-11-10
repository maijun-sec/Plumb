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
package org.plumb.tac.frontend.ts;

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
 * Finds the source files of a TypeScript or JavaScript project in the source tree.
 *
 * <h2>Why the whole project and not the one file</h2>
 *
 * <p>A TypeScript file names the modules it imports, and the compiler resolves those names against
 * the project. A file analysed on its own therefore has unknown types wherever it imports something
 * -- and an unknown type is an unresolved callee, which is a <b>missing finding</b> rather than a
 * missing precision.
 *
 * <p>So the discovery collects the project's files, and the compiler is given all of them at once.
 *
 * <h2>Where the project's boundary is</h2>
 *
 * <p>The boundary is the compile context's module root when the file belongs to one -- that is the
 * root the scan was told to analyse -- and otherwise the nearest ancestor that holds a {@code
 * tsconfig.json}, which is the language's own notion of a project. A single file with neither is
 * its own project.
 *
 * <h2>What is left out</h2>
 *
 * <p>Declaration files ({@code .d.ts}) hold signatures and no executable body, so analysing them
 * would report findings against declarations rather than against code. The dependency directory and
 * the build outputs hold other people's code and generated code. A directory whose name starts with
 * a dot is a tool's own state.
 */
public final class TsProjectDiscovery {

  private TsProjectDiscovery() {}

  /** The extensions of the languages this sidecar reads. */
  public static final Set<String> EXTENSIONS =
      Set.of("ts", "tsx", "mts", "cts", "js", "jsx", "mjs", "cjs");

  /**
   * The directories that never hold a project's own sources.
   *
   * <p>{@code node_modules} holds the dependencies, {@code dist} / {@code build} / {@code out} hold
   * the build's output, and {@code coverage} holds a report.
   */
  private static final Set<String> EXCLUDED_DIRECTORIES =
      Set.of("node_modules", "dist", "build", "out", "coverage", "vendor");

  /**
   * The most files a project may hold.
   *
   * <p>A monorepo can hold tens of thousands of source files, and giving all of them to the
   * compiler is both slow and pointless for an analysis that reads a few of them. The cap makes the
   * cost bounded, and the truncation is <b>reported</b> rather than hidden: a project that was cut
   * short may have unknown types, and that is a lower bound on the findings rather than an answer.
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
   * <p><b>Why only {@code tsconfig.json}.</b> A package manifest is not a project boundary: the
   * compiler does not read it to decide what the project contains. Worse, a manifest is a common
   * thing to find in a HOME directory, and a boundary that walks up to it would make the whole home
   * directory one project -- thousands of files the analysis never asked for.
   *
   * <p>The {@code tsconfig.json} is the language's own statement of what the project is, and the
   * compiler reads exactly that file to decide.
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
      if (Files.isRegularFile(current.resolve("tsconfig.json"))) {
        return current;
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
          .filter(TsProjectDiscovery::isSourceFile)
          .filter(path -> !isUnderExcludedDirectory(root, path))
          .forEach(out::add);
    } catch (IOException | UncheckedIOException | SecurityException ex) {
      // A tree that cannot be walked yields what was found so far. The
      // frontend says how many files the compiler got, so a partial tree is
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
    if (isDeclarationFile(text)) {
      return false;
    }
    int dot = text.lastIndexOf('.');
    if (dot < 0 || dot == text.length() - 1) {
      return false;
    }
    return EXTENSIONS.contains(text.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  /**
   * Whether a file name is a declaration file.
   *
   * <p>A {@code .d.ts} file declares the shape of other modules. It holds no executable body, so
   * there is nothing in it to find a finding in.
   *
   * @param name the file's name
   * @return true when the file is a declaration file
   */
  public static boolean isDeclarationFile(String name) {
    if (name == null) {
      return false;
    }
    String lower = name.toLowerCase(Locale.ROOT);
    return lower.endsWith(".d.ts") || lower.endsWith(".d.mts") || lower.endsWith(".d.cts");
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
