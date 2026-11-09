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
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.model.SymbolReference;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * {@link JavaLanguageLevel#DEFAULT} (Java 21, the newest level JavaParser names explicitly).
 * Records / sealed types / pattern matching are all enabled. Callers that need a different level /
 * encoding should use the appropriate constructor.
 *
 * <p><b>Java 8 through Java 25 are all supported.</b> See {@link JavaLanguageLevel#resolve(String)}
 * for the mapping from a build description's level string to a parser level.
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

  /**
   * The default parser: the default language level, UTF-8, and a JDK-only type solver.
   *
   * <p>The level is {@link JavaLanguageLevel#DEFAULT} (Java 21) rather than a hard-coded older
   * level, so that a caller who does not care about the level still gets one that parses everything
   * a modern JDK accepts.
   */
  public JavaSourceParser() {
    this(
        JavaLanguageLevel.DEFAULT,
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
   * JavaLanguageLevel#DEFAULT} (Java 21). The source encoding follows the same rule.
   *
   * <p>Java 8 through Java 25 are all accepted; see {@link JavaLanguageLevel#resolve(String)} for
   * the mapping.
   *
   * <p>An empty or null {@code modules} list is allowed; the resulting parser behaves identically
   * to the JDK-only default constructor.
   */
  public JavaSourceParser(ParserConfiguration.LanguageLevel level, List<JavaModule> modules) {
    this(level, deriveEncoding(modules), buildTypeSolverFor(modules));
  }

  /**
   * Convenience overload that derives the language level from the modules.
   *
   * <p>This is the constructor that makes the build description's language level actually take
   * effect: the first module that declares one wins. Before this was wired up, the level was
   * carried all the way from the build description into {@link JavaModule#languageLevel()} and then
   * ignored -- the parser always ran at {@code JAVA_17}, so a project targeting Java 21 failed to
   * parse with "switch patterns not supported".
   */
  public JavaSourceParser(List<JavaModule> modules) {
    this(resolveLanguageLevel(modules), deriveEncoding(modules), buildTypeSolverFor(modules));
  }

  /**
   * The language level implied by the given modules.
   *
   * <p>The first module that declares a level wins. An unrecognised level falls back to the default
   * and is logged, rather than failing the whole scan -- a typo in the build description should not
   * stop an analysis, but it should be visible.
   */
  static ParserConfiguration.LanguageLevel resolveLanguageLevel(List<JavaModule> modules) {
    if (modules == null) return JavaLanguageLevel.DEFAULT;
    for (JavaModule m : modules) {
      if (m == null) continue;
      String declared = m.languageLevel();
      if (declared == null || declared.isBlank()) continue;
      if (!JavaLanguageLevel.isKnown(declared)) {
        LOG.warn(
            "Unrecognised Java language level '{}' on module '{}'; falling back to {}",
            declared,
            m.name(),
            JavaLanguageLevel.DEFAULT);
      }
      return JavaLanguageLevel.resolve(declared);
    }
    return JavaLanguageLevel.DEFAULT;
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
    // The classpath entries, split by what they are.
    //
    // An ARCHIVE is read by JarTypeSolver. A DIRECTORY is not an archive and
    // cannot be opened as one -- and a multi-module build puts its sibling
    // modules on the classpath as directories ("core/target/classes"), so the
    // directory is the normal case in a multi-module project, not the corner
    // case.
    List<Path> archives = new ArrayList<>();
    List<Path> directories = new ArrayList<>();
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
      // (3) every classpath entry, read by what it is.
      for (Path cp : m.classpath()) {
        if (cp == null || !Files.exists(cp)) continue;
        Path canon = canonical(cp);
        if (!seenCp.add(canon)) continue;
        if (Files.isDirectory(cp)) {
          directories.add(cp);
        } else {
          archives.add(cp);
        }
      }
    }
    for (Path archive : archives) {
      try {
        combined.add(new JarTypeSolver(archive));
      } catch (IOException ex) {
        LOG.warn("JarTypeSolver({}) failed: {}", archive, ex.getMessage());
      } catch (RuntimeException ex) {
        LOG.warn("JarTypeSolver({}) failed: {}", archive, ex.getMessage());
      }
    }
    // (4) the class directories, through a class loader.
    //
    // A class directory is a place where a class is found BY NAME, and that is
    // what a class loader does. The loader is given the whole classpath and
    // not just the directories, because a class in a directory refers to the
    // classes in the archives beside it.
    //
    // The loader is added LAST, so the source solvers keep the precedence
    // they have always had: a type that is in the project's own source is
    // answered from that source, and the compiled copy is only consulted when
    // the source is not there.
    if (!directories.isEmpty()) {
      TypeSolver classDirectorySolver = classDirectorySolver(directories, archives);
      if (classDirectorySolver != null) {
        combined.add(classDirectorySolver);
      }
    }
    return combined;
  }

  /**
   * The solver that reads the class directories on the classpath.
   *
   * <p>The loader is built over the whole classpath, and every lookup it cannot answer is answered
   * with "not found" rather than with an exception: a class directory can hold a class whose own
   * dependencies are missing, and that is a fact about the classpath, not a reason to fail the
   * scan.
   *
   * @param directories the class directories
   * @param archives the archives beside them
   * @return the solver, or {@code null} when none could be built
   */
  private static TypeSolver classDirectorySolver(List<Path> directories, List<Path> archives) {
    List<URL> urls = new ArrayList<>();
    for (Path entry : directories) {
      URL url = toUrl(entry);
      if (url != null) urls.add(url);
    }
    for (Path archive : archives) {
      URL url = toUrl(archive);
      if (url != null) urls.add(url);
    }
    if (urls.isEmpty()) {
      return null;
    }
    try {
      URLClassLoader loader =
          new URLClassLoader(urls.toArray(new URL[0]), JavaSourceParser.class.getClassLoader());
      return new GuardedClassLoaderTypeSolver(loader);
    } catch (RuntimeException ex) {
      LOG.warn("the class directories could not be read: {}", ex.getMessage());
      return null;
    }
  }

  private static URL toUrl(Path path) {
    try {
      return path.toUri().toURL();
    } catch (MalformedURLException ex) {
      LOG.warn("the classpath entry {} has no usable URL", path);
      return null;
    }
  }

  /**
   * A class loader solver that answers "not found" instead of failing.
   *
   * <p>A class directory is allowed to hold a class whose dependencies are not on the classpath,
   * and loading such a class fails with a linkage error rather than with "not found". The failure
   * says something about the classpath, and it must not take the scan down with it.
   */
  private static final class GuardedClassLoaderTypeSolver extends ClassLoaderTypeSolver {

    GuardedClassLoaderTypeSolver(ClassLoader loader) {
      super(loader);
    }

    @Override
    public SymbolReference<ResolvedReferenceTypeDeclaration> tryToSolveType(String name) {
      try {
        return super.tryToSolveType(name);
      } catch (RuntimeException | LinkageError ex) {
        return SymbolReference.unsolved();
      }
    }
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
