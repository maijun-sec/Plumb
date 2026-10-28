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
 * Simplified Java type. We model only what the dataflow engine needs: primitives, a tagged class
 * reference, arrays, and the {@code void} / {@code null} / {@code unknown} sentinels.
 *
 * <p>Type precision is intentionally not 1:1 with the Java type system. A {@code Map<String,
 * List<Integer>>} is just a {@code CLASS("java.util.Map")}; generics are erased.
 */
public final class TacType implements java.io.Serializable {
  private static final long serialVersionUID = 1L;

  public enum Kind {
    BOOLEAN,
    BYTE,
    SHORT,
    CHAR,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    STRING, // treated as reference, but we keep a fast path
    CLASS, // reference: non-array, non-String
    ARRAY,
    NULL,
    VOID,
    UNKNOWN
  }

  public static final TacType BOOLEAN = new TacType(Kind.BOOLEAN, null);
  public static final TacType BYTE = new TacType(Kind.BYTE, null);
  public static final TacType SHORT = new TacType(Kind.SHORT, null);
  public static final TacType CHAR = new TacType(Kind.CHAR, null);
  public static final TacType INT = new TacType(Kind.INT, null);
  public static final TacType LONG = new TacType(Kind.LONG, null);
  public static final TacType FLOAT = new TacType(Kind.FLOAT, null);
  public static final TacType DOUBLE = new TacType(Kind.DOUBLE, null);
  public static final TacType STRING = new TacType(Kind.STRING, null);
  public static final TacType NULL = new TacType(Kind.NULL, null);
  public static final TacType VOID = new TacType(Kind.VOID, null);
  public static final TacType UNKNOWN = new TacType(Kind.UNKNOWN, null);

  public static TacType ofClass(String fqn) {
    return new TacType(Kind.CLASS, Objects.requireNonNull(fqn));
  }

  public static TacType arrayOf(TacType component) {
    return new TacType(Kind.ARRAY, Objects.requireNonNull(component));
  }

  private final Kind kind;

  /** For {@code CLASS}: the fqn; for {@code ARRAY}: the component type. */
  private final Object payload;

  private TacType(Kind kind, Object payload) {
    this.kind = kind;
    this.payload = payload;
  }

  public Kind kind() {
    return kind;
  }

  public String className() {
    if (kind != Kind.CLASS) throw new IllegalStateException("not CLASS: " + kind);
    return (String) payload;
  }

  public TacType arrayComponent() {
    if (kind != Kind.ARRAY) throw new IllegalStateException("not ARRAY: " + kind);
    return (TacType) payload;
  }

  public boolean isPrimitive() {
    return switch (kind) {
      case BOOLEAN, BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE -> true;
      default -> false;
    };
  }

  public boolean isReference() {
    return switch (kind) {
      case STRING, CLASS, ARRAY -> true;
      default -> false;
    };
  }

  @Override
  public String toString() {
    return switch (kind) {
      case BOOLEAN -> "boolean";
      case BYTE -> "byte";
      case SHORT -> "short";
      case CHAR -> "char";
      case INT -> "int";
      case LONG -> "long";
      case FLOAT -> "float";
      case DOUBLE -> "double";
      case STRING -> "java.lang.String";
      case CLASS -> className();
      case ARRAY -> arrayComponent() + "[]";
      case NULL -> "<null>";
      case VOID -> "void";
      case UNKNOWN -> "<unknown>";
    };
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof TacType t)) return false;
    if (kind != t.kind) return false;
    return Objects.equals(payload, t.payload);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, payload);
  }
}
