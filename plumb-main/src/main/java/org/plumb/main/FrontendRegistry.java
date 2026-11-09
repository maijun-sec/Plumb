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
package org.plumb.main;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.DefaultCompileContext;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;

/**
 * The map from a source file to the frontend that can read it.
 *
 * <p><b>WHY THIS EXISTS.</b> The Kotlin and Scala frontends were implemented, tested, and then
 * never reached by a real scan: {@code ScanRunner} filtered for {@code .java} and never constructed
 * a {@link TacFrontend}. A frontend that is not on the scan path is not a capability, it is a
 * prototype -- and, worse, it is indistinguishable from "the code is clean", because a frontend
 * that never runs produces zero findings and zero problems.
 *
 * <p>This registry is the single place that knows which frontend claims which extension. Adding a
 * language means adding a frontend and registering it here; nothing else in the pipeline changes,
 * because every frontend lowers to the same TAC.
 *
 * <p><b>THE JAVA PATH IS NOT SPECIAL-CASED HERE.</b> Java also has a frontend ({@code
 * JavaTacFrontend}), so the dispatch is uniform. What differs is that Java's frontend needs the
 * parser that the rest of the pipeline already builds (it carries the resolved TypeSolver), so the
 * caller supplies it.
 *
 * <p><b>THE FRONTENDS ARE LAZY.</b> The Kotlin frontend builds an IntelliJ application and the
 * Scala frontend builds a compiler context; neither is cheap, and a scan of pure Java code must not
 * pay for either. So a frontend is constructed the first time a file of its language is actually
 * encountered.
 */
final class FrontendRegistry implements AutoCloseable {

  /** The frontend for Java, supplied by the caller because it carries the resolved parser. */
  private final TacFrontend javaFrontend;

  /** The frontends for the other languages, built on first use. */
  private final Map<SourceLanguage, TacFrontend> built = new LinkedHashMap<>();

  /** The compile context shared by every frontend in this run. */
  private final CompileContext context;

  /** The source roots, used to give the Kotlin frontend a module structure. */
  private final List<Path> sourceRoots;

  /** The classpath, for type resolution. */
  private final List<Path> classpath;

  /**
   * The languages this run analyses. Empty means "every language".
   *
   * <p>The filter is a LOAD-TIME filter: a frontend whose language was not selected is never
   * constructed, so a Java-only scan does not pay for the Kotlin Analysis API's IntelliJ
   * application or for the Scala compiler context.
   */
  private final Set<SourceLanguage> enabledLanguages;

  /**
   * Builds a registry.
   *
   * @param javaFrontend the Java frontend, which carries the parser the rest of the pipeline uses
   * @param sourceRoots the source roots of the scanned project
   * @param classpath the classpath entries for type resolution
   */
  FrontendRegistry(TacFrontend javaFrontend, List<Path> sourceRoots, List<Path> classpath) {
    this(javaFrontend, sourceRoots, classpath, Set.of());
  }

  /**
   * Builds a registry that only serves the given languages.
   *
   * @param javaFrontend the Java frontend, which carries the parser the rest of the pipeline uses
   * @param sourceRoots the source roots of the scanned project
   * @param classpath the classpath entries for type resolution
   * @param enabledLanguages the languages to serve; empty means every language
   */
  FrontendRegistry(
      TacFrontend javaFrontend,
      List<Path> sourceRoots,
      List<Path> classpath,
      Set<SourceLanguage> enabledLanguages) {
    this.javaFrontend = javaFrontend;
    this.sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
    this.classpath = classpath == null ? List.of() : List.copyOf(classpath);
    this.enabledLanguages =
        enabledLanguages == null
            ? Set.of()
            : java.util.Collections.unmodifiableSet(enabledLanguages);
    this.context = newContext(this.sourceRoots, this.classpath);
  }

  /**
   * Whether this registry serves the given language.
   *
   * <p>An empty selection means every language.
   */
  boolean serves(SourceLanguage language) {
    if (enabledLanguages.isEmpty()) return true;
    return language != null && enabledLanguages.contains(language);
  }

