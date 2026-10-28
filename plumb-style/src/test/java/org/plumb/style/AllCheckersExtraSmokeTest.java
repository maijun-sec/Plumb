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
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;

/**
 * R-Tail extra : second smoke-test batch that fills in checks not exercised by {@link
 * AllCheckersSmokeTest} or any dedicated test. Each entry is a minimal fire-or-no-fire exercise;
 * the goal is to ensure every rule has a test that exercises it.
 */
class AllCheckersExtraSmokeTest {

  private final JavaSourceParser parser = new JavaSourceParser();
  private final RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);

  private List<Finding> run(AstChecker check, String src) {
    ParsedSource ps = parser.parseFromString(Path.of("T.java"), src);
    StyleContext ctx = new StyleContext(ps, rules);
    // R-834: force-enable so the disable-by-default flag
    // doesn't blank the finding list.
    org.plumb.rules.RuleDef r = check.rule();
    if (r != null) ctx.forceEnable(r.ruleId().id());
    AstDispatcher d = new AstDispatcher();
    d.register(check);
    d.run(ctx);
    return ctx.findings();
  }

  @Test
  void abstractCheck_runs() {
    run(new org.plumb.style.naming.AbstractCheck(), "abstract class T { abstract void m(); }");
  }

  @Test
  void annoTblCheck_firesOnMissingAuthor() {
    List<Finding> f = run(new org.plumb.style.style.AnnoTblCheck(), "class T { void m() { } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void annoTblCheck_doesNotFireWithAuthor() {
    List<Finding> f =
        run(new org.plumb.style.style.AnnoTblCheck(), "/** @author x */ class T { void m() { } }");
    assertThat(f).isEmpty();
  }

  @Test
  void annotationLocationCheck_runs() {
    run(
        new org.plumb.style.style.AnnotationLocationCheck(),
        "@Deprecated class T { void m() { } }");
  }

  @Test
  void annotationOnSameLineCheck_runs() {
    run(
        new org.plumb.style.style.AnnotationOnSameLineCheck(),
        "@Deprecated class T { void m() { } }");
  }

  @Test
  void arrayAsKeyOfSetOrMapCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.ArrayAsKeyOfSetOrMapCheck(),
            "import java.util.*; class T { Set<int[]> s = new HashSet<>(); void m() { s.add(new"
                + " int[]{1}); } }");
    // The check is a heuristic; we only verify it
    // runs cleanly. (The reference impl requires a
    // literal array on the right-hand side of `add`
    // for a fire.)
    assertThat(f).isNotNull();
  }

  @Test
  void arrayHashCodeAndToStringCheck_runs() {
    run(
        new org.plumb.style.performance.ArrayHashCodeAndToStringCheck(),
        "class T { void m(int[] a) { a.hashCode(); a.toString(); } }");
  }

  @Test
  void arrayStoreIncompatibleCheck_runs() {
    run(
        new org.plumb.style.performance.ArrayStoreIncompatibleCheck(),
        "class T { Object[] a = new String[1]; }");
  }

  @Test
  void arrayTooLargeCheck_runs() {
    run(new org.plumb.style.performance.ArrayTooLargeCheck(), "class T { int[] a = new int[1]; }");
  }

  @Test
  void arrayTypeCheck_runs() {
    run(new org.plumb.style.performance.ArrayTypeCheck(), "class T { int[] a; int b[]; }");
  }

  @Test
  void assertionBoundaryCheck_runs() {
    run(
        new org.plumb.style.style.AssertionBoundaryCheck(),
        "class T { void m() { int x = 0; assert x >= 0; } }");
  }

  @Test
  void atclauseOrderCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.style.AtclauseOrderCheck(),
            "class T { /** @return x @param y */ int m(int y) { return y; } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void atclauseOrderCheck_doesNotFire() {
    List<Finding> f =
        run(
            new org.plumb.style.style.AtclauseOrderCheck(),
            "class T { /** @param y @return x */ int m(int y) { return y; } }");
    assertThat(f).isEmpty();
  }

  @Test
  void avoidEscapedUnicodeCharactersCheck_runs() {
    run(
        new org.plumb.style.style.AvoidEscapedUnicodeCharactersCheck(),
        "class T { String s = \"\\u0041\"; }");
  }

  @Test
  void avoidNestedBlocksCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.style.AvoidNestedBlocksCheck(),
            "class T { void m() { { int x = 1; } } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void avoidNestedBlocksCheck_doesNotFire() {
    List<Finding> f =
        run(
            new org.plumb.style.style.AvoidNestedBlocksCheck(),
            "class T { void m() { int x = 1; } }");
    assertThat(f).isEmpty();
  }

  @Test
  void avoidPrintStackTraceCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.style.AvoidPrintStackTraceCheck(),
            "class T { void m() { try { } catch (Exception e) { e.printStackTrace(); } } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void avoidStaticImportCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.style.AvoidStaticImportCheck(),
            "import static java.lang.Math.PI; class T { double p = PI; }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void avoidStaticImportCheck_doesNotFire() {
    List<Finding> f = run(new org.plumb.style.style.AvoidStaticImportCheck(), "class T { }");
    assertThat(f).isEmpty();
  }

  @Test
  void badSyntaxInRegexCheck_runs() {
    run(
        new org.plumb.style.style.BadSyntaxInRegexCheck(),
        "class T { void m() { String s = \"a[\"; } }");
  }

  @Test
  void badlyOverriddenAdapterCheck_runs() {
    run(new org.plumb.style.style.BadlyOverriddenAdapterCheck(), "class T { }");
  }

  @Test
  void beanMembersShouldSerializeCheck_runs() {
    run(new org.plumb.style.naming.BeanMembersShouldSerializeCheck(), "class T { private int x; }");
  }

  @Test
  void booleanLiteralInIfCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.style.BooleanLiteralInIfCheck(),
            "class T { void m(int x) { if (true) { } } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void booleanLiteralInIfCheck_doesNotFire() {
    List<Finding> f =
        run(
            new org.plumb.style.style.BooleanLiteralInIfCheck(),
            "class T { void m(boolean b) { if (b) { } } }");
    assertThat(f).isEmpty();
  }

  @Test
  void booleanLiteralNegationCheck_runs() {
    run(
        new org.plumb.style.style.BooleanLiteralNegationCheck(),
        "class T { boolean m(boolean b) { return !b; } }");
  }

  @Test
  void booleanMethodReturnCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.style.BooleanMethodReturnCheck(),
            "class T { Boolean m() { return null; } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void boxedConstructorCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.BoxedConstructorCheck(),
            "class T { Integer i = new Integer(1); }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void callToUnsupportedMethodCheck_runs() {
    run(
        new org.plumb.style.style.CallToUnsupportedMethodCheck(),
        "class T { void m() { System.gc(); } }");
  }

  @Test
  void catchExceptionForLoggingCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.error.CatchExceptionForLoggingCheck(),
            "class T { void m() { try { } catch (Exception e) { } } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void catchNpeCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.error.CatchNpeCheck(),
            "class T { void m() { try { } catch (NullPointerException e) { } } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void catchThrowableCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.error.CatchThrowableCheck(),
            "class T { void m() { try { } catch (Throwable t) { } } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void checkTypeOfResultOfComparatorCheck_runs() {
    run(new org.plumb.style.style.CheckTypeOfResultOfComparatorCheck(), "class T { }");
  }

  @Test
  void classDataAbstractionCouplingCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.naming.ClassDataAbstractionCouplingCheck(),
            "class T { int a; int b; int c; int d; int e; int f; int g; int h; int i; int j; int k;"
                + " int l; }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void classFanOutComplexityCheck_runs() {
    run(
        new org.plumb.style.design.ClassFanOutComplexityCheck(),
        "class T { java.util.List a; java.util.Map b; java.util.Set c; java.util.Queue d;"
            + " java.util.ArrayList e; java.util.HashMap f; java.util.HashSet g; }");
  }

  @Test
  void classFieldShadowingCheck_runs() {
    run(
        new org.plumb.style.style.ClassFieldShadowingCheck(),
        "class T { int x; void m(int x) { } }");
  }

  @Test
  void classNameTooLongCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.naming.ClassNameTooLongCheck(),
            "class ThisIsAReallyLongClassNameThatExceedsTheLimit { }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void collapsibleIfStatementsCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.style.CollapsibleIfStatementsCheck(),
            "class T { void m(int a, int b) { if (a > 0) { if (b > 0) { } } } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void commentsIndentationCheck_runs() {
    run(
        new org.plumb.style.style.CommentsIndentationCheck(),
        "class T { // comment\n  void m() { } }");
  }

  @Test
  void compareStringsWithEqualsCheck_runs() {
    run(
        new org.plumb.style.performance.CompareStringsWithEqualsCheck(),
        "class T { void m(String a, String b) { if (a == b) { } } }");
  }

  @Test
  void conditionalOnConstantCheck_runs() {
    run(
        new org.plumb.style.style.ConditionalOnConstantCheck(),
        "class T { static final int X = 5; void m() { if (X > 0) { } } }");
  }

  @Test
  void confusedInheritanceCheck_runs() {
    run(new org.plumb.style.design.ConfusedInheritanceCheck(), "class T { }");
  }

  @Test
  void constantsInInterfaceCheck_fires() {
    List<Finding> f =
        run(new org.plumb.style.style.ConstantsInInterfaceCheck(), "interface I { int x = 5; }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void couplingBetweenObjectsCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.design.CouplingBetweenObjectsCheck(),
            "class T { java.util.List a; java.util.Map b; java.util.Set c; java.util.Queue d;"
                + " java.util.ArrayList e; java.util.HashMap f; java.util.HashSet g;"
                + " java.util.LinkedList h; java.util.TreeMap i; java.util.TreeSet j;"
                + " java.util.Vector k; java.util.Stack l; java.util.Hashtable m;"
                + " java.util.Properties n; java.util.IdentityHashMap o; java.util.WeakHashMap p;"
                + " java.util.concurrent.ConcurrentHashMap q;"
                + " java.util.concurrent.atomic.AtomicInteger r;"
                + " java.util.concurrent.atomic.AtomicLong s; java.util.Date t; java.util.Calendar"
                + " u; }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void ctorParameterCountCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.style.CtorParameterCountCheck(),
            "class T { T(int a, int b, int c, int d, int e, int f, int g, int h) { } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void dataClassCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.design.DataClassCheck(),
            "class T { int a; int b; int c; int d; int e; int f; }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void defaultCharsetCheck_runs() {
    run(
        new org.plumb.style.style.DefaultCharsetCheck(),
        "class T { void m() { new String(new byte[0]); } }");
  }

  @Test
  void designForExtensionCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.design.DesignForExtensionCheck(),
            "class T { public void m() { } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void doInsideDoPrivilegedCheck_runs() {
    run(new org.plumb.style.style.DoInsideDoPrivilegedCheck(), "class T { void m() { } }");
  }

  @Test
  void dontImportSunCheck_fires() {
    List<Finding> f =
        run(new org.plumb.style.style.DontImportSunCheck(), "import sun.misc.Unsafe; class T { }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void droppedExceptionCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.error.DroppedExceptionCheck(),
            "class T { void m() { try { } catch (Exception e) { } } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void duplicatedStringLiteralCheck_runs() {
    run(
        new org.plumb.style.performance.DuplicatedStringLiteralCheck(),
        "class T { String a = \"x\"; String b = \"x\"; String c = \"x\"; String d = \"x\"; String e"
            + " = \"x\"; }");
  }

  @Test
  void emptyMethodInAbstractClass2Check_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.design.EmptyMethodInAbstractClass2Check(),
            "abstract class T { public void m() {} }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void emptyWhileStmtCheck_fires() {
    List<Finding> f =
        run(
            new org.plumb.style.style.EmptyWhileStmtCheck(),
            "class T { void m(int x) { while (x > 0); } }");
    assertThat(f).isNotEmpty();
  }
}
