package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Missing Javadoc on public type. */
public final class MissingJavadocTypeCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-missingjavadoctype", "MISSINGJAVADOCTYPE"),
          "Missing Javadoc on public type",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MissingJavadocTypeCheck",
          false,
          List.of(
              "ast-style-missing-javadoc-type",
              "missingjavadoctype")); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832

  // Disabled by default: this rule reports a style preference rather than a defect.

  @Override
  public String name() {
    return "missingjavadoctype";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    if (c.isPublic() && c.getJavadoc().isEmpty()) {
      int line = c.getBegin().map(p -> p.line).orElse(-1);
      int col = c.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Missing Javadoc on public type: " + c.getNameAsString(),
          RuleDef.Severity.LOW,
          c.getNameAsString());
    }
  }
}
