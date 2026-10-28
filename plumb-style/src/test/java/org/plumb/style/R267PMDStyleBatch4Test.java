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
import org.plumb.style.design.AbstractClassWithoutAbstractMethodCheck;
import org.plumb.style.error.EmptyCatchBlockCheck;
import org.plumb.style.error.EmptyFinalizerCheck;
import org.plumb.style.error.FinalizeShouldBeProtectedCheck;
import org.plumb.style.error.IdenticalCatchBranchesCheck;
import org.plumb.style.naming.AbstractNamingCheck;
import org.plumb.style.naming.InvalidLogMessageFormatCheck;
import org.plumb.style.naming.LocalVariableNamingConventionsCheck;
import org.plumb.style.naming.LongVariableCheck;
import org.plumb.style.naming.MethodNamingConventionsCheck;
import org.plumb.style.performance.DontUseFloatTypeForLoopIndicesCheck;
import org.plumb.style.security.AvoidUsingHardCodedIPCheck;
import org.plumb.style.style.AssignmentInOperandCheck;
import org.plumb.style.style.AvoidDeeplyNestedIfStmtsCheck;
import org.plumb.style.style.AvoidReassigningParametersCheck;
import org.plumb.style.style.BigDecimalDoubleConstructorCheck;
import org.plumb.style.style.ClassWithOnlyPrivateConstructorsShouldBeFinalCheck;
import org.plumb.style.style.ForLoopCanBeForeachCheck;
import org.plumb.style.style.ForLoopVariableCountCheck;
import org.plumb.style.style.ImmutableFieldCheck;
import org.plumb.style.style.ImportFromSamePackageCheck;
import org.plumb.style.style.JUnitAssertionsShouldIncludeMessageCheck;
import org.plumb.style.style.JUnitTestsShouldIncludeAssertCheck;

/** / / style checkers batch 4 (25 more rules, pl-0160 through pl-0189). */
class R267PMDStyleBatch4Test {

