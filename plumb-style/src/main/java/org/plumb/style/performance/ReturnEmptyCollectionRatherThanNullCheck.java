package org.plumb.style.performance;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** return null for collection : use empty. */
public final class ReturnEmptyCollectionRatherThanNullCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-return-null-collection", "RETURN_NULL_COLLECTION"),
          "return null for collection : use empty",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.ReturnEmptyCollectionRatherThanNullCheck",
          true,
          List.of("ast-performance-return-empty-collection-rather-than-null", "return-null-collection")
      );

  @Override
  public String name() {
    return "return-null-collection";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ReturnStmt n, StyleContext ctx) {
    if (n.getExpression().isEmpty()) return;
    if (!(n.getExpression().get() instanceof NullLiteralExpr)) return;
    if (n.findAncestor(MethodDeclaration.class).isEmpty()) return;
    MethodDeclaration md = n.findAncestor(MethodDeclaration.class).get();
    Type returnType = md.getType();
    if (returnType instanceof ArrayType || returnType instanceof ClassOrInterfaceType) {
      String name = returnType.toString();
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "return null for " + name + " : return empty instead",
          RuleDef.Severity.MEDIUM,
          name);
    }
  }
}
