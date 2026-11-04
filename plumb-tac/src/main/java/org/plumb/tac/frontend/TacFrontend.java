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
package org.plumb.tac.frontend;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;

/**
 * The one and only extension point for adding a language to Plumb.
 *
 * <p>A frontend turns source text into {@link TacFunction}s. Everything downstream -- the DFA
 * engine, the taint checkers, the Z3 path solver, the call graph, the reporter -- is language
 * neutral and needs no change when a new frontend appears.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li><b>Stateless.</b> All per-session state lives in the {@link CompileContext} handed to
 *       {@link #parse}. Two frontends may run concurrently.
 *   <li><b>Total.</b> A frontend must not throw on malformed input. Report the problem through
 *       {@link ParseResult#problems()} and return whatever could be parsed.
 *   <li><b>Lossless about semantics.</b> A frontend may lower a language construct into a different
 *       TAC shape ({@code when} into a branch chain, {@code match} into a branch chain), but it
 *       must not silently drop behaviour. When it does lower a construct, it should say so through
 *       {@link ParseResult#precision()}.
 * </ul>
 *
 * <h2>What a frontend must NOT do</h2>
 *
 * <p>It must not add language-specific TAC opcodes. The TAC is a multi-language contract; a
 * frontend that needs a new shape (a branch chain, a call, a move) must express it with the
 * existing instruction kinds. {@code TacInstruction.Kind#NULL_CHECK} is the one opcode added for
 * multi-language work, and it is language-neutral by construction (it means "this value is proven
 * non-null", which every language can say).
 */
public interface TacFrontend {

  /** The language this frontend handles. */
  SourceLanguage language();

  /** The source file extensions this frontend accepts, lower-case and without the dot. */
  Set<String> fileExtensions();

  /**
   * The precision this frontend produces when nothing was lowered away.
   *
   * <p>A frontend that degrades some constructs reports {@link IrPrecision#SOURCE_LOWERED} for the
   * functions it degraded, and {@link IrPrecision#SOURCE_FULL} for the rest.
   */
  default IrPrecision defaultPrecision() {
    return IrPrecision.SOURCE_FULL;
  }

  /**
   * Whether this frontend needs a resolved classpath (for type resolution).
   *
   * <p>True for every frontend that resolves types against binaries: Java (the symbol solver needs
   * the JDK and the project's dependencies), Kotlin (the Analysis API needs library roots), and
   * Scala (the compiler needs the classpath).
   */
  default boolean requiresClasspath() {
    return true;
  }

  /**
   * Parse one source file into TAC functions.
   *
   * @param source the source text
   * @param path the file's path, used for error messages and source mapping
   * @param ctx the session-scoped compile context (classpath, symbol cache, module structure)
   * @return the functions declared in this file, plus any problems encountered
   */
  ParseResult parse(String source, Path path, CompileContext ctx);

  /**
   * The result of parsing one file.
   *
   * @param functions the functions the file declares; empty for a file with no declarations
   * @param problems human-readable descriptions of parse/resolution problems; empty when the file
   *     parsed cleanly. A non-empty list does NOT invalidate the functions -- partial results are
   *     the normal case for a file that references an unresolvable symbol.
   * @param precision the precision of the produced functions
   */
  record ParseResult(List<TacFunction> functions, List<String> problems, IrPrecision precision) {

    public ParseResult {
      functions = functions == null ? List.of() : List.copyOf(functions);
      problems = problems == null ? List.of() : List.copyOf(problems);
      precision = precision == null ? IrPrecision.SOURCE_FULL : precision;
    }

    /** A result with no functions and no problems. */
    public static ParseResult empty() {
      return new ParseResult(List.of(), List.of(), IrPrecision.SOURCE_FULL);
    }

    /** True when nothing went wrong. */
    public boolean ok() {
      return problems.isEmpty();
    }
  }

  /**
   * The result of parsing one file, with STRUCTURED problems.
   *
   * <p><b>Why the structured form exists.</b> A plain string says "something went wrong". A {@link
   * org.plumb.tac.ir.TacProgram.FunctionProblem} says WHICH construct went wrong, in WHICH
   * function, at WHICH line and column, and whether the cause was an unsupported construct or a
   * broken source.
   *
   * <p>That distinction is not cosmetic for a source-less consumer. A consumer that receives only
   * strings cannot answer "is this file incomplete because the frontend lacks a feature, or because
   * the source does not compile?" -- and those need different responses: one is a frontend backlog
   * item, the other is a code fix. It also cannot report WHERE the gap is, which is the first thing
   * anyone wants to know about a missing finding.
   *
   * <p>The structured form is what travels through the IR, so it has to be the SPI's native shape.
   * A frontend that only has strings can still be used: {@link #parse} is the required method and
   * the default implementation wraps the string-based one.
   */
  record StructuredParseResult(
      List<TacFunction> functions,
      List<org.plumb.tac.ir.TacProgram.FunctionProblem> problems,
      IrPrecision precision) {

    public StructuredParseResult {
      functions = functions == null ? List.of() : List.copyOf(functions);
      problems = problems == null ? List.of() : List.copyOf(problems);
      precision = precision == null ? IrPrecision.SOURCE_FULL : precision;
    }

    /** A result with no functions and no problems. */
    public static StructuredParseResult empty() {
      return new StructuredParseResult(List.of(), List.of(), IrPrecision.SOURCE_FULL);
    }

    /** True when nothing went wrong. */
    public boolean ok() {
      return problems.isEmpty();
    }
  }

  /**
   * Parse one source file into TAC functions, with structured problems.
   *
   * <p>The default implementation delegates to {@link #parse(String, Path, CompileContext)} and
   * wraps each string problem into a {@link org.plumb.tac.ir.TacProgram.FunctionProblem}. That
   * keeps the string problems visible and gives them a home, but without the construct name and the
   * position -- so a frontend that CAN report those should override this method.
   *
   * @param source the source text
   * @param path the file's path
   * @param ctx the session-scoped compile context
   * @return the functions and the structured problems
   */
  default StructuredParseResult parseStructured(String source, Path path, CompileContext ctx) {
    ParseResult plain = parse(source, path, ctx);
    List<org.plumb.tac.ir.TacProgram.FunctionProblem> structured = new java.util.ArrayList<>();
    for (String problem : plain.problems()) {
      structured.add(
          new org.plumb.tac.ir.TacProgram.FunctionProblem(
              path == null ? "<file>" : path.toString(), "UNHANDLED_NODE", problem, null, -1, -1));
    }
    return new StructuredParseResult(plain.functions(), structured, plain.precision());
  }
}
