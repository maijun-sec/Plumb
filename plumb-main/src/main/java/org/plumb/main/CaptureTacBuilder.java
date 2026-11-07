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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.plumb.build.capture.CaptureEntry;
import org.plumb.parser.JavaModule;
import org.plumb.parser.JavaSourceParser;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.DefaultCompileContext;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.java.JavaTacFrontend;
import org.plumb.tac.ir.MergedProgram;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;

/**
 * Builds the TAC from the captures, and stores it.
 *
 * <p><b>Why the capture drives the build and not the other way round.</b> The usual path reads a
 * source tree and guesses the classpath from a build file. This path reads what the build actually
 * did: the classpath it resolved, the source path it searched, the level it compiled at. A type
 * that resolves against the real classpath resolves here too, and a type that does not resolve is a
 * fact about the project rather than about the guess.
 *
 * <p><b>Why the result is a store and not a cache.</b> The store is meant to be read somewhere
 * else. It carries the IR and nothing else, so the analysis that reads it needs neither the sources
 * nor the classpath nor a toolchain.
 */
public final class CaptureTacBuilder {

  private CaptureTacBuilder() {}

  /**
   * What the build produced.
   *
   * @param programs the programs, keyed by their source path
   * @param problems the problems the frontends reported, one per file that could not be read
   */
  public record BuildResult(Map<String, TacProgram> programs, List<String> problems) {

    public BuildResult {
      programs = programs == null ? Map.of() : Map.copyOf(programs);
      problems = problems == null ? List.of() : List.copyOf(problems);
    }
  }

  /**
   * Build the TAC from the captures.
   *
   * @param captures the captures
   * @return the programs and the problems
   */
  public static BuildResult build(List<CaptureEntry> captures) {
    Map<String, TacProgram> programs = new LinkedHashMap<>();
    List<String> problems = new ArrayList<>();
    if (captures == null || captures.isEmpty()) {
      return new BuildResult(programs, problems);
    }

    List<Path> classpath = CaptureToTacBridge.classpathOf(captures);
    List<Path> sourceRoots = CaptureToTacBridge.sourceRootsOf(captures);
    String languageLevel = CaptureToTacBridge.languageLevelOf(captures);

    // The compile context: one for the whole build, because symbol resolution and the
    // cross-language symbol table have to be shared or the cross-file edges are lost.
    List<CompileContext.Module> modules = new ArrayList<>();
    for (Path root : sourceRoots) {
      modules.add(
          new CompileContext.Module(
              root.getFileName() == null ? "src" : root.getFileName().toString(),
              root,
              List.of(),
              classpath,
              List.of(),
              languageLevel));
    }
    if (modules.isEmpty()) {
      modules.add(
          new CompileContext.Module(
              "src", Path.of("."), List.of(), classpath, List.of(), languageLevel));
    }

    try (DefaultCompileContext context = new DefaultCompileContext(modules, classpath)) {
      JavaSourceParser parser = buildParser(sourceRoots, classpath, languageLevel);
      TacFrontend javaFrontend = new JavaTacFrontend(parser);

      for (CaptureEntry entry : captures) {
        if (entry == null) {
          continue;
        }
        for (Path file : CaptureToTacBridge.existingSourceFiles(entry)) {
          String key = file.toAbsolutePath().normalize().toString();
          if (programs.containsKey(key)) {
            // Two captures of the same file are the same file. The second one
            // would produce the same program, and storing it twice would make
            // the store's size a lie.
            continue;
          }
          try {
            TacProgram program = translate(file, javaFrontend, context);
            if (program != null) {
              programs.put(key, program);
            }
          } catch (RuntimeException e) {
            problems.add(file + ": " + e.getMessage());
          }
        }
      }
    }
    return new BuildResult(programs, problems);
  }

  /**
   * Translate one file.
   *
   * <p>Every language goes through the frontend SPI: the Java frontend lowers straight to TAC as
   * well, so there is one path rather than two, and the path a non-Java language takes is the one
   * the scanner takes.
   *
   * @param file the file
   * @param javaFrontend the Java frontend
   * @param context the compile context
   * @return the program, or {@code null} when the frontend could not read the file
   */
  private static TacProgram translate(Path file, TacFrontend javaFrontend, CompileContext context) {
    String source;
    try {
      source = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
    TacFrontend frontend = frontendFor(file, javaFrontend, context);
    if (frontend == null) {
      return null;
    }
    TacFrontend.ParseResult parsed = frontend.parse(source, file, context);
    if (parsed == null || parsed.functions().isEmpty()) {
      return null;
    }
    TacProgram program = new TacProgram(file, "");
    for (TacFunction fn : parsed.functions()) {
      program.addFunction(fn);
    }
    for (String problem : parsed.problems()) {
      program.addProgramProblem(
          new TacProgram.FunctionProblem(
              file.getFileName().toString(), "FRONTEND", problem, null, -1, -1));
    }
    return program;
  }

  /**
   * The frontend for a file.
   *
   * @param file the file
   * @param javaFrontend the Java frontend
   * @param context the compile context
   * @return the frontend, or {@code null} when the file's language has none
   */
  private static TacFrontend frontendFor(
      Path file, TacFrontend javaFrontend, CompileContext context) {
    SourceLanguage language = FrontendRegistry.languageOf(file);
    if (language == SourceLanguage.JAVA) {
      return javaFrontend;
    }
    return switch (language) {
      case KOTLIN -> new org.plumb.tac.frontend.kotlin.KotlinTacFrontend(context);
      case SCALA -> new org.plumb.tac.frontend.scala.ScalaTacFrontend();
      case CSHARP -> new org.plumb.tac.frontend.csharp.CSharpTacFrontend();
      default -> null;
    };
  }

  /**
   * The parser the Java frontend uses.
   *
   * @param sourceRoots the source roots
   * @param classpath the classpath
   * @param languageLevel the language level
   * @return the parser
   */
  private static JavaSourceParser buildParser(
      List<Path> sourceRoots, List<Path> classpath, String languageLevel) {
    if (sourceRoots.isEmpty() && classpath.isEmpty()) {
      return new JavaSourceParser();
    }
    List<JavaModule> modules = new ArrayList<>();
    for (Path root : sourceRoots) {
      modules.add(
          new JavaModule(
              root.getFileName() == null ? "src" : root.getFileName().toString(),
              root,
              List.of(),
              classpath,
              List.of(),
              JavaModule.Scope.COMPILE,
              languageLevel,
              null));
    }
    if (modules.isEmpty()) {
      modules.add(
          new JavaModule(
              "src",
              Path.of("."),
              List.of(),
              classpath,
              List.of(),
              JavaModule.Scope.COMPILE,
              languageLevel,
              null));
    }
    return new JavaSourceParser(modules);
  }

  /**
   * Store the built programs.
   *
   * @param store the store
   * @param result the build result
   * @param manifest the manifest entries to record alongside the programs
   * @throws IOException if the programs cannot be stored
   */
  public static void store(
      org.plumb.tac.io.SqliteTacStore store, BuildResult result, Map<String, String> manifest)
      throws IOException {
    for (Map.Entry<String, TacProgram> entry : result.programs().entrySet()) {
      store.put(entry.getKey(), entry.getValue());
    }
    if (manifest != null) {
      for (Map.Entry<String, String> entry : manifest.entrySet()) {
        store.putManifest(entry.getKey(), entry.getValue());
      }
    }
    // The merged program is stored too, so a consumer can read the store with
    // one read instead of merging the sources itself.
    if (!result.programs().isEmpty()) {
      store.putMerged(new MergedProgram(new ArrayList<>(result.programs().values())));
    }
  }
}
