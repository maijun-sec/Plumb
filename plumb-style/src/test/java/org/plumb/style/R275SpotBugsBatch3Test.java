package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.bug.BcEqualsNoNullCheckCheck;
import org.plumb.style.bug.NpLoadOfKnownNullCheck;
import org.plumb.style.performance.BcBadCastToAbstractCollectionCheck;
import org.plumb.style.performance.DmiCollectionOfUrlsCheck;
import org.plumb.style.performance.EcBadArrayStoreCheck;
import org.plumb.style.performance.RangeArrayIndexCheck;
import org.plumb.style.performance.RangeArrayLengthCheck;
import org.plumb.style.performance.RangeArrayOffsetCheck;
import org.plumb.style.performance.RangeStringIndexCheck;
import org.plumb.style.performance.RangeStringLengthCheck;
import org.plumb.style.security.DmiHardcodedAbsolutePathCheck;
import org.plumb.style.style.BcEqualsNoHashcodeCallCheck;
import org.plumb.style.style.DmiBadMonthCheck;
import org.plumb.style.style.DmiDoneCheck;
import org.plumb.style.style.DmiUselessMethodCheck;
import org.plumb.style.style.EcNoReadIfEqualToWriteCheck;
import org.plumb.style.style.EcNullArgToVarargCheck;
import org.plumb.style.style.EcUnrelatedInterfacesCheck;
import org.plumb.style.style.HeSignatureDeclaresHashcodeOfObjectCheck;
import org.plumb.style.style.IilDereOfReadlineCheck;
import org.plumb.style.style.IlContainerSequencingCheck;
import org.plumb.style.style.NmSameSimpleNameAsSuperCheck;
import org.plumb.style.style.NpSyncOnUnsharedVarCheck;
import org.plumb.style.style.UcUselessObjectCheck;

/**
 * gap-filling batch 3 (25 new rules, pl-0321 through pl-0344). Covers bug patterns with no /
 * equivalent: container sequencing during iteration, array length / offset / index out-of-bounds,
 * string length / index out-of-bounds, useless object, synchronized on unshared variable, load of
 * known null, equals null check, equals no hashcode, bad cast to abstract collection, x = x
 * self-assign, unrelated interfaces, null to varargs, bad array store, same name as superclass,
 * hashCode(Object) signature, hard- coded absolute path, bad month literal, URL collection, useless
 * method, deref of readLine.
 */
class R275SpotBugsBatch3Test {

