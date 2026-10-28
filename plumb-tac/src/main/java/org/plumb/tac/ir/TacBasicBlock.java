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

import java.util.Collections;
import java.util.List;

// IR-serializable: this class participates in the disk-cache contract.
// Instances are written via IrSerializer (ObjectOutputStream) and read
// back via IrSerializer.read(...). Field additions are backwards
// compatible; renaming or removing fields needs a serialVersionUID bump.
/**
 * A basic block: a maximal consecutive run of instructions with a single entry (the first) and a
 * single exit (the last). Each block owns the {@code indices} of the {@link TacInstruction}s it
 * contains (in the parent {@link TacFunction}'s instruction list).
 */
public final class TacBasicBlock implements java.io.Serializable {
  private static final long serialVersionUID = 1L;

  private final int id;
  private final List<Integer> indices;

  public TacBasicBlock(int id, List<Integer> indices) {
    this.id = id;
    this.indices = List.copyOf(indices);
  }

  public int id() {
    return id;
  }

  public List<Integer> indices() {
    return Collections.unmodifiableList(indices);
  }

  public int firstIndex() {
    return indices.get(0);
  }

  public int lastIndex() {
    return indices.get(indices.size() - 1);
  }

  public boolean isEmpty() {
    return indices.isEmpty();
  }

  @Override
  public String toString() {
    return "B" + id + "(insns " + firstIndex() + "-" + lastIndex() + ")";
  }
}
