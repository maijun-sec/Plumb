package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.bug.EqualsNullCheck;
import org.plumb.style.error.AvoidCatchingThrowableCheck;
import org.plumb.style.naming.AbstractNamingCheck;
import org.plumb.style.naming.AvoidFinalLocalVariableCheck;
import org.plumb.style.naming.ClassNamingConventionsCheck;
import org.plumb.style.naming.LocalVariableCouldBeFinalCheck;
import org.plumb.style.naming.LongVariableCheck;
import org.plumb.style.performance.DontUseFloatTypeForLoopIndicesCheck;
import org.plumb.style.security.AvoidUsingHardCodedIPCheck;
import org.plumb.style.style.AssignmentToNonFinalStaticCheck;
import org.plumb.style.style.DefaultPackageCheck;
import org.plumb.style.style.DuplicateImportsCheck;
import org.plumb.style.style.EmptyStaticInitializerCheck;
import org.plumb.style.style.IdempotentOperationsCheck;
import org.plumb.style.style.ImmutableFieldCheck;
import org.plumb.style.style.InstantiationToGetClassCheck;

/**
 * gap-filling batch (16 new rules, pl-0551 through pl-0580). Each rule has a fire + no-fire test.
 * The 14 stubs in this batch (ArrayNamingCheck_PMD, AvoidEnumsCheck_PMD,
 * AvoidLiteralsInIfConditionCheck_PMD, BeanMembersShouldSerializeCheck_PMD,
 * CallSuperInConstructorCheck_PMD, CollapsibleIfStatementsCheck_PMD,
 * CouplingBetweenObjectsCheck_PMD, CyclomaticComplexityCheck_PMD, DataClassCheck_PMD,
 * DataflowAnomalyAnalysisCheck_PMD, DontImportJavaLangCheck_PMD,
 * EmptyMethodInAbstractClassCheck_PMD, LocalHomeNamingConventionCheck_PMD, MCReturnsVoidCheck_PMD)
 * are placeholders for follow-up rounds.
 */
class R282PmdGapFillingTest {

