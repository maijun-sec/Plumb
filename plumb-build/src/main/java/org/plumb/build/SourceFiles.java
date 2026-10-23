package org.plumb.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * R-833 ? Shared utilities for the four adapters. Path walking and classpath dedup logic lives
 * here so each adapter only deals with build-system-specific parsing.
 */
public final class SourceFiles {

  private SourceFiles() {}

  /**
   * Walk {@code root} and collect every file whose name ends in {@code extensions}.
   *
   * <p><b>Symlinks are NOT followed.</b> The JDK's {@link Files#walk(Path, int)} overload we call
   * does not pass {@link java.nio.file.FileVisitOption#FOLLOW_LINKS}, which is exactly the
   * no-follow behaviour we want. This is defence against infinite recursion in monorepo checkouts
   * that contain self-referential symlinks (a common mistake when {@code node_modules} or {@code
   * .git} point back into the source tree).
   *
   * <p>Hidden directories (names starting with {@code .}) are skipped so {@code .git}, {@code
   * .idea}, {@code .gradle}, {@code .vscode} etc. do not contribute to the source set. This
   * matches what IDEs and Maven do.
   *
   * <p>The walk is bounded to {@value #MAX_DEPTH} levels so even a buggy symlink chain cannot
   * exhaust the JVM stack.
   *
   * <p>{@code root} itself is included if it matches.
   *
   * <p>This is a pure filesystem walk; the adapter is responsible for picking the right roots
   * (e.g. {@code src/main/java} for production, {@code src/test/java} for tests).
   *
   * @param root the directory to walk
   * @param extensions filename suffixes to accept (e.g. {@code ".java"}); may not be null
   * @return sorted list of matching regular files, possibly empty if {@code root} does not exist
   */
  public static List<Path> walk(Path root, List<String> extensions) {
    if (root == null || !Files.isDirectory(root)) {
      return List.of();
    }
    if (extensions == null || extensions.isEmpty()) {
      return List.of();
    }
    try (Stream<Path> stream = Files.walk(root, MAX_DEPTH)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(p -> !isHiddenSegment(p))
          .filter(
              p -> {
                String n = p.getFileName().toString();
                for (String ext : extensions) {
                  if (n.endsWith(ext)) return true;
                }
                return false;
              })
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new BuildAdapterException(
          "Failed to walk source root " + root + ": " + e.getMessage(), e);
    }
  }

  /** Maximum recursion depth applied to {@link #walk(Path, List)}. */
  private static final int MAX_DEPTH = 100;

  /**
   * Returns {@code true} when any segment of {@code path} starts with a dot. {@code .git}, {@code
   * .idea}, {@code .gradle}, etc. are skipped during source walks so they don't pollute the source
   * set.
   */
  private static boolean isHiddenSegment(Path path) {
    for (Path part : path) {
      String name = part.toString();
      if (name.length() > 1 && name.charAt(0) == '.' && !name.equals("..")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Split a classpath string (path-separated by the OS) into a list of {@link Path}s. Empty
   * segments are skipped.
   */
  public static List<Path> splitClasspath(String cp) {
    if (cp == null || cp.isBlank()) return List.of();
    String sep = java.io.File.pathSeparator;
    return java.util.Arrays.stream(cp.split(java.util.regex.Pattern.quote(sep)))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(Path::of)
        .toList();
  }
}