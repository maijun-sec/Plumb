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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The IR-level contracts the multi-language frontends depend on.
 *
 * <p>These are the load-bearing invariants of the language dimension: the language enum, the
 * precision enum, the nullability dimension on {@link TacType}, the identity rule on {@link
 * TacOperand.TacVar}, and the synthetic flag on {@link TacFunction}.
 *
 * <p>Each of them has a failure mode that is <b>silent</b>. A broken {@code TacVar} identity does
 * not throw -- it drops a taint fact on a lookup that "almost" matched. A broken nullability
 * default does not throw -- it silently changes every Java null-dereference finding. That is why
 * they are pinned by tests rather than left to convention.
 */
class MultiLanguageIrContractTest {

  @Nested
  @DisplayName("SourceLanguage")
  class SourceLanguageTest {

    @Test
    @DisplayName("parse() maps the enum names in any case")
    void parsesEnumNamesCaseInsensitively() {
      assertThat(SourceLanguage.parse("java")).isEqualTo(SourceLanguage.JAVA);
      assertThat(SourceLanguage.parse("KOTLIN")).isEqualTo(SourceLanguage.KOTLIN);
      assertThat(SourceLanguage.parse("Scala")).isEqualTo(SourceLanguage.SCALA);
      assertThat(SourceLanguage.parse("groovy")).isEqualTo(SourceLanguage.GROOVY);
    }

    @Test
    @DisplayName("parse() maps the common aliases")
    void parsesAliases() {
      assertThat(SourceLanguage.parse("jvm-kotlin")).isEqualTo(SourceLanguage.KOTLIN);
      assertThat(SourceLanguage.parse("scala3")).isEqualTo(SourceLanguage.SCALA);
      assertThat(SourceLanguage.parse("  kotlin  ")).isEqualTo(SourceLanguage.KOTLIN);
    }

    @Test
    @DisplayName("an unrecognised language is UNKNOWN, never JAVA")
    void unknownNeverBecomesJava() {
      // This matters: if an unknown language silently became JAVA, the Java-specific rules
      // would fire on code they do not understand.
      assertThat(SourceLanguage.parse("cobol")).isEqualTo(SourceLanguage.UNKNOWN);
      assertThat(SourceLanguage.parse("")).isEqualTo(SourceLanguage.UNKNOWN);
      assertThat(SourceLanguage.parse("   ")).isEqualTo(SourceLanguage.UNKNOWN);
      assertThat(SourceLanguage.parse(null)).isEqualTo(SourceLanguage.UNKNOWN);
    }

    @Test
    @DisplayName("only Kotlin has a compile-time non-null type system")
    void onlyKotlinHasNonNullTypeSystem() {
      assertThat(SourceLanguage.KOTLIN.hasNonNullTypeSystem()).isTrue();
      assertThat(SourceLanguage.JAVA.hasNonNullTypeSystem()).isFalse();
      assertThat(SourceLanguage.SCALA.hasNonNullTypeSystem()).isFalse();
      assertThat(SourceLanguage.GROOVY.hasNonNullTypeSystem()).isFalse();
      // C#'s nullable reference annotations are a WARNING, not an error: a program
      // that violates one still compiles and still runs. So C# must NOT answer
      // true here -- the answer would let the null-dereference checker suppress
      // findings that are entirely real.
      assertThat(SourceLanguage.CSHARP.hasNonNullTypeSystem()).isFalse();
      assertThat(SourceLanguage.UNKNOWN.hasNonNullTypeSystem()).isFalse();
    }

    @Test
    @DisplayName("only the statically typed languages support full dataflow")
    void onlyStaticLanguagesSupportFullDataflow() {
      assertThat(SourceLanguage.JAVA.supportsFullDataflow()).isTrue();
      assertThat(SourceLanguage.KOTLIN.supportsFullDataflow()).isTrue();
      assertThat(SourceLanguage.SCALA.supportsFullDataflow()).isTrue();
      // C# is statically typed, so it supports the full dataflow analysis.
      assertThat(SourceLanguage.CSHARP.supportsFullDataflow()).isTrue();
      assertThat(SourceLanguage.GROOVY.supportsFullDataflow()).isFalse();
      assertThat(SourceLanguage.UNKNOWN.supportsFullDataflow()).isFalse();
    }

