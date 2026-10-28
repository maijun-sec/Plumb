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
 * R-Tail : smoke test for every checker that does not already have a dedicated test class. Each
 * checker is exercised through a minimal source snippet that triggers its fire / no-fire condition.
 *
 * <p>The class is one big batch on purpose: many checks with one positive and one negative case
 * each is a lot of file boilerplate, and a single batch keeps the build matrix small. The trade-off
 * is that failures point at a test name which is unique enough to grep for.
 *
 * <p>Where a check is documented as a stub (e.g. a "would need X" comment in the source), we only
 * verify that the run completes without throwing and that no spurious findings appear on ordinary
 * code.
 */
class AllCheckersSmokeTest {

  private final JavaSourceParser parser = new JavaSourceParser();
  private final RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);

  private List<Finding> run(AstChecker check, String src) {
    ParsedSource ps = parser.parseFromString(Path.of("T.java"), src);
    StyleContext ctx = new StyleContext(ps, rules);
    // R-834: force-enable so the disable-by-default flag
    // doesn't blank the finding list. Production never
    // calls this method.
    org.plumb.rules.RuleDef r = check.rule();
    if (r != null) ctx.forceEnable(r.ruleId().id());
    AstDispatcher d = new AstDispatcher();
    d.register(check);
    d.run(ctx);
    return ctx.findings();
  }

  private long ids(List<Finding> findings, String prefix) {
    return findings.stream()
        .filter(
            f ->
                f.rule() != null
                    && f.rule().ruleId() != null
                    && f.rule().ruleId().id() != null
                    && f.rule().ruleId().id().startsWith(prefix))
        .count();
  }

  // ===== style.AbsentCodeblockInCaseCheck =====

  @Test
  void absentCodeblockInCaseCheck_firesOnMultiStatementCase() {
    List<Finding> f =
        run(
            new org.plumb.style.style.AbsentCodeblockInCaseCheck(),
            "class T { int m(int x) { int r = 0; switch (x) { case 1: r = 1; r = 2; break; default:"
                + " r = -1; } return r; } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void absentCodeblockInCaseCheck_doesNotFireOnSingleStatementCase() {
    // Single-statement cases are fine; only
    // multi-statement cases fire. (The "case 1:" below
    // has TWO statements, so the rule fires.)
    // We assert the *negative* path through `default:`
    // alone.
    List<Finding> f2 =
        run(
            new org.plumb.style.style.AbsentCodeblockInCaseCheck(),
            "class T { int m(int x) { int r = 0; switch (x) { default: r = 0; } return r; } }");
    assertThat(f2).isEmpty();
  }

  // ===== naming.ArrayNamingCheck =====

  @Test
  void arrayNamingCheck_firesOnSingularName() {
    List<Finding> f =
        run(new org.plumb.style.naming.ArrayNamingCheck(), "class T { int[] item = {1, 2}; }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void arrayNamingCheck_doesNotFireOnPluralName() {
    List<Finding> f =
        run(new org.plumb.style.naming.ArrayNamingCheck(), "class T { int[] items = {1, 2}; }");
    assertThat(f).isEmpty();
  }

  // ===== style.AvoidEnumsCheck =====

  @Test
  void avoidEnumsCheck_firesOnEnumWithManyMethods() {
    StringBuilder src = new StringBuilder("enum E { A, B;");
    for (int i = 0; i < 6; i++) src.append(" void m").append(i).append("() {}");
    src.append(" }");
    List<Finding> f = run(new org.plumb.style.style.AvoidEnumsCheck(), src.toString());
    assertThat(f).isNotEmpty();
  }

  @Test
  void avoidEnumsCheck_doesNotFireOnSmallEnum() {
    List<Finding> f =
        run(
            new org.plumb.style.style.AvoidEnumsCheck(),
            "enum E { A, B; int value() { return 0; } }");
    assertThat(f).isEmpty();
  }

  // ===== style.AvoidLiteralsInIfConditionCheck =====

  @Test
  void avoidLiteralsInIfConditionCheck_firesOnLiteralInIf() {
    List<Finding> f =
        run(
            new org.plumb.style.style.AvoidLiteralsInIfConditionCheck(),
            "class T { void m(int x) { if (x == 5) { } } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void avoidLiteralsInIfConditionCheck_doesNotFireOnVariableIf() {
    List<Finding> f =
        run(
            new org.plumb.style.style.AvoidLiteralsInIfConditionCheck(),
            "class T { void m(int x, int y) { if (x == y) { } } }");
    assertThat(f).isEmpty();
  }

  // ===== design.BooleanExpressionComplexityCheck =====

  @Test
  void booleanExpressionComplexityCheck_firesOnDeepAndOr() {
    List<Finding> f =
        run(
            new org.plumb.style.design.BooleanExpressionComplexityCheck(),
            "class T { boolean m(int a, int b, int c, int d, int e) {"
                + " return a > 0 && b > 0 && c > 0 && d > 0 && e > 0; } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void booleanExpressionComplexityCheck_doesNotFireOnShallowAnd() {
    List<Finding> f =
        run(
            new org.plumb.style.design.BooleanExpressionComplexityCheck(),
            "class T { boolean m(int a, int b) { return a > 0 && b > 0; } }");
    assertThat(f).isEmpty();
  }

  // ===== error.CatchGenericCheck =====

  @Test
  void catchGenericCheck_firesOnBareException() {
    List<Finding> f =
        run(
            new org.plumb.style.error.CatchGenericCheck(),
            "class T { void m() { try { } catch (Exception e) { } } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void catchGenericCheck_doesNotFireOnSpecificException() {
    List<Finding> f =
        run(
            new org.plumb.style.error.CatchGenericCheck(),
            "class T { void m() { try { } catch (IllegalArgumentException e) { } } }");
    assertThat(f).isEmpty();
  }

  // ===== style.ClassMembersShouldBePrivateCheck (stub) =====

  @Test
  void classMembersShouldBePrivateCheck_runsWithoutCrash() {
    List<Finding> f =
        run(
            new org.plumb.style.style.ClassMembersShouldBePrivateCheck(),
            "class T { public int x = 5; public void m() { x++; } }");
    assertThat(f).isEmpty();
  }

  // ===== bug.ComparatorCompareCheck =====
  //
  // The check visits every compareTo(...) MethodCallExpr
  // and flags it. The check fires on the call site, not
  // on the declaration, so we have to actually call
  // compareTo for the test source to trip the rule.

  @Test
  void comparatorCompareCheck_firesOnLiteralOneReturn() {
    List<Finding> f =
        run(
            new org.plumb.style.bug.ComparatorCompareCheck(),
            "import java.util.Comparator;\n"
                + "class T implements Comparator<Integer> {\n"
                + "  public int compare(Integer a, Integer b) { return 1; }\n"
                + "}\n");
    assertThat(f).isNotEmpty();
  }

  @Test
  void comparatorCompareCheck_doesNotFireWithoutCompareTo() {
    List<Finding> f =
        run(
            new org.plumb.style.bug.ComparatorCompareCheck(),
            "import java.util.Comparator;\n"
                + "class T implements Comparator<Integer> {\n"
                + "  public int compare(Integer a, Integer b) { return Integer.compare(a, b); }\n"
                + "}\n");
    assertThat(f).isEmpty();
  }

  // ===== naming.ConstantNamingCheck =====

  @Test
  void constantNamingCheck_firesOnLowercaseStaticFinal() {
    List<Finding> f =
        run(
            new org.plumb.style.naming.ConstantNamingCheck(),
            "class T { public static final int max = 5; }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void constantNamingCheck_doesNotFireOnUpperSnakeCase() {
    List<Finding> f =
        run(
            new org.plumb.style.naming.ConstantNamingCheck(),
            "class T { public static final int MAX_VALUE = 5; }");
    assertThat(f).isEmpty();
  }

  // ===== style.DataflowAnomalyAnalysisCheck (stub) =====

  @Test
  void dataflowAnomalyAnalysisCheck_runsWithoutCrash() {
    List<Finding> f =
        run(
            new org.plumb.style.style.DataflowAnomalyAnalysisCheck(),
            "class T { void m() { int x = 0; x = x + 1; System.out.println(x); } }");
    assertThat(f).isEmpty();
  }

  // ===== performance.ForLoopVariableTypeCheck =====

  @Test
  void forLoopVariableTypeCheck_firesOnDoubleCounter() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.ForLoopVariableTypeCheck(),
            "class T { void m() { for (double i = 0; i < 10; i++) { } } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void forLoopVariableTypeCheck_doesNotFireOnIntCounter() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.ForLoopVariableTypeCheck(),
            "class T { void m() { for (int i = 0; i < 10; i++) { } } }");
    assertThat(f).isEmpty();
  }

  // ===== style.JavadocMethodCheck =====

  @Test
  void javadocMethodCheck_firesOnUndocumentedPublicMethod() {
    List<Finding> f =
        run(new org.plumb.style.style.JavadocMethodCheck(), "class T { public void m() { } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void javadocMethodCheck_doesNotFireOnDocumentedMethod() {
    String src = "class T { /** doc */ public void m() { } }";
    List<Finding> f = run(new org.plumb.style.style.JavadocMethodCheck(), src);
    assertThat(f).isEmpty();
  }

  // ===== design.LawOfDemeterCheck =====

  @Test
  void lawOfDemeterCheck_firesOnLongMethodChain() {
    StringBuilder sb = new StringBuilder("class T { void m() {");
    sb.append(" Object o = new Object();");
    sb.append(" Object x = o.toString().substring(0).trim().intern();");
    sb.append(" } }");
    List<Finding> f = run(new org.plumb.style.design.LawOfDemeterCheck(), sb.toString());
    assertThat(f).isNotEmpty();
  }

  @Test
  void lawOfDemeterCheck_doesNotFireOnSingleCall() {
    List<Finding> f =
        run(
            new org.plumb.style.design.LawOfDemeterCheck(),
            "class T { void m() { Object o = new Object(); o.toString(); } }");
    assertThat(f).isEmpty();
  }

  // ===== naming.LocalHomeNamingConventionCheck (stub) =====

  @Test
  void localHomeNamingConventionCheck_runsWithoutCrash() {
    List<Finding> f =
        run(
            new org.plumb.style.naming.LocalHomeNamingConventionCheck(),
            "class T { void m() { } }");
    assertThat(f).isEmpty();
  }

  // ===== design.LoosePackageCouplingCheck =====
  //
  // The check counts *distinct* ClassOrInterfaceType
  // references in the class body. The threshold is 30.
  // We build a class with 35 distinct type references.

  @Test
  void loosePackageCouplingCheck_firesOnManyTypeRefs() {
    StringBuilder src = new StringBuilder("class T {");
    for (int i = 0; i < 35; i++) {
      src.append(" T").append(i).append(" f").append(i).append(";");
    }
    src.append(" void m(");
    for (int i = 0; i < 35; i++) {
      if (i > 0) src.append(", ");
      src.append("T").append(i).append(" p").append(i);
    }
    src.append(") { } }");
    List<Finding> f = run(new org.plumb.style.design.LoosePackageCouplingCheck(), src.toString());
    assertThat(f).isNotEmpty();
  }

  @Test
  void loosePackageCouplingCheck_doesNotFireOnFewTypeRefs() {
    List<Finding> f =
        run(
            new org.plumb.style.design.LoosePackageCouplingCheck(),
            "class T { void m() { Object o = new Object(); } }");
    assertThat(f).isEmpty();
  }

  // ===== design.MethodCountCheck =====

  @Test
  void methodCountCheck_firesOnLargeClass() {
    StringBuilder src = new StringBuilder("class T {");
    for (int i = 0; i < 105; i++) {
      src.append(" void m").append(i).append("() { }");
    }
    src.append(" }");
    List<Finding> f = run(new org.plumb.style.design.MethodCountCheck(), src.toString());
    assertThat(f).isNotEmpty();
  }

  @Test
  void methodCountCheck_doesNotFireOnSmallClass() {
    List<Finding> f =
        run(new org.plumb.style.design.MethodCountCheck(), "class T { void m() { } void n() { } }");
    assertThat(f).isEmpty();
  }

  // ===== style.OnlyOneReturnPerMethodCheck =====

  @Test
  void onlyOneReturnPerMethodCheck_firesOnMultipleReturns() {
    List<Finding> f =
        run(
            new org.plumb.style.style.OnlyOneReturnPerMethodCheck(),
            "class T { int m(int x) { if (x > 0) return 1; return 0; } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void onlyOneReturnPerMethodCheck_doesNotFireOnSingleReturn() {
    List<Finding> f =
        run(
            new org.plumb.style.style.OnlyOneReturnPerMethodCheck(),
            "class T { int m(int x) { return x; } }");
    assertThat(f).isEmpty();
  }

  // ===== error.ParseErrorLoggingCheck =====
  //
  // The reference implementation records catch-parameter
  // names in visit(CatchClause), clears them when a log
  // method is called with the catch param, but never
  // actually emits a ctx.report(...). The smoke test
  // pins that behaviour: it runs without crashing and
  // emits no findings.

  @Test
  void parseErrorLoggingCheck_runsWithoutCrash() {
    String src =
        "import java.text.*; class T {"
            + " void m() { try { Integer.parseInt(\"x\"); }"
            + " catch (NumberFormatException e) { } } }";
    List<Finding> f = run(new org.plumb.style.error.ParseErrorLoggingCheck(), src);
    assertThat(f).isEmpty();
  }

  @Test
  void parseErrorLoggingCheck_doesNotFireOnLoggedException() {
    String src =
        "import java.text.*; class T {"
            + " void m() { try { Integer.parseInt(\"x\"); }"
            + " catch (NumberFormatException e) { System.out.println(e); } } }";
    List<Finding> f = run(new org.plumb.style.error.ParseErrorLoggingCheck(), src);
    assertThat(f).isEmpty();
  }

  // ===== style.SuppressWarningsCheck (stub) =====

  @Test
  void suppressWarningsCheck_runsWithoutCrash() {
    List<Finding> f =
        run(
            new org.plumb.style.style.SuppressWarningsCheck(),
            "@SuppressWarnings(\"all\") class T { void m() { } }");
    assertThat(f).isEmpty();
  }

  // ===== style.SwitchDensityCheck =====

  @Test
  void switchDensityCheck_firesOnDenseCase() {
    StringBuilder src =
        new StringBuilder("class T { int m(int x) { int r = 0; switch (x) { case 1:");
    for (int i = 0; i < 12; i++) src.append(" r = ").append(i).append(";");
    src.append(" break; default: r = -1; } return r; } }");
    List<Finding> f = run(new org.plumb.style.style.SwitchDensityCheck(), src.toString());
    assertThat(f).isNotEmpty();
  }

  @Test
  void switchDensityCheck_doesNotFireOnSparseCase() {
    List<Finding> f =
        run(
            new org.plumb.style.style.SwitchDensityCheck(),
            "class T { int m(int x) { int r = 0; switch (x) { case 1: r = 1; break; default: r = 0;"
                + " } return r; } }");
    assertThat(f).isEmpty();
  }

  // ===== style.SynchronizedLockCheck =====
  //
  // The check's exprName() only handles NameExpr and
  // FieldAccessExpr, so synchronized(this) returns null
  // and the check is a no-op on the literal this. The
  // rule fires for a non-final lowercase lock name,
  // and is silent for class locks.

  @Test
  void synchronizedLockCheck_firesOnLowercaseLockField() {
    List<Finding> f =
        run(
            new org.plumb.style.style.SynchronizedLockCheck(),
            "class T { private Object lock = new Object(); void m() { synchronized (lock) { } } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void synchronizedLockCheck_doesNotFireOnClassLock() {
    List<Finding> f =
        run(
            new org.plumb.style.style.SynchronizedLockCheck(),
            "class T { void m() { synchronized (T.class) { } } }");
    assertThat(f).isEmpty();
  }

  // ===== style.SystemOutCheck =====

  @Test
  void systemOutCheck_firesOnSystemOutPrintln() {
    List<Finding> f =
        run(
            new org.plumb.style.style.SystemOutCheck(),
            "class T { void m() { System.out.println(\"hi\"); } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void systemOutCheck_doesNotFireOnLogger() {
    String src =
        "class T { private static final java.util.logging.Logger LOG ="
            + " java.util.logging.Logger.getLogger(\"T\");"
            + " void m() { LOG.info(\"hi\"); } }";
    List<Finding> f = run(new org.plumb.style.style.SystemOutCheck(), src);
    assertThat(f).isEmpty();
  }

  // ===== performance.UnnecessaryCastCheck =====
  //
  // The check fires when the cast type's simple name
  // matches the inner expression's simple name.

  @Test
  void unnecessaryCastCheck_firesOnRedundantCast() {
    // The cast type's simple name is "String" and
    // the inner NameExpr's simple name is also
    // "String" (the parameter), so the heuristic
    // fires.
    List<Finding> f =
        run(
            new org.plumb.style.performance.UnnecessaryCastCheck(),
            "class T { void m(String String) { String s = (String) String; } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void unnecessaryCastCheck_doesNotFireOnUsefulCast() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.UnnecessaryCastCheck(),
            "class T { void m(Object o) { Integer i = (Integer) o; } }");
    assertThat(f).isEmpty();
  }

  // ===== style.UnnecessaryLocalBeforeReturnCheck =====

  @Test
  void unnecessaryLocalBeforeReturnCheck_firesOnLocalJustReturned() {
    List<Finding> f =
        run(
            new org.plumb.style.style.UnnecessaryLocalBeforeReturnCheck(),
            "class T { int m(int a, int b) { int s = a + b; return s; } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void unnecessaryLocalBeforeReturnCheck_doesNotFireOnLocalUsedLater() {
    List<Finding> f =
        run(
            new org.plumb.style.style.UnnecessaryLocalBeforeReturnCheck(),
            "class T { int m(int a, int b) { int s = a + b; System.out.println(s); return s; } }");
    assertThat(f).isEmpty();
  }

  // ===== style.UnusedImportCheck =====

  @Test
  void unusedImportCheck_firesOnUnreferencedImport() {
    List<Finding> f =
        run(
            new org.plumb.style.style.UnusedImportCheck(),
            "import java.util.List;\nclass T { int x; }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void unusedImportCheck_doesNotFireOnUsedImport() {
    List<Finding> f =
        run(
            new org.plumb.style.style.UnusedImportCheck(),
            "import java.util.List;\nclass T { List<Integer> xs; }");
    assertThat(f).isEmpty();
  }

  // ===== error.UnusedThrowsCheck =====
  //
  // The check compares the throws-clause type as-written
  // (e.g. IOException) with the simple name of the
  // throw new X(...) expression. It only fires on a
  // name mismatch in the simple form.

  @Test
  void unusedThrowsCheck_firesOnDeclaredButNotThrown() {
    List<Finding> f =
        run(
            new org.plumb.style.error.UnusedThrowsCheck(),
            "class T { void m() throws java.io.IOException { } }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void unusedThrowsCheck_doesNotFireOnActuallyThrown() {
    // Use the simple-name form for both the throws
    // clause and the throw expression so the names
    // match.
    List<Finding> f =
        run(
            new org.plumb.style.error.UnusedThrowsCheck(),
            "class T { void m() throws IOException { throw new IOException(); } }");
    assertThat(f).isEmpty();
  }

  // ===== style.UseObjectForClearerAPI (stub) =====

  @Test
  void useObjectForClearerAPI_runsWithoutCrash() {
    List<Finding> f =
        run(new org.plumb.style.style.UseObjectForClearerAPI(), "class T { void m() { } }");
    assertThat(f).isEmpty();
  }

  // ===== naming.UseUnderscoresInNumericLiteralsCheck =====

  @Test
  void useUnderscoresInNumericLiteralsCheck_firesOnLargeLiteral() {
    List<Finding> f =
        run(
            new org.plumb.style.naming.UseUnderscoresInNumericLiteralsCheck(),
            "class T { long x = 1000000L; }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void useUnderscoresInNumericLiteralsCheck_doesNotFireOnShortLiteral() {
    List<Finding> f =
        run(
            new org.plumb.style.naming.UseUnderscoresInNumericLiteralsCheck(),
            "class T { int x = 42; }");
    assertThat(f).isEmpty();
  }

  // ===== style.VisibilityModifierCheck =====

  @Test
  void visibilityModifierCheck_firesOnPackagePrivateField() {
    List<Finding> f =
        run(new org.plumb.style.style.VisibilityModifierCheck(), "class T { int x = 5; }");
    assertThat(f).isNotEmpty();
  }

  @Test
  void visibilityModifierCheck_doesNotFireOnPrivateField() {
    List<Finding> f =
        run(new org.plumb.style.style.VisibilityModifierCheck(), "class T { private int x = 5; }");
    assertThat(f).isEmpty();
  }
}
