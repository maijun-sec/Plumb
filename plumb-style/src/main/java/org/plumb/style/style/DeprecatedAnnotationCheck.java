package org.plumb.style.style;

import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * {@code @Deprecated} should declare {@code since} / {@code forRemoval}.
 *
 * <p>Only marker annotations (no arguments) fire; single-member or normal-annotation forms are
 * assumed to carry the required metadata.
 */
public final class DeprecatedAnnotationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-deprecated-annotation", "DEPRECATED_ANNOTATION"),
          "@Deprecated should declare since / forRemoval",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DeprecatedAnnotationCheck",
          true,
          List.of("ast-style-deprecated-annotation", "deprecated-annotation"));

  @Override
  public String name() {
    return "deprecated-annotation";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MarkerAnnotationExpr ann, StyleContext ctx) {
    if (!"Deprecated".equals(ann.getNameAsString())) return;
    int line = ann.getBegin().map(p -> p.line).orElse(-1);
    int col = ann.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "@Deprecated should include since=\"...\" or forRemoval=true",
        RuleDef.Severity.LOW,
        "Deprecated");
  }
}
