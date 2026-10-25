package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-580 (clone() without super.clone()).
 *
 * <p>Detects {@code public Object clone() { ... return new ...; }} where the body returns a {@code
 * new SomeClass()} instead of {@code super.clone()}.
 */
public final class ObjectModelCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-cwe580-clone-without-super", "CWE580_CLONE_WITHOUT_SUPER"),
          "clone() returns new without super.clone() (CWE-580).",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-580"),
          "org.plumb.style.ObjectModelCheck",
          true,
          List.of("ast-style-cwe580-clone-without-super-style", "cwe580-clone-without-super"));

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "cwe580-clone-without-super";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!"clone".equals(m.getNameAsString())) return;
    if (m.getParameters().size() != 0) return;
    BlockStmt body = m.getBody().orElse(null);
    if (body == null) return;
    boolean isBad = false;
    for (ReturnStmt rs : body.findAll(ReturnStmt.class)) {
      if (rs.getExpression().isEmpty()) continue;
      com.github.javaparser.ast.expr.Expression e = rs.getExpression().get();
      if (e instanceof ObjectCreationExpr) {
        isBad = true;
        break;
      }
      if (e instanceof com.github.javaparser.ast.expr.NameExpr ne) {
        String varName = ne.getNameAsString();
        if (isLocalAssignedToNew(body, varName)) {
          isBad = true;
          break;
        }
      }
    }
    if (!isBad) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    String key = "cwe580|" + m.getNameAsString() + "|" + line;
    if (reported.add(key)) {
      ctx.report(
          line,
          -1,
          DEFAULT_RULE,
          "clone() in '"
              + m.getNameAsString()
              + "' returns a freshly-allocated object instead of"
              + " super.clone() (CWE-580).",
          RuleDef.Severity.MEDIUM,
          "cwe580@clone-line " + line);
    }
  }

  private static boolean isLocalAssignedToNew(BlockStmt body, String varName) {
    final boolean[] found = {false};
    body.walk(
        com.github.javaparser.ast.expr.VariableDeclarationExpr.class,
        vde -> {
          for (var v : vde.getVariables()) {
            if (varName.equals(v.getNameAsString())
                && v.getInitializer().isPresent()
                && v.getInitializer().get() instanceof ObjectCreationExpr) {
              found[0] = true;
            }
          }
        });
    body.walk(
        com.github.javaparser.ast.expr.AssignExpr.class,
        ae -> {
          if (found[0]) return;
          if (ae.getTarget() instanceof com.github.javaparser.ast.expr.NameExpr ne
              && varName.equals(ne.getNameAsString())
              && ae.getValue() instanceof ObjectCreationExpr) {
            found[0] = true;
          }
        });
    return found[0];
  }
}