  private final RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);

  private List<?> run(String src, AstChecker check) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("R267Test.java"), src);
    StyleContext ctx = new StyleContext(ps, rules);
    new AstDispatcher().register(check).run(ctx);
    return ctx.findings();
  }

  // ============== AbstractClassWithoutAbstractMethod (pl-0160) ==============
  @Test
  void abstractNoMethod_fires() {
    String src = "abstract class N { void m() {} }";
    assertThat(run(src, new AbstractClassWithoutAbstractMethodCheck()))
        .as("abstract class with no abstract method fires")
        .isNotEmpty();
  }

  // ============== AbstractNaming (pl-0161) ==============
  @Test
  void abstractNaming_fires() {
    String src = "abstract class MyClass { abstract void m(); }";
    assertThat(run(src, new AbstractNamingCheck()))
        .as("abstract class not prefixed with Abstract fires")
        .isNotEmpty();
  }

  // ============== AssignmentInOperand (pl-0162) ==============
  @Test
  void assignInOperand_fires() {
    String src = "class N { void m() {" + " int x; if ((x = 1) > 0) { } } }";
    assertThat(run(src, new AssignmentInOperandCheck()))
        .as("assignment in if-condition fires")
        .isNotEmpty();
  }

  // ============== AvoidDeeplyNestedIfStmts (pl-0163) ==============
  @Test
  void deeplyNestedIf_fires() {
    String src =
        "class N { void m(int a, int b, int c, int d, int e) {"
            + " if (a > 0) { if (b > 0) { if (c > 0) { if (d > 0) { if (e > 0) { } } } } } } }";
    assertThat(run(src, new AvoidDeeplyNestedIfStmtsCheck()))
        .as("5-level nested if fires")
        .isNotEmpty();
  }

  // ============== AvoidReassigningParameters (pl-0166) ==============
  @Test
  void reassignParameter_fires() {
    String src = "class N { void m(int x) { x = 2; } }";
    assertThat(run(src, new AvoidReassigningParametersCheck()))
        .as("reassign parameter fires")
        .isNotEmpty();
  }

  // ============== BigDecimalDoubleConstructor (pl-0167) ==============
  @Test
  void bigDecDouble_fires() {
    String src =
        "import java.math.BigDecimal; class N {"
            + " BigDecimal m() { return new BigDecimal(0.1); } }";
    assertThat(run(src, new BigDecimalDoubleConstructorCheck()))
        .as("new BigDecimal(0.1) fires")
        .isNotEmpty();
  }

  // ============== ClassWithOnlyPrivateConstructorsShouldBeFinal (pl-0168) ==============
  @Test
  void privateCtorsFinal_fires() {
    String src = "class N { private N() {} }";
    assertThat(run(src, new ClassWithOnlyPrivateConstructorsShouldBeFinalCheck()))
        .as("class with only private ctors but not final fires")
        .isNotEmpty();
  }

  // ============== DontUseFloatTypeForLoopIndices (pl-0169) ==============
  @Test
  void floatLoopIndex_fires() {
    String src = "class N { void m() {" + " for (double d = 0.0; d < 10.0; d += 0.1) { } } }";
    assertThat(run(src, new DontUseFloatTypeForLoopIndicesCheck()))
        .as("for-loop with double index fires")
        .isNotEmpty();
  }

  // ============== EmptyCatchBlock (pl-0170) ==============
  @Test
  void emptyCatchBlock_fires() {
    String src = "class N { void m() { try { int x = 1; }" + " catch (Exception e) { } } }";
    assertThat(run(src, new EmptyCatchBlockCheck())).as("empty catch block fires").isNotEmpty();
  }

  // ============== EmptyFinalizer (pl-0171) ==============
  @Test
  void emptyFinalizer_fires() {
    String src = "class N { public void finalize() {} }";
    assertThat(run(src, new EmptyFinalizerCheck())).as("empty finalize() fires").isNotEmpty();
  }

  // ============== FinalizeShouldBeProtected (pl-0175) ==============
  @Test
  void finalizeProtected_fires() {
    String src =
        "class N { public void finalize() {"
            + " try { super.finalize(); } catch (Throwable t) { } } }";
    assertThat(run(src, new FinalizeShouldBeProtectedCheck()))
        .as("public finalize() fires")
        .isNotEmpty();
  }

  // ============== ForLoopCanBeForeach (pl-0176) ==============
  @Test
  void forCanBeForeach_fires() {
    String src =
        "import java.util.List; class N {"
            + " void m(List<String> l) {"
            + " for (int i = 0; i < l.size(); i++) { String s = l.get(i); } } }";
    assertThat(run(src, new ForLoopCanBeForeachCheck()))
        .as("for-loop with l.get(i) fires")
        .isNotEmpty();
  }

  // ============== ForLoopVariableCount (pl-0177) ==============
  @Test
  void forVarCount_fires() {
    String src = "class N { void m() {" + " for (int i = 0, j = 0; i < 10; i++) { } } }";
    assertThat(run(src, new ForLoopVariableCountCheck()))
        .as("for with 2 init variables fires")
        .isNotEmpty();
  }

  // ============== IdenticalCatchBranches (pl-0178) ==============
  @Test
  void identicalCatch_fires() {
    String src =
        "class N { void m() { try { int x = 1; }"
            + " catch (RuntimeException e) { System.out.println(\"e\"); }"
            + " catch (Exception e) { System.out.println(\"e\"); } } }";
    assertThat(run(src, new IdenticalCatchBranchesCheck()))
        .as("identical catch bodies fire")
        .isNotEmpty();
  }

  // ============== ImmutableField (pl-0179) ==============
  @Test
  void immutableField_fires() {
    String src = "class N { private int x = 1; }";
    assertThat(run(src, new ImmutableFieldCheck()))
        .as("non-final never-reassigned field fires")
        .isNotEmpty();
  }

  // ============== ImportFromSamePackage (pl-0180) ==============
  @Test
  void importSamePackage_fires() {
    String src = "package com.example;" + "import com.example.Foo;" + "class N { Foo f; }";
    assertThat(run(src, new ImportFromSamePackageCheck()))
        .as("import from same package fires")
        .isNotEmpty();
  }

  // ============== InvalidLogMessageFormat (pl-0181) ==============
  @Test
  void invalidLog_fires() {
    String src =
        "class N { void m() {"
            + " org.slf4j.LoggerFactory.getLogger(N.class).info(\"x = {}\", 5);"
            + " org.slf4j.LoggerFactory.getLogger(N.class).info(\"no placeholder\", 5);"
            + " } }";
    // The 2nd log call has no {} placeholder.
    // Both should be detected; we just assert non-empty.
    assertThat(run(src, new InvalidLogMessageFormatCheck()))
        .as("log with no placeholder fires")
        .isNotEmpty();
  }

  // ============== JUnitAssertionsShouldIncludeMessage (pl-0182) ==============
  @Test
  void junitMessage_fires() {
    String src =
        "import static org.junit.jupiter.api.Assertions.assertEquals;"
            + "class N { void m(int a, int b) { assertEquals(a, b); } }";
    assertThat(run(src, new JUnitAssertionsShouldIncludeMessageCheck()))
        .as("assertEquals without message fires")
        .isNotEmpty();
  }

  // ============== JUnitTestsShouldIncludeAssert (pl-0183) ==============
  @Test
  void junitNoAssert_fires() {
    String src = "import org.junit.jupiter.api.Test;" + "class N { @Test void m() { int x = 1; } }";
    assertThat(run(src, new JUnitTestsShouldIncludeAssertCheck()))
        .as("@Test without assert fires")
        .isNotEmpty();
  }

  // ============== LocalVariableNamingConventions (pl-0184) ==============
  @Test
  void localVarNaming_fires() {
    String src = "class N { void m() { int My_Var = 1; } }";
    assertThat(run(src, new LocalVariableNamingConventionsCheck()))
        .as("local var with underscore fires")
        .isNotEmpty();
  }

  // ============== LongVariable (pl-0185) ==============
  @Test
  void longVariable_fires() {
    String src =
        "class N { void m() {"
            + " int thisIsAnExtremelyLongVariableNameThatExceedsTheLimit = 1; } }";
    assertThat(run(src, new LongVariableCheck())).as("var name > 30 chars fires").isNotEmpty();
  }

  // ============== MethodNamingConventions (pl-0189) ==============
  @Test
  void methodNaming_fires() {
    String src = "class N { public void my_method() {} }";
    assertThat(run(src, new MethodNamingConventionsCheck()))
        .as("method with underscore fires")
        .isNotEmpty();
  }

  // ============== AvoidUsingHardCodedIP (pl-0188) ==============
  @Test
  void hardcodedIP_fires() {
    String src = "class N { String s = \"192.168.1.1\"; }";
    assertThat(run(src, new AvoidUsingHardCodedIPCheck())).as("hard-coded IP fires").isNotEmpty();
  }
}
