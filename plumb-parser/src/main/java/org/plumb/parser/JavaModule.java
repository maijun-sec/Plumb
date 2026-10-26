package org.plumb.parser;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A logical Java module: one root of source files (typically a Maven module, a Gradle subproject,
 * or a single-project source root) plus the classpath needed to resolve types declared inside that
 * source set.
 *
 * <p>The record is intentionally minimal. Plumb treats each {@code --source-root} / {@code
 * --module-root} as a flat {@code JavaModule}; the per-module classpath and the Maven/Gradle/Bazel
 * module auto-discovery are not implemented yet.
 *
 * <p><b>What this record is NOT for.</b> The {@code classpath} list is consumed <em>only</em> by
 * {@link JavaSourceParser}'s {@code TypeSolver}. It is <b>never</b> routed into the TAC translation
 * pipeline or into {@code BytecodeAnalyzer}. That boundary is the cardinal rule of the design (per
 * user constraint: "不希望分析 第三方 jar 的内容").
 *
 * <p><b>Discovery helpers.</b> The static factories walk the {@code sourceRoot} for {@code .java}
 * files eagerly so that downstream code can do a sanity check (e.g. log "0 .java files under
 * source-root X" when the path is wrong) without re-walking the filesystem.
 */
public record JavaModule(
    String name,
    Path sourceRoot,
    List<Path> sourceFiles,
    List<Path> classpath,
    List<String> moduleDeps,
    Scope scope,
    String languageLevel,
    Charset sourceEncoding) {

  /** Module scope — main, test, runtime, etc. Default is {@link Scope#COMPILE}. */
  public enum Scope {
    COMPILE,
    TEST,
    RUNTIME,
    PROVIDED,
    SYSTEM;

    public static Scope fromString(String s) {
      if (s == null || s.isBlank()) return COMPILE;
      String lower = s.trim().toLowerCase();
      switch (lower) {
        case "test":
        case "testimplementation":
        case "testcompile":
          return TEST;
        case "runtime":
        case "runtimeonly":
          return RUNTIME;
        case "provided":
        case "compileonly":
          return PROVIDED;
        case "system":
          return SYSTEM;
        case "compile":
        case "implementation":
        case "api":
        case "classpath":
        default:
          return COMPILE;
      }
    }
  }

  public JavaModule {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(sourceRoot, "sourceRoot");
    Objects.requireNonNull(sourceFiles, "sourceFiles");
    Objects.requireNonNull(classpath, "classpath");
    Objects.requireNonNull(moduleDeps, "moduleDeps");
    Objects.requireNonNull(scope, "scope");
    sourceFiles = List.copyOf(sourceFiles);
    classpath = List.copyOf(classpath);
    moduleDeps = List.copyOf(moduleDeps);
  }

  /**
   * Build a single-root module: walks {@code sourceRoot} for {@code .java} files and attaches the
   * supplied {@code classpath} (third-party jar / class-folder list).
   *
   * <p>The classpath is shared across all modules in ; the per-module classpath is + work (paired
   * with the Maven/Gradle/Bazel module auto-discovery).
   */
  public static JavaModule singleRoot(String name, Path sourceRoot, List<Path> classpath) {
    return singleRoot(name, sourceRoot, classpath, Scope.COMPILE, null, null);
  }

  /** Same as {@link #singleRoot(String, Path, List)} with a chosen {@link Scope}. */
  public static JavaModule singleRoot(
      String name, Path sourceRoot, List<Path> classpath, Scope scope) {
    return singleRoot(name, sourceRoot, classpath, scope, null, null);
  }

  /**
   * Same as {@link #singleRoot(String, Path, List)} with {@link Scope} + language level + encoding.
   */
  public static JavaModule singleRoot(
      String name,
      Path sourceRoot,
      List<Path> classpath,
      Scope scope,
      String languageLevel,
      Charset sourceEncoding) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(sourceRoot, "sourceRoot");
    Objects.requireNonNull(classpath, "classpath");
    List<Path> sources = walkJavaFiles(sourceRoot);
    return new JavaModule(
        name,
        sourceRoot,
        sources,
        classpath,
        List.of(),
        scope == null ? Scope.COMPILE : scope,
        languageLevel,
        sourceEncoding);
  }

  /**
   * Walk {@code root} recursively for {@code .java} files. Hidden directories (names starting with
   * {@code .}) are skipped so that {@code .git}, {@code .idea}, {@code .gradle} etc. do not
   * contribute to the source set.
   *
   * <p><b>Symlinks are not followed.</b> Uses {@link Files#walk(Path, int)} which defaults to
   * no-follow -- defence against infinite recursion when a checkout contains self-referential
   * symlinks.
   *
   * <p>Depth is bounded to {@value #MAX_DEPTH} so even a malformed symlink chain cannot exhaust the
   * JVM stack.
   *
   * <p>I/O errors are propagated to the caller so a misconfigured CLI flag does not silently
   * produce an empty source set.
   */
  private static List<Path> walkJavaFiles(Path root) {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    List<Path> out = new ArrayList<>();
    try (var stream = Files.walk(root, MAX_DEPTH)) {
      for (Path p : (Iterable<Path>) stream::iterator) {
        if (!Files.isRegularFile(p)) continue;
        if (isHiddenSegment(p)) continue;
        String name = p.getFileName().toString();
        if (!name.endsWith(".java")) continue;
        out.add(p);
      }
    } catch (IOException e) {
      // Propagate the IO error so misconfigured roots don't silently produce empty
      // source sets. Caller is expected to log + skip the offending module.
      throw new RuntimeException("Failed to walk source root " + root + ": " + e.getMessage(), e);
    }
    return out;
  }

  private static final int MAX_DEPTH = 100;

  private static boolean isHiddenSegment(Path path) {
    for (Path part : path) {
      String n = part.toString();
      if (n.length() > 1 && n.charAt(0) == '.' && !n.equals("..")) return true;
    }
    return false;
  }
}