    @Test
    @DisplayName("the enum constants are stable -- the disk cache serialises them by name")
    void enumConstantsAreStable() {
      // The IR disk cache serialises enums by NAME, so a rename or a reorder would
      // invalidate every cached program. Adding at the end is fine; changing these
      // is not.
      //
      // CSHARP sits BEFORE UNKNOWN, and that is deliberate: UNKNOWN is the
      // fallback and must stay LAST, because a new language is appended in front
      // of it. The name-based serialisation is what makes the position safe --
      // only the ORDER is load-bearing here.
      assertThat(SourceLanguage.values())
          .containsExactly(
              SourceLanguage.JAVA,
              SourceLanguage.KOTLIN,
              SourceLanguage.SCALA,
              SourceLanguage.GROOVY,
              SourceLanguage.CSHARP,
              SourceLanguage.GO,
              SourceLanguage.UNKNOWN);
    }

    @Test
    @DisplayName("UNKNOWN is the last constant -- a new language is appended in front of it")
    void unknownIsLast() {
      // The disk cache serialises by name, so the ORDER is not what breaks the
      // cache -- the NAME is. But UNKNOWN is the fallback, and every lookup that
      // fails to identify a language lands on it, so it must stay last: a new
      // language is added in front of it, never after it.
      assertThat(SourceLanguage.values()[SourceLanguage.values().length - 1])
          .isEqualTo(SourceLanguage.UNKNOWN);
    }
  }

  @Nested
  @DisplayName("IrPrecision")
  class IrPrecisionTest {

    @Test
    @DisplayName("only the source levels see lambda bodies")
    void onlySourceLevelsSeeLambdaBodies() {
      assertThat(IrPrecision.SOURCE_FULL.seesLambdaBodies()).isTrue();
      assertThat(IrPrecision.SOURCE_LOWERED.seesLambdaBodies()).isTrue();
      assertThat(IrPrecision.BYTECODE.seesLambdaBodies()).isFalse();
      assertThat(IrPrecision.BYTECODE_SYNTHETIC.seesLambdaBodies()).isFalse();
    }

    @Test
    @DisplayName("only the source levels see parameter annotations")
    void onlySourceLevelsSeeParameterAnnotations() {
      assertThat(IrPrecision.SOURCE_FULL.seesParameterAnnotations()).isTrue();
      assertThat(IrPrecision.SOURCE_LOWERED.seesParameterAnnotations()).isTrue();
      assertThat(IrPrecision.BYTECODE.seesParameterAnnotations()).isFalse();
      assertThat(IrPrecision.BYTECODE_SYNTHETIC.seesParameterAnnotations()).isFalse();
    }

    @Test
    @DisplayName("only the source levels may suppress a null-dereference finding")
    void onlySourceLevelsMaySuppressNullDeref() {
      assertThat(IrPrecision.SOURCE_FULL.maySuppressNullDeref()).isTrue();
      assertThat(IrPrecision.SOURCE_LOWERED.maySuppressNullDeref()).isTrue();
      assertThat(IrPrecision.BYTECODE.maySuppressNullDeref()).isFalse();
      assertThat(IrPrecision.BYTECODE_SYNTHETIC.maySuppressNullDeref()).isFalse();
    }

    @Test
    @DisplayName("only SOURCE_FULL preserves the source structure")
    void onlySourceFullPreservesStructure() {
      assertThat(IrPrecision.SOURCE_FULL.hasSourceStructure()).isTrue();
      assertThat(IrPrecision.SOURCE_LOWERED.hasSourceStructure()).isFalse();
      assertThat(IrPrecision.BYTECODE.hasSourceStructure()).isFalse();
      assertThat(IrPrecision.BYTECODE_SYNTHETIC.hasSourceStructure()).isFalse();
    }

