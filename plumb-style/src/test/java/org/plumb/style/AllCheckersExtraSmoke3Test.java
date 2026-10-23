package org.plumb.style;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;

/**
 * R-Tail extra 3 : fourth smoke-test batch. Each entry is a minimal exercise for a rule not covered
 * by the other smoke-test classes.
 */
class AllCheckersExtraSmoke3Test {

  private final JavaSourceParser parser = new JavaSourceParser();
  private final RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);

  private List<Finding> run(AstChecker check, String src) {
    ParsedSource ps = parser.parseFromString(Path.of("T.java"), src);
    StyleContext ctx = new StyleContext(ps, rules);
    AstDispatcher d = new AstDispatcher();
    d.register(check);
    d.run(ctx);
    return ctx.findings();
  }

  @Test
  void jdkObsoleteCollectionCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.JdkObsoleteCollectionCheck(),
            "import java.util.*; class T { Vector<Integer> v = new Vector<>(); }");
  }

  @Test
  void jumbledIncrementerLoopCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.JumbledIncrementerLoopCheck(),
            "class T { void m() { for (int i = 0; i < 10; i--) { } } }");
  }

  @Test
  void localeDependentCaseConversionCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.LocaleDependentCaseConversionCheck(),
            "class T { void m(String s) { String t = s.toLowerCase(); } }");
  }

  @Test
  void mcReturnsVoidCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.MCReturnsVoidCheck(),
            "class T { @Override public String toString() { return null; } }");
  }

  @Test
  void malformedSyncCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.MalformedSyncCheck(),
            "class T { void m() { synchronized (m) { } } }");
  }

  @Test
  void methodReturnCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.MethodReturnCheck(),
            "class T { String m() { return m(); } }");
  }

  @Test
  void missingJavadocMethodCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.MissingJavadocMethodCheck(),
            "class T { public void m() { } }");
  }

  @Test
  void missingJavadocPackageCheck_runs() {
    List<Finding> f =
        run(new org.plumb.style.style.MissingJavadocPackageCheck(), "package-info.java missing");
  }

  @Test
  void missingJavadocTypeCheck_runs() {
    List<Finding> f =
        run(new org.plumb.style.style.MissingJavadocTypeCheck(), "public class T { }");
  }

  @Test
  void missingOverrideCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.naming.MissingOverrideCheck(),
            "class P { public String toString() { return \"x\"; } }");
  }

  @Test
  void missingStaticMethodInNonInstantiableClassCheck_runs() {
    run(
        new org.plumb.style.style.MissingStaticMethodInNonInstantiableClassCheck(),
        "class T { private T() { } }");
  }

  @Test
  void missingSwitchDefaultCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.MissingSwitchDefaultCheck(),
            "class T { int m(int x) { switch (x) { case 1: return 0; } return -1; } }");
  }

  @Test
  void modifierOrderCheck_runs() {
    List<Finding> f =
        run(new org.plumb.style.style.ModifierOrderCheck(), "class T { static public int x = 5; }");
  }

  @Test
  void namingConventionCheck_runs() {
    run(new org.plumb.style.naming.NamingConventionCheck(), "class T { int x = 1; }");
  }

  @Test
  void nestedBlockCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.NestedBlockCheck(),
            "class T { void m() { { int x = 1; } } }");
  }

  @Test
  void nestedForDepthCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.NestedForDepthCheck(),
            "class T { void m() { for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int"
                + " k = 0; k < 3; k++) for (int l = 0; l < 3; l++) { } } } }");
  }

  @Test
  void nestedIfDepthCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.NestedIfDepthCheck(),
            "class T { void m(int a) { if (a > 0) if (a > 1) if (a > 2) if (a > 3) { } } }");
  }

  @Test
  void nestedTryCatchCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.error.NestedTryCatchCheck(),
            "class T { void m() { try { try { } catch (Exception e) { } } catch (Exception e) { } }"
                + " }");
  }

  @Test
  void newlineAtEndOfFileCheck_runs() {
    run(new org.plumb.style.style.NewlineAtEndOfFileCheck(), "class T { }");
  }

  @Test
  void noArrayTrailingCommaCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.NoArrayTrailingCommaCheck(),
            "class T { int[] a = {1, 2, 3,}; }");
  }

  @Test
  void noEnumTrailingCommaCheck_runs() {
    List<Finding> f =
        run(new org.plumb.style.style.NoEnumTrailingCommaCheck(), "enum E { A, B,; }");
  }

  @Test
  void nonSerializableClassInSerializableContextCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.NonSerializableClassInSerializableContextCheck(),
            "import java.io.Serializable; class T implements Serializable { Object x; }");
  }

  @Test
  void objectsEqualityCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.ObjectsEqualityCheck(),
            "class T { void m() { java.util.Objects.equals(\"a\", \"b\"); } }");
  }

  @Test
  void outerTypeFilenameCheck_runs() {
    List<Finding> f = run(new org.plumb.style.naming.OuterTypeFilenameCheck(), "class Other { }");
  }

  @Test
  void outerTypeNumberCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.OuterTypeNumberCheck(),
            "class T { class A {} class B {} class C {} }");
  }

  @Test
  void overrideEqualsAndHashCodeCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.naming.OverrideEqualsAndHashCodeCheck(),
            "class T { @Override public boolean equals(Object o) { return false; } }");
  }

  @Test
  void packageCaseCheck_runs() {
    List<Finding> f =
        run(new org.plumb.style.naming.PackageCaseCheck(), "package org.plumb.Test; class T { }");
  }

  @Test
  void parenPadCheck_runs() {
    run(new org.plumb.style.style.ParenPadCheck(), "class T { void m() { } }");
  }

  @Test
  void pathCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.security.PathCheck(),
            "import java.nio.file.*; class T { Path p = Paths.get(\"C:\\\\Users\\\\admin\"); }");
  }

  @Test
  void publicConstantFieldNamingCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.naming.PublicConstantFieldNamingCheck(),
            "class T { public static int myValue = 5; }");
  }

  @Test
  void publicMethodNoUncheckedExceptionCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.error.PublicMethodNoUncheckedExceptionCheck(),
            "class T { public void m() throws RuntimeException { } }");
  }

  @Test
  void redundantNullCheckOfNullValueCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.bug.RedundantNullCheckOfNullValueCheck(),
            "class T { void m(Object o) { if (o == null) { o.toString(); } } }");
  }

  @Test
  void returnCountCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.bug.ReturnCountCheck(),
            "class T { int m(int x) { if (x > 0) return 1; if (x > 1) return 2; if (x > 2) return"
                + " 3; return 0; } }");
  }

  @Test
  void returnEmptyCollectionRatherThanNullCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.ReturnEmptyCollectionRatherThanNullCheck(),
            "class T { int[] m() { return null; } }");
  }

  @Test
  void separatorWrapCheck_runs() {
    run(new org.plumb.style.style.SeparatorWrapCheck(), "class T { int x = 1 + 2; }");
  }

  @Test
  void serializableSuperConstructorCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.SerializableSuperConstructorCheck(),
            "import java.io.Serializable; class T implements Serializable { T() { } }");
  }

  @Test
  void shortClassNameCheck_runs() {
    List<Finding> f = run(new org.plumb.style.naming.ShortClassNameCheck(), "class A { }");
  }

  @Test
  void shortMethodNameCheck_runs() {
    List<Finding> f =
        run(new org.plumb.style.naming.ShortMethodNameCheck(), "class T { void m() { } }");
  }

  @Test
  void shortVariableCheck_runs() {
    List<Finding> f =
        run(new org.plumb.style.naming.ShortVariableCheck(), "class T { void m() { int a = 1; } }");
  }

  @Test
  void simpleDateFormatNeedsLocaleCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.SimpleDateFormatNeedsLocaleCheck(),
            "import java.text.*; class T { SimpleDateFormat f = new SimpleDateFormat(\"yyyy\"); }");
  }

  @Test
  void simplifyBooleanExpressionsCheck_runs() {
    run(
        new org.plumb.style.performance.SimplifyBooleanExpressionsCheck(),
        "class T { boolean m(boolean b) { return b && true; } }");
  }

  @Test
  void simplifyConditionalCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.SimplifyConditionalCheck(),
            "class T { int m(boolean b) { return b ? true : false; } }");
  }

  @Test
  void singularFieldCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.SingularFieldCheck(),
            "class T { int x; void m() { x = 1; } void n() { x = 2; } }");
  }

  @Test
  void staticMethodNeedNotBeSynchronizedCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.StaticMethodNeedNotBeSynchronizedCheck(),
            "class T { static synchronized void m() { } }");
  }

  @Test
  void stringEqualityWithEqualsCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.StringEqualityWithEqualsCheck(),
            "class T { boolean m(String a, String b) { return a == b; } }");
  }

  @Test
  void stringInstantiationCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.StringInstantiationCheck(),
            "class T { String m() { return new String(\"x\"); } }");
  }

  @Test
  void stringLiteralEqualityCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.StringLiteralEqualityCheck(),
            "class T { boolean m() { return \"a\" == \"a\"; } }");
  }

  @Test
  void superCloneCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.SuperCloneCheck(),
            "class T { @Override public Object clone() { return new T(); } }");
  }

  @Test
  void superFinalizeCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.error.SuperFinalizeCheck(),
            "class T { @Override protected void finalize() { } }");
  }

  @Test
  void suspiciousIntegerDivisionCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.SuspiciousIntegerDivisionCheck(),
            "class T { int m(int a, int b) { return a / b; } }");
  }

  @Test
  void switchWithTooFewCasesCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.SwitchWithTooFewCasesCheck(),
            "class T { int m(int x) { switch (x) { case 1: return 0; case 2: return 1; } return -1;"
                + " } }");
  }

  @Test
  void switchWithoutDefaultCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.SwitchWithoutDefaultCheck(),
            "class T { int m(int x) { switch (x) { case 1: return 0; case 2: return 1; } return -1;"
                + " } }");
  }

  @Test
  void synchronizationOnStringOrBoxedCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.SynchronizationOnStringOrBoxedCheck(),
            "class T { void m() { String s = \"x\"; synchronized (s) { } } }");
  }

  @Test
  void throwableNotThrownCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.error.ThrowableNotThrownCheck(),
            "class T { void m() throws Throwable { } }");
  }

  @Test
  void throwsCountCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.error.ThrowsCountCheck(),
            "class T { void m() throws Exception, RuntimeException, Throwable, Error,"
                + " NullPointerException, IllegalArgumentException { } }");
  }

  @Test
  void todoCommentCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.TodoCommentCheck(),
            "class T { // TODO: fix this\n  void m() { } }");
  }

  @Test
  void unnecessaryCheckForNotNullCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.bug.UnnecessaryCheckForNotNullCheck(),
            "class T { void m() { String s = \"\"; if (s != null) { } } }");
  }

  @Test
  void unnecessaryFullyQualifiedEnumNameCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.naming.UnnecessaryFullyQualifiedEnumNameCheck(),
            "import java.util.concurrent.TimeUnit; class T { TimeUnit m() { return null; } }");
  }

  @Test
  void unnecessaryParenthesesMethodCallsCheck_runs() {
    run(
        new org.plumb.style.style.UnnecessaryParenthesesMethodCallsCheck(),
        "class T { int m() { return (1 + 2); } }");
  }

  @Test
  void unusedFormalParameterCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.UnusedFormalParameterCheck(),
            "class T { private void m(int x) { } }");
  }

  @Test
  void unusedLocalVariableCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.naming.UnusedLocalVariableCheck(),
            "class T { void m() { int x = 1; } }");
  }

  @Test
  void unusedNullCheckInEqualsCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.bug.UnusedNullCheckInEqualsCheck(),
            "class T { public boolean equals(Object o) { if (o == null) return false; return false;"
                + " } }");
  }

  @Test
  void unusedPrivateFieldCheck_runs() {
    List<Finding> f =
        run(new org.plumb.style.style.UnusedPrivateFieldCheck(), "class T { private int x; }");
  }

  @Test
  void upperEllCheck_runs() {
    List<Finding> f = run(new org.plumb.style.style.UpperEllCheck(), "class T { long x = 100l; }");
  }

  @Test
  void uselessObjectCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.UselessObjectCheck(),
            "class T { void m() { new Object(); } }");
  }

  @Test
  void uselessOperationOnImmutableCheck_runs() {
    run(
        new org.plumb.style.style.UselessOperationOnImmutableCheck(),
        "class T { void m() { String s = \"\"; s = s + \"x\"; } }");
  }
}
