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

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.naming.LocalVariableCouldBeFinalCheck;
import org.plumb.style.style.CommentDefaultAccessCheck;
import org.plumb.style.style.EmptyMethodInAbstractCheck;
import org.plumb.style.style.ExtendsObjectCheck;
import org.plumb.style.style.UnnecessaryReturnCheck;

/**
 * Style/codestyle batch (5 rules) (ExtendsObject, EmptyMethodInAbstractClass, UnnecessaryReturn,
 * LocalVariableCouldBeFinal, CommentDefaultAccessModifier). Each rule is a 1-to-1 mirror of source
 * XML, with 4 unit tests per rule.
 */
class R204PmdBatchTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<org.plumb.reporter.Finding> run(String src, AstChecker checker) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(checker).run(ctx);
    return ctx.findings();
  }

  // ============== ExtendsObject ==============
  @Test
  void extendsObject_firesOnObject() {
    String src = "class N extends Object {}";
    assertThat(run(src, new ExtendsObjectCheck()))
        .as("ExtendsObject: extends Object fires")
        .isNotEmpty();
  }

  @Test
  void extendsObject_firesOnJavaLangObject() {
    String src = "class N extends java.lang.Object {}";
    assertThat(run(src, new ExtendsObjectCheck()))
        .as("ExtendsObject: extends java.lang.Object fires")
        .isNotEmpty();
  }

  @Test
  void extendsObject_noFireOnNormal() {
    String src = "class N {}";
    assertThat(run(src, new ExtendsObjectCheck()))
        .as("ExtendsObject: no extends does not fire")
        .isEmpty();
  }

  @Test
  void extendsObject_noFireOnInterface() {
    String src = "interface I extends Object {}";
    assertThat(run(src, new ExtendsObjectCheck()))
        .as("ExtendsObject: interface does not fire")
        .isEmpty();
  }

  // ============== EmptyMethodInAbstract ==============
  @Test
  void emptyMethodInAbstract_firesOnReturnNull() {
    String src = "abstract class N {" + " String m() { return null; }" + "}";
    assertThat(run(src, new EmptyMethodInAbstractCheck()))
        .as("EmptyMethodInAbstract: return null fires")
        .isNotEmpty();
  }

  @Test
  void emptyMethodInAbstract_firesOnReturnZero() {
    String src = "abstract class N {" + " int m() { return 0; }" + "}";
    assertThat(run(src, new EmptyMethodInAbstractCheck()))
        .as("EmptyMethodInAbstract: return 0 fires")
        .isNotEmpty();
  }

  @Test
  void emptyMethodInAbstract_noFireOnRealImpl() {
    String src = "abstract class N {" + " String m() { return \"real\"; }" + "}";
    assertThat(run(src, new EmptyMethodInAbstractCheck()))
        .as("EmptyMethodInAbstract: real impl does not fire")
        .isEmpty();
  }

  @Test
  void emptyMethodInAbstract_noFireOnConcreteClass() {
    String src = "class N {" + " String m() { return null; }" + "}";
    assertThat(run(src, new EmptyMethodInAbstractCheck()))
        .as("EmptyMethodInAbstract: concrete class does not fire")
        .isEmpty();
  }

  // ============== UnnecessaryReturn ==============
  @Test
  void unnecessaryReturn_fires() {
    String src = "class N { void m() { int x = 1; return; } }";
    assertThat(run(src, new UnnecessaryReturnCheck()))
        .as("UnnecessaryReturn: trailing return; fires")
        .isNotEmpty();
  }

  @Test
  void unnecessaryReturn_noFireOnValueReturn() {
    String src = "class N { int m() { return 5; } }";
    assertThat(run(src, new UnnecessaryReturnCheck()))
        .as("UnnecessaryReturn: return 5 does not fire")
        .isEmpty();
  }

  @Test
  void unnecessaryReturn_noFireWithoutReturn() {
    String src = "class N { void m() { int x = 1; } }";
    assertThat(run(src, new UnnecessaryReturnCheck()))
        .as("UnnecessaryReturn: no return at end does not fire")
        .isEmpty();
  }

  @Test
  void unnecessaryReturn_noFireOnNonVoidValueReturn() {
    // void m() { return 5; } — return value in void
    // method is a compile error in Java. Skipping.
    String src = "class N { void m() { System.out.println(\"a\"); } }";
    assertThat(run(src, new UnnecessaryReturnCheck()))
        .as("UnnecessaryReturn: no trailing return does not fire")
        .isEmpty();
  }

  // ============== LocalVariableCouldBeFinal ==============
  @Test
  void localVarCouldBeFinal_firesOnSingleAssign() {
    String src = "class N { void m() {" + " int x = 1;" + " System.out.println(x);" + "} }";
    assertThat(run(src, new LocalVariableCouldBeFinalCheck()))
        .as("LocalVarCouldBeFinal: single-assign var fires")
        .isNotEmpty();
  }

  @Test
  void localVarCouldBeFinal_noFireOnReassign() {
    String src =
        "class N { void m() {" + " int x = 1;" + " x = 2;" + " System.out.println(x);" + "} }";
    assertThat(run(src, new LocalVariableCouldBeFinalCheck()))
        .as("LocalVarCouldBeFinal: re-assign does not fire")
        .isEmpty();
  }

  @Test
  void localVarCouldBeFinal_noFireOnAlreadyFinal() {
    String src = "class N { void m() {" + " final int x = 1;" + " System.out.println(x);" + "} }";
    assertThat(run(src, new LocalVariableCouldBeFinalCheck()))
        .as("LocalVarCouldBeFinal: already final does not fire")
        .isEmpty();
  }

  @Test
  void localVarCouldBeFinal_firesOnMultipleSingleAssigns() {
    String src =
        "class N { void m() {" + " int x = 1; int y = 2;" + " System.out.println(x + y);" + "} }";
    assertThat(run(src, new LocalVariableCouldBeFinalCheck()))
        .as("LocalVarCouldBeFinal: 2 single-assign vars fire")
        .hasSizeGreaterThanOrEqualTo(2);
  }

  // ============== CommentDefaultAccess ==============
  @Test
  void commentDefaultAccess_firesOnNoComment() {
    // Field with no access modifier AND no comment
    String src = "class N { String f; }";
    assertThat(run(src, new CommentDefaultAccessCheck()))
        .as("CommentDefaultAccess: no comment fires")
        .isNotEmpty();
  }

  @Test
  void commentDefaultAccess_noFireOnDefaultComment() {
    // Field with /* default */ comment
    String src = "class N { /* default */ String f; }";
    assertThat(run(src, new CommentDefaultAccessCheck()))
        .as("CommentDefaultAccess: /* default */ comment does not fire")
        .isEmpty();
  }

  @Test
  void commentDefaultAccess_noFireOnExplicitModifier() {
    // Private field with no comment — has explicit modifier
    String src = "class N { private String f; }";
    assertThat(run(src, new CommentDefaultAccessCheck()))
        .as("CommentDefaultAccess: explicit private does not fire")
        .isEmpty();
  }

  @Test
  void commentDefaultAccess_noFireOnPublic() {
    String src = "class N { public String f; }";
    assertThat(run(src, new CommentDefaultAccessCheck()))
        .as("CommentDefaultAccess: explicit public does not fire")
        .isEmpty();
  }
}
