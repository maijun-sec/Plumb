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
package org.plumb.dfa.taint;

import java.util.LinkedHashMap;
import java.util.Map;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * The facts that say "this reference cannot be null here".
 *
 * <p>This tracker holds the two <b>path-independent</b> nullability facts, as distinct from the
 * path-dependent ones that {@link GuardTracker} holds:
 *
 * <ol>
 *   <li><b>Type-system facts</b> ({@link TacType#isNullable()}): the variable's declared type is
 *       statically non-nullable. Kotlin's {@code String} is such a type; {@code String?} is not.
 *       This is a statement about the <i>declaration</i>: it holds on every path, in every function
 *       that sees the declaration.
 *   <li><b>Explicit check facts</b> ({@link org.plumb.tac.ir.TacInstruction.Kind#NULL_CHECK}): a
 *       compiler-inserted or user-written null check has already proven the value non-null on this
 *       path.
 * </ol>
 *
 * <h2>Why this is separate from {@link GuardTracker}</h2>
 *
 * <p>{@code GuardTracker} answers "is this variable proven non-null <i>on the current path</i>?",
 * and it derives that from branches the analyser read. {@code NullabilityFacts} answers a different
 * question: "is this variable's <i>type</i> incapable of holding null, or has an explicit check
 * already run?". The two overlap but neither subsumes the other, and conflating them is how a
 * null-dereference checker ends up either losing findings or inventing them.
 *
 * <h2>The directional rule</h2>
 *
 * <p><b>These facts may only suppress a finding. They may never establish that a call site is
 * safe.</b>
 *
 * <p>The reason is specific, not general caution:
 *
 * <ul>
 *   <li>A Kotlin non-null type can be violated at runtime. A Java method returning {@code String}
 *       is a <i>platform type</i> when seen from Kotlin: the compiler inserts an assertion that
 *       throws on null, but {@code -Xno-call-assertions} / {@code -Xno-param-assertions} removes
 *       it, and ProGuard/R8's {@code -assumenosideeffects} strips it from bytecode. "The type says
 *       non-null" is a statement about the source, not about the running program.
 *   <li>A {@code NULL_CHECK} in the bytecode may have been inserted by the compiler and later
 *       stripped by an optimiser.
 * </ul>
 *
 * <p>So a non-null fact removes a finding that the checker could not otherwise justify. It never
 * turns "no finding" into "proven safe", and the checker that consumes these facts must not treat
 * their absence as evidence of anything.
 *
 * <h2>Scope</h2>
 *
 * <p>Per-function state, reset by {@link #clear()} from the owning checker's {@code initFunction}.
 * The type-system half is re-derived per function because the same variable name in a different
 * function may have a different declared type.
 */
public final class NullabilityFacts {

  /**
   * Variables whose declared type is statically non-nullable, with the reason recorded for the
   * finding's detail text.
   *
   * <p>Keyed by variable identity ({@link TacOperand.TacVar}), whose equality is name + temp flag
   * -- deliberately not the type, so a variable that is re-resolved with a refined type stays the
   * same key.
   */
  private final Map<TacOperand.TacVar, String> nonNullByType = new LinkedHashMap<>();

  /** Variables an explicit {@code NULL_CHECK} has proven non-null on this path. */
  private final Map<TacOperand.TacVar, String> provenByCheck = new LinkedHashMap<>();

  /** Per-function reset. Called from the owning checker's {@code initFunction}. */
  public void clear() {
    nonNullByType.clear();
    provenByCheck.clear();
  }

  /**
   * Records a variable whose declared type is statically non-nullable.
   *
   * <p>Only reference types can be null, so a primitive type is ignored. A variable whose type is
   * {@link TacType.Kind#UNKNOWN} is ignored too: "unknown" is not "non-null", and treating it as
   * non-null would suppress findings on every unresolved symbol.
   *
   * @param var the variable
   * @param reason a human-readable reason, used in the finding's detail text
   */
  public void recordNonNullType(TacOperand.TacVar var, String reason) {
    if (var == null) return;
    TacType t = var.type();
    if (t == null || !t.isReference()) return;
    if (t.isNullable()) return;
    nonNullByType.putIfAbsent(var, reason);
  }

  /**
   * Records that an explicit null check has proven the variable non-null.
   *
   * @param var the variable
   * @param reason a human-readable reason
   */
  public void recordProvenByCheck(TacOperand.TacVar var, String reason) {
    if (var == null) return;
    provenByCheck.putIfAbsent(var, reason);
  }

  /**
   * Whether the variable's declared type is statically non-nullable.
   *
   * <p>This is the type-system fact alone. It says nothing about the current path.
   */
  public boolean isNonNullByType(TacOperand.TacVar var) {
    return var != null && nonNullByType.containsKey(var);
  }

  /** Whether an explicit null check has proven the variable non-null. */
  public boolean isProvenByCheck(TacOperand.TacVar var) {
    return var != null && provenByCheck.containsKey(var);
  }

  /**
   * Whether the variable cannot be null here, and why.
   *
   * <p>The check-fact wins over the type-fact when both hold, because it is the stronger, more
   * specific statement: it was established at a particular point on this path rather than inherited
   * from a declaration.
   *
   * @return a reason when the variable is known non-null here, or {@code null} when nothing says so
   */
  public String reasonIfNonNull(TacOperand.TacVar var) {
    if (var == null) return null;
    String byCheck = provenByCheck.get(var);
    if (byCheck != null) return byCheck;
    return nonNullByType.get(var);
  }
}
