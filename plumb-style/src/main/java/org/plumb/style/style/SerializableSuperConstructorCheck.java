package org.plumb.style.style;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Serializable super constructor. */
public final class SerializableSuperConstructorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-serializablesuperconstructor", "SERIALIZABLESUPERCONSTRUCTOR"),
          "Serializable super constructor",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SerializableSuperConstructorCheck",
          true,
          List.of("ast-style-serializable-super-ctor", "serializablesuperconstructor"));

  @Override
  public String name() {
    return "serializablesuperconstructor";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ConstructorDeclaration c, StyleContext ctx) {
    if (c.getBody().getStatements().isEmpty()) {
      int line = c.getBegin().map(p -> p.line).orElse(-1);
      int col = c.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Serializable super constructor",
          RuleDef.Severity.MEDIUM,
          c.getNameAsString());
    }
  }
}
