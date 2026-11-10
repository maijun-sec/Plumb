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

// IR-serializable: this enum participates in the disk-cache contract (IrSerializer).
// Enum constants are serialised by name, so ADDING a constant at the end is
// backwards compatible. Reordering or renaming a constant is NOT.

/**
 * The source language a {@link TacFunction}'s body was written in.
 *
 * <p>This is a <b>dimension of the IR contract</b>, not a language-specific extension. The TAC
 * itself stays language-neutral; this enum tells a checker which language's semantics it is looking
 * at, so that:
 *
 * <ul>
 *   <li>language-specific sinks can be declared (e.g. {@code kotlin.text.Regex});
 *   <li>language-specific false positives can be suppressed (e.g. Kotlin's non-null type system);
 *   <li>language-specific checks can be enabled (e.g. Kotlin's {@code Result<T>} error handling).
 * </ul>
 *
 * <p><b>Directional rule.</b> This enum may be used to <i>suppress</i> a finding or to
 * <i>select</i> a rule. It must never be used to <i>prove</i> that code is safe -- see {@link
 * IrPrecision} for the same rule applied to precision.
 */
public enum SourceLanguage {

  /** Java source. The default; {@code .java} files parsed by the JavaParser frontend. */
  JAVA,

  /** Kotlin source. */
  KOTLIN,

  /** Scala 3 source. */
  SCALA,

  /**
   * Groovy source.
   *
   * <p>Groovy is dynamically dispatched, so dataflow precision is limited. Only enable Groovy
   * analysis when the user accepts that.
   */
  GROOVY,

  /**
   * C# source.
   *
   * <p>C# is statically typed, but its nullable reference annotations are a WARNING, not a compile
   * error: a program that violates one still compiles and still runs. So a nullable annotation
   * cannot be used to prove that a value is non-null at run time, and {@link
   * #hasNonNullTypeSystem()} answers {@code false} for C#. See that method for why the answer
   * matters.
   *
   * <p>The C# frontend reads the source through Roslyn, so the types, overloads and property
   * accessors it reports are the ones the C# compiler itself sees.
   */
  CSHARP,

  /**
   * Go source.
   *
   * <p>Go is statically typed, so {@link #supportsFullDataflow()} answers {@code true}. It does
   * <b>not</b> have a compile-time non-null type system: Go's nil is an ordinary value of every
   * pointer, map, slice, function, channel and interface type, and nothing at the declaration site
   * promises a non-nil value. So {@link #hasNonNullTypeSystem()} answers {@code false}, for the
   * same reason it does for C#.
   *
   * <p>The Go frontend reads the source through the Go toolchain's own type checker, so the types
   * it reports are the ones the Go compiler itself sees.
   */
  GO,

  /**
   * JavaScript source.
   *
   * <p>JavaScript is dynamically typed: a variable holds whatever it was last assigned, and an
   * object is a map of properties rather than a typed record. So {@link #supportsFullDataflow()}
   * answers {@code false}, and {@link #hasNonNullTypeSystem()} answers {@code false} -- {@code
   * null} and {@code undefined} are ordinary values in JavaScript, and nothing at the declaration
   * site promises either is absent.
   *
   * <p>The frontend reads the source through the TypeScript compiler with {@code allowJs}, so the
   * types it reports are the ones the TypeScript checker sees -- which for plain JavaScript means
   * {@code any} for most of the program.
   */
  JAVASCRIPT,

  /**
   * TypeScript source.
   *
   * <p>TypeScript is statically typed, but it is <b>gradually</b> typed: a value whose type the
   * checker could not determine is {@code any}, and an {@code any} propagates. So the answer to
   * {@link #supportsFullDataflow()} is {@code false} -- the analysis is only as strong as the
   * annotations the program carries, and a program with no annotations is as dynamic as JavaScript.
   *
   * <p>{@link #hasNonNullTypeSystem()} answers {@code false} for the same reason it does for
   * JavaScript: {@code strictNullChecks} is a compiler <i>option</i>, not a property of the
   * language, so a file compiled without it has no non-null guarantee to rely on.
   */
  TYPESCRIPT,

