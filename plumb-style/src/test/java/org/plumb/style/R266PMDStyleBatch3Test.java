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
import org.plumb.style.bug.CompareObjectsWithEqualsCheck;
import org.plumb.style.bug.JumbledIncrementerCheck;
import org.plumb.style.error.AvoidInstanceofChecksInCatchClauseCheck;
import org.plumb.style.error.AvoidLosingExceptionInformationCheck;
import org.plumb.style.naming.SuspiciousEqualsMethodNameCheck;
import org.plumb.style.naming.UseUtilityClassCheck;
import org.plumb.style.performance.AvoidArrayLoopsCheck;
import org.plumb.style.performance.ForLoopWithSizeMethodCheck;
import org.plumb.style.performance.UseAssertTrueInsteadOfAssertEqualsCheck;
import org.plumb.style.performance.UseCollectionIsEmptyCheck;
import org.plumb.style.performance.UseIndexOfCharCheck;
import org.plumb.style.performance.UselessStringValueOfCheck;
import org.plumb.style.style.AssignmentToNonFinalStaticCheck;
import org.plumb.style.style.AvoidReassigningLoopVariablesCheck;
import org.plumb.style.style.DontImportJavaLangCheck;
import org.plumb.style.style.DuplicateImportsCheck;
import org.plumb.style.style.EmptyIfStmtCheck;
import org.plumb.style.style.EmptyStaticInitializerCheck;
import org.plumb.style.style.EmptySwitchStatementsCheck;
import org.plumb.style.style.EmptyTryBlockCheck;
import org.plumb.style.style.EqualComparisonCheck;
import org.plumb.style.style.PreserveStackTraceCheck;
import org.plumb.style.style.RedundantFieldInitializerCheck;
import org.plumb.style.style.SystemPrintlnCheck;
import org.plumb.style.style.UnnecessaryParenthesesCheck;
import org.plumb.style.style.WhileLoopWithLiteralBooleanCheck;

/** / / style checkers batch 3 (25 more rules, pl-0120 through pl-0159). */
class R266PMDStyleBatch3Test {

