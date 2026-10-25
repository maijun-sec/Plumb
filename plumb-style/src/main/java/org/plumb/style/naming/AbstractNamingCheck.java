package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Abstract class should be named starting with {@code Abstract} (heuristic). */
public final class AbstractNamingCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-abstract-naming", "ABSTRACT_NAMING"),
          "abstract class should be named starting with Abstract (heuristic)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AbstractNamingCheck",
          true,
          List.of("ast-naming-abstract-naming", "abstract-naming"));

  @Override
  public String name() {
    return "abstract-naming";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    if (!c.isAbstract()) return;
    if (c.getNameAsString().toLowerCase().startsWith("abstract")) return;
    int line = c.getBegin().map(p -> p.line).orElse(-1);
    int col = c.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Abstract class should be named Abstract" + c.getNameAsString(),
        RuleDef.Severity.LOW,
        c.getNameAsString());
  }
}
