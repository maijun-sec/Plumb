package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Badly overridden adapter. */
public final class BadlyOverriddenAdapterCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-badly-overridden-adapter", "BADLY_OVERRIDDEN_ADAPTER"),
          "Badly overridden adapter",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.BadlyOverriddenAdapterCheck",
          true,
          List.of("ast-style-overridden-adapter", "badly-overridden-adapter")
      );

  @Override
  public String name() {
    return "badly-overridden-adapter";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!m.getNameAsString().startsWith("mouse")) return;
    if (m.getParameters().size() != 1) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Badly overridden adapter",
        RuleDef.Severity.LOW,
        m.getNameAsString());
  }
}