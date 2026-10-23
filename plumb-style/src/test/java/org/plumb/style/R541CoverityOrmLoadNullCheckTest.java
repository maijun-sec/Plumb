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
import org.plumb.style.quality.OrmLoadNullCheckCheck;

/** Coverity <code>ORM_LOAD_NULL_CHECK</code> tests. */
class R541CoverityOrmLoadNullCheckTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new OrmLoadNullCheckCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnLoadThenNullCheck() {
    String src =
        "class N {\n"
            + "  void m(org.hibernate.Session s, Long id) {\n"
            + "    Object o = s.load(id);\n"
            + "    if (o == null) { System.out.println(\"x\"); }\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("ORM_LOAD_NULL_CHECK: Session.load + null check fires").isNotEmpty();
  }

  @Test
  void firesOnGetReferenceThenNullCheck() {
    String src =
        "class N {\n"
            + "  void m(javax.persistence.EntityManager em, Long id) {\n"
            + "    Object o = em.getReference(Object.class, id);\n"
            + "    if (o != null) { System.out.println(\"x\"); }\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("ORM_LOAD_NULL_CHECK: getReference + null check fires").isNotEmpty();
  }

  @Test
  void noFireOnGetThenNullCheck() {
    // Session.get (or EntityManager.find) CAN return null,
    // so a null check is legitimate.
    String src =
        "class N {\n"
            + "  void m(org.hibernate.Session s, Long id) {\n"
            + "    Object o = s.get(id);\n"
            + "    if (o == null) { System.out.println(\"x\"); }\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("ORM_LOAD_NULL_CHECK: get() + null check does not fire").isEmpty();
  }

  @Test
  void noFireWhenNoNullCheckFollows() {
    String src =
        "class N {\n"
            + "  void m(org.hibernate.Session s, Long id) {\n"
            + "    Object o = s.load(id);\n"
            + "    o.toString();\n"
            + "  }\n"
            + "}";
    assertThat(run(src))
        .as("ORM_LOAD_NULL_CHECK: load() with no null check does not fire")
        .isEmpty();
  }

  @Test
  void noFireWhenVariableIsReassignedBeforeCheck() {
    // Coverity's "only result of a load operation" clause:
    // if the variable is re-assigned between the load and
    // the check, the value being tested might not be the
    // load result.
    String src =
        "class N {\n"
            + "  void m(org.hibernate.Session s, Long id) {\n"
            + "    Object o = s.load(id);\n"
            + "    o = null;\n"
            + "    if (o == null) { System.out.println(\"x\"); }\n"
            + "  }\n"
            + "}";
    assertThat(run(src)).as("ORM_LOAD_NULL_CHECK: reassigned variable does not fire").isEmpty();
  }
}