    @Test
    @DisplayName("the enum constants are stable -- the disk cache serialises them by name")
    void enumConstantsAreStable() {
      assertThat(IrPrecision.values())
          .containsExactly(
              IrPrecision.SOURCE_FULL,
              IrPrecision.SOURCE_LOWERED,
              IrPrecision.BYTECODE,
              IrPrecision.BYTECODE_SYNTHETIC);
    }
  }

  @Nested
  @DisplayName("TacType nullability")
  class TacTypeNullabilityTest {

    @Test
    @DisplayName("every existing factory produces a nullable type -- Java semantics are unchanged")
    void existingFactoriesStayNullable() {
      // This is the load-bearing default. If any of these became non-nullable, every
      // Java null-dereference finding would silently disappear.
      assertThat(TacType.STRING.isNullable()).isTrue();
      assertThat(TacType.INT.isNullable()).isTrue();
      assertThat(TacType.UNKNOWN.isNullable()).isTrue();
      assertThat(TacType.NULL.isNullable()).isTrue();
      assertThat(TacType.ofClass("java.lang.String").isNullable()).isTrue();
      assertThat(TacType.arrayOf(TacType.INT).isNullable()).isTrue();
    }

    @Test
    @DisplayName("nonNull() flips the flag and is idempotent")
    void nonNullIsIdempotent() {
      TacType nullable = TacType.ofClass("kotlin.String");
      TacType nonNull = nullable.nonNull();

      assertThat(nullable.isNullable()).isTrue();
      assertThat(nonNull.isNullable()).isFalse();
      assertThat(nonNull.nonNull()).isSameAs(nonNull);
      assertThat(nullable.nullable()).isSameAs(nullable);
    }

    @Test
    @DisplayName("nonNullClass / nonNullArray build non-nullable references")
    void nonNullFactories() {
      assertThat(TacType.nonNullClass("kotlin.String").isNullable()).isFalse();
      assertThat(TacType.nonNullArray(TacType.INT).isNullable()).isFalse();
      assertThat(TacType.nonNullArray(TacType.INT).kind()).isEqualTo(TacType.Kind.ARRAY);
    }

    @Test
    @DisplayName("nullability participates in equality and hashCode")
    void nullabilityParticipatesInEquality() {
      TacType nullable = TacType.ofClass("java.lang.String");
      TacType nonNull = TacType.nonNullClass("java.lang.String");

      assertThat(nullable).isNotEqualTo(nonNull);
      assertThat(nullable.hashCode()).isNotEqualTo(nonNull.hashCode());
      assertThat(nullable).isEqualTo(TacType.ofClass("java.lang.String"));
      assertThat(nonNull).isEqualTo(TacType.ofClass("java.lang.String").nonNull());
    }

    @Test
    @DisplayName("displayName() is the identity form; toString() is the decorated display form")
    void displayNameIsTheIdentityForm() {
      TacType nonNull = TacType.nonNullClass("java.lang.String");

      // displayName() must stay undecorated: it is used as a map key (the field-taint
      // tracker keys its per-class maps on the class name).
      assertThat(nonNull.displayName()).isEqualTo("java.lang.String");
      assertThat(nonNull.toString()).isEqualTo("java.lang.String (non-null)");

      // The nullable form has no decoration at all.
      TacType nullable = TacType.ofClass("java.lang.String");
      assertThat(nullable.displayName()).isEqualTo("java.lang.String");
      assertThat(nullable.toString()).isEqualTo("java.lang.String");
    }

    @Test
    @DisplayName("displayName() does not decorate any reference kind")
    void displayNameNeverDecorates() {
      assertThat(TacType.nonNullArray(TacType.INT).displayName()).isEqualTo("int[]");
      assertThat(TacType.nonNullClass("java.util.List").displayName()).isEqualTo("java.util.List");
      assertThat(TacType.STRING.displayName()).isEqualTo("java.lang.String");
    }

