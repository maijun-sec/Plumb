package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Missing {@code @author} / {@code @version} Javadoc tag on a type declaration. */
public final class AnnoTblCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-annotation-table", "ANNOTATION_TABLE"),
          "@author/@version tag is missing",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AnnoTblCheck",
          false,
          List.of("ast-style-annotation-table", "annotation-table")
      ); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832 baseline)

  @Override
  public String name() {
    return "annotation-table";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    if (!c.getJavadoc().isEmpty() && c.getJavadoc().get().toText().contains("@author")) return;
    int line = c.getBegin().map(p -> p.line).orElse(-1);
    int col = c.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class missing @author Javadoc tag: " + c.getNameAsString(),
        RuleDef.Severity.LOW,
        c.getNameAsString());
  }
}