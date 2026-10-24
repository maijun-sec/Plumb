package org.plumb.style.style;

import com.github.javaparser.ast.body.EnumDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Enum is a class; consider if it has too many methods. */
public final class AvoidEnumsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-avoid-enums", "AVOID_ENUMS"),
          "enum is a class; consider if it has too many methods",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AvoidEnumsCheck",
          true,
          List.of("ast-style-enum", "avoid-enums"));

  @Override
  public String name() {
    return "avoid-enums";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(EnumDeclaration e, StyleContext ctx) {
    // Heuristic: count methods in enum, fire if > 5.
    if (e.getMethods().size() <= 5) return;
    int line = e.getBegin().map(p -> p.line).orElse(-1);
    int col = e.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Enum " + e.getNameAsString() + " has many methods; consider refactor",
        RuleDef.Severity.LOW,
        e.getNameAsString());
  }
}
