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

import java.io.Serializable;
import java.util.Objects;

/**
 * A record of ONE thing a frontend could not lower faithfully.
 *
 * <p><b>What a drop is for.</b> A drop is not a fallback channel. It is the receipt for a construct
 * that the TAC cannot represent exactly, so that the loss is VISIBLE instead of SILENT. A frontend
 * that cannot lower a construct must emit a drop; a frontend that lowers it must not. The engine
 * derives a function's precision from these receipts, and the coverage report counts them.
 *
 * <p><b>Why this matters.</b> Before drops existed, the only way a frontend could say "I did not
 * manage this" was to emit nothing at all. Nothing is indistinguishable from "there was nothing
 * here" — so a construct that was silently dropped looked like a construct that did not exist, and
 * the engine reported nothing about it. That is the worst possible failure mode for a security
 * tool: a missing check reads as a clean result.
 *
 * <p><b>The four impacts.</b> The impact says WHAT was lost, and it is what decides the function's
 * precision (see {@link TacFunction#derivePrecision}):
 *
 * <ul>
 *   <li>{@link Impact#SEMANTICS} — the semantics of the construct are lost. The value the construct
 *       produces, or the target it calls, is not knowable from the source. A checker that would
 *       have concluded something from the construct must now conclude nothing.
 *   <li>{@link Impact#STRUCTURE} — only the SHAPE is lost; what the code does is still known. A
 *       {@code when} lowered into a branch chain still computes the same value, but the fact that
 *       it was a {@code when} is gone, so an "exhaustive switch" checker must not fire on it.
 *   <li>{@link Impact#VALUE} — exactly ONE value is unknown (the result of {@code eval}, the URL
 *       behind {@code import.meta.url}). Everything else about the construct is intact.
 *   <li>{@link Impact#TYPE} — only type-level information is lost. The runtime behaviour is
 *       unaffected, so a runtime analysis is not degraded by it.
 * </ul>
 *
 * <p><b>Directional rule.</b> A drop may only make the engine more cautious. It may never be used
 * to argue that code is safe.
 *
 * <p>IR-serializable: this class participates in the disk-cache contract (IrSerializer). The field
 * names are part of that contract — a rename is a breaking change.
 */
public final class TacDrop implements Serializable {

  private static final long serialVersionUID = 1L;

  /**
   * What kind of information the drop lost.
   *
   * <p>Enum constants are serialised by name, so ADDING a constant at the end is backwards
   * compatible. Reordering or renaming a constant is NOT.
   */
  public enum Impact {
    /** The construct's semantics are lost. Lowers the function to SOURCE_DEGRADED. */
    SEMANTICS,
    /** Only the construct's shape is lost. Lowers the function to SOURCE_LOWERED. */
    STRUCTURE,
    /** Exactly one value is unknown. The function's precision is unchanged. */
    VALUE,
    /** Only type-level information is lost. The function's precision is unchanged. */
    TYPE
  }

  /**
   * How far the drop reaches.
   *
   * <p>A {@link #FUNCTION} drop is about one function's body. A {@link #PROGRAM} drop is about the
   * whole translation unit — a file that could not be parsed, a dependency that was not resolved, a
   * source-level capability the engine does not have.
   */
  public enum Scope {
    /** The drop is about the function that carries it. */
    FUNCTION,
    /** The drop is about the whole program / translation unit. */
    PROGRAM
  }

  private final String construct;
  private final String detail;
  private final Impact impact;
  private final int line;
  private final int column;
  private final Scope scope;

  /**
   * A drop with no source position.
   *
   * <p>Use this for PROGRAM-scope drops and for constructs whose position the frontend does not
   * have (a build-time artefact, for instance).
   */
  public TacDrop(String construct, String detail, Impact impact) {
    this(construct, detail, impact, 0, 0, Scope.FUNCTION);
  }

  /**
   * A drop at a source position.
   *
   * @param construct a stable, machine-readable id for the construct that was not lowered — for
   *     example {@code "reflect.invoke"} or {@code "inline.notExpanded"}. This id is what a
   *     coverage report groups by, so it must be stable and must not embed a variable part.
   * @param detail a human-readable explanation of THIS occurrence. May be empty.
   * @param impact what kind of information was lost.
   * @param line 1-based source line, or 0 when unknown.
   * @param column 1-based source column, or 0 when unknown.
   * @param scope how far the drop reaches.
   */
  public TacDrop(
      String construct, String detail, Impact impact, int line, int column, Scope scope) {
    this.construct = Objects.requireNonNull(construct);
    this.detail = detail == null ? "" : detail;
    this.impact = Objects.requireNonNull(impact);
    this.line = line;
    this.column = column;
    this.scope = scope == null ? Scope.FUNCTION : scope;
  }

  /** The stable id of the construct that was not lowered. Never null. */
  public String construct() {
    return construct;
  }

  /** A human-readable explanation of this occurrence. Never null; may be empty. */
  public String detail() {
    return detail;
  }

  /** What kind of information was lost. Never null. */
  public Impact impact() {
    return impact;
  }

  /** 1-based source line, or 0 when unknown. */
  public int line() {
    return line;
  }

  /** 1-based source column, or 0 when unknown. */
  public int column() {
    return column;
  }

  /** How far this drop reaches. Never null. */
  public Scope scope() {
    return scope;
  }

  /** Whether this drop loses semantics (as opposed to only shape, value, or type). */
  public boolean losesSemantics() {
    return impact == Impact.SEMANTICS;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof TacDrop)) {
      return false;
    }
    TacDrop other = (TacDrop) o;
    return line == other.line
        && column == other.column
        && construct.equals(other.construct)
        && detail.equals(other.detail)
        && impact == other.impact
        && scope == other.scope;
  }

  @Override
  public int hashCode() {
    return Objects.hash(construct, detail, impact, line, column, scope);
  }

  @Override
  public String toString() {
    return "TacDrop("
        + construct
        + ", "
        + impact
        + (line > 0 ? " @" + line + ":" + column : "")
        + ", "
        + detail
        + ")";
  }
}
