package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** JUnit assertion should include a failure message. */
public final class JUnitAssertionsShouldIncludeMessageCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-junit-message", "JUNIT_MESSAGE"),
          "JUnit assertion should include a failure message",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.JUnitAssertionsShouldIncludeMessageCheck",
          true,
          List.of("ast-style-assertions-should-include-message", "junit-message"));

  @Override
  public String name() {
    return "junit-message";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"assertEquals".equals(n.getNameAsString())
        && !"assertTrue".equals(n.getNameAsString())
        && !"assertFalse".equals(n.getNameAsString())
        && !"assertNotNull".equals(n.getNameAsString())
        && !"assertNull".equals(n.getNameAsString())
        && !"assertSame".equals(n.getNameAsString())
        && !"assertNotSame".equals(n.getNameAsString())) return;
    // Look at the LAST argument; if it's a String
    // literal, there's a message.
    if (n.getArguments().isEmpty()) return;
    var last = n.getArgument(n.getArguments().size() - 1);
    if (last instanceof StringLiteralExpr) return;
    // Heuristic: 1-arg assertTrue/assertFalse
    // commonly have just the boolean; skip.
    if (n.getArguments().size() == 1
        && ("assertTrue".equals(n.getNameAsString()) || "assertFalse".equals(n.getNameAsString())))
      return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        n.getNameAsString() + "() should include a failure message",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}
