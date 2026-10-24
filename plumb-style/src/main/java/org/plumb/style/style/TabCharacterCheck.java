package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid tab characters in source files. */
public final class TabCharacterCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-tab-character", "TAB_CHARACTER"),
          "avoid tab characters in source files",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.TabCharacterCheck",
          true,
          List.of("ast-style-character", "tab-character"));

  @Override
  public String name() {
    return "tab-character";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    // Iterate the raw text by source range.
    n.getRange()
        .ifPresent(
            r -> {
              // We don't have direct access to the raw text;
              // use the toString of the unit and look for tabs.
              String src = n.toString();
              int idx = src.indexOf('\t');
              if (idx >= 0) {
                ctx.report(
                    -1,
                    -1,
                    DEFAULT_RULE,
                    "Tab character found in source : use spaces for indentation",
                    RuleDef.Severity.LOW,
                    "tab");
              }
            });
  }
}
