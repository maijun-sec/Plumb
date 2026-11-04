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
package org.plumb.tac.ir;

// IR-serializable: this enum participates in the disk-cache contract (IrSerializer).
// Enum constants are serialised by name, so ADDING a constant at the end is
// backwards compatible. Reordering or renaming a constant is NOT.

/**
 * How much of the original source semantics survived the lowering into TAC.
 *
 * <p>A checker needs to know this to decide whether it may trust a particular piece of information.
 * Two examples:
 *
 * <ul>
 *   <li>A Kotlin {@code val x: String} is non-null <i>by the type system</i>. A frontend that read
 *       the type can therefore suppress a null-dereference finding. A frontend that only saw
 *       bytecode would have to infer the same thing heuristically, so it lowers precision.
 *   <li>A Kotlin {@code data class}'s {@code componentN()} accessors are compiler-generated. A
 *       "dead code" checker must not report them as unused private methods.
 * </ul>
 *
 * <p><b>Directional rule -- read this before using this enum.</b>
 *
 * <p>Precision may be used to <b>suppress a false positive</b>. It must <b>never</b> be used to
 * <b>prove that code is safe</b>. The reasoning:
 *
 * <ul>
 *   <li>A missing null check does not imply the value is non-null at runtime: {@code
 *       -Xno-param-assertions} and {@code -Xno-call-assertions} strip Kotlin's compiler-inserted
 *       checks, and ProGuard/R8's {@code -assumenosideeffects} strips them from bytecode.
 *   <li>"The bytecode has a check" therefore does not imply "the runtime is safe", and neither does
 *       "the type system says non-null" imply "this particular call site cannot receive null" --
 *       platform types from Java interop still can.
 * </ul>
 *
 * <p>Inverting this rule hides real vulnerabilities, which is strictly worse than a false positive.
 */
public enum IrPrecision {

  /**
   * Full source semantics: annotations (including parameter-level), nullability, exact line
   * numbers, and lambda bodies are all present.
   *
   * <p>This is the default for a source frontend that had access to the type system.
   */
  SOURCE_FULL,

  /**
   * Source semantics, but the language construct itself was lowered away.
   *
   * <p>Examples: a Kotlin {@code when} expression degraded into a branch chain; a Kotlin {@code
   * suspend fun} whose state machine was flattened into ordinary control flow; a Scala {@code
   * match} degraded into a branch chain.
   *
   * <p>Semantics are preserved -- no information about <i>what the code does</i> is lost -- but
   * structural information (this was a {@code when}, this was a {@code match}) is gone. Checkers
   * that key on structure (e.g. "exhaustive switch" checks) must not fire here.
   */
  SOURCE_LOWERED,

  /**
   * Bytecode semantics: annotations and line numbers may be incomplete, and lambda bodies are
   * invisible because they live in an {@code invokedynamic} bootstrap method.
   *
   * <p>Used by the {@code .jar} analysis path. Not used for user source.
   */
  BYTECODE,

  /**
   * Bytecode semantics on a JVM-synthesised method: a lambda body, an accessor, a bridge method, a
   * Kotlin {@code copy$default}, and so on.
   *
   * <p>These have no counterpart in any source file, so a checker must not report findings that
   * point at them as if they were user-written code.
   */
  BYTECODE_SYNTHETIC;

  /** Whether this precision level preserves the original source structure (not just semantics). */
  public boolean hasSourceStructure() {
    return this == SOURCE_FULL;
  }

  /** Whether lambda bodies are visible at this precision level. */
  public boolean seesLambdaBodies() {
    return this == SOURCE_FULL || this == SOURCE_LOWERED;
  }

  /**
   * Whether parameter-level annotations are visible at this precision level.
   *
   * <p>Bytecode keeps method annotations in {@code RuntimeVisibleAnnotations}, but a parameter
   * annotation has no slot of its own in the class file format -- it is only preserved in the
   * optional {@code MethodParameters} attribute -- so it is not reliably recoverable.
   */
  public boolean seesParameterAnnotations() {
    return this == SOURCE_FULL || this == SOURCE_LOWERED;
  }

  /**
   * Whether a null-dereference finding may be suppressed on the strength of this precision alone.
   *
   * <p>Only source-level precision qualifies: the type system states a fact about the declaration.
   * Bytecode-level precision has to guess, and a guess that errs on the side of "safe" hides real
   * bugs.
   */
  public boolean maySuppressNullDeref() {
    return this == SOURCE_FULL || this == SOURCE_LOWERED;
  }
}
