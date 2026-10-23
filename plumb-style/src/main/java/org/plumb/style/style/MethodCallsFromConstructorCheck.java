package org.plumb.style.style;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** constructor calls overridable method. */
public final class MethodCallsFromConstructorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-ctor-method", "CTOR_METHOD"),
          "constructor calls overridable method",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-665"),
          "org.plumb.style.MethodCallsFromConstructorCheck",
          true,
          List.of("ast-style-method-call", "ctor-method")
      );

  @Override
  public String name() {
    return "ctor-method";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ConstructorDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    for (MethodCallExpr m : n.findAll(MethodCallExpr.class)) {
      // Skip `this(...)` and `super(...)` invocations.
      if (m.getScope().isEmpty()) continue;
      if (!(m.getScope().get() instanceof ThisExpr)) continue;
      int line = m.getBegin().map(p -> p.line).orElse(-1);
      int col = m.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Constructor calls overridable method '" + m.getNameAsString() + "'",
          RuleDef.Severity.MEDIUM,
          m.getNameAsString());
    }
  }
}
