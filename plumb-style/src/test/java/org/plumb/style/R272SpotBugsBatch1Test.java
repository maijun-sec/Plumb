package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.bug.RedundantNullCheckCheck;
import org.plumb.style.style.BitwiseAndCheck;
import org.plumb.style.style.BitwiseOrCheck;
import org.plumb.style.style.EqualsNoObjectCheck;
import org.plumb.style.style.ExposeMutableRepStorageCheck;
import org.plumb.style.style.MutableEnumFieldCheck;
import org.plumb.style.style.NonSerializableFieldCheck;
import org.plumb.style.style.SerializableSubclassCheck;
import org.plumb.style.style.SignedBitCheckCheck;
import org.plumb.style.style.StaticWriteFromInstanceCheck;
import org.plumb.style.style.UnconfirmedCastCheck;

/**
 * gap-filling batch 1 (10 new rules, pl-0260 through pl-0269). Covers bug patterns that are unique
 * to (no / equivalent): cast without redundant null check, bitwise-in-conditional, signed bit
 * check, serializable subclass, EI_EXPOSE_REP2, mutable enum field, static write from instance,
 * equals without Object, non-serializable field.
 *
 * <p>Each checker is exercised through a minimal source snippet that triggers its fire / no-fire
 * condition; the test asserts the expected presence / absence of a finding in the
 */
class R272SpotBugsBatch1Test {

