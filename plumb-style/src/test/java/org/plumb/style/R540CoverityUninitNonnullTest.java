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
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.quality.UninitNonnullCheck;

/**
 * Coverity <code>UNINIT_NONNULL</code> tests.
 *
 * <p>Cases covered:
 *
 * <ul>
 *   <li>{@code @NonNull} field not assigned in ctor →fires.
 *   <li>{@code @NonNull} field assigned in ctor →does not fire.
 *   <li>No {@code @NonNull} annotation →does not fire.
 *   <li>Field initialised inline ({@code @NonNull String x = "y";}) →does not fire.
 *   <li>Two ctors, only one assigns the field →fires on the unassigned ctor.
 *   <li>{@code @NonNull} field assigned {@code null} →fires (null literal is the "not non-null"
 *       case).
 * </ul>
 */
class R540CoverityUninitNonnullTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new UninitNonnullCheck()).run(ctx);
    return ctx.findings();
  }

  private ClassOrInterfaceDeclaration theClass(CompilationUnit cu) {
    return cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
  }

  @Test
  void firesWhenNonNullFieldNotAssignedInCtor() {
    String src =
        "import javax.annotation.Nonnull;\n"
            + "class N {\n"
            + "  @Nonnull String name;\n"
            + "  N() { /* forgot to assign name */ }\n"
            + "}";
    assertThat(run(src)).as("UNINIT_NONNULL: @Nonnull field not assigned in ctor").isNotEmpty();
  }

  @Test
  void noFireWhenFieldAssignedInCtor() {
    String src =
        "class N {\n" + "  @NonNull String name;\n" + "  N() { this.name = \"x\"; }\n" + "}";
    assertThat(run(src)).as("UNINIT_NONNULL: field assigned in ctor does not fire").isEmpty();
  }

  @Test
  void noFireWhenFieldIsInitiallyAssigned() {
    // Field initialiser is enough : the JLS still
    // considers the field "definitely assigned" before the
    // ctor body runs.
    String src = "class N {\n" + "  @NonNull String name = \"default\";\n" + "  N() {}\n" + "}";
    assertThat(run(src)).as("UNINIT_NONNULL: inline-initialised field is fine").isEmpty();
  }

  @Test
  void noFireWhenNoNonNullAnnotation() {
    String src =
        "class N {\n" + "  String name;\n" + "  N() { /* name is not @NonNull */ }\n" + "}";
    assertThat(run(src)).as("UNINIT_NONNULL: no @NonNull means no fire").isEmpty();
  }

  @Test
  void firesOnUnassignedCtorInMultiCtorClass() {
    String src =
        "class N {\n"
            + "  @NonNull String name;\n"
            + "  N() { this.name = \"x\"; }\n"
            + "  N(int i) { /* forgot to assign name */ }\n"
            + "}";
    // Should fire on N(int) but not on N().
    assertThat(run(src)).as("UNINIT_NONNULL: second ctor unassigned fires").hasSize(1);
  }

  @Test
  void firesWhenAssignedNullLiteral() {
    // Assigning null to a @NonNull field is exactly the
    // thing the checker is supposed to catch.
    String src =
        "class N {\n" + "  @NonNull String name;\n" + "  N() { this.name = null; }\n" + "}";
    assertThat(run(src)).as("UNINIT_NONNULL: assigning null does not count").isNotEmpty();
  }

  @Test
  void uninitFieldsUtilityReportsTheOffendingFields() {
    String src =
        "class N {\n"
            + "  @NonNull String a;\n"
            + "  @NonNull String b;\n"
            + "  N() { this.a = \"x\"; /* missing b */ }\n"
            + "}";
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    assertThat(UninitNonnullCheck.uninitFields(theClass(ps.unit()))).containsExactly("b");
  }
}
