package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * @Override method with no real body (DMI_DONE).
 */
public final class DmiDoneCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-dmi-done", "DMI_DONE"),
          "@Override method with no real body (DMI_DONE)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DmiDoneCheck",
          true,
          List.of("ast-style-done", "dmi-done")
      );

  @Override
  public String name() {
    return "dmi-done";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    BlockStmt body = n.getBody().get();
    // Method body is `return;` only : useless override.
    if (body.getStatements().size() == 1
        && body.getStatements().get(0) instanceof ReturnStmt ret
        && !ret.getExpression().isPresent()) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Method with empty body 'return;' only : useless",
          RuleDef.Severity.LOW,
          n.getNameAsString());
    }
  }
}
