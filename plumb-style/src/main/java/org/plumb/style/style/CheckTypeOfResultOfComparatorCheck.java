package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Check type of result of {@code Comparator.compare}. */
public final class CheckTypeOfResultOfComparatorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-comparator-result", "COMPARATOR_RESULT"),
          "Check type of result of Comparator.compare",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CheckTypeOfResultOfComparatorCheck",
          true,
          List.of("ast-style-comparator-result", "comparator-result")
      );

  @Override
  public String name() {
    return "comparator-result";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!"compare".equals(m.getNameAsString())) return;
    String t = m.getType().asString();
    if (!"int".equals(t) && !"Integer".equals(t)) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Check type of Comparator result",
        RuleDef.Severity.LOW,
        "compare");
  }
}