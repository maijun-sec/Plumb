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

import java.util.List;
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
    /**
     * A fixed-point decimal.
     *
     * <p>C# has a distinct {@code decimal} type, and it is not a floating-point number: it is a
     * 128-bit fixed-point value with a scale, and arithmetic on it is exact where {@code double} is
     * not. Folding it into {@link #DOUBLE} would be a silent change of value, so it gets its own
     * kind.
     */
    DECIMAL,
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
  public static final TacType DECIMAL = new TacType(Kind.DECIMAL, null);
  public static final TacType STRING = new TacType(Kind.STRING, null);
  public static final TacType NULL = new TacType(Kind.NULL, null);
  public static final TacType VOID = new TacType(Kind.VOID, null);
  public static final TacType UNKNOWN = new TacType(Kind.UNKNOWN, null);

  public static TacType ofClass(String fqn) {
    return new TacType(Kind.CLASS, Objects.requireNonNull(fqn));
  }

  /** A statically non-nullable class reference. */
  public static TacType nonNullClass(String fqn) {
    return new TacType(Kind.CLASS, Objects.requireNonNull(fqn), false);
  }

  public static TacType arrayOf(TacType component) {
    return new TacType(Kind.ARRAY, Objects.requireNonNull(component));
  }

  /** A statically non-nullable array reference. */
  public static TacType nonNullArray(TacType component) {
    return new TacType(Kind.ARRAY, Objects.requireNonNull(component), false);
  }

  private final Kind kind;

  /** For {@code CLASS}: the fqn; for {@code ARRAY}: the component type. */
  private final Object payload;

  /**
   * Whether a reference of this type may hold {@code null}.
   *
   * <p><b>The default is {@code true}</b>, which is Java's semantics: any reference type may hold
   * null. Every existing singleton constant, {@link #ofClass} and {@link #arrayOf} therefore
   * produce a nullable type, so Java analysis behaves exactly as it did before this field existed.
   *
   * <p>A source frontend that read a language's type system sets this to {@code false} when the
   * type is statically non-nullable (Kotlin's {@code String} as opposed to {@code String?}). That
   * lets the null-dereference checker suppress a finding.
   *
   * <p><b>Directional rule.</b> {@code nullable == false} is a statement about the
   * <i>declaration</i>, not about a particular runtime value. It may be used to suppress a false
   * positive; it must never be used to prove that a call site cannot receive null (a Java method's
   * return type seen from Kotlin is a platform type and still may be null).
   *
   * <p>Note that {@link Kind#NULL} is a different concept: it means "this value <i>is</i> the null
   * literal" (a value-level fact), whereas this field means "a reference of this type <i>may</i>
   * hold null" (a type-level fact). The two must not be conflated.
   */
  private final boolean nullable;

  /**
   * What the solver may assume about this type's numbers, or {@code null} when nothing is known.
   *
   * <p>{@code null} is the default for every type built today, so the engine's behaviour is
   * unchanged until a frontend states a domain. That is deliberate: an absent domain means "make no
   * assumption", which is the safe reading.
   *
   * <p>A domain never makes the solver's job easier by lying -- it says what the type IS, and the
   * solver uses it to know when its own integer model is an approximation. See {@link
   * TacValueDomain}.
   */
  private final TacValueDomain domain;

  /**
   * The members of a UNION type, or an empty list when this is not a union.
   *
   * <p><b>Why the members and not a name.</b> A union has to be recorded as the set of types it is
   * made of. Splicing the member names into one string ({@code "A|B"}) produces a type name that
   * matches no type in the program, so every lookup of it fails and every rule that fires on it
   * fires on nothing. The members are kept as types, so a rule can ask "is any member of this union
   * a buffer?" and get an answer.
   *
   * <p>Empty for every type built before this field existed, so equality and hashing are unchanged
   * for them.
   */
  private final List<TacType> unionMembers;

  private TacType(Kind kind, Object payload) {
    this(kind, payload, true, null);
  }

  private TacType(Kind kind, Object payload, boolean nullable) {
    this(kind, payload, nullable, null);
  }

  private TacType(Kind kind, Object payload, boolean nullable, TacValueDomain domain) {
    this(kind, payload, nullable, domain, null);
  }

  private TacType(
      Kind kind, Object payload, boolean nullable, TacValueDomain domain, List<TacType> union) {
    this.kind = kind;
    this.payload = payload;
    this.nullable = nullable;
    this.domain = domain;
    this.unionMembers = union == null || union.isEmpty() ? List.of() : List.copyOf(union);
  }

  /**
   * Returns a copy of this type carrying the given UNION members.
   *
   * <p>Returns {@code this} when the members are already the requested ones. Passing an empty list
   * removes the union -- the same as never having had one.
   */
  public TacType withUnionMembers(List<TacType> members) {
    List<TacType> next = members == null ? List.of() : List.copyOf(members);
    if (next.isEmpty() && unionMembers.isEmpty()) return this;
    if (next.equals(unionMembers)) return this;
    return new TacType(kind, payload, nullable, domain, next);
  }

  /**
   * The members of this union type, or an empty list when this is not a union.
   *
   * <p>Never null.
   */
  public List<TacType> unionMembers() {
    return unionMembers == null ? List.of() : unionMembers;
  }

  /**
   * Returns a copy of this type carrying the given value domain.
   *
   * <p>Returns {@code this} when the domain is already the requested one, so repeated calls are
   * cheap and the singleton constants stay canonical.
   *
   * <p>Passing {@code null} removes the domain -- the same as never having stated one.
   */
  public TacType withDomain(TacValueDomain value) {
    if (Objects.equals(domain, value)) return this;
    // The union members come LAST: the five-argument constructor's fifth
    // parameter is the union, not the domain. Leaving them out here dropped
    // the union whenever a domain was stated -- and the union is what says
    // "this value is one of these types".
    return new TacType(kind, payload, nullable, value, unionMembers);
  }

  /** The value domain of this type, or {@code null} when nothing is known about it. */
  public TacValueDomain domain() {
    return domain;
  }

  /**
   * Returns a copy of this type marked statically non-nullable.
   *
   * <p>Returns {@code this} when the type is already non-nullable, so repeated calls are cheap and
   * the singleton constants stay canonical.
   */
  public TacType nonNull() {
    if (!nullable) return this;
    return new TacType(kind, payload, false, domain, unionMembers);
  }

  /**
   * Returns a copy of this type marked nullable.
   *
   * <p>The inverse of {@link #nonNull()}; used when a frontend learns that a platform type (a Java
   * return value seen from Kotlin) may in fact be null.
   */
  public TacType nullable() {
    if (nullable) return this;
    return new TacType(kind, payload, true, domain, unionMembers);
  }

  /** Whether a reference of this type may hold {@code null}. */
  public boolean isNullable() {
    return nullable;
  }

  public Kind kind() {
    return kind;
  }

  public String className() {
    if (kind != Kind.CLASS) throw new IllegalStateException("not CLASS: " + kind);
    return (String) payload;
  }

  /**
   * The type's name, without the nullability marker.
   *
   * <p>{@link #toString()} is a <i>display</i> form: it appends {@code " (non-null)"} to a
   * non-nullable reference so that a dump explains why a null-dereference finding was suppressed.
   * That decoration makes it unusable as a <i>key</i>: the field-taint tracker keys its per-class
   * maps on the class name, so a decorated key would not match the undecorated key that a different
   * code path produced -- and the mismatch is silent.
   *
   * <p>So any code that needs the type's name for identity must use this method, not {@link
   * #toString()}.
   */
  public String displayName() {
    return switch (kind) {
      case BOOLEAN -> "boolean";
      case BYTE -> "byte";
      case SHORT -> "short";
      case CHAR -> "char";
      case INT -> "int";
      case LONG -> "long";
      case FLOAT -> "float";
      case DOUBLE -> "double";
      case DECIMAL -> "decimal";
      case STRING -> "java.lang.String";
      case CLASS -> className();
      case ARRAY -> arrayComponent() + "[]";
      case NULL -> "<null>";
      case VOID -> "void";
      case UNKNOWN -> "<unknown>";
    };
  }

  public TacType arrayComponent() {
    if (kind != Kind.ARRAY) throw new IllegalStateException("not ARRAY: " + kind);
    return (TacType) payload;
  }

  public boolean isPrimitive() {
    return switch (kind) {
      case BOOLEAN, BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE, DECIMAL -> true;
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
    String base = displayName();
    // A non-nullable reference is worth showing: it is the marker the null-dereference checker
    // reads, and seeing it in a dump explains why a finding was suppressed.
    if (!nullable && isReference()) base = base + " (non-null)";
    if (domain != null) base = base + " " + domain;
    return base;
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof TacType t)) return false;
    if (kind != t.kind) return false;
    // Nullability participates in equality: a nullable "java.lang.String" and a non-nullable
    // "java.lang.String" are different types as far as the null-dereference checker is concerned.
    // Existing code that compares a resolved type against a singleton constant (e.g.
    // TacType.STRING) is unaffected, because every singleton is nullable and every type built
    // through ofClass/arrayOf is nullable too.
    if (nullable != t.nullable) return false;
    // The value domain participates in equality, for the same reason nullability does: two types
    // with different domains are different types as far as the solver is concerned. A float and a
    // double have different Kind, but a double with no domain and a double that says
    // Floating(53,11) are NOT the same type -- the first is "a number of unknown precision", the
    // second is "an IEEE-754 double". Collapsing them would let a lookup for one find the other.
    // Existing code is unaffected: every type built today has a null domain.
    if (!Objects.equals(domain, t.domain)) return false;
    // The union members participate for the same reason: a "string | null" and
    // a plain "string" are different types, and collapsing them would let a
    // lookup for one find the other.
    if (!unionMembers().equals(t.unionMembers())) return false;
    return Objects.equals(payload, t.payload);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, payload, nullable, domain, unionMembers);
  }
}
