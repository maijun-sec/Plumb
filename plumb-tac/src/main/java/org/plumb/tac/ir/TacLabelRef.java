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
 * A structured reference to a label.
 *
 * <p>The TAC has always been able to say "jump to L1". What it could not say is <i>what L1 is</i>:
 * a loop head, a switch case, a catch handler, a finally entry, or just a block. A checker that
 * needs to know "does this branch leave the loop it is in?" can work that out from the instruction
 * stream, but only by re-deriving what the front end already knew.
 *
 * <p>A label reference carries that knowledge as data. It does not change what the instruction
 * does; it makes the shape of the control flow visible.
 *
 * <p>IR-serializable: this class participates in the disk-cache contract (IrSerializer).
 */
public final class TacLabelRef implements Serializable {

  private static final long serialVersionUID = 1L;

  /** What kind of place the label names. */
  public enum Kind {
    /** The head of a loop -- the label a loop's back-edge jumps to. */
    LOOP_HEAD,
    /** The end of a loop -- the label a {@code break} jumps to. */
    LOOP_END,
    /** One case of a switch. */
    SWITCH_CASE,
    /** The end of a switch. */
    SWITCH_END,
    /** An ordinary block. */
    BLOCK,
    /** A catch handler's entry. */
    CATCH,
    /** A finally block's entry. */
    FINALLY_ENTRY,
    /**
     * A state a suspended execution resumes in.
     *
     * <p>A generator's {@code yield} leaves the function at a suspend point and the execution comes
     * back at a label like this one; see {@link TacResumePoint}.
     */
    RESUME,
    /** A label whose purpose the front end could not classify. */
    OTHER
  }

  private final String name;
  private final Kind kind;
  private final String scopeId;

  public TacLabelRef(String name, Kind kind, String scopeId) {
    this.name = name;
    this.kind = kind == null ? Kind.OTHER : kind;
    this.scopeId = scopeId;
  }

  /** The label's name, or {@code null} when the front end did not name it. */
  public String name() {
    return name;
  }

  /** What kind of place the label names. Never null. */
  public Kind kind() {
    return kind;
  }

  /**
   * The scope the label belongs to, or {@code null} when the front end did not say.
   *
   * <p>Two labels in different scopes may share a name, so a reference that does not say which
   * scope it is in does not identify a target.
   */
  public String scopeId() {
    return scopeId;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof TacLabelRef r)) {
      return false;
    }
    return kind == r.kind && Objects.equals(name, r.name) && Objects.equals(scopeId, r.scopeId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(name, kind, scopeId);
  }

  @Override
  public String toString() {
    return kind + "(" + name + ")";
  }
}
