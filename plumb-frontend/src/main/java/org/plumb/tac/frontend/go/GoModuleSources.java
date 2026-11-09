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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the source directory of a Go module's dependencies.
 *
 * <h2>Why the source directory has to be looked up at all</h2>
 *
 * <p>A dependency is normally resolved from its <b>export data</b> -- the compiler's own record of
 * the package's exported API. But the export data is written into the build's <b>temporary</b>
 * directory, and the build deletes that directory when it ends. So by the time the analysis reads
 * the capture, the export data is usually gone, and the dependency has to be resolved from its
 * source instead.
 *
 * <h2>Why the module cache is not enough</h2>
 *
 * <p>The toolchain's module cache holds the dependencies that were <b>downloaded</b>. A dependency
 * that was <b>replaced</b> -- {@code replace other.example/mod =&gt; ../other-repo} -- is not in the
 * cache at all: it is a directory on disk, often in another repository. And a dependency that
 * belongs to another module of a <b>workspace</b> ({@code go.work}) is in the workspace, not in the
 * cache either.
 *
 * <p>Neither of those is reachable through the cache lookup, so without this class a project that
 * depends on a replaced or a workspace-local module has its dependencies' types unknown, and every
 * call into them is unresolved. That is a missing finding, not a missing precision.
 *
 * <h2>What is read</h2>
 *
 * <ul>
 *   <li>the module's own {@code go.mod} -- its module path, and its {@code replace} directives
 *       whose target is a directory;
 *   <li>the {@code go.work} above the module -- the workspace's {@code use} directives, each of
 *       which names a module by the module path its own {@code go.mod} declares.
 * </ul>
 *
 * <p>Only the <b>longest</b> matching module path wins, so a module named {@code example.com/m}
 * does not swallow the import {@code example.com/m2/pkg}.
 */
public final class GoModuleSources {

  private GoModuleSources() {}

  /**
   * The modules that live on disk, keyed by their module path.
   *
   * <p>The map is what the analysis needs when a dependency is neither in the module cache nor in
   * the capture: a dependency that was <b>replaced</b>, or one that belongs to another module of
   * a <b>workspace</b>. The module path is the prefix its import paths start with, so the map
   * answers "where does this import path live" for every package of the module.
   *
   * <p>A replaced module's own build description is read as well, so that a replacement of a
   * replacement is found too.
   *
   * @param moduleRoot the module root
   * @return the module paths and their directories
   */
  public static Map<String, Path> modulesOf(Path moduleRoot) {
    Map<String, Path> out = new LinkedHashMap<>();
    if (moduleRoot == null) {
      return out;
    }
    Set<Path> visited = new LinkedHashSet<>();
    collect(moduleRoot, out, visited);

    Path workspace = workspaceOf(moduleRoot);
    if (workspace != null) {
      for (Map.Entry<String, Path> entry : workspaceModules(workspace).entrySet()) {
        out.putIfAbsent(entry.getKey(), entry.getValue());
        collect(entry.getValue(), out, visited);
      }
      Path base = workspace.getParent();
      if (base != null) {
        for (Map.Entry<String, Path> entry : replacementsOf(workspace, base).entrySet()) {
          out.putIfAbsent(entry.getKey(), entry.getValue());
          collect(entry.getValue(), out, visited);
        }
      }
    }
    return out;
  }

  /**
   * The directory of an import path, when one of the modules provides it.
   *
   * @param modules the modules, keyed by module path
   * @param importPath the import path
   * @return the directory, or null when no module provides it
   */
  public static Path directoryFor(Map<String, Path> modules, String importPath) {
    if (modules == null || importPath == null || importPath.isEmpty()) {
      return null;
    }
    String best = null;
    for (String candidate : modules.keySet()) {
      if (candidate == null || candidate.isEmpty()) {
        continue;
      }
      if (!importPath.equals(candidate) && !importPath.startsWith(candidate + "/")) {
        continue;
      }
      if (best == null || candidate.length() > best.length()) {
        best = candidate;
      }
    }
    if (best == null) {
      return null;
    }
    Path base = modules.get(best);
    String rest = importPath.equals(best) ? "" : importPath.substring(best.length() + 1);
    Path directory = rest.isEmpty() ? base : base.resolve(rest.replace('/', java.io.File.separatorChar));
    // A directory that is not there cannot be read, and naming it would only
    // replace one missing import with another.
    return Files.isDirectory(directory) ? directory : null;
  }