  /**
   * The context every frontend shares.
   *
   * <p>One context per run, not one per file: symbol resolution and the cross-language symbol table
   * must be shared, or cross-file call edges are lost.
   */
  private static CompileContext newContext(List<Path> sourceRoots, List<Path> classpath) {
    List<CompileContext.Module> modules = new ArrayList<>();
    int idx = 0;
    for (Path root : sourceRoots) {
      modules.add(
          new CompileContext.Module(
              moduleId(root, idx++), root, List.of(), classpath, List.of(), null));
    }
    if (modules.isEmpty()) {
      modules.add(
          new CompileContext.Module("src", Path.of("."), List.of(), classpath, List.of(), null));
    }
    return new DefaultCompileContext(modules, classpath);
  }

  /**
   * The id of a module, derived from its source root's NAME.
   *
   * <p>The name is what a user can type on the command line: {@code --module service} for {@code
   * .../service}. A root whose name is not usable (the filesystem root, a drive letter) falls back
   * to a positional id, so the id is always something.
   *
   * <p>Two roots with the same name would collide, so the index is appended when it is needed: the
   * first root keeps the bare name, a later one with the same name becomes {@code name.2}.
   */
  private static String moduleId(Path root, int index) {
    String name =
        root == null ? null : root.getFileName() == null ? null : root.getFileName().toString();
    if (name == null || name.isBlank()) {
      return "module" + (index + 1);
    }
    return name.replaceAll("[^A-Za-z0-9._-]", "_");
  }

  /**
   * The language a file belongs to, by its extension.
   *
   * <p>Unknown extensions yield {@link SourceLanguage#UNKNOWN}: the caller decides what to do with
   * a file it cannot classify, and the default is to skip it rather than to guess.
   *
   * @param file the source file
   * @return the language, or UNKNOWN
   */
  static SourceLanguage languageOf(Path file) {
    if (file == null) {
      return SourceLanguage.UNKNOWN;
    }
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1) {
      return SourceLanguage.UNKNOWN;
    }
    return languageOfExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  /**
   * The language an extension belongs to.
   *
   * @param extension the extension, lower-case and without the dot
   * @return the language, or UNKNOWN
   */
  static SourceLanguage languageOfExtension(String extension) {
    if (extension == null) {
      return SourceLanguage.UNKNOWN;
    }
    switch (extension.toLowerCase(Locale.ROOT)) {
      case "java":
        return SourceLanguage.JAVA;
      case "kt":
      case "kts":
        return SourceLanguage.KOTLIN;
      case "scala":
      case "sc":
        return SourceLanguage.SCALA;
      case "cs":
        return SourceLanguage.CSHARP;
      case "go":
        return SourceLanguage.GO;
      default:
        return SourceLanguage.UNKNOWN;
    }
  }

  /**
   * Whether a file is a source file Plumb can read.
   *
   * <p>Used by the file discovery, so that a Kotlin or Scala file is not skipped before the
   * dispatcher ever sees it.
   */
  static Set<String> knownExtensions() {
    return Set.of("java", "kt", "kts", "scala", "sc", "cs", "go");
  }

  /**
   * Whether a file is a source file of a language Plumb has a frontend for.
   *
   * @param file the file
   * @return true when the extension names a known language
   */
  static boolean isSourceFile(Path file) {
    if (file == null) {
      return false;
    }
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1) {
      return false;
    }
    return knownExtensions().contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  /**
   * The frontend for a language, built on first use.
   *
   * @param language the language
   * @return the frontend, or {@code null} when the language has none
   */
  TacFrontend frontendFor(SourceLanguage language) {
    if (!serves(language)) return null;
    switch (language) {
      case JAVA:
        return javaFrontend;
      case KOTLIN:
        return built.computeIfAbsent(
            SourceLanguage.KOTLIN,
            l -> new org.plumb.tac.frontend.kotlin.KotlinTacFrontend(context));
      case SCALA:
        return built.computeIfAbsent(
            SourceLanguage.SCALA, l -> new org.plumb.tac.frontend.scala.ScalaTacFrontend());
      case CSHARP:
        // The C# frontend drives a .NET sidecar, so constructing it is not
        // free the way a pure-Java frontend is: it looks for the sidecar
        // executable. The frontend itself reports a missing toolchain as an
        // explicit problem, so constructing it and finding no toolchain is a
        // LOUD failure rather than a silent one.
        return built.computeIfAbsent(
            SourceLanguage.CSHARP, l -> new org.plumb.tac.frontend.csharp.CSharpTacFrontend());
      case GO:
        // The Go frontend drives a Go sidecar, so constructing it is not free
        // the way a pure-Java frontend is: it looks for the sidecar executable.
        // The frontend itself reports a missing toolchain as an explicit
        // problem, so constructing it and finding no toolchain is a LOUD
        // failure rather than a silent one.
        return built.computeIfAbsent(
            SourceLanguage.GO, l -> new org.plumb.tac.frontend.go.GoTacFrontend());
      default:
        return null;
    }
  }