  /**
   * ArkTS source -- the application language of HarmonyOS / OpenHarmony.
   *
   * <p>ArkTS is derived from TypeScript but is <b>statically restricted</b>: it forbids {@code
   * any}, forbids runtime-shaped object mutation, and forbids several other dynamic constructs.
   * Those restrictions are exactly what a dataflow engine wants, so ArkTS is the dynamic-family
   * language with the strongest guarantees -- but the guarantees come from the language's rules,
   * not from a type system with non-null annotations, so {@link #hasNonNullTypeSystem()} answers
   * {@code false}.
   *
   * <p>The frontend reads the source through {@code ohos-typescript}, the TypeScript fork the
   * OpenHarmony toolchain uses, so the types it reports are the ones the ArkTS compiler itself
   * sees.
   */
  ARKTS,

  /**
   * Python 3 source.
   *
   * <p><b>This means Python 3, not Python 2.</b> The two major versions have different grammars,
   * and the frontend parses with the {@code ast} module of the running interpreter, so a Python 2
   * file is a parse error rather than a silently mis-analysed file.
   *
   * <p>Python is dynamically typed, so {@link #supportsFullDataflow()} answers {@code false} and
   * {@link #hasNonNullTypeSystem()} answers {@code false} -- {@code None} is an ordinary value of
   * every type, and the absence of an annotation says nothing about it.
   */
  PYTHON3,

  /**
   * PHP source.
   *
   * <p>PHP is weakly typed: a variable may change type, and a declared type is a coercion request
   * rather than a guarantee. So {@link #supportsFullDataflow()} answers {@code false} and {@link
   * #hasNonNullTypeSystem()} answers {@code false}.
   *
   * <p>PHP's dynamic dispatch is the deeper problem: the magic methods {@code __call} and {@code
   * __get} make arbitrary method and property names reachable, so a call site's target cannot
   * always be enumerated statically.
   */
  PHP,

  /**
   * The language could not be determined -- typically code loaded from a third-party {@code .jar}
   * through the bytecode frontend.
   */
  UNKNOWN;

  /**
   * Whether this language has a compile-time non-null type system.
   *
   * <p>Kotlin's {@code String} (as opposed to {@code String?}) guarantees non-nullness at the
   * declaration site. Scala and Java have no such system.
   *
   * <p><b>C# does NOT, despite having nullable annotations.</b> C#'s nullable reference types are a
   * compile-time WARNING: {@code string?} produces a diagnostic, not an error, and the program
   * still compiles and still runs. A C# program can therefore pass null to a {@code string}
   * parameter and nothing stops it. Treating C# as having a non-null type system would let the
   * null-dereference checker suppress findings that are entirely real -- which is the one thing the
   * {@code nullable} flag must never be used for. See {@link TacType#isNullable()}.
   *
   * <p><b>Directional rule.</b> A {@code true} answer means "the compiler rejects a null literal
   * for this type", which is a fact about the <i>declaration</i>. It says nothing about whether a
   * particular runtime value is null (platform types from Java interop still can be). Use it only
   * to suppress null-dereference false positives.
   */
  public boolean hasNonNullTypeSystem() {
    return this == KOTLIN;
  }

