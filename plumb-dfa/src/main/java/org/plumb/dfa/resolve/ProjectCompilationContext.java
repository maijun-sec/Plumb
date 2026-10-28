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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.plumb.dfa.engine.BuildOutputFilter;

/**
 * Java project-root analysis context.
 *
 * <p>This is the public entry point for "scan a sub-tree of a Java project, but resolve cross-file
 * references in the whole project". The shape mirrors what {@code javac} does: the user tells the
 * compiler (1) where the project root is -- the single directory that contains every package the
 * analysis may need -- and (2) what sub-tree of that root to actually compile. The root is the
 * search space; the sub-tree is the work list. The package declaration of every {@code .java} file
 * under the root is the indexing key, and the directory layout follows the package name, so an
 * {@code import a.b.C;} statement in a file under the sub-tree can always be resolved by looking
 * for {@code <root>/a/b/C.java}.
 *
 * <p>The context bundles the two pre-existing resolvers -- the {@link SourcePathResolver} indexes
 * the whole root and is used to look up cross-file references; the {@link StaticFieldResolver}
 * extracts {@code public static final} literals from the same root and is used to fold constant
 * field accesses at the IR level. The class itself also exposes the work-list of files inside the
 * sub-tree so the engine knows what to actually feed into the DFA pipeline.
 *
 * <p>Nothing in this class is hardcoded for any test suite. The same shape works for Juliet, for a
 * Maven multi-module project, for a single {@code src/} tree in any IDE-generated project, and for
 * any other directory layout that respects the package-mirrors-directory convention.
 */
public final class ProjectCompilationContext {

  private final Path projectRoot;
  private final Path sourceRoot;
  private final SourcePathResolver sourcePath;
  private final StaticFieldResolver staticFields;
  private final BuildOutputFilter buildFilter;

  /**
   * Create a new context. Both arguments must be non-null and the {@code sourceRoot} must be a
   * directory that lives inside {@code projectRoot} -- this mirrors javac's rule that the source
   * path is a search space, not a list of disjoint roots.
   *
   * @throws IllegalArgumentException if the inputs do not satisfy the containment rule.
   */
  public ProjectCompilationContext(Path projectRoot, Path sourceRoot) {
    this(projectRoot, sourceRoot, new BuildOutputFilter());
  }

  public ProjectCompilationContext(
      Path projectRoot, Path sourceRoot, BuildOutputFilter buildFilter) {
    if (projectRoot == null) {
      throw new IllegalArgumentException("projectRoot must not be null");
    }
    if (sourceRoot == null) {
      throw new IllegalArgumentException("sourceRoot must not be null");
    }
    this.projectRoot = projectRoot.toAbsolutePath().normalize();
    this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
    if (!this.sourceRoot.startsWith(this.projectRoot)) {
      throw new IllegalArgumentException(
          "sourceRoot ("
              + this.sourceRoot
              + ") is not inside projectRoot ("
              + this.projectRoot
              + ")");
    }
    this.buildFilter = buildFilter;
    this.sourcePath = new SourcePathResolver(this.projectRoot, this.buildFilter);
    this.staticFields = new StaticFieldResolver(this.sourcePath);
  }

  public Path projectRoot() {
    return projectRoot;
  }

  public Path sourceRoot() {
    return sourceRoot;
  }

  public SourcePathResolver sourcePath() {
    return sourcePath;
  }

  public StaticFieldResolver staticFields() {
    return staticFields;
  }

  /**
   * Walk the project root + the sub-tree and build the in-memory indices (package → file map and
   * static-field map). Must be called before {@link #sourcePath()}, {@link #staticFields()}, or
   * {@link #filesToAnalyze()}.
   */
  public void index() throws IOException {
    sourcePath.index();
    staticFields.index();
  }

  /**
   * The work-list: every {@code .java} file inside {@link #sourceRoot()} (excluding build output)
   * that the DFA engine should analyse. The files outside {@code sourceRoot} but inside the project
   * root are reachable through {@link #sourcePath()}; they are not analysed themselves but their
   * definitions are available to resolve imports and field accesses from inside the work-list.
   */
  public List<Path> filesToAnalyze() throws IOException {
    List<Path> out = new ArrayList<>();
    if (!Files.isDirectory(sourceRoot)) {
      return out;
    }
    try (Stream<Path> stream = Files.walk(sourceRoot)) {
      var it = stream.iterator();
      while (it.hasNext()) {
        Path p = it.next();
        if (!Files.isRegularFile(p)) continue;
        if (!p.toString().toLowerCase(Locale.ROOT).endsWith(".java")) continue;
        if (buildFilter.isBuildOutput(p)) continue;
        out.add(p);
      }
    }
    return out;
  }

  /**
   * Convenience: count the {@code .java} files inside {@link #sourceRoot()} without keeping a list.
   * Useful for tests and progress reporting.
   */
  public int countFilesToAnalyze() throws IOException {
    return filesToAnalyze().size();
  }

  /** Convenience: count the classes indexed by {@link #sourcePath()}. */
  public int indexedClassCount() {
    return sourcePath.indexedClassCount();
  }

  /** Convenience: count the static fields indexed by {@link #staticFields()}. */
  public int staticFieldCount() {
    return staticFields.size();
  }

  /**
   * Resolve a fully-qualified class name (the form used by Java {@code import} statements and by
   * generated TAC {@code CALL} operands) to a source file in the project root. Returns {@code null}
   * if the class is not in the project (e.g. it is part of {@code java.lang} or another JDK
   * library).
   */
  public Path resolveClass(String fqcn) {
    return sourcePath.resolveClass(fqcn);
  }

  /**
   * Look up the value of a {@code public static final} field by its declaring class (FQCN) and
   * field name. Returns {@code null} if the field is not in the project or is not a constant that
   * the resolver can fold.
   */
  public StaticFieldResolver.LiteralValue lookupStatic(String fqcn, String field) {
    return staticFields.lookup(fqcn, field);
  }

  @Override
  public String toString() {
    return "ProjectCompilationContext{projectRoot="
        + projectRoot
        + ", sourceRoot="
        + sourceRoot
        + ", classes="
        + indexedClassCount()
        + ", staticFields="
        + staticFieldCount()
        + "}";
  }
}
