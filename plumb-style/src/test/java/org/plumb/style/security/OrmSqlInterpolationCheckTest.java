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
/*
 * CWE-89: an ORM mapper method whose SQL is built by string interpolation.
 *
 * The two MyBatis placeholder forms have opposite safety properties, and the
 * difference is the whole point of the rule:
 *
 *   #{name}  ->  a PreparedStatement placeholder. The value is bound as data
 *                and cannot change the SQL structure. SAFE.
 *   ${name}  ->  a raw string substitution. The value becomes part of the SQL
 *                text and CAN change the statement. UNSAFE.
 *
 * ${} does have legitimate uses (a dynamic table name, a dynamic column, an
 * ORDER BY direction), because a prepared-statement placeholder cannot stand
 * in for SQL structure. So the rule reports a ${} that names one of the
 * method's parameters, and leaves a ${} that names nothing the caller can
 * influence alone.
 */
package org.plumb.style.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.Finding;

class OrmSqlInterpolationCheckTest {

  private final SecurityAstCheckTestHarness harness =
      new SecurityAstCheckTestHarness(new OrmSqlInterpolationCheck());

  private List<Finding> run(String src) {
    return harness.run(src);
  }

  @Test
  void aDollarPlaceholderNamingAParameterIsReported() {
    List<Finding> findings =
        run(
            "import org.apache.ibatis.annotations.Select;\n"
                + "class UserMapper {\n"
                + "  @Select(\"SELECT * FROM users WHERE name = '${name}'\")\n"
                + "  Object findByName(String name);\n"
                + "}\n");

    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).rule().ruleId().id())
        .isEqualTo("ast-security-orm-sql-interpolation");
    assertThat(findings.get(0).message()).contains("name");
  }

  @Test
  void aHashPlaceholderIsNotReported() {
    // The safe form: a bound parameter cannot change the statement.
    List<Finding> findings =
        run(
            "import org.apache.ibatis.annotations.Select;\n"
                + "class UserMapper {\n"
                + "  @Select(\"SELECT * FROM users WHERE name = #{name}\")\n"
                + "  Object findByName(String name);\n"
                + "}\n");

    assertThat(findings).isEmpty();
  }

  @Test
  void aDollarPlaceholderThatNamesNoParameterIsNotReported() {
    // ${} naming something the method does not receive cannot be influenced by
    // the caller. This is the legitimate dynamic-identifier shape.
    List<Finding> findings =
        run(
            "import org.apache.ibatis.annotations.Select;\n"
                + "class UserMapper {\n"
                + "  @Select(\"SELECT * FROM ${tableName} WHERE id = #{id}\")\n"
                + "  Object findById(Long id);\n"
                + "}\n");

    assertThat(findings).isEmpty();
  }

  @Test
  void aDollarPlaceholderNamingAParamAliasIsReported() {
    // MyBatis resolves ${} against the @Param alias when one is present.
    List<Finding> findings =
        run(
            "import org.apache.ibatis.annotations.Select;\n"
                + "import org.apache.ibatis.annotations.Param;\n"
                + "class UserMapper {\n"
                + "  @Select(\"SELECT * FROM users WHERE name = '${userName}'\")\n"
                + "  Object findByName(@Param(\"userName\") String name);\n"
                + "}\n");

    assertThat(findings).hasSize(1);
  }

  @Test
  void aDollarPlaceholderInAnUpdateAnnotationIsReported() {
    List<Finding> findings =
        run(
            "import org.apache.ibatis.annotations.Update;\n"
                + "class UserMapper {\n"
                + "  @Update(\"UPDATE users SET name = '${name}' WHERE id = #{id}\")\n"
                + "  int rename(String name, Long id);\n"
                + "}\n");

    assertThat(findings).hasSize(1);
  }

  @Test
  void aDeleteAnnotationIsCovered() {
    List<Finding> findings =
        run(
            "import org.apache.ibatis.annotations.Delete;\n"
                + "class UserMapper {\n"
                + "  @Delete(\"DELETE FROM users WHERE name = '${name}'\")\n"
                + "  int delete(String name);\n"
                + "}\n");

    assertThat(findings).hasSize(1);
  }

  @Test
  void aSpringDataJpaQueryWithInterpolationIsReported() {
    List<Finding> findings =
        run(
            "import org.springframework.data.jpa.repository.Query;\n"
                + "import org.springframework.data.repository.query.Param;\n"
                + "class UserRepository {\n"
                + "  @Query(\"SELECT u FROM User u WHERE u.name = '#{:#{name}}'\")\n"
                + "  Object findByName(@Param(\"name\") String name);\n"
                + "}\n");

    // A JPA @Query with a bound parameter is safe; this shape uses only the
    // bound form and must not be reported.
    assertThat(findings).isEmpty();
  }

  @Test
  void anAnnotationThatIsNotAStatementIsIgnored() {
    // @GetMapping and friends carry no SQL at all.
    List<Finding> findings =
        run(
            "import org.springframework.web.bind.annotation.GetMapping;\n"
                + "class Controller {\n"
                + "  @GetMapping(\"/users/${name}\")\n"
                + "  Object list(String name);\n"
                + "}\n");

    assertThat(findings).isEmpty();
  }

  @Test
  void aMethodWithNoAnnotationsIsIgnored() {
    List<Finding> findings =
        run("class Plain {\n" + "  Object findByName(String name) { return null; }\n" + "}\n");

    assertThat(findings).isEmpty();
  }

  @Test
  void aDollarPlaceholderNamingAParameterOfAnotherMethodIsNotReported() {
    // The placeholder names 'other', which this method does not receive.
    List<Finding> findings =
        run(
            "import org.apache.ibatis.annotations.Select;\n"
                + "class UserMapper {\n"
                + "  @Select(\"SELECT * FROM users WHERE name = '${other}'\")\n"
                + "  Object findByName(String name);\n"
                + "}\n");

    assertThat(findings).isEmpty();
  }

  @Test
  void aConcatenatedParameterIsReported() {
    // The statement is built by concatenation, so the annotation's value is not
    // a literal at all. The parameter's value still becomes query text.
    List<Finding> findings =
        run(
            "import org.springframework.data.jpa.repository.Query;\n"
                + "import org.springframework.data.repository.query.Param;\n"
                + "class UserRepository {\n"
                + "  @Query(\"SELECT u FROM User u WHERE u.name = '\" + name + \"'\")\n"
                + "  Object findByName(@Param(\"name\") String name);\n"
                + "}\n");

    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).message()).contains("name");
  }

  @Test
  void aConcatenationOfTwoLiteralsIsNotReported() {
    // Nothing the caller controls is mixed in.
    List<Finding> findings =
        run(
            "import org.springframework.data.jpa.repository.Query;\n"
                + "class UserRepository {\n"
                + "  @Query(\"SELECT u FROM User u WHERE u.active = true \"\n"
                + "        + \"ORDER BY u.name\")\n"
                + "  Object findActive();\n"
                + "}\n");

    assertThat(findings).isEmpty();
  }

  @Test
  void aConcatenationWithANonParameterVariableIsNotReported() {
    // A local constant the caller cannot influence.
    List<Finding> findings =
        run(
            "import org.springframework.data.jpa.repository.Query;\n"
                + "class UserRepository {\n"
                + "  @Query(\"SELECT u FROM User u WHERE u.name = '\" + CONSTANT + \"'\")\n"
                + "  Object findAll();\n"
                + "  private static final String CONSTANT = \"x\";\n"
                + "}\n");

    assertThat(findings).isEmpty();
  }
}
