package org.plumb.style.style;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** field should declare an explicit visibility modifier. */
public final class VisibilityModifierCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-visibility-modifier", "VISIBILITY_MODIFIER"),
          "field should declare an explicit visibility modifier",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.VisibilityModifierCheck",
          true,
          List.of("ast-style-visibility-modifier", "visibility-modifier")
      );

  @Override
  public String name() {
    return "visibility-modifier";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    boolean hasVisibility =
        n.getModifiers().stream()
            .anyMatch(
                m ->
                    m.getKeyword() == Modifier.Keyword.PUBLIC
                        || m.getKeyword() == Modifier.Keyword.PRIVATE
                        || m.getKeyword() == Modifier.Keyword.PROTECTED);
    if (hasVisibility) return;
    // Interfaces allow public-by-default fields;
    // skip those.
    if (n.getParentNode().isPresent()
        && n.getParentNode().get()
            instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cid
        && cid.isInterface()) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Field should declare an explicit visibility (private / protected / public)",
        RuleDef.Severity.LOW,
        "field");
  }
}
