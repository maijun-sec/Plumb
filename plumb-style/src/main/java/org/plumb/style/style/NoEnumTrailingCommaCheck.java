package org.plumb.style.style;

import com.github.javaparser.ast.body.EnumDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Enum should not have trailing comma. */
public final class NoEnumTrailingCommaCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-noenumtrailingcomma", "NOENUMTRAILINGCOMMA"),
          "Enum should not have trailing comma",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NoEnumTrailingCommaCheck",
          true,
          List.of("ast-style-enum-trailing-comma", "noenumtrailingcomma"));

  @Override
  public String name() {
    return "noenumtrailingcomma";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(EnumDeclaration e, StyleContext ctx) {
    String s = e.toString();
    if (s.contains(",\n}")) {
      int line = e.getBegin().map(p -> p.line).orElse(-1);
      int col = e.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Enum should not have trailing comma",
          RuleDef.Severity.LOW,
          e.getNameAsString());
    }
  }
}
