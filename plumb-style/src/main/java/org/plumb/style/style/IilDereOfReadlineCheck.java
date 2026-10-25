package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** readLine() result not null-checked (IIL_DEREF_OF_READLINE_VALUE). */
public final class IilDereOfReadlineCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-iil-deref-readline", "IIL_DEREF_READLINE"),
          "readLine() result not null-checked (IIL_DEREF_OF_READLINE_VALUE)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-476"),
          "org.plumb.style.IilDereOfReadlineCheck",
          true,
          List.of("ast-style-deref-of-readline-value", "iil-deref-readline"));

  @Override
  public String name() {
    return "iil-deref-readline";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (m.getBody().isEmpty()) return;
    boolean seenIfNull = false;
    Map<String, Integer> readLineVars = new HashMap<>();
    for (Statement s : m.getBody().get().getStatements()) {
      if (s instanceof ExpressionStmt) {
        if (((ExpressionStmt) s).getExpression() instanceof VariableDeclarationExpr vde) {
          for (VariableDeclarator v : vde.getVariables()) {
            if (!v.getInitializer().isPresent()) return;
            if (v.getInitializer().get() instanceof MethodCallExpr mc
                && "readLine".equals(mc.getNameAsString())) {
              readLineVars.put(v.getNameAsString(), s.getBegin().map(p -> p.line).orElse(-1));
            }
          }
        }
        if (((ExpressionStmt) s).getExpression() instanceof MethodCallExpr mc) {
          if (mc.getScope().isEmpty()) return;
          String scope = mc.getScope().get().toString();
          if (readLineVars.containsKey(scope)) {
            int line = s.getBegin().map(p -> p.line).orElse(-1);
            int col = s.getBegin().map(p -> p.column).orElse(-1);
            ctx.report(
                line,
                col,
                DEFAULT_RULE,
                "readLine() result '" + scope + "' dereferenced without null check",
                RuleDef.Severity.MEDIUM,
                scope);
          }
        }
      }
      if (s instanceof com.github.javaparser.ast.stmt.IfStmt) {
        seenIfNull = true;
      }
    }
  }
}
