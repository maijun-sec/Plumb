package org.plumb.style.error;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** empty finalize() : remove the method (finalizers are deprecated). */
public final class EmptyFinalizerCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-empty-finalizer", "EMPTY_FINALIZER"),
          "empty finalize() : remove the method (finalizers are deprecated)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.EmptyFinalizerCheck",
          true,
          List.of("ast-error-finalizer", "empty-finalizer"));

  @Override
  public String name() {
    return "empty-finalizer";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (!"finalize".equals(n.getNameAsString())) return;
    if (n.getBody().isEmpty()) return;
    BlockStmt body = n.getBody().get();
    if (!body.getStatements().isEmpty()) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Empty finalize() : remove the method",
        RuleDef.Severity.MEDIUM,
        "finalize");
  }
}
