package org.plumb.style.performance;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import java.util.Optional;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** String comparison with == (should use.equals()). */
public final class StringEqualityWithEqualsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-string-equality-with-equals", "STRING_EQUALITY_WITH_EQUALS"),
          "String comparison with == (should use.equals())",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-697"),
          "org.plumb.style.StringEqualityWithEqualsCheck",
          true,
          List.of("ast-performance-string-equality-with-equals", "string-equality-with-equals"));

  @Override
  public String name() {
    return "string-equality-with-equals";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.EQUALS
        && n.getOperator() != BinaryExpr.Operator.NOT_EQUALS) return;
    final Expression left = n.getLeft();
    final Expression right = n.getRight();
    // At least one side must be String-like. We
    // try two signals:
    // 1) The enclosing method has a String-typed
    // parameter or local var that matches the
    // NameExpr on either side.
    // 2) The NameExpr's identifier is
    // String-like by name heuristic.
    // 3) Either side is a String literal.
    if (!looksLikeString(left) && !looksLikeString(right)) {
      if (!isStringNameInEnclosingMethod(left, ctx) && !isStringNameInEnclosingMethod(right, ctx)) {
        return;
      }
    }
    ctx.report(
        n.getBegin().map(p -> p.line).orElse(-1),
        n.getBegin().map(p -> p.column).orElse(-1),
        DEFAULT_RULE,
        "Use.equals() to compare String values, not ==",
        RuleDef.Severity.MEDIUM,
        n.toString());
  }

  /**
   * Heuristic: a String literal is obviously a String. A NameExpr with a String-like name (<code>
   * str</code>, <code>name</code>,...) is likely a String. Numeric / boolean names (<code>i</code>,
   * <code>b</code>, <code>count</code>, <code>size</code>) are NOT.
   */
  private static boolean looksLikeString(Expression e) {
    if (e instanceof StringLiteralExpr) return true;
    if (e instanceof NameExpr ne) {
      String lower = ne.getNameAsString().toLowerCase();
      if (lower.contains("str")) return true;
      if (lower.contains("name")) return true;
      if (lower.contains("text")) return true;
      if (lower.contains("msg")) return true;
      if (lower.contains("key")) return true;
      if (lower.contains("line")) return true;
      if (lower.contains("word")) return true;
      if (lower.contains("char")) return true;
      if (lower.endsWith("str")) return true;
    }
    return false;
  }

  /**
   * If the either side matches a String-typed parameter or local variable in the enclosing method,
   * treat it as a String. This catches the common case of where the parameter names are not
   * String-like by themselves but the types are.
   */
  private static boolean isStringNameInEnclosingMethod(Expression e, StyleContext ctx) {
    if (!(e instanceof NameExpr ne)) return false;
    String name = ne.getNameAsString();
    // Walk up to the enclosing MethodDeclaration.
    Optional<com.github.javaparser.ast.body.MethodDeclaration> md =
        ne.findAncestor(MethodDeclaration.class);
    if (md.isEmpty()) return false;
    MethodDeclaration m = md.get();
    // Check parameters
    for (com.github.javaparser.ast.body.Parameter p : m.getParameters()) {
      if (p.getNameAsString().equals(name) && isStringType(p.getType())) {
        return true;
      }
    }
    // Check local var declarations
    for (com.github.javaparser.ast.stmt.Statement st : m.getBody().orElseThrow().getStatements()) {
      if (st instanceof com.github.javaparser.ast.stmt.ExpressionStmt es
          && es.getExpression()
              instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr vde) {
        for (com.github.javaparser.ast.body.VariableDeclarator v : vde.getVariables()) {
          if (v.getNameAsString().equals(name) && isStringType(vde.getElementType())) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static boolean isStringType(com.github.javaparser.ast.type.Type t) {
    String name = t.asString();
    return "String".equals(name)
        || "java.lang.String".equals(name)
        || "CharSequence".equals(name)
        || "StringBuilder".equals(name)
        || "StringBuffer".equals(name);
  }
}
