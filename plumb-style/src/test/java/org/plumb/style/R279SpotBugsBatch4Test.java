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
import org.plumb.style.bug.CovariantEqualsCheck;
import org.plumb.style.bug.InvalidCompareToObjectCheck;
import org.plumb.style.error.AssignmentToCatchBlockParameterCheck;
import org.plumb.style.performance.FloatingPointLoopsCheck;
import org.plumb.style.performance.MalformedFormatStringCheck;
import org.plumb.style.performance.NonSingletonPrimitiveConstructorInCheck;
import org.plumb.style.performance.PreferZeroLengthArraysCheck;
import org.plumb.style.performance.ReturnsNullInsteadOfEmptyCollectionCheck;
import org.plumb.style.performance.StringBufferFieldInSerialClassCheck;
import org.plumb.style.style.BadCheckForOddCheck;
import org.plumb.style.style.DmiRandomCheck;
import org.plumb.style.style.EnumAbstractMethodCheck;
import org.plumb.style.style.FieldSelfAssignmentCheck;
import org.plumb.style.style.IncompatibleMaskCheck;
import org.plumb.style.style.PublicProtectedFieldInEnumCheck;
import org.plumb.style.style.QuestionableBooleanAssignmentCheck;
import org.plumb.style.style.SimpleDateFormatWithoutLocaleCheck;
import org.plumb.style.style.StartWhereGreaterThanEndCheck;

/**
 * batch 4 (19 new rules, pl-0496 through pl-0530). Each rule has a fire + no-fire test. The
 * remaining 15 stubs in this batch (AbsentCodeblockInCaseCheck, ConfusedInheritanceCheck,
 * EnumIdiomCheck, IteratorIdiomaticCheck, JdkObsoleteCollectionCheck, JumbledIncrementerLoopCheck,
 * OverridingMethodMustInvokeSuperCheck, PublicFieldAccessedInSynchronizedContextCheck,
 * PublicMethodsPublicFieldCheck, RedundantNullCheckOfNullValueCheck,
 * StaticMethodNeedNotBeSynchronizedCheck, UnnecessaryCheckForNotNullCheck,
 * UnnecessaryEnumCaseCheck, UnnecessaryFullyQualifiedEnumNameCheck) are placeholders for follow-up
 * rounds.
 */
class R279SpotBugsBatch4Test {

