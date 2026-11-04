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
package org.plumb.tac.frontend.kotlin;

import java.util.List;
import org.jetbrains.kotlin.analysis.api.KaSession;
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol;
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin;
import org.jetbrains.kotlin.analysis.api.types.KaClassType;
import org.jetbrains.kotlin.analysis.api.types.KaType;
import org.jetbrains.kotlin.analysis.api.types.KaTypeProjection;
import org.jetbrains.kotlin.analysis.api.types.TypeInformationKt;
import org.jetbrains.kotlin.name.ClassId;
import org.plumb.tac.ir.TacType;

/**
 * Maps Kotlin types onto {@link TacType}s.
 *
 * <p><b>What the Kotlin type system contributes that Java's does not.</b> Java has no
 * non-null type system: every reference type may hold {@code null}, and the frontend says
 * so for every declaration. Kotlin does have one, and this mapper is where it is read. A
 * Kotlin {@code String} is a {@link TacType} with {@link TacType#isNullable()} {@code false};
 * a Kotlin {@code String?} keeps the default {@code true}.
 *
 * <h2>The three nullability outcomes</h2>
 *
 * <p>Not every Kotlin type is a plain "nullable" or "non-null". There is a third case, and
 * getting it wrong is how the frontend starts inventing findings:
 *
 * <ul>
 *   <li><b>Non-null</b> ({@code String}): the declared type cannot hold {@code null}.
 *       Mapped to a {@link TacType#nonNull() non-null} type. This is the false positive
 *       the null-dereference checker needs to suppress.
 *   <li><b>Nullable</b> ({@code String?}): the declared type may hold {@code null}. Mapped
 *       to the default (nullable) type.
 *   <li><b>Platform</b> (a Java type seen from Kotlin): the compiler inserts an assertion
 *       that throws on {@code null}, but {@code -Xno-call-assertions} removes it and
 *       ProGuard's {@code -assumenosideeffects} strips it from bytecode. <b>A platform type
 *       may hold {@code null} at runtime</b>, so it is mapped as <i>nullable</i>, even
 *       though Kotlin's own type checker lets you dereference it without a {@code ?}.
 * </ul>
 *
 * <p><b>How a platform type is told apart.</b> The Analysis API does not mark a Java
 * type as nullable: a Java declaration says nothing about null, so the compiler models
 * the type as <i>flexible</i> -- neither definitely-null nor definitely-non-null -- and
 * the analysis API's flexible-nullability flag is only set for a type it inferred from a
 * flexible declaration. A Java type that is <i>named</i> in the Kotlin source
 * ({@code s: java.lang.StringBuilder}) comes back as an ordinary non-null type. The
 * reliable signal is therefore the <b>origin of the type's classifier</b>: a Java class
 * has the origin {@link KaSymbolOrigin#JAVA_LIBRARY} (compiled) or {@link
 * KaSymbolOrigin#JAVA_SOURCE} (in the analysed sources), while a Kotlin class has {@link
 * KaSymbolOrigin#SOURCE} or {@link KaSymbolOrigin#LIBRARY}.
 *
 * <p><b>The directional rule.</b> A non-null marking here may only ever <i>suppress</i> a
 * finding. It must never be used to prove that a call site is safe -- see {@link TacType}'s
 * own javadoc for why.
 */
final class KotlinTypeMapper {

  /**
   * The Kotlin standard library's {@code String}, recognised by its class id rather than by
   * a name comparison, so a shaded or relocated copy still maps correctly.
   */
  private static final String KT_STRING = "kotlin.String";

  private static final String KT_INT = "kotlin.Int";
  private static final String KT_LONG = "kotlin.Long";
  private static final String KT_SHORT = "kotlin.Short";
  private static final String KT_BYTE = "kotlin.Byte";
  private static final String KT_CHAR = "kotlin.Char";
  private static final String KT_BOOLEAN = "kotlin.Boolean";
  private static final String KT_FLOAT = "kotlin.Float";
  private static final String KT_DOUBLE = "kotlin.Double";
  private static final String KT_UNIT = "kotlin.Unit";
  private static final String KT_ANY = "kotlin.Any";

  private final KaSession session;

  KotlinTypeMapper(KaSession session) {
    this.session = session;
  }

