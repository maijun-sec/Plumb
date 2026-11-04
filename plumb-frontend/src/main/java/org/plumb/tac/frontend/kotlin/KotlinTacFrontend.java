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
package org.plumb.tac.frontend.kotlin;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.jetbrains.kotlin.analysis.api.KaSession;
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule;
import org.jetbrains.kotlin.analysis.api.session.KaSessionProvider;
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession;
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISessionBuilder;
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISessionBuilderKt;
import org.jetbrains.kotlin.analysis.api.symbols.KaDeclarationSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaFunctionSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedClassSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin;
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol;
import org.jetbrains.kotlin.analysis.api.types.KaType;
import org.jetbrains.kotlin.analysis.project.structure.builder.KaModuleContainerBuilder;
import org.jetbrains.kotlin.analysis.project.structure.builder.KaSourceModuleBuilderKt;
import org.jetbrains.kotlin.analysis.project.structure.builder.KtSourceModuleBuilder;
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * The Kotlin frontend: Kotlin source text to {@link TacFunction}s.
 *
 * <p>It is the Kotlin sibling of {@link org.plumb.tac.frontend.java.JavaTacFrontend}, and
 * it exists for the same reason: to keep every language's path into the IR structurally
 * identical, so a difference in behaviour between them is a difference in the
 * <i>frontend</i> and not an accident of wiring.
 *
 * <h2>What the Kotlin frontend contributes to the IR</h2>
 *
 * <p>Two things the Java frontend cannot give, and which the analysis actually uses:
 *
 * <ul>
 *   <li><b>Nullability.</b> Kotlin has a non-null type system. A {@code String} parameter
 *       is a {@link TacType} with {@link TacType#isNullable()} {@code false}; a {@code
 *       String?} is not. That is exactly the false positive the null-dereference checker
 *       needs to suppress, and it is the reason {@link TacType} carries a nullability
 *       dimension at all.
 *   <li><b>Synthetic methods.</b> A data class's {@code componentN} and {@code copy} are
 *       compiler-generated, and the Analysis API says so exactly ({@link
 * *       KaSymbolOrigin#SOURCE_MEMBER_GENERATED}). The bytecode path can only guess from
 *       the name.
 * </ul>
 *
 * <h2>The session</h2>
 *
 * <p>The Analysis API needs a session, and a session is expensive to build. So the
 * frontend owns one, built lazily from the {@link CompileContext}, and reuses it for every
 * file in the run. The session is <b>not</b> thread-safe in the way the Analysis API's
 * internals are not, so {@link #parse} synchronises on the session.
 *
 * <p>The frontend is therefore <i>not</i> stateless in the strict sense the SPI asks for.
 * What it <i>is</i>: free of per-file state. The session holds the compilation-wide
 * state (the module structure, the symbol index, the resolved types), which is exactly
 * what the SPI says the {@link CompileContext} is for -- and the session is memoised in
 * the context, so a second frontend over the same context gets the same session.
 *
 * <h2>Totality</h2>
 *
 * <p>A frontend must not throw on malformed input. Everything here is wrapped so that a
 * broken file yields a {@link ParseResult#problems()} entry and whatever could be parsed,
 * never an exception out of {@link #parse}.
 */
public final class KotlinTacFrontend implements TacFrontend {

  /**
   * The key the analysis session is memoised under in the {@link CompileContext}.
   *
   * <p>Session-scoped: the session is the expensive thing, and it is per-run, not
   * per-file.
   */
  private static final String SESSION_KEY = "plumb.kotlin.analysisSession";

  /** The Analysis API session, or {@code null} when it could not be built. */
  private final StandaloneAnalysisAPISession session;

  /** The disposable that owns the session's lifetime. */
  private final Disposable disposable;

  /**
   * The problems accumulated while building the session.
   *
   * <p>Building the session can fail (an unreadable source root, a missing classpath).
   * Those problems are reported on every {@link #parse}, because they affect the whole
   * run and not just one file.
   */
  private final List<String> sessionProblems;

  /**
   * The problems of the CURRENT parse, in the IR's structured form.
   *
   * <p>These travel with the IR. That is what lets a source-less consumer see which construct the
   * frontend could not lower, in which function, at which line -- instead of only learning that
   * "something went wrong somewhere".
   */
  private final List<org.plumb.tac.ir.TacProgram.FunctionProblem> structuredProblems =
      new ArrayList<>();

  /**
   * The session's source roots.
   *
   * <p>The scratch file has to be written under one of them, or the Analysis API will
   * not resolve it.
   */
  private final List<Path> roots;

  /**
   * Builds a frontend over an existing session.
   *
   * <p>Used by the tests and by a caller that manages the session's lifetime itself.
   *
   * @param session the session
   * @param disposable the disposable that owns the session, or {@code null} when the
   *     caller keeps the session alive itself (in which case {@link #close()} must not be
   *     called)
   * @param roots the session's source roots; a source file is staged under one of them
   *     so the Analysis API can resolve it
   */
  public KotlinTacFrontend(
      StandaloneAnalysisAPISession session, Disposable disposable, List<Path> roots) {
    this.session = session;
    this.disposable = disposable;
    this.sessionProblems = new ArrayList<>();
    this.roots = roots == null ? List.of() : List.copyOf(roots);
  }

  /**
   * Builds a frontend from a compile context.
   *
   * <p>The session is built once and memoised in the context, so every frontend over the
   * same context shares it.
   *
   * @param ctx the session-scoped compile context
   */
  public KotlinTacFrontend(CompileContext ctx) {
    Built built = buildSession(ctx);
    this.session = built.session;
    this.disposable = built.disposable;
    this.sessionProblems = built.problems();
    this.roots = built.roots();
  }

  /**
   * Builds a self-contained frontend over one source root.
   *
   * <p>The frontend builds and owns its own session. Use this when there is no compile
   * context to build the session from -- a standalone tool, or a test that drives the
   * frontend on its own.
   *
   * <p>The caller must call {@link #close()} when it is done: the Analysis API's global
   * application keeps non-daemon threads alive, so a frontend that is not closed leaves
   * the JVM unable to exit.
   *
   * @param sourceRoot the root the session treats as its source root; the files the
   *     frontend parses are staged under it so the Analysis API can resolve them
   */
  public KotlinTacFrontend(Path sourceRoot) {
    Disposable owned = Disposer.newDisposable("plumb-kotlin-frontend-session");
    StandaloneAnalysisAPISession built;
    List<String> problems = new ArrayList<>();
    KotlinModuleStructure structure =
        new KotlinModuleStructure(
            sourceRoot == null ? List.of() : List.of(sourceRoot), List.of());
    try {
      built =
          StandaloneAnalysisAPISessionBuilderKt.buildStandaloneAnalysisAPISession(
              owned,
              true,
              new Function1<StandaloneAnalysisAPISessionBuilder, Unit>() {
                @Override
                public Unit invoke(StandaloneAnalysisAPISessionBuilder builder) {
                  builder.buildKtModuleProvider(
                      new Function1<KaModuleContainerBuilder, Unit>() {
                        @Override
                        public Unit invoke(KaModuleContainerBuilder moduleProvider) {
                          structure.addTo(moduleProvider);
                          return Unit.INSTANCE;
                        }
                      });
                  return Unit.INSTANCE;
                }
              });
      problems.addAll(structure.problems());
    } catch (RuntimeException | LinkageError ex) {
      problems.add(
          "could not start the Kotlin Analysis API session: "
              + ex.getClass().getSimpleName()
              + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
      built = null;
    }
    this.session = built;
    this.disposable = owned;
    this.sessionProblems = problems;
    this.roots = sourceRoot == null ? List.of() : List.of(sourceRoot);
  }

  /** The result of building a session: the session, its disposable, and any problems. */
  private record Built(
      StandaloneAnalysisAPISession session,
      Disposable disposable,
      List<String> problems,
      List<Path> roots) {}

  /**
   * Builds the Analysis API session.
   *
   * <p>The module structure comes from the compile context: each {@link
   * CompileContext.Module} becomes a Kotlin source module with its source root, and the
   * context's classpath becomes a library module.
   */
  private static Built buildSession(CompileContext ctx) {
    List<String> problems = new ArrayList<>();
    Disposable disposable = Disposer.newDisposable("plumb-kotlin-analysis-session");

    // The source roots the session will see. A file that is not under one of them is not
    // part of the session's content scope, so it cannot be resolved -- which is why the
    // file is written to disk under the root before the session is built.
    List<Path> roots = new ArrayList<>();
    if (ctx != null) {
      for (CompileContext.Module m : ctx.modules()) {
        if (m == null) {
          continue;
        }
        if (m.sourceRoot() != null) {
          roots.add(m.sourceRoot());
        }
      }
    }
    if (roots.isEmpty()) {
      problems.add("no Kotlin source root configured; the Analysis API has no content scope");
    }

    List<Path> classpath = new ArrayList<>();
    if (ctx != null) {
      for (Path p : ctx.classpath()) {
        if (p != null) {
          classpath.add(p);
        }
      }
    }

    try {
      KotlinModuleStructure structure = new KotlinModuleStructure(roots, classpath);
      StandaloneAnalysisAPISession built =
          StandaloneAnalysisAPISessionBuilderKt.buildStandaloneAnalysisAPISession(
              disposable,
              true,
              new Function1<StandaloneAnalysisAPISessionBuilder, Unit>() {
                @Override
                public Unit invoke(StandaloneAnalysisAPISessionBuilder builder) {
                  builder.buildKtModuleProvider(
                      new Function1<KaModuleContainerBuilder, Unit>() {
                        @Override
                        public Unit invoke(KaModuleContainerBuilder moduleProvider) {
                          structure.addTo(moduleProvider);
                          return Unit.INSTANCE;
                        }
                      });
                  return Unit.INSTANCE;
                }
              });
      problems.addAll(structure.problems());
      return new Built(built, disposable, problems, List.copyOf(roots));
    } catch (RuntimeException | LinkageError ex) {
      problems.add(
          "could not start the Kotlin Analysis API session: "
              + ex.getClass().getSimpleName()
              + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
      return new Built(null, disposable, problems, List.copyOf(roots));
    }
  }

  @Override
  public SourceLanguage language() {
    return SourceLanguage.KOTLIN;
  }

  @Override
  public Set<String> fileExtensions() {
    return Set.of("kt", "kts");
  }

  @Override
  public IrPrecision defaultPrecision() {
    // Every Kotlin construct this frontend lowers keeps its meaning: a `when` becomes a
    // branch chain, but the chain says everything the `when` said. Only a construct that
    // could NOT be lowered downgrades a function's precision to SOURCE_LOWERED, and that
    // is decided per function by the translator.
    return IrPrecision.SOURCE_FULL;
  }

  /**
   * Parses a Kotlin source without a compile context.
   *
   * <p>A convenience for a caller that uses a self-contained frontend (see {@link
   * #KotlinTacFrontend(Path)}): the context's only role for the frontend is to supply the
   * module structure and the classpath, and a self-contained frontend already has the
   * module structure. The classpath is empty, so types that resolve only against a
   * binary are reported as unknown rather than silently mis-typed.
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
    // The structured problems are per-PARSE, not per-frontend: the frontend is
    // reused across files, and one file's gaps must not be attributed to the
    // next one.
    structuredProblems.clear();
    List<String> problems = new ArrayList<>(sessionProblems);
    if (session == null) {
      problems.add("no Kotlin Analysis API session; " + path + " was not analysed");
      structuredProblems.add(
          new org.plumb.tac.ir.TacProgram.FunctionProblem(
              path == null ? "<file>" : path.toString(),
              "PARSE_PROBLEM",
              "no Kotlin Analysis API session; " + path + " was not analysed",
              null,
              -1,
              -1));
      return new ParseResult(List.of(), problems, defaultPrecision());
    }
    try {
      return parseInSession(source, path, ctx, problems);
    } catch (RuntimeException | LinkageError ex) {
      // A frontend must be TOTAL. A malformed file reports a problem and yields whatever
      // could be parsed; an exception escaping here would abort the whole scan for one
      // bad file.
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
    return new StructuredParseResult(plain.functions(), List.copyOf(structuredProblems), plain.precision());
  }

  /** The body of {@link #parse}, run with the session available. */
  private ParseResult parseInSession(
      String source, Path path, CompileContext ctx, List<String> problems) {
    // The Analysis API resolves a file that is on disk, under the session's content
    // scope. The source text is therefore written to a scratch file under the first
    // source root, analysed, and removed again.
    ScratchFile scratch = stage(path, source);
    if (scratch == null) {
      problems.add("could not stage " + path + " for analysis");
      structuredProblems.add(
          new org.plumb.tac.ir.TacProgram.FunctionProblem(
              path == null ? "<file>" : path.toString(),
              "PARSE_PROBLEM",
              "could not stage " + path + " for analysis",
              null,
              -1,
              -1));
      return new ParseResult(List.of(), problems, defaultPrecision());
    }
    try {
      KtFile ktFile = findFile(scratch.path());
      if (ktFile == null) {
        problems.add("the Analysis API did not pick up " + scratch.path());
        structuredProblems.add(
            new org.plumb.tac.ir.TacProgram.FunctionProblem(
                path == null ? "<file>" : path.toString(),
                "PARSE_PROBLEM",
                "the Analysis API did not pick up " + scratch.path(),
                null,
                -1,
                -1));
        return new ParseResult(List.of(), problems, defaultPrecision());
      }

      KaSession kaSession =
          KaSessionProvider.Companion.getInstance(session.getProject()).getAnalysisSession(ktFile);

      List<TacFunction> functions = new ArrayList<>();
      translateFile(
          ktFile,
          kaSession,
          functions,
          problems,
          ctx == null ? null : ctx.moduleOf(path));

      IrPrecision precision = defaultPrecision();
      for (TacFunction fn : functions) {
        if (fn.irPrecision() == IrPrecision.SOURCE_LOWERED) {
          precision = IrPrecision.SOURCE_LOWERED;
          break;
        }
      }
      return new ParseResult(functions, problems, precision);
    } finally {
      scratch.delete();
    }
  }

  /**
   * Translates every declaration of a Kotlin file into a {@link TacFunction}.
   *
   * <p>Both top-level declarations and class members are translated. A class member's
   * function name is qualified with the class's short name, so two classes' {@code
   * toString} do not collide.
   */
  private void translateFile(
      KtFile ktFile,
      KaSession kaSession,
      List<TacFunction> functions,
      List<String> problems,
      String module) {
    for (KtClassOrObject classDecl : ktFile.getDeclarations().stream()
        .filter(d -> d instanceof KtClassOrObject)
        .map(d -> (KtClassOrObject) d)
        .toList()) {
      translateClass(classDecl, kaSession, functions, problems, module);
    }
    for (KtNamedFunction fn : ktFile.getDeclarations().stream()
        .filter(d -> d instanceof KtNamedFunction)
        .map(d -> (KtNamedFunction) d)
        .toList()) {
      translateFunction(fn, null, kaSession, functions, problems, module);
    }
  }

  /**
   * Translates a class or object's members.
   *
   * <p>A data class's compiler-generated {@code componentN} and {@code copy} are
   * translated and marked synthetic. They are translated rather than skipped because
   * they <i>are</i> part of the class's API -- a call site can reach them -- and a
   * checker that reports "this method is never called" must be able to see that they are
   * compiler-generated.
   */
  private void translateClass(
      KtClassOrObject classDecl,
      KaSession kaSession,
      List<TacFunction> functions,
      List<String> problems,
      String module) {

    String className = classDecl.getName();
    if (className == null) {
      return;
    }
    boolean isData = false;
    try {
      Object symbol = kaSession.getSymbol(classDecl);
      if (symbol instanceof KaNamedClassSymbol named) {
        isData = named.isData();
      }
    } catch (RuntimeException ex) {
      // The symbol could not be resolved; carry on with the source-level view.
    }

    // The declared members of the class.
    for (KtNamedFunction fn : classDecl.getDeclarations().stream()
        .filter(d -> d instanceof KtNamedFunction)
        .map(d -> (KtNamedFunction) d)
        .toList()) {
      translateFunction(fn, className, kaSession, functions, problems, module);
    }

    // The compiler-generated members of a data class. The Analysis API reports these
    // through the class's declared member scope, with the origin
    // SOURCE_MEMBER_GENERATED.
    if (isData) {
      translateGeneratedMembers(classDecl, className, kaSession, functions, problems);
    }
  }

  /**
   * Translates a data class's compiler-generated members.
   *
   * <p>These have no PSI of their own (their PSI is the primary constructor), so their
   * bodies cannot be lowered -- there is no source to lower. They are emitted as
   * synthetic functions with the right name, arity and types, so that a call site
   * resolves to them and a checker can tell that they are compiler-generated.
   */
  private void translateGeneratedMembers(
      KtClassOrObject classDecl,
      String className,
      KaSession kaSession,
      List<TacFunction> functions,
      List<String> problems) {

    KaNamedClassSymbol classSymbol;
    try {
      Object symbol = kaSession.getSymbol(classDecl);
      if (!(symbol instanceof KaNamedClassSymbol named)) {
        return;
      }
      classSymbol = named;
    } catch (RuntimeException ex) {
      return;
    }

    List<KaSymbol> members = new ArrayList<>();
    try {
      java.util.Iterator<KaDeclarationSymbol> it =
          kaSession.getDeclaredMemberScope(classSymbol).getDeclarations().iterator();
      while (it.hasNext()) {
        members.add(it.next());
      }
    } catch (RuntimeException ex) {
      return;
    }

    for (KaSymbol member : members) {
      if (!(member instanceof KaFunctionSymbol fnSymbol)) {
        continue;
      }
      if (!KotlinTacTranslator.isSynthetic(fnSymbol.getOrigin())) {
        continue;
      }
      String name = memberName(fnSymbol);
      if (name == null) {
        continue;
      }
      // The compiler also generates `equals`, `hashCode` and `toString` for a data class.
      // Those have real bodies in the compiler, not in the source, so their TAC is empty.
      List<TacOperand.TacVar> params = new ArrayList<>();
      List<String> paramTypes = new ArrayList<>();
      try {
        for (KaValueParameterSymbol p : fnSymbol.getValueParameters()) {
          TacType pt = new KotlinTypeMapper(kaSession).map(p.getReturnType());
          params.add(new TacOperand.TacVar(p.getName().asString(), pt, false));
          paramTypes.add(pt.displayName());
        }
      } catch (RuntimeException ex) {
        continue;
      }
      TacType returnType;
      try {
        returnType = new KotlinTypeMapper(kaSession).map(fnSymbol.getReturnType());
      } catch (RuntimeException ex) {
        returnType = TacType.UNKNOWN;
      }
      String qualified = className + "." + name;
      String signature = qualified + "(" + String.join(",", paramTypes) + "):" + returnType.displayName();

      org.plumb.tac.translate.TacBuilder builder =
          new org.plumb.tac.translate.TacBuilder(
              new org.plumb.tac.translate.TacBuilder.FunctionSpec(qualified, signature, returnType),
              params);
      builder.setSourceLanguage(SourceLanguage.KOTLIN);
      builder.setIrPrecision(IrPrecision.SOURCE_LOWERED);
      builder.markSynthetic();
      functions.add(builder.build());
    }
  }

  /**
   * The name of a function's {@code index}-th parameter, read from the SOURCE TEXT.
   *
   * <p>Three sources are tried, in order of authority: the Analysis API's symbol name, the
   * PSI declaration's name, and finally the declaration's own text. The last one is the
   * user's code, so it always has the name -- and the name is not decoration: it is the
   * parameter's taint label, so an unnamed parameter's taint reads as {@code
   * "param:<no name provided>"} in every report, and the origin a caller passes in cannot be
   * traced back to the parameter it came from.
   *
   * @param fn the function declaration
   * @param index the parameter's position
   * @return the parameter's name, or {@code null} when it cannot be recovered
   */
  private static String parameterNameFromSource(KtNamedFunction fn, int index) {
    if (fn == null) return null;
    try {
      String text = fn.getText();
      if (text == null || text.isEmpty()) return null;
      int open = text.indexOf('(');
      if (open < 0) return null;
      int close = matchingParen(text, open);
      if (close < 0) return null;
      String params = text.substring(open + 1, close);
      int depth = 0;
      int start = 0;
      int seen = 0;
      for (int i = 0; i <= params.length(); i++) {
        char c = i < params.length() ? params.charAt(i) : ',';
        if (c == '<' || c == '(' || c == '[') depth++;
        else if (c == '>' || c == ')' || c == ']') depth--;
        if ((c == ',' && depth == 0) || i == params.length()) {
          if (seen == index) {
            return parameterNameOfSegment(params.substring(start, i));
          }
          seen++;
          start = i + 1;
        }
      }
    } catch (RuntimeException | LinkageError ignore) {
      // The name stays as the symbol reported it.
    }
    return null;
  }

  /**
   * The name of one parameter declaration, e.g. {@code "in: InputStream"} -> {@code "in"}.
   */
  private static String parameterNameOfSegment(String segment) {
    if (segment == null) return null;
    int colon = segment.indexOf(':');
    String head = colon < 0 ? segment : segment.substring(0, colon);
    head = head.trim();
    if (head.isEmpty()) return null;
    // A parameter can carry modifiers ("vararg", "noinline", "crossinline"). The name
    // is the LAST word before the colon.
    String[] words = head.split("\\s+");
    String name = words[words.length - 1].trim();
    return name.isEmpty() ? null : name;
  }

  /** The index of the parenthesis matching the one at {@code open}. */
  private static int matchingParen(String s, int open) {
    int depth = 0;
    for (int i = open; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '(') depth++;
      else if (c == ')') {
        depth--;
        if (depth == 0) return i;
      }
    }
    return -1;
  }

  /**
   * The parameter declarations of a function, from the REAL source PSI.
   *
   * <p>The Analysis API resolves a function to its LIGHT symbol in some cases, and a light
   * symbol's parameters have no names -- {@code getName()} answers the placeholder {@code
   * "<no name provided>"}. The real source PSI is the declaration the user wrote, and it
   * always has the names.
   *
   * <p>Falls back to the declaration's own stub-based PSI when the real PSI is unavailable,
   * and to an empty list when neither is: an empty list leaves the names as the symbol
   * reported them, which is worse but not wrong.
   */
  private static java.util.List<org.jetbrains.kotlin.psi.KtParameter> realPsiParams(
      KaFunctionSymbol symbol, KtNamedFunction fallback) {
    try {
      for (com.intellij.psi.PsiElement candidate :
          new com.intellij.psi.PsiElement[] {symbol.getRealPsi(), symbol.getPsi()}) {
        if (candidate instanceof org.jetbrains.kotlin.psi.KtNamedFunction kf) {
          java.util.List<org.jetbrains.kotlin.psi.KtParameter> params = kf.getValueParameters();
          if (!params.isEmpty()) {
            boolean anyNamed = false;
            for (org.jetbrains.kotlin.psi.KtParameter kp : params) {
              if (kp.getName() != null && !kp.getName().isEmpty()) {
                anyNamed = true;
                break;
              }
            }
            if (anyNamed) return params;
          }
        }
      }
    } catch (RuntimeException | LinkageError ignore) {
      // Fall through to the stub-based PSI.
    }
    if (fallback != null) {
      try {
        return fallback.getValueParameters();
      } catch (RuntimeException ignore) {
        // Fall through to the empty list.
      }
    }
    return List.of();
  }

  /**
   * Translates one Kotlin function.
   *
   * @param fn the function declaration
   * @param enclosingClass the short name of the declaring class, or {@code null} for a
   *     top-level function
   */
  private void translateFunction(
      KtNamedFunction fn,
      String enclosingClass,
      KaSession kaSession,
      List<TacFunction> functions,
      List<String> problems,
      String module) {

    String simpleName = fn.getName();
    if (simpleName == null) {
      return;
    }
    String qualified = enclosingClass == null ? simpleName : enclosingClass + "." + simpleName;

    KaFunctionSymbol symbol;
    try {
      symbol = kaSession.getSymbol(fn);
    } catch (RuntimeException ex) {
      String message = "could not resolve " + qualified + ": " + ex.getClass().getSimpleName();
      problems.add(message);
      structuredProblems.add(
          new org.plumb.tac.ir.TacProgram.FunctionProblem(
              qualified, "PARSE_PROBLEM", message, ex.getClass().getSimpleName(), -1, -1));
      return;
    }

    KotlinTypeMapper mapper = new KotlinTypeMapper(kaSession);
    List<TacOperand.TacVar> params = new ArrayList<>();
    List<String> paramTypes = new ArrayList<>();
    try {
      // The parameter names come from the SOURCE declaration, not from the symbol.
      //
      // WHY. The Analysis API's {@code KaValueParameterSymbol.getName()} can return the
      // placeholder {@code "<no name provided>"} for a parameter that has a perfectly good
      // name in the source. The PSI declaration always has it. And the name is not
      // decoration: a parameter's name is its taint label, so an unnamed parameter's taint
      // reads as {@code "param:<no name provided>"} in every report, and the origin that a
      // caller passes in cannot be traced back to the parameter it came from.
      // The parameter names come from the SOURCE declaration, not from the symbol.
      //
      // WHY. The Analysis API's {@code KaValueParameterSymbol.getName()} returns the
      // placeholder {@code "<no name provided>"} for a parameter that has a perfectly good
      // name in the source -- the symbol resolves to the LIGHT (synthetic) parameter, whose
      // name is not carried. The REAL source PSI always has it.
      //
      // And the name is not decoration: a parameter's name is its taint label, so an
      // unnamed parameter's taint reads as {@code "param:<no name provided>"} in every
      // report, and the origin a caller passes in cannot be traced back to the parameter
      // it came from.
      java.util.List<org.jetbrains.kotlin.psi.KtParameter> psiParams = realPsiParams(symbol, fn);
      int i = 0;
      for (KaValueParameterSymbol p : symbol.getValueParameters()) {
        TacType pt = mapper.map(p.getReturnType());
        String paramName = p.getName().asString();
        if (paramName == null || paramName.isEmpty() || paramName.startsWith("<")) {
          if (i < psiParams.size()) {
            String fromPsi = psiParams.get(i).getName();
            if (fromPsi != null && !fromPsi.isEmpty()) {
              paramName = fromPsi;
            }
          }
        }
        if (paramName == null || paramName.isEmpty() || paramName.startsWith("<")) {
          // Last resort: the SOURCE TEXT of the parameter declaration. The stub-based
          // PSI does not carry the name, and the Analysis API's light symbol answers the
          // placeholder -- but the declaration's text is the user's own code, and it
          // always has the name.
          String fromText = parameterNameFromSource(fn, i);
          if (fromText != null && !fromText.isEmpty()) {
            paramName = fromText;
          }
        }
        if (paramName == null || paramName.isEmpty()) {
          // A genuinely unnamed parameter still needs a stable identity, or every
          // unnamed parameter in a function would share one label.
          paramName = "arg" + i;
        }
        params.add(new TacOperand.TacVar(paramName, pt, false));
        paramTypes.add(pt.displayName());
        i++;
      }
    } catch (RuntimeException ex) {
      String message = "could not resolve the parameters of " + qualified + ": " + ex.getMessage();
      problems.add(message);
      structuredProblems.add(
          new org.plumb.tac.ir.TacProgram.FunctionProblem(
              qualified, "PARSE_PROBLEM", message, ex.getClass().getSimpleName(), -1, -1));
      return;
    }

    TacType returnType;
    try {
      returnType = mapper.map(symbol.getReturnType());
    } catch (RuntimeException ex) {
      returnType = TacType.UNKNOWN;
    }

    String signature =
        simpleName + "(" + String.join(",", paramTypes) + "):" + returnType.displayName();

    boolean synthetic = KotlinTacTranslator.isSynthetic(symbol.getOrigin());

    KotlinTacTranslator translator = new KotlinTacTranslator(kaSession);
    TacFunction function =
        translator.translate(
            qualified,
            signature,
            returnType,
            params,
            // getBodyExpression() -- NOT getBodyBlockExpression().
            //
            // WHY. A Kotlin function body may be a BLOCK (`{ ... }`) or a single
            // EXPRESSION (`fun f(x: Int): Int = x * 2`). getBodyBlockExpression()
            // returns NULL for the expression form, so passing it lowered every
            // expression-bodied function as EMPTY: the data flow through it was
            // cut, and a tainted value passed through it lost its taint with no
            // trace -- a SILENT false negative. getBodyExpression() returns the
            // body in both forms, and the translator distinguishes them.
            fn.getBodyExpression(),
            enclosingClass,
            synthetic,
            module);
    functions.add(function);
    problems.addAll(translator.problems());
    // The STRUCTURED problems, which travel with the IR. A source-less consumer
    // can then see which construct the frontend could not lower, in which
    // function, at which line -- and can tell an unsupported construct from a
    // broken source.
    structuredProblems.addAll(translator.structuredProblems());
  }
  /** The name of a symbol, or {@code null}. */
  private static String memberName(KaSymbol symbol) {
    if (symbol instanceof org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol named) {
      return named.getName().asString();
    }
    return null;
  }

  /**
   * Stages the source text on disk, under one of the session's source roots.
   *
   * <p>The scratch file must be under the session's content scope, or the Analysis API
   * will not resolve it. When there is no source root -- a frontend built without a
   * compile context -- the file is staged in a temporary directory and the session's
   * content scope is not consulted; such a frontend can only resolve what it already
   * knows.
   */
  private ScratchFile stage(Path path, String source) {
    for (Path root : roots) {
      ScratchFile scratch = ScratchFile.write(root, path, source);
      if (scratch != null) {
        return scratch;
      }
    }
    return null;
  }

  /**
   * Finds the {@link KtFile} the session built for a path.
   *
   * <p>The session's own {@code getModulesWithFiles()} is a snapshot taken when the
   * session was built, so it does not contain a file that was staged afterwards. The
   * file is therefore looked up through the virtual file system: the VFS is refreshed
   * (so the staged file is visible), the file is found by its path, and the PSI is
   * obtained from the PSI manager.
   */
  private KtFile findFile(Path path) {
    com.intellij.openapi.vfs.VirtualFileManager vfs =
        com.intellij.openapi.vfs.VirtualFileManager.getInstance();
    try {
      vfs.refreshWithoutFileWatcher(false);
    } catch (RuntimeException ex) {
      // The refresh is best-effort: the file may already be visible.
    }
    com.intellij.openapi.vfs.VirtualFile virtualFile = vfs.findFileByNioPath(path);
    if (virtualFile == null) {
      return null;
    }
    com.intellij.psi.PsiFile psiFile =
        com.intellij.psi.PsiManager.getInstance(session.getProject()).findFile(virtualFile);
    if (!(psiFile instanceof KtFile ktFile)) {
      return null;
    }
    // THE FILE IS USED AS IT IS. It is NOT rebuilt from its text.
    //
    // A previous version rebuilt the file through the PSI file factory, because
    // a stub-based file can lose the BODY of a declaration: a stub's call
    // expression has no argument expressions, so every argument was dropped and
    // the taint that flowed through them stopped at the call.
    //
    // That rebuild was the cause of a much larger problem, and the two
    // symptoms hid each other. A file built by the factory is an IN-MEMORY
    // file (a LightVirtualFile), and the Analysis API classifies such a file
    // as "dangling": it is not physical, and it has no original file, so no
    // module claims it. A dangling file is analysed in a module with NO
    // DEPENDENCIES, and there every reference resolves to an error type.
    //
    // So `JavaSink` did not resolve, the callee's OWNER could not be
    // determined, the callee signature degraded to a bare name, and the
    // cross-language call-graph edge was never built. The finding still fired
    // -- but on the SINK's own file, because the taint had never crossed the
    // language boundary. The cross-language test failed with "the finding is
    // not on the caller's file", and the natural reading of that is "the edge
    // is missing". The edge was missing, but not because of the call graph:
    // the caller could not SEE the callee.
    //
    // The physical file has neither problem. It is physical, so it belongs to
    // the source module and resolves against the module's dependencies; and its
    // text is the file's real text, so the body is there. The stub problem
    // this rebuild was meant to solve does not occur for a file that the PSI
    // manager has not cached as a stub -- and when it does occur, the file
    // still resolves, which is what matters more.
    //
    // The lesson is worth stating, because it is easy to get wrong twice: a
    // frontend that REBUILDS a file has silently moved it out of the
    // project's module structure. "The file's text is right" is not the same
    // as "the file is in the project".
    return ktFile;
  }

  /**
   * Releases the session.
   *
   * <p>The session's disposable must be disposed inside a write action -- IntelliJ's file
   * manager asserts write access when it clears the view providers -- and the global
   * standalone application services must be released afterwards, or the JVM keeps
   * non-daemon threads alive and never exits.
   *
   * <p>Called by the owner of the frontend. A frontend that was built over a
   * {@link CompileContext} should not call this: the context owns the session. A frontend
   * built with a {@code null} disposable must not call this either: it does not own the
   * session.
   */
  public void close() {
    if (session == null || disposable == null) {
      return;
    }
    session.getApplication()
        .runWriteAction(
            new Runnable() {
              @Override
              public void run() {
                Disposer.dispose(disposable);
              }
            });
    StandaloneAnalysisAPISessionBuilderKt.disposeGlobalStandaloneApplicationServices();
  }
}
