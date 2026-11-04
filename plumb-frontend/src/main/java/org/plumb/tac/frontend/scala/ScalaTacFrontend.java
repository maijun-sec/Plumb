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
package org.plumb.tac.frontend.scala;

import dotty.tools.dotc.Compiler;
import dotty.tools.dotc.Driver;
import dotty.tools.dotc.core.Contexts.Context;
import dotty.tools.dotc.reporting.Reporter;
import dotty.tools.io.AbstractFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;

/**
 * The Scala 3 frontend: Scala source text to {@link TacFunction}s.
 *
 * <p>It is the Scala sibling of {@link org.plumb.tac.frontend.java.JavaTacFrontend} and
 * {@link org.plumb.tac.frontend.kotlin.KotlinTacFrontend}, and it exists for the same
 * reason: to keep every language's path into the IR structurally identical, so a
 * difference in behaviour between them is a difference in the <i>frontend</i> and not an
 * accident of wiring.
 *
 * <h2>How it works</h2>
 *
 * <p>The frontend runs the Scala 3 compiler over the file and lowers the typed tree into
 * TAC. The lowering happens inside a real compiler plugin phase -- see {@link
 * PlumbScalaPlugin} and {@link ScalaTacPhase} -- so the trees the translator sees are
 * fully typed, and the plugin is installed through the same mechanism dotc uses for a
 * plugin loaded with {@code -Xplugin}.
 *
 * <h2>What the Scala frontend contributes to the IR</h2>
 *
 * <p>Two things the Java frontend cannot give, and which the analysis actually uses:
 *
 * <ul>
 *   <li><b>Synthetic methods.</b> A case class's {@code copy}, {@code apply}, {@code
 *       unapply} and {@code _N} accessors are compiler-generated, and dotc says so
 *       exactly ({@code Flags.Synthetic}). The bytecode path can only guess from the
 *       name.
 *   <li><b>Nothing else.</b> Scala has no non-null type system, so unlike the Kotlin
 *       frontend this one adds no nullability information: every reference stays
 *       nullable, exactly as it would for Java.
 * </ul>
 *
 * <h2>Totality</h2>
 *
 * <p>A frontend must not throw on malformed input. Everything here is wrapped so that a
 * broken file yields a {@link ParseResult#problems()} entry and whatever could be parsed,
 * never an exception out of {@link #parse}. A Scala file that does not compile produces
 * the compiler's own diagnostics as problems.
 *
 * <h2>Statelessness</h2>
 *
 * <p>The frontend holds no state between calls. Each parse creates its own compiler
 * context, plugin and phase, and discards them afterwards, so two frontends may run
 * concurrently without interfering.
 */
public final class ScalaTacFrontend implements TacFrontend {

  /**
   * The problems of the CURRENT parse, in the IR's structured form.
   *
   * <p>These travel with the IR. A source-less consumer can then see WHICH construct the
   * frontend could not lower, at WHICH line -- instead of only learning that "something went
   * wrong somewhere".
   */
  private final List<org.plumb.tac.ir.TacProgram.FunctionProblem> structuredProblems =
      new ArrayList<>();

  @Override
  public SourceLanguage language() {
    return SourceLanguage.SCALA;
  }

  @Override
  public Set<String> fileExtensions() {
    return Set.of("scala");
  }

  @Override
  public IrPrecision defaultPrecision() {
    // Every Scala construct this frontend lowers keeps its meaning: a `match` becomes a
    // branch chain, and the chain says everything the `match` said. Only a construct
    // that could NOT be lowered downgrades a function's precision to SOURCE_LOWERED,
    // and that is decided per function by the translator.
    return IrPrecision.SOURCE_FULL;
  }

  /**
   * Parses a Scala source without a compile context.
   *
   * <p>A convenience for a caller that has no session context: the context's only role
   * for the frontend is to supply the classpath, and a context-free parse resolves
   * against the Scala library alone.
   *
   * @param source the source text
   * @param path the file's path, used for error messages
   * @return the functions the file declares, plus any problems
   */
  public ParseResult parse(String source, Path path) {
    return parse(source, path, null);
  }

