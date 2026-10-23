/*
 * R729-A6: AST check for CWE-698 (Redirect Without
 * Exit). Fires when a `sendRedirect(...)` call appears
 * in a method body that contains further statements
 * after the redirect. The fix in Juliet's good()
 * sinks is to make the redirect the last statement;
 * here we report every sendRedirect call site that is
 * NOT the last statement of its enclosing block.
 *
 * <p>Generic ? applies to any
 * `HttpServletResponse.sendRedirect` use. False
 * positives may fire if the developer has a control
 * flow path that returns after the redirect; the
 * AI-dedup pass filters those.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class RedirectWithoutExitCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-redirect-without-exit", "REDIRECT_WITHOUT_EXIT"),
          "sendRedirect() is not the last statement — add return after it (CWE-698)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-698"),
          "org.plumb.style.RedirectWithoutExitCheck",
          true,
          List.of("ast-style-redirect-without-exit", "redirect-without-exit")
      );

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "redirect-without-exit";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"sendRedirect".equals(n.getNameAsString())) return;
    // Scope must be response / a NameExpr.
    if (n.getScope().isEmpty()) return;
    // Find the enclosing BlockStmt and the parent
    // Statement.
    Statement parentStmt = findParentStatement(n);
    if (parentStmt == null) return;
    Node block = parentStmt.getParentNode().orElse(null);
    if (!(block instanceof BlockStmt bs)) return;
    int idx = bs.getStatements().indexOf(parentStmt);
    if (idx < 0) return;
    boolean isLast = (idx == bs.getStatements().size() - 1);
    // If the redirect is followed by a sibling that
    // is a `return;` (no value) — Juliet's good2
    // pattern — accept.
    if (!isLast) {
      Statement next = bs.getStatements().get(idx + 1);
      if (next.toString().startsWith("return")) {
        return;
      }
    } else {
      return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String key = line + "|" + col + "|noExit";
    if (reported.add(key)) {
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "sendRedirect() is followed by other "
              + "statements — add `return;` "
              + "immediately after the redirect (CWE-698)",
          RuleDef.Severity.HIGH,
          "sendRedirect(...)");
    }
  }

  private Statement findParentStatement(Node n) {
    Node cur = n;
    while (cur != null) {
      Node parent = cur.getParentNode().orElse(null);
      if (parent instanceof Statement) return (Statement) parent;
      cur = parent;
    }
    return null;
  }
}
