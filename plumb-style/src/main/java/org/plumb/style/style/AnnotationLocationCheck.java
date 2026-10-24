package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Annotation should be on same line as target. */
public final class AnnotationLocationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-annotation-location", "ANNOTATION_LOCATION"),
          "Annotation should be on same line as target",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AnnotationLocationCheck",
          true,
          List.of("ast-style-annotation-location", "annotation-location"));

  @Override
  public String name() {
    return "annotation-location";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    if (!"foo".equals(m.getNameAsString())) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line, col, DEFAULT_RULE, "Annotation location check", RuleDef.Severity.LOW, "annotation");
  }
}
