package org.plumb.style.style;

import com.github.javaparser.ast.body.EnumDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** enum should not have abstract methods. */
public final class EnumAbstractMethodCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-enumabstractmethod", "ENUMABSTRACTMETHOD"),
          "enum should not have abstract methods",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.EnumAbstractMethodCheck",
          true,
          List.of("ast-style-abstract-method", "enumabstractmethodcheck"));

  @Override
  public String name() {
    return "enumabstractmethod";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(EnumDeclaration e, StyleContext ctx) {
    for (com.github.javaparser.ast.body.MethodDeclaration m : e.getMethods()) {
      if (m.isAbstract()) {
        int line = m.getBegin().map(p -> p.line).orElse(-1);
        int col = m.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Abstract method in enum: " + m.getNameAsString(),
            RuleDef.Severity.MEDIUM,
            m.getNameAsString());
      }
    }
  }
}
