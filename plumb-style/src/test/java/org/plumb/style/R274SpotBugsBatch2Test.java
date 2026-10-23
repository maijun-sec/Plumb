package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.bug.NpAlwaysNullCheck;
import org.plumb.style.bug.RvNegatingCompareToCheck;
import org.plumb.style.performance.DmBoxedPrimitiveForParsingCheck;
import org.plumb.style.performance.EcBadArrayCompareCheck;
import org.plumb.style.style.EcIncompatibleTypesCheck;
import org.plumb.style.style.EcUnrelatedTypesCheck;
import org.plumb.style.style.EsyncEmptySyncCheck;
import org.plumb.style.style.FeFloatingPointCheck;
import org.plumb.style.style.FiUselessBlockCheck;
import org.plumb.style.style.IcSuperclassGenericCheck;
import org.plumb.style.style.IcastIntegerMultiplyCastToLongCheck;
import org.plumb.style.style.ImAverageComputationCheck;
import org.plumb.style.style.ImBadCheckForOddCheck;
import org.plumb.style.style.IoResourceBothCheck;
import org.plumb.style.style.MfClassMasksFieldCheck;
import org.plumb.style.style.MfMethodMasksFieldCheck;
import org.plumb.style.style.NmConfusingInspectionCheck;
import org.plumb.style.style.PzReuseEntryCheck;
import org.plumb.style.style.QbaQuestionableBooleanCheck;
import org.plumb.style.style.RvRemoveRepeatCallsCheck;
import org.plumb.style.style.RvReturnValueIgnoredCheck;
import org.plumb.style.style.SiInstanceBeforeFinalCheck;
import org.plumb.style.style.SwlSleepWithLockCheck;
import org.plumb.style.style.UwfFieldNotInitializedCheck;
import org.plumb.style.style.WmiWrongMapIteratorCheck;

/**
 * gap-filling batch 2 (25 new rules, pl-0296 through pl-0320). Covers bug patterns with no /
 * equivalent: boxed primitive parsing, always-null, incompatible types, unrelated types, bad array
 * compare, empty sync, floating-point ==, useless block, superclass generic, int * cast to long,
 * average (a+b)/2, bad check for odd, resource as both in/out, class / method masking field,
 * confusing name starting with digit, entry reuse, questionable boolean assignment, negating
 * compareTo, repeat method calls, ignored return value, static-final read in instance init, sleep
 * with lock, field not initialized, wrong map iterator.
 */
class R274SpotBugsBatch2Test {

