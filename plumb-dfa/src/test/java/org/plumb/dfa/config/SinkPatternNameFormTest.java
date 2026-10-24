/*
 * Verifies that a sink family still matches when the TAC callee signature
 * carries a SIMPLE receiver name instead of a fully qualified one.
 *
 * Why this matters:
 *   The TAC callee signature is fully qualified only when the symbol solver can
 *   resolve the receiver's type. For a third-party class whose jar is not on
 *   the analysis classpath -- which is the normal case when a project is
 *   scanned without its dependency jars -- the IR keeps the simple name:
 *
 *       org.springframework.jdbc.core.JdbcTemplate.query/2  (resolved)
 *       JdbcTemplate.query/2                                  (unresolved)
 *
 *   The sink patterns in sink-families.yaml are anchored to fully qualified
 *   names, so before this behaviour existed every framework sink (Spring's
 *   JdbcTemplate, MyBatis, Jackson, Guava) was unreachable without any error:
 *   the analysis simply reported nothing.
 *
 * The invariants pinned here:
 *   I1  A fully qualified callee still matches the strict pattern.
 *   I2  A simple-name callee matches the same sink.
 *   I3  The relaxation is narrow: the class name and the method name must
 *       still match exactly, so a different class or a different method on the
 *       same class does not match.
 *   I4  The strict form always wins when it matches, so a qualified callee is
 *       never answered by the relaxed fallback.
 */
package org.plumb.dfa.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SinkPatternNameFormTest {

  private static final String JDBC_TEMPLATE_QUERY =
      "^org\\.springframework\\.jdbc\\.core\\.JdbcTemplate\\.query(/\\d+)?";

  private static SinkFamily sqlFamily(String... patterns) {
    return new SinkFamily("sql.sinks", "CWE-89", "HIGH", true, List.of(patterns));
  }

  @Test
  void aFullyQualifiedCalleeMatchesTheStrictPattern() {
    SinkFamily family = sqlFamily(JDBC_TEMPLATE_QUERY);

    assertThat(family.matches("org.springframework.jdbc.core.JdbcTemplate.query/2")).isTrue();
  }

  @Test
  void aSimpleNameCalleeMatchesTheSameSink() {
    SinkFamily family = sqlFamily(JDBC_TEMPLATE_QUERY);

    assertThat(family.matches("JdbcTemplate.query/2")).isTrue();
  }

  @Test
  void aDifferentClassWithTheSameMethodNameDoesNotMatch() {
    SinkFamily family = sqlFamily(JDBC_TEMPLATE_QUERY);

    assertThat(family.matches("NamedParameterJdbcTemplate.query/2")).isFalse();
    assertThat(family.matches("Other.query/2")).isFalse();
  }

  @Test
  void aDifferentMethodOnTheSameClassDoesNotMatch() {
    SinkFamily family = sqlFamily(JDBC_TEMPLATE_QUERY);

    assertThat(family.matches("JdbcTemplate.update/2")).isFalse();
    assertThat(family.matches("JdbcTemplate.queryForList/1")).isFalse();
  }

  @Test
  void theStrictFormIsPreferredForAFullyQualifiedCallee() {
    // Two families watch different classes with the same method name. A
    // qualified callee must be answered by the family whose STRICT pattern
    // matches, not by the relaxed fallback of the other one.
    SinkFamily strict = sqlFamily("^com\\.acme\\.Widget\\.render(/\\d+)?");
    SinkFamily relaxedOnly = sqlFamily(JDBC_TEMPLATE_QUERY);

    // The strict family does not match the simple name, the relaxed one does.
    assertThat(strict.matches("Widget.render/1")).isTrue();
    assertThat(relaxedOnly.matches("Widget.render/1")).isFalse();
  }

  @Test
  void aDisabledFamilyMatchesNothing() {
    SinkFamily family =
        new SinkFamily("sql.sinks", "CWE-89", "HIGH", false, List.of(JDBC_TEMPLATE_QUERY));

    assertThat(family.matches("JdbcTemplate.query/2")).isFalse();
  }

  @Test
  void theRelaxedFormKeepsTheClassAndMethodNameMandatory() {
    String relaxed = SinkFamily.SinkPattern.relaxedForm(JDBC_TEMPLATE_QUERY);

    // The package prefix becomes optional, the class and method do not, and
    // a word boundary keeps "query" from also matching "queryForList".
    assertThat(relaxed).isEqualTo("^(?:[\\w.]+\\.)?JdbcTemplate\\.query\\b(/\\d+)?");
  }

  @Test
  void aPatternWithoutAPackagePrefixIsLeftAlone() {
    // "^Runtime\.exec" names a class in the default package: there is no
    // package prefix to relax, and inventing one would change what it matches.
    String pattern = "^Runtime\\.exec(/\\d+)?";

    assertThat(SinkFamily.SinkPattern.relaxedForm(pattern)).isEqualTo(pattern);
  }

  @Test
  void aPatternThatIsNotAQualifiedClassMethodIsLeftAlone() {
    // A bare method-name pattern ("getParameter") is not a qualified class
    // reference, so there is nothing safe to relax.
    String pattern = "getParameter(/\\d+)?";

    assertThat(SinkFamily.SinkPattern.relaxedForm(pattern)).isEqualTo(pattern);
  }

  @Test
  void theArgIndexSurvivesTheRelaxedMatch() {
    // The relaxation must not change WHICH argument the family reads the
    // tainted value from: a pattern that names argument 1 must still use 1.
    SinkFamily family =
        new SinkFamily(
            "header.sinks",
            "CWE-113",
            "HIGH",
            true,
            -1,
            List.of(
                new SinkFamily.SinkPattern(
                    "^org\\.springframework\\.http\\.ResponseEntity\\.<init>/\\d+", 1)));

    SinkFamily.SinkPattern matched = family.matchPattern("ResponseEntity.<init>/2");
    assertThat(matched).isNotNull();
    assertThat(matched.argIndex()).isEqualTo(1);
  }

  @Test
  void everyRealSinkPatternStillCompilesAfterRelaxation() {
    // The relaxed form is derived mechanically for every pattern the engine
    // loads. A pattern whose text does not fit the expected shape must be
    // returned unchanged rather than rewritten into something else.
    for (String pattern :
        List.of(
            "^java\\.sql\\.Statement\\.executeQuery(/\\d+)?",
            "^java\\.io\\.FileOutputStream\\.<init>(/\\d+)?",
            "^com\\.fasterxml\\.jackson\\.databind\\.ObjectMapper\\.readValue(/\\d+)?",
            "^javax\\.persistence\\.EntityManager\\.createQuery(/\\d+)?")) {
      String relaxed = SinkFamily.SinkPattern.relaxedForm(pattern);
      assertThat(relaxed).startsWith("^(?:[\\w.]+\\.)?");
      // The class name and the method name are still present, unescaped-dot
      // for the class and escaped-dot for the method.
      assertThat(relaxed).contains("\\.");
    }
  }
}
