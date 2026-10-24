package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.bug.CompareToReturnValueCheck;
import org.plumb.style.design.ConstructorCallingOverridableCheck;
import org.plumb.style.design.PublicConstructorInAbstractClassCheck;
import org.plumb.style.error.CatchOfThrowableCheck;
import org.plumb.style.error.IteratorNextExceptionCheck;
import org.plumb.style.error.ObjectFinalizeOverloadCheck;
import org.plumb.style.error.RawExceptionCheck;
import org.plumb.style.naming.AtLeastOneConstructorCheck;
import org.plumb.style.naming.BadAbstractClassNameCheck;
import org.plumb.style.naming.BooleanGetMethodNameCheck;
import org.plumb.style.naming.ClassWithOnlyPrivateConstructorsCheck;
import org.plumb.style.performance.CollectionInappropriateCheck;
import org.plumb.style.performance.LoopsWithSameConditionCheck;
import org.plumb.style.performance.NullReturnedToStringCheck;
import org.plumb.style.performance.UnnecessaryBoxingCheck;
import org.plumb.style.security.CaseInsensitiveComparisonCheck;
import org.plumb.style.security.HardcodedIpCheck;
import org.plumb.style.style.CastArithmeticOperandCheck;
import org.plumb.style.style.ChildClassShadowFieldCheck;
import org.plumb.style.style.DiamondOperatorCheck;
import org.plumb.style.style.EmptyLineSeparatorCheck;
import org.plumb.style.style.EqualsArgumentTypeCheck;
import org.plumb.style.style.ForLoopFalseConditionCheck;
import org.plumb.style.style.LambdaParameterShadowingCheck;
import org.plumb.style.style.LoggersDeclarationCheck;
import org.plumb.style.style.MethodTooComplexCheck;
import org.plumb.style.style.PackageInfoCheck;
import org.plumb.style.style.PrintfFailCheck;
import org.plumb.style.style.RelationalOperatorSymmetryCheck;
import org.plumb.style.style.StandardFunctionalInterfaceCheck;
import org.plumb.style.style.UppercaseSuffixCheck;

/** : batch 5 (31 new rules, pl-0431 through pl-0461). Each rule has a fire + no-fire test. */
class R277SonarQubeBatch5Test {

