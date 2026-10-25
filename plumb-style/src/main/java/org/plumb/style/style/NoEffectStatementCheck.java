/*
 * C2: AST-style CWE-398 (Indicator of Poor Code
 * Quality) checker — `;` empty-statement-as-statement
 * (the `;` line at the top of `bad()`) and
 * self-assignment (`x = x`).
 *
 * <p>Generic. The check fires on:
 * <ol>
 *   <li>`visit(EmptyStmt)` — top-level `;` literal in
 *       a method body (when the parent statement is
 *       a BlockStmt, i.e. not inside a control
 *       structure's brace).</li>
 *   <li>`visit(AssignExpr)` — `x = x` self-assignment.
 *   </li>
 * </ol>
 *
 * <p>The empty-statement heuristic excludes `;` inside
 * `for (...; ; ...)` headers ? those are intentional
 * empty-statement placeholders in C for-loop syntax and
 * have semantic meaning.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class NoEffectStatementCheck implements AstChecker {

  private static final RuleDef EMPTY_STMT_RULE =
      new RuleDef(
          new RuleId("ast-style-no-effect-statement", "NO_EFFECT_STATEMENT"),
          "Standalone `;` statement has no effect (CWE-398)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-398"),
          "org.plumb.style.NoEffectStatementCheck",
          true,
          List.of("ast-style-no-effect-statement", "no-effect-statement"));

  private static final RuleDef SELF_ASSIGN_RULE =
      new RuleDef(
          new RuleId("dfa-style-self-assign", "SELF_ASSIGN"),
          "Self-assignment `x = x` has no effect (CWE-398)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-398"),
          "org.plumb.style.NoEffectStatementCheck",
          true,
          List.of("dfa-style-empty-statement", "no-effect-statement"));

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "no-effect-statement";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(EmptyStmt n, StyleContext ctx) {
    // Skip ONLY when the EmptyStmt is a DIRECT child
    // of a ForStmt — `for (...; ; ...)` uses `;` as
    // an intentional empty-statement placeholder in
    // the loop header. Anything inside the for-loop
    // BODY (a BlockStmt whose parent is a ForStmt)
    // is a real fire site.
    //
    // Previously we walked up the parent chain and
    // skipped ANY ForStmt ancestor — that incorrectly
    // missed the `for (i...) { ; ... }` pattern in
    // Juliet's CWE-398 semicolon_17 family.
    var parent = n.getParentNode().orElse(null);
    if (parent instanceof ForStmt) return;
    // Skip when the empty stmt is the body of an
    // IfStmt / WhileStmt / ForStmt / DoStmt /
    // SwitchEntry / CatchClause — those are handled
    // by the empty-block family.
    if (parent instanceof com.github.javaparser.ast.stmt.IfStmt
        || parent instanceof com.github.javaparser.ast.stmt.WhileStmt
        || parent instanceof com.github.javaparser.ast.stmt.DoStmt
        || parent instanceof com.github.javaparser.ast.stmt.SwitchEntry
        || parent instanceof com.github.javaparser.ast.stmt.CatchClause) {
      return;
    }
    // If parent is a BlockStmt AND the BlockStmt's
    // parent is an IfStmt whose *else* branch
    // contains the empty stmt, skip — that's
    // empty-else territory. For all other BlockStmt
    // parents (method body, if body, while body,
    // for body, try body, etc.) we DO fire because
    // a standalone `;` is always a useless statement.
    if (parent instanceof com.github.javaparser.ast.stmt.SwitchEntry) {
      return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String key = line + "|" + col + "|empStmt";
    if (reported.add(key)) {
      ctx.report(
          line,
          col,
          EMPTY_STMT_RULE,
          "Standalone `;` is a statement that has no effect " + "(CWE-398)",
          RuleDef.Severity.LOW,
          ";");
    }
  }

  @Override
  public void visit(AssignExpr n, StyleContext ctx) {
    if (n.getOperator() != AssignExpr.Operator.ASSIGN) return;
    Expression left = n.getTarget();
    Expression right = n.getValue();
    // self-assign if both sides are the same NameExpr
    if (left instanceof NameExpr nl
        && right instanceof NameExpr nr
        && nl.getNameAsString().equals(nr.getNameAsString())) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      String key = line + "|" + col + "|self";
      if (reported.add(key)) {
        ctx.report(
            line,
            col,
            SELF_ASSIGN_RULE,
            "Self-assignment `"
                + nl.getNameAsString()
                + " = "
                + nr.getNameAsString()
                + "` has no effect (CWE-398)",
            RuleDef.Severity.LOW,
            nl.getNameAsString() + " = " + nr.getNameAsString());
      }
    }
  }
}
