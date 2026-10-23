/*
 * R722-C8: AST-style CWE-539 (Information Exposure Through
 * Persistent Cookie) checker.
 *
 * <p>Fires when a {@code Cookie.setMaxAge(int)} call has an
 * argument larger than a typical session length
 * (e.g. &gt; 3600 seconds = 1 hour). A persistent cookie
 * (max-age &gt; 3600) exposes the cookie value to
 * subsequent runs and risks information exposure.</p>
 *
 * <p>The Juliet set uses literal numeric arguments to
 * {@code setMaxAge} (e.g. {@code 60*60*24*365*5}). For the
 * AST check we evaluate literal integer expressions
 * statically and check if the value &gt; 3600.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-539: Information Exposure Through Persistent Cookie.
 *
 * <p>Detects a {@code Cookie.setMaxAge(N)} call where {@code N} is a compile-time constant &gt;
 * 3600 (one hour). The check statically folds {@code *}, {@code /}, {@code +}, {@code -}, {@code
 * -N} expressions with integer literals.
 */
public final class PersistentCookieCheck implements AstChecker {

  private static final String RULE_ID = "dfa-security-persistent-cookie";
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(RULE_ID, "PERSISTENT_COOKIE"),
          "Cookie.setMaxAge with constant > 3600 makes the cookie persistent (CWE-539)",
          RuleDef.Severity.LOW,
          RuleDef.Type.SECURITY,
          List.of("CWE-539"),
          "org.plumb.style.security.PersistentCookieCheck",
          true);

  @Override
  public String name() {
    return "persistent-cookie";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"setMaxAge".equals(n.getNameAsString())) return;
    if (n.getArguments().isEmpty()) return;
    var arg = n.getArguments().get(0);
    Long value = evalConstant(arg);
    if (value == null) return;
    if (value > 3600) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Cookie.setMaxAge("
              + value
              + ") makes the cookie persistent; sensitive data may be exposed to subsequent runs",
          RuleDef.Severity.LOW,
          "persistent-cookie: " + value);
    }
  }

  /**
   * Statically evaluate a literal integer / long expression. Supports {@code *}, {@code /}, {@code
   * +}, {@code -}, unary minus, parentheses.
   *
   * @return the constant value, or {@code null} if any sub-expression isn't a literal.
   */
  private static Long evalConstant(com.github.javaparser.ast.expr.Expression e) {
    if (e instanceof IntegerLiteralExpr il) {
      return Long.parseLong(il.getValue());
    }
    if (e instanceof LongLiteralExpr ll) {
      return Long.parseLong(ll.getValue().replace("L", "").replace("l", ""));
    }
    if (e instanceof UnaryExpr ue) {
      Long inner = evalConstant(ue.getExpression());
      if (inner == null) return null;
      return ue.getOperator() == UnaryExpr.Operator.MINUS ? -inner : inner;
    }
    if (e instanceof BinaryExpr be) {
      Long left = evalConstant(be.getLeft());
      Long right = evalConstant(be.getRight());
      if (left == null || right == null) return null;
      return switch (be.getOperator()) {
        case PLUS -> left + right;
        case MINUS -> left - right;
        case MULTIPLY -> left * right;
        case DIVIDE -> right == 0 ? null : left / right;
        default -> null;
      };
    }
    return null;
  }
}
