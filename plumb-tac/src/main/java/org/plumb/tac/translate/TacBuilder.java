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
package org.plumb.tac.translate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * Mutable helper held by the translator while lowering a single method. Backs the per-function
 * instruction list, the synthetic label / temp counters, and the local-variable list. The
 * translator is single threaded per function.
 *
 * <p>Also holds the per-function annotation metadata (class-level, method-level, and field-level).
 * The translator fills these from the JavaParser AST and {@link TacBuilder#build} propagates them
 * into the resulting {@link TacFunction}.
 */
public final class TacBuilder {

  /** Static metadata for the function being built. */
  public record FunctionSpec(String name, String signature, TacType returnType) {}

  private final FunctionSpec spec;
  private final List<TacOperand.TacVar> params;
  private final List<TacOperand.TacVar> locals = new ArrayList<>();
  private final List<TacInstruction> instructions = new ArrayList<>();
  // Annotation metadata captured during translation. The
  // outer class's annotation names propagate into every function
  // in the class (so a {@code @RestController} on the class makes
  // the controller's methods recognizable as endpoints). The
  // method-level list is the function-specific annotations.
  private final List<String> classAnnotations = new ArrayList<>();
  private final List<String> methodAnnotations = new ArrayList<>();
  private final Map<String, List<String>> fieldAnnotations = new HashMap<>();
  // Parameter-level annotation metadata, same shape as
  // fieldAnnotations but keyed on the parameter name. Used by
  // MISSING_INPUT_VALIDATION to detect @RequestParam
  // parameters that lack @Valid / @Validated.
  private final Map<String, List<String>> paramAnnotations = new HashMap<>();
  // The VALUES the annotations carry, keyed by the annotation's simple name.
  //
  // WHY THIS IS SEPARATE FROM THE NAME LISTS. `@Value("${app.password}")` and
  // `@Value("${app.username}")` are the same annotation by name and completely
  // different facts. A checker that can only see names cannot answer "which
  // property does this field bind to?" -- and that is a question worth answering,
  // because a config-bound value is a value that came from outside the program.
  private final Map<String, List<String>> annotationValues = new HashMap<>();

  /**
   * Per-function field-expression cache. Keyed by the textual form of a {@code FieldAccessExpr}
   * (e.g. {@code "o.a"}), value is the {@link TacOperand.TacVar} that holds (or will hold) the
   * result of that field read. Populated when the translator lowers {@code String t1 = o.a;} so
   * that a later {@code o.a.b = X;} can reuse {@code t1} as the FIELDSTORE's {@code lhs1} -- making
   * {@code fieldMap[t1][b]} the natural storage for the write, which the chain walk in {@link
   * org.plumb.dfa.taint.TaintFact#taintOfChain} then discovers transitively. Without this cache the
   * translator would emit a fresh temp for the FIELDLOAD scope of the chained write, and the chain
   * walk from a later {@code t1.b} read would not find the taint (it would query {@code
   * fieldMap[t1][b]}, not {@code fieldMap[temp][b]}).
   */
  private final Map<String, TacOperand.TacVar> fieldExprCache = new HashMap<>();

  // Try / catch / finally regions registered as the
  // translator lowers each TryStmt. The list is copied into
  // the produced TacFunction at build() time so the CFG
  // builder can wire up exception-flow edges.
  private final List<TacFunction.TryRegion> tryRegions = new ArrayList<>();
  private int tempCounter = 0;
  private int labelCounter = 0;

  /**
   * Whether the function being built is compiler-generated.
   *
   * <p>Set by the frontends that know the difference: the Java translator sets it for a record's
   * implicit accessors and canonical constructor, the Kotlin frontend for a data class's {@code
   * componentN} / {@code copy}, the Scala frontend for a case class's {@code copy} / {@code apply}.
   *
   * @see TacFunction#isSynthetic()
   */
  private boolean synthetic = false;

  /** Marks the function being built as compiler-generated. */
  public void markSynthetic() {
    this.synthetic = true;
  }

  /** Whether the function being built is compiler-generated. */
  public boolean isSynthetic() {
    return synthetic;
  }

  /**
   * The language of the function being built.
   *
   * <p>Defaults to {@link org.plumb.tac.ir.SourceLanguage#JAVA}, because every pre-existing caller
   * is the Java translator. The Kotlin and Scala frontends set it to their own language so that a
   * checker can tell which language's semantics it is looking at.
   *
   * @see org.plumb.tac.ir.TacFunction#sourceLanguage()
   */
  private org.plumb.tac.ir.SourceLanguage sourceLanguage = org.plumb.tac.ir.SourceLanguage.JAVA;

  /** Sets the language of the function being built. */
  public void setSourceLanguage(org.plumb.tac.ir.SourceLanguage language) {
    this.sourceLanguage = language == null ? org.plumb.tac.ir.SourceLanguage.JAVA : language;
  }

  /** The language of the function being built. */
  public org.plumb.tac.ir.SourceLanguage sourceLanguage() {
    return sourceLanguage;
  }

  /**
   * The module the function being built belongs to, or {@code null} when there is no module
   * boundary.
   *
   * <p>A frontend sets it from the compilation context's {@link
   * org.plumb.tac.frontend.CompileContext#moduleOf(java.nio.file.Path)}. It is what lets a
   * single-module analysis report the findings of one module and only those, while the other
   * modules are still read to keep the call graph whole.
   *
   * @see org.plumb.tac.ir.TacFunction#module()
   */
  private String module;

  /** Sets the module of the function being built. */
  public void setModule(String module) {
    this.module = module == null || module.isBlank() ? null : module;
  }

  /** The module of the function being built. */
  public String module() {
    return module;
  }

  /**
   * The short name of the class that declares the function being built, or {@code null}.
   *
   * <p>Used by the frontends that have to name a nested type after the function that declares it: a
   * local class is named after its enclosing method, and an anonymous class after the method that
   * creates it. Both need the enclosing class's name to build a unique qualified name.
   *
   * @see #enclosingClass()
   */
  public String enclosingClass() {
    return enclosingClass;
  }

  /**
   * The precision of the function being built.
   *
   * <p>Defaults to {@link org.plumb.tac.ir.IrPrecision#SOURCE_FULL}. A frontend that lowered a
   * language construct away (a Kotlin {@code when}, a Scala {@code match}) sets {@link
   * org.plumb.tac.ir.IrPrecision#SOURCE_LOWERED} for the functions it degraded.
   *
   * @see org.plumb.tac.ir.TacFunction#irPrecision()
   */
  private org.plumb.tac.ir.IrPrecision irPrecision = org.plumb.tac.ir.IrPrecision.SOURCE_FULL;

  /** Sets the precision of the function being built. */
  public void setIrPrecision(org.plumb.tac.ir.IrPrecision precision) {
    this.irPrecision = precision == null ? org.plumb.tac.ir.IrPrecision.SOURCE_FULL : precision;
  }

  /** The precision of the function being built. */
  public org.plumb.tac.ir.IrPrecision irPrecision() {
    return irPrecision;
  }

  /**
   * The syntax constructs in this function that the translator could not lower.
   *
   * <p><b>Why this channel exists.</b> The translator's fallback for an unrecognised construct is
   * to produce a {@code null} value. That is a <b>silent</b> data-flow corruption: a tainted value
   * flowing through an unsupported construct becomes {@code null}, and every downstream taint fact
   * about it disappears. No exception is thrown and nothing is logged at the default level -- the
   * only symptom is a missing finding, which is indistinguishable from "the code is safe".
   *
   * <p>So an unlowered construct is <b>recorded</b>, not swallowed. The translator promotes these
   * into {@link org.plumb.tac.ir.TacProgram.FunctionProblem}s with the kind {@code UNHANDLED_NODE},
   * and the driver can surface them.
   *
   * <p><b>Directional rule.</b> A problem is a statement about the <i>analysis</i>, not about the
   * <i>program</i>: "the analyser could not fully model this" says nothing about whether the code
   * is vulnerable. It must never be reported as a finding, and it must never be used to suppress
   * one either -- it means the analysis is <i>less</i> complete, not more.
   *
   * @see #reportUnhandled(String, int, int)
   */
  private final List<TacProgram.FunctionProblem> problems = new ArrayList<>();

  /**
   * Records a construct the translator could not lower.
   *
   * @param nodeClass the simple class name of the AST node, e.g. {@code "TextBlockLiteralExpr"}
   * @param line the source line, or -1 if unknown
   * @param column the source column, or -1 if unknown
   */
  public void reportUnhandled(String nodeClass, int line, int column) {
    if (nodeClass == null || nodeClass.isEmpty()) return;
    problems.add(
        new TacProgram.FunctionProblem(
            spec.name(), "UNHANDLED_NODE", "not lowered: " + nodeClass, nodeClass, line, column));
  }

  /** The unhandled constructs recorded for this function. */
  public List<TacProgram.FunctionProblem> problems() {
    return List.copyOf(problems);
  }

  /**
   * Records a construct the SOURCE could not provide -- a parse error the parser recovered from,
   * rather than a construct the translator does not support.
   *
   * <p><b>Why the kind matters.</b> {@code UNHANDLED_NODE} says "the frontend does not model this".
   * {@code PARSE_PROBLEM} says "the input is broken". A consumer that wants to distinguish "this
   * file uses a language feature I should add" from "this file does not compile" cannot do it if
   * both are reported as the same kind. The kinds are separate in {@link
   * TacProgram.FunctionProblem} precisely so they can be told apart.
   *
   * @param message what went wrong
   * @param line the source line, or -1 if unknown
   * @param column the source column, or -1 if unknown
   */
  public void reportParseProblem(String message, int line, int column) {
    if (message == null || message.isEmpty()) return;
    problems.add(
        new TacProgram.FunctionProblem(
            spec.name(), "PARSE_PROBLEM", message, "UnparsableStmt", line, column));
  }

  // The lambda counter is NOT here. It lives on the TacTranslator, because a
  // lambda's name must be unique across the whole program: a per-builder
  // counter restarted at zero for every method, so several methods' lambdas
  // were all named `lambda$0`, and a lookup by name resolved to an arbitrary
  // body. See TacTranslator.nextLambdaName().

  public TacBuilder(FunctionSpec spec, List<TacOperand.TacVar> params) {
    this.spec = spec;
    this.params = new java.util.ArrayList<>(params);
  }

  public FunctionSpec spec() {
    return spec;
  }

  public List<TacOperand.TacVar> params() {
    return params;
  }

  public List<TacOperand.TacVar> locals() {
    return locals;
  }

  /**
   * Declares a named local.
   *
   * <p>Used by features that introduce a variable the source did not spell as a declaration -- a
   * pattern variable ({@code x instanceof String s}) and a record's component binding. The variable
   * must appear in {@code locals()} so that the DFA's per-variable maps, the alias tracker and the
   * field tracker all recognise it as a local rather than an unknown name.
   *
   * <p>Re-declaring the same name is a no-op: the second declaration refers to the same storage
   * location, and the TAC's variable identity is name-based.
   */
  public void addLocal(TacOperand.TacVar v) {
    if (v == null) return;
    for (TacOperand.TacVar existing : locals) {
      if (existing.name().equals(v.name()) && existing.isTemp() == v.isTemp()) return;
    }
    locals.add(v);
  }

  /** Merge class-level annotation names captured by the translator. */
  public void addClassAnnotations(List<String> names) {
    for (String n : names) {
      if (n != null && !n.isEmpty() && !classAnnotations.contains(n)) {
        classAnnotations.add(n);
      }
    }
  }

  /** Merge method-level annotation names for this function. */
  public void addMethodAnnotations(List<String> names) {
    for (String n : names) {
      if (n != null && !n.isEmpty() && !methodAnnotations.contains(n)) {
        methodAnnotations.add(n);
      }
    }
  }

  /** Record a {@code fieldName -> [annotation, ...]} mapping. */
  public void addFieldAnnotations(String fieldName, List<String> names) {
    if (fieldName == null) return;
    fieldAnnotations.computeIfAbsent(fieldName, k -> new ArrayList<>());
    for (String n : names) {
      if (n != null && !n.isEmpty() && !fieldAnnotations.get(fieldName).contains(n)) {
        fieldAnnotations.get(fieldName).add(n);
      }
    }
  }

  /**
   * Record a {@code paramName -> [annotation, ...]} mapping. The parameter name is the simple name
   * from the AST (matching {@link org.plumb.tac.ir.TacFunction#params()}).
   */
  public void addParamAnnotations(String paramName, List<String> names) {
    if (paramName == null) return;
    paramAnnotations.computeIfAbsent(paramName, k -> new ArrayList<>());
    for (String n : names) {
      if (n != null && !n.isEmpty() && !paramAnnotations.get(paramName).contains(n)) {
        paramAnnotations.get(paramName).add(n);
      }
    }
  }

  public List<String> classAnnotations() {
    return classAnnotations;
  }

  public List<String> methodAnnotations() {
    return methodAnnotations;
  }

  public Map<String, List<String>> fieldAnnotations() {
    return fieldAnnotations;
  }

  public Map<String, List<String>> paramAnnotations() {
    return paramAnnotations;
  }

  /** Cache accessors. */
  public Map<String, TacOperand.TacVar> fieldExprCache() {
    return fieldExprCache;
  }

  /**
   * Register a try / catch / finally region after the translator has finished lowering the
   * corresponding {@code TryStmt}. Indices are into the current {@code instructions()} list and
   * inclusive on both ends. The CFG builder reads these ranges to add exception-flow edges from
   * blocks inside the try range to the catch handler blocks (so a throw inside the try body
   * propagates facts to the matched catch) and from blocks inside the catch range that end in
   * {@code THROW} to the finally block (rethrow routing).
   */
  public void recordTryRegion(TacFunction.TryRegion region) {
    if (region == null) return;
    tryRegions.add(region);
  }

  /** Read-only view of the regions registered so far. */
  public List<TacFunction.TryRegion> tryRegions() {
    return tryRegions;
  }

  public TacInstruction emit(TacInstruction insn) {
    instructions.add(insn);
    return insn;
  }

  public List<TacInstruction> instructions() {
    return new ArrayList<>(instructions);
  }

  /** Allocate a fresh temporary; the returned var is also added to {@code locals}. */
  public TacOperand.TacVar newTemp(TacType type) {
    // Temp name is "t_t<N>" so it cannot collide with a
    // user local that happens to be named "t" or "t<N>".
    // Previously temps were "t<N>" with display prefix "t_"
    // (e.g. "t_t2"), but the raw name "t2" collided with a
    // user local `String t2 = ...`. The translateName lookup
    // matched the temp first and shadowed the local, leading
    // to wrong SINK arguments in nested-field tests. We
    // now use "t_t<N>" as the raw name, keeping the display
    // path simple. The "t_" toString prefix is still applied
    // to all temps uniformly (t_t2 displays as "t_t_t2" —
    // verbose but unambiguous and the IR is internal).
    String name = "t_t" + (tempCounter++);
    TacOperand.TacVar v = new TacOperand.TacVar(name, type, true);
    locals.add(v);
    return v;
  }

  public TacOperand.TacVar newTemp() {
    return newTemp(TacType.UNKNOWN);
  }

  public String newLabel(String hint) {
    return hint + "_" + (labelCounter++);
  }

  public TacFunction build() {
    return new TacFunction(
        spec.name(),
        spec.signature(),
        spec.returnType(),
        new ArrayList<>(params),
        new ArrayList<>(locals),
        instructions,
        classAnnotations,
        methodAnnotations,
        fieldAnnotations,
        paramAnnotations,
        enclosingClass,
        origin,
        new ArrayList<>(tryRegions),
        sourceLanguage,
        irPrecision,
        synthetic,
        new java.util.LinkedHashMap<>(annotationValues),
        module);
  }

  /**
   * Record the VALUE an annotation carries.
   *
   * @param annotationName the annotation's simple name, e.g. {@code "Value"}
   * @param value the value's textual form, e.g. {@code "${app.password}"}
   */
  public void addAnnotationValue(String annotationName, String value) {
    if (annotationName == null || annotationName.isEmpty()) return;
    if (value == null) return;
    annotationValues.computeIfAbsent(annotationName, k -> new ArrayList<>());
    if (!annotationValues.get(annotationName).contains(value)) {
      annotationValues.get(annotationName).add(value);
    }
  }

  /** The annotation values collected so far, keyed by the annotation's simple name. */
  public Map<String, List<String>> annotationValues() {
    return annotationValues;
  }

  /**
   * The declaring class of the function being built (e.g. {@code "pkg.MyThread"}).
   *
   * <p><b>Why the package is part of it.</b> The name is the class's IDENTITY, not a label: two
   * classes with the same simple name in different packages are two different classes. The merged
   * view keeps same-named methods on different classes apart by exactly this name, so a name
   * without the package lets one of the two classes drop out of the analysis whole.
   */
  private String enclosingClass;

  /**
   * Sets the name of the class that declares the function.
   *
   * <p><b>Public because the Kotlin and Scala front-ends need it.</b> They live in a different
   * package, so a package-private setter was unreachable -- and the Kotlin front-end worked around
   * it by encoding the declaring class in the function's <em>name</em> and leaving {@code
   * enclosingClass} null.
   *
   * <p>That workaround had a real cost. The enclosing class is not decoration: the call graph
   * indexes functions by it, the merged program uses it to keep same-named methods on different
   * classes apart, and the finding reporter resolves a finding's file path through it. With it
   * null, a Kotlin function was dropped from the call graph's merged-key index entirely -- so a
   * Kotlin caller had <b>zero</b> call sites, and no taint could cross the language boundary.
   *
   * @param name the declaring class's name, or {@code null} for a top-level function
   */
  public void setEnclosingClass(String name) {
    this.enclosingClass = name;
  }

  /**
   * Source origin of the function being built. Defaults to {@link SourceOrigin#SOURCE}; the
   * bytecode front-end overrides to {@link SourceOrigin#LIBRARY} for class-file input. Setter is
   * package-private; only the front-end callers ({@link TacTranslator} and the bytecode analyzer)
   * set it.
   */
  private SourceOrigin origin = SourceOrigin.SOURCE;

  void setOrigin(SourceOrigin o) {
    this.origin = (o == null) ? SourceOrigin.SOURCE : o;
  }

  /**
   * Marks the function as compiler-generated code.
   *
   * <p>Used by the front-ends that can tell generated code from hand-written code: a Kotlin {@code
   * data class}'s accessors, a Java {@code record}'s accessors.
   *
   * <p>The origin becomes {@link SourceOrigin#GENERATED}, which is still analysed by default -- a
   * generated accessor carries taint, and skipping it would drop the taint at every property
   * access.
   *
   * @param generated whether the function is compiler-generated
   */
  public void markGenerated(boolean generated) {
    if (generated) {
      this.origin = SourceOrigin.GENERATED;
    }
  }
}