    @Test
    @DisplayName("Kind.NULL stays a value-level fact and is not the nullability dimension")
    void nullKindIsUnrelatedToNullability() {
      // Kind.NULL means "this value IS the null literal". The nullable flag means "a
      // reference of this type MAY hold null". Conflating them would let a null literal
      // be treated as a nullable type and vice versa.
      assertThat(TacType.NULL.kind()).isEqualTo(TacType.Kind.NULL);
      assertThat(TacType.NULL.isNullable()).isTrue();
      assertThat(TacType.Kind.NULL).isNotEqualTo(TacType.Kind.CLASS);
      assertThat(TacType.ofClass("java.lang.String").kind()).isEqualTo(TacType.Kind.CLASS);
    }

    @Test
    @DisplayName("a decimal is a primitive, and is not a double")
    void decimalIsItsOwnPrimitive() {
      // C#'s `decimal` is a 128-bit FIXED-POINT value, not a floating-point one.
      // Folding it into DOUBLE would silently change the value, so it gets its own
      // kind.
      assertThat(TacType.DECIMAL.kind()).isEqualTo(TacType.Kind.DECIMAL);
      assertThat(TacType.DECIMAL.isPrimitive()).isTrue();
      assertThat(TacType.DECIMAL.displayName()).isEqualTo("decimal");
      assertThat(TacType.DECIMAL).isNotEqualTo(TacType.DOUBLE);
    }
  }

  @Nested
  @DisplayName("TacVar identity")
  class TacVarIdentityTest {

    @Test
    @DisplayName("the type does not participate in identity")
    void typeDoesNotParticipateInIdentity() {
      // This is the load-bearing rule: the dataflow engine keys every per-variable map
      // on the TacVar. If the type participated, a variable refined from UNKNOWN to a
      // resolved type would become a DIFFERENT key, and the taint recorded under the old
      // key would be silently dropped.
      TacOperand.TacVar unresolved = new TacOperand.TacVar("x", TacType.UNKNOWN, false);
      TacOperand.TacVar resolved =
          new TacOperand.TacVar("x", TacType.ofClass("java.lang.String"), false);

      assertThat(unresolved).isEqualTo(resolved);
      assertThat(unresolved.hashCode()).isEqualTo(resolved.hashCode());
    }

    @Test
    @DisplayName("a refined nullability does not change identity")
    void nullabilityRefinementDoesNotChangeIdentity() {
      TacOperand.TacVar nullable =
          new TacOperand.TacVar("x", TacType.ofClass("kotlin.String"), false);
      TacOperand.TacVar nonNull =
          new TacOperand.TacVar("x", TacType.ofClass("kotlin.String").nonNull(), false);

      assertThat(nullable).isEqualTo(nonNull);
      assertThat(nullable.hashCode()).isEqualTo(nonNull.hashCode());
    }

    @Test
    @DisplayName("the temp flag does participate in identity")
    void tempFlagParticipatesInIdentity() {
      TacOperand.TacVar local = new TacOperand.TacVar("x", TacType.INT, false);
      TacOperand.TacVar temp = new TacOperand.TacVar("x", TacType.INT, true);

      assertThat(local).isNotEqualTo(temp);
      assertThat(local.hashCode()).isNotEqualTo(temp.hashCode());
    }

