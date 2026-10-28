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
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.quality.HibernateBadHashcodeCheck;

/** Coverity <code>HIBERNATE_BAD_HASHCODE</code> tests. */
class R544CoverityHibernateBadHashcodeTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new HibernateBadHashcodeCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnHashCodeUsingId() {
    String src =
        "import javax.persistence.Entity;\n"
            + "import javax.persistence.Id;\n"
            + "@Entity class User {\n"
            + "  @Id Long id;\n"
            + "  @Override public int hashCode() {"
            + "    return id.hashCode();\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("HIBERNATE_BAD_HASHCODE: hashCode() uses @Id fires").isNotEmpty();
  }

  @Test
  void firesOnEqualsUsingId() {
    String src =
        "import javax.persistence.Entity;\n"
            + "import javax.persistence.Id;\n"
            + "@Entity class User {\n"
            + "  @Id Long id;\n"
            + "  @Override public boolean equals(Object o) {"
            + "    return id.equals(((User)o).id);"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("HIBERNATE_BAD_HASHCODE: equals() uses @Id fires").isNotEmpty();
  }

  @Test
  void noFireOnBusinessKey() {
    // Use a non-id field in hashCode/equals : this is the
    // correct pattern (use a stable business key).
    String src =
        "import javax.persistence.Entity;\n"
            + "import javax.persistence.Id;\n"
            + "@Entity class User {\n"
            + "  @Id Long id;\n"
            + "  String email;\n"
            + "  @Override public int hashCode() {"
            + "    return email.hashCode();\n"
            + "  }\n"
            + "  @Override public boolean equals(Object o) {"
            + "    return email.equals(((User)o).email);"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("HIBERNATE_BAD_HASHCODE: business key is fine").isEmpty();
  }

  @Test
  void noFireOnNonEntity() {
    String src =
        "class N {\n"
            + "  Long id;\n"
            + "  public int hashCode() { return id.hashCode(); }\n"
            + "}";
    assertThat(run(src)).as("HIBERNATE_BAD_HASHCODE: non-entity class does not fire").isEmpty();
  }

  @Test
  void noFireOnLocalVariableShadow() {
    // A local variable named "id" shadows the field; the
    // checker should not fire because the reference is to
    // the local.
    String src =
        "import javax.persistence.Entity;\n"
            + "import javax.persistence.Id;\n"
            + "@Entity class User {\n"
            + "  @Id Long id;\n"
            + "  @Override public int hashCode() {\n"
            + "    Long id = 0L;\n"
            + "    return id.hashCode();\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("HIBERNATE_BAD_HASHCODE: local variable shadow is fine").isEmpty();
  }
}
