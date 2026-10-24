package org.plumb.style.performance;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use for-each instead of indexed for-loop over array. */
public final class AvoidArrayLoopsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-avoid-array-loops", "AVOID_ARRAY_LOOPS"),
          "use for-each instead of indexed for-loop over array",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AvoidArrayLoopsCheck",
          true,
          List.of("ast-performance-array-loops", "avoid-array-loops"));

  @Override
  public String name() {
    return "avoid-array-loops";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    // Find the loop counter name from initialization.
    String counter = null;
    String arrayName = null;
    for (var init : n.getInitialization()) {
      if (!(init instanceof VariableDeclarationExpr vde)) continue;
      for (VariableDeclarator v : vde.getVariables()) {
        if (v.getType().toString().equals("int")) {
          counter = v.getNameAsString();
          break;
        }
      }
    }
    if (counter == null) return;
    // Find an ArrayAccessExpr with this counter inside
    // the body. If found, suggest for-each.
    for (var a : n.findAll(ArrayAccessExpr.class)) {
      if (a.getIndex() instanceof NameExpr ne && ne.getNameAsString().equals(counter)) {
        if (a.getName() instanceof NameExpr an) {
          arrayName = an.getNameAsString();
        }
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "for ("
                + counter
                + "...) over "
                + (arrayName != null ? arrayName : "array")
                + " : use for-each",
            RuleDef.Severity.LOW,
            counter);
        return;
      }
    }
  }
}
