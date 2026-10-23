package org.plumb.style.style;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** instance method writes to static field (ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD). */
public final class StaticWriteFromInstanceCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-st-write-static-from-instance", "ST_WRITE_STATIC_FROM_INSTANCE"),
          "instance method writes to static field (ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-668"),
          "org.plumb.style.StaticWriteFromInstanceCheck",
          true,
          List.of("ast-style-write-to-static-from-instance-method", "st-write-static-from-instance")
      );

  @Override
  public String name() {
    return "st-write-static-from-instance";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(AssignExpr n, StyleContext ctx) {
    if (n.getOperator() == AssignExpr.Operator.ASSIGN) {
      // Plain assignment only : `+=` etc are not flagged
      // here (, but for clarity we narrow).
    }
    // The LHS must be a static field reference: `ClassName.x =...`
    // or a simple `x =...` resolved against a static field.
    if (n.getTarget() instanceof FieldAccessExpr fa) {
      // fa.getScope() must be a class-name NameExpr,
      // e.g. `MyClass.field =...`.
      if (fa.getScope() instanceof NameExpr ne) {
        String scope = ne.getNameAsString();
        if (scope.length() > 0 && Character.isUpperCase(scope.charAt(0))) {
          int line = n.getBegin().map(p -> p.line).orElse(-1);
          int col = n.getBegin().map(p -> p.column).orElse(-1);
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              "Static field '"
                  + scope
                  + "."
                  + fa.getNameAsString()
                  + "' assigned from instance context",
              RuleDef.Severity.MEDIUM,
              scope + "." + fa.getNameAsString());
        }
      }
    }
  }
}