  /**
   * Maps a Kotlin type onto a {@link TacType}.
   *
   * <p>An unresolved type maps to {@link TacType#UNKNOWN}, which is <i>nullable</i> and
   * carries no information: "unknown" is not "non-null", and treating it as non-null
   * would suppress findings on every symbol the analyser failed to resolve.
   *
   * @param type the Kotlin type; {@code null} maps to {@link TacType#UNKNOWN}
   * @return the corresponding TAC type
   */
  TacType map(KaType type) {
    if (type == null) {
      return TacType.UNKNOWN;
    }
    // A type variable (T) is not a class: leave it unknown rather than guessing.
    if (type instanceof org.jetbrains.kotlin.analysis.api.types.KaTypeParameterType) {
      return TacType.UNKNOWN;
    }
    if (type instanceof org.jetbrains.kotlin.analysis.api.types.KaDefinitelyNotNullType dt) {
      // `T & Any` -- a definitely-non-null type parameter. Non-null.
      TacType mapped = map(dt.getOriginal());
      return mapped.isReference() ? mapped.nonNull() : mapped;
    }
    if (type instanceof org.jetbrains.kotlin.analysis.api.types.KaFunctionType) {
      return TacType.ofClass("kotlin.Function");
    }
    if (type instanceof KaClassType ct) {
      return mapClassType(ct);
    }
    return TacType.UNKNOWN;
  }

  /**
   * Maps a class type, handling the array case and the primitives.
   *
   * <p>The nullability decision is made here and nowhere else, so there is exactly one
   * place to audit when the rules change.
   */
  private TacType mapClassType(KaClassType ct) {
    ClassId classId = ct.getClassId();
    String fqn = classId == null ? null : classId.asFqNameString();

    // Arrays: Kotlin's `IntArray` is a distinct class from `Array<Int>`, and the element
    // type is not reachable through the usual generic arguments.
    TacType component = mapArrayComponent(ct, classId);
    if (component != null) {
      return applyNullability(TacType.arrayOf(component), ct);
    }

    TacType primitive = mapPrimitive(fqn);
    if (primitive != null) {
      // A primitive cannot hold null; the nullability marker is meaningless on it and
      // TacType.isReference() is false, so the DFA's type-fact seeding skips it anyway.
      return primitive;
    }

    if (KT_STRING.equals(fqn)) {
      return applyNullability(TacType.STRING, ct);
    }
    if (KT_UNIT.equals(fqn)) {
      return TacType.VOID;
    }
    if (KT_ANY.equals(fqn)) {
      // `Any` is non-null, but `Any?` is the universal nullable type. Both are handled by
      // applyNullability, so `Any` falls through to the generic class branch below with
      // the "kotlin.Any" fqn.
      return applyNullability(TacType.ofClass(fqn), ct);
    }
    if (fqn == null || fqn.isEmpty()) {
      return TacType.UNKNOWN;
    }
    return applyNullability(TacType.ofClass(fqn), ct);
  }

  /**
   * Returns the component type when {@code ct} is an array, or {@code null} when it is not.
   *
   * <p>Kotlin has three array shapes and they are not interchangeable: {@code IntArray} (and
   * the other primitive arrays) carry the component type in the class itself, while {@code
   * Array<T>} carries it as a type argument.
   */
  private TacType mapArrayComponent(KaClassType ct, ClassId classId) {
    String fqn = classId == null ? null : classId.asFqNameString();
    if (fqn == null) {
      return null;
    }
    switch (fqn) {
      case "kotlin.IntArray":
        return TacType.INT;
      case "kotlin.LongArray":
        return TacType.LONG;
      case "kotlin.ShortArray":
        return TacType.SHORT;
      case "kotlin.ByteArray":
        return TacType.BYTE;
      case "kotlin.CharArray":
        return TacType.CHAR;
      case "kotlin.BooleanArray":
        return TacType.BOOLEAN;
      case "kotlin.FloatArray":
        return TacType.FLOAT;
      case "kotlin.DoubleArray":
        return TacType.DOUBLE;
      default:
        break;
    }
    if (!"kotlin.Array".equals(fqn)) {
      return null;
    }
    List<KaTypeProjection> args = ct.getTypeArguments();
    if (args.isEmpty()) {
      // An unparameterised `Array` -- the element type is unknown.
      return TacType.UNKNOWN;
    }
    KaTypeProjection projection = args.get(0);
    KaType argument = projection.getType();
    return argument == null ? TacType.UNKNOWN : map(argument);
  }

  /** Maps a Kotlin primitive class id onto the corresponding TAC primitive, or null. */
  private static TacType mapPrimitive(String fqn) {
    if (fqn == null) {
      return null;
    }
    switch (fqn) {
      case KT_INT:
        return TacType.INT;
      case KT_LONG:
        return TacType.LONG;
      case KT_SHORT:
        return TacType.SHORT;
      case KT_BYTE:
        return TacType.BYTE;
      case KT_CHAR:
        return TacType.CHAR;
      case KT_BOOLEAN:
        return TacType.BOOLEAN;
      case KT_FLOAT:
        return TacType.FLOAT;
      case KT_DOUBLE:
        return TacType.DOUBLE;
      default:
        return null;
    }
  }

