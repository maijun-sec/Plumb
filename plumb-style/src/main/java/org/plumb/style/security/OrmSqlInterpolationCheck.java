package org.plumb.style.security;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-89: an ORM mapper method whose SQL is built by string interpolation.
 *
 * <h2>Why this is not a taint rule</h2>
 *
 * <p>The dangerous MyBatis / JPA shape is:
 *
 * <pre>
 *   &#64;Select("SELECT * FROM users WHERE name = '${name}'")
 *   User findByName(String name);
 * </pre>
 *
 * <p>The tainted value is spliced into the ANNOTATION's string constant. No call ever carries it,
 * so there is no data-flow edge for a taint engine to follow -- the value never becomes a call
 * argument. The TAC does not even carry annotations: the mapper method lowers to a bare {@code
 * RETURN}. Reaching this shape therefore needs the annotation itself, which only the AST has.
 *
 * <h2>What the rule knows about the frameworks</h2>
 *
 * <p>The two MyBatis placeholder forms have opposite safety properties, and that difference is the
 * whole point of the rule:
 *
 * <ul>
 *   <li>{@code #{name}} becomes a {@code PreparedStatement} placeholder ({@code ?}). The value is
 *       bound as data and cannot change the SQL structure. This is the safe form.
 *   <li>{@code ${name}} is a raw string substitution. The value becomes part of the SQL text and
 *       can change the statement. This is the dangerous form.
 * </ul>
 *
 * <p>{@code ${}} does have legitimate uses -- a dynamic table name, a dynamic column, an {@code
 * ORDER BY} direction -- because a prepared-statement placeholder cannot stand in for SQL
 * structure. So the rule does not report every {@code ${}}; it reports a {@code ${}} whose
 * placeholder names a method parameter. A {@code ${}} that names nothing the method receives cannot
 * be influenced by the caller and is left alone.
 *
 * <p>The same distinction applies to the JPA / Hibernate annotation forms that take raw JPQL or
 * HQL: {@code @Query("... " + value)} and {@code @Query("... ${value}")} are reported, and a query
 * whose text carries no interpolation is not.
 *
 * <h2>What is reported</h2>
 *
 * <ul>
 *   <li>A mapper SQL annotation whose text contains a {@code ${...}} naming one of the method's
 *       parameters.
 *   <li>A query annotation whose text is built by concatenating a method parameter into the query
 *       string.
 * </ul>
 *
 * <p>Both are reported at the annotation, because that is where the SQL text lives.
 */
public final class OrmSqlInterpolationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-orm-sql-interpolation", "ORM_SQL_INTERPOLATION"),
          "ORM mapper builds SQL by string interpolation -- use a bound parameter instead (CWE-89)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.SECURITY,
          List.of("CWE-89"),
          "org.plumb.style.security.OrmSqlInterpolationCheck",
          true,
          List.of("ast-security-orm-sql-interpolation", "orm-sql-interpolation"));

  /**
   * The MyBatis raw-substitution placeholder: {@code ${name}}.
   *
   * <p>Note the escaped form matters here: in the SQL text, {@code ${}} is a dollar sign followed
   * by braces. A regex for it is {@code \$\{([^}]*)\}}.
   */
  private static final Pattern DOLLAR_INTERPOLATION = Pattern.compile("\\$\\{([^}]*)\\}");

  /**
   * The MyBatis / JPA bound-parameter placeholder: {@code #{name}}.
   *
   * <p>Used only to tell the two apart, so it must be matched BEFORE the {@code ${}} form is
   * considered: the text {@code #{name}} contains a hash, not a dollar, so the two patterns cannot
   * overlap -- but a bound placeholder must never be reported as an interpolation.
   */
  private static final Pattern HASH_PLACEHOLDER = Pattern.compile("#\\{[^}]*\\}");

  /**
   * Annotation simple names that carry a SQL / JPQL / HQL statement.
   *
   * <p>MyBatis: {@code @Select}, {@code @Update}, {@code @Delete}, {@code @Insert},
   * {@code @SelectProvider}, {@code @UpdateProvider}, {@code @DeleteProvider},
   * {@code @InsertProvider}.
   *
   * <p>Spring Data JPA: {@code @Query}. Hibernate also has {@code @NamedNativeQuery} and
   * {@code @Formula}, but those take SQL that is fixed at build time -- a native query's SQL cannot
   * reference a method parameter, because the query is bound to the entity rather than to the
   * method. They are therefore not listed.
   */
  private static final List<String> SQL_ANNOTATIONS =
      List.of(
          "Select",
          "Update",
          "Delete",
          "Insert",
          "SelectProvider",
          "UpdateProvider",
          "DeleteProvider",
          "InsertProvider",
          "Query");

  /** The annotation member that holds the statement. */
  private static final List<String> STATEMENT_MEMBERS = List.of("value", "query", "sql");

  @Override
  public String name() {
    return "orm-sql-interpolation";
  }

  @Override
  public void init(StyleContext ctx) {
    // stateless
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getAnnotations().isEmpty()) return;
    for (AnnotationExpr annotation : n.getAnnotations()) {
      String annotationName = annotation.getNameAsString();
      if (!isSqlAnnotation(annotationName)) continue;

      String offender = offenderIn(annotation, n);
      if (offender == null) continue;

      int line = annotation.getBegin().map(p -> p.line).orElse(-1);
      int col = annotation.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "@"
              + annotationName
              + " builds SQL by interpolating '"
              + offender
              + "'. Use a bound parameter (#{"
              + offender
              + "}) so the value cannot change the statement.",
          RuleDef.Severity.HIGH,
          "orm-sql-interpolation");
    }
  }

  /**
   * The parameter whose value ends up inside the statement, or {@code null} when the statement is
   * safe.
   *
   * <p>Two ways the value can get in:
   *
   * <ol>
   *   <li>A {@code ${name}} placeholder inside the statement text that names one of the method's
   *       parameters. The placeholder is a raw substitution, so the parameter's value becomes SQL
   *       text.
   *   <li>The statement text is built by CONCATENATING a parameter into the string, as in
   *       {@code @Query("SELECT ... " + value)}. Here the annotation's value is not a literal at
   *       all, so the text has to be read from the expression.
   * </ol>
   */
  private static String offenderIn(AnnotationExpr annotation, MethodDeclaration method) {
    Expression value = statementExpression(annotation);
    if (value == null) return null;

    // Case 1: the statement is a literal that contains a ${...} placeholder.
    if (value instanceof StringLiteralExpr literal) {
      return interpolationOffender(literal.getValue(), method);
    }

    // Case 2: the statement is a concatenation that mixes in a parameter.
    return concatenatedOffender(value, method);
  }

  /**
   * The expression that carries the statement, or {@code null} when the annotation has none.
   *
   * <p>Handles the three shapes an annotation can take: a single member ({@code @Select("...")}), a
   * normal member list ({@code @Query(value = "...", nativeQuery = true)}), and a marker with no
   * value at all ({@code @SelectProvider}), which has no statement of its own.
   */
  private static Expression statementExpression(AnnotationExpr annotation) {
    if (annotation instanceof SingleMemberAnnotationExpr single) {
      return single.getMemberValue();
    }
    if (annotation instanceof NormalAnnotationExpr normal) {
      for (MemberValuePair pair : normal.getPairs()) {
        if (isStatementMember(pair.getNameAsString())) {
          return pair.getValue();
        }
      }
    }
    return null;
  }

  private static boolean isSqlAnnotation(String annotationName) {
    for (String candidate : SQL_ANNOTATIONS) {
      if (candidate.equals(annotationName)) return true;
    }
    return false;
  }

  private static boolean isStatementMember(String memberName) {
    for (String candidate : STATEMENT_MEMBERS) {
      if (candidate.equals(memberName)) return true;
    }
    return false;
  }

  /**
   * Find the parameter that is CONCATENATED into the statement, or {@code null} when there is none.
   *
   * <p>The shape is {@code @Query("SELECT ... " + value)}: the annotation's value is a {@code
   * BinaryExpr} whose right-hand side is the method's parameter. The parameter's value becomes part
   * of the query text, exactly as with a {@code ${}} placeholder.
   *
   * <p>The concatenation nests: {@code "a " + name + " b"} parses as {@code ("a " + name) + " b"},
   * so the search has to walk the whole tree rather than look at the top level only.
   *
   * <p>Only a parameter is an offender. A concatenation of two literals, or of a constant the
   * method cannot influence, is not.
   */
  private static String concatenatedOffender(Expression value, MethodDeclaration method) {
    if (value instanceof NameExpr nameExpr) {
      String name = nameExpr.getNameAsString();
      return namesParameter(method, name) ? name : null;
    }
    if (value instanceof BinaryExpr binary) {
      if (binary.getOperator() != BinaryExpr.Operator.PLUS) return null;
      String left = concatenatedOffender(binary.getLeft(), method);
      if (left != null) return left;
      return concatenatedOffender(binary.getRight(), method);
    }
    return null;
  }

  /**
   * Find the parameter whose value ends up inside the statement, or {@code null} when the statement
   * is safe.
   *
   * <p>Only the {@code ${...}} placeholder form is handled here; the concatenated form is handled
   * by {@link #concatenatedOffender}.
   */
  private static String interpolationOffender(String statement, MethodDeclaration method) {
    // Case 1: a ${...} placeholder naming a parameter of this method.
    Matcher matcher = DOLLAR_INTERPOLATION.matcher(statement);
    while (matcher.find()) {
      String placeholder = matcher.group(1).trim();
      if (placeholder.isEmpty()) continue;
      // A nested bound placeholder is not an interpolation.
      if (HASH_PLACEHOLDER.matcher(matcher.group()).matches()) continue;
      if (namesParameter(method, placeholder)) {
        return placeholder;
      }
    }
    return null;
  }

  /**
   * True when the method declares a parameter with the given name.
   *
   * <p>Both the plain parameter name and the {@code @Param("...")} alias are accepted, because
   * MyBatis resolves a {@code ${}} placeholder against the {@code @Param} name when one is present
   * and against the parameter name otherwise.
   */
  private static boolean namesParameter(MethodDeclaration method, String name) {
    for (var parameter : method.getParameters()) {
      if (parameter.getNameAsString().equals(name)) return true;
      for (var annotation : parameter.getAnnotations()) {
        if (!"Param".equals(annotation.getNameAsString())) continue;
        String alias = annotationValue(annotation);
        if (alias != null && alias.equals(name)) return true;
      }
    }
    return false;
  }

  /**
   * The string value of an annotation, whether it is a single member or a {@code value = "..."}
   * pair. Returns {@code null} for anything else.
   */
  private static String annotationValue(AnnotationExpr annotation) {
    if (annotation instanceof SingleMemberAnnotationExpr single) {
      if (single.getMemberValue() instanceof StringLiteralExpr literal) {
        return literal.getValue();
      }
      return null;
    }
    if (annotation instanceof NormalAnnotationExpr normal) {
      for (MemberValuePair pair : normal.getPairs()) {
        if (!"value".equals(pair.getNameAsString())) continue;
        if (pair.getValue() instanceof StringLiteralExpr literal) {
          return literal.getValue();
        }
      }
    }
    return null;
  }

  /** The method's parameter names, lower-cased, for diagnostics. */
  static String describeParameters(MethodDeclaration method) {
    StringBuilder sb = new StringBuilder();
    for (var parameter : method.getParameters()) {
      if (sb.length() > 0) sb.append(", ");
      sb.append(parameter.getNameAsString().toLowerCase(Locale.ROOT));
    }
    return sb.toString();
  }
}
