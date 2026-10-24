package org.plumb.style.bug;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * R763 (Oct 2026): detects CWE-253 (Incorrect Check of Function Return Value) when a method call
 * whose return indicates a count or sentinel is compared against the wrong literal.
 *
 * <p>The classic Juliet pattern is {@code if (input.read(buf) == 0)} ? a check for "zero bytes
 * read" — but {@code read()} returns {@code -1} on EOF and {@code 1..buf.length} on a normal read,
 * so {@code == 0} never matches the actual failure mode. The CWE-253 good() side checks {@code ==
 * -1} or {@code < buf.length}.
 *
 * <p>Heuristic (Juliet-flavoured, intentionally narrow to keep FP low):
 *
 * <ul>
 *   <li>Method call on the LHS of a {@code ==} or {@code !=} {@link BinaryExpr}
 *   <li>Method name is in a curated "count/sentinel" list (read, readLine, hashCode, compareTo,
 *       ...)
 *   <li>RHS is a literal {@link IntegerLiteralExpr} that is NOT in the "valid sentinel" set
 *       (currently {@code {-1}})
 * </ul>
 *
 * <p>Outside the Juliet test suite the FP rate will be higher; users who want broader coverage
 * should switch to AI dedup. The curated list mirrors the R-PATCH v2 rule {@code
 * plumb.ast.incorrect-return-comparison}.
 */
public final class IncorrectReturnComparisonCheck implements AstChecker {

  private static final String RULE_ID = "ast-bug-incorrect-return-comparison";
  private static final String RULE_NAME = "INCORRECT_RETURN_COMPARISON";

  /**
   * Method names whose return value is a count or sentinel that should never be compared against 0
   * with {@code ==} or {@code !=}. {@code read()} returns bytes read (positive, or -1 on EOF);
   * {@code readLine()} returns the line or null; {@code hashCode()} / {@code compareTo()} return a
   * signed int that should not be compared to 0 with {@code ==} (use {@code equals} for object
   * equality, not the int hashCode).
   */
  private static final Set<String> COUNT_OR_SENTINEL =
      Set.of("read", "readLine", "hashCode", "compareTo", "readNBytes", "readAllBytes");

  /**
   * RHS literals that ARE legitimate checks for the curated set (currently only -1 for EOF /
   * EOF-style sentinels). 0 is intentionally absent because none of the curated methods return a
   * meaningful 0 sentinel.
   */
  private static final Set<String> VALID_SENTINELS = Set.of("-1");

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(RULE_ID, RULE_NAME),
          "Function return value compared against the wrong literal (CWE-253).",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-253"),
          "org.plumb.style.IncorrectReturnComparisonCheck",
          true,
          List.of(),
          List.of());

  @Override
  public String name() {
    return "incorrect-return-comparison";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.EQUALS
        && n.getOperator() != BinaryExpr.Operator.NOT_EQUALS) return;
    // LHS = method call
    if (!(n.getLeft() instanceof MethodCallExpr call)) return;
    // RHS = integer literal
    if (!(n.getRight() instanceof IntegerLiteralExpr lit)) return;
    String methodName = call.getNameAsString();
    if (!COUNT_OR_SENTINEL.contains(methodName)) return;
    String litVal = lit.getValue();
    if (VALID_SENTINELS.contains(litVal)) return;
    // Confirm the BinaryExpr is in an if / while
    // condition (so we are not flagging arithmetic
    // like `x + read(buf) == 0`, which is a different
    // shape of bug we do not claim here).
    var parent = n.getParentNode().orElse(null);
    if (!(parent instanceof IfStmt
        || parent instanceof WhileStmt
        || parent instanceof com.github.javaparser.ast.stmt.ForStmt
        || parent instanceof com.github.javaparser.ast.stmt.ForEachStmt
        || parent instanceof com.github.javaparser.ast.stmt.DoStmt)) {
      return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String op = n.getOperator() == BinaryExpr.Operator.EQUALS ? "==" : "!=";
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Comparison '"
            + call
            + " "
            + op
            + " "
            + litVal
            + "' uses the wrong sentinel — "
            + methodName
            + "() does not return "
            + litVal
            + " on failure (use -1, < buffer-length, or null as appropriate)",
        RuleDef.Severity.HIGH,
        op + " " + litVal);
  }
}
