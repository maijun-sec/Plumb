/*
 * R729-A4: AST check for CWE-584 (Return in Finally
 * Block). Fires when a ReturnStmt's ancestors include
 * a TryStmt where the ReturnStmt lives INSIDE the
 * finally block (not the try or catch block).
 *
 * <p>Generic. Any `return` inside a finally clause
 * silently swallows exceptions from the try / catch
 * arms; universal bug.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class ReturnInFinallyBlockCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-return-in-finally", "RETURN_IN_FINALLY"),
          "return statement inside a finally block — swallows try/catch exceptions (CWE-584)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-584"),
          "org.plumb.style.ReturnInFinallyBlockCheck",
          true,
          List.of("ast-style-return-in-finally", "return-in-finally")
      );

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "return-in-finally";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(ReturnStmt n, StyleContext ctx) {
    // Walk up. Find the nearest TryStmt and confirm
    // we're inside the finally block.
    Node cur = n;
    while (cur != null) {
      Node parent = cur.getParentNode().orElse(null);
      if (parent instanceof TryStmt ts) {
        var finallyOpt = ts.getFinallyBlock();
        if (finallyOpt.isPresent() && isInside(cur, finallyOpt.get())) {
          int line = n.getBegin().map(p -> p.line).orElse(-1);
          int col = n.getBegin().map(p -> p.column).orElse(-1);
          String key = line + "|" + col + "|inFin";
          if (reported.add(key)) {
            ctx.report(
                line,
                col,
                DEFAULT_RULE,
                "return inside a finally block — "
                    + "this swallows any exception thrown "
                    + "in the try / catch arms (CWE-584)",
                RuleDef.Severity.HIGH,
                "return");
          }
        }
        break;
      }
      cur = parent;
    }
  }

  private boolean isInside(Node child, Node ancestor) {
    Node c = child;
    while (c != null) {
      if (c == ancestor) return true;
      c = c.getParentNode().orElse(null);
    }
    return false;
  }
}
