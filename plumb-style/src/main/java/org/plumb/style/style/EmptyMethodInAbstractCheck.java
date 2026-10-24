package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import java.util.Optional;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Empty method body in abstract class should be marked abstract. */
public final class EmptyMethodInAbstractCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-empty-method-in-abstract", "EMPTY_METHOD_IN_ABSTRACT"),
          "Empty method body in abstract class should be marked abstract",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EmptyMethodInAbstractCheck",
          true,
          List.of("ast-style-method-in-abstract", "empty-method-in-abstract"));

  @Override
  public String name() {
    return "empty-method-in-abstract";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (!n.isAbstract()) return;
    for (MethodDeclaration method : n.getMethods()) {
      if (method.isAbstract() || method.isNative()) continue;
      // Walk the body and check if it's a stub.
      final Optional<BlockStmt> body = method.getBody();
      if (body.isEmpty()) continue;
      if (isStubBody(body.get())) {
        ctx.report(
            method.getBegin().map(p -> p.line).orElse(-1),
            method.getBegin().map(p -> p.column).orElse(-1),
            DEFAULT_RULE,
            "Method '"
                + method.getNameAsString()
                + "' has a stub body but is not abstract (mark abstract)",
            RuleDef.Severity.LOW,
            method.getNameAsString());
      }
    }
  }

  /**
   * Returns true when the body is empty, or contains a single <code>return
   * null;</code> / <code>return 0;</code> (the canonical stub patterns). We intentionally narrow to
   * the canonical patterns rather than any "tiny body" to avoid over-firing on real
   * implementations.
   */
  private static boolean isStubBody(BlockStmt body) {
    final List<Statement> stmts = body.getStatements();
    if (stmts.isEmpty()) return true;
    if (stmts.size() > 1) return false;
    Statement s = stmts.get(0);
    if (!(s instanceof ReturnStmt ret)) return false;
    if (ret.getExpression().isEmpty()) return true; // bare `return;`
    return ret.getExpression().get().toString().equals("null")
        || ret.getExpression().get().toString().equals("0");
  }
}
