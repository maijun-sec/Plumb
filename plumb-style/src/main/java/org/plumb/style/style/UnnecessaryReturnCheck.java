package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Final 'return' in void method is unnecessary. */
public final class UnnecessaryReturnCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-unnecessary-return", "UNNECESSARY_RETURN"),
          "Final 'return' in void method is unnecessary",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnnecessaryReturnCheck",
          true,
          List.of("ast-style-unnecessary-return", "unnecessary-return"));

  @Override
  public String name() {
    return "unnecessary-return";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    BlockStmt body = n.getBody().get();
    if (body.getStatements().isEmpty()) return;
    var last = body.getStatements().get(body.getStatements().size() - 1);
    if (!(last instanceof ReturnStmt rs)) return;
    if (rs.getExpression().isPresent()) return;
    // Check the method is void (or no explicit return type).
    String returnType = n.getType().asString();
    if (!returnType.equals("void")) return;
    int line = rs.getBegin().map(p -> p.line).orElse(-1);
    int col = rs.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Final 'return' in void method '" + n.getNameAsString() + "' is unnecessary",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}
