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
package org.plumb.dfa.resolve;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.plumb.dfa.engine.BuildOutputFilter;

/**
 * Source-path resolver. The user designates a project root (a single directory that contains every
 * source file the analysis may need to look up) and the resolver builds a {@code package → file}
 * map by walking the root and indexing every {@code .java} file by its {@code package} declaration.
 *
 * <p>Conceptually this is the same as {@code javac -sourcepath}: the resolver only sees files the
 * user pointed at, never relies on the JDK classpath, and never treats test-support code (or any
 * other helper package) as project-specific. If a reference fails to resolve, the resolver returns
 * {@code null} and downstream phases treat the reference as opaque (no hardcoded substitution).
 */
public final class SourcePathResolver {

  /** Fully-qualified class name → source file. */
  private final Map<String, Path> classToFile = new LinkedHashMap<>();

  /** package → set of source files in that package. */
  private final Map<String, Set<Path>> packageToFiles = new LinkedHashMap<>();

  private final Path projectRoot;
  private final BuildOutputFilter buildFilter;

  /**
   * The most {@code .java} files {@link #index()} will index.
   *
   * <p>This is a safety budget, not a policy. The resolver reads every file it indexes, so a root
   * that happens to contain a large tree -- a temporary directory, a home directory, a filesystem
   * root -- turns indexing into an unbounded walk that reads and regex-scans tens of thousands of
   * files. The budget makes that impossible: the walk stops, and the resolver reports that it
   * stopped.
   *
   * <p>Over the budget is not an error. The resolver indexes what it reached and sets {@link
   * #indexTruncated()}; a caller that needs completeness can react to that, and a caller that does
   * not gets a partial index rather than a hang.
   */
  public static final int MAX_INDEXED_FILES = 5_000;

  /** Whether {@link #index()} stopped early because the root held more files than the budget. */
  private boolean indexTruncated;

  private static final Pattern PACKAGE_PATTERN = Pattern.compile("\\bpackage\\s+([\\w.$]+)\\s*;");
  private static final Pattern CLASS_PATTERN =
      Pattern.compile(
          "\\b(?:public\\s+|final\\s+|abstract\\s+)?(?:class|interface|enum)\\s+([\\w$]+)");

  public SourcePathResolver(Path projectRoot) {
    this(projectRoot, new BuildOutputFilter());
  }

  public SourcePathResolver(Path projectRoot, BuildOutputFilter buildFilter) {
    this.projectRoot = projectRoot;
    this.buildFilter = buildFilter == null ? new BuildOutputFilter() : buildFilter;
  }

  public Path projectRoot() {
    return projectRoot;
  }

  /**
   * Walk the project root once and index every {@code .java} file by package + class name. The walk
   * is O(N) in the number of files and uses {@link Files#walk} for a one-pass scan. The walk
   * delegates the build-output decision to the injected {@link BuildOutputFilter} so the same
   * indexer works for Maven, Gradle, Ant, Eclipse, IntelliJ, and any other build tool that drops
   * copies of source files into a known output directory.
   */
  public void index() throws IOException {
    if (projectRoot == null || !Files.isDirectory(projectRoot)) {
      return;
    }
    indexTruncated = false;
    int indexed = 0;
    try (var stream = Files.walk(projectRoot)) {
      var it = stream.iterator();
      while (it.hasNext()) {
        Path p = it.next();
        if (!Files.isRegularFile(p)) continue;
        if (!p.toString().toLowerCase(Locale.ROOT).endsWith(".java")) continue;
        if (buildFilter.isBuildOutput(p)) continue;
        if (indexed >= MAX_INDEXED_FILES) {
          // The budget is exhausted. Stop walking -- the remaining tree is
          // unbounded as far as the caller is concerned -- and say so.
          indexTruncated = true;
          break;
        }
        indexOne(p);
        indexed++;
      }
    }
  }

  /**
   * Whether {@link #index()} stopped early because the root held more files than {@link
   * #MAX_INDEXED_FILES}.
   *
   * <p>{@code true} means the index is PARTIAL: some classes in the tree are not resolvable because
   * they were never indexed. A caller that treats the index as complete when this is {@code true}
   * will silently lose cross-file resolutions.
   */
  public boolean indexTruncated() {
    return indexTruncated;
  }

  private void indexOne(Path p) {
    String content;
    try {
      content = Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      return;
    }
    String pkg = readPackage(content);
    if (pkg == null) return;
    String fqcnPrefix = pkg + ".";
    for (String className : readClassNames(content)) {
      String fqcn = fqcnPrefix + className;
      // First wins. Two
      // files declaring the
      // same FQCN is a
      // duplicate class
      // definition, not our
      // problem to solve.
      classToFile.putIfAbsent(fqcn, p);
    }
    packageToFiles.computeIfAbsent(pkg, k -> new LinkedHashSet<>()).add(p);
  }

  private static String readPackage(String src) {
    Matcher m = PACKAGE_PATTERN.matcher(src);
    return m.find() ? m.group(1) : null;
  }

  private static java.util.Set<String> readClassNames(String src) {
    java.util.Set<String> out = new LinkedHashSet<>();
    Matcher m = CLASS_PATTERN.matcher(src);
    while (m.find()) {
      out.add(m.group(1));
    }
    return out;
  }

  /**
   * Resolve a fully-qualified class name to a source file, or {@code null} if the class isn't in
   * the indexed project.
   */
  public Path resolveClass(String fqcn) {
    if (fqcn == null || fqcn.isEmpty()) return null;
    return classToFile.get(fqcn);
  }

  /**
   * Resolve a {@code package.ClassName} reference (the way Java imports read) to a source file. The
   * {@code fqcn} argument must be a fully-qualified name with the package prefix.
   */
  public Path resolveByFqcn(String fqcn) {
    return resolveClass(fqcn);
  }

  public Set<Path> filesInPackage(String pkg) {
    return packageToFiles.getOrDefault(pkg, Set.of());
  }

  public int indexedClassCount() {
    return classToFile.size();
  }

  public int indexedFileCount() {
    return packageToFiles.values().stream().mapToInt(Set::size).sum();
  }

  /**
   * Every {@code .java} file this resolver indexed.
   *
   * <p>The index already knows these paths, so a consumer that needs to read them -- {@link
   * StaticFieldResolver} parses the {@code public static final} initialisers out of them -- should
   * use this list rather than walking the tree again. A second walk reads every file twice and,
   * worse, is not covered by {@link #MAX_INDEXED_FILES}: the budget would apply to the first walk
   * while the second one ran unbounded.
   *
   * @return the indexed files, in index order
   */
  public java.util.List<Path> indexedFiles() {
    java.util.List<Path> out = new java.util.ArrayList<>();
    for (java.util.Set<Path> files : packageToFiles.values()) {
      out.addAll(files);
    }
    return out;
  }
}
