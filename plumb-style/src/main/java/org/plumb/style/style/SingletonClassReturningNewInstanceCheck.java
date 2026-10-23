package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** utility class method should not return new instance. */
public final class SingletonClassReturningNewInstanceCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-singleton-returning-new", "SINGLETON_RETURNING_NEW"),
          "utility class method should not return new instance",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.SingletonClassReturningNewInstanceCheck",
          false,
          List.of("ast-style-singleton-returning-new", "singleton-returning-new")
      ); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832 baseline)

  @Override
  public String name() {
    return "singleton-returning-new";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    // Only fire for static methods.
    boolean isStatic =
        n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
    if (!isStatic) return;
    String returnType = n.getType().toString();
    // Look for `return new...` in the body.
    n.findAll(ObjectCreationExpr.class)
        .forEach(
            oc -> {
              // Use asString() to get the full type name
              // (e.g. "java.lang.String" not "String").
              String ctorType = oc.getType().asString();
              if (!returnType.equals(ctorType)) {
                int line = oc.getBegin().map(p -> p.line).orElse(-1);
                int col = oc.getBegin().map(p -> p.column).orElse(-1);
                ctx.report(
                    line,
                    col,
                    DEFAULT_RULE,
                    "Utility method returns new instance of " + ctorType,
                    RuleDef.Severity.LOW,
                    ctorType);
              }
            });
  }
}
