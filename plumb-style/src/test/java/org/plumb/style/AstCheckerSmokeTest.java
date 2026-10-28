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
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;

class AstCheckerSmokeTest {

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("S.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    // R-834: force-enable every registered rule for smoke tests.
    for (var d : new AstCheckerRegistry().builtins().all()) {
      var rd = d.rule();
      if (rd != null) ctx.forceEnable(rd.ruleId().id());
    }
    // A1: single-pass dispatch via AstDispatcher.
    AstDispatcher d = new AstDispatcher();
    new AstCheckerRegistry().builtins().all().forEach(d::register);
    d.run(ctx);
    return ctx.findings();
  }

  @Test
  void emptyCatch() {
    String src = "class S { void m() { try { int x = 1; } catch (Exception e) {} } }";
    List<Finding> findings = run(src);
    assertThat(
            findings.stream()
                .anyMatch(f -> f.rule() != null && f.rule().ruleId().name().equals("EMPTY_BLOCK")))
        .isTrue();
  }

  @Test
  void systemOut() {
    String src = "class S { void m() { System.out.println(\"x\"); } }";
    List<Finding> findings = run(src);
    assertThat(
            findings.stream()
                .anyMatch(
                    f -> f.rule() != null && f.rule().ruleId().name().equals("SYSTEM_OUT_PRINTLN")))
        .isTrue();
  }

  @Test
  void unusedImport() {
    String src = "import java.util.List;\nimport java.util.Map;\nclass S { int x; }";
    List<Finding> findings = run(src);
    long unused =
        findings.stream()
            .filter(f -> f.rule() != null && f.rule().ruleId().name().equals("UNUSED_IMPORT"))
            .count();
    assertThat(unused).isEqualTo(2);
  }

  @Test
  void classNamingFails() {
    String src = "class my_class { }";
    List<Finding> findings = run(src);
    // R-826: ClassNamingConventionsCheck's RuleId.name
    // was disambiguated to CLASS_NAMING_CONVENTIONS to
    // resolve a three-way conflict with DataClassCheck
    // (CLASS) and FinalClassCheck (CLASS_FINAL).
    assertThat(
            findings.stream()
                .anyMatch(f -> f.rule() != null && f.rule().ruleId().name().equals("CLASS_NAMING")))
        .isTrue();
  }

  @Test
  void classNamingPasses() {
    String src = "class MyClass { void myMethod() { } }";
    List<Finding> findings = run(src);
    assertThat(
            findings.stream()
                .noneMatch(f -> f.rule() != null && f.rule().ruleId().name().equals("CLASS")))
        .isTrue();
    assertThat(
            findings.stream()
                .noneMatch(
                    f ->
                        f.rule() != null
                            && f.rule().ruleId().name().equals("METHOD_NAMING_CONVENTIONS")))
        .isTrue();
  }

  @Test
  void catchGeneric() {
    String src = "class S { void m() { try {} catch (Throwable t) {} } }";
    List<Finding> findings = run(src);
    assertThat(
            findings.stream()
                .anyMatch(
                    f ->
                        f.rule() != null && f.rule().ruleId().name().equals("STYLE_CATCH_GENERIC")))
        .isTrue();
  }

  // ----------: annotation imports not reported as unused ----------

  @Test
  void annotationImportIsNotReportedAsUnused() {
    // The classic JPA anti-pattern that the
    // smoke test caught: import jakarta.persistence.Entity
    // is used by @Entity on the class. The check
    // should NOT report it as unused.
    String src =
        "import jakarta.persistence.Entity;\n"
            + "import jakarta.persistence.Id;\n"
            + "@Entity\n"
            + "class User { @Id int id; }";
    List<Finding> findings = run(src);
    long unused =
        findings.stream()
            .filter(f -> f.rule() != null && f.rule().ruleId().name().equals("UNUSED_IMPORT"))
            .count();
    assertThat(unused)
        .as("R137: annotation imports are not 'unused' even though their only reference is @Foo")
        .isEqualTo(0);
  }

  @Test
  void multipleAnnotationImportsNotReportedAsUnused() {
    // PetClinic-shaped: BaseEntity with @MappedSuperclass + @Entity + @Id
    String src =
        "import jakarta.persistence.MappedSuperclass;\n"
            + "import jakarta.persistence.Entity;\n"
            + "import jakarta.persistence.Id;\n"
            + "import jakarta.persistence.GeneratedValue;\n"
            + "import java.io.Serializable;\n"
            + "@MappedSuperclass\n"
            + "@Entity\n"
            + "class BaseEntity implements Serializable {\n"
            + " @Id @GeneratedValue int id;\n"
            + "}";
    List<Finding> findings = run(src);
    long unused =
        findings.stream()
            .filter(f -> f.rule() != null && f.rule().ruleId().name().equals("UNUSED_IMPORT"))
            .count();
    assertThat(unused)
        .as("R137: 4 annotation imports + 1 interface (Serializable) : all used")
        .isEqualTo(0);
  }

  @Test
  void trulyUnusedImportStillReported() {
    // The check must still catch truly unused imports.
    String src = "import java.util.List;\n" + "import java.util.Map;\n" + "class S { int x; }";
    List<Finding> findings = run(src);
    long unused =
        findings.stream()
            .filter(f -> f.rule() != null && f.rule().ruleId().name().equals("UNUSED_IMPORT"))
            .count();
    assertThat(unused)
        .as("R137: regression-guard : truly unused imports must still fire")
        .isEqualTo(2);
  }

  @Test
  void springAnnotationImportNotReportedAsUnused() {
    // PetClinic-shaped: a Spring @Controller
    String src =
        "import org.springframework.stereotype.Controller;\n"
            + "import org.springframework.web.bind.annotation.GetMapping;\n"
            + "@Controller\n"
            + "class UserController { @GetMapping(\"/u\") void m() {} }";
    List<Finding> findings = run(src);
    long unused =
        findings.stream()
            .filter(f -> f.rule() != null && f.rule().ruleId().name().equals("UNUSED_IMPORT"))
            .count();
    assertThat(unused)
        .as("R137: Spring stereotype + web mapping imports are not unused")
        .isEqualTo(0);
  }
}
