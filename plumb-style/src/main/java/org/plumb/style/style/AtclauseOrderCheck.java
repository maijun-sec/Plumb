package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.javadoc.Javadoc;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** {@code @return} before {@code @param} in Javadoc (heuristic). */
public final class AtclauseOrderCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-atclause-order", "ATCLAUSE_ORDER"),
          "@return before @param in Javadoc (heuristic)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AtclauseOrderCheck",
          true,
          List.of("ast-style-atclause-order", "atclause-order"));

  @Override
  public String name() {
    return "atclause-order";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    Javadoc jd = m.getJavadoc().orElse(null);
    if (jd == null) return;
    String text = jd.toText();
    int paramIdx = text.indexOf("@param");
    int returnIdx = text.indexOf("@return");
    if (paramIdx < 0 || returnIdx < 0 || returnIdx >= paramIdx) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "@return should come after @param in Javadoc",
        RuleDef.Severity.LOW,
        m.getNameAsString());
  }
}
