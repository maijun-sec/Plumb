package org.plumb.tac.ir;

// IR-serializable: this class participates in the disk-cache contract.
// Instances are written via IrSerializer (ObjectOutputStream) and read
// back via IrSerializer.read(...). Field additions are backwards
// compatible; renaming or removing fields needs a serialVersionUID bump.

/** Unary operators used by {@code UnaryOp} instructions. */
public enum UnaryOperator implements java.io.Serializable {
  NEG, // -x
  NOT, // !x  (boolean)
  BITNOT, // ~x
  PRE_INC, // ++x
  PRE_DEC, // --x
  POST_INC, // x++
  POST_DEC, // x--
  CAST; // (T) x — payload is the target TacType on the instruction

  private static final long serialVersionUID = 1L;
}
