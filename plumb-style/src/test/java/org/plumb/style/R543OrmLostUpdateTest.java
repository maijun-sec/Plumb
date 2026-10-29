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
import org.plumb.style.quality.OrmLostUpdateCheck;

/** the ORM_LOST_UPDATE defect class tests. */
class R543OrmLostUpdateTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new OrmLostUpdateCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnTransientEntityMutatedButNotPersisted() {
    String src =
        "import javax.persistence.Entity;\n"
            + "import javax.persistence.EntityManager;\n"
            + "@Entity class User { String name; }\n"
            + "class N {\n"
            + "  void m(EntityManager em) {\n"
            + "    User u = new User();\n"
            + "    u.name = \"x\";\n"
            + "  }\n"
            + "}";
    assertThat(run(src))
        .as("ORM_LOST_UPDATE: transient entity mutated, no persist call")
        .isNotEmpty();
  }

  @Test
  void noFireWhenPersisted() {
    String src =
        "import javax.persistence.Entity;\n"
            + "import javax.persistence.EntityManager;\n"
            + "@Entity class User { String name; }\n"
            + "class N {\n"
            + "  void m(EntityManager em) {\n"
            + "    User u = new User();\n"
            + "    u.name = \"x\";\n"
            + "    em.persist(u);\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("ORM_LOST_UPDATE: em.persist() suppresses the finding").isEmpty();
  }

  @Test
  void noFireOnReadOnlyUse() {
    // Entity is created and read but never mutated : the
    // the rule's heuristic says "any reading of the contents
    // is enough to consider it a temporary variable".
    String src =
        "import javax.persistence.Entity;\n"
            + "import javax.persistence.EntityManager;\n"
            + "@Entity class User { String name; }\n"
            + "class N {\n"
            + "  void m(EntityManager em) {\n"
            + "    User u = new User();\n"
            + "    String n = u.name;\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("ORM_LOST_UPDATE: read-only use is fine").isEmpty();
  }

  @Test
  void noFireOnNonEntityClass() {
    String src =
        "import javax.persistence.EntityManager;\n"
            + "class N {\n"
            + "  void m(EntityManager em) {\n"
            + "    String u = new String(\"x\");\n"
            + "    u = \"y\";\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("ORM_LOST_UPDATE: non-entity class does not fire").isEmpty();
  }

  @Test
  void firesOnMergeForDetachedEntity() {
    // merge() also counts as a persist method.
    String src =
        "import javax.persistence.Entity;\n"
            + "import javax.persistence.EntityManager;\n"
            + "@Entity class User { String name; }\n"
            + "class N {\n"
            + "  void m(EntityManager em) {\n"
            + "    User u = new User();\n"
            + "    u.name = \"x\";\n"
            + "    em.merge(u);\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("ORM_LOST_UPDATE: em.merge() suppresses the finding").isEmpty();
  }

  @Test
  void firesWhenEntityDeclaredAfterConsumer() {
    // R784-OrmOrder-2026-10-18 regression test. Previously, the @Entity class was
    // collected in visit(ClassOrInterfaceDeclaration), which only fires after the
    // pre-order DFS visits every prior sibling — so a consumer class declared
    // BEFORE the @Entity class would silently miss this finding. The fix pre-
    // collects entity class names via findAll(ClassOrInterfaceDeclaration.class)
    // in init() so the order does not matter.
    String src =
        "import javax.persistence.Entity;\n"
            + "import javax.persistence.EntityManager;\n"
            + "class N {\n"
            + "  void m(EntityManager em) {\n"
            + "    User u = new User();\n"
            + "    u.name = \"x\";\n"
            + "  }\n"
            + "}\n"
            + "@Entity class User { String name; }";
    assertThat(run(src))
        .as("ORM_LOST_UPDATE: @Entity declared AFTER consumer must still be detected")
        .isNotEmpty();
  }
}
