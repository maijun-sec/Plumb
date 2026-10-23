/*
 * R-812 Batch 3 ? Unchecked return value (CWE-252) migrated
 * from
 * plumb-dfa/.../checkers/UncheckedReturnChecker.java.
 *
 * <p>Detects method calls whose return value is
 * discarded when the method indicates success / failure.
 * The DFA half walked the TAC IR to determine whether the
 * dest temp was read later. The AST half fires on every
 * bare {@link ExpressionStmt} whose expression is a call
 * to a "must-check" method.</p>
 *
 * <p><b>Method-name whitelist.</b> Mirrors the DFA half's
 * curated list of security-sensitive methods:
 * {@code close}, {@code delete}, {@code mkdir},
 * {@code mkdirs}, {@code renameTo}, {@code createNewFile},
 * {@code commit}, {@code rollback}, {@code execute},
 * {@code next}, {@code read}, {@code readLine},
 * {@code remove}. The receiver type is NOT verified ? the
 * AST half is intentionally broader (every {@code close()}
 * on anything fires, even if it's a custom close() that
 * doesn't return anything useful). The AI dedup prompt
 * filters FPs.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** CWE-252: unchecked return value. */
public final class UncheckedReturnCheck implements AstChecker {

  /**
   * Method-name whitelist. The DFA half also keyed off the receiver type ({@code File}, {@code
   * Connection}, ...); the AST half drops that constraint ? symbol resolution on a method receiver
   * adds noise without much value, and the AI dedup handles the resulting FPs.
   */
  private static final Set<String> MUST_CHECK =
      Set.of(
          // File I/O
          "delete",
          "mkdir",
          "mkdirs",
          "renameTo",
          "createNewFile",
          // SQL / IO close
          "close",
          "commit",
          "rollback",
          "execute",
          // Map / List get — null when key missing / OOB
          "get",
          // Iterator next — throws NoSuchElementException
          "next",
          // Stream / Reader read — bytes read / -1 EOF
          "read",
          "readLine",
          // Map / List contains / remove
          "remove");

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-unchecked-return", "UNCHECKED_RETURN"),
          "Return value of a security-sensitive method (close / "
              + "delete / mkdir / commit / read / ...) is "
              + "discarded; the value indicates success/failure "
              + "and must be checked (CWE-252)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-252"),
          "org.plumb.style.security.UncheckedReturnCheck",
          true,
          List.of("dfa-security-unchecked-return", "unchecked-return")
      );

  @Override
  public String name() {
    return "unchecked-return";
  }

  @Override
  public void init(StyleContext ctx) {
    // stateless
  }

  @Override
  public void visit(ExpressionStmt n, StyleContext ctx) {
    if (!(n.getExpression() instanceof MethodCallExpr call)) return;
    if (!MUST_CHECK.contains(call.getNameAsString())) return;

    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Return value of "
            + call.getNameAsString()
            + "() is discarded; the value indicates "
            + "success/failure and must be checked (CWE-252)",
        RuleDef.Severity.MEDIUM,
        "unchecked-return: " + call.getNameAsString() + "()");
  }
}
