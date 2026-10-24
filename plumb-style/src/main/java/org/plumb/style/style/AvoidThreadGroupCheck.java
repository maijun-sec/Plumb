package org.plumb.style.style;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** avoid ThreadGroup (use Executor instead). */
public final class AvoidThreadGroupCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-avoid-threadgroup", "AVOID_THREADGROUP"),
          "avoid ThreadGroup (use Executor instead)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.AvoidThreadGroupCheck",
          true,
          List.of("ast-style-threadgroup", "avoid-threadgroup"));

  @Override
  public String name() {
    return "avoid-threadgroup";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceType n, StyleContext ctx) {
    if (!"ThreadGroup".equals(n.getNameAsString())) return;
    // Skip import declarations and static field
    // declarations where the name is a reference.
    Node parent = n.getParentNode().orElse(null);
    if (parent == null) return;
    if (parent instanceof com.github.javaparser.ast.ImportDeclaration) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Avoid ThreadGroup : use Executor framework",
        RuleDef.Severity.MEDIUM,
        "ThreadGroup");
  }
}
