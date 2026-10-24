package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** outer type number (only one outer type per file). */
public final class OuterTypeNumberCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-outertypenumber", "OUTERTYPENUMBER"),
          "outer type number (only one outer type per file)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.OuterTypeNumberCheck",
          true,
          List.of("ast-style-type-number", "outertypenumbercheck"));

  @Override
  public String name() {
    return "outertypenumber";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    int topLevel =
        (int)
            n.getTypes().stream()
                .filter(
                    t ->
                        !(t instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration)
                            || !((com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) t)
                                .isNestedType())
                .count();
    if (topLevel > 1) {
      int line = 1, col = 1;
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "File has " + topLevel + " top-level types; expected 1",
          RuleDef.Severity.LOW,
          "file");
    }
  }
}
