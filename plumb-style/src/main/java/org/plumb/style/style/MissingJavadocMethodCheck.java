package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Missing Javadoc on public method. */
public final class MissingJavadocMethodCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-missingjavadocmethod", "MISSINGJAVADOCMETHOD"),
          "Missing Javadoc on public method",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MissingJavadocMethodCheck",
          true,
          List.of("ast-style-missing-javadoc-method", "missingjavadocmethod"));

  @Override
  public String name() {
    return "missingjavadocmethod";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (m.isPublic() && m.getJavadoc().isEmpty()) {
      int line = m.getBegin().map(p -> p.line).orElse(-1);
      int col = m.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Missing Javadoc on public method: " + m.getNameAsString(),
          RuleDef.Severity.LOW,
          m.getNameAsString());
    }
  }
}
