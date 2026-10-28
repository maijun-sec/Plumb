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
import org.plumb.style.error.EmptyCatchBlockCheck;
import org.plumb.style.naming.BooleanGetMethodNameCheck;
import org.plumb.style.naming.ClassTypeParameterNameCheck;
import org.plumb.style.naming.ConstantNameCheck;
import org.plumb.style.naming.InterfaceTypeParameterNameCheck;
import org.plumb.style.naming.LocalFinalVariableNameCheck;
import org.plumb.style.naming.LocalVariableNameCheck;
import org.plumb.style.naming.MemberNameCheck;
import org.plumb.style.naming.MethodNameCheck;
import org.plumb.style.style.AvoidProtectedFieldInFinalClassCheck;
import org.plumb.style.style.AvoidProtectedMethodInFinalClassCheck;
import org.plumb.style.style.EmptyLineSeparatorCheck;
import org.plumb.style.style.EmptyStatementCheck;
import org.plumb.style.style.FinalClassCheck;

/**
 * batch 3 (14 new rules, pl-0531 through pl-0550). Each rule has a fire + no-fire test. The 6 stubs
 * in this batch (AbstractCheck, ArrayTypeCheck_CS, DeclarationOrderCheck_CS, EqualsAvoidNullCheck,
 * GenericNamingCheck, JavaNCSSCheck) are placeholders for follow-up rounds.
 */
class R281CheckstyleBatch3Test {

  private final RuleRegistry rules = new RuleRegistry().withBuiltinDefaults(true);

