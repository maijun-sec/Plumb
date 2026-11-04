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
package org.plumb.tac.frontend.scala;

import dotty.tools.dotc.core.Contexts.Context;
import dotty.tools.dotc.core.Types.Type;
import org.plumb.tac.ir.TacType;

/**
 * Maps Scala 3 types onto {@link TacType}.
 *
 * <p><b>Why the mapping is lossy, and why that is fine.</b> The TAC models only what the
 * dataflow engine needs: primitives, a tagged class reference, arrays, and the {@code void} /
 * {@code null} / {@code unknown} sentinels. A Scala {@code Map[String, List[Int]]} is just
 * {@code CLASS("scala.collection.immutable.Map")}; generics are erased. That is the same
 * trade the Java frontend makes, so a checker sees one shape regardless of the source
 * language.
 *
 * <p><b>Nullability.</b> Scala has no non-null type system. {@code String} and {@code
 * String?} do not exist as distinct types; every reference may hold {@code null}. So every
 * reference this mapper produces stays <b>nullable</b>, which is the {@link TacType}
 * default. The frontend therefore never claims a Scala value is non-null, and a
 * null-dereference finding on Scala code is reported exactly as it would be for Java.
 *
 * <p><b>{@code Option} is not special.</b> {@code Option[T]} is an ordinary class
 * reference ({@code scala.Option}), not a null-safety mechanism. Treating it as one would
 * let a null-dereference checker prove safety from an {@code Option} -- which the
 * "nullability may only suppress, never prove" rule forbids.
 *
 * <p><b>Arrays.</b> {@code Array[Int]} maps to {@code TacType.arrayOf(INT)}. Scala's
 * {@code Array} is a Java array at runtime, so the array component type is what the
 * dataflow engine needs.
 */
final class ScalaTypeMapper {

  /** The compiler context the types are read in. */
  private final Context ctx;

  ScalaTypeMapper(Context ctx) {
    this.ctx = ctx;
  }

  /**
   * Maps a Scala type onto a TAC type.
   *
   * @param type the Scala type; {@code null} maps to {@link TacType#UNKNOWN}
   * @return the TAC type; never null
   */
  TacType map(Type type) {
    if (type == null) {
      return TacType.UNKNOWN;
    }
    try {
      return mapDealiased(type.dealias(ctx));
    } catch (RuntimeException ex) {
      // A type the compiler cannot resolve (an error type, a type from a missing
      // dependency) must not abort the frontend. An unknown type is the honest answer.
      return TacType.UNKNOWN;
    }
  }

  /** Maps a type that has already been de-aliased. */
  private TacType mapDealiased(Type type) {
    String fqn = className(type);
    if (fqn == null) {
      return TacType.UNKNOWN;
    }
    switch (fqn) {
      case "scala.Boolean":
        return TacType.BOOLEAN;
      case "scala.Byte":
        return TacType.BYTE;
      case "scala.Short":
        return TacType.SHORT;
      case "scala.Char":
        return TacType.CHAR;
      case "scala.Int":
        return TacType.INT;
      case "scala.Long":
        return TacType.LONG;
      case "scala.Float":
        return TacType.FLOAT;
      case "scala.Double":
        return TacType.DOUBLE;
      case "scala.Unit":
        return TacType.VOID;
      case "java.lang.String":
        return TacType.STRING;
      case "scala.Any":
      case "scala.AnyRef":
      case "java.lang.Object":
        return TacType.ofClass(fqn);
      case "scala.Null":
        return TacType.NULL;
      default:
        break;
    }
    if (isArray(type)) {
      Type component = arrayComponent(type);
      return component == null ? TacType.ofClass(fqn) : TacType.arrayOf(map(component));
    }
    // Everything else -- Option, List, a user class -- is a plain class reference.
    return TacType.ofClass(fqn);
  }

  /**
   * The fully-qualified name of a type's class, or {@code null} when the type has none.
   *
   * <p>{@code classSymbol} already resolves the aliases that matter: Scala's {@code
   * String} is {@code scala.Predef.String}, whose class symbol is {@code
   * java.lang.String}. That is the name the TAC wants, because it is the name the
   * bytecode and the Java frontend use for the same type.
   */
  private String className(Type type) {
    dotty.tools.dotc.core.Symbols.Symbol symbol = type.classSymbol(ctx);
    if (symbol == null) {
      return null;
    }
    String name = symbol.denot(ctx).fullName(ctx).toString();
    return name == null || name.isEmpty() ? null : name;
  }

  /**
   * Whether the type is an array type.
   *
   * <p>Scala's {@code Array[T]} is {@code scala.Array} applied to a type argument. It is
   * not a Java array type in the type system, so the check cannot be "is it a Java
   * array" -- it is "is the class symbol the Array class".
   */
  private boolean isArray(Type type) {
    dotty.tools.dotc.core.Symbols.Symbol symbol = type.classSymbol(ctx);
    return symbol != null
        && "scala.Array".equals(symbol.denot(ctx).fullName(ctx).toString());
  }

  /**
   * The component type of an array type, or {@code null} when it cannot be read.
   *
   * <p>The component type is the first type argument of {@code Array[T]}.
   */
  private Type arrayComponent(Type type) {
    if (!(type instanceof dotty.tools.dotc.core.Types.AppliedType)) {
      return null;
    }
    scala.collection.immutable.List<Type> args =
        ((dotty.tools.dotc.core.Types.AppliedType) type).args();
    if (args.isEmpty()) {
      return null;
    }
    scala.collection.Iterator<Type> it = args.iterator();
    return it.hasNext() ? it.next() : null;
  }
}
