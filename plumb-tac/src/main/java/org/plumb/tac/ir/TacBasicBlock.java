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