  /**
   * Applies the Kotlin nullability to a mapped type.
   *
   * <p>Three rules, in order:
   *
   * <ol>
   *   <li>A <b>platform type</b> (a Java type seen from Kotlin) may hold null at runtime,
   *       so it stays nullable. This is checked first because a platform type's
   *       nullability is genuinely unknown, not "non-null".
   *   <li>A type explicitly marked nullable ({@code T?}) stays nullable.
   *   <li>Everything else -- a Kotlin type with no {@code ?} -- is non-null.
   * </ol>
   *
   * <p><b>How a platform type is recognised.</b> By the <i>origin of the type's
   * classifier</i>, not by the type's nullability flags. The Analysis API reports a Java
   * type as a plain non-null type when it is named explicitly ({@code s:
   * java.lang.StringBuilder}) and as a flexible type when it is inferred ({@code
   * StringBuilder()}); neither carries the "this may be null" flag the Kotlin compiler
   * would use, because a Java declaration genuinely says nothing about null. The
   * classifier's origin does say it: {@link KaSymbolOrigin#JAVA_LIBRARY} for a compiled
   * Java class, {@link KaSymbolOrigin#JAVA_SOURCE} for one in the analysed sources.
   */
  private TacType applyNullability(TacType mapped, KaType kt) {
    if (!mapped.isReference()) {
      // Primitives and void cannot hold null.
      return mapped;
    }
    if (isPlatformType(kt)) {
      // A platform type: the Java declaration says nothing about null, and the Kotlin
      // compiler's assertion can be stripped. Treat as nullable.
      return mapped.nullable();
    }
    if (TypeInformationKt.isMarkedNullable(session, kt)) {
      return mapped.nullable();
    }
    return mapped.nonNull();
  }

  /**
   * Whether a type is a platform type -- a Java type seen from Kotlin.
   *
   * <p>A Java declaration carries no nullability, so a value of such a type may hold
   * {@code null} at runtime even though Kotlin's own type checker lets you dereference it
   * without a {@code ?}.
   *
   * @param type the Kotlin type
   * @return true when the type's classifier is a Java class
   */
  private boolean isPlatformType(KaType type) {
    if (type == null) {
      return false;
    }
    if (TypeInformationKt.getHasFlexibleNullability(session, type)) {
      // A flexible type is a Java type by definition.
      return true;
    }
    KaClassLikeSymbol classifier = classifierOf(type);
    if (classifier == null) {
      return false;
    }
    KaSymbolOrigin origin = classifier.getOrigin();
    return origin == KaSymbolOrigin.JAVA_LIBRARY || origin == KaSymbolOrigin.JAVA_SOURCE;
  }

  /**
   * Whether a Kotlin type is statically non-nullable.
   *
   * <p>Used by the translator to decide whether a variable's declaration is a
   * non-null fact. A platform type is <i>not</i> statically non-null, for the same reason
   * it maps to a nullable {@link TacType}.
   *
   * @param type the Kotlin type
   * @return true when the type is a Kotlin reference type that cannot hold null
   */
  boolean isStaticallyNonNull(KaType type) {
    if (type == null) {
      return false;
    }
    if (isPlatformType(type)) {
      return false;
    }
    return !TypeInformationKt.isMarkedNullable(session, type);
  }

  /**
   * The fully-qualified name of a type's classifier, for a callee signature.
   *
   * <p>Returns {@code null} for a type with no classifier (a type variable, a function
   * type), which the caller renders as {@code "?"}.
   */
  static String classNameOf(KaType type) {
    if (type == null) {
      return null;
    }
    if (type instanceof KaClassType ct) {
      ClassId id = ct.getClassId();
      return id == null ? null : id.asFqNameString();
    }
    if (type instanceof org.jetbrains.kotlin.analysis.api.types.KaDefinitelyNotNullType dt) {
      return classNameOf(dt.getOriginal());
    }
    return null;
  }

  /**
   * The classifier symbol of a type, or {@code null}.
   *
   * <p>Used to decide whether a declaration's type is a Kotlin class or a Java class --
   * which matters for the synthetic detection of a Java-implemented interface member.
   */
  static KaClassLikeSymbol classifierOf(KaType type) {
    if (type == null) {
      return null;
    }
    if (type instanceof KaClassType ct) {
      KaSymbol sym = ct.getSymbol();
      return sym instanceof KaClassLikeSymbol cls ? cls : null;
    }
    return null;
  }
}
