package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Javadoc paragraphs should be separated by single
 *
 * <p>.
 */
public final class JavadocParagraphCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-javadocparagraph", "JAVADOCPARAGRAPH"),
          "Javadoc paragraphs should be separated by single <p>",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.JavadocParagraphCheck",
          true,
          List.of("ast-style-javadoc-paragraph", "javadocparagraph"));

  @Override
  public String name() {
    return "javadocparagraph";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (m.getJavadoc().isPresent()) {
      String jd = m.getJavadoc().get().toText();
      if (jd.contains("<p><p>")) {
        int line = m.getBegin().map(p -> p.line).orElse(-1);
        int col = m.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Javadoc paragraphs should be separated by single <p>",
            RuleDef.Severity.LOW,
            m.getNameAsString());
      }
    }
  }
}