  @Override
  public ParseResult parse(String source, Path path, CompileContext ctx) {
    if (source == null) {
      return ParseResult.empty();
    }
    structuredProblems.clear();
    List<String> problems = new ArrayList<>();
    try {
      return parseWithCompiler(source, path, ctx, problems);
    } catch (RuntimeException | LinkageError ex) {
      // A frontend must be TOTAL. A malformed file reports a problem and yields
      // whatever could be parsed; an exception escaping here would abort the whole
      // scan for one bad file.
      String message =
          "parse failure in "
              + path
              + ": "
              + ex.getClass().getSimpleName()
              + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
      problems.add(message);
      structuredProblems.add(
          new org.plumb.tac.ir.TacProgram.FunctionProblem(
              path == null ? "<file>" : path.toString(),
              "PARSE_PROBLEM",
              message,
              ex.getClass().getSimpleName(),
              -1,
              -1));
      return new ParseResult(List.of(), problems, defaultPrecision());
    }
  }

  @Override
  public StructuredParseResult parseStructured(String source, Path path, CompileContext ctx) {
    ParseResult plain = parse(source, path, ctx);
    return new StructuredParseResult(
        plain.functions(), List.copyOf(structuredProblems), plain.precision());
  }

  /**
   * The body of {@link #parse}: run the compiler and collect the result.
   *
   * @param source the source text
   * @param path the file's path
   * @param ctx the session-scoped compile context, or {@code null}
   * @param problems the list the found problems are added to
   * @return the parse result
   */
  private ParseResult parseWithCompiler(
      String source, Path path, CompileContext ctx, List<String> problems) {
    Path workDir;
    Path sourceFile;
    Path outputDir;
    try {
      workDir = Files.createTempDirectory("plumb-scala-frontend");
      // The staged file goes under its PACKAGE directory, because that is where a
      // compiler expects to find a class of that package. The staged Scala file and
      // the staged Java sources then share one package-shaped tree, and a Java class
      // is found the same way it would be in a real source tree.
      String packagePath = packagePathOf(source);
      Path packageDir = packagePath == null ? workDir : workDir.resolve(packagePath);
      Files.createDirectories(packageDir);
      sourceFile = packageDir.resolve(fileName(path));
      outputDir = Files.createTempDirectory("plumb-scala-out");
      Files.write(sourceFile, source.getBytes(StandardCharsets.UTF_8));
      // The JAVA sources, staged under their own package directories. Without them
      // the compiler reports "Not found: JavaSink" for a Java class that exists only
      // as source, the Scala file does not compile, and the frontend contributes
      // NOTHING to the program.
      stageJavaSources(ctx, workDir);
    } catch (IOException ex) {
      problems.add("could not stage " + path + " for compilation: " + ex.getMessage());
      return new ParseResult(List.of(), problems, defaultPrecision());
    }

    try {
      // The plugin and its phase are created fresh for every parse, so nothing
      // survives from one file to the next and two frontends never share state.
      ScalaTacPhase phase = new ScalaTacPhase();
      // The module the file belongs to, so the produced functions carry it. It is
      // what lets a single-module analysis report this module's findings and only
      // those, while the other modules are still read for symbol resolution.
      phase.setModule(ctx == null ? null : ctx.moduleOf(path));
      PlumbScalaPlugin plugin = new PlumbScalaPlugin(phase);
      Context rootContext = new PlumbScalaContext(plugin).initialCtx();

      String[] args = compilerArgs(ctx, sourceFile, outputDir, source, workDir);

      Driver driver = new Driver();
      scala.Option<scala.Tuple2<scala.collection.immutable.List<AbstractFile>, Context>> setup =
          driver.setup(args, rootContext);
      if (setup.isEmpty()) {
        problems.add("the Scala compiler rejected the arguments for " + path);
        return new ParseResult(List.of(), problems, defaultPrecision());
      }
      scala.Tuple2<scala.collection.immutable.List<AbstractFile>, Context> prepared = setup.get();

      Reporter reporter = driver.doCompile(new Compiler(), prepared._1(), prepared._2());
      collectDiagnostics(reporter, problems);

      List<TacFunction> functions = phase.functions();
      problems.addAll(phase.problems());
      // The STRUCTURED problems, which travel with the IR.
      structuredProblems.addAll(phase.structuredProblems());

      IrPrecision precision = defaultPrecision();
      for (TacFunction fn : functions) {
        if (fn.irPrecision() == IrPrecision.SOURCE_LOWERED) {
          precision = IrPrecision.SOURCE_LOWERED;
          break;
        }
      }
      return new ParseResult(functions, problems, precision);
    } finally {
      deleteRecursively(workDir);
      deleteRecursively(outputDir);
    }
  }

