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
package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.error.AvoidCatchingThrowableCheck;
import org.plumb.style.error.DoNotExtendJavaLangErrorCheck;
import org.plumb.style.naming.BooleanGetMethodNameCheck;
import org.plumb.style.naming.UnnecessaryFullyQualifiedClassNameCheck;
import org.plumb.style.performance.AvoidCalendarDateInstanceCheck;
import org.plumb.style.performance.AvoidStringBufferFieldCheck;
import org.plumb.style.performance.DoNotCallGarbageCollectionCheck;
import org.plumb.style.performance.ReturnEmptyCollectionNotNullCheck;
import org.plumb.style.performance.UnnecessaryCaseChangeCheck;
import org.plumb.style.performance.UseAssertEqualsInsteadOfAssertTrueCheck;
import org.plumb.style.performance.UseShortArrayInitializerCheck;
import org.plumb.style.style.AvoidMessageDigestFieldCheck;
import org.plumb.style.style.AvoidNativeMethodsCheck;
import org.plumb.style.style.AvoidProtectedFieldInFinalClassCheck;
import org.plumb.style.style.AvoidThreadGroupCheck;
import org.plumb.style.style.AvoidUsingOctalValuesCheck;
import org.plumb.style.style.AvoidUsingVolatileCheck;
import org.plumb.style.style.CallSuperInConstructorCheck;
import org.plumb.style.style.ConfusingTernaryCheck;
import org.plumb.style.style.ControlStatementBracesCheck;
import org.plumb.style.style.EqualsArgumentTypeCheck;
import org.plumb.style.style.MethodTooLongCheck;
import org.plumb.style.style.SingletonClassReturningNewInstanceCheck;
import org.plumb.style.style.UnusedAssignmentCheck;
import org.plumb.style.style.UseAssertNullInsteadOfAssertTrueCheck;
import org.plumb.style.style.UseAssertSameInsteadOfAssertTrueCheck;
import org.plumb.style.style.UseConcurrentHashMapCheck;
import org.plumb.style.style.UseDiamondOperatorCheck;

/**
 * / / style checkers batch 1 (28 new rules in plumb-style, pl-0072 through pl-0100).
 *
 * <p>Each new check is exercised against a minimal source snippet that triggers its fire / no-fire
 * condition. The test asserts the expected presence / absence of a finding in the
 *
 * <p>Style checks are pure AST pattern matches ? no taint or inter-procedural analysis is required,
 * so each test is a single-line source string + a single assertion.
 */
class R265PMDStyleBatch1Test {