  private List<?> run(String src, AstChecker check) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("R281.java"), src);
    StyleContext ctx = new StyleContext(ps, rules);
    new AstDispatcher().register(check).run(ctx);
    return ctx.findings();
  }

  @Test
  void avoidprotectedfieldinfinalclasscheck_cs_fires() {
    String src = "final class N { protected int x; }";
    assertThat(run(src, new AvoidProtectedFieldInFinalClassCheck()))
        .as("AvoidProtectedFieldInFinalClassCheck_CS fires on bad code")
        .isNotEmpty();
  }

  @Test
  void avoidprotectedfieldinfinalclasscheck_cs_noFire() {
    String src = "class N { protected int x; }";
    assertThat(run(src, new AvoidProtectedFieldInFinalClassCheck()))
        .as("AvoidProtectedFieldInFinalClassCheck_CS does not fire on good code")
        .isEmpty();
  }

  @Test
  void avoidprotectedmethodinfinalclasscheck_cs_fires() {
    String src = "final class N { protected void m() {} }";
    assertThat(run(src, new AvoidProtectedMethodInFinalClassCheck()))
        .as("AvoidProtectedMethodInFinalClassCheck_CS fires on bad code")
        .isNotEmpty();
  }

  @Test
  void avoidprotectedmethodinfinalclasscheck_cs_noFire() {
    String src = "class N { protected void m() {} }";
    assertThat(run(src, new AvoidProtectedMethodInFinalClassCheck()))
        .as("AvoidProtectedMethodInFinalClassCheck_CS does not fire on good code")
        .isEmpty();
  }

  @Test
  void booleangetmethodnamecheck_cs_fires() {
    String src = "class N { public boolean getReady() { return true; } }";
    assertThat(run(src, new BooleanGetMethodNameCheck()))
        .as("BooleanGetMethodNameCheck_CS fires on bad code")
        .isNotEmpty();
  }

  @Test
  void booleangetmethodnamecheck_cs_noFire() {
    String src = "class N { public boolean isReady() { return true; } }";
    assertThat(run(src, new BooleanGetMethodNameCheck()))
        .as("BooleanGetMethodNameCheck_CS does not fire on good code")
        .isEmpty();
  }

  @Test
  void classtypeparameternamecheck_cs_fires() {
    String src = "class N<myType> { }";
    assertThat(run(src, new ClassTypeParameterNameCheck()))
        .as("ClassTypeParameterNameCheck_CS fires on bad code")
        .isNotEmpty();
  }

  @Test
  void classtypeparameternamecheck_cs_noFire() {
    String src = "class N<T> { }";
    assertThat(run(src, new ClassTypeParameterNameCheck()))
        .as("ClassTypeParameterNameCheck_CS does not fire on good code")
        .isEmpty();
  }

  @Test
  void constantnamecheck_cs_fires() {
    String src = "class N { static final int max_count = 5; }";
    assertThat(run(src, new ConstantNameCheck()))
        .as("ConstantNameCheck_CS fires on bad code")
        .isNotEmpty();
  }

  @Test
  void constantnamecheck_cs_noFire() {
    String src = "class N { static final int MAX_COUNT = 5; }";
    assertThat(run(src, new ConstantNameCheck()))
        .as("ConstantNameCheck_CS does not fire on good code")
        .isEmpty();
  }

  @Test
  void emptycatchblockcheck_cs_fires() {
    String src = "class N { void m() { try {} catch (Exception e) {} } }";
    assertThat(run(src, new EmptyCatchBlockCheck()))
        .as("EmptyCatchBlockCheck_CS fires on bad code")
        .isNotEmpty();
  }

  @Test
  void emptycatchblockcheck_cs_noFire() {
    String src = "class N { void m() { try {} catch (Exception e) { System.out.println(e); } } }";
    assertThat(run(src, new EmptyCatchBlockCheck()))
        .as("EmptyCatchBlockCheck_CS does not fire on good code")
        .isEmpty();
  }

  @Test
  void emptylineseparatorcheck_cs_fires() {
    String src = "class N { void a() {} void b() {} }";
    assertThat(run(src, new EmptyLineSeparatorCheck()))
        .as("EmptyLineSeparatorCheck_CS fires on bad code")
        .isNotEmpty();
  }

  @Test
  void emptylineseparatorcheck_cs_noFire() {
    String src = "class N { void a() {}\n\n void b() {} }";
    assertThat(run(src, new EmptyLineSeparatorCheck()))
        .as("EmptyLineSeparatorCheck_CS does not fire on good code")
        .isEmpty();
  }

  @Test
  void emptystatementcheck_cs_fires() {
    String src = "class N { void m() {; } }";
    assertThat(run(src, new EmptyStatementCheck()))
        .as("EmptyStatementCheck_CS fires on bad code")
        .isNotEmpty();
  }

  @Test
  void emptystatementcheck_cs_noFire() {
    String src = "class N { void m() {} }";
    assertThat(run(src, new EmptyStatementCheck()))
        .as("EmptyStatementCheck_CS does not fire on good code")
        .isEmpty();
  }

  @Test
  void finalclasscheck_cs_fires() {
    String src = "class N { private N() {} int m() { return 1; } }";
    assertThat(run(src, new FinalClassCheck()))
        .as("FinalClassCheck_CS fires on bad code")
        .isNotEmpty();
  }

  @Test
  void finalclasscheck_cs_noFire() {
    String src = "final class N { private N() {} int m() { return 1; } }";
    assertThat(run(src, new FinalClassCheck()))
        .as("FinalClassCheck_CS does not fire on good code")
        .isEmpty();
  }

  @Test
  void interfacetypeparameternamecheck_fires() {
    String src = "interface N<myParam> { }";
    assertThat(run(src, new InterfaceTypeParameterNameCheck()))
        .as("InterfaceTypeParameterNameCheck fires on bad code")
        .isNotEmpty();
  }

  @Test
  void interfacetypeparameternamecheck_noFire() {
    String src = "interface N<T> { }";
    assertThat(run(src, new InterfaceTypeParameterNameCheck()))
        .as("InterfaceTypeParameterNameCheck does not fire on good code")
        .isEmpty();
  }

  @Test
  void localfinalvariablenamecheck_cs_fires() {
    String src = "class N { void m() { final int MyVar = 1; } }";
    assertThat(run(src, new LocalFinalVariableNameCheck()))
        .as("LocalFinalVariableNameCheck_CS fires on bad code")
        .isNotEmpty();
  }

  @Test
  void localfinalvariablenamecheck_cs_noFire() {
    String src = "class N { void m() { final int myVar = 1; } }";
    assertThat(run(src, new LocalFinalVariableNameCheck()))
        .as("LocalFinalVariableNameCheck_CS does not fire on good code")
        .isEmpty();
  }

  @Test
  void localvariablenamecheck_cs_fires() {
    String src = "class N { void m() { int MyVar = 1; } }";
    assertThat(run(src, new LocalVariableNameCheck()))
        .as("LocalVariableNameCheck_CS fires on bad code")
        .isNotEmpty();
  }

  @Test
  void localvariablenamecheck_cs_noFire() {
    String src = "class N { void m() { int myVar = 1; } }";
    assertThat(run(src, new LocalVariableNameCheck()))
        .as("LocalVariableNameCheck_CS does not fire on good code")
        .isEmpty();
  }

  @Test
  void membernamecheck_cs_fires() {
    String src = "class N { int My_Field; }";
    assertThat(run(src, new MemberNameCheck()))
        .as("MemberNameCheck_CS fires on bad code")
        .isNotEmpty();
  }

  @Test
  void membernamecheck_cs_noFire() {
    String src = "class N { int myField; }";
    assertThat(run(src, new MemberNameCheck()))
        .as("MemberNameCheck_CS does not fire on good code")
        .isEmpty();
  }

  @Test
  void methodnamecheck_cs_fires() {
    String src = "class N { void My_Method() {} }";
    assertThat(run(src, new MethodNameCheck()))
        .as("MethodNameCheck_CS fires on bad code")
        .isNotEmpty();
  }

  @Test
  void methodnamecheck_cs_noFire() {
    String src = "class N { void myMethod() {} }";
    assertThat(run(src, new MethodNameCheck()))
        .as("MethodNameCheck_CS does not fire on good code")
        .isEmpty();
  }
}