  /**
   * Whether this language is statically typed enough for reliable dataflow analysis.
   *
   * <p><b>Why the dynamic-type languages answer {@code false}.</b> A {@code true} answer tells the
   * engine that the types it sees are the types the program has, so a call target, a field type and
   * a receiver are all known. In JavaScript, Python and PHP they are not: a value's type is
   * whatever it was last assigned, and an object is a map whose keys are not known statically.
   *
   * <p><b>The consequence is a lost finding, not a noisier one.</b> The engine uses this flag to
   * decide whether it may claim that a receiver is of a given type. Claiming it for a language
   * whose types are not known would make the engine skip a target it could not resolve -- so the
   * answer must follow the language, not the ambition.
   *
   * <p>TypeScript and ArkTS answer {@code false} for a different reason. TypeScript is statically
   * typed, but <i>gradually</i>: an unresolved value is {@code any}, and an {@code any} propagates
   * through the whole expression it appears in. A program with no annotations is as dynamic as
   * JavaScript, so the language cannot promise what a particular program does.
   */
  public boolean supportsFullDataflow() {
    return this == JAVA || this == KOTLIN || this == SCALA || this == CSHARP || this == GO;
  }

  /**
   * Whether a subscript expression in this language names an entry of a keyed namespace.
   *
   * <p><b>What the question is about.</b> In Java, {@code a[i]} is an array element and the index
   * is an integer. In JavaScript, {@code obj[k]} is a property of an object and the key is a
   * string; {@code arr[0]} is the same thing as {@code arr["0"]}, because an array IS an object
   * with numeric property names. Python's {@code d[k]} and PHP's {@code $a[$k]} are keyed the same
   * way. So "the same syntax" means "the same kind of location" in Java and "a different kind of
   * location" in these five languages.
   *
   * <p><b>Why the engine has to know.</b> A keyed namespace has entries, and an entry's identity is
   * the pair (object, key). Tracking that needs the keyed form of the access path ({@link
   * TacAccessPath.MapKey} and its wildcards), and the engine must know WHICH namespace a subscript
   * reaches before it can pick the form. Getting it wrong is not a precision trade-off: a subscript
   * tracked as an array element cannot see a write made through a property, and the taint is simply
   * gone.
   *
   * <p><b>Why the languages already supported are NOT in this set.</b> Their subscripts are array
   * indices, and their map-like containers are reached through CALL instructions rather than
   * through a subscript opcode. Answering true for them would change how their existing findings
   * are computed, so the answer follows the language rather than the ambition.
   *
   * <p><b>Directional rule.</b> Like the other predicates on this enum, this one may select a
   * tracking strategy or suppress a finding. It must never be used to prove that a location is
   * clean.
   */
  public boolean hasKeyedContainers() {
    return this == JAVASCRIPT
        || this == TYPESCRIPT
        || this == ARKTS
        || this == PYTHON3
        || this == PHP;
  }

  /**
   * Parse a language name as written in configuration (YAML, CLI).
   *
   * <p>Accepts the enum name in any case, plus a few common aliases. Returns {@link #UNKNOWN} for
   * anything unrecognised -- an unknown language must not silently become {@link #JAVA}, because
   * that would make Java-specific rules fire on code they do not understand.
   */
  public static SourceLanguage parse(String name) {
    if (name == null || name.isBlank()) return UNKNOWN;
    String k = name.trim().toUpperCase(java.util.Locale.ROOT);
    switch (k) {
      case "JAVA":
      case "JVM-JAVA":
        return JAVA;
      case "KOTLIN":
      case "KOT":
      case "JVM-KOTLIN":
        return KOTLIN;
      case "SCALA":
      case "SCALA3":
      case "JVM-SCALA":
        return SCALA;
      case "GROOVY":
      case "JVM-GROOVY":
        return GROOVY;
      case "CSHARP":
      case "C#":
      case "DOTNET-CSHARP":
        return CSHARP;
      case "GO":
      case "GOLANG":
        return GO;
      case "JAVASCRIPT":
      case "JS":
      case "ECMASCRIPT":
        return JAVASCRIPT;
      case "TYPESCRIPT":
      case "TS":
        return TYPESCRIPT;
      case "ARKTS":
      case "ETS":
        return ARKTS;
      case "PYTHON3":
      case "PY3":
      case "PYTHON":
        return PYTHON3;
      case "PHP":
        return PHP;
      default:
        return UNKNOWN;
    }
  }
}
