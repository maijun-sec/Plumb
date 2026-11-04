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
package org.plumb.tac.frontend.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.plumb.parser.JavaModule;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The Java frontend: Java source text to {@link TacFunction}s.
 *
 * <p>This is the reference implementation of {@link TacFrontend}. It exists to prove the contract
 * is implementable -- and, more usefully, to make the Java path and the Kotlin and Scala paths
 * structurally identical, so that a difference in behaviour between them is a difference in the
 * <i>frontend</i> and not an accident of wiring.
 *
 * <h2>What the Java frontend contributes to the IR</h2>
 *
 * <p>Two things a bytecode frontend cannot give, and which the analysis actually uses:
 *
 * <ul>
 *   <li><b>Nullability.</b> Java has no non-null type system, so every reference type is nullable
 *       and nothing is gained here. (Kotlin's frontend does gain it -- that is where {@link
 *       org.plumb.tac.ir.TacType#isNullable()} earns its keep.)
 *   <li><b>Synthetic methods.</b> A Java {@code record}'s accessors and canonical constructor are
 *       compiler-generated, and the frontend marks them as such. The bytecode path can only guess
 *       from the name.
 * </ul>
 *
 * <h2>Precision</h2>
 *
 * <p>{@link IrPrecision#SOURCE_FULL}: Java's constructs all lower into the TAC without losing
 * information. A switch expression becomes a branch chain, but the branch chain says everything the
 * switch said -- no information is lost, only structure. So the precision is {@code SOURCE_FULL},
 * not {@code SOURCE_LOWERED}.
 */
public final class JavaTacFrontend implements TacFrontend {

  /**
   * The parser used for the session.
   *
   * <p>Built once from the compile context, because the type solver is expensive to construct and
   * is the thing that makes cross-file resolution work.
   */
  private final JavaSourceParser parser;

  /**
   * Builds a frontend over an existing parser.
   *
   * <p>Used by the CLI, which already has a parser configured from the build description.
   */
  public JavaTacFrontend(JavaSourceParser parser) {
    this.parser = parser;
  }

  /**
   * Builds a frontend from a compile context.
   *
   * <p>The parser's language level comes from the first module that declares one; the classpath
   * comes from every module.
   */
  public JavaTacFrontend(CompileContext ctx) {
    this(buildParser(ctx));
  }

  private static JavaSourceParser buildParser(CompileContext ctx) {
    if (ctx == null) return new JavaSourceParser();
    List<JavaModule> modules = new ArrayList<>();
    for (CompileContext.Module m : ctx.modules()) {
      if (m == null) continue;
      modules.add(
          new JavaModule(
              m.name(),
              m.sourceRoot(),
              m.sourceFiles(),
              m.classpath(),
              m.dependsOn(),
              JavaModule.Scope.COMPILE,
              m.languageLevel(),
              null));
    }
    return new JavaSourceParser(modules);
  }

  @Override
  public SourceLanguage language() {
    return SourceLanguage.JAVA;
  }

  @Override
  public Set<String> fileExtensions() {
    return Set.of("java");
  }

  @Override
  public IrPrecision defaultPrecision() {
    // Java's constructs all lower into the TAC without losing information. A switch
    // expression becomes a branch chain, but the branch chain says everything the
    // switch said.
    return IrPrecision.SOURCE_FULL;
  }

  @Override
  public ParseResult parse(String source, Path path, CompileContext ctx) {
    if (source == null) {
      return ParseResult.empty();
    }
    try {
      ParsedSource parsed = parser.parseFromString(path, source);
      List<String> problems = new ArrayList<>();
      for (var p : parsed.problems()) {
        problems.add(p.getMessage());
      }
      TacTranslator translator = new TacTranslator(parsed);
      translator.setModule(ctx == null ? null : ctx.moduleOf(path));
      TacProgram program = translator.translate();
      List<TacFunction> functions = new ArrayList<>(program.functions());
      return new ParseResult(functions, problems, defaultPrecision());
    } catch (RuntimeException ex) {
      // A frontend must be TOTAL: a malformed file reports a problem and yields whatever
      // could be parsed. An exception escaping here would abort the whole scan for one
      // bad file.
      List<String> problems = new ArrayList<>();
      problems.add("parse failure in " + path + ": " + ex.getMessage());
      return new ParseResult(List.of(), problems, defaultPrecision());
    }
  }

  /**
   * Parse with the IR's STRUCTURED problems.
   *
   * <p>The Java translator already records its gaps as {@link TacProgram.FunctionProblem}s -- the
   * parse problems, and the constructs it could not lower -- so this method simply hands them over.
   * That is what makes the problems travel with the IR: a consumer that has only the IR can see
   * WHICH construct was not lowered, in WHICH function, at WHICH line.
   */
  @Override
  public StructuredParseResult parseStructured(String source, Path path, CompileContext ctx) {
    if (source == null) {
      return StructuredParseResult.empty();
    }
    try {
      ParsedSource parsed = parser.parseFromString(path, source);
      TacTranslator translator = new TacTranslator(parsed);
      translator.setModule(ctx == null ? null : ctx.moduleOf(path));
      TacProgram program = translator.translate();
      List<TacFunction> functions = new ArrayList<>(program.functions());
      List<String> messages = new ArrayList<>();
      for (var p : parsed.problems()) {
        messages.add(p.getMessage());
      }
      for (TacProgram.FunctionProblem p : program.programProblems()) {
        messages.add(p.toString());
      }
      // The precision is the lowest precision any function carries: one degraded function
      // makes the FILE's precision degraded, because the file's finding set is only as
      // trustworthy as its least trustworthy function.
      IrPrecision precision = defaultPrecision();
      for (TacFunction fn : functions) {
        if (fn.irPrecision() == IrPrecision.SOURCE_LOWERED) {
          precision = IrPrecision.SOURCE_LOWERED;
          break;
        }
      }
      return new StructuredParseResult(
          functions, List.copyOf(program.programProblems()), precision);
    } catch (RuntimeException ex) {
      String message = "parse failure in " + path + ": " + ex.getMessage();
      return new StructuredParseResult(
          List.of(),
          List.of(
              new TacProgram.FunctionProblem(
                  path == null ? "<file>" : path.toString(),
                  "PARSE_PROBLEM",
                  message,
                  ex.getClass().getSimpleName(),
                  -1,
                  -1)),
          defaultPrecision());
    }
  }
}
