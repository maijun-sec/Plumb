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
 * A RESUME POINT: the place a suspended execution continues from.
 *
 * <p>A generator's {@code yield} and a coroutine's {@code suspend} both leave the current execution
 * behind: the caller gets control back, and the execution comes back later. {@link
 * TacInstruction.Effect#SUSPEND} marks the instruction where the execution leaves; this class names
 * the places it may come back to.
 *
 * <p><b>Why the two are separate.</b> The effect is a property of ONE instruction; the resume point
 * is a property of the FUNCTION. A generator with three yields suspends three times and can resume
 * at three different places -- and which one is reached depends on the caller, which is not in the
 * TAC. Putting the resume points on the function keeps that fact in the right place.
 *
 * <p><b>What the engine may conclude.</b> A resume point is not a normal branch target: control
 * arrives there from OUTSIDE the function, not from the instruction before it. The builder turns
 * that into an ordinary CFG edge, so the engine's usual merge at that block is the merge of the
 * states at every suspend point that can lead to it -- an over-approximation, and the honest one,
 * because the caller chooses the order.
 *
 * <p>IR-serializable: this class participates in the disk-cache contract (IrSerializer).
 */
public final class TacResumePoint implements Serializable {

  private static final long serialVersionUID = 1L;

  /** Which kind of resume this is. */
  public enum Kind {
    /**
     * The execution resumes because the caller asked the suspended execution to continue.
     *
     * <p>A generator's {@code yield} and a coroutine's {@code suspend} are this kind.
     */
    SUSPEND,
    /**
     * The execution resumes because an exception unwound to here.
     *
     * <p>No front end emits this kind yet: an exception thrown into a suspended generator lands on
     * the {@code catch} handler, which the exception flow already owns. The kind is here because
     * the shape is known, and a kind that is not needed yet must not be invented later under
     * pressure.
     */
    EXCEPTION
  }

  private final String label;
  private final Kind kind;

  private TacResumePoint(String label, Kind kind) {
    this.label = Objects.requireNonNull(label, "label");
    this.kind = Objects.requireNonNull(kind, "kind");
  }

  /** A resume point the caller drives: the execution continues from {@code label}. */
  public static TacResumePoint suspend(String label) {
    return new TacResumePoint(label, Kind.SUSPEND);
  }

  /** A resume point an exception reaches: the execution continues from {@code label}. */
  public static TacResumePoint exception(String label) {
    return new TacResumePoint(label, Kind.EXCEPTION);
  }

  /** A resume point of the given kind. */
  public static TacResumePoint of(String label, Kind kind) {
    return new TacResumePoint(label, kind);
  }

  /**
   * The label the execution continues from.
   *
   * <p>This is the name of a {@code LABEL} instruction in the same function.
   */
  public String label() {
    return label;
  }

  /** Which kind of resume this is. Never null. */
  public Kind kind() {
    return kind;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof TacResumePoint p)) {
      return false;
    }
    return label.equals(p.label) && kind == p.kind;
  }

  @Override
  public int hashCode() {
    return Objects.hash(label, kind);
  }

  @Override
  public String toString() {
    return kind + "(" + label + ")";
  }
}
