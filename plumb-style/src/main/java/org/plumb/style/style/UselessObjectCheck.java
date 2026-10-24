package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import java.util.Optional;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Object created and discarded without use. */
public final class UselessObjectCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-useless-object", "USELESS_OBJECT"),
          "Object created and discarded without use",
          RuleDef.Severity.LOW,
          RuleDef.Type.PERFORMANCE,
          List.of(),
          "org.plumb.style.UselessObjectCheck",
          true,
          List.of("ast-style-useless-object-alpha", "useless-object"));

  @Override
  public String name() {
    return "useless-object";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    final Optional<BlockStmt> body = n.getBody();
    if (body.isEmpty()) return;
    // First pass: collect local variables whose
    // initializer is `new...()` or `valueOf(...)` and
    // the variable name.
    final java.util.Map<String, Statement> candidates = new java.util.HashMap<>();
    for (Statement s : body.get().getStatements()) {
      if (s instanceof ExpressionStmt es
          && es.getExpression() instanceof VariableDeclarationExpr vde) {
        vde.getVariables()
            .forEach(
                v -> {
                  if (v.getInitializer().isPresent()) {
                    Expression init = v.getInitializer().get();
                    if (isObjectCreation(init) || isValueOfCall(init)) {
                      candidates.put(v.getNameAsString(), s);
                    }
                  }
                });
      }
    }
    if (candidates.isEmpty()) return;
    // Second pass: count references per candidate. A
    // "reference" is a NameExpr anywhere in the method
    // body other than the declaration. The walk
    // (NameExpr.class) does NOT see the variable
    // declarator's name as a NameExpr (it lives in
    // VariableDeclarator.getName() as a String, not
    // a NameExpr node), so a count of 0 means
    // truly never used. Any positive count means
    // the variable is used at least once.
    final java.util.Map<String, Integer> refCounts = new java.util.HashMap<>();
    for (String c : candidates.keySet()) refCounts.put(c, 0);
    body.get()
        .walk(
            com.github.javaparser.ast.expr.NameExpr.class,
            ne -> {
              String name = ne.getNameAsString();
              if (candidates.containsKey(name)) refCounts.merge(name, 1, Integer::sum);
            });
    for (java.util.Map.Entry<String, Integer> e : refCounts.entrySet()) {
      if (e.getValue() == 0) {
        Statement decl = candidates.get(e.getKey());
        ctx.report(
            decl.getBegin().map(p -> p.line).orElse(-1),
            decl.getBegin().map(p -> p.column).orElse(-1),
            DEFAULT_RULE,
            "Object stored in variable '" + e.getKey() + "' is useless (created and never used)",
            RuleDef.Severity.LOW,
            e.getKey());
      }
    }
  }

  private static boolean isObjectCreation(Expression e) {
    return e instanceof ObjectCreationExpr;
  }

  private static boolean isValueOfCall(Expression e) {
    if (e instanceof MethodCallExpr mc) {
      return "valueOf".equals(mc.getNameAsString());
    }
    return false;
  }
}