  /**
   * A module and the modules it replaces, collected into the map.
   *
   * @param moduleRoot the module root
   * @param out the map to fill
   * @param visited the directories that were already read
   */
  private static void collect(Path moduleRoot, Map<String, Path> out, Set<Path> visited) {
    if (moduleRoot == null) {
      return;
    }
    Path normalized = moduleRoot.toAbsolutePath().normalize();
    if (!visited.add(normalized)) {
      return;
    }
    String modulePath = GoProjectDiscovery.modulePathOf(moduleRoot);
    if (modulePath != null && !modulePath.isEmpty()) {
      out.putIfAbsent(modulePath, normalized);
    }
    Map<String, Path> replacements = replacementsOf(normalized.resolve("go.mod"), normalized);
    for (Map.Entry<String, Path> entry : replacements.entrySet()) {
      out.putIfAbsent(entry.getKey(), entry.getValue());
    }
    for (Path replaced : replacements.values()) {
      collect(replaced, out, visited);
    }
  }

  /**
   * The {@code replace} directives of a module file whose target is a directory.
   *
   * <p>A replacement that names another module and a version -- {@code replace x =&gt; y v1.0.0} --
   * is NOT a directory: that dependency is in the module cache, and the toolchain's own lookup
   * finds it.
   *
   * @param file the module file, or the workspace file
   * @param base the directory the file's relative paths are resolved against
   * @return the replaced module paths and their directories
   */
  private static Map<String, Path> replacementsOf(Path file, Path base) {
    Map<String, Path> out = new LinkedHashMap<>();
    if (base == null) {
      return out;
    }
    for (String statement : statementsOf(file)) {
      if (!statement.startsWith("replace ")) {
        continue;
      }
      String body = statement.substring("replace ".length()).trim();
      int arrow = body.indexOf("=>");
      if (arrow < 0) {
        continue;
      }
      String[] left = body.substring(0, arrow).trim().split("\\s+");
      String[] right = body.substring(arrow + 2).trim().split("\\s+");
      if (left.length == 0 || right.length != 1 || right[0].isEmpty()) {
        continue;
      }
      Path directory = localDirectory(base, right[0]);
      if (directory == null) {
        continue;
      }
      out.put(left[0], directory);
    }
    return out;
  }

  /**
   * The modules of a workspace, keyed by their module path.
   *
   * @param workspace the workspace file
   * @return the module paths and their directories
   */
  private static Map<String, Path> workspaceModules(Path workspace) {
    Map<String, Path> out = new LinkedHashMap<>();
    Path base = workspace.getParent();
    if (base == null) {
      return out;
    }
    for (String statement : statementsOf(workspace)) {
      if (!statement.startsWith("use ")) {
        continue;
      }
      String body = statement.substring("use ".length()).replace("(", " ").replace(")", " ").trim();
      for (String use : body.split("\\s+")) {
        if (use.isEmpty()) {
          continue;
        }
        Path directory = localDirectory(base, use);
        if (directory == null) {
          continue;
        }
        String modulePath = GoProjectDiscovery.modulePathOf(directory);
        if (modulePath == null || modulePath.isEmpty()) {
          continue;
        }
        out.put(modulePath, directory);
      }
    }
    return out;
  }

  /**
   * The workspace file above a module, if there is one.
   *
   * @param moduleRoot the module root
   * @return the workspace file, or null
   */
  private static Path workspaceOf(Path moduleRoot) {
    Path current = moduleRoot.toAbsolutePath().normalize();
    while (current != null) {
      Path candidate = current.resolve("go.work");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      current = current.getParent();
    }
    return null;
  }

  /**
   * A path that names a directory on disk, resolved against a base directory.
   *
   * @param base the base directory
   * @param text the path as it was written
   * @return the directory, or null when it does not name one
   */
  private static Path localDirectory(Path base, String text) {
    if (text == null || text.isEmpty()) {
      return null;
    }
    Path resolved;
    try {
      Path candidate = Path.of(text);
      resolved = candidate.isAbsolute() ? candidate : base.resolve(candidate);
    } catch (RuntimeException ex) {
      return null;
    }
    resolved = resolved.toAbsolutePath().normalize();
    return Files.isDirectory(resolved) ? resolved : null;
  }

  /**
   * The statements of a module file, one per directive.
   *
   * <p>A block form -- {@code replace ( ... )} -- contributes one statement per line, because each
   * line of it is a directive of its own.
   *
   * @param file the file
   * @return the statements
   */
  static List<String> statementsOf(Path file) {
    List<String> out = new ArrayList<>();
    if (file == null || !Files.isRegularFile(file)) {
      return out;
    }
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException ex) {
      return out;
    }
    String pending = null;
    for (String raw : lines) {
      String line = stripComment(raw).trim();
      if (line.isEmpty()) {
        continue;
      }
      if (pending != null) {
        if (line.equals(")")) {
          pending = null;
          continue;
        }
        out.add(pending + " " + line);
        continue;
      }
      if (line.endsWith("(")) {
        pending = line.substring(0, line.length() - 1).trim();
        continue;
      }
      out.add(line);
    }
    return out;
  }

  /**
   * A line without its comment.
   *
   * @param line the line
   * @return the line up to its comment
   */
  private static String stripComment(String line) {
    int slash = line.indexOf("//");
    return slash < 0 ? line : line.substring(0, slash);
  }
}