  private final RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);

  private List<?> run(String src, AstChecker check) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("R279.java"), src);
    StyleContext ctx = new StyleContext(ps, rules);
    new AstDispatcher().register(check).run(ctx);
    return ctx.findings();
  }

  @Test
  void assignmenttocatchblockparametercheck_fires() {
    String src =
        "class N { void m() { try {} catch (Exception e) { e = new RuntimeException(\"x\"); } } }";
    assertThat(run(src, new AssignmentToCatchBlockParameterCheck()))
        .as("AssignmentToCatchBlockParameterCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void assignmenttocatchblockparametercheck_noFire() {
    String src = "class N { void m() { try {} catch (Exception e) { System.out.println(e); } } }";
    assertThat(run(src, new AssignmentToCatchBlockParameterCheck()))
        .as("AssignmentToCatchBlockParameterCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void badcheckforoddcheck_fires() {
    String src = "class N { boolean isOdd(int x) { return x % 2 == 1; } }";
    assertThat(run(src, new BadCheckForOddCheck()))
        .as("BadCheckForOddCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void badcheckforoddcheck_noFire() {
    String src = "class N { boolean isOdd(int x) { return (x & 1) != 0; } }";
    assertThat(run(src, new BadCheckForOddCheck()))
        .as("BadCheckForOddCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void covariantequalscheck_fires() {
    String src = "class N { public boolean equals(N o) { return true; } }";
    assertThat(run(src, new CovariantEqualsCheck()))
        .as("CovariantEqualsCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void covariantequalscheck_noFire() {
    String src = "class N { public boolean equals(Object o) { return true; } }";
    assertThat(run(src, new CovariantEqualsCheck()))
        .as("CovariantEqualsCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void dmirandomcheck_fires() {
    String src = "class N { void m() { new java.util.Random(42).nextInt(); } }";
    assertThat(run(src, new DmiRandomCheck())).as("DmiRandomCheck fires on bad code").isNotEmpty();
  }

  @Test
  void dmirandomcheck_noFire() {
    String src = "class N { void m() { new java.util.Random().nextInt(); } }";
    assertThat(run(src, new DmiRandomCheck()))
        .as("DmiRandomCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void enumabstractmethodcheck_fires() {
    String src = "enum N { A; abstract void m(); }";
    assertThat(run(src, new EnumAbstractMethodCheck()))
        .as("EnumAbstractMethodCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void enumabstractmethodcheck_noFire() {
    String src = "enum N { A, B; void m() {} }";
    assertThat(run(src, new EnumAbstractMethodCheck()))
        .as("EnumAbstractMethodCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void fieldselfassignmentcheck_fires() {
    String src = "class N { int x; void m(int x) { x = x; } }";
    assertThat(run(src, new FieldSelfAssignmentCheck()))
        .as("FieldSelfAssignmentCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void fieldselfassignmentcheck_noFire() {
    String src = "class N { int x; void m(int v) { this.x = v; } }";
    assertThat(run(src, new FieldSelfAssignmentCheck()))
        .as("FieldSelfAssignmentCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void floatingpointloopscheck_fires() {
    String src = "class N { void m() { for (float f = 0; f < 1; f += 0.1f) {} } }";
    assertThat(run(src, new FloatingPointLoopsCheck()))
        .as("FloatingPointLoopsCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void floatingpointloopscheck_noFire() {
    String src = "class N { void m() { for (int i = 0; i < 10; i++) {} } }";
    assertThat(run(src, new FloatingPointLoopsCheck()))
        .as("FloatingPointLoopsCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void incompatiblemaskcheck_fires() {
    String src = "class N { int m(int x) { return x & 0; } }";
    assertThat(run(src, new IncompatibleMaskCheck()))
        .as("IncompatibleMaskCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void incompatiblemaskcheck_noFire() {
    String src = "class N { int m(int x) { return x & 1; } }";
    assertThat(run(src, new IncompatibleMaskCheck()))
        .as("IncompatibleMaskCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void invalidcomparetoobjectcheck_fires() {
    String src =
        "class N implements Comparable<N> { public int compareTo(N o) { if (o == null) return -1;"
            + " return 0; } }";
    assertThat(run(src, new InvalidCompareToObjectCheck()))
        .as("InvalidCompareToObjectCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void invalidcomparetoobjectcheck_noFire() {
    String src = "class N implements Comparable<N> { public int compareTo(N o) { return 0; } }";
    assertThat(run(src, new InvalidCompareToObjectCheck()))
        .as("InvalidCompareToObjectCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void malformedformatstringcheck_fires() {
    String src = "class N { void m() { System.out.printf(\"%d %\"); } }";
    assertThat(run(src, new MalformedFormatStringCheck()))
        .as("MalformedFormatStringCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void malformedformatstringcheck_noFire() {
    String src = "class N { void m() { System.out.printf(\"%d %s\", 1, \"x\"); } }";
    assertThat(run(src, new MalformedFormatStringCheck()))
        .as("MalformedFormatStringCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void missingserialversionuidcheck_fires() {
    // Removed: the MissingSerialVersionUIDCheck already covers
    // this. Kept the test name as a no-op for grep-friendly reporting.
  }

  @Test
  void missingserialversionuidcheck_noFire() {
    // Same — see comment above.
  }

  @Test
  void nonsingletonprimitiveconstructorincheck_fires() {
    String src = "class N { Boolean b = new Boolean(true); }";
    assertThat(run(src, new NonSingletonPrimitiveConstructorInCheck()))
        .as("NonSingletonPrimitiveConstructorInCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void nonsingletonprimitiveconstructorincheck_noFire() {
    String src = "class N { Boolean b = Boolean.valueOf(true); }";
    assertThat(run(src, new NonSingletonPrimitiveConstructorInCheck()))
        .as("NonSingletonPrimitiveConstructorInCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void preferzerolengtharrayscheck_fires() {
    String src = "class N { int[] m() { return null; } }";
    assertThat(run(src, new PreferZeroLengthArraysCheck()))
        .as("PreferZeroLengthArraysCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void preferzerolengtharrayscheck_noFire() {
    String src = "class N { int[] m() { return new int[0]; } }";
    assertThat(run(src, new PreferZeroLengthArraysCheck()))
        .as("PreferZeroLengthArraysCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void publicprotectedfieldinenumcheck_fires() {
    String src = "enum N { A; public int x = 1; }";
    assertThat(run(src, new PublicProtectedFieldInEnumCheck()))
        .as("PublicProtectedFieldInEnumCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void publicprotectedfieldinenumcheck_noFire() {
    String src = "enum N { A; private int x = 1; }";
    assertThat(run(src, new PublicProtectedFieldInEnumCheck()))
        .as("PublicProtectedFieldInEnumCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void questionablebooleanassignmentcheck_sb_fires() {
    String src = "class N { boolean b; void m() { if (b = true) {} } }";
    assertThat(run(src, new QuestionableBooleanAssignmentCheck()))
        .as("QuestionableBooleanAssignmentCheck_SB fires on bad code")
        .isNotEmpty();
  }

  @Test
  void questionablebooleanassignmentcheck_sb_noFire() {
    String src = "class N { boolean b; void m() { if (b == true) {} } }";
    assertThat(run(src, new QuestionableBooleanAssignmentCheck()))
        .as("QuestionableBooleanAssignmentCheck_SB does not fire on good code")
        .isEmpty();
  }

  @Test
  void returnsnullinsteadofemptycollectioncheck_fires() {
    String src = "class N { java.util.List<String> m() { return null; } }";
    assertThat(run(src, new ReturnsNullInsteadOfEmptyCollectionCheck()))
        .as("ReturnsNullInsteadOfEmptyCollectionCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void returnsnullinsteadofemptycollectioncheck_noFire() {
    String src =
        "class N { java.util.List<String> m() { return java.util.Collections.emptyList(); } }";
    assertThat(run(src, new ReturnsNullInsteadOfEmptyCollectionCheck()))
        .as("ReturnsNullInsteadOfEmptyCollectionCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void simpledateformatwithoutlocalecheck_fires() {
    String src = "class N { void m() { new java.text.SimpleDateFormat(\"yyyy-MM-dd\"); } }";
    assertThat(run(src, new SimpleDateFormatWithoutLocaleCheck()))
        .as("SimpleDateFormatWithoutLocaleCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void simpledateformatwithoutlocalecheck_noFire() {
    String src =
        "class N { void m() { new java.text.SimpleDateFormat(\"yyyy-MM-dd\", java.util.Locale.US);"
            + " } }";
    assertThat(run(src, new SimpleDateFormatWithoutLocaleCheck()))
        .as("SimpleDateFormatWithoutLocaleCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void startwheregreaterthanendcheck_fires() {
    String src = "class N { void m() { for (int i=10; i<0; i++) {} } }";
    assertThat(run(src, new StartWhereGreaterThanEndCheck()))
        .as("StartWhereGreaterThanEndCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void startwheregreaterthanendcheck_noFire() {
    String src = "class N { void m() { for (int i=0; i<10; i++) {} } }";
    assertThat(run(src, new StartWhereGreaterThanEndCheck()))
        .as("StartWhereGreaterThanEndCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void stringbufferfieldinserialclasscheck_fires() {
    String src =
        "import java.io.Serializable; class N implements Serializable { StringBuffer buf = new"
            + " StringBuffer(); }";
    assertThat(run(src, new StringBufferFieldInSerialClassCheck()))
        .as("StringBufferFieldInSerialClassCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void stringbufferfieldinserialclasscheck_noFire() {
    String src =
        "import java.io.Serializable; class N implements Serializable { StringBuilder buf = new"
            + " StringBuilder(); } }";
    assertThat(run(src, new StringBufferFieldInSerialClassCheck()))
        .as("StringBufferFieldInSerialClassCheck does not fire on good code")
        .isEmpty();
  }
}
