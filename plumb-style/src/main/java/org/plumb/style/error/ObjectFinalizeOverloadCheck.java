package org.plumb.style.error;

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** {@code finalize()} should not be overloaded (i.e., should take no parameters). */
public final class ObjectFinalizeOverloadCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-object-finalize-overload", "OBJECT_FINALIZE_OVERLOAD"),
          "finalize() should not be overloaded",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ObjectFinalizeOverloadCheck",
          true,
          List.of("ast-error-object-finalize-overload", "object-finalize-overload"));

  @Override
  public String name() {
    return "object-finalize-overload";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!"finalize".equals(m.getNameAsString())) return;
    if (m.getParameters().isEmpty()) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Overloaded finalize() method; should be the no-arg form",
        RuleDef.Severity.LOW,
        m.getNameAsString());
  }
}
