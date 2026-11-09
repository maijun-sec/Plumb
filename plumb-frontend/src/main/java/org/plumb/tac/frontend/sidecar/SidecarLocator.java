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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where a sidecar's executable lives.
 *
 * <h2>Why the lookup is a search and not a fixed path</h2>
 *
 * <p>The sidecar is a native binary, so it is built by its own toolchain rather than by the Java
 * build. That means it can sit in a build output directory, next to the JAR in a distribution, or
 * anywhere the user points at with an environment variable. The lookup therefore tries, in order:
 * an explicit override, the environment variable, the directory next to the running code, and the
 * build output under the source tree.
 *
 * <h2>Why EVERY ancestor is a candidate</h2>
 *
 * <p>The code's own location depends on who is asking: the frontend's tests run from {@code
 * plumb-frontend/target/test-classes}, so the nearest ancestor with a {@code pom.xml} is {@code
 * plumb-frontend}; but another module's tests run from its own directory, whose ancestors are not
 * the source tree at all. A lookup that stopped at the first {@code pom.xml} would find a module
 * directory and never reach the repository root.
 *
 * <p>So every ancestor is a candidate, and the caller's layout is tried under each of them. The
 * first hit wins, and the nearest match is the right one.
 */
public final class SidecarLocator {

  private SidecarLocator() {}

  /**
   * Where one sidecar lives.
   *
   * @param environmentVariable the environment variable that overrides the location
   * @param projectDirectoryName the sidecar's directory name, e.g. {@code csharp-sidecar}
   * @param buildOutputPaths the build output directories, relative to the sidecar's directory
   * @param executableName the executable's file name
   */
  public record Layout(
      String environmentVariable,
      String projectDirectoryName,
      List<String> buildOutputPaths,
      String executableName) {

    public Layout {
      buildOutputPaths =
          buildOutputPaths == null ? List.of() : List.copyOf(buildOutputPaths);
    }
  }

  /**
   * Find a sidecar's executable.
   *
   * @param override an explicit path, or null
   * @param layout where the sidecar lives
   * @param here the location of the calling code, or null
   * @return the executable, or null when none was found
   */
  public static Path locate(Path override, Layout layout, Path here) {
    List<Path> candidates = new ArrayList<>();
    if (override != null) {
      candidates.add(override);
    }
    String fromEnvironment = System.getenv(layout.environmentVariable());
    if (fromEnvironment != null && !fromEnvironment.isBlank()) {
      candidates.add(Path.of(fromEnvironment));
    }

    // Next to the JAR: the distribution ships the sidecar alongside it.
    Path location = here != null ? here : pathOfCallingClass();
    if (location != null) {
      Path directory = location.getParent();
      if (directory != null) {
        candidates.add(directory.resolve(layout.projectDirectoryName()).resolve(layout.executableName()));
        candidates.add(directory.resolve(layout.executableName()));
      }
    }

    // The source tree's build output.
    List<Path> roots = repositoryRootsOf(location);
    roots.addAll(repositoryRootsOf(workingDirectory()));
    for (Path base : roots) {
      candidates.addAll(buildOutputs(base, layout));
    }

    for (Path candidate : candidates) {
      if (candidate != null && Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * The build output directories under a repository root.
   *
   * <p>The sidecar may sit directly under the repository root, or under the module that owns it.
   *
   * @param base the repository root
   * @param layout where the sidecar lives
   * @return the candidates
   */
  private static List<Path> buildOutputs(Path base, Layout layout) {
    List<Path> out = new ArrayList<>();
    for (Path project : List.of(base.resolve(layout.projectDirectoryName()), base)) {
      for (String output : layout.buildOutputPaths()) {
        out.add(project.resolve(output).resolve(layout.executableName()));
      }
    }
    // The sidecar may live in a MODULE of the repository rather than at its
    // root: the C# and the Go sidecar are both under the frontend module. So
    // every module of the root is a candidate as well.
    //
    // This is what makes the lookup work from a packaged distribution, where
    // the code runs from a JAR in one module and the sidecar sits in another.
    for (Path module : modulesOf(base)) {
      Path project = module.resolve(layout.projectDirectoryName());
      for (String output : layout.buildOutputPaths()) {
        out.add(project.resolve(output).resolve(layout.executableName()));
      }
    }
    return out;
  }

  /**
   * The modules of a repository root.
   *
   * <p>A module is a directory that holds a build file. The build file is the marker because it is
   * what the build itself uses to decide what is a module.
   *
   * @param base the repository root
   * @return the module directories
   */
  private static List<Path> modulesOf(Path base) {
    List<Path> out = new ArrayList<>();
    if (base == null || !Files.isDirectory(base)) {
      return out;
    }
    try (Stream<Path> children = Files.list(base)) {
      children
          .filter(child -> Files.isRegularFile(child.resolve("pom.xml")))
          .forEach(out::add);
    } catch (IOException e) {
      // The directory cannot be read, so it contributes no candidates. The
      // lookup continues with the ones it already has.
    }
    return out;
  }

  /** The process's working directory, or null when it cannot be determined. */
  private static Path workingDirectory() {
    try {
      return Path.of("").toAbsolutePath().normalize();
    } catch (RuntimeException ex) {
      return null;
    }
  }

  /**
   * The candidate build roots, nearest first.
   *
   * @param here the location to walk up from
   * @return the candidate roots
   */
  private static List<Path> repositoryRootsOf(Path here) {
    List<Path> roots = new ArrayList<>();
    if (here == null) {
      return roots;
    }
    Path current = here.toAbsolutePath().normalize();
    // A FILE (a jar) is not a directory to walk from; start at its parent.
    if (!Files.isDirectory(current)) {
      current = current.getParent();
    }
    for (int i = 0; i < 8 && current != null; i++) {
      if (Files.isRegularFile(current.resolve("pom.xml"))) {
        roots.add(current);
      }
      current = current.getParent();
    }
    return roots;
  }

  /**
   * The location of the class that asked for the sidecar.
   *
   * @return the location, or null
   */
  private static Path pathOfCallingClass() {
    try {
      return Path.of(
          SidecarLocator.class
              .getProtectionDomain()
              .getCodeSource()
              .getLocation()
              .toURI());
    } catch (Exception ex) {
      return null;
    }
  }
}
