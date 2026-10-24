package org.plumb.style.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * toString() returning null.
 *
 * <p>Pattern D: per-method {@link MethodDeclaration} visit. For toString() methods, walk the body
 * once looking for {@link ReturnStmt} returning {@link NullLiteralExpr}. Replaces visit(CU) + 2
 * findAll with a single per-method walk triggered by AstDispatcher.
 */
public final class NullReturnedToStringCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-null-returned-tostring", "NULL_RETURNED_TOSTRING"),
          "toString() returning null",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NullReturnedToStringCheck",
          true,
          List.of("ast-performance-returned-tostring", "null-returned-tostring"));

  @Override
  public String name() {
    return "null-returned-tostring";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!m.getNameAsString().equals("toString")) return;
    if (!m.getBody().isPresent()) return;
    m.getBody()
        .get()
        .walk(
            Node.class,
            n -> {
              if (n instanceof ReturnStmt) {
                ReturnStmt ret = (ReturnStmt) n;
                if (ret.getExpression().isPresent()
                    && ret.getExpression().get() instanceof NullLiteralExpr) {
                  int line = ret.getBegin().map(p -> p.line).orElse(-1);
                  int col = ret.getBegin().map(p -> p.column).orElse(-1);
                  ctx.report(
                      line,
                      col,
                      DEFAULT_RULE,
                      "toString() should not return null",
                      RuleDef.Severity.LOW,
                      "toString");
                }
              }
            });
  }
}
