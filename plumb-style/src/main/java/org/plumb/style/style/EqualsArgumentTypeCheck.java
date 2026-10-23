package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** equals(Object) param type isn't java.lang.Object (CWE-697). */
public final class EqualsArgumentTypeCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-equals-arg-type", "EQUALS_ARG_TYPE"),
          "equals() argument type should be java.lang.Object",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-697"),
          "org.plumb.style.EqualsArgumentTypeCheck",
          true,
          List.of("ast-style-equals-arg-type", "equals-arg-type")
      );

  @Override
  public String name() {
    return "equals-arg-type";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!m.getNameAsString().equals("equals")) return;
    if (m.getParameters().size() != 1) return;
    Parameter p = m.getParameter(0);
    if (!p.getTypeAsString().equals("Object")) {
      int line = p.getBegin().map(b -> b.line).orElse(-1);
      int col = p.getBegin().map(b -> b.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "equals() argument type should be java.lang.Object, found "
              + p.getTypeAsString(),
          RuleDef.Severity.MEDIUM,
          p.getTypeAsString());
    }
  }
}