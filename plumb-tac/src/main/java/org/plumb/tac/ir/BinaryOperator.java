package org.plumb.tac.ir;

// IR-serializable: this class participates in the disk-cache contract.
// Instances are written via IrSerializer (ObjectOutputStream) and read
// back via IrSerializer.read(...). Field additions are backwards
// compatible; renaming or removing fields needs a serialVersionUID bump.

/** Binary operators used by {@code BinOp} instructions. */
public enum BinaryOperator implements java.io.Serializable {
  // arithmetic
  ADD,
  SUB,
  MUL,
  DIV,
  MOD,
  // bitwise
  AND,
  OR,
  XOR,
  SHL,
  SHR,
  USHR,
  // comparison
  EQ,
  NE,
  LT,
  LE,
  GT,
  GE,
  // short-circuit
  LAND,
  LOR;

  private static final long serialVersionUID = 1L;
}
