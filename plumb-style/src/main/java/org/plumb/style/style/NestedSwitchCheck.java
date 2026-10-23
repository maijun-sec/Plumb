package org.plumb.style.style;

import com.github.javaparser.ast.stmt.SwitchStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** switch nested inside another switch. */
public final class NestedSwitchCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-nested-switch", "NESTED_SWITCH"),
          "switch nested inside another switch",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NestedSwitchCheck",
          true,
          List.of("ast-style-nested-switch", "nested-switch")
      );

  @Override
  public String name() {
    return "nested-switch";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SwitchStmt n, StyleContext ctx) {
    // If any ancestor is also a SwitchStmt, this is nested.
    com.github.javaparser.ast.Node parent = n.getParentNode().orElse(null);
    while (parent != null) {
      if (parent instanceof SwitchStmt) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Switch nested inside another switch : extract to a method",
            RuleDef.Severity.LOW,
            "switch");
        return;
      }
      if (parent instanceof com.github.javaparser.ast.body.MethodDeclaration
          || parent instanceof com.github.javaparser.ast.body.ConstructorDeclaration) {
        return;
      }
      parent = parent.getParentNode().orElse(null);
    }
  }
}