    @Test
    @DisplayName("the name does participate in identity")
    void nameParticipatesInIdentity() {
      TacOperand.TacVar a = new TacOperand.TacVar("a", TacType.INT, false);
      TacOperand.TacVar b = new TacOperand.TacVar("b", TacType.INT, false);

      assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("a refined variable still hits the same map entry")
    void refinedVariableHitsTheSameMapEntry() {
      java.util.Map<TacOperand.TacVar, String> tainted = new java.util.HashMap<>();
      TacOperand.TacVar declared = new TacOperand.TacVar("x", TacType.UNKNOWN, false);
      tainted.put(declared, "tainted");

      TacOperand.TacVar refined =
          new TacOperand.TacVar("x", TacType.ofClass("kotlin.String").nonNull(), false);

      assertThat(tainted).containsKey(refined);
      assertThat(tainted.get(refined)).isEqualTo("tainted");
    }
  }

  @Nested
  @DisplayName("TacFunction metadata")
  class TacFunctionMetadataTest {

    private TacFunction minimal() {
      return new TacFunction("f", "f()", TacType.VOID, List.of(), List.of(), List.of());
    }

    @Test
    @DisplayName("the short constructor defaults to Java, SOURCE_FULL and not synthetic")
    void shortConstructorDefaults() {
      TacFunction fn = minimal();
      assertThat(fn.sourceLanguage()).isEqualTo(SourceLanguage.JAVA);
      assertThat(fn.irPrecision()).isEqualTo(IrPrecision.SOURCE_FULL);
      assertThat(fn.isSynthetic()).isFalse();
    }

    @Test
    @DisplayName("the language/precision constructor defaults synthetic to false")
    void languageConstructorDefaultsSyntheticToFalse() {
      TacFunction fn =
          new TacFunction(
              "f",
              "f()",
              TacType.VOID,
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              java.util.Map.of(),
              java.util.Map.of(),
              null,
              SourceOrigin.SOURCE,
              List.of(),
              SourceLanguage.KOTLIN,
              IrPrecision.SOURCE_LOWERED);

      assertThat(fn.sourceLanguage()).isEqualTo(SourceLanguage.KOTLIN);
      assertThat(fn.irPrecision()).isEqualTo(IrPrecision.SOURCE_LOWERED);
      assertThat(fn.isSynthetic())
          .as("a hand-written method must never be mistaken for a compiler-generated one")
          .isFalse();
    }

    @Test
    @DisplayName("the full constructor carries the synthetic flag")
    void fullConstructorCarriesSynthetic() {
      TacFunction fn =
          new TacFunction(
              "component1",
              "component1()",
              TacType.INT,
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              java.util.Map.of(),
              java.util.Map.of(),
              "Point",
              SourceOrigin.SOURCE,
              List.of(),
              SourceLanguage.KOTLIN,
              IrPrecision.SOURCE_FULL,
              true);

      assertThat(fn.isSynthetic()).isTrue();
      assertThat(fn.sourceLanguage()).isEqualTo(SourceLanguage.KOTLIN);
    }

    @Test
    @DisplayName("a null language or precision falls back to the Java defaults")
    void nullMetadataFallsBack() {
      TacFunction fn =
          new TacFunction(
              "f",
              "f()",
              TacType.VOID,
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              List.of(),
              java.util.Map.of(),
              java.util.Map.of(),
              null,
              SourceOrigin.SOURCE,
              List.of(),
              null,
              null,
              false);

      assertThat(fn.sourceLanguage()).isEqualTo(SourceLanguage.JAVA);
      assertThat(fn.irPrecision()).isEqualTo(IrPrecision.SOURCE_FULL);
    }
  }

  @Nested
  @DisplayName("NULL_CHECK opcode")
  class NullCheckOpcodeTest {

    @Test
    @DisplayName("the opcode exists and is part of the language-neutral instruction set")
    void nullCheckExists() {
      assertThat(TacInstruction.Kind.valueOf("NULL_CHECK")).isNotNull();
      // It must not be a language-specific opcode: it means "this value is proven
      // non-null", which every language can say.
      assertThat(TacInstruction.Kind.NULL_CHECK).isNotEqualTo(TacInstruction.Kind.BRANCH);
      assertThat(TacInstruction.Kind.values()).contains(TacInstruction.Kind.NULL_CHECK);
    }
  }
}
