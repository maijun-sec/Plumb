package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Annotation should be on same line as target. */
public final class AnnotationOnSameLineCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-annotation-on-same-line", "ANNOTATION_ON_SAME_LINE"),
          "Annotation should be on same line as target",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AnnotationOnSameLineCheck",
          true,
          List.of("ast-style-annotation-on-same-line", "annotation-on-same-line")
      );

  @Override
  public String name() {
    return "annotation-on-same-line";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!m.getAnnotations().isNonEmpty()) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Annotation should be on same line as target",
        RuleDef.Severity.LOW,
        m.getNameAsString());
  }
}