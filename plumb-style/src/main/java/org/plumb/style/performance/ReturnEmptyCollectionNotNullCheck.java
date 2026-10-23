package org.plumb.style.performance;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** return empty collection instead of null. */
public final class ReturnEmptyCollectionNotNullCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-return-empty-not-null", "RETURN_EMPTY_NOT_NULL"),
          "return empty collection instead of null",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.ReturnEmptyCollectionNotNullCheck",
          true,
          List.of("ast-performance-return-empty-not-null", "return-empty-not-null")
      );

  @Override
  public String name() {
    return "return-empty-not-null";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ReturnStmt n, StyleContext ctx) {
    if (n.getExpression().isEmpty()) return;
    if (!(n.getExpression().get() instanceof NullLiteralExpr)) return;
    if (n.findAncestor(MethodDeclaration.class).isEmpty()) return;
    MethodDeclaration md = n.findAncestor(MethodDeclaration.class).get();
    String returnType = md.getType().toString();
    // Strip generic parameters: "List<String>" -> "List".
    String baseType = returnType;
    int genIdx = returnType.indexOf('<');
    if (genIdx > 0) baseType = returnType.substring(0, genIdx);
    if (isCollectionType(baseType)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Return empty " + returnType + " instead of null : avoid forcing callers to null-check",
          RuleDef.Severity.MEDIUM,
          returnType);
    }
  }

  private static boolean isCollectionType(String t) {
    return t.equals("List")
        || t.equals("Collection")
        || t.equals("Set")
        || t.equals("Map")
        || t.equals("Queue")
        || t.equals("Deque")
        || t.equals("java.util.List")
        || t.equals("java.util.Collection")
        || t.equals("java.util.Set")
        || t.equals("java.util.Map")
        || t.endsWith("[]");
  }
}