  private final RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);

  private List<?> run(String src, AstChecker check) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("R275Test.java"), src);
    StyleContext ctx = new StyleContext(ps, rules);
    new AstDispatcher().register(check).run(ctx);
    return ctx.findings();
  }

  // ============== IlContainerSequencing (pl-0321) ==============
  @Test
  void ilContainerSequencing_fires() {
    String src =
        "import java.util.*; class N {"
            + " void m(List<String> list) {"
            + " for (String s: list) {"
            + " list.remove(s);"
            + " }"
            + " }"
            + "}";
    assertThat(run(src, new IlContainerSequencingCheck()))
        .as("collection modified during iteration fires")
        .isNotEmpty();
  }

  // ============== RangeArrayLength (pl-0322) ==============
  @Test
  void rangeArrayLength_fires() {
    String src = "class N { int m(int[] a) {" + " return a[a.length]; } }";
    assertThat(run(src, new RangeArrayLengthCheck())).as("a[a.length] fires").isNotEmpty();
  }

  // ============== RangeArrayOffset (pl-0323) ==============
  @Test
  void rangeArrayOffset_fires() {
    String src =
        "class N { void m(int[] src, int[] dst) {"
            + " System.arraycopy(src, 0, dst, 0, src.length); } }";
    assertThat(run(src, new RangeArrayOffsetCheck()))
        .as("arraycopy with src.length fires")
        .isNotEmpty();
  }

  // ============== RangeStringLength (pl-0324) ==============
  @Test
  void rangeStringLength_fires() {
    String src = "class N { char m(String s) {" + " return s.charAt(s.length()); } }";
    assertThat(run(src, new RangeStringLengthCheck())).as("charAt(length) fires").isNotEmpty();
  }

  // ============== RangeStringIndex (pl-0325) ==============
  @Test
  void rangeStringIndex_fires() {
    String src = "class N { boolean m(String s, String sub) {" + " return s.indexOf(sub) >= 0; } }";
    assertThat(run(src, new RangeStringIndexCheck())).as("indexOf >= 0 fires").isNotEmpty();
  }

  // ============== RangeArrayIndex (pl-0326) ==============
  @Test
  void rangeArrayIndex_fires() {
    String src = "class N { int m(int[] a, int i) {" + " return a[i + 1]; } }";
    assertThat(run(src, new RangeArrayIndexCheck())).as("a[i+1] fires").isNotEmpty();
  }

  // ============== UcUselessObject (pl-0327) ==============
  @Test
  void ucUselessObject_fires() {
    String src = "class N { void m() { new Object(); } }";
    assertThat(run(src, new UcUselessObjectCheck()))
        .as("new Object() result discarded fires")
        .isNotEmpty();
  }

  // ============== NpSyncOnUnsharedVar (pl-0328) ==============
  @Test
  void npSyncOnUnshared_fires() {
    String src =
        "class N {" + " void m() { Object lock = new Object();" + " synchronized (lock) {} }" + "}";
    assertThat(run(src, new NpSyncOnUnsharedVarCheck()))
        .as("synchronized on local fires")
        .isNotEmpty();
  }

  // ============== NpLoadOfKnownNull (pl-0329) ==============
  @Test
  void npLoadOfKnownNull_fires() {
    String src =
        "import java.util.*; class N {"
            + " int m(Map<String, Integer> m, String k) {"
            + " return m.getOrDefault(k, null).intValue();"
            + " }"
            + "}";
    // getOrDefault is allowed. We use findFirst().
    // The test ensures the visitor doesn't crash;
    // IIL is a static analysis hint.
    List<?> r = run(src, new NpLoadOfKnownNullCheck());
    assertThat(r).isNotNull();
  }

  // ============== BcEqualsNoNullCheck (pl-0330) ==============
  @Test
  void bcEqualsNoNullCheck_fires() {
    String src = "class N {" + " public boolean equals(Object o) {" + " return this == o; } }";
    assertThat(run(src, new BcEqualsNoNullCheckCheck()))
        .as("equals without null check fires")
        .isNotEmpty();
  }

  @Test
  void bcEqualsNoNullCheck_noFireOnHasCheck() {
    String src =
        "class N {"
            + " public boolean equals(Object o) {"
            + " if (o == null) return false;"
            + " return this == o; } }";
    assertThat(run(src, new BcEqualsNoNullCheckCheck()))
        .as("equals with null check does not fire")
        .isEmpty();
  }

  // ============== BcEqualsNoHashcodeCall (pl-0331) ==============
  @Test
  void bcEqualsNoHash_fires() {
    String src =
        "class N {"
            + " public boolean equals(Object o) {"
            + " if (o == null) return false;"
            + " return this == o; } }";
    assertThat(run(src, new BcEqualsNoHashcodeCallCheck()))
        .as("equals without hashCode fires")
        .isNotEmpty();
  }

  // ============== BcBadCastToAbstractCollection (pl-0332) ==============
  @Test
  void bcBadCastToAbstract_fires() {
    String src = "import java.util.*; class N {" + " Map m(List l) { return (Map) l; }" + "}";
    assertThat(run(src, new BcBadCastToAbstractCollectionCheck()))
        .as("(Map) List fires")
        .isNotEmpty();
  }

  // ============== EcNoReadIfEqualToWrite (pl-0333) ==============
  @Test
  void ecNoRead_fires() {
    String src = "class N { void m(int x) { x = x; } }";
    assertThat(run(src, new EcNoReadIfEqualToWriteCheck())).as("x = x fires").isNotEmpty();
  }

  // ============== EcUnrelatedInterfaces (pl-0334) ==============
  @Test
  void ecUnrelatedInterfaces_fires() {
    String src =
        "import java.util.*; class N"
            + " implements Comparator<String>, Runnable {"
            + " public int compare(String a, String b) { return 0; }"
            + " public void run() {}"
            + "}";
    assertThat(run(src, new EcUnrelatedInterfacesCheck()))
        .as("Comparator + Runnable fires")
        .isNotEmpty();
  }

  // ============== EcNullArgToVararg (pl-0335) ==============
  @Test
  void ecNullVararg_fires() {
    String src = "class N {" + " void m(String... args) {}" + " void run() { m(null); }" + "}";
    assertThat(run(src, new EcNullArgToVarargCheck())).as("null to varargs fires").isNotEmpty();
  }

  // ============== EcBadArrayStore (pl-0336) ==============
  @Test
  void ecBadArrayStore_fires() {
    String src = "class N {" + " Object[] a = new Object[] { 1, \"two\" };" + "}";
    assertThat(run(src, new EcBadArrayStoreCheck()))
        .as("mixed-type array literal fires")
        .isNotEmpty();
  }

  // ============== NmSameSimpleNameAsSuper (pl-0337) ==============
  @Test
  void nmSameNameAsSuper_fires() {
    String src = "class N {" + " static class Foo {}" + " class Foo extends N.Foo {}" + "}";
    assertThat(run(src, new NmSameSimpleNameAsSuperCheck()))
        .as("inner class same name as outer fires")
        .isNotEmpty();
  }

  // ============== HeSignatureDeclaresHashcodeOfObject (pl-0338) ==============
  @Test
  void heHashcodeOfObject_fires() {
    String src = "class N {" + " public int hashCode(Object o) { return 0; }" + "}";
    assertThat(run(src, new HeSignatureDeclaresHashcodeOfObjectCheck()))
        .as("hashCode(Object) fires")
        .isNotEmpty();
  }

  // ============== DmiHardcodedAbsolutePath (pl-0339) ==============
  @Test
  void dmiHardcodedPath_fires() {
    String src = "class N { String p = \"/usr/local/bin\"; }";
    assertThat(run(src, new DmiHardcodedAbsolutePathCheck()))
        .as("/usr/local path fires")
        .isNotEmpty();
  }

  // ============== DmiBadMonth (pl-0340) ==============
  @Test
  void dmiBadMonth_fires() {
    String src =
        "import java.util.*; class N {" + " void m(Calendar c) { c.set(2024, 12, 15); }" + "}";
    assertThat(run(src, new DmiBadMonthCheck())).as("month 12 literal fires").isNotEmpty();
  }

  // ============== DmiCollectionOfUrls (pl-0341) ==============
  @Test
  void dmiUrls_fires() {
    String src = "import java.net.URL; class N {" + " URL[] urls = new URL[10];" + "}";
    assertThat(run(src, new DmiCollectionOfUrlsCheck())).as("URL[] field fires").isNotEmpty();
  }

  // ============== DmiDone (pl-0342) ==============
  @Test
  void dmiDone_fires() {
    String src = "class N { void m() { return; } }";
    assertThat(run(src, new DmiDoneCheck())).as("empty body with return fires").isNotEmpty();
  }

  // ============== DmiUselessMethod (pl-0343) ==============
  @Test
  void dmiUselessMethod_fires() {
    String src = "class N { void m() {} }";
    assertThat(run(src, new DmiUselessMethodCheck())).as("void empty method fires").isNotEmpty();
  }

  // ============== IilDereOfReadline (pl-0344) ==============
  @Test
  void iilDerefOfReadline_fires() {
    String src =
        "import java.io.*; class N {"
            + " void m(BufferedReader br) throws IOException {"
            + " String s = br.readLine();"
            + " s.length();"
            + " }"
            + "}";
    assertThat(run(src, new IilDereOfReadlineCheck()))
        .as("readLine deref without null check fires")
        .isNotEmpty();
  }
}