  private final RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);

  private List<?> run(String src, AstChecker check) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("R265Test.java"), src);
    StyleContext ctx = new StyleContext(ps, rules);
    new AstDispatcher().register(check).run(ctx);
    return ctx.findings();
  }

  // ============== AvoidCatchingThrowable (pl-0072) ==============
  @Test
  void avoidCatchingThrowable_fires() {
    String src = "class N { void m() { try { int x = 1; } catch (Throwable t) {} } }";
    assertThat(run(src, new AvoidCatchingThrowableCheck()))
        .as("catch (Throwable) fires")
        .isNotEmpty();
  }

  @Test
  void avoidCatchingThrowable_noFireOnException() {
    String src = "class N { void m() { try { int x = 1; } catch (Exception e) {} } }";
    assertThat(run(src, new AvoidCatchingThrowableCheck()))
        .as("catch (Exception) does not fire")
        .isEmpty();
  }

  // ============== DoNotCallGarbageCollection (pl-0073) ==============
  @Test
  void doNotCallGc_fires() {
    String src = "class N { void m() { System.gc(); } }";
    assertThat(run(src, new DoNotCallGarbageCollectionCheck()))
        .as("System.gc() fires")
        .isNotEmpty();
  }

  @Test
  void doNotCallGc_noFireOnOtherMethod() {
    String src = "class N { void m() { System.out.println(\"hi\"); } }";
    assertThat(run(src, new DoNotCallGarbageCollectionCheck()))
        .as("System.out.println does not fire")
        .isEmpty();
  }

  // ============== DoNotExtendJavaLangError (pl-0074) ==============
  @Test
  void doNotExtendError_fires() {
    String src = "class N extends Error { }";
    assertThat(run(src, new DoNotExtendJavaLangErrorCheck()))
        .as("extends Error fires")
        .isNotEmpty();
  }

  @Test
  void doNotExtendError_noFireOnObject() {
    String src = "class N { }";
    assertThat(run(src, new DoNotExtendJavaLangErrorCheck()))
        .as("plain class does not fire")
        .isEmpty();
  }

  // ============== AvoidMessageDigestField (pl-0075) ==============
  @Test
  void avoidMessageDigestField_fires() {
    String src = "class N { private MessageDigest md; }";
    assertThat(run(src, new AvoidMessageDigestFieldCheck()))
        .as("MessageDigest field fires")
        .isNotEmpty();
  }

  @Test
  void avoidMessageDigestField_noFireOnString() {
    String src = "class N { private String s; }";
    assertThat(run(src, new AvoidMessageDigestFieldCheck()))
        .as("String field does not fire")
        .isEmpty();
  }

  // ============== AvoidNativeMethods (pl-0076) ==============
  @Test
  void avoidNativeMethods_fires() {
    String src = "class N { public native void m(); }";
    assertThat(run(src, new AvoidNativeMethodsCheck())).as("native method fires").isNotEmpty();
  }

  @Test
  void avoidNativeMethods_noFireOnNonNative() {
    String src = "class N { public void m() {} }";
    assertThat(run(src, new AvoidNativeMethodsCheck()))
        .as("non-native method does not fire")
        .isEmpty();
  }

  // ============== AvoidThreadGroup (pl-0077) ==============
  @Test
  void avoidThreadGroup_fires() {
    String src = "class N { void m() { ThreadGroup g = new ThreadGroup(\"x\"); } }";
    assertThat(run(src, new AvoidThreadGroupCheck())).as("new ThreadGroup fires").isNotEmpty();
  }

  // ============== AvoidUsingOctalValues (pl-0078) ==============
  @Test
  void avoidOctal_fires() {
    String src = "class N { int x = 0755; }";
    assertThat(run(src, new AvoidUsingOctalValuesCheck()))
        .as("octal literal 0755 fires")
        .isNotEmpty();
  }

  @Test
  void avoidOctal_noFireOnHex() {
    String src = "class N { int x = 0xFF; }";
    assertThat(run(src, new AvoidUsingOctalValuesCheck()))
        .as("hex literal does not fire")
        .isEmpty();
  }

  // ============== AvoidUsingVolatile (pl-0079) ==============
  @Test
  void avoidVolatile_fires() {
    String src = "class N { private volatile int x; }";
    assertThat(run(src, new AvoidUsingVolatileCheck())).as("volatile field fires").isNotEmpty();
  }

  // ============== BooleanGetMethodName (pl-0080) ==============
  @Test
  void booleanGet_fires() {
    String src = "class N { public boolean getReady() { return true; } }";
    assertThat(run(src, new BooleanGetMethodNameCheck()))
        .as("getXxx returning boolean fires")
        .isNotEmpty();
  }

  @Test
  void booleanGet_noFireOnIs() {
    String src = "class N { public boolean isReady() { return true; } }";
    assertThat(run(src, new BooleanGetMethodNameCheck())).as("isXxx does not fire").isEmpty();
  }

  // ============== CallSuperInConstructor (pl-0081) ==============
  @Test
  void callSuper_fires() {
    String src = "class P { P() {} }" + "class N extends P { N() { int x = 1; } }";
    assertThat(run(src, new CallSuperInConstructorCheck()))
        .as("constructor without super() fires")
        .isNotEmpty();
  }

  @Test
  void callSuper_noFireWithSuper() {
    String src = "class P { P() {} }" + "class N extends P { N() { super(); } }";
    assertThat(run(src, new CallSuperInConstructorCheck()))
        .as("constructor with super() does not fire")
        .isEmpty();
  }

  // ============== ConfusingTernary (pl-0082) ==============
  @Test
  void confusingTernary_fires() {
    String src =
        "class N { int m(int a, int b, int c, int d) {" + " return a > 0 ? b: c > 0 ? d: 0; } }";
    assertThat(run(src, new ConfusingTernaryCheck())).as("nested ternary fires").isNotEmpty();
  }

  // ============== AvoidCalendarDateInstance (pl-0083) ==============
  @Test
  void avoidCalendarDateInstance_fires() {
    String src = "import java.util.Date; class N { Date d = new Date(); }";
    assertThat(run(src, new AvoidCalendarDateInstanceCheck())).as("new Date() fires").isNotEmpty();
  }

  // ============== AvoidStringBufferField (pl-0084) ==============
  @Test
  void avoidStringBufferField_fires() {
    String src = "class N { private StringBuffer sb; }";
    assertThat(run(src, new AvoidStringBufferFieldCheck()))
        .as("StringBuffer field fires")
        .isNotEmpty();
  }

  // ============== ControlStatementBraces (pl-0085) ==============
  @Test
  void needBraces_firesOnIf() {
    String src = "class N { void m(int x) { if (x > 0) System.out.println(\"a\"); } }";
    assertThat(run(src, new ControlStatementBracesCheck()))
        .as("if without braces fires")
        .isNotEmpty();
  }

  @Test
  void needBraces_noFireWithBraces() {
    String src = "class N { void m(int x) { if (x > 0) { System.out.println(\"a\"); } } }";
    assertThat(run(src, new ControlStatementBracesCheck()))
        .as("if with braces does not fire")
        .isEmpty();
  }

  // ============== UnnecessaryCaseChange (pl-0086) ==============
  @Test
  void unnecessaryCaseChange_fires() {
    String src =
        "class N { boolean m(String a, String b) {" + " return a.toLowerCase().equals(b); } }";
    assertThat(run(src, new UnnecessaryCaseChangeCheck()))
        .as("toLowerCase().equals fires")
        .isNotEmpty();
  }

  @Test
  void unnecessaryCaseChange_noFireOnEqualsIgnoreCase() {
    String src = "class N { boolean m(String a, String b) {" + " return a.equalsIgnoreCase(b); } }";
    assertThat(run(src, new UnnecessaryCaseChangeCheck()))
        .as("equalsIgnoreCase does not fire")
        .isEmpty();
  }

  // ============== UseShortArrayInitializer (pl-0087) ==============
  @Test
  void useShortArrayInit_fires() {
    String src = "class N { int[] x = new int[] { 1, 2, 3 }; }";
    assertThat(run(src, new UseShortArrayInitializerCheck()))
        .as("new int[] { } fires")
        .isNotEmpty();
  }

  // ============== AvoidProtectedFieldInFinalClass (pl-0088) ==============
  @Test
  void protectedInFinalClass_fires() {
    String src = "final class N { protected int x; }";
    assertThat(run(src, new AvoidProtectedFieldInFinalClassCheck()))
        .as("protected in final class fires")
        .isNotEmpty();
  }

  @Test
  void protectedInFinalClass_noFireOnNonFinal() {
    String src = "class N { protected int x; }";
    assertThat(run(src, new AvoidProtectedFieldInFinalClassCheck()))
        .as("protected in non-final class does not fire")
        .isEmpty();
  }

  // ============== UseAssertEquals (pl-0089) ==============
  @Test
  void useAssertEquals_fires() {
    String src =
        "import static org.junit.jupiter.api.Assertions.assertTrue;"
            + "class N { void m(int a, int b) { assertTrue(a == b); } }";
    assertThat(run(src, new UseAssertEqualsInsteadOfAssertTrueCheck()))
        .as("assertTrue(a == b) fires")
        .isNotEmpty();
  }

  // ============== UseAssertNull (pl-0090) ==============
  @Test
  void useAssertNull_fires() {
    String src =
        "import static org.junit.jupiter.api.Assertions.assertTrue;"
            + "class N { void m(Object x) { assertTrue(x == null); } }";
    assertThat(run(src, new UseAssertNullInsteadOfAssertTrueCheck()))
        .as("assertTrue(x == null) fires")
        .isNotEmpty();
  }

  // ============== UseAssertSame (pl-0091) ==============
  @Test
  void useAssertSame_fires() {
    String src =
        "import static org.junit.jupiter.api.Assertions.assertTrue;"
            + "class N { void m(Object a, Object b) { assertTrue(a == b); } }";
    assertThat(run(src, new UseAssertSameInsteadOfAssertTrueCheck()))
        .as("assertTrue(a == b) fires")
        .isNotEmpty();
  }

  // ============== UseConcurrentHashMap (pl-0092) ==============
  @Test
  void useConcurrentHashMap_fires() {
    String src =
        "import java.util.HashMap; class N { private HashMap<String,String> m = new HashMap<>(); }";
    assertThat(run(src, new UseConcurrentHashMapCheck()))
        .as("non-final HashMap field fires")
        .isNotEmpty();
  }

  @Test
  void useConcurrentHashMap_noFireOnFinal() {
    String src =
        "import java.util.HashMap; class N { private final HashMap<String,String> m = new"
            + " HashMap<>(); }";
    assertThat(run(src, new UseConcurrentHashMapCheck()))
        .as("final HashMap field does not fire")
        .isEmpty();
  }

  // ============== ReturnEmptyCollectionNotNull (pl-0093) ==============
  @Test
  void returnEmptyNotNull_fires() {
    String src = "import java.util.List; class N { List<String> m() { return null; } }";
    assertThat(run(src, new ReturnEmptyCollectionNotNullCheck()))
        .as("return null for List fires")
        .isNotEmpty();
  }

  @Test
  void returnEmptyNotNull_noFireOnString() {
    String src = "class N { String m() { return null; } }";
    assertThat(run(src, new ReturnEmptyCollectionNotNullCheck()))
        .as("return null for String does not fire")
        .isEmpty();
  }

  // ============== EqualsArgumentType (pl-0094) ==============
  @Test
  void equalsArgType_fires() {
    String src = "class N { public boolean equals(String s) { return false; } }";
    assertThat(run(src, new EqualsArgumentTypeCheck())).as("equals(String) fires").isNotEmpty();
  }

  @Test
  void equalsArgType_noFireOnObject() {
    String src = "class N { public boolean equals(Object o) { return false; } }";
    assertThat(run(src, new EqualsArgumentTypeCheck()))
        .as("equals(Object) does not fire")
        .isEmpty();
  }

  // ============== UseDiamond (pl-0096) ==============
  @Test
  void useDiamond_fires() {
    String src =
        "import java.util.ArrayList; import java.util.List;"
            + "class N { List<String> m() { return new ArrayList<String>(); } }";
    assertThat(run(src, new UseDiamondOperatorCheck()))
        .as("new ArrayList<String>() fires")
        .isNotEmpty();
  }

  // ============== UnnecessaryFQN (pl-0097) ==============
  @Test
  void unnecessaryFqn_fires() {
    String src = "class N { java.util.List<String> m() { return null; } }";
    assertThat(run(src, new UnnecessaryFullyQualifiedClassNameCheck()))
        .as("fully qualified name in code fires")
        .isNotEmpty();
  }

  // ============== SingletonReturningNew (pl-0098) ==============
  @Test
  void singletonReturningNew_fires() {
    String src =
        "class N {" + " public static String m() { return new java.lang.String(\"x\"); } }";
    assertThat(run(src, new SingletonClassReturningNewInstanceCheck()))
        .as("static method returning new instance of different type fires")
        .isNotEmpty();
  }

  // ============== UnusedAssignment (pl-0099) ==============
  @Test
  void unusedAssignment_fires() {
    String src = "class N { void m() { int x = 1; x = 2; System.out.println(x); } }";
    assertThat(run(src, new UnusedAssignmentCheck()))
        .as("assignment expression fires")
        .isNotEmpty();
  }

  // ============== MethodLength (pl-0100) ==============
  @Test
  void methodLength_noFireOnShort() {
    String src = "class N { void m() { int x = 1; x++; } }";
    assertThat(run(src, new MethodTooLongCheck())).as("short method does not fire").isEmpty();
  }
}
