/*
 * R-812 ? ErrorMessageInfoLeakCheck (CWE-209) migrated
 * from plumb-dfa/.../ErrorMessageInfoLeakChecker.java to
 * plumb-style AST.
 *
 * <p>Flags exception sinks inside {@code catch} blocks
 * that leak exception details:</p>
 * <ul>
 *   <li>{@code e.printStackTrace()}</li>
 *   <li>{@code e.toString()} (when the receiver matches
 *       the catch parameter name)</li>
 *   <li>{@code e.getMessage()} (when the receiver matches
 *       the catch parameter name)</li>
 *   <li>{@code throw new RuntimeException(e)} /
 *       {@code IllegalArgumentException} /
 *       {@code Exception} directly wrapping the catch
 *       parameter</li>
 * </ul>
 *
 * <p>The AST version is a strict subset of the previous
 * DFA half: it walks each {@code CatchClause}, captures
 * the parameter name, and inspects every {@code MethodCallExpr}
 * (scope = {@code NameExpr} matching the param name) and
 * every {@code ThrowStmt} (expression = {@code new
 * XxxException(<param>)}). The DFA half additionally
 * tracked sinks outside catch blocks (logger.error(e),
 * System.out.print(throwable)) via type-based analysis; the
 * AST version is intentionally catch-block-only because the
 * user's spec is "in a catch block".</p>
 */
package org.plumb.style.error;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class ErrorMessageInfoLeakCheck implements AstChecker {

  /** Catch-block exception-receiver sinks. */
  private static final Set<String> SINK_METHODS =
      Set.of("printStackTrace", "toString", "getMessage");

  /**
   * Wrapper exception classes that the rule recognises when thrown directly from a catch block with
   * the catch param as the only argument.
   */
  private static final Set<String> WRAPPER_TYPES =
      Set.of(
          "RuntimeException", "IllegalArgumentException",
          "Exception", "IllegalStateException");

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-error-message-info-leak", "ERROR_MESSAGE_INFO_LEAK"),
          "Exception/error message exposed in catch block (CWE-209)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-209"),
          "org.plumb.style.error.ErrorMessageInfoLeakCheck",
          true,
          List.of("dfa-error-info-leak-error", "error-message-info-leak"));

  @Override
  public String name() {
    return "error-message-info-leak";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CatchClause n, StyleContext ctx) {
    BlockStmt body = n.getBody();
    if (body == null) return;
    String paramName = n.getParameter().getNameAsString();

    // Walk the catch body for sink methods on the
    // catch param and for throws that directly wrap
    // the catch param. findAll walks all descendants
    // of body, so nested catches / lambdas are
    // covered.
    for (MethodCallExpr mc : body.findAll(MethodCallExpr.class)) {
      String callee = mc.getNameAsString();
      if (!SINK_METHODS.contains(callee)) continue;
      // Receiver must be the catch param name (a
      // simple NameExpr). This is the AST analogue
      // of the DFA half's "receiver is in
      // throwableInScope".
      if (mc.getScope().isEmpty()) continue;
      Expression scope = mc.getScope().get();
      if (!(scope instanceof NameExpr ne)) continue;
      if (!ne.getNameAsString().equals(paramName)) continue;
      int line = mc.getBegin().map(p -> p.line).orElse(-1);
      int col = mc.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Exception sink in catch block: "
              + paramName
              + "."
              + callee
              + "() may leak exception details "
              + "(CWE-209).",
          RuleDef.Severity.MEDIUM,
          "error-msg-leak [sink]: " + paramName + "." + callee);
    }

    // Throws that directly wrap the catch param.
    for (ThrowStmt ts : body.findAll(ThrowStmt.class)) {
      Expression expr = ts.getExpression();
      if (expr == null) continue;
      if (!(expr instanceof ObjectCreationExpr oc)) continue;
      String typeName = oc.getType().getNameAsString();
      if (!WRAPPER_TYPES.contains(typeName)) continue;
      if (oc.getArguments().size() != 1) continue;
      if (!(oc.getArgument(0) instanceof NameExpr arg)) continue;
      if (!arg.getNameAsString().equals(paramName)) continue;
      int line = ts.getBegin().map(p -> p.line).orElse(-1);
      int col = ts.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "throw new "
              + typeName
              + "("
              + paramName
              + ") wraps the catch parameter directly "
              + "and may leak exception details (CWE-209).",
          RuleDef.Severity.MEDIUM,
          "error-msg-leak [throw]: new " + typeName + "(" + paramName + ")");
    }
  }

  /** Unused — kept for symmetry / future logger sinks. */
  @SuppressWarnings("unused")
  private static String lower(String s) {
    return s.toLowerCase(Locale.ROOT);
  }
}
