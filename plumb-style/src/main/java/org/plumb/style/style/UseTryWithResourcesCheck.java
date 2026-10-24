package org.plumb.style.style;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** use try-with-resources instead of try-finally to close resources. */
public final class UseTryWithResourcesCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-use-try-with-resources", "USE_TRY_WITH_RESOURCES"),
          "use try-with-resources instead of try-finally to close resources",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of("CWE-400"),
          "org.plumb.style.UseTryWithResourcesCheck",
          true,
          List.of("ast-style-try-with-resources", "use-try-with-resources"));

  @Override
  public String name() {
    return "use-try-with-resources";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(TryStmt n, StyleContext ctx) {
    // Skip if the try already has a try-with-resources
    // resource list (Java 7+).
    if (!n.getResources().isEmpty()) return;
    n.getFinallyBlock()
        .ifPresent(
            fb -> {
              // Find close() calls in the finally block.
              Set<String> closedVars = new HashSet<>();
              for (MethodCallExpr m : fb.findAll(MethodCallExpr.class)) {
                if (!"close".equals(m.getNameAsString())) continue;
                if (m.getScope().isEmpty()) continue;
                if (m.getScope().get() instanceof NameExpr ne) {
                  closedVars.add(ne.getNameAsString());
                }
              }
              if (closedVars.isEmpty()) return;
              // Find the matching variable declarations in
              // the try body.
              for (String var : closedVars) {
                if (isDeclaredInTry(n.getTryBlock(), var)) {
                  int line = n.getBegin().map(p -> p.line).orElse(-1);
                  int col = n.getBegin().map(p -> p.column).orElse(-1);
                  ctx.report(
                      line,
                      col,
                      DEFAULT_RULE,
                      "Use try-with-resources instead of try-finally to close '" + var + "'",
                      RuleDef.Severity.LOW,
                      var);
                  return;
                }
              }
            });
  }

  private boolean isDeclaredInTry(BlockStmt tryBlock, String var) {
    for (VariableDeclarationExpr e : tryBlock.findAll(VariableDeclarationExpr.class)) {
      for (VariableDeclarator v : e.getVariables()) {
        if (var.equals(v.getNameAsString())) return true;
      }
    }
    return false;
  }
}
