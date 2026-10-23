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
import org.plumb.style.quality.OrmUnnecessaryGetCheck;

/** Coverity <code>ORM_UNNECESSARY_GET</code> tests. */
class R542CoverityOrmUnnecessaryGetTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new OrmUnnecessaryGetCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnGetUsedOnlyAsArgument() {
    // Canonical Coverity example: result is passed to a
    // setter; the setter's body is opaque to us, but the
    // conservative AST pattern fires anyway.
    String src =
        "class N {\n"
            + "  void m(org.hibernate.Session s, java.io.Serializable id) {\n"
            + "    Object x = s.get(Object.class, id);\n"
            + "    setScore(x);\n"
            + "  }\n"
            + "  void setScore(Object s) {}\n"
            + "}";
    assertThat(run(src)).as("ORM_UNNECESSARY_GET: get() + pass-as-arg fires").isNotEmpty();
  }

  @Test
  void firesOnGetUsedOnlyForId() {
    String src =
        "class N {\n"
            + "  void m(javax.persistence.EntityManager em, Long id) {\n"
            + "    Object x = em.find(Object.class, id);\n"
            + "    Long xid = x.getId();\n"
            + "    setScore(xid);\n"
            + "  }\n"
            + "  void setScore(Long id) {}\n"
            + "}";
    assertThat(run(src)).as("ORM_UNNECESSARY_GET: getId() use fires").isNotEmpty();
  }

  @Test
  void noFireWhenGetUsedForContents() {
    // x.getName() accesses the entity's contents : the
    // get() is required.
    String src =
        "class N {\n"
            + "  void m(org.hibernate.Session s, java.io.Serializable id) {\n"
            + "    Object x = s.get(Object.class, id);\n"
            + "    String n = x.getName();\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("ORM_UNNECESSARY_GET: get() + content access does not fire").isEmpty();
  }

  @Test
  void noFireWhenGetAssignedToField() {
    // x.f = ... assigns to x's field, which is a contents
    // access.
    String src =
        "class N {\n"
            + "  Object other;\n"
            + "  void m(org.hibernate.Session s, java.io.Serializable id) {\n"
            + "    Object x = s.get(Object.class, id);\n"
            + "    other.id = x.id;\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("ORM_UNNECESSARY_GET: get() + field read does not fire").isEmpty();
  }

  @Test
  void noFireWhenVariableNeverUsed() {
    // Variable unused : that's a different finding.
    String src =
        "class N {\n"
            + "  void m(org.hibernate.Session s, java.io.Serializable id) {\n"
            + "    Object x = s.get(Object.class, id);\n"
            + "  }\n"
            + "}";
    assertThat(run(src))
        .as("ORM_UNNECESSARY_GET: unused variable does not fire (different finding)")
        .isEmpty();
  }
}