  private final RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);

  private List<?> run(String src, AstChecker check) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("R277.java"), src);
    StyleContext ctx = new StyleContext(ps, rules);
    new AstDispatcher().register(check).run(ctx);
    return ctx.findings();
  }

  @Test
  void atleastoneconstructorcheck_sonar_fires() {
    String src = "abstract class N { void m(); }";
    assertThat(run(src, new AtLeastOneConstructorCheck()))
        .as("AtLeastOneConstructorCheck_Sonar fires on bad code")
        .isNotEmpty();
  }

  @Test
  void atleastoneconstructorcheck_sonar_noFire() {
    String src = "abstract class N { protected N() {} void m(); }";
    assertThat(run(src, new AtLeastOneConstructorCheck()))
        .as("AtLeastOneConstructorCheck_Sonar does not fire on good code")
        .isEmpty();
  }

  @Test
  void badabstractclassnamecheck_fires() {
    String src = "abstract class N { void m(); }";
    assertThat(run(src, new BadAbstractClassNameCheck()))
        .as("BadAbstractClassNameCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void badabstractclassnamecheck_noFire() {
    String src = "abstract class AbstractN { void m(); }";
    assertThat(run(src, new BadAbstractClassNameCheck()))
        .as("BadAbstractClassNameCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void booleangetmethodnamecheck_fires() {
    String src = "class N { public boolean getReady() { return true; } }";
    assertThat(run(src, new BooleanGetMethodNameCheck()))
        .as("BooleanGetMethodNameCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void booleangetmethodnamecheck_noFire() {
    String src = "class N { public boolean isReady() { return true; } }";
    assertThat(run(src, new BooleanGetMethodNameCheck()))
        .as("BooleanGetMethodNameCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void caseinsensitivecomparisoncheck_fires() {
    String src = "class N { boolean m(String s) { return s.toLowerCase().equals(\"x\"); } }";
    assertThat(run(src, new CaseInsensitiveComparisonCheck()))
        .as("CaseInsensitiveComparisonCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void caseinsensitivecomparisoncheck_noFire() {
    String src = "class N { boolean m(String s) { return s.equalsIgnoreCase(\"x\"); } }";
    assertThat(run(src, new CaseInsensitiveComparisonCheck()))
        .as("CaseInsensitiveComparisonCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void catchofthrowablecheck_fires() {
    String src = "class N { void m() { try {} catch (Throwable t) {} } }";
    assertThat(run(src, new CatchOfThrowableCheck()))
        .as("CatchOfThrowableCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void catchofthrowablecheck_noFire() {
    String src = "class N { void m() { try {} catch (RuntimeException e) {} } }";
    assertThat(run(src, new CatchOfThrowableCheck()))
        .as("CatchOfThrowableCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void castarithmeticoperandcheck_fires() {
    String src = "class N { long m(long a, long b) { return (long)(a * b); } }";
    assertThat(run(src, new CastArithmeticOperandCheck()))
        .as("CastArithmeticOperandCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void castarithmeticoperandcheck_noFire() {
    String src = "class N { int m(int a, int b) { return a * b; } }";
    assertThat(run(src, new CastArithmeticOperandCheck()))
        .as("CastArithmeticOperandCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void childclassshadowfieldcheck_fires() {
    String src = "class P { int x; } class N extends P { int x; }";
    assertThat(run(src, new ChildClassShadowFieldCheck()))
        .as("ChildClassShadowFieldCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void childclassshadowfieldcheck_noFire() {
    String src = "class P { int x; } class N extends P { int y; }";
    assertThat(run(src, new ChildClassShadowFieldCheck()))
        .as("ChildClassShadowFieldCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void classwithonlyprivateconstructorscheck_sonar_fires() {
    String src = "class N { private N() {} int m() { return 1; } }";
    assertThat(run(src, new ClassWithOnlyPrivateConstructorsCheck()))
        .as("ClassWithOnlyPrivateConstructorsCheck_Sonar fires on bad code")
        .isNotEmpty();
  }

  @Test
  void classwithonlyprivateconstructorscheck_sonar_noFire() {
    String src = "final class N { private N() {} int m() { return 1; } }";
    assertThat(run(src, new ClassWithOnlyPrivateConstructorsCheck()))
        .as("ClassWithOnlyPrivateConstructorsCheck_Sonar does not fire on good code")
        .isEmpty();
  }

  @Test
  void collectioninappropriatecheck_fires() {
    String src = "import java.util.Vector; class N { Vector v; }";
    assertThat(run(src, new CollectionInappropriateCheck()))
        .as("CollectionInappropriateCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void collectioninappropriatecheck_noFire() {
    String src = "import java.util.ArrayList; class N { ArrayList v; }";
    assertThat(run(src, new CollectionInappropriateCheck()))
        .as("CollectionInappropriateCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void comparetoreturnvaluecheck_fires() {
    String src = "class N implements Comparable<N> { public int compareTo(N o) { return 5; } }";
    assertThat(run(src, new CompareToReturnValueCheck()))
        .as("CompareToReturnValueCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void comparetoreturnvaluecheck_noFire() {
    String src = "class N implements Comparable<N> { public int compareTo(N o) { return 0; } }";
    assertThat(run(src, new CompareToReturnValueCheck()))
        .as("CompareToReturnValueCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void constructorcallingoverridablecheck_fires() {
    String src = "class N { N() { N(); } }";
    assertThat(run(src, new ConstructorCallingOverridableCheck()))
        .as("ConstructorCallingOverridableCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void constructorcallingoverridablecheck_noFire() {
    String src = "class N { N() { System.out.println(\"x\"); } void init() {} }";
    assertThat(run(src, new ConstructorCallingOverridableCheck()))
        .as("ConstructorCallingOverridableCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void diamondoperatorcheck_fires() {
    String src =
        "import java.util.List; class N { List<String> l = new java.util.ArrayList<String>(); }";
    assertThat(run(src, new DiamondOperatorCheck()))
        .as("DiamondOperatorCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void diamondoperatorcheck_noFire() {
    String src = "class N { Object o = new Object(); }";
    assertThat(run(src, new DiamondOperatorCheck()))
        .as("DiamondOperatorCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void emptylineseparatorcheck_sonar_fires() {
    String src = "class N { void a() {} void b() {} }";
    assertThat(run(src, new EmptyLineSeparatorCheck()))
        .as("EmptyLineSeparatorCheck_Sonar fires on bad code")
        .isNotEmpty();
  }

  @Test
  void emptylineseparatorcheck_sonar_noFire() {
    String src = "class N { void a() {}\n\n void b() {} }";
    assertThat(run(src, new EmptyLineSeparatorCheck()))
        .as("EmptyLineSeparatorCheck_Sonar does not fire on good code")
        .isEmpty();
  }

  @Test
  void equalsargumenttypecheck_fires() {
    String src = "class N { public boolean equals(String s) { return true; } }";
    assertThat(run(src, new EqualsArgumentTypeCheck()))
        .as("EqualsArgumentTypeCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void equalsargumenttypecheck_noFire() {
    String src = "class N { public boolean equals(Object o) { return true; } }";
    assertThat(run(src, new EqualsArgumentTypeCheck()))
        .as("EqualsArgumentTypeCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void forloopfalseconditioncheck_fires() {
    String src = "class N { void m() { for (int i=0; 0==0; i++) {} } }";
    assertThat(run(src, new ForLoopFalseConditionCheck()))
        .as("ForLoopFalseConditionCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void forloopfalseconditioncheck_noFire() {
    String src = "class N { void m() { for (int i=0; i<10; i++) {} } }";
    assertThat(run(src, new ForLoopFalseConditionCheck()))
        .as("ForLoopFalseConditionCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void hardcodedipcheck_fires() {
    String src = "class N { String s = \"10.0.0.1\"; }";
    assertThat(run(src, new HardcodedIpCheck()))
        .as("HardcodedIpCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void hardcodedipcheck_noFire() {
    String src = "class N { String s = \"localhost\"; }";
    assertThat(run(src, new HardcodedIpCheck()))
        .as("HardcodedIpCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void iteratornextexceptioncheck_fires() {
    String src = "class N { void m(java.util.List l) { Object o = l.iterator().next(); } }";
    assertThat(run(src, new IteratorNextExceptionCheck()))
        .as("IteratorNextExceptionCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void iteratornextexceptioncheck_noFire() {
    String src =
        "class N { void m(java.util.List l) { java.util.Iterator it = l.iterator(); if"
            + " (it.hasNext()) { Object o = it.next(); } } }";
    assertThat(run(src, new IteratorNextExceptionCheck()))
        .as("IteratorNextExceptionCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void lambdaparametershadowingcheck_fires() {
    String src =
        "class N { int x; void m() { java.util.function.Function<Integer,Integer> f = (x) -> x + 1;"
            + " } }";
    assertThat(run(src, new LambdaParameterShadowingCheck()))
        .as("LambdaParameterShadowingCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void lambdaparametershadowingcheck_noFire() {
    String src = "class N { int x; void m() { Runnable r = () -> { int y = 1; }; } }";
    assertThat(run(src, new LambdaParameterShadowingCheck()))
        .as("LambdaParameterShadowingCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void loggersdeclarationcheck_fires() {
    String src = "class N { Logger log = Logger.getLogger(\"x\"); }";
    assertThat(run(src, new LoggersDeclarationCheck()))
        .as("LoggersDeclarationCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void loggersdeclarationcheck_noFire() {
    String src = "class N { private static final Logger log = Logger.getLogger(\"x\"); }";
    assertThat(run(src, new LoggersDeclarationCheck()))
        .as("LoggersDeclarationCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void loopswithsameconditioncheck_fires() {
    String src =
        "class N { void m(int n) { for (int i=0; i<10; i++) { for (int j=0; i<10; j++) {} } } }";
    assertThat(run(src, new LoopsWithSameConditionCheck()))
        .as("LoopsWithSameConditionCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void loopswithsameconditioncheck_noFire() {
    String src =
        "class N { void m(int n, int m) { for (int i=0; i<n; i++) { for (int j=0; j<m; j++) {} } }"
            + " }";
    assertThat(run(src, new LoopsWithSameConditionCheck()))
        .as("LoopsWithSameConditionCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void methodtoocomplexcheck_fires() {
    String src =
        "class N { int m(int x) { int c = 0; if (x>0) c++; if (x>1) c++; if (x>2) c++; if (x>3)"
            + " c++; if (x>4) c++; if (x>5) c++; if (x>6) c++; if (x>7) c++; if (x>8) c++; if (x>9)"
            + " c++; if (x>10) c++; return c; } }";
    assertThat(run(src, new MethodTooComplexCheck()))
        .as("MethodTooComplexCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void methodtoocomplexcheck_noFire() {
    String src = "class N { int m(int x) { return x > 0 ? 1: 0; } }";
    assertThat(run(src, new MethodTooComplexCheck()))
        .as("MethodTooComplexCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void nullreturnedtostringcheck_fires() {
    String src = "class N { public String toString() { return null; } }";
    assertThat(run(src, new NullReturnedToStringCheck()))
        .as("NullReturnedToStringCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void nullreturnedtostringcheck_noFire() {
    String src = "class N { public String toString() { return \"\"; } }";
    assertThat(run(src, new NullReturnedToStringCheck()))
        .as("NullReturnedToStringCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void objectfinalizeoverloadcheck_fires() {
    String src = "class N { protected void finalize(boolean x) {} }";
    assertThat(run(src, new ObjectFinalizeOverloadCheck()))
        .as("ObjectFinalizeOverloadCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void objectfinalizeoverloadcheck_noFire() {
    String src = "class N { protected void finalize() {} }";
    assertThat(run(src, new ObjectFinalizeOverloadCheck()))
        .as("ObjectFinalizeOverloadCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void packageinfocheck_fires() {
    String src = "package com.x; class N { }";
    assertThat(run(src, new PackageInfoCheck()))
        .as("PackageInfoCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void packageinfocheck_noFire() {
    String src = "class N { }";
    assertThat(run(src, new PackageInfoCheck()))
        .as("PackageInfoCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void printffailcheck_fires() {
    String src = "class N { void m() { System.out.printf(\"x\"); } }";
    assertThat(run(src, new PrintfFailCheck()))
        .as("PrintfFailCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void printffailcheck_noFire() {
    String src = "class N { void m() { System.out.println(\"x\"); } }";
    assertThat(run(src, new PrintfFailCheck()))
        .as("PrintfFailCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void publicconstructorinabstractclasscheck_fires() {
    String src = "abstract class N { public N() {} }";
    assertThat(run(src, new PublicConstructorInAbstractClassCheck()))
        .as("PublicConstructorInAbstractClassCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void publicconstructorinabstractclasscheck_noFire() {
    String src = "abstract class N { protected N() {} }";
    assertThat(run(src, new PublicConstructorInAbstractClassCheck()))
        .as("PublicConstructorInAbstractClassCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void rawexceptioncheck_fires() {
    String src = "class N { void m() { throw new RuntimeException(\"x\"); } }";
    assertThat(run(src, new RawExceptionCheck()))
        .as("RawExceptionCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void rawexceptioncheck_noFire() {
    String src = "class N { void m() { throw new IllegalStateException(\"x\"); } }";
    assertThat(run(src, new RawExceptionCheck()))
        .as("RawExceptionCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void relationaloperatorsymmetrycheck_fires() {
    String src = "class N { boolean m(int a, int b) { return a == b || a == b; } }";
    assertThat(run(src, new RelationalOperatorSymmetryCheck()))
        .as("RelationalOperatorSymmetryCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void relationaloperatorsymmetrycheck_noFire() {
    String src = "class N { boolean m(int a, int b) { return a > b && b < a; } }";
    assertThat(run(src, new RelationalOperatorSymmetryCheck()))
        .as("RelationalOperatorSymmetryCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void standardfunctionalinterfacecheck_fires() {
    String src = "interface N { void m(); }";
    assertThat(run(src, new StandardFunctionalInterfaceCheck()))
        .as("StandardFunctionalInterfaceCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void standardfunctionalinterfacecheck_noFire() {
    String src = "@FunctionalInterface interface N { void m(); }";
    assertThat(run(src, new StandardFunctionalInterfaceCheck()))
        .as("StandardFunctionalInterfaceCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void unnecessaryboxingcheck_sonar_fires() {
    String src = "class N { Integer i = new Integer(5); }";
    assertThat(run(src, new UnnecessaryBoxingCheck()))
        .as("UnnecessaryBoxingCheck_Sonar fires on bad code")
        .isNotEmpty();
  }

  @Test
  void unnecessaryboxingcheck_sonar_noFire() {
    String src = "class N { Integer i = Integer.valueOf(5); }";
    assertThat(run(src, new UnnecessaryBoxingCheck()))
        .as("UnnecessaryBoxingCheck_Sonar does not fire on good code")
        .isEmpty();
  }

  @Test
  void uppercasesuffixcheck_fires() {
    String src = "class N { long x = 100l; }";
    assertThat(run(src, new UppercaseSuffixCheck()))
        .as("UppercaseSuffixCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void uppercasesuffixcheck_noFire() {
    String src = "class N { long x = 100L; }";
    assertThat(run(src, new UppercaseSuffixCheck()))
        .as("UppercaseSuffixCheck does not fire on good code")
        .isEmpty();
  }
}
