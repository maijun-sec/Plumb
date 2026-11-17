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
  LOR,
  // loose equality: the language coerces the operands before comparing them
  // (JavaScript's `==`, Python's `==` on mixed types). They are NOT the same
  // comparison as EQ: `0 == "0"` is true under loose equality and false under
  // strict equality, so a rule that reads a loose comparison as a strict one
  // draws conclusions the program does not support.
  LOOSE_EQ,
  LOOSE_NE;

  private static final long serialVersionUID = 1L;
}
