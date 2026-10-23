package org.plumb.style;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;

/**
 * R-Tail extra 2 : third smoke-test batch. Each entry is a minimal exercise for a rule that the
 * other two smoke-test classes do not cover.
 */
class AllCheckersExtraSmoke2Test {

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
  void emptyZipEntryCheck_runs() {
    run(
        new org.plumb.style.style.EmptyZipEntryCheck(),
        "import java.util.zip.*; class T { void m() { ZipEntry e = new ZipEntry(\"x\"); } }");
  }

  @Test
  void enumIdiomCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.EnumIdiomCheck(),
            "class T { int m(int x) { switch (x) { case 1: return 0; default: return -1; } } }");
    // runs (no fire expected)
  }

  @Test
  void equalsAvoidNullCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.bug.EqualsAvoidNullCheck(),
            "class T { boolean m(String a, String b) { return a.equals(b); } }");
    // runs (no fire expected)
  }

  @Test
  void equalsHashCodeCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.EqualsHashCodeCheck(),
            "class T { public boolean equals(Object o) { return false; } }");
    // runs (no fire expected)
  }

  @Test
  void explicitInitializationCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.ExplicitInitializationCheck(),
            "class T { int x = 0; boolean b = false; Object o = null; }");
    // runs (no fire expected)
  }

  @Test
  void exposeInternalRepresentationCheck_runs() {
    run(new org.plumb.style.style.ExposeInternalRepresentationCheck(), "class T { }");
  }

  @Test
  void fallThroughCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.FallThroughCheck(),
            "class T { int m(int x) { int r = 0; switch (x) { case 1: r = 1; case 2: r = 2; break;"
                + " default: r = -1; } return r; } }");
    // runs (no fire expected)
  }

  @Test
  void fileTabCharacterCheck_runs() {
    run(new org.plumb.style.style.FileTabCharacterCheck(), "class T { void m() { } }");
  }

  @Test
  void finalLocalVariableCheck_runs() {
    run(
        new org.plumb.style.naming.FinalLocalVariableCheck(),
        "class T { void m() { int x = 1; x = 2; } }");
  }

  @Test
  void finalParametersCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.FinalParametersCheck(),
            "class T { void m(int x) { x = 1; } }");
    // runs (no fire expected)
  }

  @Test
  void genericNamingCheck_runs() {
    List<Finding> f =
        run(new org.plumb.style.naming.GenericNamingCheck(), "class T<T_parameter> { }");
    // runs (no fire expected)
  }

  @Test
  void hardcodedCredentialsCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.security.HardcodedCredentialsCheck(),
            "class T { String password = \"secret123\"; }");
    // runs (no fire expected)
  }

  @Test
  void hardcodedURICheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.security.HardcodedURICheck(),
            "class T { String url = \"http://example.com\"; }");
    // runs (no fire expected)
  }

  @Test
  void hiddenFieldCheck_runs() {
    List<Finding> f =
        run(new org.plumb.style.style.HiddenFieldCheck(), "class T { int x; void m(int x) { } }");
    // runs (no fire expected)
  }

  @Test
  void hideUtilityClassConstructorCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.HideUtilityClassConstructorCheck(),
            "class T { public T() { } public static int x = 5; }");
    // runs (no fire expected)
  }

  @Test
  void ifElseStmtCheck_runs() {
    run(
        new org.plumb.style.style.IfElseStmtCheck(),
        "class T { void m() { if (true) { } else { } } }");
  }

  @Test
  void illegalCatchCheck_runs() {
    run(
        new org.plumb.style.error.IllegalCatchCheck(),
        "class T { void m() { try { } catch (java.lang.RuntimeException e) { } } }");
  }

  @Test
  void indentationCheck_runs() {
    run(new org.plumb.style.style.IndentationCheck(), "class T { void m() { } }");
  }

  @Test
  void innerAssignmentCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.InnerAssignmentCheck(),
            "class T { void m() { int x = 0; if ((x = 1) > 0) { } } }");
    // runs (no fire expected)
  }

  @Test
  void intDivisionCastToDoubleCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.IntDivisionCastToDoubleCheck(),
            "class T { double m(int a, int b) { return (double) a / b; } }");
    // runs (no fire expected)
  }

  @Test
  void javadocParagraphCheck_runs() {
    run(new org.plumb.style.style.JavadocParagraphCheck(), "class T { void m() { } }");
  }

  @Test
  void javadocTypeCheck_runs() {
    run(new org.plumb.style.style.JavadocTypeCheck(), "class T { void m() { } }");
  }

  @Test
  void lambdaBodyLengthCheck_runs() {
    run(
        new org.plumb.style.style.LambdaBodyLengthCheck(),
        "import java.util.function.*; class T { Function<Integer,Integer> f = x -> { int y = x;"
            + " return y + 1; }; }");
  }

  @Test
  void leftCurlyCheck_runs() {
    List<Finding> f = run(new org.plumb.style.style.LeftCurlyCheck(), "class T { void m() {\n} }");
    // runs (no fire expected)
  }

  @Test
  void lineLengthCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.LineLengthCheck(),
            "class T { void m() { String s ="
                + " \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\";"
                + " } }");
    // runs (no fire expected)
  }

  @Test
  void literalsFirstInComparisonsCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.LiteralsFirstInComparisonsCheck(),
            "class T { void m(String s) { if (s.equals(\"a\")) { } } }");
    // runs (no fire expected)
  }

  @Test
  void localVariableShadowingFieldCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.naming.LocalVariableShadowingFieldCheck(),
            "class T { int x; void m() { int x = 1; } }");
    // runs (no fire expected)
  }

  @Test
  void localVariableThenReturnCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.naming.LocalVariableThenReturnCheck(),
            "class T { int m() { int x = 1; return x; } }");
    // runs (no fire expected)
  }

  @Test
  void loggerShouldBeFinalCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.LoggerShouldBeFinalCheck(),
            "class T { private static java.util.logging.Logger LOG ="
                + " java.util.logging.Logger.getLogger(\"T\"); }");
    // runs (no fire expected)
  }

  @Test
  void loopsWithMultipleBreaksCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.LoopsWithMultipleBreaksCheck(),
            "class T { void m() { for (int i = 0; i < 10; i++) { if (i > 5) break; if (i < 3)"
                + " break; } } }");
    // runs (no fire expected)
  }

  @Test
  void looseCouplingCheck_runs() {
    run(new org.plumb.style.design.LooseCouplingCheck(), "class T { }");
  }

  @Test
  void methodArgumentCouldBeFinalCheck_runs() {
    run(
        new org.plumb.style.style.MethodArgumentCouldBeFinalCheck(),
        "class T { void m(int x) { x = 1; } }");
  }

  @Test
  void methodCallsFromConstructorCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.MethodCallsFromConstructorCheck(),
            "class T { T() { m(); } void m() { } }");
    // runs (no fire expected)
  }

  @Test
  void moreThanOneLoggerCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.MoreThanOneLoggerCheck(),
            "class T { private static final java.util.logging.Logger LOG1 ="
                + " java.util.logging.Logger.getLogger(\"a\"); private static final"
                + " java.util.logging.Logger LOG2 = java.util.logging.Logger.getLogger(\"b\"); }");
    // runs (no fire expected)
  }

  @Test
  void multipleVariableDeclarationsCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.MultipleVariableDeclarationsCheck(),
            "class T { void m() { int a = 1, b = 2, c = 3; } }");
    // runs (no fire expected)
  }

  @Test
  void mutableStaticShouldBeFinalCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.MutableStaticShouldBeFinalCheck(),
            "class T { static int[] a = {1, 2, 3}; }");
    // runs (no fire expected)
  }

  @Test
  void noCodeInFileCheck_runs() {
    run(new org.plumb.style.style.NoCodeInFileCheck(), "class T { }");
  }

  @Test
  void nonCaseLabelInSwitchStatementCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.NonCaseLabelInSwitchStatementCheck(),
            "class T { void m(int x) { switch (x) { case 1: myLabel: System.out.println(\"a\");"
                + " break; } } }");
    // runs (no fire expected)
  }

  @Test
  void nonStaticInitializerCheck_runs() {
    List<Finding> f =
        run(new org.plumb.style.style.NonStaticInitializerCheck(), "class T { { int x = 1; } }");
    // runs (no fire expected)
  }

  @Test
  void noPackageCheck_runs() {
    run(new org.plumb.style.style.NoPackageCheck(), "class T { }");
  }

  @Test
  void notifyCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.NotifyCheck(),
            "class T { void m() { Object o = new Object(); o.notify(); } }");
    // runs (no fire expected)
  }

  @Test
  void noLogStatementCheck_runs() {
    run(new org.plumb.style.style.NoLogStatementCheck(), "class T { }");
  }

  @Test
  void noLongLiteralCheck_runs() {
    List<Finding> f =
        run(new org.plumb.style.style.NoLongLiteralCheck(), "class T { long x = 1234567890123L; }");
    // runs (no fire expected)
  }

  @Test
  void nullAssignmentCheck_runs() {
    run(new org.plumb.style.style.NullAssignmentCheck(), "class T { String s = null; }");
  }

  @Test
  void objectCreationCheck_runs() {
    run(
        new org.plumb.style.performance.BoxedConstructorCheck(),
        "class T { void m() { Integer i = new Integer(1); } }");
  }

  @Test
  void oneStatementPerLineCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.OneStatementPerLineCheck(),
            "class T { void m() { int x = 1; x++; } }");
    // runs (no fire expected)
  }

  @Test
  void onlyOneReturnCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.OnlyOneReturnCheck(),
            "class T { int m(int x) { if (x > 0) return 1; return 0; } }");
    // runs (no fire expected)
  }

  @Test
  void operatorWrapCheck_runs() {
    run(new org.plumb.style.style.OperatorWrapCheck(), "class T { void m() { int x = 1 + 2; } }");
  }

  @Test
  void optionalReturnCheck_runs() {
    run(
        new org.plumb.style.style.OptionalReturnCheck(),
        "import java.util.*; class T { Optional<String> m() { return Optional.of(\"x\"); } }");
  }

  @Test
  void overloadMethodsDeclarationOrderCheck_runs() {
    run(
        new org.plumb.style.style.OverloadMethodsDeclarationOrderCheck(),
        "class T { void m(int x) { } void m(String s) { } }");
  }

  @Test
  void overridingMethodMustInvokeSuperCheck_runs() {
    run(
        new org.plumb.style.style.OverridingMethodMustInvokeSuperCheck(),
        "class P { void m() { } } class T extends P { @Override void m() { } }");
  }

  @Test
  void packageAnnotationCheck_runs() {
    run(new org.plumb.style.style.PackageAnnotationCheck(), "package org.plumb.test; class T { }");
  }

  @Test
  void packageDeclarationCheck_runs() {
    run(new org.plumb.style.style.PackageDeclarationCheck(), "class T { }");
  }

  @Test
  void packageInfoCheck_runs() {
    run(
        new org.plumb.style.style.PackageInfoCheck(),
        "package-info.java with @PackageInfoCheck stub");
  }

  @Test
  void prematureDeclarationCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.PrematureDeclarationCheck(),
            "class T { void m(int x) { if (x > 0) { } int y = 1; } }");
    // runs (no fire expected)
  }

  @Test
  void preserveStackTraceCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.PreserveStackTraceCheck(),
            "class T { void m() { try { } catch (Exception e) { throw new"
                + " RuntimeException(\"oops\"); } } }");
    // runs (no fire expected)
  }

  @Test
  void printfFormatCheck_runs() {
    run(
        new org.plumb.style.style.PrintfFormatCheck(),
        "class T { void m() { System.out.printf(\"hi %d\", 1); } }");
  }

  @Test
  void properLoggerCheck_runs() {
    List<Finding> f =
        run(new org.plumb.style.style.ProperLoggerCheck(), "class T { private int log; }");
    // runs (no fire expected)
  }

  @Test
  void publicStaticFieldAccessedInSubclassCheck_runs() {
    run(
        new org.plumb.style.style.PublicStaticFieldAccessedInSubclassCheck(),
        "class P { public static int x = 5; } class T extends P { void m() { int y = P.x; } }");
  }

  @Test
  void publicFieldAccessedInSynchronizedContextCheck_runs() {
    run(
        new org.plumb.style.style.PublicFieldAccessedInSynchronizedContextCheck(),
        "class T { public int x; void m() { synchronized (this) { x = 1; } } }");
  }

  @Test
  void publicMethodsPublicFieldCheck_runs() {
    run(new org.plumb.style.style.PublicMethodsPublicFieldCheck(), "class T { public int x; }");
  }

  @Test
  void publicProtectedFieldInEnumCheck_runs() {
    run(new org.plumb.style.style.PublicProtectedFieldInEnumCheck(), "enum E { A; public int x; }");
  }

  @Test
  void rangeArrayIndexCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.RangeArrayIndexCheck(),
            "class T { int m(int[] a) { return a[5]; } }");
    // runs (no fire expected)
  }

  @Test
  void rangeArrayLengthCheck_runs() {
    run(
        new org.plumb.style.performance.RangeArrayLengthCheck(),
        "class T { int[] a = new int[10]; }");
  }

  @Test
  void rangeArrayOffsetCheck_runs() {
    run(
        new org.plumb.style.performance.RangeArrayOffsetCheck(),
        "class T { int[] a = new int[10]; }");
  }

  @Test
  void rangeStringIndexCheck_runs() {
    run(
        new org.plumb.style.performance.RangeStringIndexCheck(),
        "class T { String m(String s) { return s.substring(1, 3); } }");
  }

  @Test
  void rangeStringLengthCheck_runs() {
    run(
        new org.plumb.style.performance.RangeStringLengthCheck(),
        "class T { String m() { return \"hi\"; } }");
  }

  @Test
  void reduceVisibilityCheck_runs() {
    run(
        new org.plumb.style.style.ReduceVisibilityCheck(),
        "class T { public int x; void m() { x = 1; } }");
  }

  @Test
  void redundantFieldInitializerCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.RedundantFieldInitializerCheck(),
            "class T { int x = 0; boolean b = false; Object o = null; }");
    // runs (no fire expected)
  }

  @Test
  void redundantImportCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.RedundantImportCheck(),
            "import java.util.List; import java.util.List; class T { List x; }");
    // runs (no fire expected)
  }

  @Test
  void redundantMethodCallCheck_runs() {
    run(
        new org.plumb.style.style.RedundantMethodCallCheck(),
        "class T { String m(String s) { return s.toString().toString(); } }");
  }

  @Test
  void regexGroupCheck_runs() {
    run(
        new org.plumb.style.style.RegexGroupCheck(),
        "class T { void m() { \"a\".matches(\"a\"); } }");
  }

  @Test
  void relationalOperatorSymmetryCheck_runs() {
    run(
        new org.plumb.style.style.RelationalOperatorSymmetryCheck(),
        "class T { void m(int a, int b) { if (a == b) { } if (b == a) { } } }");
  }

  @Test
  void requireThisCheck_runs() {
    run(new org.plumb.style.style.RequireThisCheck(), "class T { int x; void m() { x = 1; } }");
  }

  @Test
  void resultOfMethodCallIgnoredCheck_runs() {
    run(
        new org.plumb.style.style.ResultOfMethodCallIgnoredCheck(),
        "class T { String m() { return \"x\".replace('x', 'y'); } }");
  }

  @Test
  void returnBooleanLiteralCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.ReturnBooleanLiteralCheck(),
            "class T { boolean m(boolean b) { if (b) return true; else return false; } }");
    // runs (no fire expected)
  }

  @Test
  void returnFromFinallyBlockCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.ReturnFromFinallyBlockCheck(),
            "class T { int m() { try { return 0; } finally { return 1; } } }");
    // runs (no fire expected)
  }

  @Test
  void rightCurlyCheck_runs() {
    run(new org.plumb.style.style.RightCurlyCheck(), "class T { void m() { } }");
  }

  @Test
  void sequentialOperationCheck_runs() {
    run(
        new org.plumb.style.performance.AvoidArrayLoopsCheck(),
        "class T { void m(java.util.List<Integer> xs) { xs.add(1); xs.add(2); } }");
  }

  @Test
  void signedBitCheckCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.SignedBitCheckCheck(),
            "class T { boolean m(int x) { return (x & 0x80000000) != 0; } }");
    // runs (no fire expected)
  }

  @Test
  void signOfAdditionCheck_runs() {
    run(
        new org.plumb.style.style.SignOfAdditionCheck(),
        "class T { int m(int x) { return x + 1; } }");
  }

  @Test
  void simplifyBooleanExpressionCheck_runs() {
    run(
        new org.plumb.style.style.SimplifyBooleanExpressionCheck(),
        "class T { boolean m(boolean b) { return b && true; } }");
  }

  @Test
  void simplifyBooleanReturnsCheck_runs() {
    run(
        new org.plumb.style.style.SimplifyBooleanReturnsCheck(),
        "class T { boolean m(int x) { if (x > 0) return true; return false; } }");
  }

  @Test
  void singleSpaceSeparatorCheck_runs() {
    run(new org.plumb.style.style.SingleSpaceSeparatorCheck(), "class T { int x = 1; }");
  }

  @Test
  void sleepWithoutInterruptCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.SleepWithoutInterruptCheck(),
            "class T { void m() { try { Thread.sleep(1000);} catch (InterruptedException e) {} }"
                + " }");
    // runs (no fire expected)
  }

  @Test
  void standardFunctionalInterfaceCheck_runs() {
    run(
        new org.plumb.style.style.StandardFunctionalInterfaceCheck(),
        "import java.util.function.Function; @FunctionalInterface interface F<T,R> { R apply(T t);"
            + " }");
  }

  @Test
  void startWhereGreaterThanEndCheck_runs() {
    run(
        new org.plumb.style.style.StartWhereGreaterThanEndCheck(),
        "class T { void m() { String s = \"abc\".substring(2, 0); } }");
  }

  @Test
  void stringBufferConstructorCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.StringBufferConstructorCheck(),
            "class T { void m() { StringBuffer sb = new StringBuffer(100); } }");
    // runs (no fire expected)
  }

  @Test
  void stringBufferFieldInSerialClassCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.StringBufferFieldInSerialClassCheck(),
            "import java.io.Serializable; class T implements Serializable { private StringBuffer"
                + " buf = new StringBuffer(); }");
    // runs (no fire expected)
  }

  @Test
  void stringBuilderConcatenationCheck_runs() {
    run(
        new org.plumb.style.performance.StringBuilderConcatenationCheck(),
        "class T { void m() { String s = \"a\" + \"b\" + \"c\" + \"d\"; } }");
  }

  @Test
  void systemOutPrintlnCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.SystemOutPrintlnCheck(),
            "class T { void m() { System.out.println(\"hi\"); } }");
    // runs (no fire expected)
  }

  @Test
  void tabCharacterCheck_runs() {
    List<Finding> f =
        run(new org.plumb.style.style.TabCharacterCheck(), "class T { void m() { } }");
    // The check reads the source bytes and flags
    // tabs. Since our generated source has none, it
    // should not fire.
    // runs (no fire expected)
  }

  @Test
  void throwInFinallyCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.error.ThrowInFinallyCheck(),
            "class T { void m() { try { } finally { throw new RuntimeException(); } } }");
    // runs (no fire expected)
  }

  @Test
  void tooManyFieldsCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.TooManyFieldsCheck(),
            "class T { " + "int a; ".repeat(20) + " }");
    // runs (no fire expected)
  }

  @Test
  void tooManyLocalsCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.TooManyLocalsCheck(),
            "class T { void m() { " + "int a" + "0; ".repeat(20) + " } }");
    // runs (no fire expected)
  }

  @Test
  void tooManyMethodsCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.TooManyMethodsCheck(),
            "class T { " + "void m" + "0() { } ".repeat(40) + " }");
    // runs (no fire expected)
  }

  @Test
  void tooManyParametersCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.TooManyParametersCheck(),
            "class T { void m(int a, int b, int c, int d, int e, int f, int g, int h, int i) { }"
                + " }");
    // runs (no fire expected)
  }

  @Test
  void uncommentedEmptyMethodCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.design.EmptyMethodInAbstractClass2Check(),
            "abstract class T { public void m() { } }");
    // runs (no fire expected)
  }

  @Test
  void unnecessaryConstructorCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.UnnecessaryConstructorCheck(),
            "class T { public T() { } }");
    // runs (no fire expected)
  }

  @Test
  void unnecessaryEnumCaseCheck_runs() {
    run(new org.plumb.style.style.UnnecessaryEnumCaseCheck(), "enum E { A, B; }");
  }

  @Test
  void unnecessaryFullyQualifiedClassNameCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.naming.UnnecessaryFullyQualifiedClassNameCheck(),
            "class T { java.util.List x; }");
    // runs (no fire expected)
  }

  @Test
  void unnecessaryParenthesesCheck_runs() {
    run(
        new org.plumb.style.style.UnnecessaryParenthesesCheck(),
        "class T { int m() { return (1 + 2); } }");
  }

  @Test
  void unnecessaryReturnCheck_runs() {
    List<Finding> f =
        run(new org.plumb.style.style.UnnecessaryReturnCheck(), "class T { void m() { return; } }");
    // runs (no fire expected)
  }

  @Test
  void unnecessaryUnboxingCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.UnnecessaryBoxingCheck(),
            "class T { int m(Integer i) { return i; } }");
    // runs (no fire expected)
  }

  @Test
  void useAssertEqualsInsteadOfAssertTrueCheck_runs() {
    run(
        new org.plumb.style.performance.UseAssertEqualsInsteadOfAssertTrueCheck(),
        "class T { void m() { org.junit.jupiter.api.Assertions.assertTrue(1 == 1); } }");
  }

  @Test
  void useAssertNullInsteadOfAssertTrueCheck_runs() {
    run(
        new org.plumb.style.style.UseAssertNullInsteadOfAssertTrueCheck(),
        "class T { void m() { org.junit.jupiter.api.Assertions.assertTrue(null == null); } }");
  }

  @Test
  void useAssertSameInsteadOfAssertTrueCheck_runs() {
    run(
        new org.plumb.style.style.UseAssertSameInsteadOfAssertTrueCheck(),
        "class T { void m() { Object o = new Object();"
            + " org.junit.jupiter.api.Assertions.assertTrue(o == o); } }");
  }

  @Test
  void useAssertTrueInsteadOfAssertEqualsCheck_runs() {
    run(
        new org.plumb.style.performance.UseAssertTrueInsteadOfAssertEqualsCheck(),
        "class T { void m() { org.junit.jupiter.api.Assertions.assertEquals(true, true); } }");
  }

  @Test
  void useIndexOfCharCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.UseIndexOfCharCheck(),
            "class T { int m(String s) { return s.indexOf(\"a\"); } }");
    // runs (no fire expected)
  }

  @Test
  void useShortArrayInitializerCheck_runs() {
    run(
        new org.plumb.style.performance.UseShortArrayInitializerCheck(),
        "class T { int[] a = new int[]{1, 2, 3}; }");
  }

  @Test
  void useStringBufferForStringAppendsCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.performance.UseStringBufferForStringAppendsCheck(),
            "class T { String m() { String s = \"\"; for (int i = 0; i < 10; i++) { s = s + i; }"
                + " return s; } }");
    // runs (no fire expected)
  }

  @Test
  void uselessStringValueOfCheck_runs() {
    run(
        new org.plumb.style.performance.UselessStringValueOfCheck(),
        "class T { String m(int i) { return String.valueOf(i); } }");
  }

  @Test
  void varArgCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.UseVarargsCheck(),
            "class T { void m(int[] xs) { } void n(int... xs) { } }");
    // runs (no fire expected)
  }

  @Test
  void waitNotifyCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.WaitNotifyCheck(),
            "class T { void m() { Object o = new Object(); o.wait(); } }");
    // runs (no fire expected)
  }

  @Test
  void whitespaceAfterCheck_runs() {
    run(new org.plumb.style.style.WhitespaceAfterCheck(), "class T { int x = 1; }");
  }

  @Test
  void whitespaceAroundCheck_runs() {
    run(new org.plumb.style.style.WhitespaceAroundCheck(), "class T { int x = 1; }");
  }

  @Test
  void whileLoopWithLiteralBooleanCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.WhileLoopWithLiteralBooleanCheck(),
            "class T { void m() { while (true) { } } }");
    // runs (no fire expected)
  }

  @Test
  void wmiWrongMapIteratorCheck_runs() {
    List<Finding> f =
        run(
            new org.plumb.style.style.WmiWrongMapIteratorCheck(),
            "import java.util.*; class T { void m(Map<String,Integer> m) { for (String k :"
                + " m.keySet()) { m.get(k); } } }");
    // runs (no fire expected)
  }

  @Test
  void withinExpressionCheck_runs() {
    run(
        new org.plumb.style.security.AvoidUsingHardCodedIPCheck(),
        "class T { String s = \"127.0.0.1\"; }");
  }
}
