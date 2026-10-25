package org.plumb.style.error;

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Throwing raw {@code Exception} / {@code RuntimeException} / {@code Throwable}. */
public final class RawExceptionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-raw-exception", "RAW_EXCEPTION"),
          "throwing raw Exception/RuntimeException",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.RawExceptionCheck",
          true,
          List.of("ast-error-exception", "raw-exception"));

  @Override
  public String name() {
    return "raw-exception";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr oc, StyleContext ctx) {
    if (!(oc.getType() instanceof ClassOrInterfaceType)) return;
    ClassOrInterfaceType t = (ClassOrInterfaceType) oc.getType();
    String name = t.getNameAsString();
    if (!"Exception".equals(name) && !"RuntimeException".equals(name) && !"Throwable".equals(name))
      return;
    int line = oc.getBegin().map(p -> p.line).orElse(-1);
    int col = oc.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Throwing raw " + name + "; use a domain-specific subclass",
        RuleDef.Severity.LOW,
        name);
  }
}