  /**
   * The command-line arguments the compiler is run with.
   *
   * <p>The classpath comes from the compile context. The Scala library itself is always
   * on it, because the compiler needs {@code scala.Predef} to type even the simplest
   * file.
   *
   * <p><b>Why {@code -sourcepath} is passed.</b> The classpath holds BINARIES. A Java class that
   * exists only as SOURCE -- which is the normal case for the code under analysis -- is not on
   * it, so the compiler reports {@code "Not found: JavaSink"} and the file fails to compile. The
   * frontend then contributes no function at all, and a cross-language call from Scala to Java is
   * not merely unresolved: the whole file is missing from the program.
   *
   * <p>The source path is where the compiler looks for sources it needs to compile. Passing the
   * module's source root makes a Java source visible to the Scala compiler, so the file compiles
   * and the call is resolved.
   *
   * @param ctx the session-scoped compile context, or {@code null}
   * @param sourceFile the file to compile
   * @param outputDir the directory the class files are written to
   * @return the compiler's arguments
   */
  private String[] compilerArgs(CompileContext ctx, Path sourceFile, Path outputDir) {
    return compilerArgs(ctx, sourceFile, outputDir, null, null);
  }

  /**
   * The command-line arguments the compiler is run with.
   *
   * @param ctx the session-scoped compile context, or {@code null}
   * @param sourceFile the file to compile
   * @param outputDir the directory the class files are written to
   * @param source the source text, used to find the declared package
   * @param workDir the staging directory, or {@code null}
   * @return the compiler's arguments
   */
  private String[] compilerArgs(
      CompileContext ctx, Path sourceFile, Path outputDir, String source, Path workDir) {
    List<String> classpath = new ArrayList<>();
    if (ctx != null) {
      for (Path entry : ctx.classpath()) {
        if (entry != null) {
          classpath.add(entry.toString());
        }
      }
    }
    for (Path entry : scalaLibraryClasspath()) {
      if (!classpath.contains(entry.toString())) {
        classpath.add(entry.toString());
      }
    }
    List<String> args = new ArrayList<>();
    args.add("-classpath");
    args.add(String.join(java.io.File.pathSeparator, classpath));
    // The SOURCE PATH. Without it a Java class that exists only as source is not
    // found, the file does not compile, and the frontend contributes nothing.
    String sourcePath = sourcePathOf(ctx, sourceFile, packagePathOf(source), workDir);
    if (sourcePath != null && !sourcePath.isEmpty()) {
      args.add("-sourcepath");
      args.add(sourcePath);
    }
    args.add("-d");
    args.add(outputDir.toString());
    args.add(sourceFile.toString());
    return args.toArray(new String[0]);
  }

