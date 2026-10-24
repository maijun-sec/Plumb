package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** class name is too short (limit 3). */
public final class ShortClassNameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-short-class", "SHORT_CLASS"),
          "class name is too short (limit 3)",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.ShortClassNameCheck",
          true,
          List.of("ast-naming-class-name", "short-class"));

  @Override
  public String name() {
    return "short-class";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    String name = n.getNameAsString();
    if (name.length() >= 3) return;
    // Allow common abbreviations.
    if (name.equals("IO")
        || name.equals("UI")
        || name.equals("UI")
        || name.equals("DB")
        || name.equals("OS")) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class name '" + name + "' is too short",
        RuleDef.Severity.LOW,
        name);
  }
}