  /**
   * Parses a source file with the frontend that owns its language.
   *
   * <p>The result carries the functions AND the structured problems. The problems matter: a
   * frontend that could not lower part of a file says so, and the finding set is then known to be a
   * lower bound rather than a complete answer. Silently dropping them would make "the frontend
   * cannot read this" look exactly like "there is nothing here".
   *
   * @param file the source file
   * @return the parse outcome
   */
  ParsedSourceFile parseFile(Path file) {
    SourceLanguage language = languageOf(file);
    TacFrontend frontend = frontendFor(language);
    if (frontend == null) {
      if (!serves(language)) {
        // The file's language was not selected for this run. That is a choice, not a
        // problem: the file is out of scope, and saying so keeps "not analysed" from looking
        // like "analysed and clean".
        return new ParsedSourceFile(
            List.of(),
            List.of(
                new TacProgram.FunctionProblem(
                    file.toString(),
                    "LANGUAGE_NOT_SELECTED",
                    "this file is "
                        + language.name().toLowerCase(Locale.ROOT)
                        + ", and this run analyses "
                        + describeSelection()
                        + "; the file was not analysed",
                    null,
                    -1,
                    -1)),
            language,
            null);
      }
      return new ParsedSourceFile(List.of(), List.of(), SourceLanguage.UNKNOWN, null);
    }
    String source;
    try {
      source = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException ex) {
      return new ParsedSourceFile(
          List.of(),
          List.of(
              new TacProgram.FunctionProblem(
                  file.toString(),
                  "PARSE_PROBLEM",
                  "could not read the source file: " + ex.getMessage(),
                  null,
                  -1,
                  -1)),
          language,
          null);
    }
    try {
      TacFrontend.StructuredParseResult result = frontend.parseStructured(source, file, context);
      return new ParsedSourceFile(
          result.functions(), result.problems(), language, result.precision());
    } catch (RuntimeException | LinkageError ex) {
      // A frontend must be total, but a compiler API can still fail in a way
      // its author did not anticipate. The scan continues; the file is
      // reported as unanalysed rather than silently omitted.
      return new ParsedSourceFile(
          List.of(),
          List.of(
              new TacProgram.FunctionProblem(
                  file.toString(),
                  "TRANSLATION_ERROR",
                  "the frontend failed on this file: " + ex,
                  null,
                  -1,
                  -1)),
          language,
          null);
    }
  }

  /** The shared compile context. */
  CompileContext context() {
    return context;
  }

  /** A short description of the selected languages, for the log and the problems. */
  String describeSelection() {
    if (enabledLanguages.isEmpty()) return "all languages";
    List<String> names = new ArrayList<>();
    for (SourceLanguage l : enabledLanguages) {
      names.add(l.name().toLowerCase(Locale.ROOT));
    }
    java.util.Collections.sort(names);
    return String.join(", ", names);
  }

  @Override
  public void close() {
    for (TacFrontend frontend : built.values()) {
      if (frontend instanceof AutoCloseable closeable) {
        try {
          closeable.close();
        } catch (Exception ex) {
          // Best effort: a frontend that cannot be closed must not fail the run.
        }
      }
    }
    context.close();
  }

  /**
   * The outcome of parsing one source file.
   *
   * @param functions the functions the file declares
   * @param problems what the frontend could not lower, with the construct and the position
   * @param language the file's language
   * @param precision the precision of the produced functions
   */
  record ParsedSourceFile(
      List<TacFunction> functions,
      List<TacProgram.FunctionProblem> problems,
      SourceLanguage language,
      org.plumb.tac.ir.IrPrecision precision) {}
}
