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

import java.util.Objects;

// IR-serializable: this class participates in the disk-cache contract.
// Instances are written via IrSerializer (ObjectOutputStream) and read
// back via IrSerializer.read(...). Field additions are backwards
// compatible; renaming or removing fields needs a serialVersionUID bump.
/**
 * Operand of a TAC instruction. Either a {@link TacVar} (named storage location) or a literal
 * constant.
 */
public sealed interface TacOperand extends java.io.Serializable
    permits TacOperand.TacVar, TacOperand.Const {
  long serialVersionUID = 1L;

  /** Named storage: parameter, local, or a synthetic temporary. */
  final class TacVar implements TacOperand {
    private final String name;
    private final TacType type;
    private final boolean isTemp;

    public TacVar(String name, TacType type, boolean isTemp) {
      this.name = Objects.requireNonNull(name);
      this.type = type == null ? TacType.UNKNOWN : type;
      this.isTemp = isTemp;
    }

    public String name() {
      return name;
    }

    public TacType type() {
      return type;
    }

    public boolean isTemp() {
      return isTemp;
    }

    @Override
    public String toString() {
      // temps use "t_t<N>" raw names now. The toString
      // is just the raw name + ":" + type — no more prefix
      // (the prefix was only there to disambiguate "t2" from
      // a local with the same name; the raw name itself is
      // now unambiguous).
      return name + ":" + type;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof TacVar v)) return false;
      return name.equals(v.name) && type.equals(v.type) && isTemp == v.isTemp;
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, type, isTemp);
    }
  }

  /** Literal constant: int / long / float / double / boolean / char / String / null. */
  final class Const implements TacOperand {
    public enum Kind {
      INT,
      LONG,
      FLOAT,
      DOUBLE,
      BOOL,
      CHAR,
      STRING,
      NULL
    }

    private final Kind kind;
    private final Object value;
    private final TacType type;

    public Const(Kind kind, Object value, TacType type) {
      this.kind = Objects.requireNonNull(kind);
      this.value = value;
      this.type = Objects.requireNonNull(type);
    }

    public static Const ofInt(int v) {
      return new Const(Kind.INT, v, TacType.INT);
    }

    public static Const ofLong(long v) {
      return new Const(Kind.LONG, v, TacType.LONG);
    }

    public static Const ofFloat(float v) {
      return new Const(Kind.FLOAT, v, TacType.FLOAT);
    }

    public static Const ofDouble(double v) {
      return new Const(Kind.DOUBLE, v, TacType.DOUBLE);
    }

    public static Const ofBool(boolean v) {
      return new Const(Kind.BOOL, v, TacType.BOOLEAN);
    }

    public static Const ofChar(char v) {
      return new Const(Kind.CHAR, v, TacType.CHAR);
    }

    public static Const ofString(String v) {
      return new Const(Kind.STRING, v, TacType.STRING);
    }

    public static Const ofNull() {
      return new Const(Kind.NULL, null, TacType.NULL);
    }

    public Kind kind() {
      return kind;
    }

    public Object value() {
      return value;
    }

    public TacType type() {
      return type;
    }

    @Override
    public String toString() {
      return switch (kind) {
        case STRING -> "\"" + value + "\"";
        case CHAR -> "'" + value + "'";
        case NULL -> "null";
        default -> String.valueOf(value);
      };
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Const c)) return false;
      return kind == c.kind && Objects.equals(value, c.value);
    }

    @Override
    public int hashCode() {
      return Objects.hash(kind, value);
    }
  }
}
