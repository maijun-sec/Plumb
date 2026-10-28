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
        new ArrayList<>(tryRegions));
  }

  /**
   * Short name of the declaring class (e.g. {@code "MyThread"}) to attach to the function being
   * built. Setter is package-private; the {@link TacTranslator} is the only caller.
   */
  private String enclosingClass;

  void setEnclosingClass(String name) {
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
}