  /**
   * Copies the Java sources of the project into the staging directory, each under its own
   * package directory.
   *
   * <p><b>Why they are COPIED and not just pointed at.</b> A compiler's source path resolves a
   * class of package {@code demo} as {@code <root>/demo/JavaSink.java}. So the source path
   * entry has to be the directory ABOVE the package directory, and the package directory has to
   * exist on disk with the source inside it.
   *
   * <p>That is only true of a conventional source tree. A scanner is often handed a flat
   * directory -- {@code JavaSink.java} and {@code Foo.scala} side by side, with the package
   * declared inside the files -- and there the package directory does not exist, so the lookup
   * misses and the compiler reports "Not found: JavaSink".
   *
   * <p>Copying the Java sources into a package-shaped tree makes the lookup succeed for BOTH
   * layouts: the staged tree is laid out the way the compiler expects, whatever the original
   * tree looked like. The package each source belongs to is read from the source's own
   * {@code package} declaration, because that -- not the file's directory -- is what the compiler
   * will look up.
   *
   * <p>Only the Java sources are copied. The Scala sources are not: each is compiled in its own
   * parse, and copying them would make the compiler see the same declaration twice.
   *
   * @param ctx the compile context
   * @param workDir the staging directory
   */
  private static void stageJavaSources(CompileContext ctx, Path workDir) {
    if (ctx == null || ctx.modules() == null) return;
    for (CompileContext.Module module : ctx.modules()) {
      Path root = module.sourceRoot();
      if (root == null || !Files.isDirectory(root)) continue;
      try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
        for (Path file : walk.filter(Files::isRegularFile).toList()) {
          String name = file.getFileName().toString();
          if (!name.endsWith(".java")) continue;
          String packagePath = packagePathOf(readTextQuietly(file));
          Path target =
              (packagePath == null ? workDir : workDir.resolve(packagePath)).resolve(name);
          try {
            Files.createDirectories(target.getParent());
            Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
          } catch (IOException | RuntimeException ex) {
            // A source that cannot be staged is a source the compiler will not see.
            // The compile then reports it as unresolved, which is the honest outcome.
          }
        }
      } catch (IOException | RuntimeException ex) {
        // The walk failed; the compile will report what it cannot resolve.
      }
    }
  }

  /**
   * Reads a file's text, or returns {@code null} when it cannot be read.
   *
   * @param file the file
   * @return the text, or {@code null}
   */
  private static String readTextQuietly(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException | RuntimeException ex) {
      return null;
    }
  }

  /**
   * The source path for the compiler: the staging directory, the module's source root, and the
   * file's own directory.
   *
   * <p><b>Why a compiler's source path is not a directory to look in.</b> It is a list of roots, and
   * a class in package {@code demo} is looked up as {@code <root>/demo/JavaSink.java}. So the root
   * that finds a class is the one that sits ABOVE the package directory.
   *
   * <p>That is why the STAGING directory is the primary entry: the staged Scala file and the
   * staged Java sources are laid out in a package-shaped tree under it, so the lookup succeeds
   * whether the original tree was a conventional one ({@code src/main/java/demo/JavaSink.java}) or
   * a flat one ({@code JavaSink.java} next to {@code Foo.scala}, with the package declared inside
   * the file). See {@link #stageJavaSources}.
   *
   * <p><b>Why the roots' ANCESTORS are deliberately NOT added.</b> They would look harmless -- a
   * root above the package directory finds the class too -- but a source path entry is a place the
   * compiler SCANS. A scan directory under {@code %TEMP%} would therefore pick up every stray
   * {@code .java} file the machine happens to have there, and the compiler would try to compile
   * them. That is not a theoretical concern: the build's own formatter leaves scratch files in the
   * temp directory, and they are not valid Java.
   *
   * @param ctx the compile context
   * @param sourceFile the file being compiled
   * @param packagePath the package's directory path, relative to its root, or {@code null}
   * @param workDir the staging directory, or {@code null}
   * @return the source path, or {@code null} when nothing is known
   */
  private static String sourcePathOf(
      CompileContext ctx, Path sourceFile, String packagePath, Path workDir) {
    List<String> roots = new ArrayList<>();
    // The STAGING directory. The staged file and the staged Java sources live under
    // it in a package-shaped tree, so it is the root the compiler resolves a class
    // against: "<workDir>/demo/JavaSink.java".
    if (workDir != null) {
      String text = workDir.toString();
      if (!roots.contains(text)) {
        roots.add(text);
      }
    }
    if (ctx != null && ctx.modules() != null) {
      for (CompileContext.Module module : ctx.modules()) {
        Path root = module.sourceRoot();
        if (root == null) continue;
        String text = root.toString();
        if (!roots.contains(text)) {
          roots.add(text);
        }
      }
    }
    // The file's own directory: the module root may not contain it (a file handed
    // to the scanner directly, outside any module).
    if (sourceFile != null && sourceFile.getParent() != null) {
      String parent = sourceFile.getParent().toString();
      if (!roots.contains(parent)) {
        roots.add(parent);
      }
    }
    if (roots.isEmpty()) return null;
    return String.join(java.io.File.pathSeparator, roots);
  }

  /**
   * The Scala library's own jars, so the compiler can resolve {@code scala.Predef}.
   *
   * <p>These are the jars the frontend itself was loaded with. Reading them from the
   * class loader keeps the frontend and the compiler on the same Scala version, which
   * matters: a compiler that links against a different {@code scala3-library_3} than
   * the one it types against produces nonsense.
   *
   * @return the jars, empty when they cannot be located
   */
  private static List<Path> scalaLibraryClasspath() {
    List<Path> jars = new ArrayList<>();
    addJarOf(scala.Predef$.class, jars);
    addJarOf(scala.Option.class, jars);
    addJarOf(dotty.tools.dotc.core.Symbols.Symbol.class, jars);
    return jars;
  }

  /**
   * Adds the jar a class was loaded from to the list, if it is not already there.
   *
   * <p>The jar is found through the class's code source, which is where the JVM
   * actually loaded it from. That is more reliable than looking the artifact up by
   * name: the jar's file name does not have to match the artifact's id.
   *
   * @param type the class to look at
   * @param jars the list the jar is added to
   */
  private static void addJarOf(Class<?> type, List<Path> jars) {
    try {
      java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
      if (source == null) {
        return;
      }
      java.net.URL location = source.getLocation();
      if (location == null) {
        return;
      }
      // The URL is parsed as a URI rather than stripped of its "file:" prefix by
      // hand: a Windows URL reads "file:/D:/path", and removing the prefix by hand
      // would leave "/D:/path" -- a path with a stray leading slash before the
      // drive letter, which is not a valid Windows path.
      Path path = Path.of(location.toURI());
      if (Files.isRegularFile(path) && !jars.contains(path)) {
        jars.add(path);
      }
    } catch (RuntimeException | LinkageError | java.net.URISyntaxException ex) {
      // A jar that cannot be located is a missing classpath entry, not a crash.
    }
  }

  /**
   * Turns the compiler's diagnostics into problems.
   *
   * <p>The compiler reports errors and warnings separately. Only the errors are
   * problems -- a warning does not mean the file failed to parse.
   *
   * <p>The diagnostics also carry a SOURCE POSITION, so they become structured problems with a
   * line and a column. That is what lets a consumer point at the exact place the Scala file
   * failed to compile, instead of only learning that it did.
   *
   * @param reporter the compiler's reporter
   * @param problems the list the problems are added to
   */
  private void collectDiagnostics(Reporter reporter, List<String> problems) {
    if (reporter == null) {
      return;
    }
    try {
      scala.collection.Iterator<?> errors = reporter.allErrors().iterator();
      while (errors.hasNext()) {
        Object error = errors.next();
        String text = String.valueOf(error);
        problems.add(text);
        // The position is extracted from the diagnostic's TEXT, not from its
        // position object. The position object needs a compiler Context to
        // report a line (`SourcePosition.line(Context)`), and the reporter --
        // which is all that survives the compile -- has no Context. The
        // diagnostic's own rendering is "path:line: message", so the line is
        // already in the text.
        //
        // WHY THIS IS BETTER THAN THE OBJECT. It uses no compiler-internal API
        // beyond what the frontend already touches, so a dotc upgrade cannot
        // break it. And a position that cannot be read must not lose the
        // diagnostic, so the parse is defensive.
        int line = -1;
        int column = -1;
        int at = text.indexOf(':');
        if (at > 0 && at + 1 < text.length()) {
          int secondColon = text.indexOf(':', at + 1);
          String lineText =
              secondColon > 0 ? text.substring(at + 1, secondColon) : text.substring(at + 1);
          try {
            line = Integer.parseInt(lineText.trim());
          } catch (NumberFormatException ignored) {
            // Not a "path:line:" prefix after all. The diagnostic keeps its
            // text; it just has no structured position.
          }
        }
        structuredProblems.add(
            new org.plumb.tac.ir.TacProgram.FunctionProblem(
                "<scala>", "PARSE_PROBLEM", text, "CompilerError", line, column));
      }
    } catch (RuntimeException ex) {
      problems.add("could not read the Scala compiler's diagnostics: " + ex.getMessage());
    }
  }

  /** The file name to compile under. */
  private static String fileName(Path path) {
    if (path == null) {
      return "Source.scala";
    }
    Path name = path.getFileName();
    if (name == null) {
      return "Source.scala";
    }
    String text = name.toString();
    return text.endsWith(".scala") ? text : text + ".scala";
  }

  /**
   * The package the source declares, as a directory path.
   *
   * <p>Read from the source text rather than from the file's location, because the two do not
   * have to agree: a scanner is often handed a file whose package declaration does not match its
   * directory, and a compiler's source-path lookup is driven by the PACKAGE, not by the
   * directory.
   *
   * @param source the source text
   * @return the package path ({@code "demo/sub"} for {@code package demo.sub}), or {@code null}
   *     when the source declares no package
   */
  private static String packagePathOf(String source) {
    if (source == null) return null;
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)")
            .matcher(source);
    if (!m.find()) return null;
    return m.group(1).replace('.', java.io.File.separatorChar);
  }

  /**
   * Deletes a directory and everything under it.
   *
   * <p>Best effort: a leftover temporary directory must not fail a parse.
   *
   * @param dir the directory to delete
   */
  private static void deleteRecursively(Path dir) {
    if (dir == null || !Files.exists(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      List<Path> paths = new ArrayList<>();
      walk.forEach(paths::add);
      paths.sort(Comparator.reverseOrder());
      for (Path path : paths) {
        Files.deleteIfExists(path);
      }
    } catch (IOException | RuntimeException ex) {
      // Best effort.
    }
  }
}
