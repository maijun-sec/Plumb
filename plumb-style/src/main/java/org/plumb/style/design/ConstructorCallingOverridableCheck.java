/*
 * constructor calls overridable method.
 *
 * <p><b>R-818 (2026-10-14): pattern D rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.findAll(ConstructorDeclaration)} (1 walk) + for each
 * ctor {@code ctor.findAll(MethodCallExpr)} (1 walk per ctor).
 * The new implementation uses {@code visit(MethodCallExpr)}
 * dispatched by {@code AstDispatcher} + a findAncestor check
 * to confirm the call is inside a constructor. Total: 1 walk
 * per file (was 1 + N_ctor walks).</p>
 */
package org.plumb.style.design;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class ConstructorCallingOverridableCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-ctor-calling-overridable", "CTOR_CALLING_OVERRIDABLE"),
          "constructor calls overridable method",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-688"),
          "org.plumb.style.ConstructorCallingOverridableCheck",
          true,
          List.of("ast-design-ctor-calling-overridable", "ctor-calling-overridable"));

  @Override
  public String name() {
    return "ctor-calling-overridable";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr mc, StyleContext ctx) {
    // Find the enclosing constructor. If the call is a
    // bare-name call inside a constructor body, the
    // constructor's name and the call's name should match.
    ConstructorDeclaration ctor = mc.findAncestor(ConstructorDeclaration.class).orElse(null);
    if (ctor == null) return;
    String ctorName = ctor.getNameAsString();
    if (!mc.getNameAsString().equals(ctorName)) return;
    int line = mc.getBegin().map(p -> p.line).orElse(-1);
    int col = mc.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Constructor calls "
            + ctorName
            + "() (explicit this/super ctor chain is fine, but bare name is suspicious)",
        RuleDef.Severity.MEDIUM,
        ctorName);
  }
}