  private final RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);

  private List<?> run(String src, AstChecker check) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("R282.java"), src);
    StyleContext ctx = new StyleContext(ps, rules);
    new AstDispatcher().register(check).run(ctx);
    return ctx.findings();
  }

  @Test
  void abstractnamingcheck_pmd_fires() {
    String src = "abstract class N { void m(); }";
    assertThat(run(src, new AbstractNamingCheck()))
        .as("AbstractNamingCheck_PMD fires on bad code")
        .isNotEmpty();
  }

  @Test
  void abstractnamingcheck_pmd_noFire() {
    String src = "abstract class AbstractN { void m(); }";
    assertThat(run(src, new AbstractNamingCheck()))
        .as("AbstractNamingCheck_PMD does not fire on good code")
        .isEmpty();
  }

  @Test
  void assignmenttononfinalstaticcheck_pmd_fires() {
    String src = "class N { static int x; void m() { x = 1; } }";
    assertThat(run(src, new AssignmentToNonFinalStaticCheck()))
        .as("AssignmentToNonFinalStaticCheck_PMD fires on bad code")
        .isNotEmpty();
  }

  @Test
  void assignmenttononfinalstaticcheck_pmd_noFire() {
    String src = "class N { static final int X = 1; void m() {} }";
    assertThat(run(src, new AssignmentToNonFinalStaticCheck()))
        .as("AssignmentToNonFinalStaticCheck_PMD does not fire on good code")
        .isEmpty();
  }

  @Test
  void avoidcatchingthrowablecheck_pmd_fires() {
    String src = "class N { void m() { try {} catch (Throwable t) {} } }";
    assertThat(run(src, new AvoidCatchingThrowableCheck()))
        .as("AvoidCatchingThrowableCheck_PMD fires on bad code")
        .isNotEmpty();
  }

  @Test
  void avoidcatchingthrowablecheck_pmd_noFire() {
    String src = "class N { void m() { try {} catch (RuntimeException e) {} } }";
    assertThat(run(src, new AvoidCatchingThrowableCheck()))
        .as("AvoidCatchingThrowableCheck_PMD does not fire on good code")
        .isEmpty();
  }

  @Test
  void avoidfinallocalvariablecheck_pmd_fires() {
    String src = "class N { void m() { final int x = 1; System.out.println(x); } }";
    assertThat(run(src, new AvoidFinalLocalVariableCheck()))
        .as("AvoidFinalLocalVariableCheck_PMD fires on bad code")
        .isNotEmpty();
  }

  @Test
  void avoidfinallocalvariablecheck_pmd_noFire() {
    String src = "class N { void m() { int x = 1; System.out.println(x); } }";
    assertThat(run(src, new AvoidFinalLocalVariableCheck()))
        .as("AvoidFinalLocalVariableCheck_PMD does not fire on good code")
        .isEmpty();
  }

  @Test
  void avoidusinghardcodedipcheck_pmd_fires() {
    String src = "class N { String ip = \"10.0.0.1\"; }";
    assertThat(run(src, new AvoidUsingHardCodedIPCheck()))
        .as("AvoidUsingHardCodedIPCheck_PMD fires on bad code")
        .isNotEmpty();
  }

  @Test
  void avoidusinghardcodedipcheck_pmd_noFire() {
    String src = "class N { String ip = \"localhost\"; }";
    assertThat(run(src, new AvoidUsingHardCodedIPCheck()))
        .as("AvoidUsingHardCodedIPCheck_PMD does not fire on good code")
        .isEmpty();
  }

  @Test
  void classnamingconventionscheck_pmd_fires() {
    String src = "class my_class { }";
    assertThat(run(src, new ClassNamingConventionsCheck()))
        .as("ClassNamingConventionsCheck_PMD fires on bad code")
        .isNotEmpty();
  }

  @Test
  void classnamingconventionscheck_pmd_noFire() {
    String src = "class MyClass { }";
    assertThat(run(src, new ClassNamingConventionsCheck()))
        .as("ClassNamingConventionsCheck_PMD does not fire on good code")
        .isEmpty();
  }

  @Test
  void defaultpackagecheck_pmd_fires() {
    String src = "class N { }";
    assertThat(run(src, new DefaultPackageCheck()))
        .as("DefaultPackageCheck_PMD fires on bad code")
        .isNotEmpty();
  }

  @Test
  void defaultpackagecheck_pmd_noFire() {
    String src = "package com.x; class N { }";
    assertThat(run(src, new DefaultPackageCheck()))
        .as("DefaultPackageCheck_PMD does not fire on good code")
        .isEmpty();
  }

  @Test
  void dontusefloattypeforloopindicescheck_pmd_fires() {
    String src = "class N { void m() { for (float f = 0; f < 1; f += 0.1f) {} } }";
    assertThat(run(src, new DontUseFloatTypeForLoopIndicesCheck()))
        .as("DontUseFloatTypeForLoopIndicesCheck_PMD fires on bad code")
        .isNotEmpty();
  }

  @Test
  void dontusefloattypeforloopindicescheck_pmd_noFire() {
    String src = "class N { void m() { for (int i = 0; i < 10; i++) {} } }";
    assertThat(run(src, new DontUseFloatTypeForLoopIndicesCheck()))
        .as("DontUseFloatTypeForLoopIndicesCheck_PMD does not fire on good code")
        .isEmpty();
  }

  @Test
  void duplicateimportscheck_pmd_fires() {
    String src = "import java.util.List; import java.util.List; class N { List l; }";
    assertThat(run(src, new DuplicateImportsCheck()))
        .as("DuplicateImportsCheck_PMD fires on bad code")
        .isNotEmpty();
  }

  @Test
  void duplicateimportscheck_pmd_noFire() {
    String src = "import java.util.List; class N { List l; }";
    assertThat(run(src, new DuplicateImportsCheck()))
        .as("DuplicateImportsCheck_PMD does not fire on good code")
        .isEmpty();
  }

  @Test
  void emptystaticinitializercheck_pmd_fires() {
    String src = "class N { static { } }";
    assertThat(run(src, new EmptyStaticInitializerCheck()))
        .as("EmptyStaticInitializerCheck_PMD fires on bad code")
        .isNotEmpty();
  }

  @Test
  void emptystaticinitializercheck_pmd_noFire() {
    String src = "class N { static { System.out.println(\"x\"); } }";
    assertThat(run(src, new EmptyStaticInitializerCheck()))
        .as("EmptyStaticInitializerCheck_PMD does not fire on good code")
        .isEmpty();
  }

  @Test
  void equalsnullcheck_pmd_fires() {
    String src = "class N { boolean m(Object o) { return o.equals(null); } }";
    assertThat(run(src, new EqualsNullCheck()))
        .as("EqualsNullCheck_PMD fires on bad code")
        .isNotEmpty();
  }

  @Test
  void equalsnullcheck_pmd_noFire() {
    String src = "class N { boolean m(Object o) { return o == null; } }";
    assertThat(run(src, new EqualsNullCheck()))
        .as("EqualsNullCheck_PMD does not fire on good code")
        .isEmpty();
  }

  @Test
  void idempotentoperationscheck_pmd_fires() {
    String src = "class N { int x; void m() { x = x; } }";
    assertThat(run(src, new IdempotentOperationsCheck()))
        .as("IdempotentOperationsCheck_PMD fires on bad code")
        .isNotEmpty();
  }

  @Test
  void idempotentoperationscheck_pmd_noFire() {
    String src = "class N { int x; void m() { x = 1; } }";
    assertThat(run(src, new IdempotentOperationsCheck()))
        .as("IdempotentOperationsCheck_PMD does not fire on good code")
        .isEmpty();
  }

  @Test
  void immutablefieldcheck_pmd_fires() {
    String src = "class N { int x = 1; void m() { System.out.println(x); } }";
    assertThat(run(src, new ImmutableFieldCheck()))
        .as("ImmutableFieldCheck_PMD fires on bad code")
        .isNotEmpty();
  }

  @Test
  void immutablefieldcheck_pmd_noFire() {
    String src = "class N { final int x = 1; void m() { System.out.println(x); } }";
    assertThat(run(src, new ImmutableFieldCheck()))
        .as("ImmutableFieldCheck_PMD does not fire on good code")
        .isEmpty();
  }

  @Test
  void instantiationtogetclasscheck_pmd_fires() {
    String src = "class N { void m() { new N().getClass(); } }";
    assertThat(run(src, new InstantiationToGetClassCheck()))
        .as("InstantiationToGetClassCheck_PMD fires on bad code")
        .isNotEmpty();
  }

  @Test
  void instantiationtogetclasscheck_pmd_noFire() {
    String src = "class N { void m() { N n = new N(); n.getClass(); } }";
    assertThat(run(src, new InstantiationToGetClassCheck()))
        .as("InstantiationToGetClassCheck_PMD does not fire on good code")
        .isEmpty();
  }

  @Test
  void localvariablecouldbefinalcheck_pmd_fires() {
    String src = "class N { void m() { int x = 1; System.out.println(x); } }";
    assertThat(run(src, new LocalVariableCouldBeFinalCheck()))
        .as("LocalVariableCouldBeFinalCheck_PMD fires on bad code")
        .isNotEmpty();
  }

  @Test
  void localvariablecouldbefinalcheck_pmd_noFire() {
    String src = "class N { void m() { final int x = 1; System.out.println(x); } }";
    assertThat(run(src, new LocalVariableCouldBeFinalCheck()))
        .as("LocalVariableCouldBeFinalCheck_PMD does not fire on good code")
        .isEmpty();
  }

  @Test
  void longvariablecheck_pmd_fires() {
    String src = "class N { void m() { int thisIsAReallyLongVariableName = 1; } }";
    assertThat(run(src, new LongVariableCheck()))
        .as("LongVariableCheck_PMD fires on bad code")
        .isNotEmpty();
  }

  @Test
  void longvariablecheck_pmd_noFire() {
    String src = "class N { void m() { int x = 1; } }";
    assertThat(run(src, new LongVariableCheck()))
        .as("LongVariableCheck_PMD does not fire on good code")
        .isEmpty();
  }
}
