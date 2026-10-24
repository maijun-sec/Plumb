package org.plumb.style.error;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ReferenceType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Declared exception is not thrown by the method.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `ignoreUnchecked` | BOOLEAN | `true` | Skip RuntimeException and subclasses |
 */
public final class UnusedThrowsCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "ignoreUnchecked",
        "Skip RuntimeException and subclasses",
        RuleParameter.Type.BOOLEAN,
        "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-unused-throws", "UNUSED_THROWS"),
          "Declared exception is not thrown by the method",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnusedThrowsCheck",
          true,
          List.of("ast-error-unused-throws", "unused-throws"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "unused-throws";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    checkThrows(
        n.getThrownExceptions(),
        n.getNameAsString(),
        n.findAll(com.github.javaparser.ast.stmt.ThrowStmt.class),
        ctx);
  }

  @Override
  public void visit(ConstructorDeclaration n, StyleContext ctx) {
    checkThrows(
        n.getThrownExceptions(),
        n.getNameAsString(),
        n.findAll(com.github.javaparser.ast.stmt.ThrowStmt.class),
        ctx);
  }

  private void checkThrows(
      java.util.List<ReferenceType> thrown,
      String methodName,
      java.util.List<com.github.javaparser.ast.stmt.ThrowStmt> throws_,
      StyleContext ctx) {
    if (thrown.isEmpty()) return;
    boolean ignoreUnchecked = paramBool(ctx, "ignoreUnchecked", true);
    // Collect the type names actually thrown via `throw new XYZ(...)`.
    Set<String> thrownTypeNames = new HashSet<>();
    for (com.github.javaparser.ast.stmt.ThrowStmt ts : throws_) {
      com.github.javaparser.ast.expr.Expression e = ts.getExpression();
      if (e == null) continue;
      if (e instanceof com.github.javaparser.ast.expr.ObjectCreationExpr oc) {
        thrownTypeNames.add(oc.getType().getNameAsString());
      } else if (e instanceof com.github.javaparser.ast.expr.NameExpr ne) {
        thrownTypeNames.add(ne.getNameAsString());
      }
    }
    for (ReferenceType t : thrown) {
      String typeName = t.toString();
      if (ignoreUnchecked && isUnchecked(typeName)) continue;
      // If the method throws a "no-arg" call to this type's constructor,
      // mark as used.
      if (thrownTypeNames.contains(typeName)) continue;
      // Heuristic: also accept if the method body calls a method that
      // might throw. We can't know without resolution, so we report.
      int line = t.getBegin().map(p -> p.line).orElse(-1);
      int col = t.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Method '"
              + methodName
              + "' declares throws '"
              + typeName
              + "' but does not explicitly throw it",
          RuleDef.Severity.LOW,
          typeName);
    }
  }

  private static boolean isUnchecked(String typeName) {
    return typeName.equals("RuntimeException")
        || typeName.equals("Error")
        || typeName.endsWith("RuntimeException")
        || typeName.endsWith("Error");
  }

  private static boolean paramBool(StyleContext ctx, String name, boolean def) {
    Boolean override = ctx.paramOverride(name, Boolean.class);
    if (override != null) return override;
    for (RuleParameter p : PARAMS) {
      if (p.name().equals(name)) return p.boolDefault();
    }
    return def;
  }
}