  private final RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);

  private List<?> run(String src, AstChecker check) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("R272Test.java"), src);
    StyleContext ctx = new StyleContext(ps, rules);
    new AstDispatcher().register(check).run(ctx);
    return ctx.findings();
  }

  private long count(List<?> findings, String prefix) {
    return findings.stream().filter(f -> f.toString().contains(prefix)).count();
  }

  // ============== UnconfirmedCast (pl-0260) ==============
  @Test
  void unconfirmedCast_firesOnBareCast() {
    String src = "import java.util.*; class N {" + " Object m(Object o) { return (String) o; } }";
    assertThat(run(src, new UnconfirmedCastCheck())).as("unconfirmed cast fires").isNotEmpty();
  }

  @Test
  void unconfirmedCast_noFireOnConfirmedCast() {
    String src =
        "class N {"
            + " Object m(Object o) {"
            + " if (o instanceof String) return (String) o;"
            + " return null; } }";
    assertThat(run(src, new UnconfirmedCastCheck()))
        .as("instanceof-confirmed cast does not fire")
        .isEmpty();
  }

  // ============== RedundantNullCheck (pl-0261) ==============
  @Test
  void redundantNullCheck_firesOnDoubleGuard() {
    String src =
        "class N {"
            + " void m(String s) {"
            + " if (s == null) return;"
            + " if (s == null) throw new RuntimeException();"
            + " } }";
    assertThat(run(src, new RedundantNullCheckCheck()))
        .as("redundant null check fires")
        .isNotEmpty();
  }

  @Test
  void redundantNullCheck_noFireOnSingleGuard() {
    String src =
        "class N {"
            + " void m(String s) {"
            + " if (s == null) return;"
            + " System.out.println(s.length());"
            + " } }";
    assertThat(run(src, new RedundantNullCheckCheck()))
        .as("single null guard does not fire")
        .isEmpty();
  }

  // ============== BitwiseAnd (pl-0262) ==============
  @Test
  void bitwiseAnd_firesOnAmpInIf() {
    String src =
        "class N { void m(boolean a, boolean b) {" + " if (a & b) System.out.println(\"x\"); } }";
    assertThat(run(src, new BitwiseAndCheck())).as("& in if fires").isNotEmpty();
  }

  @Test
  void bitwiseAnd_noFireOnLogicalAnd() {
    String src =
        "class N { void m(boolean a, boolean b) {" + " if (a && b) System.out.println(\"x\"); } }";
    assertThat(run(src, new BitwiseAndCheck())).as("&& in if does not fire").isEmpty();
  }

  // ============== BitwiseOr (pl-0270) ==============
  @Test
  void bitwiseOr_firesOnPipeInIf() {
    String src =
        "class N { void m(boolean a, boolean b) {" + " if (a | b) System.out.println(\"x\"); } }";
    assertThat(run(src, new BitwiseOrCheck())).as("| in if fires").isNotEmpty();
  }

  // ============== SignedBitCheck (pl-0263) ==============
  @Test
  void signedBitCheck_firesOnNegativeHighBitMask() {
    String src = "class N { boolean m(int x) {" + " return (x & -2147483648) != 0; } }";
    assertThat(run(src, new SignedBitCheckCheck())).as("high-bit mask fires").isNotEmpty();
  }

  @Test
  void signedBitCheck_noFireOnPosMask() {
    String src = "class N { boolean m(int x) {" + " return (x & 0xFF) != 0; } }";
    assertThat(run(src, new SignedBitCheckCheck())).as("positive mask does not fire").isEmpty();
  }

  // ============== SerializableSubclass (pl-0264) ==============
  @Test
  void serializableSubclass_firesOnExtendsThrowableNoImpl() {
    String src = "import java.io.Serializable;" + "class N extends Throwable { }";
    assertThat(run(src, new SerializableSubclassCheck()))
        .as("extends Throwable without Serializable fires")
        .isNotEmpty();
  }

  @Test
  void serializableSubclass_noFireOnExtendsThrowableWithImpl() {
    String src =
        "import java.io.Serializable;" + "class N extends Throwable implements Serializable { }";
    assertThat(run(src, new SerializableSubclassCheck()))
        .as("with Serializable impl does not fire")
        .isEmpty();
  }

  // ============== ExposeMutableRep2 (pl-0265) ==============
  @Test
  void exposeMutableRep2_firesOnArrayField() {
    String src = "class N {" + " private int[] data;" + " N(int[] d) { data = d; } }";
    assertThat(run(src, new ExposeMutableRepStorageCheck()))
        .as("array stored from param fires")
        .isNotEmpty();
  }

  // ============== MutableEnumField (pl-0266) ==============
  @Test
  void mutableEnumField_firesOnNonFinalField() {
    String src = "enum E { A; int x; }";
    assertThat(run(src, new MutableEnumFieldCheck())).as("non-final enum field fires").isNotEmpty();
  }

  @Test
  void mutableEnumField_noFireOnFinalField() {
    String src = "enum E { A; final int x = 0; }";
    assertThat(run(src, new MutableEnumFieldCheck()))
        .as("final enum field does not fire")
        .isEmpty();
  }

  // ============== StaticWriteFromInstance (pl-0267) ==============
  @Test
  void staticWriteFromInstance_firesOnClassFieldAssign() {
    String src = "class N {" + " static int x;" + " void m() { N.x = 1; } }";
    assertThat(run(src, new StaticWriteFromInstanceCheck()))
        .as("static field assigned by name fires")
        .isNotEmpty();
  }

  // ============== EqualsNoObject (pl-0268) ==============
  @Test
  void equalsNoObject_firesOnPrimitiveParam() {
    String src = "class N {" + " public boolean equals(int o) { return true; } }";
    assertThat(run(src, new EqualsNoObjectCheck()))
        .as("equals with primitive param fires")
        .isNotEmpty();
  }

  @Test
  void equalsNoObject_noFireOnObjectParam() {
    String src = "class N {" + " public boolean equals(Object o) { return true; } }";
    assertThat(run(src, new EqualsNoObjectCheck()))
        .as("equals with Object param does not fire")
        .isEmpty();
  }

  // ============== NonSerializableField (pl-0269) ==============
  @Test
  void nonSerializableField_firesOnThreadInSerializable() {
    String src =
        "import java.io.Serializable;" + "class N implements Serializable {" + " Thread t; }";
    assertThat(run(src, new NonSerializableFieldCheck()))
        .as("Thread field in Serializable class fires")
        .isNotEmpty();
  }

  @Test
  void nonSerializableField_noFireOnIntField() {
    String src = "import java.io.Serializable;" + "class N implements Serializable {" + " int x; }";
    assertThat(run(src, new NonSerializableFieldCheck()))
        .as("int field in Serializable class does not fire")
        .isEmpty();
  }
}