  private final RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);

  private List<?> run(String src, AstChecker check) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("R266Test.java"), src);
    StyleContext ctx = new StyleContext(ps, rules);
    new AstDispatcher().register(check).run(ctx);
    return ctx.findings();
  }

  // ============== AssignmentToNonFinalStatic (pl-0120) ==============
  @Test
  void assignNonFinalStatic_fires() {
    String src = "class N { static int x;" + " void m() { N.x = 1; } }";
    assertThat(run(src, new AssignmentToNonFinalStaticCheck()))
        .as("assign to non-final static fires")
        .isNotEmpty();
  }

  // ============== AvoidArrayLoops (pl-0121) ==============
  @Test
  void avoidArrayLoops_fires() {
    String src =
        "class N { void m(int[] arr) {"
            + " for (int i = 0; i < arr.length; i++) { System.out.println(arr[i]); } } }";
    assertThat(run(src, new AvoidArrayLoopsCheck())).as("for-loop with arr[i] fires").isNotEmpty();
  }

  // ============== AvoidInstanceofChecksInCatchClause (pl-0122) ==============
  @Test
  void instanceofInCatch_fires() {
    String src =
        "class N { void m() {"
            + " try { int x = 1; }"
            + " catch (Exception e) {"
            + " if (e instanceof RuntimeException) { } } } }";
    assertThat(run(src, new AvoidInstanceofChecksInCatchClauseCheck()))
        .as("instanceof in catch fires")
        .isNotEmpty();
  }

  // ============== AvoidLosingExceptionInformation (pl-0123) ==============
  @Test
  void loseExceptionInfo_fires() {
    String src =
        "class N { void m() {"
            + " try { int x = 1; }"
            + " catch (Exception e) {"
            + " throw new RuntimeException(\"oops\");"
            + " } } }";
    assertThat(run(src, new AvoidLosingExceptionInformationCheck()))
        .as("throw without original cause fires")
        .isNotEmpty();
  }

  // ============== DontImportJavaLang (pl-0125) ==============
  @Test
  void dontImportJavaLang_fires() {
    String src = "import java.lang.String;" + "class N { String s = \"hi\"; }";
    assertThat(run(src, new DontImportJavaLangCheck()))
        .as("import java.lang.String fires")
        .isNotEmpty();
  }

  // ============== DuplicateImports (pl-0126) ==============
  @Test
  void duplicateImports_fires() {
    String src =
        "import java.util.List;" + "import java.util.List;" + "class N { List<String> l; }";
    assertThat(run(src, new DuplicateImportsCheck())).as("duplicate import fires").isNotEmpty();
  }

  // ============== EmptyIfStmt (pl-0127) ==============
  @Test
  void emptyIfStmt_fires() {
    String src = "class N { void m(int x) { if (x > 0); } }";
    assertThat(run(src, new EmptyIfStmtCheck())).as("empty if (cond); fires").isNotEmpty();
  }

  // ============== EmptyStaticInitializer (pl-0128) ==============
  @Test
  void emptyStaticInit_fires() {
    String src = "class N { static { } }";
    assertThat(run(src, new EmptyStaticInitializerCheck()))
        .as("empty static initializer fires")
        .isNotEmpty();
  }

  // ============== EmptySwitchStatements (pl-0129) ==============
  @Test
  void emptySwitch_fires() {
    String src = "class N { void m(int x) { switch (x) {} } }";
    assertThat(run(src, new EmptySwitchStatementsCheck())).as("empty switch fires").isNotEmpty();
  }

  // ============== EmptyTryBlock (pl-0130) ==============
  @Test
  void emptyTryBlock_fires() {
    String src = "class N { void m() { try {}" + " catch (Exception e) { } } }";
    assertThat(run(src, new EmptyTryBlockCheck())).as("empty try block fires").isNotEmpty();
  }

  // ============== EqualComparison (pl-0132) ==============
  @Test
  void equalComparison_fires() {
    String src = "class N { boolean m(String str1, String str2) {" + " return str1 == str2; } }";
    assertThat(run(src, new EqualComparisonCheck())).as("== on String fires").isNotEmpty();
  }

  // ============== RedundantFieldInitializer (pl-0135) ==============
  @Test
  void redundantInit_fires() {
    String src = "class N { int x = 0; boolean b = false; }";
    assertThat(run(src, new RedundantFieldInitializerCheck())).as("int x = 0 fires").isNotEmpty();
  }

  // ============== SuspiciousEqualsMethodName (pl-0137) ==============
  @Test
  void suspiciousEquals_fires() {
    String src = "class N { public boolean equals(String s) { return false; } }";
    assertThat(run(src, new SuspiciousEqualsMethodNameCheck()))
        .as("equals(String) fires")
        .isNotEmpty();
  }

  // ============== UseCollectionIsEmpty (pl-0140) ==============
  @Test
  void useCollectionIsEmpty_fires() {
    String src =
        "import java.util.List; class N {" + " boolean m(List<?> l) { return l.size() == 0; } }";
    assertThat(run(src, new UseCollectionIsEmptyCheck())).as("size() == 0 fires").isNotEmpty();
  }

  // ============== UnnecessaryParentheses (pl-0141) ==============
  @Test
  void unnecessaryParens_fires() {
    String src = "class N { int m(int x) { return (x); } }";
    assertThat(run(src, new UnnecessaryParenthesesCheck()))
        .as("(x) around a name fires")
        .isNotEmpty();
  }

  // ============== WhileLoopWithLiteralBoolean (pl-0142) ==============
  @Test
  void whileLiteralBoolean_fires() {
    String src = "class N { void m() { while (true) { } } }";
    assertThat(run(src, new WhileLoopWithLiteralBooleanCheck()))
        .as("while(true) fires")
        .isNotEmpty();
  }

  // ============== UseUtilityClass (pl-0146) ==============
  @Test
  void useUtilityClass_fires() {
    String src = "class N { public static int x = 1;" + " public static int m() { return 1; } }";
    assertThat(run(src, new UseUtilityClassCheck()))
        .as("class with only static members fires")
        .isNotEmpty();
  }

  // ============== SystemPrintln (pl-0149) ==============
  @Test
  void systemPrintln_fires() {
    String src = "class N { void m() { System.out.println(\"hi\"); } }";
    assertThat(run(src, new SystemPrintlnCheck())).as("System.out.println fires").isNotEmpty();
  }

  // ============== PreserveStackTrace (pl-0151) ==============
  @Test
  void preserveStackTrace_fires() {
    String src =
        "class N { void m() {"
            + " try { int x = 1; }"
            + " catch (Exception e) {"
            + " throw new RuntimeException(\"err\");"
            + " } } }";
    assertThat(run(src, new PreserveStackTraceCheck()))
        .as("throw without original cause fires")
        .isNotEmpty();
  }

  // ============== AvoidReassigningLoopVariables (pl-0152) ==============
  @Test
  void reassignLoopVar_fires() {
    String src = "class N { void m() {" + " for (int i = 0; i < 10; i++) { i = 5; } } }";
    assertThat(run(src, new AvoidReassigningLoopVariablesCheck()))
        .as("reassign loop var fires")
        .isNotEmpty();
  }

  // ============== ForLoopWithSizeMethod (pl-0153) ==============
  @Test
  void forSize_fires() {
    String src =
        "import java.util.List; class N {"
            + " void m(List<?> l) { for (int i = 0; i < l.size(); i++) { } } }";
    assertThat(run(src, new ForLoopWithSizeMethodCheck()))
        .as("for(i; i<l.size();) fires")
        .isNotEmpty();
  }

  // ============== CompareObjectsWithEquals (pl-0154) ==============
  @Test
  void compareObjectsWithEquals_fires() {
    String src = "class N { boolean m(Object a, Object b) { return a == b; } }";
    assertThat(run(src, new CompareObjectsWithEqualsCheck()))
        .as("== on objects fires")
        .isNotEmpty();
  }

  // ============== UseIndexOfChar (pl-0155) ==============
  @Test
  void useIndexOfChar_fires() {
    String src = "class N { int m(String s) { return s.indexOf(\"a\"); } }";
    assertThat(run(src, new UseIndexOfCharCheck())).as("s.indexOf(\"a\") fires").isNotEmpty();
  }

  // ============== UselessStringValueOf (pl-0157) ==============
  @Test
  void uselessStringValueOf_fires() {
    String src = "class N { String m(int x) { return \"\" + x; } }";
    assertThat(run(src, new UselessStringValueOfCheck())).as("\"\" + x fires").isNotEmpty();
  }

  // ============== JumbledIncrementer (pl-0159) ==============
  @Test
  void jumbledIncrementer_fires() {
    String src = "class N { void m() {" + " for (int i = 0; i < 10; i += 2) { } } }";
    assertThat(run(src, new JumbledIncrementerCheck())).as("for with i += 2 fires").isNotEmpty();
  }

  // ============== UseAssertTrueInsteadOfAssertEquals (pl-0139) ==============
  @Test
  void useAssertTrue_fires() {
    String src =
        "import static org.junit.jupiter.api.Assertions.assertEquals;"
            + "class N { void m(boolean x) { assertEquals(true, x); } }";
    assertThat(run(src, new UseAssertTrueInsteadOfAssertEqualsCheck()))
        .as("assertEquals(true, x) fires")
        .isNotEmpty();
  }
}
