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
package org.plumb.parser;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin wrapper around JavaParser. Single entry point used by the rest of the pipeline; one {@link
 * ParsedSource} per source file.
 *
 * <p>The parser is configured with UTF-8 source encoding by default and a language level pinned to
 * Java 17. Records / sealed types / pattern matching are all enabled. Callers that need a different
 * level / encoding should use the appropriate constructor.
 *
 * <p><b>Type and symbol resolution.</b> Pass a {@link TypeSolver} to the constructor (or call
 * {@link #withTypeSolver(TypeSolver)}) and the parser attaches a {@link JavaSymbolSolver} to the
 * {@link ParserConfiguration}. After parsing, the {@link ParsedSource} exposes {@link
 * ParsedSource#typeSolver()} and the symbol resolver is wired into the underlying {@link
 * CompilationUnit}, so {@code symbolResolver.resolveType(...)} and {@code node.resolve()} calls
 * work the way the JavaParser docs describe.
 *
 * <p>The default {@link TypeSolver} is a {@link CombinedTypeSolver} that wraps a single {@link
 * ReflectionTypeSolver} (JDK types only). Add a {@code JavaParserTypeSolver} for project-local type
 * resolution; the parser factory will combine the two.
 *
 * <p>Note: the {@code StaticJavaParser} facade in JavaParser 3.26 swallows the {@link ParseResult}
 * and just returns a {@code CompilationUnit}; to keep access to the {@link Problem} list (which we
 * want for diagnostics) we instantiate {@link JavaParser} directly.
 */
public final class JavaSourceParser {

  private static final Logger LOG = LoggerFactory.getLogger(JavaSourceParser.class);

  private final JavaParser parser;
  private final ParserConfiguration config;
  private final TypeSolver typeSolver;
  private final Charset sourceEncoding;

  public JavaSourceParser() {
    this(
        ParserConfiguration.LanguageLevel.JAVA_17,
        StandardCharsets.UTF_8,
        new CombinedTypeSolver(new ReflectionTypeSolver()));
  }

  public JavaSourceParser(ParserConfiguration.LanguageLevel level) {
    this(level, StandardCharsets.UTF_8, new CombinedTypeSolver(new ReflectionTypeSolver()));
  }

  public JavaSourceParser(ParserConfiguration.LanguageLevel level, TypeSolver typeSolver) {
    this(level, StandardCharsets.UTF_8, typeSolver);
  }

  public JavaSourceParser(
      ParserConfiguration.LanguageLevel level, Charset sourceEncoding, TypeSolver typeSolver) {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(sourceEncoding, "sourceEncoding");
    Objects.requireNonNull(typeSolver, "typeSolver");
    this.sourceEncoding = sourceEncoding;
    this.config =
        new ParserConfiguration()
            .setLanguageLevel(level)
            .setCharacterEncoding(sourceEncoding)
            .setAttributeComments(true);
    // Default ParserConfiguration in JavaParser 3.x
    // already preserves Comment nodes attached to AST
    // — comment-collection is on by default. We
    // don't need an explicit setter; the
    // CompilationUnit tree-walk done by
    // SuspiciousCommentCheck will see every
    // LineComment / BlockComment in the source.
    this.typeSolver = typeSolver;
    this.config.setSymbolResolver(new JavaSymbolSolver(this.typeSolver));
    this.parser = new JavaParser(config);
  }

  /**
   * Build a parser whose {@link TypeSolver} combines (1) the JDK reflection solver, (2) one {@link
   * JavaParserTypeSolver} per {@link JavaModule} so the module's own source files can be used to
   * resolve cross-module type references, and (3) a {@link JarTypeSolver} for every classpath jar /
   * class-folder the module declares.
   *
   * <p>This is the entry point the {@code plumb-main} CLI uses when the user supplies {@code
   * --source-root}, {@code --module-root} or {@code --classpath} on the command line (or the
   * equivalent YAML keys). Plumb treats every input as a flat module; the per-module classpath and
   * Maven/Gradle/Bazel auto-discovery are not implemented yet.
   *
   * <p><b>Cardinal rule.</b> The {@code classpath} is consumed by the {@link TypeSolver} only. It
   * is never routed into the TAC pipeline or the SootUp bytecode path -- third-party jars exist
   * solely so the user source can resolve "what does {@code com.example.Foo} look like".
   *
   * <p>The parser's language level is determined by the first non-null {@link
   * JavaModule#languageLevel} value; if all modules report null, falls back to {@link
   * ParserConfiguration.LanguageLevel#JAVA_17}. The source encoding follows the same rule.
   *
   * <p>An empty or null {@code modules} list is allowed; the resulting parser behaves identically
   * to the JDK-only default constructor.
   */
  public JavaSourceParser(ParserConfiguration.LanguageLevel level, List<JavaModule> modules) {
    this(level, deriveEncoding(modules), buildTypeSolverFor(modules));
  }

  /** Convenience overload that picks a default language level. */
  public JavaSourceParser(List<JavaModule> modules) {
    this(
        ParserConfiguration.LanguageLevel.JAVA_17,
        deriveEncoding(modules),
        buildTypeSolverFor(modules));
  }

  /**
   * Build the {@link TypeSolver} that backs the module-aware constructor. Public for unit tests so
   * we can inspect the underlying solver composition without going through JavaParser's full
   * pipeline.
   *
   * <p>Duplicate jar / class-folder entries (same canonical path) are deduplicated so the resulting
   * {@link CombinedTypeSolver} does not carry multiple solvers for the same jar -- this is both
   * faster and avoids the "two solvers disagree on the same type" pathology.
   */
  public static TypeSolver buildTypeSolverFor(List<JavaModule> modules) {
    CombinedTypeSolver combined = new CombinedTypeSolver();
    // (1) JDK types — always available.
    combined.add(new ReflectionTypeSolver());
    if (modules == null || modules.isEmpty()) {
      return combined;
    }
    // Dedup classpath entries by their normalised absolute path so a jar referenced
    // by N modules only opens its index once.
    Set<Path> seenCp = new HashSet<>();
    Set<Path> seenSrcRoots = new HashSet<>();
    for (JavaModule m : modules) {
      if (m == null) continue;
      // (2) the module's own source root as a
      // JavaParserTypeSolver — so that, e.g., a
      // reference to `package sibling.Foo` inside
      // `src/main/java/com/example/Bar.java` can
      // resolve to the `Foo.class` declaration in
      // the same module's source set.
      Path root = m.sourceRoot();
      if (root != null && Files.isDirectory(root)) {
        Path canon = canonical(root);
        if (seenSrcRoots.add(canon)) {
          try {
            combined.add(new JavaParserTypeSolver(root));
          } catch (RuntimeException ex) {
            LOG.warn("JavaParserTypeSolver({}) failed: {}", root, ex.getMessage());
          }
        }
      }
      // (3) every classpath entry as a JarTypeSolver.
      // JavaParser accepts a Path / File / directory
      // root, so this single API call covers both
      // "foo.jar" and "build/classes" (a class-folder).
      for (Path cp : m.classpath()) {
        if (cp == null || !Files.exists(cp)) continue;
        Path canon = canonical(cp);
        if (!seenCp.add(canon)) continue;
        try {
          combined.add(new JarTypeSolver(cp));
        } catch (IOException ex) {
          LOG.warn("JarTypeSolver({}) failed: {}", cp, ex.getMessage());
        } catch (RuntimeException ex) {
          LOG.warn("JarTypeSolver({}) failed: {}", cp, ex.getMessage());
        }
      }
    }
    return combined;
  }

  private static Path canonical(Path p) {
    try {
      return p.toRealPath();
    } catch (IOException e) {
      return p.toAbsolutePath().normalize();
    }
  }

  /** Pick the first non-null encoding from {@code modules}; fall back to UTF-8. */
  private static Charset deriveEncoding(List<JavaModule> modules) {
    if (modules == null) return StandardCharsets.UTF_8;
    for (JavaModule m : modules) {
      if (m != null && m.sourceEncoding() != null) return m.sourceEncoding();
    }
    return StandardCharsets.UTF_8;
  }

  /**
   * Return a new {@link JavaSourceParser} that uses {@code extra} in addition to the default
   * reflection type solver. The two are combined into a single {@link CombinedTypeSolver} so JDK
   * types still resolve alongside project-local types.
   */
  public JavaSourceParser withTypeSolver(TypeSolver extra) {
    Objects.requireNonNull(extra, "extra");
    CombinedTypeSolver combined = new CombinedTypeSolver();
    combined.add(new ReflectionTypeSolver());
    combined.add(extra);
    return new JavaSourceParser(config.getLanguageLevel(), sourceEncoding, combined);
  }

  /** The type solver this parser was built with. */
  public TypeSolver typeSolver() {
    return typeSolver;
  }

  /** The charset this parser was configured with. */
  public Charset sourceEncoding() {
    return sourceEncoding;
  }

  public ParsedSource parse(Path file) throws IOException {
    String src = Files.readString(file, sourceEncoding);
    return parseFromString(file, src);
  }

  public ParsedSource parseFromString(Path virtualPath, String source) {
    try {
      ParseResult<CompilationUnit> result = parser.parse(source);
      CompilationUnit unit = result.getResult().orElseGet(CompilationUnit::new);
      List<Problem> problems = result.getProblems();
      if (!problems.isEmpty()) {
        for (Problem p : problems) {
          LOG.debug("Parse problem in {}: {}", virtualPath, p.getMessage());
        }
      }
      // When symbol resolution is configured
      // (typeSolver non-null), attach the symbol resolver
      // to the CompilationUnit so per-node resolve() and
      // resolveType() calls hit the symbol solver. The
      // JavaSymbolSolver's {@code setParentNode} is
      // invoked transitively by the visitor when the
      // symbol resolver is wired to the parser; for
      // safety, we also stash the type solver on the
      // ParsedSource so callers can resolve types
      // explicitly when they need to.
      return new ParsedSource(virtualPath, source, unit, problems, typeSolver);
    } catch (RuntimeException e) {
      // JavaParser throws a parse-cancelled-style RuntimeException on
      // some syntax-error paths; we still want a ParsedSource (with
      // an empty unit) so downstream phases can record the problem.
      LOG.warn("Parse failure for {}: {}", virtualPath, e.getMessage());
      CompilationUnit empty = new CompilationUnit();
      return new ParsedSource(virtualPath, source, empty, List.of(), typeSolver);
    }
  }
}