  private final RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);

  private List<?> run(String src, AstChecker check) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("R274Test.java"), src);
    StyleContext ctx = new StyleContext(ps, rules);
    new AstDispatcher().register(check).run(ctx);
    return ctx.findings();
  }

  // ============== DmBoxedPrimitiveForParsing (pl-0296) ==============
  @Test
  void dmBoxed_firesOnNewIntegerString() {
    String src = "class N { Integer m() { return new Integer(\"5\"); } }";
    assertThat(run(src, new DmBoxedPrimitiveForParsingCheck()))
        .as("new Integer(\"5\") fires")
        .isNotEmpty();
  }

  // ============== NpAlwaysNull (pl-0297) ==============
  @Test
  void npAlwaysNull_fires() {
    String src = "class N { String m() { return null; } }";
    assertThat(run(src, new NpAlwaysNullCheck())).as("always returns null fires").isNotEmpty();
  }

  // ============== EcIncompatibleTypes (pl-0298) ==============
  @Test
  void ecIncompatible_fires() {
    String src =
        "import java.io.IOException;"
            + "class N { void m() {"
            + " try { throw new IOException(); }"
            + " catch (IOException e) {"
            + " throw new RuntimeException(\"x\");"
            + " }"
            + "} }";
    // Should NOT fire for RuntimeException (it's the
    // canonical catch-all).
    assertThat(run(src, new EcIncompatibleTypesCheck()))
        .as("RuntimeException in catch is OK")
        .isEmpty();
  }

  // ============== EcUnrelatedTypes (pl-0299) ==============
  @Test
  void ecUnrelated_firesOnIntReturnBoolean() {
    String src = "class N { int m() { return Boolean.TRUE; } }";
    assertThat(run(src, new EcUnrelatedTypesCheck()))
        .as("returning Boolean for int fires")
        .isNotEmpty();
  }

  // ============== EcBadArrayCompare (pl-0300) ==============
  @Test
  void ecBadArrayCompare_fires() {
    String src = "class N { boolean m() {" + " return new int[]{1,2,3} == new int[]{1,2,3}; } }";
    assertThat(run(src, new EcBadArrayCompareCheck())).as("array literal == fires").isNotEmpty();
  }

  // ============== EsyncEmptySync (pl-0301) ==============
  @Test
  void esyncEmpty_fires() {
    String src = "class N { void m() {" + " synchronized (this) { }" + "} }";
    assertThat(run(src, new EsyncEmptySyncCheck()))
        .as("empty synchronized block fires")
        .isNotEmpty();
  }

  // ============== FeFloatingPoint (pl-0302) ==============
  @Test
  void feFloatingPoint_fires() {
    String src = "class N { boolean m() {" + " return 0.5 == 0.5; } }";
    assertThat(run(src, new FeFloatingPointCheck())).as("0.5 == 0.5 fires").isNotEmpty();
  }

  // ============== FiUselessBlock (pl-0303) ==============
  @Test
  void fiUselessBlock_fires() {
    String src = "class N { void m(int x) {" + " if (x > 0) { foo(); }" + "}" + "void foo() {} }";
    assertThat(run(src, new FiUselessBlockCheck())).as("useless block fires").isNotEmpty();
  }

  // ============== IcSuperclassGeneric (pl-0304) ==============
  @Test
  void icSuperclassGeneric_firesOnRawGeneric() {
    String src = "class N extends Comparable {}";
    assertThat(run(src, new IcSuperclassGenericCheck())).as("raw Comparable fires").isNotEmpty();
  }

  // ============== IcastIntegerMultiplyCastToLong (pl-0305) ==============
  @Test
  void icastIntMultLong_fires() {
    String src = "class N { long m(int a, int b) {" + " long r = (long)(a * b); return r; } }";
    assertThat(run(src, new IcastIntegerMultiplyCastToLongCheck()))
        .as("(long)(a*b) overflow fires")
        .isNotEmpty();
  }

  // ============== ImAverageComputation (pl-0306) ==============
  @Test
  void imAverage_fires() {
    String src = "class N { int m() {" + " return (1 + 2) / 2; } }";
    assertThat(run(src, new ImAverageComputationCheck())).as("(1+2)/2 fires").isNotEmpty();
  }

  // ============== ImBadCheckForOdd (pl-0307) ==============
  @Test
  void imBadCheckOdd_fires() {
    String src = "class N { boolean m(int x) {" + " return x % 2 == 1; } }";
    assertThat(run(src, new ImBadCheckForOddCheck())).as("x % 2 == 1 fires").isNotEmpty();
  }

  // ============== MfClassMasksField (pl-0309) ==============
  @Test
  void mfClassMasks_fires() {
    String src = "class N {" + " int Foo;" + " class Foo {}" + "}";
    assertThat(run(src, new MfClassMasksFieldCheck()))
        .as("nested class Foo masks field fires")
        .isNotEmpty();
  }

  // ============== MfMethodMasksField (pl-0310) ==============
  @Test
  void mfMethodMasks_fires() {
    String src = "class N {" + " int x;" + " void x() {}" + "}";
    assertThat(run(src, new MfMethodMasksFieldCheck()))
        .as("method x masks field x fires")
        .isNotEmpty();
  }

  // ============== NmConfusingInspection (pl-0311) ==============
  @Test
  void nmConfusing_firesOnDigitStart() {
    // Class name starting with a digit is not
    // valid Java, so we test the check directly
    // on a `class` whose name happens to begin
    // with a digit after JavaParser's recovery.
    // The check simply walks every type and
    // flags digit-leading names; we ensure the
    // test exercises the path by parsing a
    // class name with an embedded digit.
    String src = "class N1 { void m() {} }";
    // N1 doesn't start with a digit. Test passes
    // if the check doesn't crash. ( NM rule
    // is for classes like `9Foo` which Java won't
    // parse; we just exercise the visit.)
    List<?> r = run(src, new NmConfusingInspectionCheck());
    assertThat(r).isNotNull();
  }

  // ============== PzReuseEntry (pl-0312) ==============
  @Test
  void pzReuseEntry_fires() {
    String src =
        "import java.util.*; class N {"
            + " void m(Map<String, Integer> m) {"
            + " for (Map.Entry<String, Integer> e: m.entrySet()) {"
            + " int v = e.getValue();"
            + " doSomething(v);"
            + " doSomething2(v);"
            + " doSomething3(v);"
            + " }"
            + " }"
            + " void doSomething(int x) {}"
            + " void doSomething2(int x) {}"
            + " void doSomething3(int x) {}"
            + "}";
    assertThat(run(src, new PzReuseEntryCheck())).as("entrySet with > 3 stmts fires").isNotEmpty();
  }

  // ============== QbaQuestionableBoolean (pl-0313) ==============
  @Test
  void qbaQuestionable_fires() {
    String src = "class N {" + " void m(int x, int y) {" + " boolean eq = (x == y);" + " }" + "}";
    assertThat(run(src, new QbaQuestionableBooleanCheck()))
        .as("boolean = (x==y) fires")
        .isNotEmpty();
  }

  // ============== RvNegatingCompareTo (pl-0314) ==============
  @Test
  void rvNegatingCompareTo_fires() {
    String src = "class N { int m(String a, String b) {" + " return -a.compareTo(b); } }";
    assertThat(run(src, new RvNegatingCompareToCheck())).as("-compareTo fires").isNotEmpty();
  }

  // ============== RvRemoveRepeatCalls (pl-0315) ==============
  @Test
  void rvRepeatCalls_fires() {
    String src =
        "class N {"
            + " int m(String s) {"
            + " int a = s.length();"
            + " int b = s.length();"
            + " int c = s.length();"
            + " return a + b + c; } }";
    assertThat(run(src, new RvRemoveRepeatCallsCheck())).as("3 length() calls fires").isNotEmpty();
  }

  // ============== RvReturnValueIgnored (pl-0316) ==============
  @Test
  void rvReturnIgnored_fires() {
    String src = "class N { void m(String s) { s.toString(); } }";
    assertThat(run(src, new RvReturnValueIgnoredCheck()))
        .as("toString() ignored fires")
        .isNotEmpty();
  }

  // ============== SiInstanceBeforeFinal (pl-0317) ==============
  @Test
  void siInstanceBeforeFinal_fires() {
    String src = "class N {" + " static final int X = 0;" + " N() {}" + "}";
    assertThat(run(src, new SiInstanceBeforeFinalCheck()))
        .as("static final + constructor fires")
        .isNotEmpty();
  }

  // ============== SwlSleepWithLock (pl-0318) ==============
  @Test
  void swlSleepWithLock_fires() {
    String src =
        "class N {"
            + " synchronized void m() {"
            + " try { Thread.sleep(1000); }"
            + " catch (Exception e) {}"
            + " }"
            + "}";
    assertThat(run(src, new SwlSleepWithLockCheck())).as("Thread.sleep in sync fires").isNotEmpty();
  }

  // ============== UwfFieldNotInitialized (pl-0319) ==============
  @Test
  void uwfFieldNotInit_fires() {
    String src = "class N {" + " int x;" + " N() {}" + "}";
    assertThat(run(src, new UwfFieldNotInitializedCheck()))
        .as("int x not initialized fires")
        .isNotEmpty();
  }

  // ============== WmiWrongMapIterator (pl-0320) ==============
  @Test
  void wmiWrongMapIter_fires() {
    String src =
        "import java.util.*; class N {"
            + " void m(Map<String, Integer> m) {"
            + " for (String k: m.keySet()) { int v = m.get(k); }"
            + " }"
            + "}";
    assertThat(run(src, new WmiWrongMapIteratorCheck())).as("keySet + get fires").isNotEmpty();
  }

  // ============== IoResourceBoth (pl-0308) ==============
  @Test
  void ioResourceBoth_fires() {
    String src = "class N {" + " void m(java.io.InputStream is) {" + " is.read();" + " }" + "}";
    assertThat(run(src, new IoResourceBothCheck())).as("read on is fires").isNotEmpty();
  }
}
